package dev.devilswarchild.cinderlink.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PortMappingTest {
    private static final String IP1 = "urn:schemas-upnp-org:service:WANIPConnection:1";

    @Test
    void mapsThenDeletesOnClose() throws Exception {
        try (FakeRouter r = new FakeRouter(IP1)) {
            r.controlHandler = b -> new String[]{"200",
                    FakeRouter.ok(b.contains("DeletePortMapping") ? "DeletePortMapping" : "AddPortMapping", "")};
            UpnpGateway gw = UpnpGateway.fromLocation(r.location(), InetAddress.getByName("192.168.1.50"));
            PortMapping m = PortMapping.open(gw, 51234);
            assertEquals(51234, m.externalPort());
            m.close();
            m.close(); // idempotent
            long deletes = r.requests.stream().filter(s -> s.contains("#DeletePortMapping")).count();
            assertEquals(1, deletes);
        }
    }

    @Test
    void picksAnotherPortOnConflict() throws Exception {
        AtomicInteger adds = new AtomicInteger();
        try (FakeRouter r = new FakeRouter(IP1)) {
            r.controlHandler = b -> {
                if (b.contains("AddPortMapping") && adds.getAndIncrement() == 0) {
                    return new String[]{"500", FakeRouter.fault(718, "ConflictInMappingEntry")};
                }
                return new String[]{"200", FakeRouter.ok("AddPortMapping", "")};
            };
            UpnpGateway gw = UpnpGateway.fromLocation(r.location(), InetAddress.getByName("192.168.1.50"));
            try (PortMapping m = PortMapping.open(gw, 51234)) {
                assertNotEquals(51234, m.externalPort());
                assertTrue(m.externalPort() >= 20000 && m.externalPort() < 60000);
            }
        }
    }

    @Test
    void fallsBackToPermanentLease() throws Exception {
        try (FakeRouter r = new FakeRouter(IP1)) {
            r.controlHandler = b -> b.contains("<NewLeaseDuration>3600</NewLeaseDuration>")
                    ? new String[]{"500", FakeRouter.fault(725, "OnlyPermanentLeasesSupported")}
                    : new String[]{"200", FakeRouter.ok("AddPortMapping", "")};
            UpnpGateway gw = UpnpGateway.fromLocation(r.location(), InetAddress.getByName("192.168.1.50"));
            try (PortMapping m = PortMapping.open(gw, 51234)) {
                assertTrue(r.requests.stream().anyMatch(s -> s.contains("<NewLeaseDuration>0</NewLeaseDuration>")));
            }
        }
    }
}
