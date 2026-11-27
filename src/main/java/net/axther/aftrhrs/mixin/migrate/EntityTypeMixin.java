package net.axther.aftrhrs.mixin.migrate;

import net.axther.aftrhrs.migrate.Legacy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** A MythicMobs mob saved by Paper (vanilla base type + BukkitValues mythicmobs:type) loads as its native type. */
@Mixin(EntityType.class)
public abstract class EntityTypeMixin {
    @Inject(method = "by(Lnet/minecraft/world/level/storage/ValueInput;)Ljava/util/Optional;", at = @At("RETURN"), cancellable = true)
    private static void aftrhrs$mythicType(ValueInput input, CallbackInfoReturnable<Optional<EntityType<?>>> cir) {
        if (!Legacy.ready()) return;
        Optional<CompoundTag> bv = input.read("BukkitValues", CompoundTag.CODEC);
        if (bv.isEmpty()) return;
        String mythic = bv.get().getString("mythicmobs:type").orElse(null);
        if (mythic == null) return;
        Optional<EntityType<?>> t = Legacy.mythicType(mythic);
        Legacy.count(t.isPresent() ? "MythicMobs mob loaded as its native type" : "MythicMobs type UNMAPPED (loads as its vanilla base)", mythic);
        t.ifPresent(type -> cir.setReturnValue(Optional.of(type)));
    }
}
