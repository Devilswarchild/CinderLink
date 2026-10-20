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
    static final long REPUBLISH_MS = 2 * 60_000;
    static final long POLL_MS = 30_000;
    static final long CARD_MS = 30 * 60_000;
    static final long REQUEST_RESEND_MS = 2 * 60_000;
    private static final long MAX_CLOCK_SKEW_MS = 10 * 60_000;

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
    private volatile boolean dirty = true;
    private int cycle;

    public PresenceService(Identity me, FriendsStore store, DhtStore dht, Path dir, LongSupplier clock, int threads) {
        this.me = me;
        this.store = store;
        this.dht = dht;
        this.dir = dir;
        this.clock = clock;
        this.pool = Executors.newFixedThreadPool(threads, r -> {
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
            dirty = true;
        }
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
        store.put(new Friend(code.identityKey(), null, Friend.Relation.OUTGOING, "?", new UUID(0, 0),
                code.inboxSecret(), clock.getAsLong()));
        persist();
        dirty = true;
        return AddResult.SENT;
    }

    public void accept(String id) {
        store.get(id).filter(f -> f.relation() == Friend.Relation.INCOMING).ifPresent(f -> {
            store.put(f.with(Friend.Relation.FRIEND, f.boxPublic(), f.mcName(), f.mcUuid()));
            persist();
            dirty = true;
        });
    }

    public void decline(String id) {
        store.remove(id);
        persist();
    }

    public void remove(String id) {
        store.remove(id);
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
    public void tick() {
        long now = clock.getAsLong();
        cycle++;
        List<Future<?>> jobs = new ArrayList<>();
        if (now - lastCard >= CARD_MS) {
            lastCard = now;
            jobs.add(pool.submit(() -> dht.put(Protocol.card(me, nextSeq()))));
        }
        boolean poll = now - lastPoll >= POLL_MS;
        if (poll) {
            lastPoll = now;
            for (int slot = 0; slot < Protocol.INBOX_SLOTS; slot++) {
                int s = slot;
                jobs.add(pool.submit(() -> readInbox(s)));
            }
        }
        if (poll) {
            for (Friend f : store.withRelation(Friend.Relation.OUTGOING)) {
                jobs.add(pool.submit(() -> handleOutgoing(f, now)));
            }
        }
        boolean publishAll = dirty;
        dirty = false;
        for (Friend f : store.withRelation(Friend.Relation.FRIEND)) {
            if (publishAll || now - lastPublished.getOrDefault(f.id(), Long.MIN_VALUE / 2) >= REPUBLISH_MS) {
                jobs.add(pool.submit(() -> publishTo(f, now)));
            }
            if (poll) jobs.add(pool.submit(() -> readFrom(f, now)));
        }
        for (Future<?> j : jobs) {
            try {
                j.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // a slow or failed lookup just retries next round
            }
        }
    }

    private void readInbox(int slot) {
        KeyPair inbox = Protocol.inboxKey(me.signPublic(), me.inboxSecret(), slot);
        dht.get(Keys.raw(inbox.getPublic()), Protocol.INBOX_SALT).ifPresent(item -> {
            Protocol.Request req = Protocol.openRequest(me, item.value());
            if (req == null || Arrays.equals(req.signPublic(), me.signPublic())) return;
            long now = clock.getAsLong();
            if (Math.abs(now - req.timestampMs()) > 24 * 3600_000L) return; // stale or bogus time
            String id = HexFormat.of().formatHex(req.signPublic());
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
            Friend incoming = new Friend(req.signPublic(), req.boxPublic(), Friend.Relation.INCOMING, req.mcName(),
                    req.mcUuid(), null, now);
            if (store.addIncoming(incoming)) {
                persist();
                listener.requestReceived(incoming);
            }
        });
    }

    private void handleOutgoing(Friend f, long now) {
        LocalStatus l = local;
        if (l == null) return;
        Friend current = f;
        if (current.boxPublic() == null) {
            Optional<MutableItem> card = dht.get(current.signPublic(), Protocol.CARD_SALT);
            byte[] box = card.map(Protocol::cardBoxKey).orElse(null);
            if (box == null) return; // they haven't been online recently; try again later
            current = new Friend(current.signPublic(), box, Friend.Relation.OUTGOING, current.mcName(),
                    current.mcUuid(), current.inboxSecret(), current.addedAtMs());
            store.put(current);
            persist();
        }
        // Accepted? Then they're publishing a record for us.
        PresenceRecord theirs = fetchRecord(current);
        if (theirs != null) {
            Friend accepted = current.with(Friend.Relation.FRIEND, current.boxPublic(), theirs.mcName(), theirs.mcUuid());
            store.put(accepted);
            latest.put(accepted.id(), theirs);
            persist();
            publishTo(accepted, now); // answer right away instead of waiting a round
            listener.requestAccepted(accepted);
            return;
        }
        if (now - lastRequestSent.getOrDefault(current.id(), Long.MIN_VALUE / 2) < REQUEST_RESEND_MS) return;
        try {
            byte[] sealed = Protocol.sealRequest(me, l.mcName(), l.mcUuid(), now, current.signPublic(), current.boxPublic());
            int slot = Math.floorMod(Arrays.hashCode(me.signPublic()) + cycle, Protocol.INBOX_SLOTS);
            KeyPair inbox = Protocol.inboxKey(current.signPublic(), current.inboxSecret(), slot);
            if (dht.put(MutableItem.sign(inbox, Protocol.INBOX_SALT, nextSeq(), sealed)) > 0) {
                lastRequestSent.put(current.id(), now);
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
        PresenceRecord prev = latest.get(f.id());
        if (prev != null && prev.timestampMs() >= r.timestampMs()) return;
        latest.put(f.id(), r);
        boolean wasJoinable = prev != null && prev.state() == PresenceState.HOSTING && !prev.addresses().isEmpty()
                && now - prev.timestampMs() < OFFLINE_AFTER_MS;
        if (r.state() == PresenceState.HOSTING && !r.addresses().isEmpty() && !wasJoinable) {
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
        return dht.get(Keys.raw(theirKey.getPublic()), Protocol.PAIR_SALT)
                .map(item -> Protocol.openRecord(secret, f.signPublic(), item.value()))
                .orElse(null);
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
