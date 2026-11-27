package net.axther.aftrhrs.entity.mixin;

import net.axther.aftrhrs.entity.ArcanaMount;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mount rider's left-click in the air (arm swing): the Cinderwing Phoenix kindles the block they look at. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class RiderPunchMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePunch", at = @At("TAIL"))
    private void aftrhrs$riderSwing(ServerboundPunchPacket packet, CallbackInfo ci) {
        if (player.getVehicle() instanceof ArcanaMount mount) Abilities.onRiderSwing(mount, player);
    }
}
