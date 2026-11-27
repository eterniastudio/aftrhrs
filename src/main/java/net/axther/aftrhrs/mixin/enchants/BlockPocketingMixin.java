package net.axther.aftrhrs.mixin.enchants;

import net.axther.aftrhrs.feature.enchants.EnchantHarvest;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pocketing (enchantment): a broken block's drops go straight into the breaker's pack. Smithed tools use Warping instead. */
@Mixin(Block.class)
public abstract class BlockPocketingMixin {
    @Inject(method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true)
    private static void aftrhrs$pocket(BlockState state, Level level, BlockPos pos, @Nullable BlockEntity be, @Nullable Entity entity, ItemStack tool,
                                       CallbackInfo ci) {
        if (!(level instanceof ServerLevel sl) || tool == null || tool.isEmpty()) return;
        if (Smithing.BUILD != null && tool.has(Smithing.BUILD)) return;
        if (EnchantHarvest.pocket(state, sl, pos, be, entity, tool)) ci.cancel();
    }
}
