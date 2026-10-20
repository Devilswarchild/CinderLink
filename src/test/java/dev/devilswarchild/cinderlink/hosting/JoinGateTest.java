package dev.devilswarchild.cinderlink.hosting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JoinGateTest {
    @AfterEach
    void reset() {
        JoinGate.deactivate();
    }

    @Test
    void inactiveGateLetsEveryoneIn() {
        assertTrue(JoinGate.permits(UUID.randomUUID()));
    }

    @Test
    void activeGateOnlyAllowsPickedFriends() {
        UUID friend = UUID.randomUUID();
        JoinGate.activate(List.of(friend));
        assertTrue(JoinGate.permits(friend));
        assertFalse(JoinGate.permits(UUID.randomUUID()));
        assertFalse(JoinGate.permits(null));
    }

    @Test
    void inviteAndRevokeWhileHosting() {
        UUID late = UUID.randomUUID();
        JoinGate.activate(List.of());
        assertFalse(JoinGate.permits(late));
        JoinGate.allow(late);
        assertTrue(JoinGate.permits(late));
        JoinGate.revoke(late);
        assertFalse(JoinGate.permits(late));
    }

    @Test
    void deactivateClearsEverything() {
        UUID id = UUID.randomUUID();
        JoinGate.activate(List.of(id));
        JoinGate.deactivate();
        JoinGate.activate(List.of());
        assertFalse(JoinGate.permits(id));
    }
}
