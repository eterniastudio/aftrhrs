package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.furniture.power.FanShield;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link FeatherEvents#ITEM_PICKUP} (a side-effect-free veto, asked every tick right before {@code Inventory.add}) and
 * {@link FeatherEvents#ITEM_PICKED_UP} (fired only when the add really took something); items in an encased fan's
 * stream don't burn ({@link FanShield}).
 */
@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
    @WrapOperation(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean aftrhrs$pickup(Inventory inventory, ItemStack stack, Operation<Boolean> original, Player player) {
        if (!(player instanceof ServerPlayer sp)) return original.call(inventory, stack);
        ItemEntity self = (ItemEntity) (Object) this;
        if (!FeatherEvents.ITEM_PICKUP.invoker().allow(sp, self)) return false;
        int before = stack.getCount();
        boolean took = original.call(inventory, stack);
        if (took) FeatherEvents.ITEM_PICKED_UP.invoker().picked(sp, self, Math.max(0, before - stack.getCount()));
        return took;
    }

    @Inject(method = "fireImmune", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$fanFireImmune(CallbackInfoReturnable<Boolean> cir) {
        if (FanShield.shielded((ItemEntity) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$fanNoBurn(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (source.is(DamageTypeTags.IS_FIRE) && FanShield.shielded((ItemEntity) (Object) this)) cir.setReturnValue(false);
    }
}
