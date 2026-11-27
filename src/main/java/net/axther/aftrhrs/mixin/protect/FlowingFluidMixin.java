package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#FLUID_INTO}: water (and modded fluids) spreading into a block (vanilla passes an immutable pos). */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$flow(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState target, CallbackInfo ci) {
        if (level instanceof ServerLevel sl && !FeatherEvents.FLUID_INTO.invoker().allow(sl, pos, direction)) ci.cancel();
    }
}
