package dev.devilswarchild.cinderlink.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Chat styling helpers so every CinderLink message looks the same. */
public final class Ember {
    static final TextColor EMBER = TextColor.fromRgb(0xFF8A3D);

    private Ember() {}

    /** "[CinderLink] " prefix in ember orange, then the message. */
    public static MutableComponent msg(Component body) {
        return Component.literal("[CinderLink] ").withStyle(Style.EMPTY.withColor(EMBER)).append(body);
    }

    public static MutableComponent msg(String key, Object... args) {
        return msg(Component.translatable(key, args));
    }

    public static MutableComponent grey(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
    }
}
