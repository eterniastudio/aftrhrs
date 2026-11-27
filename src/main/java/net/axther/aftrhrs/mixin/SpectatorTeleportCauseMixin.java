package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

/** Tags spectator-menu teleports with {@link TeleportCause#SPECTATE}. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class SpectatorTeleportCauseMixin {
    @WrapMethod(method = "handleTeleportToEntityPacket")
    private void aftrhrs$spectateCause(ServerboundTeleportToEntityPacket packet, Operation<Void> original) {
        TeleportCause prev = TeleportCause.SPECTATE.push();
        try {
            original.call(packet);
        } finally {
            TeleportCause.pop(prev);
        }
    }
}
