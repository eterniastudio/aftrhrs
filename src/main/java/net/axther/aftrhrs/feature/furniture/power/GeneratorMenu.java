package net.axther.aftrhrs.feature.furniture.power;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.ToDoubleFunction;

/**
 * The coal generator's window (one chest row, like Paper's): the status item in slot 0, the fuel slot in the middle,
 * panes elsewhere. Only fuel goes in; the client sees a plain 9x1 chest.
 */
final class GeneratorMenu extends AbstractContainerMenu {
    static final int FUEL_SLOT = 4;
    static final int STATUS_SLOT = 0;

    final PowerBlockEntity generator;
    private final Container view;
    private final ToDoubleFunction<ItemStack> fuelTicks;

    /** Slot 4 is the generator's fuel; the others are display items no one can take. */
    static final class View extends SimpleContainer {
        private final PowerBlockEntity g;

        View(PowerBlockEntity g) {
            super(9);
            this.g = g;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot == FUEL_SLOT ? g.fuel : super.getItem(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (slot == FUEL_SLOT) {
                g.fuel = stack;
                g.setChanged();
            } else super.setItem(slot, stack);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            if (slot != FUEL_SLOT) return ItemStack.EMPTY;
            ItemStack out = g.fuel.split(count);
            g.setChanged();
            return out;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            if (slot != FUEL_SLOT) return ItemStack.EMPTY;
            ItemStack out = g.fuel;
            g.fuel = ItemStack.EMPTY;
            g.setChanged();
            return out;
        }

        @Override
        public boolean stillValid(Player player) {
            return !g.isRemoved() && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(g.getBlockPos())) < 64;
        }
    }

    GeneratorMenu(int id, Inventory inv, PowerBlockEntity generator, View view, ToDoubleFunction<ItemStack> fuelTicks) {
        super(MenuType.GENERIC_9x1, id);
        this.generator = generator;
        this.view = view;
        this.fuelTicks = fuelTicks;
        for (int i = 0; i < 9; i++) {
            final int slot = i;
            addSlot(new Slot(view, i, 8 + i * 18, 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return slot == FUEL_SLOT && fuelTicks.applyAsDouble(stack) > 0;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return slot == FUEL_SLOT;
                }
            });
        }
        addStandardInventorySlots(inv, 8, 50);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < 9) {
            if (index != FUEL_SLOT) return ItemStack.EMPTY;
            if (!moveItemStackTo(stack, 9, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (fuelTicks.applyAsDouble(stack) <= 0) return ItemStack.EMPTY;
            if (!moveItemStackTo(stack, FUEL_SLOT, FUEL_SLOT + 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return view.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        generator.setChanged();
    }
}
