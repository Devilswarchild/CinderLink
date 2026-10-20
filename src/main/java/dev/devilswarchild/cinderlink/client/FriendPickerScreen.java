package dev.devilswarchild.cinderlink.client;

import dev.devilswarchild.cinderlink.core.presence.Friend;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Tick the friends who may see and join this session. */
public final class FriendPickerScreen extends Screen {
    private final Screen parent;
    private final Set<String> selected;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private PickList list;

    public FriendPickerScreen(Screen parent, Set<String> selected) {
        super(Component.translatable("cinderlink.picker.title"));
        this.parent = parent;
        this.selected = selected;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        list = layout.addToContents(new PickList(minecraft, width));
        List<Friend> friends = FriendsRuntime.presence()
                .map(p -> p.store().withRelation(Friend.Relation.FRIEND)).orElse(List.of());
        friends.stream().sorted(Comparator.comparing(f -> f.mcName().toLowerCase())).forEach(f -> list.add(f));
        layout.addToFooter(net.minecraft.client.gui.components.Button.builder(CommonComponents.GUI_DONE,
                b -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        list.updateSize(width, layout);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (list.children().isEmpty()) {
            g.drawCenteredString(font, Component.translatable("cinderlink.picker.empty"), width / 2, height / 2, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    final class PickList extends ContainerObjectSelectionList<PickList.Row> {
        PickList(Minecraft mc, int width) {
            super(mc, width, 0, 0, 24);
        }

        void add(Friend f) {
            addEntry(new Row(f));
        }

        final class Row extends ContainerObjectSelectionList.Entry<Row> {
            private final Checkbox box;

            Row(Friend f) {
                box = Checkbox.builder(Component.literal(f.mcName()), font)
                        .selected(selected.contains(f.id()))
                        .onValueChange((cb, v) -> {
                            if (v) selected.add(f.id());
                            else selected.remove(f.id());
                        })
                        .build();
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX,
                               int mouseY, boolean hovering, float partialTick) {
                box.setPosition(left + 4, top + 2);
                box.render(g, mouseX, mouseY, partialTick);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(box);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(box);
            }
        }
    }
}
