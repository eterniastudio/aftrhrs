package net.axther.aftrhrs.mixin.progress;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link FeatherEvents#ITEM_CRAFTED} for a shift-clicked smithing (or anvil) result: vanilla only calls
 * {@code onCraftedBy} after the stack was already moved into the inventory, so fire on the result before it moves.
 */
@Mixin(ItemCombinerMenu.class)
abstract class ItemCombinerShiftMixin {
    @Inject(method = "quickMoveStack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/ItemCombinerMenu;moveItemStackTo(Lnet/minecraft/world/item/ItemStack;IIZ)Z", ordinal = 0))
    private void aftrhrs$shiftTakeResult(Player player, int slotIndex, CallbackInfoReturnable<ItemStack> cir) {
        if (!(player instanceof ServerPlayer sp)) return;
        ItemStack stack = ((AbstractContainerMenu) (Object) this).getSlot(slotIndex).getItem();
        if (!stack.isEmpty()) FeatherEvents.ITEM_CRAFTED.invoker().crafted(sp, stack);
    }
}
