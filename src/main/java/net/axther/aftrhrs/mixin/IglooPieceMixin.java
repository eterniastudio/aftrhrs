package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.content.BlockSets;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Igloos are built from snow bricks and polished snow (the mod's {@code data/minecraft/structure/igloo/top.nbt}).
 * An igloo without a basement fills its trapdoor hole with a snow block; make that polished snow so the floor matches.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.IglooPieces$IglooPiece")
abstract class IglooPieceMixin {
    @ModifyArg(method = "postProcess", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"),
            index = 1)
    private BlockState aftrhrs$floorFill(BlockState state) {
        return BlockSets.iglooFloorFill(state);
    }
}
