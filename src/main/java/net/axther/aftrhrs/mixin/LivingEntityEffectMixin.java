package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link FeatherEvents#EFFECT_ADD}: asked before a potion effect is put on a living entity (server side). */
@Mixin(LivingEntity.class)
abstract class LivingEntityEffectMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void aftrhrs$addEffect(MobEffectInstance effect, @Nullable Entity source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;
        if (!FeatherEvents.EFFECT_ADD.invoker().allow(self, effect, source)) cir.setReturnValue(false);
    }
}
