package dev.devilswarchild.cinderlink.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UpnpGatewayTest {
    private static final String IP1 = "urn:schemas-upnp-org:service:WANIPConnection:1";

    private static UpnpGateway connect(FakeRouter r) throws Exception {
        return UpnpGateway.fromLocation(r.location(), InetAddress.getByName("192.168.1.50"));
    }

    @Test
    void findsServiceAndReadsExternalAddress() throws Exception {
        try (FakeRouter r = new FakeRouter(IP1)) {
            r.controlHandler = b -> new String[]{"200",
                    FakeRouter.ok("GetExternalIPAddress", "<NewExternalIPAddress>81.2.69.142</NewExternalIPAddress>")};
            UpnpGateway gw = connect(r);
            assertEquals(IP1, gw.serviceType());
            assertEquals(InetAddress.getByName("81.2.69.142"), gw.externalAddress());
            assertTrue(r.requests.get(0).startsWith("\"" + IP1 + "#GetExternalIPAddress\""));
        }
    }

    @Test
    void prefersIpConnectionV2() throws Exception {
        try (FakeRouter r = new FakeRouter("urn:schemas-upnp-org:service:WANIPConnection:2")) {
            assertEquals("urn:schemas-upnp-org:service:WANIPConnection:2", connect(r).serviceType());
        }
    }

    @Test
    void sendsCorrectMappingRequest() throws Exception {
        try (FakeRouter r = new FakeRouter(IP1)) {
            r.controlHandler = b -> new String[]{"200", FakeRouter.ok("AddPortMapping", "")};
            connect(r).addPortMapping(51234, 51234, "TCP", "CinderLink", 3600);
            String req = r.requests.get(0);
            assertTrue(req.contains("<NewExternalPort>51234</NewExternalPort>"));
            assertTrue(req.contains("<NewInternalClient>192.168.1.50</NewInternalClient>"));
            assertTrue(req.contains("<NewLeaseDuration>3600</NewLeaseDuration>"));
            assertTrue(req.contains("<NewPortMappingDescription>CinderLink</NewPortMappingDescription>"));
        }
    }

    @Test
    void routerFaultBecomesErrorCode() throws Exception {
        try (FakeRouter r = new FakeRouter(IP1)) {
            r.controlHandler = b -> new String[]{"500", FakeRouter.fault(718, "ConflictInMappingEntry")};
            UpnpException e = assertThrows(UpnpException.class,
                    () -> connect(r).addPortMapping(1, 1, "TCP", "x", 0));
            assertEquals(UpnpException.CONFLICT_IN_MAPPING, e.code);
        }
    }

    @Test
    void rejectsXxeAndDoctype() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root>&x;</root>";
        assertThrows(UpnpException.class, () -> SafeXml.parse(evil.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsControlUrlOnAnotherHost() throws Exception {
        String desc = "<root><device><serviceList><service><serviceType>" + IP1 + "</serviceType>"
                + "<controlURL>http://evil.example.com/ctl</controlURL></service></serviceList></device></root>";
        assertThrows(UpnpException.class, () -> UpnpGateway.fromDescription(desc.getBytes(StandardCharsets.UTF_8),
                URI.create("http://192.168.1.1:5000/desc.xml"), InetAddress.getByName("192.168.1.50")));
    }

    @Test
    void escapesArgumentValues() throws Exception {
        try (FakeRouter r = new FakeRouter(IP1)) {
            r.controlHandler = b -> new String[]{"200", FakeRouter.ok("AddPortMapping", "")};
            connect(r).addPortMapping(1, 1, "TCP", "a<b>&c", 0);
            assertTrue(r.requests.get(0).contains("a&lt;b&gt;&amp;c"));
        }
    }
}
