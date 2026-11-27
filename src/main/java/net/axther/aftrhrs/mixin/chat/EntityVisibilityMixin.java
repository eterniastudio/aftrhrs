package net.axther.aftrhrs.mixin.chat;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link FeatherEvents#ENTITY_VISIBLE}: per-viewer entity hiding (vanish, nametag displays). */
@Mixin(Entity.class)
abstract class EntityVisibilityMixin {
    @Inject(method = "broadcastToPlayer", at = @At("RETURN"), cancellable = true)
    private void aftrhrs$visible(ServerPlayer viewer, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !FeatherEvents.ENTITY_VISIBLE.invoker().visible((Entity) (Object) this, viewer)) cir.setReturnValue(false);
    }
}
