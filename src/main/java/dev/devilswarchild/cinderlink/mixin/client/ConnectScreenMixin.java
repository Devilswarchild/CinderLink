package dev.devilswarchild.cinderlink.mixin.client;

import dev.devilswarchild.cinderlink.client.CinderJoin;
import net.minecraft.client.gui.screens.ConnectScreen;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a friend's address out of latest.log when joining them. Normal server connections are untouched. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void cinderlink$markJoin(CallbackInfo ci) {
        CinderJoin.active = CinderJoin.armed;
        CinderJoin.armed = false;
    }

    @Redirect(method = "connect", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", remap = false))
    private void cinderlink$quietConnect(Logger logger, String msg, Object host, Object port) {
        if (CinderJoin.active) {
            logger.info("Connecting to a CinderLink friend");
        } else {
            logger.info(msg, host, port);
        }
    }
}
