package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link FeatherEvents#ENTITY_DAMAGE}: frames, paintings, knots, cushions, boats, minecarts and armor stands. */
@Mixin({ArmorStand.class, BlockAttachedEntity.class, Cushion.class, ItemFrame.class, VehicleEntity.class})
abstract class EntityDamageMixins {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$hurt(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        if (!FeatherEvents.ENTITY_DAMAGE.invoker().allow((Entity) (Object) this, level, source, damage)) cir.setReturnValue(false);
    }
}
