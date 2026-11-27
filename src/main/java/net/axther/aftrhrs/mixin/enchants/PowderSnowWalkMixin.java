package net.axther.aftrhrs.mixin.enchants;

import net.axther.aftrhrs.feature.enchants.Enchants;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Driftwalker (enchantment) boots walk on powder snow like leather boots. Runs on both sides (the client predicts it). */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowWalkMixin {
    @Inject(method = "canEntityWalkOnPowderSnow", at = @At("RETURN"), cancellable = true)
    private static void aftrhrs$driftwalker(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && entity instanceof LivingEntity le && Enchants.worn(le, EquipmentSlot.FEET, Enchants.DRIFTWALKER) > 0) {
            cir.setReturnValue(true);
        }
    }
}
