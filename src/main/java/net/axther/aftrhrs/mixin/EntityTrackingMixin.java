package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entities that are never saved ({@link FeatherEvents#TRANSIENT_TAG}). Per-player visibility
 * ({@link FeatherEvents#ENTITY_VISIBLE}) is asked once, by {@code mixin.chat.EntityVisibilityMixin} (a second hook here
 * asked every listener twice for every entity and viewer the tracker checks).
 */
@Mixin(Entity.class)
abstract class EntityTrackingMixin {

    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$transient(CallbackInfoReturnable<Boolean> cir) {
        if (((Entity) (Object) this).entityTags().contains(FeatherEvents.TRANSIENT_TAG)) cir.setReturnValue(false);
    }
}
