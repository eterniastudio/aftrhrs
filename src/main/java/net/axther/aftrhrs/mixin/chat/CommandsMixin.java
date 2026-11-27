package net.axther.aftrhrs.mixin.chat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.tree.CommandNode;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/** {@link FeatherEvents#SEND_COMMANDS}: an empty command tree for players who may not see any (auth gate). */
@Mixin(Commands.class)
abstract class CommandsMixin {
    @WrapOperation(method = "sendCommands", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;fillUsableCommands(Lcom/mojang/brigadier/tree/CommandNode;Lcom/mojang/brigadier/tree/CommandNode;Ljava/lang/Object;Ljava/util/Map;)V"))
    private void aftrhrs$fill(CommandNode<?> source, CommandNode<?> target, Object filter, Map<?, ?> converted, Operation<Void> original,
                              @Local(argsOnly = true) ServerPlayer player) {
        if (FeatherEvents.SEND_COMMANDS.invoker().allow(player)) original.call(source, target, filter, converted);
    }
}
