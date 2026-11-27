package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link FeatherEvents#ENTITY_CHANGE_BLOCK} for every block a non-player entity breaks through
 * {@code Level.destroyBlock}: ravagers, withers, rabbits, silverfish, boats on lily pads, thrown water bottles.
 */
@Mixin(Level.class)
abstract class LevelDestroyBlockMixin {
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$destroy(BlockPos pos, boolean drop, Entity breaker, int limit, CallbackInfoReturnable<Boolean> cir) {
        if (breaker == null || breaker instanceof Player || !((Object) this instanceof ServerLevel level)) return;
        if (!FeatherEvents.ENTITY_CHANGE_BLOCK.invoker().allow(breaker, level, pos.immutable(), Blocks.AIR.defaultBlockState())) cir.setReturnValue(false);
    }
}
