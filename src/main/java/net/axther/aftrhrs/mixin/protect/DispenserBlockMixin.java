package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#DISPENSE}: dispensers and droppers (which override dispenseFrom) about to fire. */
@Mixin({DispenserBlock.class, DropperBlock.class})
abstract class DispenserBlockMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$dispense(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!FeatherEvents.DISPENSE.invoker().allow(level, pos.immutable(), state)) ci.cancel();
    }
}
