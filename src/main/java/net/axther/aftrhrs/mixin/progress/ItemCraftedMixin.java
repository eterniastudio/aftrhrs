package net.axther.aftrhrs.mixin.progress;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link FeatherEvents#ITEM_CRAFTED}: {@code Item.onCraftedBy} runs on the real stack a player takes from a result slot
 * (and, for a crafting-grid shift-click, before the stack is moved into the inventory).
 */
@Mixin(Item.class)
abstract class ItemCraftedMixin {
    @Inject(method = "onCraftedBy(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
    private void aftrhrs$crafted(ItemStack stack, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp && !stack.isEmpty()) FeatherEvents.ITEM_CRAFTED.invoker().crafted(sp, stack);
    }
}
