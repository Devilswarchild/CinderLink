package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.CinderLink;
import dev.devilswarchild.cinderlink.CinderLinkConfig;
import dev.devilswarchild.cinderlink.core.dht.DhtClient;
import dev.devilswarchild.cinderlink.core.identity.Identity;
import dev.devilswarchild.cinderlink.core.presence.Friend;
import dev.devilswarchild.cinderlink.core.presence.FriendsStore;
import dev.devilswarchild.cinderlink.core.presence.PresenceService;
import dev.devilswarchild.cinderlink.core.presence.PresenceState;
import dev.devilswarchild.cinderlink.core.util.SafeLog;
import dev.devilswarchild.cinderlink.core.util.SecureFiles;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Starts the friends engine once the game is up, runs it in the background, and shuts it down cleanly. */
@EventBusSubscriber(modid = CinderLink.MODID, value = Dist.CLIENT)
public final class FriendsRuntime {
    private static final long TICK_MS = 1000;
    private static volatile PresenceService presence;
    private static volatile DhtClient dht;
    private static volatile String problem;
    private static ScheduledExecutorService scheduler;
    private static boolean started;

    private FriendsRuntime() {}

    public static Optional<PresenceService> presence() {
        return Optional.ofNullable(presence);
    }

    /** Why the friends list isn't running (turned off, network blocked...), or null if it is. */
    public static String problem() {
        return problem;
    }

    @SubscribeEvent
    static void onFirstTick(ClientTickEvent.Post event) {
        if (started) return;
        started = true;
        if (!CinderLinkConfig.ENABLE_FRIENDS.get()) {
            problem = "disabled";
            return;
        }
        Thread t = new Thread(FriendsRuntime::start, "CinderLink startup");
        t.setDaemon(true);
        t.start();
    }

    private static void start() {
        try {
            Path dir = SecureFiles.home();
            Identity me = Identity.loadOrCreate(dir);
            FriendsStore store = FriendsStore.load(dir);
            DhtClient client = DhtClient.start();
            client.loadNodes(dir.resolve("dht-nodes.bin"));
            PresenceService svc = new PresenceService(me, store, client, dir, System::currentTimeMillis, 4);
            User user = Minecraft.getInstance().getUser();
            svc.setLocal(new PresenceService.LocalStatus(user.getName(), user.getProfileId(), null));
            svc.setListener(new Toasts());
            dht = client;
            presence = svc;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "CinderLink friends");
                th.setDaemon(true);
                return th;
            });
            scheduler.scheduleWithFixedDelay(FriendsRuntime::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
            scheduler.scheduleWithFixedDelay(() -> client.saveNodes(dir.resolve("dht-nodes.bin")), 5, 10, TimeUnit.MINUTES);
            java.util.concurrent.CompletableFuture.runAsync(dev.devilswarchild.cinderlink.core.net.InternetHosting::prefetchGateway);
            svc.setImmediate(true);
            CinderLink.LOGGER.info("CinderLink friends list started.");
        } catch (Exception e) {
            problem = "startup";
            CinderLink.LOGGER.error("CinderLink friends list couldn't start: {}", SafeLog.redact(String.valueOf(e)));
        }
    }

    private static int ticks;

    private static void tick() {
        try {
            PresenceService p = presence;
            if (p != null) p.tick(false);
            DhtClient d = dht;
            ticks++;
            // A health line soon after start, then every ~5 minutes. Counts only: no addresses, keys or names.
            if (d != null && (ticks == 15 || ticks == 60 || ticks % 300 == 0)) {
                CinderLink.LOGGER.info("CinderLink {}", d.statsLine());
            }
            // No DHT nodes at all after a while means UDP is blocked here (school/work networks, some firewalls).
            problem = d != null && d.knownNodeCount() == 0 ? "network" : null;
        } catch (Throwable t) {
            CinderLink.LOGGER.warn("CinderLink friends update failed: {}", SafeLog.redact(String.valueOf(t)));
        }
    }

    @SubscribeEvent
    static void onShutdown(GameShuttingDownEvent event) {
        PresenceService p = presence;
        DhtClient d = dht;
        if (scheduler != null) scheduler.shutdownNow();
        if (p != null) p.shutdown(1500);
        if (d != null) {
            d.saveNodes(SecureFiles.home().resolve("dht-nodes.bin"));
            d.close();
        }
    }

    /** Pops up notifications for friend activity. */
    private static final class Toasts implements PresenceService.Listener {
        @Override
        public void requestReceived(Friend from) {
            toast(Component.translatable("cinderlink.toast.request.title"),
                    Component.translatable("cinderlink.toast.request", from.mcName()));
        }

        @Override
        public void requestAccepted(Friend by) {
            toast(Component.translatable("cinderlink.toast.accepted.title"),
                    Component.translatable("cinderlink.toast.accepted", by.mcName()));
        }

        @Override
        public void friendStartedHosting(PresenceService.FriendView v) {
            if (!CinderLinkConfig.SHOW_HOSTING_TOASTS.get() || v.state() != PresenceState.HOSTING) return;
            toast(Component.translatable("cinderlink.toast.hosting.title", v.friend().mcName()),
                    Component.literal(v.world()));
        }

        @Override
        public void problem(String what) {
            CinderLink.LOGGER.warn("CinderLink friends engine problem: {}", what);
        }

        @Override
        public void timing(String what) {
            CinderLink.LOGGER.info("CinderLink timing: {}", what);
        }

        private static void toast(Component title, Component body) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    title, body));
        }
    }
}
