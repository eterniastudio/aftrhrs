package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link FeatherEvents#CRITICAL_HIT}. {@code canCriticalAttack} is only asked for full-strength attacks (it is the
 * right side of {@code fullStrength && canCriticalAttack(target)} in {@code Player.attack}), right before the damage.
 */
@Mixin(Player.class)
abstract class PlayerCritMixin {
    @Inject(method = "canCriticalAttack", at = @At("RETURN"))
    private void aftrhrs$crit(Entity target, CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (cir.getReturnValueZ() && !self.level().isClientSide()) FeatherEvents.CRITICAL_HIT.invoker().crit(self, target);
    }
}
