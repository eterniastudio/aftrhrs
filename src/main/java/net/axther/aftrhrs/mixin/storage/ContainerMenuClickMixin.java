package net.axther.aftrhrs.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.axther.aftrhrs.feature.storage.StorageLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Storage "who changed what": a player clicking in a container's own window (a chest opened directly) is who
 * changes it, so {@link StorageLog} writes their name instead of "Automation".
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuClickMixin {
    @WrapMethod(method = "clicked")
    private void aftrhrs$storageActor(int slotId, int button, ContainerInput input, Player player, Operation<Void> original) {
        if (!(player instanceof ServerPlayer sp)) {
            original.call(slotId, button, input, player);
            return;
        }
        Object prev = StorageLog.pushPlayer(sp);
        try {
            original.call(slotId, button, input, player);
        } finally {
            StorageLog.pop(prev);
        }
    }
}
