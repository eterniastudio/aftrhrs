package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Left-click into the air ({@link FeatherEvents#PLAYER_PUNCH}). */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PlayerPunchMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePunch", at = @At("TAIL"))
    private void aftrhrs$punch(ServerboundPunchPacket packet, CallbackInfo ci) {
        FeatherEvents.PLAYER_PUNCH.invoker().punched(player);
    }
}
