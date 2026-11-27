package net.axther.aftrhrs.feature.storage;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick Craft as a chest window (players without the Companion screen, and {@code /quickcraft}): the recipes the
 * player can make right now (5 rows of 8), or with Show: All Recipes every recipe (what can be made first, then the
 * rest with what is missing). Down the right side Crafting Grid, Craft Amount (×1/×4/×16/×64), where ingredients
 * come from first, Refresh, Page and Back; along the bottom Search, Clear Search, Show and Auto-Craft (missing
 * ingredients that can be crafted are crafted first). Left-click crafts the chosen amount, right-click as many as
 * fit; with shift (from a terminal) the results go into the storage instead of the inventory.
 */
final class CraftView extends View {
    static final int PER_PAGE = 40;
    static final int GRID = 8, AMOUNT = 17, SOURCE = 26, REFRESH = 35, PAGE = 44, BACK = 53;
    static final int SEARCH = 45, CLEAR = 46, SHOW = 47, AUTO = 48;
    static final int[] AMOUNTS = {1, 4, 16, 64};

    int page;
    int amount;
    boolean networkFirst;
    /** {@link View#inventoryStamp} and Quick Craft's recipe version at the last drawing: either moving redraws. */
    int invStamp;
    long recipeStamp = -1;
    /** The storage view to go back to (from a terminal), or null. */
    final StorageView from;
    final CraftList craft;
    private final List<QuickCraft.CachedRecipe> shown = new ArrayList<>();
    private int pages = 1;

    CraftView(StorageModule module, ServerPlayer viewer, StorageBlock.Terminal.At terminal, StorageView from) {
        super(module, viewer, terminal);
        this.from = from;
        this.amount = module.craftAmountOf(viewer);
        this.networkFirst = module.networkFirstOf(viewer);
        this.craft = new CraftList(module, viewer);
        this.title = text().msg(terminal == null ? "storage.craft.title" : "storage.craft.title-network");
    }

    static int slotOf(int index) {
        return (index / 8) * 9 + index % 8;
    }

    static int indexOf(int slot) {
        int row = slot / 9, col = slot % 9;
        return row < 5 && col < 8 ? row * 8 + col : -1;
    }

    @Override
    void render(ServerPlayer p) {
        drawing();
        invStamp = inventoryStamp(p);
        recipeStamp = module.craft().version();
        Network net = terminal == null ? null : module.network(p, terminal, false);
        lastSeen = StorageModule.touched(terminal, net);
        QuickCraft.Pools pools = QuickCraft.pools(p, net);
        craft.update(p, pools);
        List<QuickCraft.CachedRecipe> list = craft.list();
        pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        holdPending = System.currentTimeMillis() - lastAction < StorageView.HOLD_MS && !shown.isEmpty();
        if (holdPending) {
            // Right after the player's own craft the recipes keep their places (one that isn't listed any more leaves
            // a gap), so a quick second click never crafts whatever moved into that slot.
            for (int i = 0; i < shown.size(); i++) {
                QuickCraft.CachedRecipe old = shown.get(i);
                QuickCraft.CachedRecipe now = null;
                if (old != null) for (QuickCraft.CachedRecipe c : list) if (c.key.equals(old.key)) now = c;
                shown.set(i, now);
            }
        } else {
            shown.clear();
            for (int i = page * PER_PAGE; i < Math.min(list.size(), (page + 1) * PER_PAGE); i++) shown.add(list.get(i));
        }
        for (int i = 0; i < PER_PAGE; i++) put(slotOf(i), i < shown.size() && shown.get(i) != null ? icon(shown.get(i)) : null);
        if (list.isEmpty()) put(slotOf(19), button(Items.PAPER, craft.searching() ? "craft.nothing-found" : "craft.nothing"));

        put(SEARCH, craft.searching() ? button(Items.COMPASS, "craft.search-active", Text.unparsed("query", craft.query)) : button(Items.COMPASS, "craft.search"));
        put(CLEAR, craft.searching() ? button(Items.BARRIER, "craft.clear") : filler());
        put(SHOW, button(craft.showAll ? Items.BOOKSHELF : Items.BOOK, craft.showAll ? "craft.show-all" : "craft.show-craftable",
                Text.unparsed("ready", craft.ready()), Text.unparsed("total", list.size())));
        put(AUTO, module.recursiveDepth() <= 0 ? filler()
                : button(craft.auto ? Items.CRAFTER : Items.DROPPER, craft.auto ? "craft.auto-on" : "craft.auto-off", Text.unparsed("depth", module.recursiveDepth())));
        for (int slot = AUTO + 1; slot < 53; slot++) put(slot, filler());

        put(GRID, button(Items.CRAFTING_TABLE, "craft.grid"));
        ItemStack amt = button(Items.REDSTONE, "craft.amount", Text.unparsed("amount", amount));
        amt.setCount(Math.min(64, amount));
        put(AMOUNT, amt);
        put(SOURCE, terminal == null ? filler()
                : button(networkFirst ? Items.CHEST : Items.BUNDLE, networkFirst ? "craft.source-network" : "craft.source-inventory"));
        put(REFRESH, button(Items.SUNFLOWER, "craft.refresh"));
        ItemStack pg = button(Items.ARROW, "craft.page", Text.unparsed("page", page + 1), Text.unparsed("pages", pages));
        pg.setCount(Math.min(64, page + 1));
        put(PAGE, pg);
        put(BACK, from != null ? button(Items.OAK_DOOR, "craft.back") : button(Items.BARRIER, "craft.close"));
    }

