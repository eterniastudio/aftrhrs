package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Peaceful-mode regeneration is {@code REGEN} ({@link FeatherEvents#HEAL}). */
@Mixin(ServerPlayer.class)
abstract class PeacefulRegenMixin {
    @Inject(method = "tickRegeneration", at = @At("HEAD"))
    private void aftrhrs$start(CallbackInfo ci) {
        FeatherEvents.healReason("REGEN");
    }

    @Inject(method = "tickRegeneration", at = @At("RETURN"))
    private void aftrhrs$end(CallbackInfo ci) {
        FeatherEvents.healReason(null);
    }
}
