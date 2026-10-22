package dev.devilswarchild.cinderlink.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.math.Axis;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import org.jetbrains.annotations.Nullable;

/**
 * Your player model standing on the title screen, turning to look at the mouse like in the inventory.
 * Purely decorative: it can't be clicked or focused.
 */
final class PlayerModelWidget extends AbstractWidget {
    private static final float MODEL_HEIGHT = 2.125f; // blocks, feet to top of head (with the hat layer)
    private final PlayerModel<?> wide;
    private final PlayerModel<?> slim;

    PlayerModelWidget(int x, int y, int width, int height, EntityModelSet models) {
        super(x, y, width, height, CommonComponents.EMPTY);
        wide = new PlayerModel<>(models.bakeLayer(ModelLayers.PLAYER), false);
        slim = new PlayerModel<>(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        wide.young = false;
        slim.young = false;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        PlayerSkin skin = Skins.mine();
        PlayerModel<?> model = skin.model() == PlayerSkin.Model.SLIM ? slim : wide;

        // Same feel as the inventory: the body turns a little toward the mouse, the head turns further.
        float cx = getX() + getWidth() / 2f;
        float eyes = getY() + getHeight() * 0.12f;
        float yaw = (float) Math.atan((cx - mouseX) / 40f);
        float pitch = (float) Math.atan((eyes - mouseY) / 40f);
        float bodyYaw = yaw * 20f;
        model.head.yRot = (float) Math.toRadians(yaw * 20f);
        model.head.xRot = (float) Math.toRadians(-pitch * 20f);
        model.hat.copyFrom(model.head);

        float scale = getHeight() / MODEL_HEIGHT;
        g.pose().pushPose();
        g.pose().translate(cx, getY() + getHeight(), 100f);
        g.pose().scale(scale, scale, scale);
        g.pose().translate(0f, -0.0625f, 0f);
        g.pose().mulPose(Axis.YP.rotationDegrees(-bodyYaw));
        g.flush();
        Lighting.setupForEntityInInventory(Axis.XP.rotationDegrees(0f));
        g.pose().pushPose();
        g.pose().scale(1f, 1f, -1f);
        g.pose().translate(0f, -1.5f, 0f);
        model.renderToBuffer(g.pose(), g.bufferSource().getBuffer(model.renderType(skin.texture())),
                15728880, OverlayTexture.NO_OVERLAY);
        g.pose().popPose();
        g.flush();
        Lighting.setupFor3DItems();
        g.pose().popPose();
    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public void playDownSound(SoundManager sounds) {}

    @Nullable
    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        return null;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
