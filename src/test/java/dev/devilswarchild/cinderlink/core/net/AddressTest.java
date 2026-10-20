package dev.devilswarchild.cinderlink.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AddressTest {
    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void classifiesAddresses() throws Exception {
        assertFalse(NetAddresses.isNonPublic(ip("81.2.69.142")));
        assertTrue(NetAddresses.isNonPublic(ip("192.168.1.1")));
        assertTrue(NetAddresses.isNonPublic(ip("10.0.0.5")));
        assertTrue(NetAddresses.isNonPublic(ip("100.72.1.9")));
        assertTrue(NetAddresses.isCgnat(ip("100.72.1.9")));
        assertFalse(NetAddresses.isCgnat(ip("100.128.0.1")));
        assertFalse(NetAddresses.isNonPublic(ip("2606:4700::1111")));
        assertTrue(NetAddresses.isNonPublic(ip("fe80::1")));
        assertTrue(NetAddresses.isNonPublic(ip("fd12:3456::1")));
        assertTrue(NetAddresses.isNonPublic(ip("2001:db8::1")));
    }

    @Test
    void formatsJoinAddresses() throws Exception {
        assertEquals("81.2.69.142:51234", NetAddresses.joinAddress(ip("81.2.69.142"), 51234));
        assertEquals("[2606:4700:0:0:0:0:0:1111]:51234", NetAddresses.joinAddress(ip("2606:4700::1111"), 51234));
    }

    @Test
    void parsesSsdpLocationOnlyFromSender() throws Exception {
        String reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\n"
                + "Location: http://192.168.1.1:5000/rootDesc.xml\r\nST: x\r\n\r\n";
        assertEquals(Optional.of(URI.create("http://192.168.1.1:5000/rootDesc.xml")),
                SsdpDiscovery.parseLocation(reply, ip("192.168.1.1")));
        // a different LAN device claiming to be the router
        assertTrue(SsdpDiscovery.parseLocation(reply, ip("192.168.1.66")).isEmpty());
        // pointing at the internet
        String remote = reply.replace("192.168.1.1", "81.2.69.142");
        assertTrue(SsdpDiscovery.parseLocation(remote, ip("81.2.69.142")).isEmpty());
        // not http
        assertTrue(SsdpDiscovery.parseLocation(reply.replace("http://", "file://"), ip("192.168.1.1")).isEmpty());
    }
}
