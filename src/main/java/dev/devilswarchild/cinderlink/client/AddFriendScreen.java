package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.core.presence.PresenceService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Paste a friend's Cinder Code to send them a request. */
public final class AddFriendScreen extends Screen {
    private final Screen parent;
    private EditBox codeBox;
    private Button send;
    private Component message = Component.empty();

    public AddFriendScreen(Screen parent) {
        super(Component.translatable("cinderlink.add.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        codeBox = new EditBox(font, cx - 160, height / 2 - 20, 320, 20, Component.translatable("cinderlink.add.code"));
        codeBox.setMaxLength(120);
        codeBox.setHint(Component.literal("CNDR-...").withStyle(ChatFormatting.DARK_GRAY));
        codeBox.setResponder(s -> {
            send.active = !s.isBlank();
            message = Component.empty();
        });
        addRenderableWidget(codeBox);
        send = addRenderableWidget(Button.builder(Component.translatable("cinderlink.add.send"), b -> submit())
                .bounds(cx - 155, height / 2 + 24, 150, 20).build());
        send.active = false;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(cx + 5, height / 2 + 24, 150, 20).build());
        setInitialFocus(codeBox);
    }

    private void submit() {
        PresenceService p = FriendsRuntime.presence().orElse(null);
        if (p == null) {
            message = Component.translatable("cinderlink.add.not_running").withStyle(ChatFormatting.RED);
            return;
        }
        PresenceService.AddResult r = p.addByCode(codeBox.getValue());
        switch (r) {
            case SENT, ACCEPTED_THEIRS -> {
                SystemToast.addOrUpdate(minecraft.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("cinderlink.toast.sent.title"),
                        Component.translatable(r == PresenceService.AddResult.SENT
                                ? "cinderlink.toast.sent" : "cinderlink.toast.now_friends"));
                onClose();
            }
            default -> message = Component.translatable("cinderlink.add.result." + r.name().toLowerCase())
                    .withStyle(ChatFormatting.RED);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("cinderlink.add.help"), width / 2, height / 2 - 40, 0xFFA0A0A0);
        g.drawCenteredString(font, message, width / 2, height / 2 + 4, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
