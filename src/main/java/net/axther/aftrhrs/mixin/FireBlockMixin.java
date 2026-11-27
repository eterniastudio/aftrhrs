package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire never burns a protected block away (nor sets it alight); checked only once vanilla's burn-odds roll has passed. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    // Ordinal 1 = the re-read inside `if (random.nextInt(chance) < getBurnOdds(state))`: only blocks that will actually burn or be replaced by fire get here.
    @Inject(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 1), cancellable = true, require = 1)
    private void aftrhrs$protected(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
        if (FeatherEvents.isProtected(level, pos)) ci.cancel();
    }
}
