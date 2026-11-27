package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link FeatherEvents#EFFECT_APPLY}: an effect given by someone (potions, clouds, tipped arrows). */
@Mixin(LivingEntity.class)
abstract class LivingEntityEffectMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$effect(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (source == null || self.level().isClientSide()) return;
        if (!FeatherEvents.EFFECT_APPLY.invoker().allow(self, effect, source)) cir.setReturnValue(false);
    }
}
