package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.TurtleEggBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link FeatherEvents#PHYSICAL_INTERACT}: trampling turtle eggs. */
@Mixin(TurtleEggBlock.class)
abstract class TurtleEggBlockMixin {
    @Inject(method = "canDestroyEgg", at = @At("RETURN"), cancellable = true)
    private void aftrhrs$trample(ServerLevel level, BlockPos pos, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !FeatherEvents.PHYSICAL_INTERACT.invoker().allow(entity, level, pos, level.getBlockState(pos))) cir.setReturnValue(false);
    }
}
