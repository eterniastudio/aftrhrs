package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#HEAL}: before a living entity regains health (server side only). */
@Mixin(LivingEntity.class)
abstract class LivingEntityHealMixin {
    @Inject(method = "heal", at = @At("HEAD"))
    private void aftrhrs$heal(float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide() || amount <= 0 || self.getHealth() <= 0) return;
        FeatherEvents.HEAL.invoker().healed(self, amount, FeatherEvents.healReason());
    }
}
