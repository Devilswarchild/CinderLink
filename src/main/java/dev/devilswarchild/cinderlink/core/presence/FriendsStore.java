package dev.devilswarchild.cinderlink.core.presence;

import dev.devilswarchild.cinderlink.core.util.SecureFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Your friends, pending requests and blocked keys, saved to friends.txt in the CinderLink folder. */
public final class FriendsStore {
    private static final String FILE = "friends.txt";
    private static final int MAX_INCOMING = 50;

    private final Path dir;
    private final Map<String, Friend> friends = new LinkedHashMap<>();
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();
    /**
     * Friend id -> ignore their requests stamped at or before this time. Old requests can sit in the DHT
     * mailbox for a while, so without this a removed or declined person would pop straight back up.
     */
    private final Map<String, Long> ignoreRequestsBefore = new ConcurrentHashMap<>();
    private static final long DISMISS_MEMORY_MS = 30L * 24 * 3600_000;

    private FriendsStore(Path dir) {
        this.dir = dir;
    }

    public static FriendsStore load(Path dir) throws IOException {
        FriendsStore s = new FriendsStore(dir);
        List<String> lines;
        try {
            lines = Files.readAllLines(dir.resolve(FILE), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return s;
        }
        Base64.Decoder b = Base64.getDecoder();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\\|", -1);
            try {
                if (p[0].equals("blocked") && p.length == 2) {
                    s.blocked.add(p[1]);
                } else if (p[0].equals("dismissed") && p.length == 3) {
                    s.ignoreRequestsBefore.put(p[1], Long.parseLong(p[2]));
                } else if (p.length == 8) {
                    Friend f = new Friend(b.decode(p[1]), p[2].isEmpty() ? null : b.decode(p[2]),
                            Friend.Relation.valueOf(p[0]), p[3], UUID.fromString(p[4]),
                            p[5].isEmpty() ? null : b.decode(p[5]), Long.parseLong(p[6]));
                    // A request we sent but that isn't accepted yet has no name: they're saved as "?".
                    boolean nameOk = PresenceRecord.validName(f.mcName())
                            || (f.relation() == Friend.Relation.OUTGOING && "?".equals(f.mcName()));
                    if (f.signPublic().length == 32 && nameOk) s.friends.put(f.id(), f);
                }
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ignored) {
                // skip a damaged line rather than losing the whole list
            }
        }
        return s;
    }

    public synchronized void save() throws IOException {
        Base64.Encoder b = Base64.getEncoder();
        StringBuilder sb = new StringBuilder("# CinderLink friends. relation|identity|box|name|uuid|inbox|added|v1\n");
        for (Friend f : friends.values()) {
            sb.append(f.relation()).append('|')
              .append(b.encodeToString(f.signPublic())).append('|')
              .append(f.boxPublic() == null ? "" : b.encodeToString(f.boxPublic())).append('|')
              .append(f.mcName()).append('|')
              .append(f.mcUuid()).append('|')
              .append(f.inboxSecret() == null ? "" : b.encodeToString(f.inboxSecret())).append('|')
              .append(f.addedAtMs()).append("|v1\n");
        }
        for (String k : blocked) sb.append("blocked|").append(k).append('\n');
        long cutoff = System.currentTimeMillis() - DISMISS_MEMORY_MS;
        ignoreRequestsBefore.entrySet().removeIf(e -> e.getValue() < cutoff);
        ignoreRequestsBefore.forEach((k, t) -> sb.append("dismissed|").append(k).append('|').append(t).append('\n'));
        SecureFiles.writeAtomically(dir.resolve(FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public synchronized List<Friend> all() {
        return new ArrayList<>(friends.values());
    }

    public synchronized List<Friend> withRelation(Friend.Relation r) {
        return friends.values().stream().filter(f -> f.relation() == r).toList();
    }

    public synchronized Optional<Friend> get(String id) {
        return Optional.ofNullable(friends.get(id));
    }

    public Optional<Friend> get(byte[] signPublic) {
        return get(HexFormat.of().formatHex(signPublic));
    }

    synchronized void put(Friend f) {
        friends.put(f.id(), f);
    }

    synchronized boolean addIncoming(Friend f) {
        if (blocked.contains(f.id()) || friends.containsKey(f.id())) return false;
        if (withRelation(Friend.Relation.INCOMING).size() >= MAX_INCOMING) return false; // spam cap
        friends.put(f.id(), f);
        return true;
    }

    synchronized void remove(String id) {
        friends.remove(id);
    }

    /** Ignore this person's requests stamped at or before {@code untilMs}. */
    void dismissRequestsUntil(String id, long untilMs) {
        ignoreRequestsBefore.merge(id, untilMs, Math::max);
    }

    /** True if a request from {@code id} stamped {@code requestMs} should be ignored. */
    boolean isDismissed(String id, long requestMs) {
        Long t = ignoreRequestsBefore.get(id);
        return t != null && requestMs <= t;
    }

    synchronized void block(String id) {
        friends.remove(id);
        blocked.add(id);
    }

    public boolean isBlocked(String id) {
        return blocked.contains(id);
    }

    public synchronized Collection<String> blocked() {
        return List.copyOf(blocked);
    }

    synchronized void unblock(String id) {
        blocked.remove(id);
    }
}
