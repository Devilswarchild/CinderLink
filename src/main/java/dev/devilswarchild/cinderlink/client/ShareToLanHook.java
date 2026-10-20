package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.CinderLink;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds "Friends: ..." and "Choose friends..." to the vanilla Open to LAN screen. The choice starts at LAN only
 * every time the screen opens, so sharing with friends is always a deliberate pick.
 */
@EventBusSubscriber(modid = CinderLink.MODID, value = Dist.CLIENT)
public final class ShareToLanHook {
    private static HostVisibility mode = HostVisibility.LAN_ONLY;
    /** Friend ids ticked in the picker; remembered for this game session. */
    static final Set<String> selected = ConcurrentHashMap.newKeySet();
    private static boolean returningFromPicker;

    private ShareToLanHook() {}

    @SubscribeEvent
    static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ShareToLanScreen screen)) return;
        if (!returningFromPicker) mode = HostVisibility.LAN_ONLY; // pick each time
        returningFromPicker = false;
        int y = Math.min(188, screen.height - 52);
        Button choose = Button.builder(Component.translatable("cinderlink.lan.choose"), b -> {
                    returningFromPicker = true;
                    screen.getMinecraft().setScreen(new FriendPickerScreen(screen, selected));
                })
                .bounds(screen.width / 2 + 5, y, 150, 20)
                .tooltip(Tooltip.create(Component.translatable("cinderlink.lan.choose.tooltip")))
                .build();
        choose.active = mode == HostVisibility.SELECTED;
        CycleButton<HostVisibility> cycle = CycleButton.builder(HostVisibility::label)
                .withValues(HostVisibility.values())
                .withInitialValue(mode)
                .withTooltip(v -> Tooltip.create(Component.translatable("cinderlink.lan.friends.tooltip")))
                .create(screen.width / 2 - 155, y, 150, 20, Component.translatable("cinderlink.lan.friends"),
                        (b, v) -> {
                            mode = v;
                            choose.active = v == HostVisibility.SELECTED;
                        });
        event.addListener(cycle);
        event.addListener(choose);
    }

    /** Start and Cancel both close the screen; HostingManager only acts if the world actually got published. */
    @SubscribeEvent
    static void onClose(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof ShareToLanScreen && !returningFromPicker) {
            HostingManager.pendingMode = mode;
        }
    }
}
