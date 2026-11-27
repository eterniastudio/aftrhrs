package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#BLOCK_FORMED}: lava flowing into water made stone here. */
@Mixin(LavaFluid.class)
abstract class LavaFluidFormMixin {
    @Inject(method = "fizz", at = @At("HEAD"))
    private void aftrhrs$formed(LevelAccessor level, BlockPos pos, CallbackInfo ci) {
        if (level instanceof ServerLevel sl) FeatherEvents.BLOCK_FORMED.invoker().formed(sl, pos.immutable());
    }
}
