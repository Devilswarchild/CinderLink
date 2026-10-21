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
    void survivesAMessyRealisticNetwork() throws Exception {
        // Like the real DHT: the entry point only speaks find_node, a third of nodes never answer,
        // and a fifth don't support BEP 44 records at all.
        try (FakeDhtNetwork messy = new FakeDhtNetwork(150, 33, 20, true);
             DhtClient a = new DhtClient(messy.bootstrap(), true);
             DhtClient b = new DhtClient(messy.bootstrap(), true)) {
            KeyPair key = Keys.newEd25519();
            byte[] salt = "card".getBytes();
            int stored = a.put(key, salt, "still works".getBytes());
            assertTrue(stored >= 3, "stored on " + stored + " / " + a.statsLine());
            Optional<MutableItem> got = b.get(Keys.raw(key.getPublic()), salt);
            assertTrue(got.isPresent(), b.statsLine());
            assertArrayEquals("still works".getBytes(), got.get().value());
        }
    }

    @Test
    void repeatChecksUseTheRememberedRouteAndAreFast() throws Exception {
        try (FakeDhtNetwork messy = new FakeDhtNetwork(150, 33, 20, true);
             DhtClient writer = new DhtClient(messy.bootstrap(), true);
             DhtClient reader = new DhtClient(messy.bootstrap(), true)) {
            KeyPair key = Keys.newEd25519();
            byte[] pub = Keys.raw(key.getPublic()), salt = "p".getBytes();
            writer.put(key, salt, "v1".getBytes());
            assertArrayEquals("v1".getBytes(), reader.get(pub, salt).orElseThrow().value());
            long searches = reader.fullLookups();

            // The friend updates their status; we see it via one quick round trip, no new search.
            writer.put(key, salt, "v2".getBytes());
            long start = System.nanoTime();
            var got = reader.get(pub, salt);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertArrayEquals("v2".getBytes(), got.orElseThrow().value());
            assertEquals(searches, reader.fullLookups(), "should not have searched again");
            assertTrue(reader.fastOps() >= 1);
            assertTrue(ms < 900, "fast get took " + ms + " ms");
            assertTrue(writer.fastOps() >= 1, "second put should reuse the route: " + writer.statsLine());
        }
    }

    @Test
    void fallsBackToAFullSearchWhenRememberedNodesDie() throws Exception {
        try (FakeDhtNetwork net = new FakeDhtNetwork(40);
             DhtClient c = new DhtClient(net.bootstrap(), true)) {
            KeyPair key = Keys.newEd25519();
            byte[] pub = Keys.raw(key.getPublic()), salt = "p".getBytes();
            c.put(key, salt, "x".getBytes());
            assertTrue(c.get(pub, salt).isPresent());
            // The nodes holding the record all go offline at once.
            byte[] target = MutableItem.target(pub, salt);
            for (var n : net.nodes) {
                if (n.store.containsKey(java.util.HexFormat.of().formatHex(target))) n.kind = FakeDhtNetwork.Kind.SILENT;
            }
            long searches = c.fullLookups();
            c.put(key, salt, "y".getBytes()); // re-stored on the next-closest live nodes
            assertTrue(c.fullLookups() > searches, "should have searched again");
            assertArrayEquals("y".getBytes(), c.get(pub, salt).orElseThrow().value());
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
