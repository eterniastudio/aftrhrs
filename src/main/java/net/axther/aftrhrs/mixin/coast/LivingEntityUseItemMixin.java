package net.axther.aftrhrs.mixin.coast;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#ITEM_CONSUMED}: a player finished using (eating, drinking...) an item. */
@Mixin(LivingEntity.class)
abstract class LivingEntityUseItemMixin {
    @WrapOperation(method = "completeUsingItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack aftrhrs$consumed(ItemStack stack, Level level, LivingEntity entity, Operation<ItemStack> original) {
        if (!(entity instanceof ServerPlayer p)) return original.call(stack, level, entity);
        ItemStack before = stack.copy();
        InteractionHand hand = p.getUsedItemHand();
        ItemStack result = original.call(stack, level, entity);
        FeatherEvents.ITEM_CONSUMED.invoker().consumed(p, before, hand);
        return result;
    }
}
