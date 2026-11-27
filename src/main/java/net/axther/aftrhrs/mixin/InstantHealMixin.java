package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Instant health heals as {@code MAGIC} ({@link FeatherEvents#HEAL}). */
@Mixin(targets = "net.minecraft.world.effect.HealOrHarmMobEffect")
abstract class InstantHealMixin {
    @Inject(method = "applyEffectTick", at = @At("HEAD"))
    private void aftrhrs$tickStart(ServerLevel level, LivingEntity mob, int amplification, CallbackInfoReturnable<Boolean> cir) {
        FeatherEvents.healReason("MAGIC");
    }

    @Inject(method = "applyEffectTick", at = @At("RETURN"))
    private void aftrhrs$tickEnd(ServerLevel level, LivingEntity mob, int amplification, CallbackInfoReturnable<Boolean> cir) {
        FeatherEvents.healReason(null);
    }

    @Inject(method = "applyInstantaneousEffect", at = @At("HEAD"))
    private void aftrhrs$instantStart(ServerLevel level, Entity source, Entity owner, LivingEntity mob, int amplification, double scale, CallbackInfo ci) {
        FeatherEvents.healReason("MAGIC");
    }

    @Inject(method = "applyInstantaneousEffect", at = @At("RETURN"))
    private void aftrhrs$instantEnd(ServerLevel level, Entity source, Entity owner, LivingEntity mob, int amplification, double scale, CallbackInfo ci) {
        FeatherEvents.healReason(null);
    }
}
