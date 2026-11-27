package net.axther.aftrhrs.feature.storage;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * A storage chest window (the storage view or Quick Craft). The top 54 slots only ever hold icons: copies made for
 * showing, never real items. Every click on them is refused and done by hand against the live chests, right inside
 * the click (so the cursor is exact); the player's own inventory works normally except where a view takes a
 * shift-click over.
 */
abstract class View {
    static final int SIZE = 54;

    final StorageModule module;
    final UUID viewer;
    /** The terminal it was opened from, or null (/quickcraft: the player's inventory only). */
    final StorageBlock.Terminal.At terminal;
    final SimpleContainer inv = new SimpleContainer(SIZE);
    Component title = Component.empty();
    /** Set when the terminal is gone: clicks do nothing until the window closes. */
    boolean dead;
    /** Opened with a Crafting Terminal Remote: its tier (0: at the terminal) and link id. */
    int remote;
    String link;
    /** The storage blocks the last drawing showed (null: none yet, or no terminal): other windows' changes there redraw it. */
    StorageModule.Touched lastSeen;
    /** When the player last moved items or crafted here (see {@link StorageView#HOLD_MS}). */
    long lastAction;
    /** The last drawing kept the icons in their places (the hold): once the hold is over the window redraws. */
    boolean holdPending;
    /** The server tick of the last drawing, whatever asked for it: idle windows still redraw every full-refresh-ticks. */
    long lastFullRender;
    /** How many times the window was drawn (game tests). */
    int renders;

    View(StorageModule module, ServerPlayer viewer, StorageBlock.Terminal.At terminal) {
        this.module = module;
        this.viewer = viewer.getUUID();
        this.terminal = terminal;
    }

    abstract void render(ServerPlayer p);

    /** Every {@link #render} starts with this. */
    void drawing() {
        lastFullRender = net.axther.aftrhrs.core.Tasks.now();
        renders++;
    }

    /** A cheap fingerprint of the player's main inventory (kinds and counts): Quick Craft's list depends on it. */
    static int inventoryStamp(ServerPlayer p) {
        int h = 1;
        for (ItemStack it : p.getInventory().getNonEquipmentItems()) h = 31 * h + ItemStack.hashItemAndComponents(it) * 31 + it.getCount();
        return h;
    }

    /** A click on one of the window's own slots (0-53). Runs inside the click packet; {@code menu.getCarried()} is live. */
    abstract void click(ServerPlayer p, Menu menu, int slot, Gui.ClickKind kind);

    /** A shift-click on an item in the player's own inventory (nothing moved yet). */
    abstract void shiftBottom(ServerPlayer p, Menu menu, Slot slot);

    boolean on(StorageBlock.Terminal.At t) {
        return terminal != null && terminal.same(t);
    }

    Text text() {
        return module.text();
    }

    /** Sets a slot only when it changed (no flicker, no needless packets). */
    void put(int slot, ItemStack item) {
        ItemStack now = item == null ? ItemStack.EMPTY : item;
        if (!ItemStack.matches(inv.getItem(slot), now)) inv.setItem(slot, now);
    }

    ItemStack button(Item type, String key, TagResolver... r) {
        Icons.Icon icon = Icons.icon(type).flags().name(text().itemMsg("storage." + key + ".name", r));
        List<Component> lore = text().lines("storage." + key + ".lore", r);
        if (!(lore.size() == 1 && Adv.plain(lore.getFirst()).isEmpty())) icon.lore(lore);
        return icon.build();
    }

    static ItemStack filler() {
        return Icons.icon(Items.STAINED_GLASS_PANE.gray()).hideTooltip().build();
    }

    void open(ServerPlayer p) {
        p.openMenu(new SimpleMenuProvider((id, pinv, pl) -> new Menu(this, id, pinv), Adv.vanilla(title)));
    }

    /** True when this player has this window open right now. */
    boolean openFor(ServerPlayer p) {
        return p != null && p.containerMenu instanceof Menu m && m.view == this;
    }

