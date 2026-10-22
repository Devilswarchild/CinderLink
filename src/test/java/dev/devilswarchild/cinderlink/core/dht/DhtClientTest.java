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
            // put() returns once a few nodes confirm; the rest finish storing in the background.
            assertTrue(stored >= DhtClient.PUT_CONFIRMATIONS, "stored on " + stored);
            byte[] target = MutableItem.target(Keys.raw(key.getPublic()), salt);
            long deadline = System.currentTimeMillis() + 3_000;
            while (net.nodesStoring(target) < 6 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertTrue(net.nodesStoring(target) >= 6, "only " + net.nodesStoring(target) + " nodes stored it");
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
    void routesInUseAreRenewedInTheBackground() throws Exception {
        try (FakeDhtNetwork messy = new FakeDhtNetwork(150, 33, 20, true);
             DhtClient writer = new DhtClient(messy.bootstrap(), true);
             DhtClient reader = new DhtClient(messy.bootstrap(), true)) {
            writer.refreshAtMs = 1_000; // instead of 75 seconds
            reader.refreshAtMs = 1_000;
            var key = Keys.newEd25519();
            byte[] salt = "renew".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(writer.put(key, salt, "v1".getBytes()) > 0);
            assertTrue(reader.get(Keys.raw(key.getPublic()), salt).isPresent());
            long deadline = System.currentTimeMillis() + 15_000;
            while ((writer.renewals() == 0 || reader.renewals() == 0) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertTrue(writer.renewals() > 0, "writer route not renewed: " + writer.statsLine());
            assertTrue(reader.renewals() > 0, "reader route not renewed: " + reader.statsLine());
            // Renewed routes still give the quick path, and the reader sees the writer's update.
            long fast = writer.fastOps();
            assertTrue(writer.put(key, salt, "v2".getBytes()) > 0);
            assertTrue(writer.fastOps() > fast, writer.statsLine());
            assertEquals("v2", new String(reader.get(Keys.raw(key.getPublic()), salt).orElseThrow().value()));
        }
    }

    @Test
    void storeDuringARunningSearchJoinsItAndLandsEarly() throws Exception {
        // Like the real network: a third of nodes never answer, replies take 100-400 ms.
        try (FakeDhtNetwork slow = new FakeDhtNetwork(300, 33, 10, true);
             DhtClient writer = new DhtClient(slow.bootstrap(), true);
             DhtClient reader = new DhtClient(slow.bootstrap(), true)) {
            slow.minDelayMs = 100;
            slow.maxDelayMs = 400;
            KeyPair key = Keys.newEd25519();
            byte[] salt = "mailbox".getBytes();
            byte[] pub = Keys.raw(key.getPublic());
            reader.get(pub, salt); // the reader already watches its mailbox (remembered route)
            writer.warm(Keys.raw(Keys.newEd25519().getPublic()), salt); // writer knows some of the network

            // The code is pasted: a search for the mailbox starts. Send is clicked 300 ms later.
            long searchesBefore = writer.fullLookups();
            Thread warming = new Thread(() -> writer.warm(pub, salt));
            long t0 = System.currentTimeMillis();
            warming.start();
            Thread.sleep(300);
            long sendAt = System.currentTimeMillis();
            Thread putter = new Thread(() -> writer.put(key, salt, "hi".getBytes()));
            putter.start();
            long seenAt = -1;
            while (System.currentTimeMillis() - sendAt < 15_000) {
                if (reader.get(pub, salt).isPresent()) {
                    seenAt = System.currentTimeMillis();
                    break;
                }
                Thread.sleep(50);
            }
            putter.join(15_000);
            warming.join(15_000);
            long searchDone = System.currentTimeMillis();
            System.out.println("SLOWNET reader saw it " + (seenAt - sendAt) + " ms after Send; search+store finished "
                    + (searchDone - t0) + " ms after paste; " + writer.statsLine());
            assertTrue(seenAt > 0, "reader never saw the record");
            assertEquals(searchesBefore + 1, writer.fullLookups(), "Send should join the running search, not start another");
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
