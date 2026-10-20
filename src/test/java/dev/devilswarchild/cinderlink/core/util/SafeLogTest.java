package dev.devilswarchild.cinderlink.core.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SafeLogTest {
    @Test
    void redactsAddressesAndKeys() {
        assertEquals("Hosting on <ip> and <ip6> at 12:30:45",
                SafeLog.redact("Hosting on 73.21.144.9:51234 and [2601:4c1:8:2c0::1a]:51234 at 12:30:45"));
        assertEquals("Link <ip6> and <ip6>", SafeLog.redact("Link fe80::1 and 2001:db8::ff00:42:8329"));
        assertEquals("key <key> ok",
                SafeLog.redact("key 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08 ok"));
    }

    @Test
    void leavesNormalTextAlone() {
        String s = "version 1.21.1 neo 21.1.248 tick 20:00 ratio 3:2 a::b::c";
        assertEquals(s, SafeLog.redact(s));
    }
}
