package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.core.presence.Friend;
import dev.devilswarchild.cinderlink.core.presence.PresenceService;
import dev.devilswarchild.cinderlink.core.presence.PresenceState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The friends list: requests, friends with live status, click to join, and your Cinder Code. */
public final class FriendsScreen extends Screen {
    static final int EMBER = 0xFFFF8A3D, ONLINE = 0xFF55FF55, OFFLINE = 0xFF555555;

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 48, 60);
    private FriendList list;
    private String lastSnapshot = "";
    private int refreshTimer;

    public FriendsScreen(Screen parent) {
        super(Component.translatable("cinderlink.friends.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        list = layout.addToContents(new FriendList(minecraft, width));
        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        LinearLayout row = footer.addChild(LinearLayout.horizontal().spacing(8));
        row.addChild(Button.builder(Component.translatable("cinderlink.friends.add"),
                b -> minecraft.setScreen(new AddFriendScreen(this))).width(98).build());
        row.addChild(Button.builder(Component.translatable("cinderlink.friends.copy_code"), b -> copyCode())
                .width(98).tooltip(Tooltip.create(Component.translatable("cinderlink.friends.copy_code.tooltip"))).build());
        row.addChild(Button.builder(Component.translatable("cinderlink.friends.reset_code"), b -> confirmReset())
                .width(98).tooltip(Tooltip.create(Component.translatable("cinderlink.friends.reset_code.tooltip"))).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        rebuild();
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        list.updateSize(width, layout);
    }

    @Override
    public void tick() {
        if (++refreshTimer >= 10) {
            refreshTimer = 0;
            rebuild();
        }
    }

    private Optional<PresenceService> presence() {
        return FriendsRuntime.presence();
    }

    /** Rebuilds rows only when something visible changed, so buttons don't flicker under the mouse. */
    private void rebuild() {
        List<PresenceService.FriendView> views = presence().map(PresenceService::views).orElse(List.of());
        StringBuilder snap = new StringBuilder(String.valueOf(HostingManager.isHosting()));
        for (var v : views) {
            snap.append(v.friend().id()).append(v.friend().relation()).append(v.friend().mcName())
                .append(v.state()).append(v.world()).append(v.canJoin())
                .append(HostingManager.isVisibleTo(v.friend().id()));
        }
        if (snap.toString().equals(lastSnapshot)) return;
        lastSnapshot = snap.toString();
        double scroll = list.getScrollAmount();
        list.clear();
        List<PresenceService.FriendView> requests = new ArrayList<>(), friends = new ArrayList<>(), pending = new ArrayList<>();
        for (var v : views) {
            switch (v.friend().relation()) {
                case INCOMING -> requests.add(v);
                case FRIEND -> friends.add(v);
                case OUTGOING -> pending.add(v);
            }
        }
        if (!requests.isEmpty()) {
            list.header(Component.translatable("cinderlink.friends.section.requests", requests.size()));
            requests.forEach(v -> list.add(new RequestRow(v)));
        }
        list.header(Component.translatable("cinderlink.friends.section.friends", friends.size()));
        if (friends.isEmpty()) list.add(new TextRow(Component.translatable("cinderlink.friends.none")));
        friends.sort(Comparator.<PresenceService.FriendView>comparingInt(v -> -v.state().ordinal())
                .thenComparing(v -> v.friend().mcName().toLowerCase()));
        friends.forEach(v -> list.add(new FriendRow(v)));
        if (!pending.isEmpty()) {
            list.header(Component.translatable("cinderlink.friends.section.pending", pending.size()));
            pending.forEach(v -> list.add(new PendingRow(v)));
        }
        list.setScrollAmount(scroll);
    }

    private void copyCode() {
        presence().ifPresent(p -> {
            minecraft.keyboardHandler.setClipboard(p.identity().code().format());
            SystemToast.addOrUpdate(minecraft.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("cinderlink.toast.copied.title"),
                    Component.translatable("cinderlink.toast.copied"));
        });
    }

    private void confirmReset() {
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) presence().ifPresent(p -> {
                try {
                    p.resetCode();
                } catch (Exception ignored) {
                    // keeps the old code if the file couldn't be written
                }
            });
            minecraft.setScreen(this);
        }, Component.translatable("cinderlink.reset.title"), Component.translatable("cinderlink.reset.body")));
    }

    private void confirm(Component title, Component body, Runnable action) {
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) action.run();
            lastSnapshot = "";
            minecraft.setScreen(this);
        }, title, body));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Component status = statusLine();
        if (status != null) g.drawCenteredString(font, status, width / 2, 34, 0xFFAAAAAA);
    }

    private Component statusLine() {
        String problem = FriendsRuntime.problem();
        if ("disabled".equals(problem)) return Component.translatable("cinderlink.friends.status.disabled").withStyle(ChatFormatting.RED);
        if ("network".equals(problem)) return Component.translatable("cinderlink.friends.status.network").withStyle(ChatFormatting.RED);
        if ("startup".equals(problem)) return Component.translatable("cinderlink.friends.status.startup").withStyle(ChatFormatting.RED);
        if (presence().isEmpty()) return Component.translatable("cinderlink.friends.status.starting");
        return presence().map(p -> Component.translatable("cinderlink.friends.your_id",
                fingerprint(p.identity().signPublic()))).orElse(null);
    }

    static String fingerprint(byte[] key) {
        String hex = java.util.HexFormat.of().withUpperCase().formatHex(key, 0, 4);
        return hex.substring(0, 4) + "-" + hex.substring(4);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ list + rows

    final class FriendList extends ContainerObjectSelectionList<Row> {
        FriendList(Minecraft mc, int width) {
            super(mc, width, 0, 0, 24);
        }

        void clear() {
            clearEntries();
        }

        void add(Row r) {
            addEntry(r);
        }

        void header(Component text) {
            addEntry(new HeaderRow(text));
        }

        @Override
        public int getRowWidth() {
            return Math.min(360, width - 20);
        }
    }

    abstract class Row extends ContainerObjectSelectionList.Entry<Row> {
        final List<Button> buttons = new ArrayList<>();

        Button button(String key, int w, Button.OnPress press) {
            Button b = Button.builder(Component.translatable(key), press).width(w).build();
            buttons.add(b);
            return b;
        }

        /** Lays out buttons right-aligned and draws them. */
        void renderButtons(GuiGraphics g, int top, int left, int width, int mouseX, int mouseY, float pt) {
            int x = left + width;
            for (int i = buttons.size() - 1; i >= 0; i--) {
                Button b = buttons.get(i);
                x -= b.getWidth();
                b.setPosition(x, top + 1);
                b.render(g, mouseX, mouseY, pt);
                x -= 4;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return buttons;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return buttons;
        }
    }

    final class HeaderRow extends Row {
        private final Component text;

        HeaderRow(Component text) {
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float pt) {
            g.drawString(font, text.copy().withStyle(ChatFormatting.GOLD), left, top + 8, 0xFFFFFFFF);
        }
    }

    final class TextRow extends Row {
        private final Component text;

        TextRow(Component text) {
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float pt) {
            g.drawString(font, text, left + 4, top + 7, 0xFF808080);
        }
    }

    final class FriendRow extends Row {
        private final PresenceService.FriendView v;

        FriendRow(PresenceService.FriendView v) {
            this.v = v;
            if (v.canJoin()) {
                button("cinderlink.friends.join", 50, b -> JoinFlow.join(FriendsScreen.this, v));
            }
            if (HostingManager.isHosting() && !HostingManager.isVisibleTo(v.friend().id())) {
                button("cinderlink.friends.invite", 50, b -> {
                    HostingManager.invite(v.friend());
                    lastSnapshot = "";
                });
            }
            button("cinderlink.friends.remove_short", 20, b -> confirm(
                    Component.translatable("cinderlink.remove.title", v.friend().mcName()),
                    Component.translatable("cinderlink.remove.body"),
                    () -> presence().ifPresent(p -> p.remove(v.friend().id()))))
                    .setTooltip(Tooltip.create(Component.translatable("cinderlink.friends.remove")));
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float pt) {
            int color = switch (v.state()) {
                case HOSTING -> EMBER;
                case ONLINE -> ONLINE;
                default -> OFFLINE;
            };
            // Their face, greyed out when offline, with a small status light in the corner.
            Skins.drawFace(g, v.friend().mcUuid(), left + 2, top + 2, 18, v.state() == PresenceState.OFFLINE);
            g.fill(left + 15, top + 15, left + 22, top + 22, 0xFF000000);
            g.fill(left + 16, top + 16, left + 21, top + 21, color);
            if (v.state() == PresenceState.HOSTING) g.fill(left + 17, top + 14, left + 20, top + 16, 0xFFFFD27F); // ember glow
            g.drawString(font, v.friend().mcName(), left + 26, top + 3, 0xFFFFFFFF);
            Component status = switch (v.state()) {
                case HOSTING -> v.canJoin()
                        ? Component.translatable("cinderlink.state.hosting", v.world())
                        : Component.translatable("cinderlink.state.hosting_private");
                case ONLINE -> Component.translatable("cinderlink.state.online");
                default -> Component.translatable("cinderlink.state.offline");
            };
            g.drawString(font, font.plainSubstrByWidth(status.getString(), width - 152), left + 26, top + 13,
                    0xFF9A9A9A);
            renderButtons(g, top, left, width, mouseX, mouseY, pt);
        }
    }

    final class RequestRow extends Row {
        private final PresenceService.FriendView v;

        RequestRow(PresenceService.FriendView v) {
            this.v = v;
            String id = v.friend().id();
            button("cinderlink.friends.accept", 50, b -> {
                presence().ifPresent(p -> p.accept(id));
                lastSnapshot = "";
            });
            button("cinderlink.friends.decline", 50, b -> {
                presence().ifPresent(p -> p.decline(id));
                lastSnapshot = "";
            });
            button("cinderlink.friends.block", 40, b -> confirm(
                    Component.translatable("cinderlink.block.title", v.friend().mcName()),
                    Component.translatable("cinderlink.block.body"),
                    () -> presence().ifPresent(p -> p.block(id))));
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float pt) {
            Skins.drawFace(g, v.friend().mcUuid(), left + 2, top + 2, 18, false);
            g.drawString(font, v.friend().mcName(), left + 26, top + 3, 0xFFFFFFFF);
            g.drawString(font, Component.translatable("cinderlink.friends.request_id", v.friend().fingerprint()),
                    left + 26, top + 13, 0xFF9A9A9A);
            renderButtons(g, top, left, width, mouseX, mouseY, pt);
        }
    }

    final class PendingRow extends Row {
        private final PresenceService.FriendView v;

        PendingRow(PresenceService.FriendView v) {
            this.v = v;
            button("cinderlink.friends.cancel", 50, b -> {
                presence().ifPresent(p -> p.decline(v.friend().id()));
                lastSnapshot = "";
            });
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float pt) {
            // We don't know whose code this is until they accept: a plain "?" head.
            g.fill(left + 2, top + 2, left + 20, top + 20, 0xFF3A3A3A);
            g.drawCenteredString(font, "?", left + 11, top + 7, 0xFFAAAAAA);
            g.drawString(font, Component.translatable("cinderlink.friends.pending", v.friend().fingerprint()),
                    left + 26, top + 3, 0xFFCCCCCC);
            g.drawString(font, Component.translatable("cinderlink.friends.pending.hint"), left + 26, top + 13, 0xFF808080);
            renderButtons(g, top, left, width, mouseX, mouseY, pt);
        }
    }
}
