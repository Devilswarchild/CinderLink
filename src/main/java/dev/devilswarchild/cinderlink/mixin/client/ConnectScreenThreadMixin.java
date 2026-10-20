package dev.devilswarchild.cinderlink.mixin.client;

import dev.devilswarchild.cinderlink.client.CinderJoin;
import dev.devilswarchild.cinderlink.core.util.SafeLog;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The connector thread logs the full exception (which includes the address) when a connection fails. */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class ConnectScreenThreadMixin {
    @Redirect(method = "run", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private void cinderlink$quietFailure(Logger logger, String msg, Throwable t) {
        if (CinderJoin.active) {
            logger.error("{}: {}", msg, SafeLog.redact(String.valueOf(t)));
        } else {
            logger.error(msg, t);
        }
    }
}
