package dev.devilswarchild.cinderlink.mixin;

import com.mojang.authlib.GameProfile;
import dev.devilswarchild.cinderlink.hosting.JoinGate;
import java.net.SocketAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs during login, before the player is spawned or sent any world data, so a refused player never sees
 * anything. Only does something while CinderLink internet hosting is active.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void cinderlink$gateLogin(SocketAddress address, GameProfile profile,
                                      CallbackInfoReturnable<Component> cir) {
        if (!JoinGate.isActive()) return;
        if (server.isSingleplayerOwner(profile)) return;
        if (!JoinGate.permits(profile.getId())) {
            cir.setReturnValue(Component.translatable("cinderlink.join.not_allowed"));
        }
    }
}
