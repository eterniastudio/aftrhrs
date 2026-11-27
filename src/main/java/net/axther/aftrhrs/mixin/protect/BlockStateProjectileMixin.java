package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#PROJECTILE_HIT_BLOCK}: a projectile about to affect the block it hit. */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateProjectileMixin {
    @Inject(method = "onProjectileHit", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$hit(Level level, BlockState state, BlockHitResult hit, Projectile projectile, CallbackInfo ci) {
        if (level instanceof ServerLevel sl && !FeatherEvents.PROJECTILE_HIT_BLOCK.invoker().allow(projectile, sl, hit.getBlockPos(), state)) ci.cancel();
    }
}
