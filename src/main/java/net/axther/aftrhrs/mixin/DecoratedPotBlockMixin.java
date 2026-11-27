package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Arrows and other projectiles don't shatter a protected decorated pot. */
@Mixin(DecoratedPotBlock.class)
public abstract class DecoratedPotBlockMixin {
    @Inject(method = "onProjectileHit", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$protected(Level level, BlockState state, BlockHitResult hit, Projectile projectile, CallbackInfo ci) {
        if (FeatherEvents.isProtected(level, hit.getBlockPos())) ci.cancel();
    }
}
