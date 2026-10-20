package dev.devilswarchild.cinderlink.core.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Works out how friends can reach a world hosted on {@code port}: UPnP for IPv4, plus a direct IPv6 address.
 * Runs blocking network calls, so never call it on the game thread.
 */
public final class InternetHosting {
    private static final int DISCOVERY_TIMEOUT_MS = 2500;

    private InternetHosting() {}

    /** Why a route didn't work. The client layer turns these into translated messages. */
    public enum Problem {
        UPNP_DISABLED,
        NO_UPNP_ROUTER,
        ROUTER_REFUSED,
        CGNAT,
        DOUBLE_NAT,
        IPV6_DISABLED,
        NO_IPV6
    }

    /**
     * How friends can reach this machine. {@code lan} is for friends on the same home network (routers often
     * can't loop back to their own public address). Addresses are only ever shared inside encrypted records.
     */
    public record Result(Optional<InetSocketAddress> ipv4, Optional<InetSocketAddress> ipv6,
                         Optional<InetSocketAddress> lan, Optional<PortMapping> mapping, List<Problem> problems)
            implements AutoCloseable {
        /** True if friends outside your home network can reach you. */
        public boolean reachableFromInternet() {
            return ipv4.isPresent() || ipv6.isPresent();
        }

        /** Every route, best first, for the encrypted presence record. */
        public List<InetSocketAddress> addresses() {
            List<InetSocketAddress> out = new ArrayList<>();
            lan.ifPresent(out::add);
            ipv6.ifPresent(out::add);
            ipv4.ifPresent(out::add);
            return out;
        }

        @Override
        public void close() {
            mapping.ifPresent(PortMapping::close);
        }
    }

    public static Result open(int port, boolean useUpnp, boolean useIpv6) {
        List<Problem> problems = new ArrayList<>();
        Optional<InetSocketAddress> v4 = Optional.empty();
        Optional<InetSocketAddress> lan = Optional.empty();
        Optional<PortMapping> mapping = Optional.empty();

        if (!useUpnp) {
            problems.add(Problem.UPNP_DISABLED);
        } else {
            Optional<UpnpGateway> gw = SsdpDiscovery.findGateway(DISCOVERY_TIMEOUT_MS);
            if (gw.isPresent() && gw.get().localAddress() instanceof Inet4Address local) {
                lan = Optional.of(new InetSocketAddress(local, port));
            }
            if (gw.isEmpty()) {
                problems.add(Problem.NO_UPNP_ROUTER);
            } else {
                try {
                    InetAddress external = gw.get().externalAddress();
                    if (NetAddresses.isCgnat(external)) {
                        problems.add(Problem.CGNAT);
                    } else if (NetAddresses.isNonPublic(external)) {
                        problems.add(Problem.DOUBLE_NAT);
                    } else {
                        PortMapping m = PortMapping.open(gw.get(), port);
                        mapping = Optional.of(m);
                        v4 = Optional.of(new InetSocketAddress(external, m.externalPort()));
                    }
                } catch (UpnpException e) {
                    problems.add(Problem.ROUTER_REFUSED);
                }
            }
        }

        if (lan.isEmpty()) {
            lan = SsdpDiscovery.candidateLocalAddresses().stream().findFirst()
                    .map(a -> new InetSocketAddress(a, port));
        }
        Optional<InetSocketAddress> v6 = Optional.empty();
        if (!useIpv6) {
            problems.add(Problem.IPV6_DISABLED);
        } else {
            Optional<Inet6Address> addr = NetAddresses.findGlobalIpv6();
            if (addr.isPresent()) {
                v6 = Optional.of(new InetSocketAddress(addr.get(), port));
            } else {
                problems.add(Problem.NO_IPV6);
            }
        }
        return new Result(v4, v6, lan, mapping, List.copyOf(problems));
    }
}
