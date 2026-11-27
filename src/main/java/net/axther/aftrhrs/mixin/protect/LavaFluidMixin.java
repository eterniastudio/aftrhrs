package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lava lighting fire ({@link FeatherEvents#FIRE_SPREAD}) and lava flowing (it overrides spreadTo: {@link FeatherEvents#FLUID_INTO}). */
@Mixin(LavaFluid.class)
abstract class LavaFluidMixin {
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean aftrhrs$ignite(ServerLevel level, BlockPos to, BlockState fire, Operation<Boolean> original, ServerLevel l, BlockPos lava, FluidState fs, RandomSource r) {
        if (!FeatherEvents.FIRE_SPREAD.invoker().allow(level, lava.immutable(), to.immutable())) return false;
        return original.call(level, to, fire);
    }

    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$flow(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo ci) {
        if (level instanceof ServerLevel sl && !FeatherEvents.FLUID_INTO.invoker().allow(sl, pos, direction)) ci.cancel();
    }
}
