package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.CinderLink;
import dev.devilswarchild.cinderlink.CinderLinkConfig;
import dev.devilswarchild.cinderlink.core.net.InternetHosting;
import dev.devilswarchild.cinderlink.core.net.InternetHosting.Problem;
import dev.devilswarchild.cinderlink.core.presence.Friend;
import dev.devilswarchild.cinderlink.core.presence.PresenceService;
import dev.devilswarchild.cinderlink.hosting.JoinGate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Turns "Open to LAN" into hosting for friends: opens the router port, restricts joining to the friends picked
 * for this session, and publishes the (encrypted) way in to exactly those friends. No address is ever shown.
 */
@EventBusSubscriber(modid = CinderLink.MODID, value = Dist.CLIENT)
public final class HostingManager {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CinderLink hosting");
        t.setDaemon(true);
        return t;
    });

    /** Set when the Open to LAN screen closes; consumed on the next tick. Null = not a CinderLink session. */
    static volatile HostVisibility pendingMode;
    private static boolean wasPublished;
    private static int session;
    private static volatile InternetHosting.Result network;
    private static volatile HostVisibility mode;
    private static final Set<String> visibleTo = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String worldName = "";
    private static volatile int hostPort;

    private HostingManager() {}

    public static boolean isHosting() {
        return mode != null;
    }

    public static boolean isVisibleTo(String friendId) {
        return visibleTo.contains(friendId);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        boolean published = server != null && server.isPublished();
        HostVisibility requested = pendingMode;
        pendingMode = null;
        if (published && !wasPublished && requested != null && requested != HostVisibility.LAN_ONLY) {
            start(mc, server, requested);
        } else if (!published && wasPublished) {
            stop();
        }
        wasPublished = published;
    }

    private static void start(Minecraft mc, IntegratedServer server, HostVisibility requested) {
        PresenceService presence = FriendsRuntime.presence().orElse(null);
        if (presence == null) {
            chat(mc, Ember.msg("cinderlink.hosting.friends_off"));
            return;
        }
        boolean devMode = Boolean.getBoolean("cinderlink.dev");
        if (!server.usesAuthentication() && !devMode) {
            chat(mc, Ember.msg("cinderlink.hosting.offline_mode"));
            return;
        }
        if (devMode) {
            // Dev clients have no real Mojang session, so the account check can't succeed between them.
            // Never applies to real players: cinderlink.dev is only set by the runClient tasks.
            server.setUsesAuthentication(false);
        }
        List<Friend> friends = presence.store().withRelation(Friend.Relation.FRIEND);
        visibleTo.clear();
        switch (requested) {
            case ALL_FRIENDS -> friends.forEach(f -> visibleTo.add(f.id()));
            case SELECTED -> friends.stream().filter(f -> ShareToLanHook.selected.contains(f.id()))
                    .forEach(f -> visibleTo.add(f.id()));
            default -> { }
        }
        // Lock the door before opening it: only the picked friends' accounts may log in.
        Set<UUID> allowed = new HashSet<>();
        friends.stream().filter(f -> visibleTo.contains(f.id())).forEach(f -> allowed.add(f.mcUuid()));
        JoinGate.activate(allowed);
        mode = requested;
        worldName = server.getWorldData().getLevelName();
        int port = server.getPort();
        hostPort = port;
        int mySession = session;
        boolean upnp = CinderLinkConfig.ENABLE_UPNP.get();
        boolean ipv6 = CinderLinkConfig.ENABLE_IPV6.get();
        chat(mc, Ember.msg("cinderlink.hosting.opening"));
        WORKER.submit(() -> {
            InternetHosting.Result result = InternetHosting.open(port, upnp, ipv6);
            mc.execute(() -> {
                IntegratedServer now = mc.getSingleplayerServer();
                if (mySession != session || now == null || !now.isPublished() || now.getPort() != port) {
                    WORKER.submit(result::close);
                    return;
                }
                network = result;
                publish();
                announce(mc, result);
                CinderLink.LOGGER.info("Hosting for friends ({}; internet {})", requested,
                        result.reachableFromInternet() ? "reachable" : "unreachable");
            });
        });
    }

    /** Lets one more friend see and join this session (from the friends screen). */
    public static void invite(Friend f) {
        if (mode == null) return;
        visibleTo.add(f.id());
        JoinGate.allow(f.mcUuid());
        publish();
    }

    /** Pushes the current hosting state into the friends engine; it handles encryption and delivery. */
    private static void publish() {
        FriendsRuntime.presence().ifPresent(p -> {
            PresenceService.LocalStatus cur = p.local();
            if (cur == null) return;
            InternetHosting.Result net = network;
            List<java.net.InetSocketAddress> routes = net == null ? List.of() : new java.util.ArrayList<>(net.addresses());
            if (net != null && Boolean.getBoolean("cinderlink.dev") && hostPort > 0) {
                routes.add(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), hostPort)); // dev clients on one PC
            }
            PresenceService.Hosting h = mode == null || net == null ? null
                    : new PresenceService.Hosting(worldName, routes, Set.copyOf(visibleTo));
            p.setLocal(new PresenceService.LocalStatus(cur.mcName(), cur.mcUuid(), h));
        });
    }

    static void stop() {
        session++;
        mode = null;
        visibleTo.clear();
        JoinGate.deactivate();
        InternetHosting.Result r = network;
        network = null;
        publish();
        if (r != null) {
            boolean hadMapping = r.mapping().isPresent();
            WORKER.submit(r::close);
            CinderLink.LOGGER.info(hadMapping ? "Stopped hosting; router port closed." : "Stopped hosting.");
        }
    }

    private static void announce(Minecraft mc, InternetHosting.Result r) {
        switch (mode) {
            case INVITE_ONLY -> chat(mc, Ember.msg("cinderlink.hosting.invite_only"));
            default -> chat(mc, visibleTo.isEmpty()
                    ? Ember.msg("cinderlink.hosting.no_friends_picked")
                    : Ember.msg("cinderlink.hosting.visible_to", visibleTo.size()));
        }
        if (!r.reachableFromInternet()) {
            chat(mc, Ember.grey("cinderlink.hosting.lan_friends_only"));
            for (Problem p : r.problems()) {
                if (p == Problem.NO_IPV6 || p == Problem.IPV6_DISABLED) continue;
                chat(mc, Ember.grey("cinderlink.problem." + p.name().toLowerCase(Locale.ROOT)));
            }
        }
    }

    static void chat(Minecraft mc, Component c) {
        if (mc.gui != null) mc.gui.getChat().addMessage(c);
    }
}
