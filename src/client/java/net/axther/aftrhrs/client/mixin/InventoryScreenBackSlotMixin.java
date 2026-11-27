package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.feature.relics.BackpackWear;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The back slot's frame in the survival inventory (the inventory texture has no slot there): above the offhand. Also the
 * outfit's hat slot and cosmetic armor panel ({@code CosmeticsClient}).
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenBackSlotMixin extends AbstractRecipeBookScreen<InventoryMenu> {
    @Unique
    private static final Identifier AFTRHRS$SLOT = Identifier.withDefaultNamespace("container/slot");

    protected InventoryScreenBackSlotMixin(InventoryMenu menu, RecipeBookComponent<?> book, Inventory inventory, Component title) {
        super(menu, book, inventory, title);
    }

    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void aftrhrs$backSlotFrame(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (this.menu.slots.size() <= BackpackWear.MENU_SLOT) return;
        Slot back = this.menu.slots.get(BackpackWear.MENU_SLOT);
        if (!back.isActive()) return;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, AFTRHRS$SLOT, this.leftPos + back.x - 1, this.topPos + back.y - 1, 18, 18);
    }

    /** The outfit: the hat slot's frame, and the cosmetic armor panel left of the inventory (aside while the recipe book is open). */
    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void aftrhrs$outfitPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        boolean book = ((AbstractRecipeBookScreenAccessor) this).aftrhrs$book().isVisible();
        net.axther.aftrhrs.client.cosmetics.CosmeticsClient.drawBackground(graphics, this.menu, this.leftPos, this.topPos, mouseX, mouseY, book);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void aftrhrs$outfitTooltips(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        net.axther.aftrhrs.client.cosmetics.CosmeticsClient.drawTooltips(graphics, this.font, this.menu, this.leftPos, this.topPos, mouseX, mouseY, this.menu.getCarried());
    }
}
