package net.axther.aftrhrs.feature.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;

import java.util.function.Predicate;

/**
 * A crafting table Feather opened without a table block (Paper's {@code openWorkbench(null, true)}): the Crafting
 * Terminal's grid, Quick Craft's Crafting Grid button. It is anchored at a real position (so the grid's items go back
 * to the player when it closes) and stays open while {@code valid} says so. JEI/REI "+" may fill it.
 */
final class WorkbenchMenu extends CraftingMenu {
    private final Predicate<Player> valid;

    private WorkbenchMenu(int id, Inventory inv, ContainerLevelAccess access, Predicate<Player> valid) {
        super(id, inv, access);
        this.valid = valid;
    }

    @Override
    public boolean stillValid(Player player) {
        return valid.test(player);
    }

    /** Opens one for the player; returns it, or null when it didn't open. */
    static WorkbenchMenu open(ServerPlayer p, ServerLevel level, BlockPos anchor, Predicate<Player> valid) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new WorkbenchMenu(id, inv, ContainerLevelAccess.create(level, anchor), valid),
                Component.translatable("container.crafting")));
        return p.containerMenu instanceof WorkbenchMenu m ? m : null;
    }
}
