package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#HOOK_PULL}: reeling in a hooked entity. */
@Mixin(FishingHook.class)
abstract class FishingHookMixin {
    @Inject(method = "pullEntity", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$pull(Entity entity, CallbackInfo ci) {
        if (((FishingHook) (Object) this).getPlayerOwner() instanceof ServerPlayer p && !FeatherEvents.HOOK_PULL.invoker().allow(p, entity)) ci.cancel();
    }
}
