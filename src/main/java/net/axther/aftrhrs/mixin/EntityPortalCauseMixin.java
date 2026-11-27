package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tags portal teleports with {@link TeleportCause#NETHER_PORTAL}, {@code END_PORTAL} or {@code END_GATEWAY}.
 * Wraps only the single {@code teleportToPortalDestination} call inside {@code handlePortal} (which runs for every
 * entity every tick), so the cause costs nothing until an entity actually goes through a portal. No lambdas.
 */
@Mixin(Entity.class)
abstract class EntityPortalCauseMixin {
    @Shadow
    public PortalProcessor portalProcess;

    @WrapOperation(method = "handlePortal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;teleportToPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/portal/TeleportTransition;)V"))
    private void aftrhrs$portalCause(Entity self, ServerLevel level, TeleportTransition transition, Operation<Void> original) {
        PortalProcessor proc = portalProcess; // non-null here: handlePortal just used it
        Portal portal = proc == null ? null : ((PortalProcessorAccessor) proc).aftrhrs$portal();
        TeleportCause cause = portal instanceof EndPortalBlock ? TeleportCause.END_PORTAL
                : portal instanceof EndGatewayBlock ? TeleportCause.END_GATEWAY : TeleportCause.NETHER_PORTAL;
        TeleportCause prev = cause.push();
        try {
            original.call(self, level, transition);
        } finally {
            TeleportCause.pop(prev);
        }
    }
}
