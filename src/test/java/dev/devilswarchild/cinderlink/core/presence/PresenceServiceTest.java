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
            this.name = name;
            this.dir = Files.createDirectories(tmp.resolve(name));
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
        for (int i = 0; i < 12; i++) rounds(1, a);
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

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
