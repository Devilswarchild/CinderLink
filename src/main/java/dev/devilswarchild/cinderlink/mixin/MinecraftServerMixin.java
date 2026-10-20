package dev.devilswarchild.cinderlink.mixin;

import dev.devilswarchild.cinderlink.hosting.JoinGate;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's own "log IPs" switch: while a world is hosted through CinderLink, friends' IP addresses are
 * written to latest.log as "IP hidden" instead (people post their logs publicly when asking for help).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "logIPs", at = @At("HEAD"), cancellable = true)
    private void cinderlink$hideIps(CallbackInfoReturnable<Boolean> cir) {
        if (JoinGate.isActive()) cir.setReturnValue(false);
    }
}
