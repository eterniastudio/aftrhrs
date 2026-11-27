package net.axther.aftrhrs.mixin;

import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import net.fabricmc.fabric.impl.networking.server.ServerPlayNetworkAddon;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Fabric's {@code ServerPlayConnectionEvents.DISCONNECT} on the server thread.
 * <p>
 * fabric-networking fires the event from {@code Connection.channelInactive} (HEAD), which runs on the Netty IO
 * thread whenever a client just closes its socket (the usual quit). Every Feather quit listener (bank robbery
 * penalties killing the robber, trade escrow hand-outs, profile saves...) then touched the world and plain
 * HashMaps off-thread. Vanilla always follows up with {@code Connection.handleDisconnection()} on the server
 * thread ({@code ServerConnectionListener.tick}), where Fabric fires the event again right before
 * {@code onDisconnect} (so before {@code PlayerList.remove} saves the player); skipping the off-thread call leaves
 * Fabric's once-only flag unset so that later call delivers it.
 */
@Mixin(value = AbstractNetworkAddon.class, remap = false)
abstract class ServerDisconnectThreadMixin {
    @Inject(method = "handleDisconnect", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$serverThreadOnly(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayNetworkAddon)) return;
        MinecraftServer server = net.axther.aftrhrs.core.Feather.serverOrNull();
        if (server != null && server.isRunning() && !server.isSameThread()) ci.cancel();
    }
}
