package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#BLOCK_FORMED}: a falling block (sand, gravel, anvil...) landed here. */
@Mixin(FallingBlockEntity.class)
abstract class FallingBlockLandMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean aftrhrs$landed(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        boolean placed = original.call(level, pos, state);
        if (placed && level instanceof ServerLevel sl) FeatherEvents.BLOCK_FORMED.invoker().formed(sl, pos.immutable());
        return placed;
    }
}
