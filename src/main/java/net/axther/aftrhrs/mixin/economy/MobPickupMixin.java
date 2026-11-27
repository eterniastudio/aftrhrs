package net.axther.aftrhrs.mixin.economy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fires {@link FeatherEvents#MOB_PICKUP} where a mob decides to pick up a dropped item (every mob type's override). */
@Mixin(Mob.class)
public abstract class MobPickupMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;wantsToPickUp(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean aftrhrs$mobPickup(Mob self, ServerLevel level, ItemStack stack, Operation<Boolean> original) {
        return original.call(self, level, stack) && FeatherEvents.MOB_PICKUP.invoker().allow(self, stack);
    }
}
