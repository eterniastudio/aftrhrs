package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#PHYSICAL_INTERACT}: trampling farmland. */
@Mixin(FarmlandBlock.class)
abstract class FarmlandBlockMixin {
    @WrapOperation(method = "fallOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmlandBlock;turnToBaseBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void aftrhrs$trample(FarmlandBlock self, Entity entity, BlockState state, Level level, BlockPos pos, Operation<Void> original) {
        if (level instanceof ServerLevel sl && entity != null && !FeatherEvents.PHYSICAL_INTERACT.invoker().allow(entity, sl, pos, state)) return;
        original.call(self, entity, state, level, pos);
    }
}
