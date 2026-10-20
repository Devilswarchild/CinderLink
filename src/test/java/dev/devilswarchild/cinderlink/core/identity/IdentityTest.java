package dev.devilswarchild.cinderlink.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityTest {
    @TempDir Path dir;

    @Test
    void codeRoundTripsAndLooksRight() {
        Identity id = Identity.create();
        String text = id.code().format();
        assertTrue(text.matches("CNDR(-[0-9A-HJKMNP-TV-Z]{8}){8}"), text);
        assertEquals(id.code(), CinderCode.parse(text));
        // forgiving about spacing, case and look-alikes
        assertEquals(id.code(), CinderCode.parse("  " + text.toLowerCase().replace("-", " ") + "\n"));
    }

    @Test
    void checksumCatchesTypos() {
        String text = Identity.create().code().format();
        char[] c = text.toCharArray();
        int i = 10;
        c[i] = c[i] == 'A' ? 'B' : 'A';
        assertThrows(IllegalArgumentException.class, () -> CinderCode.parse(new String(c)));
        assertThrows(IllegalArgumentException.class, () -> CinderCode.parse(text.substring(0, text.length() - 3)));
        assertThrows(IllegalArgumentException.class, () -> CinderCode.parse("hello"));
    }

    @Test
    void savesAndReloadsSameIdentity() throws Exception {
        Identity a = Identity.loadOrCreate(dir);
        Identity b = Identity.loadOrCreate(dir);
        assertArrayEquals(a.signPublic(), b.signPublic());
        assertArrayEquals(a.boxPublic(), b.boxPublic());
        assertEquals(a.code(), b.code());
        byte[] msg = "hi".getBytes();
        assertTrue(Keys.verify(a.signPublic(), msg, b.sign(msg)));
    }

    @Test
    void resetChangesCodeButNotIdentity() throws Exception {
        Identity a = Identity.loadOrCreate(dir);
        CinderCode before = a.code();
        a.resetCode(dir);
        Identity reloaded = Identity.loadOrCreate(dir);
        assertNotEquals(before, reloaded.code());
        assertArrayEquals(before.identityKey(), reloaded.signPublic());
    }

    @Test
    void damagedFileIsRejectedNotSilentlyReplaced() throws Exception {
        Identity.loadOrCreate(dir);
        Path f = dir.resolve("identity.properties");
        Files.writeString(f, Files.readString(f).replaceFirst("sign.public=..", "sign.public=AA"));
        assertThrows(java.io.IOException.class, () -> Identity.loadOrCreate(dir));
    }

    @Test
    void sharedSecretsMatchBothWays() throws Exception {
        Identity a = Identity.create(), b = Identity.create();
        assertArrayEquals(a.sharedSecret(b.boxPublic()), b.sharedSecret(a.boxPublic()));
        assertFalse(Arrays.equals(a.sharedSecret(b.boxPublic()), a.sharedSecret(Identity.create().boxPublic())));
    }

    @Test
    void seededKeysAreDeterministic() {
        byte[] seed = Keys.sha256("x".getBytes());
        KeyPair k1 = Keys.ed25519FromSeed(seed), k2 = Keys.ed25519FromSeed(seed);
        assertArrayEquals(Keys.raw(k1.getPublic()), Keys.raw(k2.getPublic()));
        byte[] m = "m".getBytes();
        assertTrue(Keys.verify(Keys.raw(k1.getPublic()), m, Keys.sign(k2.getPrivate(), m)));
    }
}
