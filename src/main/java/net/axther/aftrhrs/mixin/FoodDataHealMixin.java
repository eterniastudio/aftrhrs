package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Heals from a full food bar are {@code SATIATED} ({@link FeatherEvents#HEAL}). */
@Mixin(FoodData.class)
abstract class FoodDataHealMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void aftrhrs$start(ServerPlayer player, CallbackInfo ci) {
        FeatherEvents.healReason("SATIATED");
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void aftrhrs$end(ServerPlayer player, CallbackInfo ci) {
        FeatherEvents.healReason(null);
    }
}
