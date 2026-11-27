package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code minecraft:custom} clicks (dialog buttons) arrive here with no player attached; fire them with the player. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerImplMixin {
    @Inject(method = "handleCustomClickAction", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"),
            cancellable = true)
    private void aftrhrs$customClick(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && game.player != null) {
            if (FeatherEvents.CUSTOM_CLICK.invoker().onClick(game.player, packet.id(), packet.payload())) ci.cancel();
        }
    }
}
