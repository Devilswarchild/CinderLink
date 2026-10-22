package dev.devilswarchild.cinderlink.core.dht;

import dev.devilswarchild.cinderlink.core.identity.Keys;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal, read-only Mainline DHT client: enough of BEP 5 to find nodes, plus BEP 44 get/put for small
 * signed records. "Read-only" (BEP 43) means we only send queries. We never answer, never store other
 * people's data, and other nodes don't add us to their routing tables.
 *
 * Blocking methods ({@link #get}, {@link #put}) must be called off the game thread.
 */
public final class DhtClient implements DhtStore, AutoCloseable {
    public static final List<InetSocketAddress> DEFAULT_BOOTSTRAP = List.of(
            InetSocketAddress.createUnresolved("router.bittorrent.com", 6881),
            InetSocketAddress.createUnresolved("dht.transmissionbt.com", 6881),
            InetSocketAddress.createUnresolved("router.utorrent.com", 6881),
            InetSocketAddress.createUnresolved("dht.libtorrent.org", 25401));

    static final int K = 8;
    static final int ALPHA = 6;
    static final int MAX_QUERIES = 80;
    static final long QUERY_TIMEOUT_MS = 600;
    /** Fast path: ask the nodes we already know hold a record, instead of searching the whole DHT again. */
    static final long FAST_TIMEOUT_MS = 800;
    /** How long a remembered route is trusted before a full search refreshes it. */
    static final long ROUTE_TTL_MS = 120_000;
    /** BEP 44 write tokens are typically valid 5-10 minutes; reuse them for at most this long. */
    static final long TOKEN_TTL_MS = 240_000;
    static final long LOOKUP_TIMEOUT_MS = 10_000;
    /** Records are stored on (and read from) this many of the closest nodes, so writer and reader overlap. */
    static final int STORE_NODES = 12;
    /** A store counts as done once this many nodes confirm it; the others finish in the background. */
    static final int PUT_CONFIRMATIONS = 3;
    /** Routes in active use are renewed in the background once they are this old, well before they expire. */
    static final long REFRESH_AT_MS = 75_000;
    /** A record counts as "in use" (and is kept warm) while it was read or written this recently. */
    static final long HOT_FOR_MS = 10 * 60_000;
    /** How often the background renewal looks for routes to renew. */
    static final long MAINTAIN_EVERY_MS = 3_000;
    /** At most this many background renewals at once, so they never crowd out real work. */
    static final int MAX_REFRESHING = 3;
    private static final int MAX_KNOWN = 256;

    private final DatagramSocket socket;
    private final byte[] myId = Keys.randomBytes(20);
    private final List<InetSocketAddress> bootstrap;
    private final boolean allowLocal;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, DhtNode> known = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<InetSocketAddress, DhtNode> e) {
                    return size() > MAX_KNOWN;
                }
            });
    private final AtomicInteger tid = new AtomicInteger(new java.security.SecureRandom().nextInt());
    private final Thread receiver;
    private volatile boolean closed;

    private record Pending(InetSocketAddress to, CompletableFuture<Map<String, Object>> future) {}

    /** Error reply from a node, e.g. 302 "sequence number less than current". */
    public static final class DhtError extends Exception {
        public final long code;

        DhtError(long code, String msg) {
            super("DHT error " + code + ": " + msg);
            this.code = code;
        }
    }

    public DhtClient(List<InetSocketAddress> bootstrap, boolean allowLocal) throws SocketException {
        this.bootstrap = List.copyOf(bootstrap);
        this.allowLocal = allowLocal;
        this.socket = new DatagramSocket(0);
        this.receiver = new Thread(this::receiveLoop, "CinderLink DHT");
        receiver.setDaemon(true);
        receiver.start();
        maintainer.scheduleWithFixedDelay(this::maintain, MAINTAIN_EVERY_MS, MAINTAIN_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Joins the public Mainline DHT. For local testing only, {@code -Dcinderlink.dhtBootstrap=host:port,...}
     * points at a private test network instead.
     */
    public static DhtClient start() throws SocketException {
        String override = System.getProperty("cinderlink.dhtBootstrap");
        if (override != null && !override.isBlank()) {
            List<InetSocketAddress> nodes = new ArrayList<>();
            for (String part : override.split(",")) {
                int colon = part.lastIndexOf(':');
                if (colon > 0) nodes.add(new InetSocketAddress(part.substring(0, colon).trim(),
                        Integer.parseInt(part.substring(colon + 1).trim())));
            }
            return new DhtClient(nodes, true);
        }
        return new DhtClient(DEFAULT_BOOTSTRAP, false);
    }

    // ---------------------------------------------------------------- public API

    /** Latest valid record for (publicKey, salt), or empty if none is found. */
    @Override
    public Optional<MutableItem> get(byte[] publicKey, byte[] salt) {
        return get(publicKey, salt, ROUTE_TTL_MS, false);
    }

    @Override
    public void warm(byte[] publicKey, byte[] salt) {
        byte[] target = MutableItem.target(publicKey, salt);
        String key = HexFormat.of().formatHex(target);
        touch(key, publicKey, salt);
        remember(key, shared(key, target, publicKey, salt, null));
    }

    @Override
    public Optional<MutableItem> get(byte[] publicKey, byte[] salt, long maxRouteAgeMs, boolean firstValueWins) {
        byte[] target = MutableItem.target(publicKey, salt);
        String key = HexFormat.of().formatHex(target);
        if (!firstValueWins) touch(key, publicKey, salt); // one-off lookups (friend codes) aren't kept warm
        Route route = routes.get(key);
        if (route != null && System.currentTimeMillis() - route.foundAt < Math.min(maxRouteAgeMs, ROUTE_TTL_MS)) {
            FastResult fast = fastGet(route, target, publicKey, salt);
            if (fast.answered >= Math.min(2, route.nodes.size())) {
                stats.fastGets.incrementAndGet();
                routes.put(key, new Route(fast.refreshed, route.foundAt));
                return fast.best;
            }
        }
        if (firstValueWins) {
            // An early-stopped search hasn't found the true closest nodes, so it isn't shared or remembered.
            stats.fullLookups.incrementAndGet();
            return lookup(target, publicKey, salt, true).best;
        }
        Lookup l = shared(key, target, publicKey, salt, null);
        remember(key, l);
        return l.best;
    }

    @Override
    public String routeInfo(byte[] publicKey, byte[] salt) {
        Route r = routes.get(HexFormat.of().formatHex(MutableItem.target(publicKey, salt)));
        String key = HexFormat.of().formatHex(MutableItem.target(publicKey, salt));
        Long landed = landedAfter.remove(key);
        String l = landed == null ? "" : "; landed on the closest nodes " + landed + "ms after the store began";
        if (r == null) return "no remembered route" + l;
        return "route of " + r.nodes.size() + " nodes, " + (System.currentTimeMillis() - r.foundAt) / 1000 + "s old" + l;
    }

    // ---------------------------------------------------------------- remembered routes

    /** The nodes closest to one record (with write tokens), found by the last full search. */
    private record Route(List<Responder> nodes, long foundAt) {}

    private final Map<String, Route> routes = new ConcurrentHashMap<>();

    /** A record we're actively using: keep its route fresh in the background. */
    private record Hot(byte[] publicKey, byte[] salt, long touchedAt) {}

    private final Map<String, Hot> hot = new ConcurrentHashMap<>();
    /** {@link #REFRESH_AT_MS}, adjustable so tests don't have to wait over a minute. */
    volatile long refreshAtMs = REFRESH_AT_MS;
    private final Map<String, Boolean> refreshing = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService maintainer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CinderLink DHT upkeep");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.ExecutorService refresher = Executors.newFixedThreadPool(MAX_REFRESHING, r -> {
        Thread t = new Thread(r, "CinderLink DHT refresh");
        t.setDaemon(true);
        return t;
    });

    private void touch(String key, byte[] publicKey, byte[] salt) {
        hot.put(key, new Hot(publicKey, salt, System.currentTimeMillis()));
        if (hot.size() > 256) hot.clear(); // bounded; whatever is still in use comes straight back
    }

    /**
     * Background upkeep: renews the route of every record in use before it expires, so reads and writes
     * (Accept, start hosting, a new request) always take the quick one-round-trip path.
     */
    private void maintain() {
        if (closed) return;
        try {
            long now = System.currentTimeMillis();
            hot.entrySet().removeIf(e -> now - e.getValue().touchedAt > HOT_FOR_MS);
            // Oldest routes first; missing routes count as oldest.
            List<Map.Entry<String, Hot>> due = new ArrayList<>();
            for (var e : hot.entrySet()) {
                Route r = routes.get(e.getKey());
                if ((r == null || now - r.foundAt >= refreshAtMs) && !refreshing.containsKey(e.getKey())) due.add(e);
            }
            due.sort(java.util.Comparator.comparingLong(e -> {
                Route r = routes.get(e.getKey());
                return r == null ? 0 : r.foundAt;
            }));
            for (var e : due) {
                if (refreshing.size() >= MAX_REFRESHING) break;
                String key = e.getKey();
                Hot h = e.getValue();
                refreshing.put(key, Boolean.TRUE);
                refresher.execute(() -> {
                    try {
                        if (closed) return;
                        stats.renewals.incrementAndGet();
                        remember(key, shared(key, MutableItem.target(h.publicKey, h.salt), h.publicKey, h.salt, null));
                    } catch (RuntimeException ignored) {
                        // next round tries again
                    } finally {
                        refreshing.remove(key);
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // upkeep must never stop
        }
    }

    private void remember(String key, Lookup l) {
        List<Responder> storing = l.responders.stream().filter(r -> r.token != null).limit(STORE_NODES).toList();
        if (storing.size() >= 2) routes.put(key, new Route(storing, System.currentTimeMillis()));
        else routes.remove(key);
        if (routes.size() > 512) routes.clear(); // bounded; rebuilt on demand
    }

    private record FastResult(Optional<MutableItem> best, int answered, List<Responder> refreshed) {}

    /** One round trip to the remembered nodes, in parallel. Also refreshes their write tokens. */
    private FastResult fastGet(Route route, byte[] target, byte[] expectedKey, byte[] salt) {
        Map<Responder, CompletableFuture<Map<String, Object>>> asks = new LinkedHashMap<>();
        for (Responder r : route.nodes) {
            Map<String, Object> a = new HashMap<>();
            a.put("target", target);
            asks.put(r, query(r.node.address(), "get", a));
        }
        try {
            CompletableFuture.allOf(asks.values().toArray(CompletableFuture[]::new))
                    .get(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // use whoever answered in time
        }
        Optional<MutableItem> best = Optional.empty();
        List<Responder> refreshed = new ArrayList<>();
        int answered = 0;
        long now = System.currentTimeMillis();
        for (var e : asks.entrySet()) {
            CompletableFuture<Map<String, Object>> f = e.getValue();
            if (!f.isDone() || f.isCompletedExceptionally() || f.isCancelled()) {
                refreshed.add(e.getKey());
                continue;
            }
            Map<String, Object> r = f.getNow(null);
            answered++;
            stats.replies.incrementAndGet();
            byte[] token = Bencode.bytes(r, "token");
            refreshed.add(token != null ? new Responder(e.getKey().node, token, now) : e.getKey());
            MutableItem item = validItem(r, target, expectedKey, salt);
            if (item != null && (best.isEmpty() || best.get().seq() < item.seq())) best = Optional.of(item);
        }
        return new FastResult(best, answered, refreshed);
    }

    /** Stores the item on the closest nodes. Returns how many accepted it. */
    @Override
    public int put(MutableItem item) {
        if (!item.verify()) throw new IllegalArgumentException("item signature invalid");
        String key = HexFormat.of().formatHex(item.target());
        touch(key, item.publicKey(), item.salt());
        Route route = routes.get(key);
        long now = System.currentTimeMillis();
        if (route != null && now - route.foundAt < ROUTE_TTL_MS) {
            List<Responder> fresh = route.nodes.stream()
                    .filter(r -> r.token != null && now - r.tokenAt < TOKEN_TTL_MS).toList();
            if (fresh.size() >= 3) {
                int ok = sendPuts(item, fresh);
                if (ok >= 2) {
                    stats.fastPuts.incrementAndGet();
                    stats.putsStored.incrementAndGet();
                    return ok;
                }
            }
        }
        // No usable route: search, storing the record on each close node the moment the search reaches it.
        Lookup l = shared(key, item.target(), item.publicKey(), item.salt(), item);
        remember(key, l);
        int ok = awaitStored(l, item, now);
        if (ok > 0) stats.putsStored.incrementAndGet();
        else stats.putsFailed.incrementAndGet();
        return ok;
    }

    // ---------------------------------------------------------------- shared searches, storing as we go

    /** Searches in progress, by record: a second request for the same record joins the first search. */
    private final Map<String, Lookup> searching = new ConcurrentHashMap<>();
    /** For timing logs: when the last store of a record reached enough of its closest nodes. */
    private final Map<String, Long> landedAfter = new ConcurrentHashMap<>();

    /**
     * Full search for a record, shared: if one is already running (say, started when a code was pasted),
     * this joins it instead of starting over. {@code carry}, if given, is stored on close nodes as they're found.
     */
    private Lookup shared(String key, byte[] target, byte[] expectedKey, byte[] salt, MutableItem carry) {
        Lookup mine = new Lookup();
        if (carry != null) mine.carry.add(carry);
        Lookup running = searching.putIfAbsent(key, mine);
        if (running != null) {
            if (carry != null) attach(running, carry);
            try {
                return running.finished.get(LOOKUP_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // that search failed or hung; do our own below
            }
            mine = new Lookup();
            if (carry != null) mine.carry.add(carry);
        }
        stats.fullLookups.incrementAndGet();
        try {
            return runLookup(mine, target, expectedKey, salt, false);
        } finally {
            searching.remove(key, mine);
            mine.finished.complete(mine);
        }
    }

    /** Adds a record to store to a search that's already running, and stores it on close nodes found so far. */
    private void attach(Lookup l, MutableItem item) {
        synchronized (l) {
            if (!l.carry.contains(item)) l.carry.add(item);
            for (Responder r : l.eligible) storeOn(l, item, r);
        }
    }

    /** Sends one store, once per (record, node). Caller holds the lookup's lock. */
    private void storeOn(Lookup l, MutableItem item, Responder r) {
        if (r.token == null) return;
        Map<InetSocketAddress, CompletableFuture<Map<String, Object>>> sent =
                l.stores.computeIfAbsent(item, k -> new HashMap<>());
        if (sent.containsKey(r.node.address())) return;
        sent.put(r.node.address(), putQuery(r, item).whenComplete((res, err) -> {
            if (err == null) l.ackedAt.computeIfAbsent(item, k -> new ConcurrentHashMap<>())
                    .put(r.node.address(), System.currentTimeMillis());
        }));
    }

    /** Waits until the record is on enough of the closest nodes. Returns how many of them confirmed. */
    private int awaitStored(Lookup l, MutableItem item, long started) {
        List<Responder> closest = l.responders.stream().filter(r -> r.token != null).limit(STORE_NODES).toList();
        List<CompletableFuture<Map<String, Object>>> mine = new ArrayList<>();
        synchronized (l) {
            for (Responder r : closest) storeOn(l, item, r);
            Map<InetSocketAddress, CompletableFuture<Map<String, Object>>> sent = l.stores.get(item);
            for (Responder r : closest) mine.add(sent.get(r.node.address()));
        }
        if (mine.isEmpty()) return 0;
        int need = Math.min(PUT_CONFIRMATIONS, mine.size());
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CompletableFuture<Void> enough = new CompletableFuture<>();
        for (CompletableFuture<Map<String, Object>> f : mine) {
            f.whenComplete((r, e) -> {
                if (e == null && ok.incrementAndGet() >= need) enough.complete(null);
                if (finished.incrementAndGet() == mine.size()) enough.complete(null);
            });
        }
        try {
            enough.get(QUERY_TIMEOUT_MS + 300, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // whoever confirmed in time counts
        }
        // When did it actually land on `need` of the closest nodes? (Often well before the search finished.)
        Map<InetSocketAddress, Long> acks = l.ackedAt.getOrDefault(item, Map.of());
        List<Long> times = closest.stream().map(r -> acks.get(r.node.address()))
                .filter(java.util.Objects::nonNull).sorted().toList();
        if (times.size() >= need) {
            landedAfter.put(HexFormat.of().formatHex(item.target()), times.get(need - 1) - started);
        }
        return ok.get();
    }

    private CompletableFuture<Map<String, Object>> putQuery(Responder r, MutableItem item) {
        Map<String, Object> a = new HashMap<>();
        a.put("token", r.token);
        a.put("k", item.publicKey());
        a.put("seq", item.seq());
        a.put("sig", item.signature());
        a.put("v", item.value());
        if (item.salt().length > 0) a.put("salt", item.salt());
        return query(r.node.address(), "put", a);
    }

    private int sendPuts(MutableItem item, List<Responder> targets) {
        List<CompletableFuture<Map<String, Object>>> puts = new ArrayList<>();
        for (Responder r : targets) {
            if (r.token == null) continue;
            puts.add(putQuery(r, item));
            if (puts.size() >= STORE_NODES) break;
        }
        if (puts.isEmpty()) return 0;
        // Done as soon as a few nodes confirm (or all have answered): the rest finish in the background,
        // so one slow node never holds up "you're hosting" or a friend request.
        int need = Math.min(PUT_CONFIRMATIONS, puts.size());
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CompletableFuture<Void> enough = new CompletableFuture<>();
        for (CompletableFuture<Map<String, Object>> f : puts) {
            f.whenComplete((r, e) -> {
                if (e == null && ok.incrementAndGet() >= need) enough.complete(null);
                if (finished.incrementAndGet() == puts.size()) enough.complete(null);
            });
        }
        try {
            enough.get(QUERY_TIMEOUT_MS + 300, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // whoever confirmed in time counts
        }
        return ok.get();
    }

    public int put(KeyPair key, byte[] salt, byte[] value) {
        return put(MutableItem.sign(key, salt, nextSeq(), value));
    }

    private final AtomicInteger seqBump = new AtomicInteger();

    /** Millisecond clock, nudged so two puts in the same millisecond still increase. */
    long nextSeq() {
        return System.currentTimeMillis() * 16 + (seqBump.getAndIncrement() & 15);
    }

    long fullLookups() {
        return stats.fullLookups.get();
    }

    long renewals() {
        return stats.renewals.get();
    }

    long fastOps() {
        return stats.fastGets.get() + stats.fastPuts.get();
    }

    public int knownNodeCount() {
        return known.size();
    }

    // ---------------------------------------------------------------- lookups

    private record Responder(DhtNode node, byte[] token, long tokenAt) {
        Responder(DhtNode node, byte[] token) {
            this(node, token, System.currentTimeMillis());
        }
    }

    private static final class Lookup {
        final List<Responder> responders = new ArrayList<>();
        Optional<MutableItem> best = Optional.empty();
        /** Records to store on close nodes as the search finds them. Guarded by this. */
        final List<MutableItem> carry = new ArrayList<>();
        /** Stores sent so far, per record and node. Guarded by this. */
        final Map<MutableItem, Map<InetSocketAddress, CompletableFuture<Map<String, Object>>>> stores = new HashMap<>();
        final Map<MutableItem, Map<InetSocketAddress, Long>> ackedAt = new ConcurrentHashMap<>();
        /** Nodes that currently look like they're among the closest (and can store). Guarded by this. */
        List<Responder> eligible = List.of();
        final CompletableFuture<Lookup> finished = new CompletableFuture<>();
    }

    private static final class Candidate {
        final DhtNode node;
        boolean queried;
        boolean done;
        boolean triedFindNode;
        /** Didn't answer, or answered but can't store records (no token): doesn't count toward the K closest. */
        boolean useless;

        Candidate(DhtNode node) {
            this.node = node;
        }
    }

    Lookup lookup(byte[] target, byte[] expectedKey, byte[] salt) {
        return lookup(target, expectedKey, salt, false);
    }

    Lookup lookup(byte[] target, byte[] expectedKey, byte[] salt, boolean firstValueWins) {
        return runLookup(new Lookup(), target, expectedKey, salt, firstValueWins);
    }

    private Lookup runLookup(Lookup result, byte[] target, byte[] expectedKey, byte[] salt, boolean firstValueWins) {
        Map<InetSocketAddress, Candidate> cands = new LinkedHashMap<>();
        synchronized (known) {
            known.values().stream()
                    .sorted((a, b) -> DhtNode.compareDistance(target, a.id(), b.id()))
                    .limit(K * 2L)
                    .forEach(n -> cands.put(n.address(), new Candidate(n)));
        }
        if (cands.size() < K) {
            for (InetSocketAddress b : bootstrap) {
                InetSocketAddress resolved = b.isUnresolved() ? new InetSocketAddress(b.getHostString(), b.getPort()) : b;
                if (!resolved.isUnresolved()) cands.putIfAbsent(resolved, new Candidate(new DhtNode(null, resolved)));
            }
        }
        Comparator<Candidate> byDistance = (a, b) -> DhtNode.compareDistance(target, a.node.id(), b.node.id());
        Map<Candidate, CompletableFuture<Map<String, Object>>> inflight = new HashMap<>();
        long deadline = System.currentTimeMillis() + LOOKUP_TIMEOUT_MS;
        int queries = 0;

        while (!closed && System.currentTimeMillis() < deadline) {
            if (firstValueWins && result.best.isPresent()) break;
            List<Candidate> sorted = new ArrayList<>(cands.values());
            sorted.sort(byDistance);
            // Done when the K closest *useful* nodes (answered, and can store records) have all been asked,
            // or nothing closer is left to ask. Dead and legacy nodes don't count.
            List<Candidate> closest = sorted.stream().filter(c -> c.node.id() != null && !c.useless).limit(K).toList();
            boolean nothingLeft = sorted.stream().noneMatch(c -> !c.queried);
            boolean converged = !closest.isEmpty() && closest.stream().allMatch(c -> c.done)
                    && (closest.size() >= K || nothingLeft);
            if ((converged || nothingLeft) && inflight.isEmpty()) break;

            for (Candidate c : sorted) {
                if (inflight.size() >= ALPHA || queries >= MAX_QUERIES) break;
                if (c.queried) continue;
                boolean bootstrapNode = c.node.id() == null;
                if (!bootstrapNode && converged) break;
                c.queried = true;
                queries++;
                inflight.put(c, bootstrapNode ? findNode(c, target) : getQuery(c, target));
            }
            if (inflight.isEmpty()) break;

            try {
                CompletableFuture.anyOf(inflight.values().toArray(CompletableFuture[]::new))
                        .get(Math.max(1, Math.min(QUERY_TIMEOUT_MS, deadline - System.currentTimeMillis())),
                                TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // one finished exceptionally, or we waited long enough; handled below
            }
            for (var it = inflight.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!e.getValue().isDone()) continue;
                it.remove();
                Candidate c = e.getKey();
                CompletableFuture<Map<String, Object>> f = e.getValue();
                if (f.isCompletedExceptionally() || f.isCancelled()) {
                    stats.timeouts.incrementAndGet();
                    // Nodes that don't support BEP 44 reject "get"; ask them for neighbours instead so the
                    // lookup can keep moving toward the target.
                    if (!c.triedFindNode && isErrorReply(f) && queries < MAX_QUERIES && inflight.size() < ALPHA) {
                        queries++;
                        inflight.put(c, findNode(c, target));
                        break; // map modified; pick up remaining finished futures next loop
                    }
                    c.done = true;
                    c.useless = true;
                    continue;
                }
                c.done = true;
                Map<String, Object> r = f.getNow(null);
                if (r == null) {
                    c.useless = true;
                    continue;
                }
                stats.replies.incrementAndGet();
                if (Bencode.bytes(r, "token") == null) c.useless = true; // find_node-only node: can't store
                handleGetReply(r, c, target, expectedKey, salt, cands, result);
            }
            storeAsWeGo(result, cands, byDistance);
        }
        inflight.values().forEach(f -> f.cancel(false));
        result.responders.sort((a, b) -> DhtNode.compareDistance(target, a.node.id(), b.node.id()));
        return result;
    }

    private CompletableFuture<Map<String, Object>> getQuery(Candidate c, byte[] target) {
        Map<String, Object> a = new HashMap<>();
        a.put("target", target);
        return query(c.node.address(), "get", a);
    }

    private CompletableFuture<Map<String, Object>> findNode(Candidate c, byte[] target) {
        c.triedFindNode = true;
        Map<String, Object> a = new HashMap<>();
        a.put("target", target);
        return query(c.node.address(), "find_node", a);
    }

    private static boolean isErrorReply(CompletableFuture<?> f) {
        try {
            f.getNow(null);
            return false;
        } catch (java.util.concurrent.CompletionException e) {
            return e.getCause() instanceof DhtError;
        } catch (java.util.concurrent.CancellationException e) {
            return false;
        }
    }

    /** Running counters, for the occasional diagnostic log line. */
    public static final class Stats {
        final java.util.concurrent.atomic.AtomicLong replies = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong timeouts = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong putsStored = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong putsFailed = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong fullLookups = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong fastGets = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong fastPuts = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong renewals = new java.util.concurrent.atomic.AtomicLong();
    }

    private final Stats stats = new Stats();

    /** One-line summary with counts only: never addresses or keys. */
    public String statsLine() {
        return "DHT: " + known.size() + " nodes known, " + stats.replies.get() + " replies, "
                + stats.timeouts.get() + " no-reply, " + stats.putsStored.get() + " records stored, "
                + stats.putsFailed.get() + " stores failed, " + stats.fullLookups.get() + " full searches ("
                + stats.renewals.get() + " background renewals), "
                + (stats.fastGets.get() + stats.fastPuts.get()) + " fast (remembered route)";
    }

    /**
     * Nodes that answered with a write token and are, as far as we know so far, among the closest: store any
     * carried records on them right away instead of waiting for the search to finish.
     */
    private void storeAsWeGo(Lookup result, Map<InetSocketAddress, Candidate> cands, Comparator<Candidate> byDistance) {
        Map<InetSocketAddress, Responder> byAddress = new HashMap<>();
        for (Responder r : result.responders) if (r.token != null) byAddress.putIfAbsent(r.node.address(), r);
        List<Responder> eligible = new ArrayList<>();
        cands.values().stream().filter(c -> c.node.id() != null && !c.useless).sorted(byDistance).limit(STORE_NODES)
                .forEach(c -> {
                    Responder r = byAddress.get(c.node.address());
                    if (r != null) eligible.add(r);
                });
        synchronized (result) {
            result.eligible = List.copyOf(eligible);
            for (MutableItem item : result.carry) for (Responder r : eligible) storeOn(result, item, r);
        }
    }

    private void handleGetReply(Map<String, Object> r, Candidate c, byte[] target, byte[] expectedKey,
                                byte[] salt, Map<InetSocketAddress, Candidate> cands, Lookup result) {
        byte[] id = Bencode.bytes(r, "id");
        if (id == null || id.length != 20) return;
        DhtNode node = new DhtNode(id, c.node.address());
        known.put(node.address(), node);
        result.responders.add(new Responder(node, Bencode.bytes(r, "token")));
        for (DhtNode n : DhtNode.parseCompact(Bencode.bytes(r, "nodes"), allowLocal)) {
            if (cands.size() < 400) cands.putIfAbsent(n.address(), new Candidate(n));
        }
        // Replace the bootstrap placeholder (id unknown) with the real id so distance sorting works.
        if (c.node.id() == null) {
            Candidate replaced = new Candidate(node);
            replaced.queried = true;
            replaced.done = true;
            replaced.triedFindNode = c.triedFindNode;
            replaced.useless = Bencode.bytes(r, "token") == null;
            cands.put(node.address(), replaced);
        }
        MutableItem item = validItem(r, target, expectedKey, salt);
        if (item != null && (result.best.isEmpty() || result.best.get().seq() < item.seq())) {
            result.best = Optional.of(item);
        }
    }

    /** The record in a get reply, only if it's for the key we asked about and the signature checks out. */
    private static MutableItem validItem(Map<String, Object> r, byte[] target, byte[] expectedKey, byte[] salt) {
        byte[] k = Bencode.bytes(r, "k"), sig = Bencode.bytes(r, "sig"), v = Bencode.bytes(r, "v");
        Long seq = Bencode.integer(r, "seq");
        if (k == null || sig == null || v == null || seq == null) return null;
        if (!Arrays.equals(k, expectedKey)) return null;
        MutableItem item = new MutableItem(k, salt, seq, v, sig);
        if (!Arrays.equals(item.target(), target) || !item.verify()) return null; // forged or corrupted
        return item;
    }

    // ---------------------------------------------------------------- transport

    CompletableFuture<Map<String, Object>> query(InetSocketAddress to, String method, Map<String, Object> args) {
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        if (closed) {
            f.completeExceptionally(new IOException("closed"));
            return f;
        }
        int t = tid.getAndIncrement();
        byte[] tidBytes = {(byte) (t >> 8), (byte) t};
        String key = HexFormat.of().formatHex(tidBytes);
        Map<String, Object> a = new HashMap<>(args);
        a.put("id", myId);
        Map<String, Object> msg = new HashMap<>();
        msg.put("t", tidBytes);
        msg.put("y", "q");
        msg.put("q", method);
        msg.put("a", a);
        msg.put("ro", 1L);
        byte[] data = Bencode.encode(msg);
        pending.put(key, new Pending(to, f));
        f.orTimeout(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS).whenComplete((r, e) -> pending.remove(key));
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException | IllegalArgumentException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private void receiveLoop() {
        byte[] buf = new byte[Bencode.MAX_INPUT];
        while (!closed) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (IOException e) {
                if (closed) return;
                continue;
            }
            try {
                Object msg = Bencode.decode(p.getData(), p.getOffset(), p.getLength());
                byte[] t = Bencode.bytes(msg, "t");
                String y = Bencode.str(msg, "y");
                if (t == null || y == null) continue;
                Pending pend = pending.get(HexFormat.of().formatHex(t));
                // Only accept a reply from the exact address we asked (stops spoofed answers).
                if (pend == null || !pend.to.equals(p.getSocketAddress())) continue;
                if ("r".equals(y)) {
                    Map<String, Object> r = Bencode.dict(msg, "r");
                    if (r != null) pend.future.complete(r);
                } else if ("e".equals(y)) {
                    Object e = ((Map<?, ?>) msg).get("e");
                    long code = -1;
                    String text = "";
                    if (e instanceof List<?> l && l.size() >= 2) {
                        if (l.get(0) instanceof Long c) code = c;
                        if (l.get(1) instanceof byte[] b) text = new String(b, StandardCharsets.UTF_8);
                    }
                    pend.future.completeExceptionally(new DhtError(code, text));
                }
                // Incoming queries ("y":"q") are ignored: we're a read-only node.
            } catch (Bencode.DecodeException | ClassCastException ignored) {
                // garbage packet
            }
        }
    }

    // ---------------------------------------------------------------- node cache

    /** Remembers good nodes between sessions so startup doesn't depend on bootstrap routers. */
    public void saveNodes(Path file) {
        List<DhtNode> nodes;
        synchronized (known) {
            nodes = new ArrayList<>(known.values());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, DhtNode.toCompact(nodes.subList(0, Math.min(nodes.size(), 128))));
        } catch (IOException ignored) {
            // cache is optional
        }
    }

    public void loadNodes(Path file) {
        try {
            byte[] data = Files.readAllBytes(file);
            for (DhtNode n : DhtNode.parseCompact(data, allowLocal)) known.put(n.address(), n);
        } catch (IOException ignored) {
            // no cache yet
        }
    }

    @Override
    public void close() {
        closed = true;
        maintainer.shutdownNow();
        refresher.shutdownNow();
        socket.close();
        pending.values().forEach(p -> p.future.cancel(false));
    }
}
