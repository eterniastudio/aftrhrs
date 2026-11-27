package net.axther.aftrhrs.mixin.chat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/** {@link FeatherEvents#PLAYER_LOGIN} (who may log in, and with which screen) and {@link FeatherEvents#JOIN_MESSAGE}. */
@Mixin(PlayerList.class)
abstract class PlayerListLoginMixin {
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void aftrhrs$login(SocketAddress address, NameAndId profile, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(FeatherEvents.PLAYER_LOGIN.invoker().check(profile, address, cir.getReturnValue()));
    }

    @WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void aftrhrs$joinMessage(PlayerList list, Component message, boolean overlay, Operation<Void> original,
                                     Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        Component c = FeatherEvents.JOIN_MESSAGE.invoker().message(player, message);
        if (c != null) original.call(list, c, overlay);
    }
}
