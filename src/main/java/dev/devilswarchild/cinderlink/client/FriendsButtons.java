package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.CinderLink;
import dev.devilswarchild.cinderlink.CinderLinkConfig;
import dev.devilswarchild.cinderlink.core.presence.Friend;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** A "Friends" button on the title screen and pause menu, with a count of waiting requests. */
@EventBusSubscriber(modid = CinderLink.MODID, value = Dist.CLIENT)
public final class FriendsButtons {
    private FriendsButtons() {}

    @SubscribeEvent
    static void onInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        boolean title = screen instanceof TitleScreen;
        boolean pause = screen instanceof PauseScreen p && p.showsPauseMenu();
        if (!title && !pause) return;
        int requests = FriendsRuntime.presence()
                .map(p -> p.store().withRelation(Friend.Relation.INCOMING).size()).orElse(0);
        Component label = requests > 0
                ? Component.translatable("cinderlink.button.friends_count", requests)
                : Component.translatable("cinderlink.button.friends");
        Button friends = Button.builder(label, b -> screen.getMinecraft().setScreen(new FriendsScreen(screen)))
                .bounds(6, 6, 80, 20).build();
        event.addListener(friends);
        if (title && CinderLinkConfig.SHOW_PLAYER_MODEL.get()) addPlayerModel(event, screen, friends);
        // Get friends' faces ready now, so the friends list shows them straight away.
        FriendsRuntime.presence().ifPresent(p -> Skins.prefetch(p.store().all().stream()
                .map(Friend::mcUuid).toList()));
    }

    /**
     * Your player model just under the Friends button, out of the way of the main menu buttons (modpacks often
     * restyle those), if there's room.
     */
    private static void addPlayerModel(ScreenEvent.Init.Post event, Screen screen, Button friends) {
        int width = 56;
        int height = 104;
        int x = friends.getX() + (friends.getWidth() - width) / 2; // centred under the button
        int y = friends.getY() + friends.getHeight() + 6;
        if (y + height > screen.height - 14) return; // too small
        event.addListener(new PlayerModelWidget(x, y, width, height, screen.getMinecraft().getEntityModels()));
    }
}
