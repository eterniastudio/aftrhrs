package net.axther.aftrhrs.mixin.coast;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** {@link FeatherEvents#CRAFT_RESULT}: the result the crafting grid shows. */
@Mixin(CraftingMenu.class)
abstract class CraftingMenuMixin {
    @ModifyVariable(method = "slotChangedCraftingGrid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/ResultContainer;setItem(ILnet/minecraft/world/item/ItemStack;)V"), ordinal = 0)
    private static ItemStack aftrhrs$result(ItemStack result, AbstractContainerMenu menu, ServerLevel level, Player player,
                                            CraftingContainer container, ResultContainer resultSlots,
                                            @Nullable RecipeHolder<CraftingRecipe> hint) {
        if (!(player instanceof ServerPlayer p)) return result;
        ItemStack out = FeatherEvents.CRAFT_RESULT.invoker().result(p, container.asCraftInput(), result);
        return out == null ? ItemStack.EMPTY : out;
    }
}
