package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#THUNDER_HIT}: every entity a bolt strikes (one call site covers all thunderHit overrides). */
@Mixin(LightningBolt.class)
abstract class LightningBoltMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;thunderHit(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LightningBolt;)V"))
    private void aftrhrs$thunderHit(Entity target, ServerLevel level, LightningBolt bolt, Operation<Void> original) {
        if (FeatherEvents.THUNDER_HIT.invoker().allow(target, bolt)) original.call(target, level, bolt);
    }
}
