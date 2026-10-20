package dev.devilswarchild.cinderlink.core.dht;

import static org.junit.jupiter.api.Assertions.*;

import dev.devilswarchild.cinderlink.core.identity.Keys;
import java.security.KeyPair;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DhtClientTest {
    FakeDhtNetwork net;

    @BeforeEach
    void up() throws Exception {
        net = new FakeDhtNetwork(30);
    }

    @AfterEach
    void down() {
        net.close();
    }

    private DhtClient client() throws Exception {
        return new DhtClient(net.bootstrap(), true);
    }

    @Test
    void putThenGetFromAnotherClient() throws Exception {
        KeyPair key = Keys.newEd25519();
        byte[] salt = "presence".getBytes();
        try (DhtClient a = client(); DhtClient b = client()) {
            int stored = a.put(key, salt, "hello friend".getBytes());
            assertTrue(stored >= 6, "stored on " + stored);
            assertTrue(net.nodesStoring(MutableItem.target(Keys.raw(key.getPublic()), salt)) >= 6);
            Optional<MutableItem> got = b.get(Keys.raw(key.getPublic()), salt);
            assertTrue(got.isPresent());
            assertArrayEquals("hello friend".getBytes(), got.get().value());
        }
    }

    @Test
    void newerValueWins() throws Exception {
        KeyPair key = Keys.newEd25519();
        try (DhtClient a = client()) {
            a.put(key, new byte[0], "one".getBytes());
            a.put(key, new byte[0], "two".getBytes());
            assertArrayEquals("two".getBytes(), a.get(Keys.raw(key.getPublic()), new byte[0]).orElseThrow().value());
        }
    }

    @Test
    void missingRecordIsEmpty() throws Exception {
        try (DhtClient a = client()) {
            assertTrue(a.get(Keys.raw(Keys.newEd25519().getPublic()), "x".getBytes()).isEmpty());
        }
    }

    @Test
    void forgedRecordsFromLyingNodesAreIgnored() throws Exception {
        KeyPair real = Keys.newEd25519();
        byte[] salt = "s".getBytes();
        try (DhtClient a = client()) {
            a.put(real, salt, "genuine".getBytes());
            // Every node now claims a newer value, signed by an attacker's key...
            MutableItem byAttacker = MutableItem.sign(Keys.newEd25519(), salt, Long.MAX_VALUE / 2, "evil".getBytes());
            // ...or with the real key but a tampered value.
            MutableItem genuine = MutableItem.sign(real, salt, Long.MAX_VALUE / 2, "evil".getBytes());
            MutableItem tampered = new MutableItem(genuine.publicKey(), salt, genuine.seq(), "EVIL".getBytes(),
                    genuine.signature());
            for (int i = 0; i < net.nodes.size(); i++) net.nodes.get(i).liar = i % 2 == 0 ? byAttacker : tampered;
            assertTrue(a.get(Keys.raw(real.getPublic()), salt).isEmpty());
        }
    }

    @Test
    void decoderSurvivesGarbage() {
        Random r = new Random(42);
        for (int i = 0; i < 20000; i++) {
            byte[] junk = new byte[r.nextInt(200)];
            r.nextBytes(junk);
            if (i % 3 == 0 && junk.length > 0) junk[0] = (byte) "dli1".charAt(r.nextInt(4));
            try {
                Bencode.decode(junk);
            } catch (Bencode.DecodeException expected) {
                // fine
            }
        }
        assertThrows(Bencode.DecodeException.class, () -> Bencode.decode("d".repeat(40).getBytes()));
        assertThrows(Bencode.DecodeException.class, () -> Bencode.decode("99999999999:x".getBytes()));
    }

    @Test
    void signingBufferMatchesBep44Example() throws Exception {
        // BEP 44 test vector: salt "foobar", seq 1, v "Hello World!"
        byte[] buf = MutableItem.signingBuffer("foobar".getBytes(), 1, "Hello World!".getBytes());
        assertEquals("4:salt6:foobar3:seqi1e1:v12:Hello World!", new String(buf));
    }
}
