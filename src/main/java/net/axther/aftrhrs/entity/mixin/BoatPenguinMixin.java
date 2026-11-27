package net.axther.aftrhrs.entity.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.axther.aftrhrs.entity.PenguinBoats;
import net.axther.aftrhrs.entity.PenguinCreature;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Penguins and boats ({@link PenguinBoats}): pushing penguins and a pet penguin aboard add paddling thrust on water; a pet
 * penguin turns the boat into a sled on snow (friction, 1-block snowy steps); a penguin passenger faces forward in the
 * back seat (vanilla turns animals sideways). Runs wherever the boat is simulated (the steering player's client, or the
 * server for a boat nobody steers), always from the server-synced penguin state.
 */
@Mixin(AbstractBoat.class)
abstract class BoatPenguinMixin extends VehicleEntity {
    @Shadow
    private boolean inputUp;
    @Shadow
    private AbstractBoat.Status status;

    BoatPenguinMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Inject(method = "controlBoat", at = @At("TAIL"))
    private void aftrhrs$penguinThrust(CallbackInfo ci) {
        if (!inputUp || status != AbstractBoat.Status.IN_WATER || !isVehicle()) return;
        Vec3 extra = PenguinBoats.extraThrust((AbstractBoat) (Object) this);
        if (extra != Vec3.ZERO) setDeltaMovement(getDeltaMovement().add(extra));
    }

    @ModifyExpressionValue(method = "floatBoat", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;landFriction:F", opcode = Opcodes.GETFIELD, ordinal = 0))
    private float aftrhrs$penguinSled(float friction) {
        return PenguinBoats.sledFriction((AbstractBoat) (Object) this, friction);
    }

    @Override
    public float maxUpStep() {
        return PenguinBoats.stepHeight((AbstractBoat) (Object) this);
    }

    @Inject(method = "calculatePassengerBodyYRot", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$penguinFacesForward(Entity passenger, CallbackInfoReturnable<Float> cir) {
        if (passenger instanceof PenguinCreature) cir.setReturnValue(getYRot());
    }
}
