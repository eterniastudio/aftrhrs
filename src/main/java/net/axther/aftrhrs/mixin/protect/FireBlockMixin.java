package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link FeatherEvents#FIRE_SPREAD} (fire jumping to a new block) and {@link FeatherEvents#BLOCK_BURN} (fire eating
 * one). The burn hook fires only once vanilla's burn-odds roll has passed (the block will really burn or turn to fire).
 */
@Mixin(FireBlock.class)
abstract class FireBlockMixin {
    /** The fire being ticked (server thread only), for checkBurnOut, which isn't told where the fire is. */
    @Unique private static BlockPos aftrhrs$fire;

    @Inject(method = "tick", at = @At("HEAD"))
    private void aftrhrs$remember(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        aftrhrs$fire = pos.immutable();
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean aftrhrs$spread(ServerLevel level, BlockPos to, BlockState fire, Operation<Boolean> original) {
        BlockPos from = aftrhrs$fire;
        if (from != null && !FeatherEvents.FIRE_SPREAD.invoker().allow(level, from, to.immutable())) return false;
        return original.call(level, to, fire);
    }

    // Ordinal 1 = the re-read inside `if (random.nextInt(chance) < getBurnOdds(state))` (air has no burn odds).
    @Inject(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 1), cancellable = true, require = 1)
    private void aftrhrs$burn(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
        if (!(level instanceof ServerLevel sl) || aftrhrs$fire == null) return;
        if (!FeatherEvents.BLOCK_BURN.invoker().allow(sl, aftrhrs$fire, pos)) ci.cancel();
    }
}
