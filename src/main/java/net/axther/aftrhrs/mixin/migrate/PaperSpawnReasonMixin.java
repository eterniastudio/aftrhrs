package net.axther.aftrhrs.mixin.migrate;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * Paper saved a mob's spawn reason as {@code Paper.SpawnReason}; spawner / trial spawner mobs carried over from the Paper
 * world get the same {@code aftrhrs.spawn.<reason>} tag native spawns get ({@code SpawnReasonMixin}).
 */
@Mixin(Entity.class)
abstract class PaperSpawnReasonMixin {
    @Inject(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V", at = @At("TAIL"))
    private void aftrhrs$paperSpawnReason(ValueInput input, CallbackInfo ci) {
        input.getString("Paper.SpawnReason").ifPresent(r -> {
            String reason = r.toLowerCase(Locale.ROOT);
            if (reason.equals("spawner") || reason.equals("trial_spawner")) ((Entity) (Object) this).addTag("aftrhrs.spawn." + reason);
        });
    }
}
