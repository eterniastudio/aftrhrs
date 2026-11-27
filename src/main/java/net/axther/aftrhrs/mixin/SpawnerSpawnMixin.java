package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#SPAWNER_SPAWN}: a monster spawner adding a mob. */
@Mixin(BaseSpawner.class)
abstract class SpawnerSpawnMixin {
    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean aftrhrs$spawn(ServerLevel level, Entity entity, Operation<Boolean> original) {
        FeatherEvents.SPAWNER_SPAWN.invoker().spawning(level, entity, false);
        return original.call(level, entity);
    }
}
