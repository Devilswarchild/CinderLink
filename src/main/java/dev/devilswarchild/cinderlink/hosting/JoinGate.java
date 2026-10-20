package dev.devilswarchild.cinderlink.hosting;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides who may join while a world is hosted through CinderLink: the host plus the friends picked for this
 * session, matched by Minecraft account UUID. Read by the login mixin on the server thread, written by the
 * client, so it's thread-safe. Uses no client classes on purpose.
 *
 * UUIDs are only trustworthy because hosting refuses to start unless the server verifies accounts with Mojang
 * (online mode).
 */
public final class JoinGate {
    private static volatile boolean active;
    private static final Set<UUID> allowed = ConcurrentHashMap.newKeySet();

    private JoinGate() {}

    public static void activate(Collection<UUID> ids) {
        allowed.clear();
        allowed.addAll(ids);
        active = true;
    }

    public static void deactivate() {
        active = false;
        allowed.clear();
    }

    public static boolean isActive() {
        return active;
    }

    public static void allow(UUID id) {
        allowed.add(id);
    }

    public static void revoke(UUID id) {
        allowed.remove(id);
    }

    /** True if this player may log in. The host is checked separately by the caller. */
    public static boolean permits(UUID id) {
        return !active || (id != null && allowed.contains(id));
    }
}
