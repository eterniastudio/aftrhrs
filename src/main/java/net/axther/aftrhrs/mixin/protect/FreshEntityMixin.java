package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link FeatherEvents#ENTITY_ADDED}: a brand-new entity joining a level (never one loaded from disk). */
@Mixin(ServerLevel.class)
abstract class FreshEntityMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void aftrhrs$fresh(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        FeatherEvents.ENTITY_ADDED.invoker().added(entity);
    }
}
