package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hoppers and hopper minecarts see no container in a protected block (nothing is pulled out or pushed in). */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    // At RETURN, so the bank/mailbox lookups only run where vanilla actually found a container (most hopper
    // neighbours are air or plain blocks and return null first).
    @Inject(method = "getBlockContainer", at = @At("RETURN"), cancellable = true)
    private static void aftrhrs$protected(Level level, BlockPos pos, BlockState state, CallbackInfoReturnable<Container> cir) {
        if (cir.getReturnValue() != null && FeatherEvents.isProtected(level, pos)) cir.setReturnValue(null);
    }
}
