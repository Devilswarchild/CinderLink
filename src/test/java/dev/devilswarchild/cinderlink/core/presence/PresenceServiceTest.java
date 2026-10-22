package dev.devilswarchild.cinderlink.core.presence;

import static org.junit.jupiter.api.Assertions.*;

import dev.devilswarchild.cinderlink.core.dht.DhtStore;
import dev.devilswarchild.cinderlink.core.identity.Identity;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresenceServiceTest {
    @TempDir Path tmp;
    final MemoryDht dht = new MemoryDht();
    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);

    final class Player {
        final String name;
        final Path dir;
        final Identity id;
        final PresenceService svc;
        final List<String> events = new CopyOnWriteArrayList<>();

        Player(String name) throws Exception {
            this(name, dht);
        }

        Player(String name, DhtStore store) throws Exception {
            this(name, store, Files.createDirectories(tmp.resolve(name)));
        }

        Player(String name, DhtStore store, Path dir) throws Exception {
            this.name = name;
            this.dir = dir;
            this.id = Identity.loadOrCreate(dir);
            this.svc = new PresenceService(id, FriendsStore.load(dir), store, dir, clock::get, 4);
            svc.setLocal(new PresenceService.LocalStatus(name, UUID.nameUUIDFromBytes(name.getBytes()), null));
            svc.setListener(new PresenceService.Listener() {
                @Override
                public void requestReceived(Friend from) {
                    events.add("request:" + from.mcName());
                }

                @Override
                public void requestAccepted(Friend by) {
                    events.add("accepted:" + by.mcName());
                }

                @Override
                public void friendStartedHosting(PresenceService.FriendView v) {
                    events.add("hosting:" + v.friend().mcName() + ":" + v.world());
                }
            });
        }

        String code() {
            return id.code().format();
        }

        PresenceService.FriendView view(Player other) {
            return svc.views().stream().filter(v -> v.friend().id().equals(other.idHex())).findFirst().orElse(null);
        }

        String idHex() {
            return java.util.HexFormat.of().formatHex(id.signPublic());
        }

        void host(String world, Set<Player> visible) throws Exception {
            var addrs = List.of(new InetSocketAddress(InetAddress.getByName("81.2.69.142"), 51234));
            Set<String> ids = new java.util.HashSet<>();
            for (Player p : visible) ids.add(p.idHex());
            svc.setLocal(new PresenceService.LocalStatus(name, UUID.nameUUIDFromBytes(name.getBytes()),
                    new PresenceService.Hosting(world, addrs, ids)));
        }
    }

    /** Advances the clock and lets everyone do a round of network work. */
    void rounds(int n, Player... players) {
        for (int i = 0; i < n; i++) {
            clock.addAndGet(PresenceService.POLL_MS);
            for (Player p : players) p.svc.tick();
        }
    }

    void befriend(Player a, Player b) {
        assertEquals(PresenceService.AddResult.SENT, a.svc.addByCode(b.code()));
        rounds(2, a, b);
        b.svc.accept(a.idHex());
        rounds(2, a, b);
    }

    @Test
    void requestAcceptAndSeeEachOtherOnline() throws Exception {
        Player alice = new Player("Alice"), bob = new Player("Bob");
        assertEquals(PresenceService.AddResult.SENT, alice.svc.addByCode(bob.code()));
        rounds(2, alice, bob);
        assertTrue(bob.events.contains("request:Alice"), bob.events.toString());
        assertEquals(Friend.Relation.INCOMING, bob.view(alice).friend().relation());
        assertEquals(PresenceState.OFFLINE, bob.view(alice).state(), "no presence before accepting");

        bob.svc.accept(alice.idHex());
        rounds(2, alice, bob);
        assertTrue(alice.events.contains("accepted:Bob"), alice.events.toString());
        assertEquals(Friend.Relation.FRIEND, alice.view(bob).friend().relation());
        assertEquals("Bob", alice.view(bob).friend().mcName());
        assertEquals(PresenceState.ONLINE, alice.view(bob).state());
        assertEquals(PresenceState.ONLINE, bob.view(alice).state());
    }

    @Test
    void hostingIsOnlyVisibleToChosenFriends() throws Exception {
        Player alice = new Player("Alice"), bob = new Player("Bob"), carol = new Player("Carol");
        befriend(alice, bob);
        befriend(alice, carol);
        alice.host("Colony World", Set.of(bob));
        rounds(1, alice, bob, carol);

        var bobSees = bob.view(alice);
        assertEquals(PresenceState.HOSTING, bobSees.state());
        assertTrue(bobSees.canJoin());
        assertEquals("Colony World", bobSees.world());
        assertEquals(1, bob.svc.joinAddresses(alice.idHex()).size());
        assertTrue(bob.events.contains("hosting:Alice:Colony World"));

        var carolSees = carol.view(alice);
        assertEquals(PresenceState.ONLINE, carolSees.state(), "Carol wasn't picked for this session");
        assertTrue(carol.svc.joinAddresses(alice.idHex()).isEmpty());
        assertEquals("", carolSees.world());
    }

    @Test
    void mutualAddsBecomeFriendsWithoutAccepting() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        a.svc.addByCode(b.code());
        b.svc.addByCode(a.code());
        rounds(4, a, b);
        assertEquals(Friend.Relation.FRIEND, a.view(b).friend().relation());
        assertEquals(Friend.Relation.FRIEND, b.view(a).friend().relation());
    }

    @Test
    void goesOfflineWhenRecordsStopAndOnShutdown() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        befriend(a, b);
        assertEquals(PresenceState.ONLINE, a.view(b).state());
        // Ben's game crashes: no more updates. Ann notices once his record goes stale.
        clock.addAndGet(PresenceService.OFFLINE_AFTER_MS);
        rounds(1, a);
        assertEquals(PresenceState.OFFLINE, a.view(b).state());

        Player c = new Player("Cat");
        befriend(a, c);
        c.svc.shutdown(2000);
        rounds(1, a);
        assertEquals(PresenceState.OFFLINE, a.view(c).state(), "clean exit is visible right away");
    }

    @Test
    void resetCodeStopsOldCodeWorking() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        String oldCode = b.code();
        b.svc.resetCode();
        a.svc.addByCode(oldCode);
        rounds(4, a, b);
        assertTrue(b.events.isEmpty(), "request via an old code must not arrive");
    }

    @Test
    void blockedPlayersCantRequestAgain() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        a.svc.addByCode(b.code());
        rounds(2, a, b);
        b.svc.block(a.idHex());
        b.events.clear();
        rounds(6, a, b);
        assertNull(b.view(a));
        assertTrue(b.events.isEmpty());
    }

    @Test
    void removedFriendsOldRequestDoesNotComeBack() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        befriend(a, b);
        a.svc.remove(b.idHex());
        b.svc.remove(a.idHex());
        b.events.clear();
        rounds(5, a, b); // Ann's old request is still sitting in Ben's DHT mailbox
        assertNull(b.view(a), "stale request reappeared");
        assertTrue(b.events.isEmpty());

        // A genuinely new request after removing still works.
        assertEquals(PresenceService.AddResult.SENT, a.svc.addByCode(b.code()));
        rounds(2, a, b);
        assertTrue(b.events.contains("request:Ann"), b.events.toString());
    }

    @Test
    void reAddingAfterBothRemovedWorks() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        befriend(a, b);
        a.svc.remove(b.idHex()); // both click the X: each leaves a "removed" note for the other
        b.svc.remove(a.idHex());
        rounds(3, a, b);
        assertEquals(PresenceService.AddResult.SENT, a.svc.addByCode(b.code()));
        rounds(2, a, b);
        b.svc.accept(a.idHex());
        // Ben's game checks Ann's status before Ann has noticed the accept: all it finds is her old note.
        clock.addAndGet(PresenceService.POLL_MS);
        b.svc.tick();
        assertNotNull(b.view(a), "Ben dropped Ann over her old 'removed' note");
        rounds(3, a, b);
        assertNotNull(a.view(b), "Ann never saw the accept");
        assertNotNull(b.view(a), "Ben lost Ann");
        assertEquals(Friend.Relation.FRIEND, a.view(b).friend().relation());
        assertEquals(Friend.Relation.FRIEND, b.view(a).friend().relation());
    }

    @Test
    void removingSomeoneRemovesYouFromTheirListToo() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        befriend(a, b);
        a.svc.remove(b.idHex());
        rounds(2, a, b);
        assertNull(b.view(a), "Ben should drop Ann once she removes him");
    }

    @Test
    void handledRequestsAreClearedFromTheMailbox() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        befriend(a, b);
        // Every one of Ben's mailbox slots is now empty or never used: nothing left to resurface.
        for (int slot = 0; slot < Protocol.INBOX_SLOTS; slot++) {
            var key = Protocol.inboxKey(b.id.signPublic(), b.id.inboxSecret(), slot);
            var item = dht.get(dev.devilswarchild.cinderlink.core.identity.Keys.raw(key.getPublic()), Protocol.INBOX_SALT);
            assertTrue(item.isEmpty() || item.get().value().length == 0, "slot " + slot + " still holds a request");
        }
    }

    @Test
    void declinedRequestStaysQuietEvenIfResent() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        a.svc.addByCode(b.code());
        rounds(2, a, b);
        b.svc.decline(a.idHex());
        b.events.clear();
        rounds(40, a, b); // Ann's game keeps re-sending the same request every 30 seconds
        assertNull(b.view(a));
        assertTrue(b.events.isEmpty(), b.events.toString());

        // But if Ann sends a brand-new request later, Ben sees it right away: no day-long lockout.
        a.svc.decline(b.idHex()); // Ann cancels her old one...
        assertEquals(PresenceService.AddResult.SENT, a.svc.addByCode(b.code())); // ...and asks again
        rounds(2, a, b);
        assertTrue(b.events.contains("request:Ann"), "new request was blocked: " + b.events);
    }

    @Test
    void codeEdgeCases() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        assertEquals(PresenceService.AddResult.OWN_CODE, a.svc.addByCode(a.code()));
        assertEquals(PresenceService.AddResult.INVALID_CODE, a.svc.addByCode("CNDR-nope"));
        assertEquals(PresenceService.AddResult.SENT, a.svc.addByCode(b.code()));
        assertEquals(PresenceService.AddResult.ALREADY_SENT, a.svc.addByCode(b.code()));
    }

    @Test
    void outsidersLearnNothingFromTheDht() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        befriend(a, b);
        a.host("Secret Base", Set.of(b));
        rounds(1, a, b);
        // Everything stored is either a public card or ciphertext: no names, worlds or addresses in the clear.
        for (var item : dht.items.values()) {
            String raw = new String(item.value(), StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("Ann") || raw.contains("Ben") || raw.contains("Secret"), "plaintext leaked");
            assertFalse(contains(item.value(), InetAddress.getByName("81.2.69.142").getAddress()), "address leaked");
        }
        // A stranger who somehow guessed the pair-record location still can't decrypt it.
        Identity stranger = Identity.create();
        byte[] wrongSecret = stranger.sharedSecret(b.id.boxPublic());
        var key = Protocol.pairKey(wrongSecret, a.id.signPublic());
        assertTrue(dht.get(dev.devilswarchild.cinderlink.core.identity.Keys.raw(key.getPublic()), Protocol.PAIR_SALT).isEmpty());
    }

    @Test
    void pendingRequestSurvivesRestartAndStillArrives() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        a.svc.addByCode(b.code());
        FriendsStore reloaded = FriendsStore.load(a.dir);
        assertEquals(1, reloaded.withRelation(Friend.Relation.OUTGOING).size(), "pending request lost on restart");

        // Ann restarts her game before Ben ever saw the request: it must still go out.
        Player a2 = new Player("Ann", dht, a.dir);
        rounds(2, a2, b);
        assertTrue(b.events.contains("request:Ann"), b.events.toString());
    }

    @Test
    void friendsSurviveRestart() throws Exception {
        Player a = new Player("Ann"), b = new Player("Ben");
        befriend(a, b);
        FriendsStore reloaded = FriendsStore.load(a.dir);
        assertEquals(1, reloaded.withRelation(Friend.Relation.FRIEND).size());
        assertEquals("Ben", reloaded.all().get(0).mcName());
    }

    @Test
    void worksOverTheRealDhtProtocol() throws Exception {
        try (var net = new dev.devilswarchild.cinderlink.core.dht.FakeDhtNetworkAccess(25);
             var c1 = net.client(); var c2 = net.client()) {
            Player a = new Player("Ann", c1), b = new Player("Ben", c2);
            a.svc.addByCode(b.code());
            rounds(2, a, b);
            b.svc.accept(a.idHex());
            rounds(2, a, b);
            a.host("Real Net", Set.of(b));
            rounds(1, a, b);
            assertTrue(b.view(a).canJoin());
            assertEquals("Real Net", b.view(a).world());
        }
    }

    @Test
    void worksOverAMessyRealisticDht() throws Exception {
        try (var net = dev.devilswarchild.cinderlink.core.dht.FakeDhtNetworkAccess.messy(150);
             var c1 = net.client(); var c2 = net.client()) {
            Player a = new Player("Ann", c1), b = new Player("Ben", c2);
            a.svc.addByCode(b.code());
            rounds(3, a, b);
            assertTrue(b.events.contains("request:Ann"), "request never arrived: " + c1.statsLine());
            b.svc.accept(a.idHex());
            rounds(3, a, b);
            a.host("Messy Net", Set.of(b));
            rounds(2, a, b);
            assertTrue(b.view(a).canJoin(), c2.statsLine());
            assertEquals("Messy Net", b.view(a).world());
        }
    }

    @Test
    void firstContactIsFastInRealTime() throws Exception {
        // Real clock, real timeouts on dead nodes, both games ticking every second like in-game.
        try (var net = dev.devilswarchild.cinderlink.core.dht.FakeDhtNetworkAccess.messy(150);
             var c1 = net.client(); var c2 = net.client()) {
            Path d1 = Files.createDirectories(tmp.resolve("rt1")), d2 = Files.createDirectories(tmp.resolve("rt2"));
            Identity i1 = Identity.loadOrCreate(d1), i2 = Identity.loadOrCreate(d2);
            var s1 = new PresenceService(i1, FriendsStore.load(d1), c1, d1, System::currentTimeMillis, 4);
            var s2 = new PresenceService(i2, FriendsStore.load(d2), c2, d2, System::currentTimeMillis, 4);
            s1.setLocal(new PresenceService.LocalStatus("Ann", UUID.randomUUID(), null));
            s2.setLocal(new PresenceService.LocalStatus("Ben", UUID.randomUUID(), null));
            s1.setImmediate(true); // like the game: act on changes right away
            s2.setImmediate(true);
            var got = new java.util.concurrent.CountDownLatch(1);
            var accepted = new java.util.concurrent.CountDownLatch(1);
            s2.setListener(new PresenceService.Listener() {
                @Override public void requestReceived(Friend from) { got.countDown(); }
                @Override public void timing(String what) { System.out.println("Ben timing: " + what); }
            });
            s1.setListener(new PresenceService.Listener() {
                @Override public void requestAccepted(Friend by) { accepted.countDown(); }
                @Override public void timing(String what) { System.out.println("Ann timing: " + what); }
            });
            var ticker = java.util.concurrent.Executors.newScheduledThreadPool(2);
            // Exactly like the game: non-blocking ticks once a second.
            ticker.scheduleWithFixedDelay(() -> s1.tick(false), 0, 1, java.util.concurrent.TimeUnit.SECONDS);
            ticker.scheduleWithFixedDelay(() -> s2.tick(false), 0, 1, java.util.concurrent.TimeUnit.SECONDS);
            try {
                Thread.sleep(4000); // both games finish starting up (cards published, mailbox routes found)
                s1.prepare(i2.code().format()); // Ann pastes Ben's code...
                Thread.sleep(1500);             // ...and reaches for Send
                long t0 = System.nanoTime();
                s1.addByCode(i2.code().format());
                assertTrue(got.await(15, java.util.concurrent.TimeUnit.SECONDS), "request never arrived");
                long requestMs = (System.nanoTime() - t0) / 1_000_000;
                long t1 = System.nanoTime();
                s2.accept(java.util.HexFormat.of().formatHex(i1.signPublic()));
                assertTrue(accepted.await(15, java.util.concurrent.TimeUnit.SECONDS), "accept never seen");
                long acceptMs = (System.nanoTime() - t1) / 1_000_000;
                // Ann opens her world to Ben: how long until Ben has a Join button?
                Thread.sleep(3000);
                long t2 = System.nanoTime();
                var addr = java.util.List.of(new java.net.InetSocketAddress(java.net.InetAddress.getByName("81.2.69.142"), 51234));
                String benId = java.util.HexFormat.of().formatHex(i2.signPublic());
                String annId = java.util.HexFormat.of().formatHex(i1.signPublic());
                s1.setLocal(new PresenceService.LocalStatus("Ann", s1.local().mcUuid(),
                        new PresenceService.Hosting("World", addr, java.util.Set.of(benId))));
                long hostMs = -1;
                while ((System.nanoTime() - t2) / 1_000_000 < 15000) {
                    if (s2.views().stream().anyMatch(v -> v.friend().id().equals(annId) && v.canJoin())) {
                        hostMs = (System.nanoTime() - t2) / 1_000_000;
                        break;
                    }
                    Thread.sleep(50);
                }
                System.out.println("TIMING request=" + requestMs + "ms accept=" + acceptMs + "ms hosting=" + hostMs + "ms");
                assertTrue(hostMs >= 0 && hostMs < 6000, "hosting took " + hostMs + " ms");
                assertTrue(requestMs < 8000, "request took " + requestMs + " ms");
                assertTrue(acceptMs < 8000, "accept took " + acceptMs + " ms");
            } finally {
                ticker.shutdownNow();
            }
        }
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