    private ItemStack icon(QuickCraft.CachedRecipe r) {
        ItemStack icon = r.result.copy();
        List<net.minecraft.network.chat.Component> lore = new ArrayList<>(icon.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        for (Component c : craft.lines(r, amount, terminal != null)) lore.add(Adv.vanilla(c));
        icon.set(DataComponents.LORE, new ItemLore(lore));
        return icon;
    }

    @Override
    void click(ServerPlayer p, Menu menu, int raw, Gui.ClickKind click) {
        if (click != Gui.ClickKind.LEFT && click != Gui.ClickKind.RIGHT && click != Gui.ClickKind.SHIFT_LEFT && click != Gui.ClickKind.SHIFT_RIGHT) return;
        boolean right = click == Gui.ClickKind.RIGHT || click == Gui.ClickKind.SHIFT_RIGHT;
        boolean shift = click == Gui.ClickKind.SHIFT_LEFT || click == Gui.ClickKind.SHIFT_RIGHT;
        int idx = indexOf(raw);
        if (idx >= 0) {
            if (idx >= shown.size() || shown.get(idx) == null || !menu.getCarried().isEmpty()) return;
            // Shift only means "into the storage" when there is one; on /quickcraft it is a plain click.
            craft(p, shown.get(idx), right, shift && terminal != null);
            return;
        }
        switch (raw) {
            case GRID -> later(p, () -> module.openWorkbench(p));
            case AMOUNT -> {
                int i = 0;
                for (int k = 0; k < AMOUNTS.length; k++) if (AMOUNTS[k] == amount) i = k;
                amount = AMOUNTS[(i + (right ? AMOUNTS.length - 1 : 1)) % AMOUNTS.length];
                module.craftAmountOf(p, amount);
                module.click(p);
                render(p);
            }
            case SOURCE -> {
                if (terminal == null) return;
                networkFirst = !networkFirst;
                module.networkFirstOf(p, networkFirst);
                module.click(p);
                render(p);
            }
            case REFRESH -> {
                lastAction = 0;
                module.craft().rebuildSoon();
                craft.invalidate();
                module.click(p);
                render(p);
            }
            case PAGE -> {
                if (pages <= 1) return;
                page = (page + (right ? pages - 1 : 1)) % pages;
                lastAction = 0;
                module.page(p);
                render(p);
            }
            case SEARCH -> later(p, () -> openSearch(p));
            case CLEAR -> {
                if (craft.query == null) return;
                craft.query = null;
                lastAction = 0;
                page = 0;
                module.click(p);
                render(p);
            }
            case SHOW -> {
                craft.showAll = !craft.showAll;
                module.showAllOf(p, craft.showAll);
                lastAction = 0;
                page = 0;
                module.click(p);
                render(p);
            }
            case AUTO -> {
                if (module.recursiveDepth() <= 0) return;
                craft.auto = !craft.auto;
                module.autoCraftOf(p, craft.auto);
                lastAction = 0;
                page = 0;
                module.click(p);
                render(p);
            }
            case BACK -> later(p, () -> {
                if (from != null) module.reopen(p, from);
                else p.closeContainer();
            });
            default -> {
            }
        }
    }

    /** Own inventory: free to rearrange; a shift-click has nowhere to go. */
    @Override
    void shiftBottom(ServerPlayer p, Menu menu, Slot slot) {
    }

    private void later(ServerPlayer p, Runnable r) {
        Tasks.sync(() -> {
            if (!p.hasDisconnected() && !dead) r.run();
        });
    }

    private void craft(ServerPlayer p, QuickCraft.CachedRecipe r, boolean all, boolean store) {
        Network net = terminal == null ? null : module.network(p, terminal, true);
        craft.craft(p, r, net, amount, all, store);
        lastAction = System.currentTimeMillis();
        render(p);
        // Other windows on this storage redraw next tick (an inventory-only /quickcraft changes no storage).
        if (terminal != null) module.refreshSoon(StorageModule.touched(terminal, net), this);
    }

    // ------------------------------------------------------------------ search

    private void openSearch(ServerPlayer p) {
        lastAction = 0;
        module.suspend(this);
        p.closeContainer();
        Dialogs.Menu menu = module.plugin().dialogs().menu(text().msg("storage.craft.search-dialog.title"));
        menu.text(text().msg("storage.craft.search-dialog.body"), 280);
        menu.input(Dialogs.Input.text("query", text().msg("storage.search.label")).initial(craft.query == null ? "" : craft.query).maxLength(40).width(260).build());
        menu.button(text().msg("storage.search.go"), null, 150, (pl, view) -> {
            String q = view == null ? null : view.getText("query");
            craft.query = q == null || q.isBlank() ? null : q.trim();
            page = 0;
            module.reopen(pl, this);
        });
        if (craft.query != null) {
            menu.button(text().msg("storage.search.clear"), null, 150, pl -> {
                craft.query = null;
                page = 0;
                module.reopen(pl, this);
            });
        }
        menu.columns(2);
        menu.exit(text().msg("menu.back"), pl -> module.reopen(pl, this));
        menu.open(p);
    }
}
