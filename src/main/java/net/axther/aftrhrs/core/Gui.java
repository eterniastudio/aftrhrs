package net.axther.aftrhrs.core;

import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chest menus drawn by the server (Feather's {@code Bukkit.createInventory(holder, 54, title)} GUIs: the player
 * picker, /tpa heads, graves, leaderboards...). A {@code Gui} is one chest of 1-6 rows that any number of players can
 * have open, like a Bukkit inventory; its slots are <b>locked</b> by default: every click on them is refused (no
 * item ever moves, whatever the click: shift, number keys, drag, double-click, middle-click) and the slot's
 * {@link Handler} runs instead, on the server thread right after the click packet. {@link #unlock} turns slots into
 * ordinary chest slots (trade windows, editors); {@link #onChange} hears about them.
 *
 * <pre>{@code
 * Gui gui = Gui.create(6, text.msg("gui.picker.title"));
 * gui.set(Gui.NAV_CLOSE, Icons.icon(Items.BARRIER, "gui.nav.close", null), c -> c.player().closeContainer());
 * gui.onClose(p -> ...);
 * gui.open(player);
 * }</pre>
 *
 * The player's own inventory works normally (shift-clicks only reach unlocked slots) unless {@link #onBottomClick}
 * takes those clicks over. {@link #attach} keeps per-view state (the old {@code InventoryHolder} subclasses), and
 * {@link #of(ServerPlayer)} finds the Gui a player has open.
 */
public final class Gui {
    /** Bukkit's {@code ClickType}, from the vanilla click packet. */
    public enum ClickKind { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, MIDDLE, NUMBER_KEY, SWAP_OFFHAND, DROP, CONTROL_DROP, DOUBLE_CLICK, DRAG, OUTSIDE }

    /**
     * One click. {@code slot} is the raw slot (0.. size-1 the chest, then the player's 27 + 9 hotbar slots, -999
     * outside the window); {@code hotbar} is the number key (0-8) for {@link ClickKind#NUMBER_KEY}, else -1.
     */
    public record Click(ServerPlayer player, Gui gui, int slot, ClickKind kind, int hotbar, ItemStack cursor) {
        public boolean top() {
            return slot >= 0 && slot < gui.size();
        }

        public boolean isShiftClick() {
            return kind == ClickKind.SHIFT_LEFT || kind == ClickKind.SHIFT_RIGHT;
        }

        public boolean isRightClick() {
            return kind == ClickKind.RIGHT || kind == ClickKind.SHIFT_RIGHT;
        }

        public boolean isLeftClick() {
            return kind == ClickKind.LEFT || kind == ClickKind.SHIFT_LEFT;
        }
    }

    @FunctionalInterface
    public interface Handler {
        void click(Click click);
    }

    /**
     * The bottom row every 6-row menu shares ({@link #navBar}): Previous, Back, Close and Next always sit here; slots
     * 47, 48 and 50-52 are the menu's own.
     */
    public static final int NAV_PREV = 45, NAV_BACK = 46, NAV_CLOSE = 49, NAV_NEXT = 53;

    /**
     * Lays out the shared bottom row of a 6-row menu: black panes across 45-53, Previous and Next arrows when there is
     * such a page, the caller's Back icon (null: none; the caller names where it goes) and Close. Every button clicks.
     * Draw the menu's own bottom-row buttons after this.
     */
    public static void navBar(Gui g, Text text, boolean hasPrev, Handler prev, boolean hasNext, Handler next, ItemStack backIcon, Handler back) {
        g.fill(45, 53, Icons.icon(Items.STAINED_GLASS_PANE.black()).flags().hideTooltip().build());
        if (hasPrev) g.set(NAV_PREV, Icons.icon(Items.ARROW, "gui.nav.previous", null), c -> {
            text.sound(c.player(), "click");
            prev.click(c);
        });
        if (hasNext) g.set(NAV_NEXT, Icons.icon(Items.ARROW, "gui.nav.next", null), c -> {
            text.sound(c.player(), "click");
            next.click(c);
        });
        if (backIcon != null && back != null) g.set(NAV_BACK, backIcon, c -> {
            text.sound(c.player(), "click");
            back.click(c);
        });
        g.set(NAV_CLOSE, Icons.icon(Items.BARRIER, "gui.nav.close", null), c -> {
            text.sound(c.player(), "click");
            c.player().closeContainer();
        });
    }

    private final int rows;
    private Component title;
    private final SimpleContainer container;
    private final Map<Integer, Handler> handlers = new HashMap<>();
    private final BitSet unlocked = new BitSet();
    private Handler bottom;
    private Handler fallback;
    private Consumer<ServerPlayer> onClose;
    private Runnable onChange;
    private Consumer<Gui> drawer;
    private Object attachment;
    private final List<GuiMenu> menus = new ArrayList<>();
    private boolean changeQueued;

    private Gui(int rows, Component title) {
        this.rows = Math.max(1, Math.min(6, rows));
        this.title = title == null ? Component.empty() : title;
        this.container = new SimpleContainer(this.rows * 9);
    }

    /** A chest of {@code rows} rows (1-6) with this title. */
    public static Gui create(int rows, Component title) {
        return new Gui(rows, title);
    }

    /** The Gui this player has open, or null. */
    public static Gui of(ServerPlayer player) {
        return player != null && player.containerMenu instanceof GuiMenu m ? m.gui : null;
    }

    public int size() {
        return rows * 9;
    }

    public int rows() {
        return rows;
    }

    public Component title() {
        return title;
    }

    /** A new title, used the next time the Gui is opened. */
    public Gui title(Component title) {
        this.title = title == null ? Component.empty() : title;
        return this;
    }

    /** The backing container (unlocked slots' contents live here). */
    public Container container() {
        return container;
    }

    // ------------------------------------------------------------------ contents

    /** A locked item with no action. */
    public Gui set(int slot, ItemStack item) {
        return set(slot, item, (Handler) null);
    }

    /** A locked item that runs {@code action} when clicked. */
    public Gui set(int slot, ItemStack item, Runnable action) {
        return set(slot, item, action == null ? null : c -> action.run());
    }

    /** A locked item with a click handler (null: no action). */
    public Gui set(int slot, ItemStack item, Handler handler) {
        if (slot < 0 || slot >= size()) return this;
        container.setItem(slot, item == null ? ItemStack.EMPTY : item);
        if (handler == null) handlers.remove(slot);
        else handlers.put(slot, handler);
        return this;
    }

    /** Puts {@code item} (copied) in every slot from {@code from} to {@code to} inclusive, without actions. */
    public Gui fill(int from, int to, ItemStack item) {
        for (int s = Math.max(0, from); s <= Math.min(size() - 1, to); s++) set(s, item.copy());
        return this;
    }

    public ItemStack get(int slot) {
        return slot < 0 || slot >= size() ? ItemStack.EMPTY : container.getItem(slot);
    }

    /** Removes every item and handler (unlocked slots stay unlocked). */
    public Gui clear() {
        handlers.clear();
        for (int s = 0; s < size(); s++) container.setItem(s, ItemStack.EMPTY);
        return this;
    }

    /** These chest slots behave like an ordinary chest: players put items in and take them out. */
    public Gui unlock(int... slots) {
        for (int s : slots) if (s >= 0 && s < size()) unlocked.set(s);
        return this;
    }

    public Gui lock(int... slots) {
        for (int s : slots) unlocked.clear(s);
        return this;
    }

    public boolean isUnlocked(int slot) {
        return unlocked.get(slot);
    }

    /** Takes over every click in the player's own inventory (and outside the window): nothing moves there either. */
    public Gui onBottomClick(Handler handler) {
        this.bottom = handler;
        return this;
    }

    /** Runs for clicks on locked chest slots that have no handler of their own. */
    public Gui onOtherClick(Handler handler) {
        this.fallback = handler;
        return this;
    }

    /** Runs when a viewer closes the chest (also when another menu replaces it, or they leave). */
    public Gui onClose(Consumer<ServerPlayer> onClose) {
        this.onClose = onClose;
        return this;
    }

    /** Runs (next tick, at most once per tick of changes) when an unlocked slot's contents changed. */
    public Gui onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    /** The function that draws the contents; run on {@link #open} and {@link #refresh}. */
    public Gui drawer(Consumer<Gui> drawer) {
        this.drawer = drawer;
        return this;
    }

    /** Redraws with the {@link #drawer}; open views update within the tick. */
    public void refresh() {
        if (drawer != null) drawer.accept(this);
        for (GuiMenu m : List.copyOf(menus)) m.broadcastChanges();
    }

    /** Per-view state for code that finds this Gui again (Bukkit's custom InventoryHolder). */
    public Gui attach(Object attachment) {
        this.attachment = attachment;
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T attachment() {
        return (T) attachment;
    }

    /** A player changed an unlocked slot: tell {@link #onChange} once, after the click. */
    private void playerChanged() {
        if (onChange == null || changeQueued) return;
        changeQueued = true;
        // later(1), not sync: on the server thread sync runs inline, i.e. inside vanilla's quickMoveStack, and a
        // handler that hands an item back (bounty editor) had it wiped by the rest of the shift-click.
        Tasks.later(1, () -> {
            changeQueued = false;
            if (onChange == null) return;
            try {
                onChange.run();
            } catch (RuntimeException e) {
                Feather.get().getLogger().error("Menu change handler failed", e);
            }
        });
    }

    // ------------------------------------------------------------------ viewers

    /** Opens the chest for a player (closing whatever they had open). */
    public void open(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) return;
        if (drawer != null && menus.isEmpty()) drawer.accept(this);
        MenuProvider provider = new SimpleMenuProvider((id, inv, p) -> new GuiMenu(this, id, inv), Adv.vanilla(title));
        player.openMenu(provider);
    }

    /** Everyone with this chest open. */
    public List<ServerPlayer> viewers() {
        List<ServerPlayer> out = new ArrayList<>();
        for (GuiMenu m : menus) if (m.viewer instanceof ServerPlayer sp) out.add(sp);
        return out;
    }

    /** Closes the chest for everyone. */
    public void close() {
        for (ServerPlayer p : viewers()) if (p.containerMenu instanceof GuiMenu m && m.gui == this) p.closeContainer();
    }

    private static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    private static ClickKind kind(ContainerInput input, int button, int slot) {
        if (slot == -999) return ClickKind.OUTSIDE;
        return switch (input) {
            case PICKUP -> button == 1 ? ClickKind.RIGHT : ClickKind.LEFT;
            case QUICK_MOVE -> button == 1 ? ClickKind.SHIFT_RIGHT : ClickKind.SHIFT_LEFT;
            case SWAP -> button == 40 ? ClickKind.SWAP_OFFHAND : ClickKind.NUMBER_KEY;
            case CLONE -> ClickKind.MIDDLE;
            case THROW -> button == 1 ? ClickKind.CONTROL_DROP : ClickKind.DROP;
            case PICKUP_ALL -> ClickKind.DOUBLE_CLICK;
            case QUICK_CRAFT -> ClickKind.DRAG;
        };
    }

    /** The server-side chest menu behind a Gui (one per viewer). */
    static final class GuiMenu extends AbstractContainerMenu {
        final Gui gui;
        final Player viewer;
        private boolean closed;

        GuiMenu(Gui gui, int id, Inventory inv) {
            super(type(gui.rows), id);
            this.gui = gui;
            this.viewer = inv.player;
            for (int y = 0; y < gui.rows; y++) {
                for (int x = 0; x < 9; x++) addSlot(new GuiSlot(gui, y * 9 + x, 8 + x * 18, 18 + y * 18));
            }
            addStandardInventorySlots(inv, 8, 18 + gui.rows * 18 + 13);
            gui.menus.add(this);
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (!(player instanceof ServerPlayer sp)) return;
            int size = gui.size();
            boolean top = slot >= 0 && slot < size;
            if (top && !gui.unlocked.get(slot)) {
                // A locked slot: nothing moves; the client is corrected by the caller's broadcastChanges.
                Handler h = gui.handlers.getOrDefault(slot, gui.fallback);
                if (h != null && input != ContainerInput.QUICK_CRAFT) dispatch(h, sp, slot, button, input);
                return;
            }
            if (!top && gui.bottom != null) {
                if (input != ContainerInput.QUICK_CRAFT) dispatch(gui.bottom, sp, slot, button, input);
                return;
            }
            // Gathering by double-click must not pull from locked slots (GuiSlot.mayPickup refuses them).
            super.clicked(slot, button, input, player);
            if (top) {
                Handler h = gui.handlers.get(slot);
                if (h != null) dispatch(h, sp, slot, button, input);
            }
        }

        private void dispatch(Handler h, ServerPlayer sp, int slot, int button, ContainerInput input) {
            Click c = new Click(sp, gui, slot, kind(input, button, slot), input == ContainerInput.SWAP && button < 9 ? button : -1, getCarried().copy());
            // After the click packet is fully handled, so opening another menu from a handler is safe.
            Tasks.sync(() -> {
                if (sp.hasDisconnected()) return;
                try {
                    h.click(c);
                } catch (RuntimeException e) {
                    Feather.get().getLogger().error("Menu click failed", e);
                }
            });
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            Slot slot = slots.get(index);
            if (!slot.hasItem()) return ItemStack.EMPTY;
            int size = gui.size();
            ItemStack stack = slot.getItem();
            ItemStack before = stack.copy();
            if (index < size) {
                if (!gui.unlocked.get(index)) return ItemStack.EMPTY;
                if (!moveItemStackTo(stack, size, slots.size(), true)) return ItemStack.EMPTY;
            } else {
                // Only into unlocked slots: vanilla's merge pass ignores mayPlace, so move one slot range at a time.
                boolean moved = false;
                for (int i = gui.unlocked.nextSetBit(0); i >= 0 && i < size && !stack.isEmpty(); i = gui.unlocked.nextSetBit(i + 1)) {
                    if (moveItemStackTo(stack, i, i + 1, false)) moved = true;
                }
                if (!moved) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
            return before;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
            return !(target instanceof GuiSlot gs) || gui.unlocked.get(gs.getContainerSlot());
        }

        @Override
        public boolean canDragTo(Slot slot) {
            return !(slot instanceof GuiSlot gs) || gui.unlocked.get(gs.getContainerSlot());
        }

        @Override
        public boolean stillValid(Player player) {
            return !closed;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (closed) return;
            closed = true;
            gui.menus.remove(this);
            if (gui.onClose != null && player instanceof ServerPlayer sp) {
                try {
                    gui.onClose.accept(sp);
                } catch (RuntimeException e) {
                    Feather.get().getLogger().error("Menu close handler failed", e);
                }
            }
        }
    }

    private static final class GuiSlot extends Slot {
        private final Gui gui;

        GuiSlot(Gui gui, int index, int x, int y) {
            super(gui.container, index, x, y);
            this.gui = gui;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return gui.unlocked.get(getContainerSlot());
        }

        @Override
        public boolean mayPickup(Player player) {
            return gui.unlocked.get(getContainerSlot());
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (gui.unlocked.get(getContainerSlot())) gui.playerChanged();
        }
    }
}
