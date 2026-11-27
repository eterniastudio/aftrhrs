package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a player ride another player (the carry module), as Paper allowed: vanilla refuses vehicles whose type isn't
 * saved, and players aren't. Only player-on-player and a mob being lifted into a player's arms; every other rule of
 * {@code startRiding} stays.
 */
@Mixin(Entity.class)
abstract class EntityRidePlayerMixin {
    @WrapOperation(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;canSerialize()Z"))
    private boolean aftrhrs$playerVehicle(EntityType<?> type, Operation<Boolean> original, Entity vehicle) {
        if ((Object) this instanceof ServerPlayer && vehicle instanceof ServerPlayer) return true;
        // A mob the carry module is lifting into a player's arms.
        if (vehicle instanceof ServerPlayer && net.axther.aftrhrs.feature.travel.CarryModule.lifting((Entity) (Object) this)) return true;
        return original.call(type);
    }
}
