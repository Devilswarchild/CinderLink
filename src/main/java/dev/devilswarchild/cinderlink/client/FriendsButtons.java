package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.CinderLink;
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
        event.addListener(Button.builder(label, b -> screen.getMinecraft().setScreen(new FriendsScreen(screen)))
                .bounds(6, 6, 80, 20).build());
    }
}
