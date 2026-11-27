package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#PHYSICAL_INTERACT}: an arrow pressing a wooden button (Bukkit's EntityInteractEvent). */
@Mixin(ButtonBlock.class)
abstract class ButtonBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$arrowPress(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean precise, CallbackInfo ci) {
        if (!(level instanceof ServerLevel sl) || !(entity instanceof AbstractArrow)) return;
        if (!FeatherEvents.PHYSICAL_INTERACT.invoker().allow(entity, sl, pos, state)) ci.cancel();
    }
}
