package net.axther.aftrhrs.mixin.migrate;

import net.axther.aftrhrs.core.Pdc;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Paper's BukkitValues (plugin data on the entity) for the migration converters. */
@Mixin(Entity.class)
public abstract class EntityLoadMixin {
    @Inject(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V", at = @At("TAIL"))
    private void aftrhrs$captureBukkitValues(ValueInput input, CallbackInfo ci) {
        input.read("BukkitValues", CompoundTag.CODEC).ifPresent(tag -> {
            if (!tag.isEmpty()) ((Entity) (Object) this).setAttached(Pdc.LEGACY, tag);
        });
    }
}
