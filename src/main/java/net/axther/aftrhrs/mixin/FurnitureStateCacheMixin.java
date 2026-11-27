package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Furniture states share vanilla's per-state shape Cache by part and quadrant ({@link FurnitureBlock#sharedStateCaches}):
 * building ~23k identical caches was most of the furniture registration time at startup. The Cache holds only
 * collision-shape data, which for furniture depends on nothing else. A benign race only builds a duplicate Cache.
 * {@code require = 0}: if initCache changes, every state just builds its own again.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class FurnitureStateCacheMixin {
    @WrapOperation(method = "initCache", require = 0,
            at = @At(value = "NEW", target = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache"))
    private BlockBehaviour.BlockStateBase.Cache aftrhrs$shareFurnitureCache(BlockState state,
                                                                           Operation<BlockBehaviour.BlockStateBase.Cache> original) {
        if (!(state.getBlock() instanceof FurnitureBlock fb)) return original.call(state);
        int k = fb.stateCacheKey(state);
        Object[] slots = fb.sharedStateCaches;
        if (k < 0 || k >= slots.length) return original.call(state);
        Object c = slots[k];
        if (c == null) slots[k] = c = original.call(state);
        return (BlockBehaviour.BlockStateBase.Cache) c;
    }
}
