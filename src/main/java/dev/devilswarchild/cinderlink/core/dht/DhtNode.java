package dev.devilswarchild.cinderlink.core.dht;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A DHT node: 20-byte id plus IPv4 address. Ids may be null for bootstrap routers we haven't heard from yet. */
public record DhtNode(byte[] id, InetSocketAddress address) {
    static final int COMPACT_SIZE = 26;

    /** Parses BEP 5 "compact node info": 20-byte id + 4-byte IPv4 + 2-byte port, repeated. */
    static List<DhtNode> parseCompact(byte[] data, boolean allowLocal) {
        List<DhtNode> out = new ArrayList<>();
        if (data == null || data.length % COMPACT_SIZE != 0) return out;
        for (int i = 0; i + COMPACT_SIZE <= data.length; i += COMPACT_SIZE) {
            byte[] id = Arrays.copyOfRange(data, i, i + 20);
            int port = ((data[i + 24] & 0xFF) << 8) | (data[i + 25] & 0xFF);
            try {
                InetAddress ip = InetAddress.getByAddress(Arrays.copyOfRange(data, i + 20, i + 24));
                if (port == 0) continue;
                if (!allowLocal && !isRoutable(ip)) continue; // never let the DHT point us at LAN hosts
                out.add(new DhtNode(id, new InetSocketAddress(ip, port)));
            } catch (UnknownHostException ignored) {
                // not possible for 4 raw bytes
            }
        }
        return out;
    }

    static byte[] toCompact(List<DhtNode> nodes) {
        byte[] out = new byte[nodes.size() * COMPACT_SIZE];
        int i = 0;
        for (DhtNode n : nodes) {
            if (n.id == null || !(n.address.getAddress() instanceof Inet4Address)) continue;
            System.arraycopy(n.id, 0, out, i, 20);
            System.arraycopy(n.address.getAddress().getAddress(), 0, out, i + 20, 4);
            out[i + 24] = (byte) (n.address.getPort() >> 8);
            out[i + 25] = (byte) n.address.getPort();
            i += COMPACT_SIZE;
        }
        return Arrays.copyOf(out, i);
    }

    static boolean isRoutable(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
        return !(b0 == 0 || b0 >= 224 || (b0 == 100 && b1 >= 64 && b1 <= 127));
    }

    /** XOR distance comparison to a target: negative if {@code a} is closer than {@code b}. */
    static int compareDistance(byte[] target, byte[] a, byte[] b) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        for (int i = 0; i < 20; i++) {
            int da = (a[i] ^ target[i]) & 0xFF, db = (b[i] ^ target[i]) & 0xFF;
            if (da != db) return Integer.compare(da, db);
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DhtNode n && address.equals(n.address);
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }

    @Override
    public String toString() {
        return "DhtNode[redacted]";
    }
}
