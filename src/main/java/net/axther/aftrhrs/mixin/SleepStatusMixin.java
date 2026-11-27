package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#SLEEP_IGNORED}: ignored players count like spectators when the sleeping players are counted. */
@Mixin(SleepStatus.class)
abstract class SleepStatusMixin {
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
    private boolean aftrhrs$ignored(ServerPlayer player, Operation<Boolean> original) {
        return original.call(player) || FeatherEvents.SLEEP_IGNORED.invoker().ignored(player);
    }
}
