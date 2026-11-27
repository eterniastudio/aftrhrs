package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#MOB_SPAWN} for pillager patrols. */
@Mixin(PatrolSpawner.class)
abstract class PatrolSpawnerMixin {
    @WrapOperation(method = "spawnPatrolMember", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"))
    private void aftrhrs$patrol(ServerLevel level, Entity entity, Operation<Void> original) {
        if (entity instanceof Mob mob && !FeatherEvents.MOB_SPAWN.invoker().allow(level, mob, EntitySpawnReason.PATROL)) return;
        original.call(level, entity);
    }
}
