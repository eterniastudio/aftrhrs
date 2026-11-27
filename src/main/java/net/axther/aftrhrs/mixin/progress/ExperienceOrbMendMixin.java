package net.axther.aftrhrs.mixin.progress;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#ITEM_MENDED}: how much durability Mending restored to which item. */
@Mixin(ExperienceOrb.class)
abstract class ExperienceOrbMendMixin {
    @WrapOperation(method = "repairPlayerItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;setDamageValue(I)V"))
    private void aftrhrs$mended(ItemStack stack, int damage, Operation<Void> original, @Local(argsOnly = true) ServerPlayer player) {
        int before = stack.getDamageValue();
        original.call(stack, damage);
        int repaired = before - stack.getDamageValue();
        if (repaired > 0) FeatherEvents.ITEM_MENDED.invoker().mended(player, stack, repaired);
    }
}
