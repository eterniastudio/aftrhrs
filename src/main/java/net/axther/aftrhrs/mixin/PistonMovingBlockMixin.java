package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#BLOCK_FORMED}: a piston finished moving a block into this spot. */
@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingBlockMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private static void aftrhrs$moved(Level level, BlockPos pos, BlockState state, PistonMovingBlockEntity entity, CallbackInfo ci) {
        if (level instanceof ServerLevel sl) FeatherEvents.BLOCK_FORMED.invoker().formed(sl, pos.immutable());
    }

    @Inject(method = "finalTick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private void aftrhrs$finished(CallbackInfo ci) {
        PistonMovingBlockEntity self = (PistonMovingBlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel sl) FeatherEvents.BLOCK_FORMED.invoker().formed(sl, self.getBlockPos().immutable());
    }
}
