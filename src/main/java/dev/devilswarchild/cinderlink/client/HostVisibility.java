package dev.devilswarchild.cinderlink.client;

import net.minecraft.network.chat.Component;

/** Who can see and join a world opened through CinderLink. Chosen every time on the Open to LAN screen. */
public enum HostVisibility {
    LAN_ONLY("cinderlink.visibility.lan_only"),
    ALL_FRIENDS("cinderlink.visibility.all_friends"),
    SELECTED("cinderlink.visibility.selected"),
    INVITE_ONLY("cinderlink.visibility.invite_only");

    private final String key;

    HostVisibility(String key) {
        this.key = key;
    }

    public Component label() {
        return Component.translatable(key);
    }
}