    private static Gui.ClickKind kind(ContainerInput input, int button) {
        return switch (input) {
            case PICKUP -> button == 1 ? Gui.ClickKind.RIGHT : Gui.ClickKind.LEFT;
            case QUICK_MOVE -> button == 1 ? Gui.ClickKind.SHIFT_RIGHT : Gui.ClickKind.SHIFT_LEFT;
            case SWAP -> button == 40 ? Gui.ClickKind.SWAP_OFFHAND : Gui.ClickKind.NUMBER_KEY;
            case CLONE -> Gui.ClickKind.MIDDLE;
            case THROW -> button == 1 ? Gui.ClickKind.CONTROL_DROP : Gui.ClickKind.DROP;
            case PICKUP_ALL -> Gui.ClickKind.DOUBLE_CLICK;
            case QUICK_CRAFT -> Gui.ClickKind.DRAG;
        };
    }

    /** The server side of a storage window: a 6-row chest whose top slots are icons. */
    static final class Menu extends AbstractContainerMenu {
        final View view;

        Menu(View view, int id, Inventory pinv) {
            super(MenuType.GENERIC_9x6, id);
            this.view = view;
            for (int y = 0; y < 6; y++) {
                for (int x = 0; x < 9; x++) addSlot(new IconSlot(view.inv, y * 9 + x, 8 + x * 18, 18 + y * 18));
            }
            addStandardInventorySlots(pinv, 8, 18 + 6 * 18 + 13);
        }

        /** Network clicks this tick (each one scans the network and redraws): a flood of click packets stalls the server. */
        private long clickTick = -1;
        private int clicksThisTick;
        static final int MAX_CLICKS_PER_TICK = 8;

        /** False (and the window resynced) when this click must not reach the network: too many this tick, or out of reach. */
        private boolean networkClickOk(ServerPlayer sp) {
            long now = net.axther.aftrhrs.core.Tasks.now();
            if (now != clickTick) {
                clickTick = now;
                clicksThisTick = 0;
            }
            if (++clicksThisTick > MAX_CLICKS_PER_TICK) {
                sendAllDataToRemote();
                return false;
            }
            // Walked or teleported away since the last 10-tick check: no more taking or putting.
            if (view.terminal != null && view.module.problem(sp, view.terminal, view.remote, view.link) != null) {
                sendAllDataToRemote();
                return false;
            }
            return true;
        }

        @Override
        public void clicked(int slotId, int button, ContainerInput input, Player player) {
            if (!(player instanceof ServerPlayer sp)) return;
            // Gathering by double-click could reach the icons: Feather never allowed it in these windows.
            if (input == ContainerInput.PICKUP_ALL) return;
            if (slotId < 0 || slotId >= slots.size() || input == ContainerInput.QUICK_CRAFT) {
                super.clicked(slotId, button, input, player); // outside the window (drops the cursor) or dragging
                return;
            }
            if (slotId < SIZE) {
                if (view.dead || !networkClickOk(sp)) return;
                try {
                    view.click(sp, this, slotId, kind(input, button));
                } catch (RuntimeException e) {
                    view.module.plugin().getLogger().warn("Storage window click failed", e);
                }
                return;
            }
            if (input == ContainerInput.QUICK_MOVE) {
                if (view.dead || !networkClickOk(sp)) return;
                try {
                    view.shiftBottom(sp, this, slots.get(slotId));
                } catch (RuntimeException e) {
                    view.module.plugin().getLogger().warn("Storage window click failed", e);
                }
                return;
            }
            super.clicked(slotId, button, input, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
            return !(target instanceof IconSlot);
        }

        @Override
        public boolean canDragTo(Slot slot) {
            return !(slot instanceof IconSlot);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void removed(Player player) {
            // The cursor goes back into the inventory, else into the network, else to the player's feet. Never lost.
            ItemStack cursor = getCarried();
            if (!cursor.isEmpty() && player instanceof ServerPlayer sp) {
                setCarried(ItemStack.EMPTY);
                view.module.returnCursor(sp, view, cursor);
            }
            super.removed(player);
            if (player instanceof ServerPlayer sp) view.module.closed(sp, view);
        }
    }

    private static final class IconSlot extends Slot {
        IconSlot(SimpleContainer c, int index, int x, int y) {
            super(c, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
