package dev.devilswarchild.cinderlink.core.presence;

import dev.devilswarchild.cinderlink.core.dht.Bencode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What one player tells one friend, encrypted so only that friend can read it.
 * {@code addresses} is only filled when hosting AND the friend is allowed to join this session.
 */
public record PresenceRecord(PresenceState state, String mcName, UUID mcUuid, String world,
                             List<InetSocketAddress> addresses, long timestampMs, boolean ended) {
    public static final int MAX_WORLD_NAME = 48;
    private static final int MAX_ADDRESSES = 4;

    public PresenceRecord(PresenceState state, String mcName, UUID mcUuid, String world,
                          List<InetSocketAddress> addresses, long timestampMs) {
        this(state, mcName, mcUuid, world, addresses, timestampMs, false);
    }

    /** The last record someone writes for a friend they removed: "we're not friends any more". */
    static PresenceRecord unfriended(String mcName, UUID mcUuid, long now) {
        return new PresenceRecord(PresenceState.OFFLINE, mcName, mcUuid, "", List.of(), now, true);
    }

    public PresenceRecord {
        world = world == null ? "" : sanitize(world);
        addresses = addresses == null ? List.of() : List.copyOf(addresses.subList(0, Math.min(addresses.size(), MAX_ADDRESSES)));
    }

    byte[] encode() {
        Map<String, Object> m = new TreeMap<>();
        m.put("s", (long) state.ordinal());
        m.put("n", mcName);
        m.put("u", uuidBytes(mcUuid));
        m.put("w", world);
        List<Object> addrs = new ArrayList<>();
        for (InetSocketAddress a : addresses) {
            byte[] ip = a.getAddress().getAddress();
            byte[] b = Arrays.copyOf(ip, ip.length + 2);
            b[ip.length] = (byte) (a.getPort() >> 8);
            b[ip.length + 1] = (byte) a.getPort();
            addrs.add(b);
        }
        m.put("a", addrs);
        m.put("t", timestampMs);
        if (ended) m.put("x", 1L);
        return Bencode.encode(m);
    }

    /** Strict parse of decrypted bytes; anything odd is rejected rather than half-trusted. */
    static PresenceRecord decode(byte[] data) {
        try {
            Object o = Bencode.decode(data);
            Long s = Bencode.integer(o, "s"), t = Bencode.integer(o, "t");
            String name = Bencode.str(o, "n"), world = Bencode.str(o, "w");
            byte[] u = Bencode.bytes(o, "u");
            if (s == null || t == null || name == null || world == null || u == null || u.length != 16) return null;
            if (s < 0 || s >= PresenceState.values().length || !validName(name)) return null;
            List<InetSocketAddress> addrs = new ArrayList<>();
            if (((Map<?, ?>) o).get("a") instanceof List<?> l) {
                for (Object e : l) {
                    if (!(e instanceof byte[] b) || (b.length != 6 && b.length != 18)) return null;
                    int port = ((b[b.length - 2] & 0xFF) << 8) | (b[b.length - 1] & 0xFF);
                    if (port == 0) return null;
                    addrs.add(new InetSocketAddress(InetAddress.getByAddress(Arrays.copyOf(b, b.length - 2)), port));
                }
            }
            Long x = Bencode.integer(o, "x");
            boolean ended = x != null && x == 1L;
            return new PresenceRecord(PresenceState.values()[s.intValue()], name, uuid(u), world, addrs, t, ended);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9_]{1,16}");
    }

    /** Plain text only: no formatting codes, control characters or newlines, and a length cap. */
    static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX_WORLD_NAME; i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++; // skip formatting code and its argument
                continue;
            }
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) continue;
            sb.append(c);
        }
        return sb.toString().strip();
    }

    static byte[] uuidBytes(UUID u) {
        byte[] b = new byte[16];
        long hi = u.getMostSignificantBits(), lo = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (hi >>> (56 - 8 * i));
            b[8 + i] = (byte) (lo >>> (56 - 8 * i));
        }
        return b;
    }

    static UUID uuid(byte[] b) {
        long hi = 0, lo = 0;
        for (int i = 0; i < 8; i++) {
            hi = (hi << 8) | (b[i] & 0xFF);
            lo = (lo << 8) | (b[8 + i] & 0xFF);
        }
        return new UUID(hi, lo);
    }

    @Override
    public String toString() {
        return "PresenceRecord[" + state + ", " + mcName + ", addresses redacted]";
    }
}
