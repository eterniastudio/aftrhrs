package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fires {@link FeatherEvents#MOB_CONVERT} before any mob conversion; a refusal returns null (no conversion), as for a removed mob. */
@Mixin(Mob.class)
public abstract class MobConvertMixin {
    @Inject(method = "convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;"
            + "Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)"
            + "Lnet/minecraft/world/entity/Mob;", at = @At("HEAD"), cancellable = true)
    private <T extends Mob> void aftrhrs$convert(EntityType<T> type, ConversionParams params, EntitySpawnReason reason,
                                                 ConversionParams.AfterConversion<T> after, CallbackInfoReturnable<T> cir) {
        Mob self = (Mob) (Object) this;
        if (!self.level().isClientSide() && !FeatherEvents.MOB_CONVERT.invoker().allow(self, type)) cir.setReturnValue(null);
    }
}
