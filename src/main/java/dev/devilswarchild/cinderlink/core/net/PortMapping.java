package dev.devilswarchild.cinderlink.core.net;

import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A live router port mapping for one hosting session.
 *
 * Crash safety: the mapping is created with a 1-hour lease and renewed every 25 minutes, so if the game dies
 * without cleaning up, the router drops it on its own. A JVM shutdown hook also removes it on normal exits.
 * Routers that only accept permanent mappings get one, and rely on close() / the shutdown hook.
 */
public final class PortMapping implements AutoCloseable {
    public static final String PROTOCOL = "TCP";
    /** Deliberately generic: other devices on the LAN can list mappings, so no usernames go in here. */
    static final String DESCRIPTION = "CinderLink";
    static final int LEASE_SECONDS = 3600;
    static final long RENEW_SECONDS = 25 * 60;
    private static final int MAX_PORT_ATTEMPTS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UpnpGateway gateway;
    private final int externalPort;
    private final int internalPort;
    private final int lease;
    private final ScheduledExecutorService renewer;
    private final ScheduledFuture<?> renewTask;
    private final Thread shutdownHook;
    private volatile boolean closed;

    private PortMapping(UpnpGateway gateway, int externalPort, int internalPort, int lease) {
        this.gateway = gateway;
        this.externalPort = externalPort;
        this.internalPort = internalPort;
        this.lease = lease;
        this.renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CinderLink UPnP renew");
            t.setDaemon(true);
            return t;
        });
        this.renewTask = lease > 0
                ? renewer.scheduleAtFixedRate(this::renew, RENEW_SECONDS, RENEW_SECONDS, TimeUnit.SECONDS)
                : null;
        this.shutdownHook = new Thread(this::removeQuietly, "CinderLink UPnP cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Maps an external port to {@code internalPort} on this machine. Tries the same port first; if another
     * device already holds it, picks a random high port instead.
     */
    public static PortMapping open(UpnpGateway gateway, int internalPort) throws UpnpException {
        int external = internalPort;
        UpnpException last = null;
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            try {
                int lease = addWithLeaseFallback(gateway, external, internalPort);
                return new PortMapping(gateway, external, internalPort, lease);
            } catch (UpnpException e) {
                last = e;
                if (e.code != UpnpException.CONFLICT_IN_MAPPING) throw e;
                external = 20000 + RANDOM.nextInt(40000);
            }
        }
        throw last;
    }

    private static int addWithLeaseFallback(UpnpGateway gateway, int external, int internal) throws UpnpException {
        try {
            gateway.addPortMapping(external, internal, PROTOCOL, DESCRIPTION, LEASE_SECONDS);
            return LEASE_SECONDS;
        } catch (UpnpException e) {
            if (e.code != UpnpException.ONLY_PERMANENT_LEASES) throw e;
            gateway.addPortMapping(external, internal, PROTOCOL, DESCRIPTION, 0);
            return 0;
        }
    }

    public int externalPort() {
        return externalPort;
    }

    public UpnpGateway gateway() {
        return gateway;
    }

    private void renew() {
        if (closed) return;
        try {
            gateway.addPortMapping(externalPort, internalPort, PROTOCOL, DESCRIPTION, lease);
        } catch (UpnpException ignored) {
            // next renewal will retry; the old lease is still valid for a while
        }
    }

    private void removeQuietly() {
        if (closed) return;
        closed = true;
        try {
            gateway.deletePortMapping(externalPort, PROTOCOL);
        } catch (UpnpException ignored) {
            // lease expiry is the backstop
        }
    }

    /** Removes the mapping now. Safe to call more than once. */
    @Override
    public void close() {
        if (renewTask != null) renewTask.cancel(false);
        renewer.shutdownNow();
        removeQuietly();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // already shutting down; the hook is running or has run
        }
    }
}
