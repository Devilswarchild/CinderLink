package dev.devilswarchild.cinderlink.core.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Address classification helpers. No network calls happen here except reading local interfaces. */
public final class NetAddresses {
    private NetAddresses() {}

    /** True for addresses that can't be reached from the internet: private, loopback, link-local, CGNAT, etc. */
    public static boolean isNonPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;   // 100.64.0.0/10 carrier-grade NAT
            if (b0 == 0) return true;                               // 0.0.0.0/8
            if (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 0) return true; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;  // benchmarking
            if (b0 >= 224) return true;                             // multicast + reserved
            return false;
        }
        return !isGlobalUnicastV6(a);
    }

    /** True when the address is a carrier-grade NAT address (100.64.0.0/10). */
    public static boolean isCgnat(InetAddress a) {
        if (!(a instanceof Inet4Address)) return false;
        byte[] b = a.getAddress();
        return (b[0] & 0xFF) == 100 && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127;
    }

    /** True if the address could be on the same home network as us (used to sanity-check router replies). */
    public static boolean isLocalNetwork(InetAddress a) {
        return a.isSiteLocalAddress() || a.isLinkLocalAddress() || isCgnat(a)
                || (a instanceof Inet6Address && ((a.getAddress()[0] & 0xFE) == 0xFC)); // fc00::/7 unique local
    }

    /** Global unicast IPv6 is 2000::/3, excluding the documentation range 2001:db8::/32. */
    static boolean isGlobalUnicastV6(InetAddress a) {
        if (!(a instanceof Inet6Address)) return false;
        byte[] b = a.getAddress();
        if ((b[0] & 0xE0) != 0x20) return false;
        return !((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8);
    }

    /** Whether this JVM can use IPv6 at all (some launchers force IPv4 only). */
    public static boolean ipv6StackAvailable() {
        return !Boolean.getBoolean("java.net.preferIPv4Stack");
    }

    /**
     * Picks a global IPv6 address from an up, non-virtual interface. Returns empty if none.
     * Note: whether friends can actually reach it still depends on the router's IPv6 firewall.
     */
    public static Optional<Inet6Address> findGlobalIpv6() {
        if (!ipv6StackAvailable()) return Optional.empty();
        List<Inet6Address> found = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet6Address v6 && isGlobalUnicastV6(v6)) found.add(stripScope(v6));
                }
            }
        } catch (SocketException e) {
            return Optional.empty();
        }
        return found.stream().findFirst();
    }

    private static Inet6Address stripScope(Inet6Address a) {
        try {
            return (Inet6Address) InetAddress.getByAddress(a.getAddress());
        } catch (Exception e) {
            return a;
        }
    }

    /** Formats host:port the way Minecraft's Direct Connect expects ([v6]:port for IPv6). */
    public static String joinAddress(InetAddress a, int port) {
        String host = a.getHostAddress();
        int pct = host.indexOf('%');
        if (pct >= 0) host = host.substring(0, pct);
        return (a instanceof Inet6Address ? "[" + host + "]" : host) + ":" + port;
    }
}
