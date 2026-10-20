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
    static final int ALPHA = 4;
    static final int MAX_QUERIES = 80;
    static final long QUERY_TIMEOUT_MS = 1500;
    static final long LOOKUP_TIMEOUT_MS = 10_000;
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
        return lookup(MutableItem.target(publicKey, salt), publicKey, salt).best;
    }

    /** Stores the item on the closest nodes. Returns how many accepted it. */
    @Override
    public int put(MutableItem item) {
        if (!item.verify()) throw new IllegalArgumentException("item signature invalid");
        Lookup l = lookup(item.target(), item.publicKey(), item.salt());
        List<CompletableFuture<Map<String, Object>>> puts = new ArrayList<>();
        for (Responder r : l.responders) {
            if (r.token == null) continue;
            Map<String, Object> a = new HashMap<>();
            a.put("token", r.token);
            a.put("k", item.publicKey());
            a.put("seq", item.seq());
            a.put("sig", item.signature());
            a.put("v", item.value());
            if (item.salt().length > 0) a.put("salt", item.salt());
            puts.add(query(r.node.address(), "put", a));
            if (puts.size() >= K) break;
        }
        int ok = 0;
        for (CompletableFuture<Map<String, Object>> f : puts) {
            try {
                f.get(QUERY_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
                ok++;
            } catch (Exception ignored) {
                // timeout or refusal from that node
            }
        }
        return ok;
    }

    public int put(KeyPair key, byte[] salt, byte[] value) {
        return put(MutableItem.sign(key, salt, nextSeq(), value));
    }

    private final AtomicInteger seqBump = new AtomicInteger();

    /** Millisecond clock, nudged so two puts in the same millisecond still increase. */
    long nextSeq() {
        return System.currentTimeMillis() * 16 + (seqBump.getAndIncrement() & 15);
    }

    public int knownNodeCount() {
        return known.size();
    }

    // ---------------------------------------------------------------- lookups

    private record Responder(DhtNode node, byte[] token) {}

    private static final class Lookup {
        final List<Responder> responders = new ArrayList<>();
        Optional<MutableItem> best = Optional.empty();
    }

    private static final class Candidate {
        final DhtNode node;
        boolean queried;
        boolean done;

        Candidate(DhtNode node) {
            this.node = node;
        }
    }

    Lookup lookup(byte[] target, byte[] expectedKey, byte[] salt) {
        Lookup result = new Lookup();
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
            List<Candidate> sorted = new ArrayList<>(cands.values());
            sorted.sort(byDistance);
            // Done when the K closest nodes with known ids have all answered or failed.
            List<Candidate> closest = sorted.stream().filter(c -> c.node.id() != null).limit(K).toList();
            boolean converged = closest.size() >= Math.min(K, cands.size()) && !closest.isEmpty()
                    && closest.stream().allMatch(c -> c.done);
            if (converged && inflight.isEmpty()) break;

            for (Candidate c : sorted) {
                if (inflight.size() >= ALPHA || queries >= MAX_QUERIES) break;
                if (c.queried) continue;
                boolean bootstrapNode = c.node.id() == null;
                if (!bootstrapNode && converged) break;
                c.queried = true;
                queries++;
                Map<String, Object> a = new HashMap<>();
                a.put("target", target);
                inflight.put(c, query(c.node.address(), "get", a));
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
                c.done = true;
                Map<String, Object> r = e.getValue().getNow(null);
                if (r == null || e.getValue().isCompletedExceptionally()) continue;
                handleGetReply(r, c, target, expectedKey, salt, cands, result);
            }
        }
        inflight.values().forEach(f -> f.cancel(false));
        result.responders.sort((a, b) -> DhtNode.compareDistance(target, a.node.id(), b.node.id()));
        return result;
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
            cands.put(node.address(), replaced);
        }
        byte[] k = Bencode.bytes(r, "k"), sig = Bencode.bytes(r, "sig"), v = Bencode.bytes(r, "v");
        Long seq = Bencode.integer(r, "seq");
        if (k == null || sig == null || v == null || seq == null) return;
        if (!Arrays.equals(k, expectedKey)) return;
        MutableItem item = new MutableItem(k, salt, seq, v, sig);
        if (!Arrays.equals(item.target(), target) || !item.verify()) return; // forged or corrupted
        if (result.best.isEmpty() || result.best.get().seq() < seq) result.best = Optional.of(item);
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
        socket.close();
        pending.values().forEach(p -> p.future.cancel(false));
    }
}
