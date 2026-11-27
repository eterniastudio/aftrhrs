package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link FeatherEvents#ENTITY_CHANGE_BLOCK} for the blocks the ender dragon flies through. It takes them out with
 * {@code removeBlock}, which skips {@link LevelDestroyBlockMixin}, so it used to tear out locked chests, graves and
 * claimed blocks in the End.
 */
@Mixin(EnderDragon.class)
abstract class GriefDragonWalls {
    @WrapOperation(method = "checkWalls", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean aftrhrs$remove(ServerLevel level, BlockPos pos, boolean moved, Operation<Boolean> original) {
        return net.axther.aftrhrs.core.event.EntityChangeBlocks.allow((EnderDragon) (Object) this, level, pos, Blocks.AIR.defaultBlockState())
                && original.call(level, pos, moved);
    }
}
