package net.axther.aftrhrs.mixin.chat;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** {@link FeatherEvents#SERVER_STATUS}: the server-list entry (MOTD module). */
@Mixin(ServerStatusPacketListenerImpl.class)
abstract class ServerStatusPacketListenerImplMixin {
    @ModifyArg(method = "handleStatusRequest", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/status/ClientboundStatusResponsePacket;<init>(Lnet/minecraft/network/protocol/status/ServerStatus;)V"))
    private ServerStatus aftrhrs$status(ServerStatus status) {
        try {
            ServerStatus s = FeatherEvents.SERVER_STATUS.invoker().modify(status);
            return s == null ? status : s;
        } catch (RuntimeException e) {
            AftrHrs.LOG.warn("Server list entry failed", e);
            return status;
        }
    }
}
