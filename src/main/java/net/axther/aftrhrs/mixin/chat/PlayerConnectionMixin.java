package net.axther.aftrhrs.mixin.chat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#QUIT_MESSAGE} and {@link FeatherEvents#CONTAINER_CLICK}. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PlayerConnectionMixin {
    @Shadow
    public ServerPlayer player;

    @WrapOperation(method = "removePlayerFromWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void aftrhrs$quitMessage(PlayerList list, Component message, boolean overlay, Operation<Void> original) {
        Component c = FeatherEvents.QUIT_MESSAGE.invoker().message(player, message);
        if (c != null) original.call(list, c, overlay);
    }

    @WrapOperation(method = "handleCustomCommandSuggestions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerCommandSuggestionsProvider;request(Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;"))
    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> aftrhrs$suggestions(
            net.minecraft.server.network.ServerCommandSuggestionsProvider provider, String command,
            Operation<java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>> original) {
        String line = command.startsWith("/") ? command.substring(1) : command;
        return original.call(provider, command).thenApply(s -> {
            try {
                var out = FeatherEvents.COMMAND_SUGGESTIONS.invoker().modify(player, line, s);
                return out == null ? s : out;
            } catch (RuntimeException e) {
                return s;
            }
        });
    }

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"),
            cancellable = true)
    private void aftrhrs$containerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (player.containerMenu.containerId != packet.containerId()) return;
        if (!FeatherEvents.CONTAINER_CLICK.invoker().allow(player, player.containerMenu)) {
            ci.cancel();
            player.containerMenu.sendAllDataToRemote();
        }
    }
}
