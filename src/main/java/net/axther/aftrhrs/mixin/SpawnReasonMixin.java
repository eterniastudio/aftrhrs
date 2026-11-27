package net.axther.aftrhrs.mixin;

import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

/**
 * Remembers spawner / trial spawner mobs with the entity tag {@code aftrhrs.spawn.<reason>} (kept in the save, copied
 * to split slimes by vanilla), so drop tables can skip farm mobs (Paper's {@code getEntitySpawnReason}).
 */
@Mixin(Mob.class)
abstract class SpawnReasonMixin {
    @Inject(method = "finalizeSpawn", at = @At("HEAD"))
    private void aftrhrs$tagSpawnReason(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                       SpawnGroupData data, CallbackInfoReturnable<SpawnGroupData> cir) {
        if (reason == EntitySpawnReason.SPAWNER || reason == EntitySpawnReason.TRIAL_SPAWNER) {
            ((Mob) (Object) this).addTag("aftrhrs.spawn." + reason.name().toLowerCase(Locale.ROOT));
        }
    }
}
