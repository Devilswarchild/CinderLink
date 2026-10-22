package dev.devilswarchild.cinderlink.core.presence;

import dev.devilswarchild.cinderlink.core.dht.DhtStore;
import dev.devilswarchild.cinderlink.core.dht.MutableItem;
import dev.devilswarchild.cinderlink.core.identity.CinderCode;
import dev.devilswarchild.cinderlink.core.identity.Identity;
import dev.devilswarchild.cinderlink.core.identity.Keys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The friends engine: publishes your card and per-friend presence records, reads your friends' records,
 * delivers and receives friend requests. Driven by {@link #tick()}, which the caller schedules off the game
 * thread. Everything public is thread-safe.
 */
public final class PresenceService {
    public static final long OFFLINE_AFTER_MS = 5 * 60_000;
    /** Re-send our status even if nothing changed, so friends know we're still here. */
    static final long REPUBLISH_MS = 60_000;
    /** How often we check our mailbox (and retry unsent requests). Cheap: one round trip to known nodes. */
    static final long POLL_MS = 2_000;
    /** How often we check friends' status. Just under the game's 1-second tick, so it runs every tick. */
    static final long FRIEND_POLL_MS = 900;
    /**
     * Remembered routes are kept fresh in the background by the DHT client, so reads never force a full search;
     * this is only the upper bound if that upkeep falls behind.
     */
    static final long ROUTE_MAX_AGE_MS = 120_000;
    static final long CARD_MS = 30 * 60_000;
    /** A request is stored right away; this is only how often it's re-stored until they answer. */
    static final long REQUEST_RESEND_MS = 30_000;
    private static final long MAX_CLOCK_SKEW_MS = 10 * 60_000;
    /** Clock difference we tolerate between two players' computers when judging "newer than my request". */
    private static final long ACCEPT_SKEW_MS = 60_000;

    /** What we're doing right now. {@code hosting} is null when not hosting. */
    public record LocalStatus(String mcName, UUID mcUuid, Hosting hosting) {}

    /** A hosted world: who may see/join it (friend ids) and how they reach it. */
    public record Hosting(String world, List<InetSocketAddress> addresses, Set<String> visibleTo) {}

    /** A friend as the UI should show them. */
    public record FriendView(Friend friend, PresenceState state, String world, boolean canJoin, long lastSeenMs) {}

    public interface Listener {
        default void requestReceived(Friend from) {}

        default void requestAccepted(Friend by) {}

        default void friendStartedHosting(FriendView view) {}

        /** Something went wrong in the background. The text never contains addresses, keys or names. */
        default void problem(String what) {}

        /** How long a step took, for troubleshooting speed. Durations and counts only: no addresses, keys or names. */
        default void timing(String what) {}
    }

    public enum AddResult { SENT, INVALID_CODE, OWN_CODE, ALREADY_FRIENDS, ALREADY_SENT, ACCEPTED_THEIRS, BLOCKED }

    private final Identity me;
    private final FriendsStore store;
    private final DhtStore dht;
    private final Path dir;
    private final LongSupplier clock;
    private final ExecutorService pool;
    private final Map<String, PresenceRecord> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPublished = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequestSent = new ConcurrentHashMap<>();
    private final Map<String, byte[]> pairSecrets = new ConcurrentHashMap<>();
    private final AtomicLong seqCounter = new AtomicLong();
    private volatile LocalStatus local;
    private volatile Listener listener = new Listener() {};
    private volatile long lastCard = Long.MIN_VALUE / 2;
    private volatile long lastPoll = Long.MIN_VALUE / 2;
    private volatile long lastFriendPoll = Long.MIN_VALUE / 2;
    /** In the game: act on a change (hosting, Send, Accept) right away instead of at the next 1-second tick. */
    private volatile boolean immediate;
    /** Timing notes for the log: when a code was pasted / Send was clicked / the card lookup finished, by friend id. */
    private final Map<String, Long> pastedAt = new ConcurrentHashMap<>();
    private final Map<String, Long> cardReadyAt = new ConcurrentHashMap<>();
    private final Map<String, Long> sendClickedAt = new ConcurrentHashMap<>();
    /** For timing logs: when each of our mailbox slots was last checked and found empty. */
    private final long[] lastEmptyCheck = new long[Protocol.INBOX_SLOTS];
    /** Cards being looked up ahead of time (from a pasted code), by friend id. */
    private final Map<String, java.util.concurrent.CompletableFuture<byte[]>> prepared = new ConcurrentHashMap<>();
    private volatile boolean dirty = true;
    private int cycle;

    public PresenceService(Identity me, FriendsStore store, DhtStore dht, Path dir, LongSupplier clock, int threads) {
        this.me = me;
        this.store = store;
        this.dht = dht;
        this.dir = dir;
        this.clock = clock;
        this.pool = Executors.newFixedThreadPool(Math.max(threads, 8), r -> {
            Thread t = new Thread(r, "CinderLink presence");
            t.setDaemon(true);
            return t;
        });
    }

    public void setListener(Listener l) {
        listener = l == null ? new Listener() {} : l;
    }

    public void setLocal(LocalStatus status) {
        if (!Objects.equals(status, local)) {
            local = status;
            changed();
        }
    }

    /** Turns on acting immediately when something changes (the game does; tests drive ticks by hand). */
    public void setImmediate(boolean on) {
        immediate = on;
    }

    private void changed() {
        dirty = true;
        if (immediate) {
            try {
                pool.execute(() -> tick(false));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // shutting down
            }
        }
    }

    /**
     * Called as a code is pasted, before Send: finds that person's card and mailbox in the background,
     * so pressing Send is a single quick round trip. Nothing is sent to them until Send.
     */
    public void prepare(String text) {
        CinderCode code;
        try {
            code = CinderCode.parse(text);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (Arrays.equals(code.identityKey(), me.signPublic())) return;
        String id = HexFormat.of().formatHex(code.identityKey());
        if (store.get(id).isPresent() || store.isBlocked(id) || prepared.containsKey(id)) return;
        if (prepared.size() > 16) prepared.clear();
        long pasted = System.currentTimeMillis();
        pastedAt.put(id, pasted);
        KeyPair inbox = Protocol.inboxKey(code.identityKey(), code.inboxSecret(), firstSlot());
        java.util.concurrent.CompletableFuture.runAsync(
                () -> dht.warm(Keys.raw(inbox.getPublic()), Protocol.INBOX_SALT), pool);
        prepared.put(id, java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            byte[] box = dht.get(code.identityKey(), Protocol.CARD_SALT, ROUTE_MAX_AGE_MS, true)
                    .map(Protocol::cardBoxKey).orElse(null);
            cardReadyAt.put(id, System.currentTimeMillis());
            listener.timing("friend's card " + (box == null ? "NOT found" : "found") + " "
                    + secs(System.currentTimeMillis() - pasted) + " after the code was pasted");
            return box;
        }, pool));
    }

    private static String secs(long ms) {
        return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** The mailbox slot our first request to anyone goes to (fixed, so it can be found ahead of time). */
    private int firstSlot() {
        return Math.floorMod(Arrays.hashCode(me.signPublic()), Protocol.INBOX_SLOTS);
    }

    public LocalStatus local() {
        return local;
    }

    public Identity identity() {
        return me;
    }

    // ------------------------------------------------------------------ user actions

    public AddResult addByCode(String text) {
        CinderCode code;
        try {
            code = CinderCode.parse(text);
        } catch (IllegalArgumentException e) {
            return AddResult.INVALID_CODE;
        }
        if (Arrays.equals(code.identityKey(), me.signPublic())) return AddResult.OWN_CODE;
        String id = HexFormat.of().formatHex(code.identityKey());
        if (store.isBlocked(id)) return AddResult.BLOCKED;
        Optional<Friend> existing = store.get(id);
        if (existing.isPresent()) {
            switch (existing.get().relation()) {
                case FRIEND -> { return AddResult.ALREADY_FRIENDS; }
                case OUTGOING -> { return AddResult.ALREADY_SENT; }
                case INCOMING -> {
                    accept(id);
                    return AddResult.ACCEPTED_THEIRS;
                }
            }
        }
        lastRequestSent.remove(id); // a fresh request, even if we've asked this person before
        store.put(new Friend(code.identityKey(), null, Friend.Relation.OUTGOING, "?", new UUID(0, 0),
                code.inboxSecret(), clock.getAsLong()));
        persist();
        sendClickedAt.put(id, System.currentTimeMillis());
        changed();
        return AddResult.SENT;
    }

    public void accept(String id) {
        store.get(id).filter(f -> f.relation() == Friend.Relation.INCOMING).ifPresent(f -> {
            store.put(f.with(Friend.Relation.FRIEND, f.boxPublic(), f.mcName(), f.mcUuid()));
            persist();
            changed();
        });
    }

    /**
     * Declining ignores that request and its automatic re-sends. If they send a brand-new request later,
     * it shows up normally. (Block is the permanent option.)
     */
    public void decline(String id) {
        // For an incoming request, addedAtMs holds the sender's request id.
        store.get(id).filter(f -> f.relation() == Friend.Relation.INCOMING)
                .ifPresent(f -> store.dismissRequestsUntil(id, f.addedAtMs()));
        store.remove(id);
        lastRequestSent.remove(id);
        persist();
    }

    /** Removing ignores any request of theirs from before now (old ones linger in the DHT mailbox). */
    public void remove(String id) {
        // Leave a final "not friends any more" note where they read our status, so a stale record can't be
        // mistaken for acceptance later, and their game knows to drop us too.
        store.get(id).filter(f -> f.relation() == Friend.Relation.FRIEND).ifPresent(f -> {
            byte[] secret = pairSecret(f);
            LocalStatus l = local;
            if (secret == null || l == null) return;
            PresenceRecord bye = PresenceRecord.unfriended(l.mcName(), l.mcUuid(), clock.getAsLong());
            pool.submit(() -> dht.put(MutableItem.sign(Protocol.pairKey(secret, me.signPublic()), Protocol.PAIR_SALT,
                    nextSeq(), Protocol.sealRecord(secret, me.signPublic(), bye))));
        });
        store.remove(id);
        store.dismissRequestsUntil(id, clock.getAsLong());
        lastRequestSent.remove(id);
        lastPublished.remove(id);
        latest.remove(id);
        pairSecrets.remove(id);
        persist();
    }

    public void block(String id) {
        store.block(id);
        latest.remove(id);
        pairSecrets.remove(id);
        persist();
    }

    public void unblock(String id) {
        store.unblock(id);
        persist();
    }

    /** New code; every code handed out before stops working. Friends are unaffected. */
    public void resetCode() throws IOException {
        me.resetCode(dir);
    }

    public FriendsStore store() {
        return store;
    }

    // ------------------------------------------------------------------ queries for the UI / join gate

    public List<FriendView> views() {
        long now = clock.getAsLong();
        List<FriendView> out = new ArrayList<>();
        for (Friend f : store.all()) {
            PresenceRecord r = f.relation() == Friend.Relation.FRIEND ? latest.get(f.id()) : null;
            boolean fresh = r != null && now - r.timestampMs() < OFFLINE_AFTER_MS;
            PresenceState st = fresh ? r.state() : PresenceState.OFFLINE;
            boolean canJoin = st == PresenceState.HOSTING && !r.addresses().isEmpty();
            out.add(new FriendView(f, st, fresh ? r.world() : "", canJoin, r == null ? 0 : r.timestampMs()));
        }
        return out;
    }

    /** Where to connect to join this friend, or empty if they aren't hosting for us. Never shown to the player. */
    public List<InetSocketAddress> joinAddresses(String id) {
        return views().stream().filter(v -> v.friend().id().equals(id) && v.canJoin()).findFirst()
                .map(v -> latest.get(id).addresses()).orElse(List.of());
    }

    // ------------------------------------------------------------------ the engine

    /** One round of network work. Blocking; call from a background scheduler. */
    /** One round of network work, waiting for all of it to finish (handy for tests). */
    public void tick() {
        tick(true);
    }

    /**
     * Starts this round's network work. With {@code wait=false} (the game), it returns immediately: each kind
     * of check runs on its own, so a quick one-hop status check never sits behind a slow full search.
     * A check that's still running from an earlier round is simply not started again.
     */
    public void tick(boolean wait) {
        List<Future<?>> jobs;
        synchronized (this) { // the game's timer and an immediate kick may both call this
            jobs = schedule();
        }
        if (!wait) return;
        for (Future<?> j : jobs) {
            try {
                j.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // errors are reported by submit(); slow lookups just retry next round
            }
        }
    }

    private List<Future<?>> schedule() {
        long now = clock.getAsLong();
        cycle++;
        List<Future<?>> jobs = new ArrayList<>();
        if (now - lastCard >= CARD_MS) {
            lastCard = now;
            submit(jobs, "card", () -> dht.put(Protocol.card(me, nextSeq())));
        }
        boolean poll = now - lastPoll >= POLL_MS;
        boolean kick = dirty; // something just changed (sent, accepted, started hosting): act now
        if (poll) {
            lastPoll = now;
            for (int slot = 0; slot < Protocol.INBOX_SLOTS; slot++) {
                int s = slot;
                submit(jobs, "inbox" + s, () -> readInbox(s));
            }
        }
        for (Friend f : store.withRelation(Friend.Relation.OUTGOING)) {
            // Waiting on an answer we've already sent is a quick check, so do it every tick for snappy accepts.
            // Anything needing a full search (their card, first send) stays on the poll timer.
            boolean quick = f.boxPublic() != null && lastRequestSent.containsKey(f.id());
            if (poll || kick || quick) submit(jobs, "out" + f.id(), () -> handleOutgoing(f, now));
        }
        boolean friendPoll = now - lastFriendPoll >= FRIEND_POLL_MS;
        if (friendPoll) lastFriendPoll = now;
        boolean publishAll = dirty;
        dirty = false;
        for (Friend f : store.withRelation(Friend.Relation.FRIEND)) {
            if (publishAll || now - lastPublished.getOrDefault(f.id(), Long.MIN_VALUE / 2) >= REPUBLISH_MS) {
                submit(jobs, "pub" + f.id(), () -> publishTo(f, now));
            }
            if (friendPoll) submit(jobs, "read" + f.id(), () -> readFrom(f, now));
        }
        return jobs;
    }

    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();

    /** Runs {@code job} unless the same kind of job is still going; reports real errors instead of hiding them. */
    private void submit(List<Future<?>> jobs, String key, Runnable job) {
        Future<?> prev = running.get(key);
        if (prev != null && !prev.isDone()) return;
        Future<?> f = pool.submit(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                StackTraceElement at = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
                listener.problem(e.getClass().getSimpleName()
                        + (at == null ? "" : " at " + at.getFileName() + ":" + at.getLineNumber()));
            }
        });
        running.put(key, f);
        jobs.add(f);
    }

    private void readInbox(int slot) {
        KeyPair inbox = Protocol.inboxKey(me.signPublic(), me.inboxSecret(), slot);
        Optional<MutableItem> got = dht.get(Keys.raw(inbox.getPublic()), Protocol.INBOX_SALT, ROUTE_MAX_AGE_MS, false);
        if (got.isEmpty() || got.get().value().length == 0) {
            synchronized (lastEmptyCheck) {
                lastEmptyCheck[slot] = clock.getAsLong();
            }
        }
        got.ifPresent(item -> {
            if (item.value().length == 0) return; // already cleared
            handleRequest(item, slot, Keys.raw(inbox.getPublic()));
            // Whatever we decided, the request has been handled: empty the slot so it can't come back.
            // (A sender still waiting re-sends every 30 seconds, so nothing real is lost.)
            clearInboxSlot(inbox, item.seq());
        });
    }

    private void clearInboxSlot(KeyPair inbox, long handledSeq) {
        long seq = Math.max(handledSeq + 1, nextSeq()); // must outrank the request, whatever their clock says
        dht.put(MutableItem.sign(inbox, Protocol.INBOX_SALT, seq, new byte[0]));
    }

    private void handleRequest(MutableItem item, int slot, byte[] inboxPublic) {
        {
            Protocol.Request req = Protocol.openRequest(me, item.value());
            if (req == null || Arrays.equals(req.signPublic(), me.signPublic())) return;
            long now = clock.getAsLong();
            if (Math.abs(now - req.timestampMs()) > 24 * 3600_000L) return; // stale or bogus time
            String id = HexFormat.of().formatHex(req.signPublic());
            if (store.isDismissed(id, req.requestId())) return; // a request we already dealt with
            Optional<Friend> existing = store.get(id);
            if (existing.isPresent() && existing.get().relation() == Friend.Relation.OUTGOING) {
                // We'd both added each other: that's mutual consent, so become friends straight away.
                Friend f = existing.get().with(Friend.Relation.FRIEND, req.boxPublic(), req.mcName(), req.mcUuid());
                store.put(f);
                persist();
                publishTo(f, now);
                listener.requestAccepted(f);
                return;
            }
            // addedAtMs holds the sender's request id, so Decline can ignore exactly this request.
            Friend incoming = new Friend(req.signPublic(), req.boxPublic(), Friend.Relation.INCOMING, req.mcName(),
                    req.mcUuid(), null, req.requestId());
            if (store.addIncoming(incoming)) {
                persist();
                long lastEmpty;
                synchronized (lastEmptyCheck) {
                    lastEmpty = lastEmptyCheck[slot];
                }
                listener.timing("friend request found in mailbox " + slot + ", " + secs(now - req.timestampMs())
                        + " after they sent it by their clock (clocks between PCs can differ a little); "
                        + "this mailbox last came back empty " + (lastEmpty == 0 ? "never" : secs(now - lastEmpty) + " ago")
                        + "; " + dht.routeInfo(inboxPublic, Protocol.INBOX_SALT));
                listener.requestReceived(incoming);
                // While they decide, find where our shared status record lives so Accept is one quick hop.
                warmPair(incoming, me.signPublic());
            }
        }
    }

    private void handleOutgoing(Friend f, long now) {
        LocalStatus l = local;
        if (l == null) return;
        Friend current = f;
        boolean sendDue = now - lastRequestSent.getOrDefault(current.id(), Long.MIN_VALUE / 2) >= REQUEST_RESEND_MS;
        boolean firstSend = !lastRequestSent.containsKey(current.id());
        // The first send goes to a fixed slot that may already have been found when the code was pasted;
        // re-sends rotate through the slots in case one is jammed.
        int slot = firstSend ? firstSlot()
                : Math.floorMod(Arrays.hashCode(me.signPublic()) + cycle, Protocol.INBOX_SLOTS);
        KeyPair inbox = Protocol.inboxKey(current.signPublic(), current.inboxSecret(), slot);
        if (current.boxPublic() == null) {
            java.util.concurrent.CompletableFuture<byte[]> ready = prepared.remove(current.id());
            byte[] box = null;
            if (ready != null && firstSend) {
                Long clicked = sendClickedAt.get(current.id());
                Long pasted = pastedAt.get(current.id());
                listener.timing("Send clicked " + (pasted == null || clicked == null ? "?" : secs(clicked - pasted))
                        + " after pasting; card lookup " + (ready.isDone() ? "already finished" : "still running"));
            }
            if (ready != null) {
                try {
                    box = ready.get(10, TimeUnit.SECONDS); // usually already done while they reached for Send
                } catch (Exception ignored) {
                    // look it up below
                }
            } else if (sendDue) {
                // Their mailbox location comes from the code alone, so find it while we fetch their card.
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> dht.warm(Keys.raw(inbox.getPublic()), Protocol.INBOX_SALT), pool);
            }
            if (box == null) {
                box = dht.get(current.signPublic(), Protocol.CARD_SALT, ROUTE_MAX_AGE_MS, true)
                        .map(Protocol::cardBoxKey).orElse(null);
            }
            if (box == null) return; // they haven't been online recently; try again later
            current = new Friend(current.signPublic(), box, Friend.Relation.OUTGOING, current.mcName(),
                    current.mcUuid(), current.inboxSecret(), current.addedAtMs());
            store.put(current);
            persist();
        }
        // A request we've never sent can't have been accepted yet: send it first, check for acceptance later.
        boolean everSent = lastRequestSent.containsKey(current.id());
        PresenceRecord theirs = everSent ? fetchRecord(current) : null;
        if (theirs != null && !theirs.ended() && theirs.timestampMs() >= current.addedAtMs() - ACCEPT_SKEW_MS) {
            listener.timing("our request was accepted; their acceptance is " + secs(now - theirs.timestampMs())
                    + " old by their clock (clocks between PCs can differ a little)");
            Friend accepted = current.with(Friend.Relation.FRIEND, current.boxPublic(), theirs.mcName(), theirs.mcUuid());
            store.put(accepted);
            latest.put(accepted.id(), theirs);
            persist();
            publishTo(accepted, now); // answer right away instead of waiting a round
            listener.requestAccepted(accepted);
            return;
        }
        if (!sendDue) return;
        try {
            byte[] sealed = Protocol.sealRequest(me, l.mcName(), l.mcUuid(), now, current.addedAtMs(),
                    current.signPublic(), current.boxPublic());
            // No need to wait for the mailbox search started earlier: put() joins it and stores the request
            // on each close node the moment the search reaches it.
            long putStart = System.currentTimeMillis();
            int stored = dht.put(MutableItem.sign(inbox, Protocol.INBOX_SALT, nextSeq(), sealed));
            if (firstSend) {
                Long clicked = sendClickedAt.remove(current.id());
                long done = System.currentTimeMillis();
                listener.timing("friend request " + (stored > 0 ? "stored" : "NOT stored") + " on " + stored
                        + " nodes (mailbox " + slot + "): store took " + secs(done - putStart)
                        + ", " + (clicked == null ? "?" : secs(done - clicked)) + " after Send was clicked; "
                        + dht.routeInfo(Keys.raw(inbox.getPublic()), Protocol.INBOX_SALT));
                pastedAt.remove(current.id());
                cardReadyAt.remove(current.id());
            }
            if (stored > 0) {
                lastRequestSent.put(current.id(), now);
                // Get ready to spot their acceptance with quick checks instead of full searches.
                warmPair(current, current.signPublic());
            }
        } catch (GeneralSecurityException ignored) {
            // their card key was bad; nothing we can do
        }
    }

    private void publishTo(Friend f, long now) {
        LocalStatus l = local;
        byte[] secret = pairSecret(f);
        if (l == null || secret == null) return;
        PresenceRecord r = recordFor(f, l, now, PresenceState.ONLINE);
        KeyPair key = Protocol.pairKey(secret, me.signPublic());
        byte[] sealed = Protocol.sealRecord(secret, me.signPublic(), r);
        if (dht.put(MutableItem.sign(key, Protocol.PAIR_SALT, nextSeq(), sealed)) > 0) {
            lastPublished.put(f.id(), now);
        }
    }

    private PresenceRecord recordFor(Friend f, LocalStatus l, long now, PresenceState base) {
        Hosting h = l.hosting();
        if (base != PresenceState.OFFLINE && h != null && h.visibleTo().contains(f.id())) {
            return new PresenceRecord(PresenceState.HOSTING, l.mcName(), l.mcUuid(), h.world(), h.addresses(), now);
        }
        return new PresenceRecord(base, l.mcName(), l.mcUuid(), "", List.of(), now);
    }

    private void readFrom(Friend f, long now) {
        PresenceRecord r = fetchRecord(f);
        if (r == null || r.timestampMs() > now + MAX_CLOCK_SKEW_MS) return;
        // Anything written before this friendship began (the request was sent) is left over from an earlier
        // one, e.g. the "removed" note from when one of us clicked X. It says nothing about us now.
        if (r.ended() ? r.timestampMs() < f.addedAtMs() : r.timestampMs() < f.addedAtMs() - ACCEPT_SKEW_MS) return;
        PresenceRecord prev = latest.get(f.id());
        // The DHT always returns their highest-version record, so a goodbye note is final even if its clock
        // stamp ties with their last update.
        if (r.ended()) {
            // They removed us. Drop them quietly; ignore their older requests too.
            store.remove(f.id());
            store.dismissRequestsUntil(f.id(), now);
            latest.remove(f.id());
            pairSecrets.remove(f.id());
            persist();
            return;
        }
        if (prev != null && prev.timestampMs() >= r.timestampMs()) return;
        latest.put(f.id(), r);
        boolean wasJoinable = prev != null && prev.state() == PresenceState.HOSTING && !prev.addresses().isEmpty()
                && now - prev.timestampMs() < OFFLINE_AFTER_MS;
        if (r.state() == PresenceState.HOSTING && !r.addresses().isEmpty() && !wasJoinable) {
            listener.timing("friend started hosting; their update is " + secs(now - r.timestampMs())
                    + " old by their clock (clocks between PCs can differ a little)");
            views().stream().filter(v -> v.friend().id().equals(f.id())).findFirst()
                    .ifPresent(listener::friendStartedHosting);
        }
        if (!r.mcName().equals(f.mcName()) || !r.mcUuid().equals(f.mcUuid())) {
            store.put(f.with(Friend.Relation.FRIEND, f.boxPublic(), r.mcName(), r.mcUuid())); // they renamed
            persist();
        }
    }

    private PresenceRecord fetchRecord(Friend f) {
        byte[] secret = pairSecret(f);
        if (secret == null) return null;
        KeyPair theirKey = Protocol.pairKey(secret, f.signPublic());
        return dht.get(Keys.raw(theirKey.getPublic()), Protocol.PAIR_SALT, ROUTE_MAX_AGE_MS, false)
                .map(item -> Protocol.openRecord(secret, f.signPublic(), item.value()))
                .orElse(null);
    }

    /** Pre-finds the DHT nodes for the shared record written by {@code author}, in the background. */
    private void warmPair(Friend f, byte[] author) {
        byte[] secret = pairSecret(f);
        if (secret == null) return;
        KeyPair key = Protocol.pairKey(secret, author);
        java.util.concurrent.CompletableFuture.runAsync(() -> dht.warm(Keys.raw(key.getPublic()), Protocol.PAIR_SALT));
    }

    private byte[] pairSecret(Friend f) {
        if (f.boxPublic() == null) return null;
        return pairSecrets.computeIfAbsent(f.id(), k -> {
            try {
                return me.sharedSecret(f.boxPublic());
            } catch (GeneralSecurityException e) {
                return null;
            }
        });
    }

    private long nextSeq() {
        return clock.getAsLong() * 16 + (seqCounter.getAndIncrement() & 15);
    }

    private void persist() {
        try {
            store.save();
        } catch (IOException ignored) {
            // kept in memory; saved next time
        }
    }

    /** Best effort: tell friends we've gone offline (otherwise they see it after a few minutes). */
    public void shutdown(long maxWaitMs) {
        LocalStatus l = local;
        long now = clock.getAsLong();
        List<Future<?>> jobs = new ArrayList<>();
        if (l != null) {
            for (Friend f : store.withRelation(Friend.Relation.FRIEND)) {
                byte[] secret = pairSecret(f);
                if (secret == null) continue;
                PresenceRecord r = recordFor(f, l, now, PresenceState.OFFLINE);
                jobs.add(pool.submit(() -> dht.put(MutableItem.sign(Protocol.pairKey(secret, me.signPublic()),
                        Protocol.PAIR_SALT, nextSeq(), Protocol.sealRecord(secret, me.signPublic(), r)))));
            }
        }
        long deadline = System.currentTimeMillis() + maxWaitMs;
        for (Future<?> j : jobs) {
            try {
                j.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                break;
            }
        }
        pool.shutdownNow();
    }
}
