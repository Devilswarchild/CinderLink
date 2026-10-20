package dev.devilswarchild.cinderlink.core.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Finds the router via SSDP multicast (the "M-SEARCH" step of UPnP). */
public final class SsdpDiscovery {
    private static final InetSocketAddress SSDP = new InetSocketAddress("239.255.255.250", 1900);
    private static final List<String> SEARCH_TARGETS = List.of(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1");

    private SsdpDiscovery() {}

    /** A discovery answer: where the router's description lives, and which of our addresses heard it. */
    public record Found(URI location, InetAddress localAddress) {}

    /** Sends M-SEARCH from every usable IPv4 interface and returns the first working gateway. */
    public static Optional<UpnpGateway> findGateway(int timeoutMs) {
        UpnpException last = null;
        for (Found f : search(timeoutMs)) {
            try {
                return Optional.of(UpnpGateway.fromLocation(f.location(), f.localAddress()));
            } catch (UpnpException e) {
                last = e; // try the next responder
            }
        }
        return Optional.empty();
    }

    public static List<Found> search(int timeoutMs) {
        Map<URI, Found> found = new LinkedHashMap<>();
        for (InetAddress local : candidateLocalAddresses()) {
            try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(local, 0))) {
                socket.setSoTimeout(250);
                for (String st : SEARCH_TARGETS) {
                    byte[] msg = mSearch(st).getBytes(StandardCharsets.US_ASCII);
                    socket.send(new DatagramPacket(msg, msg.length, SSDP));
                }
                long deadline = System.currentTimeMillis() + timeoutMs;
                byte[] buf = new byte[2048];
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                    parseLocation(reply, p.getAddress())
                            .ifPresent(loc -> found.putIfAbsent(loc, new Found(loc, local)));
                }
            } catch (IOException ignored) {
                // interface couldn't send multicast; try the next one
            }
            if (!found.isEmpty()) break;
        }
        return new ArrayList<>(found.values());
    }

    static String mSearch(String st) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + st + "\r\n\r\n";
    }

    /**
     * Pulls the LOCATION header out of an SSDP reply. Only accepts http URLs that point at the same LAN host
     * that sent the reply, so a spoofed answer can't send us off to some other machine.
     */
    static Optional<URI> parseLocation(String reply, InetAddress sender) {
        if (!reply.startsWith("HTTP/1.1 200")) return Optional.empty();
        for (String line : reply.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (!line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("location")) continue;
            try {
                URI uri = URI.create(line.substring(colon + 1).trim());
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return Optional.empty();
                InetAddress host = InetAddress.getByName(uri.getHost());
                if (!host.equals(sender) || !NetAddresses.isLocalNetwork(host)) return Optional.empty();
                return Optional.of(uri);
            } catch (IllegalArgumentException | IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Private IPv4 addresses on real, up interfaces. Skips VPN/virtual adapters where possible. */
    static List<InetAddress> candidateLocalAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()
                        || !ni.supportsMulticast()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a);
                }
            }
        } catch (IOException ignored) {
            // no interfaces readable
        }
        return out;
    }
}
