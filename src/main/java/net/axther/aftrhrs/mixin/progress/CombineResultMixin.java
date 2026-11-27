package net.axther.aftrhrs.mixin.progress;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#COMBINE_RESULT} after an anvil or grindstone computed its result (server thread only). */
@Mixin({AnvilMenu.class, GrindstoneMenu.class})
abstract class CombineResultMixin {
    @Inject(method = "createResult", at = @At("TAIL"))
    private void aftrhrs$combined(CallbackInfo ci) {
        MinecraftServer server = Feather.serverOrNull();
        if (server == null || !server.isSameThread()) return;   // the client's copy of the menu (singleplayer)
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        ItemStack result = menu.getSlot(2).getItem();
        if (result.isEmpty()) return;
        FeatherEvents.COMBINE_RESULT.invoker().computed(menu, menu.getSlot(0).getItem(), menu.getSlot(1).getItem(), result);
    }
}
