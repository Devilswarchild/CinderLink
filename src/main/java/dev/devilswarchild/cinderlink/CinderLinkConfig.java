package dev.devilswarchild.cinderlink;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config, saved to config/cinderlink-client.toml. */
public final class CinderLinkConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static { BUILDER.push("hosting"); }
    public static final ModConfigSpec.BooleanValue ENABLE_UPNP = BUILDER
            .comment("Ask the router to forward the port while hosting (closed again when you stop).")
            .define("enableUpnp", true);
    public static final ModConfigSpec.BooleanValue ENABLE_IPV6 = BUILDER
            .comment("Offer an IPv6 address to friends when one is available.")
            .define("enableIpv6", true);
    static { BUILDER.pop(); BUILDER.push("friends"); }
    public static final ModConfigSpec.BooleanValue ENABLE_FRIENDS = BUILDER
            .comment("Use the CinderLink friends list. Off = CinderLink makes no network connections at all.")
            .define("enableFriends", true);
    public static final ModConfigSpec.BooleanValue SHOW_HOSTING_TOASTS = BUILDER
            .define("showHostingToasts", true);
    static { BUILDER.pop(); }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private CinderLinkConfig() {}
}
