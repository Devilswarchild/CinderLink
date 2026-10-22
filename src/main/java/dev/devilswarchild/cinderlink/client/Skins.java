package dev.devilswarchild.cinderlink.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import dev.devilswarchild.cinderlink.core.util.SecureFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

/**
 * Players' real Minecraft skins, looked up from Mojang by account UUID the same way the game does for anyone
 * you see in-game. Which skin each friend wears is remembered between sessions (skins.txt, public skin info
 * only), and the game keeps the skin images cached on disk, so faces show up straight away; a background
 * check still picks up skin changes.
 */
final class Skins {
    private Skins() {}

    /** A failed lookup (offline, rate-limited) is retried after this long. */
    private static final long RETRY_MS = 10 * 60_000;

    private record Entry(CompletableFuture<PlayerSkin> skin, long startedAt) {}

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    /** UUID -> the "textures" value Mojang last gave us (skin URL and model; no secrets). */
    private static final Map<UUID, String> SAVED = new ConcurrentHashMap<>();
    private static volatile boolean savedLoaded;
    private static volatile CompletableFuture<PlayerSkin> mine;

    /** The skin for a player, or the default skin until it's ready. Safe to call every frame. */
    static PlayerSkin of(UUID uuid) {
        PlayerSkin fallback = DefaultPlayerSkin.get(uuid);
        if (!real(uuid)) return fallback;
        PlayerSkin s = entry(uuid).skin.getNow(null);
        return s != null ? s : fallback;
    }

    /** Starts loading these players' skins now, so they're ready when the friends list opens. */
    static void prefetch(Collection<UUID> uuids) {
        for (UUID u : uuids) if (real(u)) entry(u);
    }

    private static boolean real(UUID uuid) {
        return uuid != null && (uuid.getMostSignificantBits() != 0 || uuid.getLeastSignificantBits() != 0);
    }

    private static Entry entry(UUID uuid) {
        long now = System.currentTimeMillis();
        return CACHE.compute(uuid, (u, old) -> {
            boolean failed = old != null && old.skin.isDone() && old.skin.getNow(null) == null;
            return old == null || (failed && now - old.startedAt > RETRY_MS) ? new Entry(load(u), now) : old;
        });
    }

    /** Your own skin. */
    static PlayerSkin mine() {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<PlayerSkin> f = mine;
        if (f == null) {
            // getGameProfile() can wait on Mojang, so never call it on the render thread.
            f = CompletableFuture.supplyAsync(mc::getGameProfile, Util.nonCriticalIoPool())
                    .thenCompose(p -> mc.getSkinManager().getOrLoad(p))
                    .exceptionally(t -> null);
            mine = f;
        }
        PlayerSkin s = f.getNow(null);
        return s != null ? s : DefaultPlayerSkin.get(mc.getUser().getProfileId());
    }

    private static CompletableFuture<PlayerSkin> load(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        loadSaved();
        // Ask Mojang in the background either way, in case they've changed skin since last time.
        CompletableFuture<PlayerSkin> fresh = CompletableFuture
                .supplyAsync(() -> mc.getMinecraftSessionService().fetchProfile(uuid, false), Util.nonCriticalIoPool())
                .thenCompose((ProfileResult r) -> {
                    if (r == null) return CompletableFuture.completedFuture((PlayerSkin) null);
                    GameProfile p = r.profile();
                    Property t = mc.getMinecraftSessionService().getPackedTextures(p);
                    if (t != null && !t.value().equals(SAVED.get(uuid))) {
                        SAVED.put(uuid, t.value());
                        CompletableFuture.runAsync(Skins::save, Util.nonCriticalIoPool());
                    }
                    return mc.getSkinManager().getOrLoad(p);
                })
                .exceptionally(t -> null);
        String known = SAVED.get(uuid);
        if (known == null) return fresh;
        // We know their skin from last time: show it right away (the image is in the game's skin cache).
        GameProfile p = new GameProfile(uuid, "");
        p.getProperties().put("textures", new Property("textures", known));
        CompletableFuture<PlayerSkin> quick = mc.getSkinManager().getOrLoad(p).exceptionally(t -> null);
        fresh.thenAccept(s -> {
            if (s != null) CACHE.computeIfPresent(uuid, (u, e) ->
                    new Entry(CompletableFuture.completedFuture(s), e.startedAt));
        });
        return quick;
    }

    private static Path file() {
        return SecureFiles.home().resolve("skins.txt");
    }

    private static synchronized void loadSaved() {
        if (savedLoaded) return;
        savedLoaded = true;
        try {
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                int bar = line.indexOf('|');
                if (line.startsWith("#") || bar < 0) continue;
                try {
                    SAVED.putIfAbsent(UUID.fromString(line.substring(0, bar)), line.substring(bar + 1));
                } catch (IllegalArgumentException ignored) {
                    // bad line
                }
            }
        } catch (IOException ignored) {
            // nothing saved yet
        }
    }

    private static synchronized void save() {
        StringBuilder sb = new StringBuilder("# CinderLink: which skin each friend wears (public skin info). uuid|textures\n");
        SAVED.forEach((u, v) -> sb.append(u).append('|').append(v).append('\n'));
        try {
            SecureFiles.writeAtomically(file(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // just slower next launch
        }
    }

    /** Draws a player's face; greyed out when {@code dim} (offline friends). */
    static void drawFace(GuiGraphics g, UUID uuid, int x, int y, int size, boolean dim) {
        if (dim) g.setColor(0.45f, 0.45f, 0.45f, 1f);
        PlayerFaceRenderer.draw(g, of(uuid), x, y, size);
        if (dim) g.setColor(1f, 1f, 1f, 1f);
    }
}
