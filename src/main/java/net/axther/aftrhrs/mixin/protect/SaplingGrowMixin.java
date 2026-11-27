package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#PLANT_GROW}: a sapling growing into a tree (random tick or bone meal). */
@Mixin(SaplingBlock.class)
abstract class SaplingGrowMixin {
    @WrapOperation(method = "advanceTree", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/grower/TreeGrower;growTree(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)Z"))
    private boolean aftrhrs$tree(TreeGrower grower, ServerLevel level, ChunkGenerator gen, BlockPos pos, BlockState state, RandomSource random, Operation<Boolean> original) {
        return FeatherEvents.PLANT_GROW.invoker().allow(level, pos) && original.call(grower, level, gen, pos, state, random);
    }
}
