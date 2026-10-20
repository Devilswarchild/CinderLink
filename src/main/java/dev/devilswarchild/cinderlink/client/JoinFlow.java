package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.core.presence.PresenceService;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Click a hosting friend's name and you're in. The address comes out of their encrypted record, is tried
 * quietly in the background, and is handed straight to Minecraft's connector. It's never displayed or logged.
 */
public final class JoinFlow {
    private static final int PROBE_TIMEOUT_MS = 2500;

    private JoinFlow() {}

    public static void join(Screen from, PresenceService.FriendView v) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.setScreen(new ConfirmScreen(yes -> {
                if (yes) start(from, v);
                else mc.setScreen(from);
            }, Component.translatable("cinderlink.join.leave.title"),
                    Component.translatable("cinderlink.join.leave.body", v.friend().mcName())));
        } else {
            start(from, v);
        }
    }

    private static void start(Screen from, PresenceService.FriendView v) {
        Minecraft mc = Minecraft.getInstance();
        List<InetSocketAddress> addresses = FriendsRuntime.presence()
                .map(p -> p.joinAddresses(v.friend().id())).orElse(List.of());
        String name = v.friend().mcName();
        Probing probing = new Probing(from, name);
        mc.setScreen(probing);
        CompletableFuture.supplyAsync(() -> firstReachable(addresses)).thenAccept(addr -> mc.execute(() -> {
            if (probing.cancelled) return;
            if (addr == null) {
                mc.setScreen(new DisconnectedScreen(from, Component.translatable("cinderlink.join.failed.title"),
                        Component.translatable("cinderlink.join.failed", name)));
                return;
            }
            if (mc.level != null) mc.disconnect(); // leave our own world first (saves it)
            Screen parent = new FriendsScreen(new TitleScreen());
            String host = addr.getAddress().getHostAddress();
            int pct = host.indexOf('%');
            if (pct >= 0) host = host.substring(0, pct);
            ServerData data = new ServerData(Component.translatable("cinderlink.join.server_name", name).getString(),
                    "cinderlink", ServerData.Type.OTHER);
            CinderJoin.armed = true;
            ConnectScreen.startConnecting(parent, mc, new ServerAddress(host, addr.getPort()), data, false, null);
        }));
    }

    /** Tries every route at once (LAN, IPv6, IPv4) and returns the first that accepts a connection. */
    static InetSocketAddress firstReachable(List<InetSocketAddress> addresses) {
        if (addresses.isEmpty()) return null;
        List<CompletableFuture<InetSocketAddress>> probes = addresses.stream()
                .map(a -> CompletableFuture.supplyAsync(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(a, PROBE_TIMEOUT_MS);
                        return a;
                    } catch (Exception e) {
                        return null;
                    }
                })).toList();
        long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS + 500;
        // Prefer the order given (LAN first) but don't wait on a dead route if a later one already worked.
        for (CompletableFuture<InetSocketAddress> p : probes) {
            try {
                InetSocketAddress ok = p.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (ok != null) return ok;
            } catch (Exception ignored) {
                // timed out; try the next route
            }
        }
        return null;
    }

    /** "Finding Steve's world..." with a Cancel button while routes are tried. */
    private static final class Probing extends Screen {
        private final Screen parent;
        private final String name;
        volatile boolean cancelled;

        Probing(Screen parent, String name) {
            super(Component.translatable("cinderlink.join.finding", name));
            this.parent = parent;
            this.name = name;
        }

        @Override
        protected void init() {
            addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                    .bounds(width / 2 - 100, height / 2 + 20, 200, 20).build());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            g.drawCenteredString(font, title, width / 2, height / 2 - 10, 0xFFFFFFFF);
        }

        @Override
        public void onClose() {
            cancelled = true;
            minecraft.setScreen(parent);
        }
    }
}
