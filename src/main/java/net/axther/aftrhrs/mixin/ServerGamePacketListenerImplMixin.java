package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Typed commands ({@link FeatherEvents#COMMAND}) and the swap-hands key ({@link FeatherEvents#SWAP_HANDS}). */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$unsignedCommand(String command, CallbackInfo ci) {
        if (!FeatherEvents.COMMAND.invoker().allow(player, command)) ci.cancel();
    }

    /**
     * Signed commands are offered after vanilla has read their signed arguments into the chat chain: cancelling at
     * HEAD left the server's chain behind the client's, and every later chat line failed its signature check
     * ("chain broken") until the player relogged - a muted player typing /me lost chat for the session.
     */
    @Inject(method = "performSignedChatCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V"),
            cancellable = true)
    private void aftrhrs$signedCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeen, CallbackInfo ci) {
        if (!FeatherEvents.COMMAND.invoker().allow(player, packet.command())) ci.cancel();
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"),
            cancellable = true)
    private void aftrhrs$swapHands(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND || player.isSpectator()) return;
        if (!FeatherEvents.SWAP_HANDS.invoker().allow(player)) {
            ci.cancel();
            player.containerMenu.sendAllDataToRemote();
        }
    }
}
