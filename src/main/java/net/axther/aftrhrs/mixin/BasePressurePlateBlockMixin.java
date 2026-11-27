package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#PHYSICAL_INTERACT}: stepping on a pressure plate that isn't pressed yet. */
@Mixin(BasePressurePlateBlock.class)
abstract class BasePressurePlateBlockMixin {
    @Inject(method = "entityInside", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/BasePressurePlateBlock;checkPressed(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V"),
            cancellable = true)
    private void aftrhrs$press(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean precise, CallbackInfo ci) {
        if (level instanceof ServerLevel sl && !FeatherEvents.PHYSICAL_INTERACT.invoker().allow(entity, sl, pos, state)) ci.cancel();
    }
}
