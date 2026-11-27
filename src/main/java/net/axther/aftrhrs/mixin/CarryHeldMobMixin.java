package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.feature.travel.CarryPose;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mob carried by a player sits in their arms, facing their way ({@link CarryPose}), on both sides. */
@Mixin(Entity.class)
abstract class CarryHeldMobMixin {
    @Inject(method = "positionRider(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$inArms(Entity passenger, Entity.MoveFunction move, CallbackInfo ci) {
        if (!((Object) this instanceof Player carrier) || !CarryPose.inArms(carrier, passenger)) return;
        Vec3 at = CarryPose.held(carrier, passenger);
        move.accept(passenger, at.x, at.y, at.z);
        CarryPose.face(carrier, passenger);
        ci.cancel();
    }
}
