package net.axther.aftrhrs.mixin.relics;

import net.axther.aftrhrs.feature.relics.BackpackWear;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The back slot ({@link BackpackWear#MENU_SLOT}, after vanilla's last slot) in every player's inventory menu, on both
 * sides; shift-clicking a backpack in the inventory puts it on the back when the back is free (out of the back slot,
 * vanilla's fallback moves it into the inventory).
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuBackSlotMixin extends AbstractCraftingMenu {
    protected InventoryMenuBackSlotMixin(MenuType<?> menuType, int containerId, int width, int height) {
        super(menuType, containerId, width, height);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void aftrhrs$backSlot(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        if (this.slots.size() == BackpackWear.MENU_SLOT) this.addSlot(BackpackWear.slot(owner));
        // the outfit (cosmetic armor and the hat) right after: menu slots 47..51
        if (this.slots.size() == net.axther.aftrhrs.feature.cosmetics.Cosmetics.MENU_FIRST) {
            for (Slot s : net.axther.aftrhrs.feature.cosmetics.Cosmetics.slots(owner)) this.addSlot(s);
        }
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$toBack(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        int back = BackpackWear.MENU_SLOT;
        if (index < 9 || index >= back || this.slots.size() <= back) return;
        Slot from = this.slots.get(index);
        Slot to = this.slots.get(back);
        if (!from.hasItem() || to.hasItem() || !to.mayPlace(from.getItem())) return;
        ItemStack stack = from.getItem();
        ItemStack clicked = stack.copy();
        if (!this.moveItemStackTo(stack, back, back + 1, false)) return;
        if (stack.isEmpty()) from.setByPlayer(ItemStack.EMPTY, clicked);
        else from.setChanged();
        from.onTake(player, stack);
        cir.setReturnValue(clicked);
    }
}
