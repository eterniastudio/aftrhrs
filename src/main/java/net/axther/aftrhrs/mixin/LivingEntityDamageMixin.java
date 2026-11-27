package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** {@link FeatherEvents#DAMAGE_AMOUNT}: lets modules scale incoming damage before vanilla armor and effects. */
@Mixin(LivingEntity.class)
abstract class LivingEntityDamageMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float aftrhrs$damageAmount(float amount, ServerLevel level, DamageSource source) {
        return FeatherEvents.DAMAGE_AMOUNT.invoker().modify((LivingEntity) (Object) this, source, amount);
    }
}
