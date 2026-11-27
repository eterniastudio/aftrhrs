package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link FeatherEvents#NATURAL_SPAWN_AT} for each spot the natural spawner tries (before a mob is picked or built),
 * and {@link FeatherEvents#MOB_SPAWN} for natural spawns (after the mob's own spawn rules said yes: the exact backstop).
 */
@Mixin(NaturalSpawner.class)
abstract class MobSpawnMixins {
    @ModifyExpressionValue(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;isRightDistanceToPlayerAndSpawnPoint(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos$MutableBlockPos;D)Z"))
    private static boolean aftrhrs$spawnAt(boolean ok, @Local(argsOnly = true) MobCategory category,
                                           @Local(argsOnly = true) ServerLevel level, @Local BlockPos.MutableBlockPos pos) {
        return ok && FeatherEvents.NATURAL_SPAWN_AT.invoker().allow(level, category, pos);
    }

    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;isValidPositionForMob(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;D)Z"))
    private static boolean aftrhrs$natural(ServerLevel level, Mob mob, double dist, Operation<Boolean> original) {
        return original.call(level, mob, dist) && FeatherEvents.MOB_SPAWN.invoker().allow(level, mob, EntitySpawnReason.NATURAL);
    }
}
