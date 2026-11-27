package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The regeneration effect heals as {@code MAGIC_REGEN} ({@link FeatherEvents#HEAL}). */
@Mixin(targets = "net.minecraft.world.effect.RegenerationMobEffect")
abstract class RegenerationEffectHealMixin {
    @Inject(method = "applyEffectTick", at = @At("HEAD"))
    private void aftrhrs$start(ServerLevel level, LivingEntity mob, int amplification, CallbackInfoReturnable<Boolean> cir) {
        FeatherEvents.healReason("MAGIC_REGEN");
    }

    @Inject(method = "applyEffectTick", at = @At("RETURN"))
    private void aftrhrs$end(ServerLevel level, LivingEntity mob, int amplification, CallbackInfoReturnable<Boolean> cir) {
        FeatherEvents.healReason(null);
    }
}
