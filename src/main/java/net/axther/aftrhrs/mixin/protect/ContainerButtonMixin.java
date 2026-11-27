package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#MENU_BUTTON}: a click on a menu button (lectern Take Book ...). */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ContainerButtonMixin {
    @WrapOperation(method = "handleContainerButtonClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clickMenuButton(Lnet/minecraft/world/entity/player/Player;I)Z"))
    private boolean aftrhrs$button(AbstractContainerMenu menu, Player player, int id, Operation<Boolean> original) {
        if (player instanceof ServerPlayer sp && !FeatherEvents.MENU_BUTTON.invoker().allow(sp, menu, id)) {
            menu.sendAllDataToRemote();
            return false;
        }
        return original.call(menu, player, id);
    }
}
