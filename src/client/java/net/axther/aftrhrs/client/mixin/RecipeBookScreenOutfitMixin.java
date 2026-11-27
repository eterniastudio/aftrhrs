package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.cosmetics.CosmeticsClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The cosmetic armor panel left of the survival inventory: its show/hide switches take clicks, and a click on the panel
 * is not a click outside the inventory (which would throw the item on the cursor).
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookScreenOutfitMixin<T extends RecipeBookMenu> extends AbstractContainerScreen<T> {
    protected RecipeBookScreenOutfitMixin(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$outfitSwitch(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen && this.menu instanceof InventoryMenu inv && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && CosmeticsClient.click(inv, this.leftPos, this.topPos, event.x(), event.y())) cir.setReturnValue(true);
    }

    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$panelIsInside(double mx, double my, int left, int top, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen && this.menu instanceof InventoryMenu inv && CosmeticsClient.onPanel(inv, left, top, mx, my)) {
            cir.setReturnValue(false);
        }
    }
}
