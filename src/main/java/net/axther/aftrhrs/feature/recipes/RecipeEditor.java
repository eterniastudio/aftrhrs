package net.axther.aftrhrs.feature.recipes;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.feature.recipes.RecipesModule.CustomRecipe;
import net.axther.aftrhrs.feature.recipes.RecipesModule.Type;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.axther.aftrhrs.feature.recipes.RecipesModule.copyOf;
import static net.axther.aftrhrs.feature.recipes.RecipesModule.empty;

/**
 * The /recipes chests: the recipe list, the 6-row editor and the delete confirmation ({@link Gui} chests).
 *
 * <p>Editor items are copies. Items the admin puts in from their own inventory are tracked as on loan
 * ({@code real[slot]}) and go back to them (or drop at their feet) whenever the editor closes, they quit or the
 * server stops; the recipe keeps copies. Items shown from a saved recipe or copied from the hand are never real,
 * so taking them out just clears the slot. Nothing can be pulled out of the editor that wasn't put in. The editor's
 * grid and result slots are locked {@link Gui} slots whose handlers do the moving; clicks in the admin's own
 * inventory are handled here too (pick up / put down / split with left and right click, shift-click into the grid).
 */
final class RecipeEditor {
    private static final int PER_PAGE = 45;
    /** Editor layout (6 rows). The 3x3 grid, inputs 0-8. */
    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int BACK = 0, ID = 4, HELP = 8, ARROW = 23, RESULT = 25;
    static final int XP_DOWN = 37, XP = 38, XP_UP = 39, TIME_DOWN = 41, TIME = 42, TIME_UP = 43;
    static final int TYPE = 45, EXACT = 46, ENABLED = 47, RENAME = 48, CANCEL = 50, SAVE = 53;

    private final Feather plugin;
    private final RecipesModule module;
    private final Map<UUID, EditorView> editors = new HashMap<>();

    RecipeEditor(Feather plugin, RecipesModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private static final class EditorView {
        final ServerPlayer viewer;
        final CustomRecipe draft;
        final String originalId;
        final Gui gui;
        /** Slots holding the admin's own items (owed back on close). */
        final boolean[] real = new boolean[54];
        boolean closed;

        EditorView(ServerPlayer viewer, CustomRecipe draft, String originalId, Gui gui) {
            this.viewer = viewer;
            this.draft = draft;
            this.originalId = originalId;
            this.gui = gui;
        }
    }

    /** A button: click sound, then the action (handlers already run after the click packet). */
    private Gui.Handler button(Gui.Handler action) {
        return c -> {
            plugin.text().sound(c.player(), "click");
            action.click(c);
        };
    }

    // ------------------------------------------------------------------ list

    void openList(ServerPlayer viewer, int page) {
        Text text = plugin.text();
        List<CustomRecipe> all = new ArrayList<>(module.recipes().values());
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        Gui gui = Gui.create(6, text.msg("recipes.gui.list-title",
                Text.unparsed("page", page + 1), Text.unparsed("pages", pages), Text.unparsed("count", all.size())));
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < all.size(); i++) {
            CustomRecipe r = all.get(start + i);
            ItemStack icon = r.result.copy();
            List<Component> lore = new ArrayList<>();
            ItemLore old = icon.get(DataComponents.LORE);
            if (old != null) for (net.minecraft.network.chat.Component l : old.lines()) lore.add(Adv.adventure(l));
            if (!lore.isEmpty()) lore.add(Component.empty());
            lore.addAll(text.lines("recipes.gui.entry-lore", Text.unparsed("id", r.id),
                    Text.component("type", module.typeName(r.type)), Text.component("state", module.stateName(r.enabled))));
            Icons.lore(icon, lore);
            if (!r.enabled) icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
            String id = r.id;
            gui.set(i, icon, button(c -> {
                CustomRecipe current = module.get(id);
                if (current == null) {
                    openList(viewer, 0);
                    return;
                }
                if (c.kind() == Gui.ClickKind.SHIFT_RIGHT) openConfirm(viewer, current);
                else openEditor(viewer, current.copy(), id);
            }));
        }
        if (all.isEmpty()) gui.set(22, icon(Items.STAINED_GLASS_PANE.lightGray(), "recipes.gui.empty", "recipes.gui.empty-lore"));
        final int current = page;
        Gui.navBar(gui, text, page > 0, c -> openList(viewer, current - 1), page < pages - 1, c -> openList(viewer, current + 1), null, null);
        gui.set(48, icon(Items.WRITABLE_BOOK, "recipes.gui.new", "recipes.gui.new-lore"), button(c -> openEditor(viewer, new CustomRecipe(), null)));
        gui.open(viewer);
        text.sound(viewer, "menu-open");
    }

    private void openConfirm(ServerPlayer viewer, CustomRecipe r) {
        Text text = plugin.text();
        Gui gui = Gui.create(3, text.msg("recipes.gui.confirm-title", Text.unparsed("id", r.id)));
        gui.fill(0, 26, icon(Items.STAINED_GLASS_PANE.black(), null, null));
        String id = r.id;
        gui.set(11, icon(Items.CONCRETE.red(), "recipes.gui.confirm-delete", "recipes.gui.confirm-delete-lore", Text.unparsed("id", r.id)), button(c -> {
            if (module.delete(id)) {
                text.send(viewer, "recipes.deleted", Text.unparsed("id", id));
                text.sound(viewer, "deleted");
            } else if (module.fileUnreadable()) {
                text.send(viewer, "recipes.file-unreadable");
            }
            openList(viewer, 0);
        }));
        gui.set(13, r.result.copy());
        gui.set(15, icon(Items.CONCRETE.lime(), "recipes.gui.confirm-keep", null), button(c -> openList(viewer, 0)));
        gui.open(viewer);
    }

    // ------------------------------------------------------------------ editor

    void openEditor(ServerPlayer viewer, CustomRecipe draft, String originalId) {
        Text text = plugin.text();
        Component title = draft.id == null ? text.msg("recipes.gui.editor-title-new")
                : text.msg("recipes.gui.editor-title", Text.unparsed("id", draft.id));
        Gui gui = Gui.create(6, title);
        EditorView view = new EditorView(viewer, draft, originalId, gui);
        gui.attach(view);
        render(view);
        gui.onBottomClick(c -> bottomClick(view, c));
        gui.onClose(p -> finish(view));
        gui.open(viewer);
        if (Gui.of(viewer) == gui) editors.put(viewer.getUUID(), view);
        else view.closed = true;
        text.sound(viewer, "menu-open");
    }

    /** Copies what's in the grid and result slot into the draft (only the slots the current type uses). */
    private void sync(EditorView view) {
        for (int i : view.draft.type.inputs()) view.draft.inputs[i] = copyOf(view.gui.get(GRID[i]));
        view.draft.result = copyOf(view.gui.get(RESULT));
    }

    /** Sets a grid/result slot's item, keeping its handler. */
    private static void put(EditorView view, int slot, ItemStack item) {
        view.gui.container().setItem(slot, item == null ? ItemStack.EMPTY : item);
    }

    /** Draws the editor from the draft, leaving the admin's own items where they are. */
    private void render(EditorView view) {
        Text text = plugin.text();
        CustomRecipe d = view.draft;
        Gui gui = view.gui;
        ItemStack bg = icon(Items.STAINED_GLASS_PANE.gray(), null, null);
        ItemStack bar = icon(Items.STAINED_GLASS_PANE.black(), null, null);
        for (int s = 0; s < 54; s++) {
            if (isGrid(s) || s == RESULT) continue;
            gui.set(s, (s >= 45 ? bar : bg).copy());
        }
        for (int i = 0; i < 9; i++) {
            int slot = GRID[i];
            if (d.type.uses(i)) {
                ItemStack shown = view.real[slot] ? gui.get(slot) : copyOf(d.inputs[i]);
                gui.set(slot, shown, c -> slotClick(view, c));
            } else {
                giveBack(view, slot);
                gui.set(slot, label(d.type, i));
            }
        }
        gui.set(RESULT, view.real[RESULT] ? gui.get(RESULT) : copyOf(d.result), c -> slotClick(view, c));

        TagResolver type = Text.component("type", module.typeName(d.type));
        TagResolver idR = Text.unparsed("id", d.id == null ? "-" : d.id);
        gui.set(BACK, icon(Items.ARROW, "recipes.gui.back", "recipes.gui.back-lore"), button(c -> openList(view.viewer, 0)));
        gui.set(ID, icon(Items.NAME_TAG, d.id == null ? "recipes.gui.id-new" : "recipes.gui.id", "recipes.gui.id-lore", idR), button(c -> rename(view)));
        gui.set(HELP, icon(Items.BOOK, "recipes.gui.help", "recipes.gui.help-lore"));
        gui.set(ARROW, icon(d.type.icon, "recipes.gui.arrow", "recipes.gui.arrow-lore", type));

        if (d.type.cooking()) {
            TagResolver xp = Text.unparsed("xp", String.format(Locale.ROOT, "%.1f", d.experience));
            TagResolver time = Text.unparsed("seconds", String.format(Locale.ROOT, "%.1f", d.cookTime / 20.0));
            TagResolver ticks = Text.unparsed("ticks", d.cookTime);
            gui.set(XP_DOWN, icon(Items.STAINED_GLASS_PANE.red(), "recipes.gui.xp-down", "recipes.gui.xp-down-lore", xp),
                    button(c -> adjust(view, () -> d.experience = round(Math.max(0, d.experience - (c.isShiftClick() ? 1f : 0.1f))))));
            gui.set(XP, icon(Items.EXPERIENCE_BOTTLE, "recipes.gui.xp", "recipes.gui.xp-lore", xp));
            gui.set(XP_UP, icon(Items.STAINED_GLASS_PANE.lime(), "recipes.gui.xp-up", "recipes.gui.xp-up-lore", xp),
                    button(c -> adjust(view, () -> d.experience = round(Math.min(1000, d.experience + (c.isShiftClick() ? 1f : 0.1f))))));
            gui.set(TIME_DOWN, icon(Items.STAINED_GLASS_PANE.red(), "recipes.gui.time-down", "recipes.gui.time-down-lore", time, ticks),
                    button(c -> adjust(view, () -> d.cookTime = Math.max(20, d.cookTime - (c.isShiftClick() ? 200 : 20)))));
            gui.set(TIME, icon(Items.CLOCK, "recipes.gui.time", "recipes.gui.time-lore", time, ticks));
            gui.set(TIME_UP, icon(Items.STAINED_GLASS_PANE.lime(), "recipes.gui.time-up", "recipes.gui.time-up-lore", time, ticks),
                    button(c -> adjust(view, () -> d.cookTime = Math.min(72000, d.cookTime + (c.isShiftClick() ? 200 : 20)))));
        }

        gui.set(TYPE, icon(d.type.icon, "recipes.gui.type", "recipes.gui.type-lore", type), button(c -> adjust(view, () -> {
            Type[] all = Type.values();
            Type old = d.type;
            Type next = all[Math.floorMod(old.ordinal() + (c.isRightClick() ? -1 : 1), all.length)];
            d.type = next;
            if (next.cooking() && (!old.cooking() || d.cookTime == old.defaultTime)) d.cookTime = next.defaultTime;
        })));
        gui.set(EXACT, icon(d.exact ? Items.ENCHANTED_BOOK : Items.PAPER,
                d.exact ? "recipes.gui.exact-yes" : "recipes.gui.exact-no", "recipes.gui.exact-lore"), button(c -> adjust(view, () -> d.exact = !d.exact)));
        gui.set(ENABLED, icon(d.enabled ? Items.DYE.lime() : Items.DYE.gray(),
                d.enabled ? "recipes.gui.enabled-yes" : "recipes.gui.enabled-no", "recipes.gui.enabled-lore"), button(c -> adjust(view, () -> d.enabled = !d.enabled)));
        gui.set(RENAME, icon(Items.NAME_TAG, "recipes.gui.rename", "recipes.gui.rename-lore", idR), button(c -> rename(view)));
        gui.set(CANCEL, icon(Items.BARRIER, "recipes.gui.cancel", "recipes.gui.cancel-lore"), button(c -> openList(view.viewer, 0)));
        gui.set(SAVE, icon(Items.EMERALD, "recipes.gui.save", "recipes.gui.save-lore"), button(c -> save(view)));
    }

    /** Filler for a grid slot the current type doesn't use (with a label for the slots it does). */
    private ItemStack label(Type type, int index) {
        if (type == Type.SMITHING && index < 3) {
            return icon(Items.STAINED_GLASS_PANE.white(), "recipes.gui.label-" + (index == 0 ? "template" : index == 1 ? "base" : "addition"), null);
        }
        if (type != Type.SMITHING && !type.crafting() && index == 1) return icon(Items.STAINED_GLASS_PANE.white(), "recipes.gui.label-input", null);
        return icon(Items.STAINED_GLASS_PANE.lightGray(), null, null);
    }

    private static float round(float f) {
        return Math.round(f * 10f) / 10f;
    }

    private static boolean isGrid(int slot) {
        for (int g : GRID) if (g == slot) return true;
        return false;
    }

    private void adjust(EditorView view, Runnable change) {
        if (view.closed) return;
        sync(view);
        change.run();
        render(view);
    }

    private void save(EditorView view) {
        if (view.closed) return;
        Text text = plugin.text();
        sync(view);
        List<Component> problems = module.save(view.draft, view.originalId);
        if (!problems.isEmpty()) {
            text.send(view.viewer, "recipes.not-saved");
            for (Component p : problems) text.send(view.viewer, "recipes.problem-line", Text.component("problem", p));
            text.sound(view.viewer, "error");
            return;
        }
        CustomRecipe saved = module.get(view.draft.id);
        text.send(view.viewer, "recipes.saved", Text.unparsed("id", saved.id), Text.component("type", module.typeName(saved.type)),
                Text.component("state", module.stateName(saved.enabled)), Text.component("item", RecipesModule.itemLabel(saved.result)));
        text.sound(view.viewer, "accepted");
        openList(view.viewer, 0); // closes the editor: the admin's items go back
    }

    /** Asks for a new id in a dialog. The editor closes meanwhile (items go back) and reopens with the draft. */
    private void rename(EditorView view) {
        if (view.closed) return;
        sync(view);
        ServerPlayer viewer = view.viewer;
        viewer.closeContainer();
        finish(view);
        openRename(viewer, view.draft, view.originalId, view.draft.id == null ? "" : view.draft.id, null);
    }

    private void openRename(ServerPlayer viewer, CustomRecipe draft, String originalId, String initial, Component error) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("recipes.rename.title"));
        menu.text(text.msg("recipes.rename.body"), 300);
        if (error != null) menu.text(error, 300);
        menu.input(Dialogs.Input.text("id", text.msg("recipes.rename.label")).initial(initial).maxLength(40).width(250).build());
        menu.button(text.msg("recipes.rename.apply"), text.msg("recipes.rename.apply-tooltip"), 150, (p, response) -> {
            String raw = response.getText("id");
            String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (id.isEmpty() && originalId == null) {
                draft.id = null;
                openEditor(p, draft, originalId);
                return;
            }
            Component problem = module.idProblem(id, originalId);
            if (problem != null) {
                text.sound(p, "error");
                openRename(p, draft, originalId, id, problem);
                return;
            }
            draft.id = id;
            text.sound(p, "click");
            openEditor(p, draft, originalId);
        });
        menu.exit(text.msg("recipes.rename.back"), p -> openEditor(p, draft, originalId));
        menu.open(viewer);
    }

    // ------------------------------------------------------------------ editor slots

    /** A click on a grid or result slot (the click itself moved nothing: these slots are locked). */
    private void slotClick(EditorView view, Gui.Click click) {
        if (view.closed) return;
        ServerPlayer p = click.player();
        int slot = click.slot();
        AbstractContainerMenu menu = p.containerMenu;
        ItemStack cursor = copyOf(menu.getCarried());
        ItemStack current = copyOf(view.gui.get(slot));
        boolean real = view.real[slot] && current != null;
        switch (click.kind()) {
            case LEFT -> {
                if (cursor != null) {
                    if (real && ItemStack.isSameItemSameComponents(current, cursor)) {
                        int move = Math.min(cursor.getCount(), current.getMaxStackSize() - current.getCount());
                        if (move <= 0) return;
                        current.grow(move);
                        cursor.shrink(move);
                        put(view, slot, current);
                        menu.setCarried(cursor.isEmpty() ? ItemStack.EMPTY : cursor);
                    } else {
                        // Swap: the slot takes the cursor stack; the cursor gets back what was theirs (a copy is just dropped).
                        put(view, slot, cursor);
                        view.real[slot] = true;
                        menu.setCarried(real ? current : ItemStack.EMPTY);
                    }
                } else if (real) {
                    put(view, slot, null);
                    view.real[slot] = false;
                    menu.setCarried(current);
                } else if (current != null) {
                    put(view, slot, null);
                } else if (slot == RESULT) {
                    loadFromHand(view, p);
                }
            }
            case RIGHT -> {
                if (cursor != null) {
                    if (real && ItemStack.isSameItemSameComponents(current, cursor)) {
                        if (current.getCount() >= current.getMaxStackSize()) return;
                        current.grow(1);
                        put(view, slot, current);
                    } else {
                        if (real) {
                            put(view, slot, null);
                            view.real[slot] = false;
                            give(p, current);
                        }
                        put(view, slot, cursor.copyWithCount(1));
                        view.real[slot] = true;
                    }
                    cursor.shrink(1);
                    menu.setCarried(cursor.isEmpty() ? ItemStack.EMPTY : cursor);
                } else if (real) {
                    int take = (current.getCount() + 1) / 2;
                    ItemStack half = current.copyWithCount(take);
                    int left = current.getCount() - take;
                    if (left > 0) {
                        put(view, slot, current.copyWithCount(left));
                    } else {
                        put(view, slot, null);
                        view.real[slot] = false;
                    }
                    menu.setCarried(half);
                } else if (current != null) {
                    put(view, slot, null);
                } else if (slot == RESULT) {
                    loadFromHand(view, p);
                }
            }
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                if (real) {
                    ItemStack rest = current.copy();
                    p.getInventory().add(rest);
                    put(view, slot, rest.isEmpty() ? null : rest);
                    view.real[slot] = !rest.isEmpty();
                } else if (current != null) {
                    put(view, slot, null);
                }
            }
            default -> {
                // number keys, drop, middle click, double click: nothing
            }
        }
        menu.broadcastChanges();
    }

    /** "Load from hand": clicking the empty result slot copies the item in the main hand (the hand keeps it). */
    private void loadFromHand(EditorView view, ServerPlayer p) {
        ItemStack hand = p.getMainHandItem();
        if (empty(hand)) return;
        put(view, RESULT, hand.copy());
        view.real[RESULT] = false;
        plugin.text().actionBar(p, "recipes.loaded-from-hand");
    }

    /**
     * A click in the admin's own inventory (or outside the window). Shift-click moves the stack into the first free
     * input slot; left/right click pick up, put down, merge and split as usual; other clicks do nothing here.
     */
    private void bottomClick(EditorView view, Gui.Click click) {
        if (view.closed) return;
        ServerPlayer p = click.player();
        AbstractContainerMenu menu = p.containerMenu;
        if (click.slot() == -999) {
            ItemStack cursor = menu.getCarried();
            if (!cursor.isEmpty() && (click.kind() == Gui.ClickKind.OUTSIDE)) {
                menu.setCarried(ItemStack.EMPTY);
                p.drop(cursor, true, Prediction.SERVER_ONLY);
            }
            menu.broadcastChanges();
            return;
        }
        if (click.slot() < 0 || click.slot() >= menu.slots.size()) return;
        Slot slot = menu.getSlot(click.slot());
        ItemStack in = slot.getItem();
        ItemStack cur = menu.getCarried();
        switch (click.kind()) {
            case SHIFT_LEFT, SHIFT_RIGHT -> shiftIn(view, slot);
            case LEFT -> {
                if (cur.isEmpty()) {
                    if (in.isEmpty()) return;
                    menu.setCarried(in);
                    slot.set(ItemStack.EMPTY);
                } else if (in.isEmpty()) {
                    slot.set(cur);
                    menu.setCarried(ItemStack.EMPTY);
                } else if (ItemStack.isSameItemSameComponents(in, cur)) {
                    int move = Math.min(cur.getCount(), in.getMaxStackSize() - in.getCount());
                    if (move > 0) {
                        in.grow(move);
                        cur.shrink(move);
                        slot.setChanged();
                        if (cur.isEmpty()) menu.setCarried(ItemStack.EMPTY);
                    }
                } else {
                    slot.set(cur);
                    menu.setCarried(in);
                }
            }
            case RIGHT -> {
                if (cur.isEmpty()) {
                    if (in.isEmpty()) return;
                    menu.setCarried(in.split((in.getCount() + 1) / 2));
                    slot.setChanged();
                } else if (in.isEmpty()) {
                    slot.set(cur.split(1));
                    if (cur.isEmpty()) menu.setCarried(ItemStack.EMPTY);
                } else if (ItemStack.isSameItemSameComponents(in, cur)) {
                    if (in.getCount() < in.getMaxStackSize()) {
                        in.grow(1);
                        cur.shrink(1);
                        slot.setChanged();
                        if (cur.isEmpty()) menu.setCarried(ItemStack.EMPTY);
                    }
                } else {
                    slot.set(cur);
                    menu.setCarried(in);
                }
            }
            default -> {
                // number keys, drop, double click: nothing (double-click would gather items out of the editor too)
            }
        }
        menu.broadcastChanges();
    }

    /** Shift-click in the admin's own inventory: the stack moves into the first free input slot. */
    private void shiftIn(EditorView view, Slot from) {
        ItemStack stack = copyOf(from.getItem());
        if (stack == null) return;
        for (int i : view.draft.type.inputs()) {
            int slot = GRID[i];
            if (!empty(view.gui.get(slot))) continue;
            from.set(ItemStack.EMPTY);
            put(view, slot, stack);
            view.real[slot] = true;
            return;
        }
    }

    /** Returns the admin's own item in a slot to them (the slot is cleared). */
    private void giveBack(EditorView view, int slot) {
        if (!view.real[slot]) return;
        view.real[slot] = false;
        ItemStack item = copyOf(view.gui.get(slot));
        put(view, slot, null);
        if (item != null) give(view.viewer, item);
    }

    /** Into the inventory, or dropped at their feet (only they can pick it up) when it's full. */
    static void give(ServerPlayer player, ItemStack item) {
        player.getInventory().add(item);
        if (item.isEmpty()) return;
        ItemEntity drop = player.drop(item, false, Prediction.SERVER_ONLY);
        if (drop != null) {
            drop.setTarget(player.getUUID());
            drop.setNoPickUpDelay();
        }
    }

    /** The editor closed (any reason): everything the admin put in goes back. Safe to call twice. */
    private void finish(EditorView view) {
        if (view.closed) return;
        view.closed = true;
        for (int s = 0; s < 54; s++) giveBack(view, s);
        editors.remove(view.viewer.getUUID(), view);
    }

    /** A player is leaving: their items go back before they are saved. */
    void quit(ServerPlayer player) {
        EditorView view = editors.get(player.getUUID());
        if (view != null) finish(view);
    }

    /** Server stop or module off: hand everything back and close open editors. */
    void shutdown() {
        for (EditorView view : new ArrayList<>(editors.values())) {
            finish(view);
            if (!view.viewer.hasDisconnected() && Gui.of(view.viewer) == view.gui) view.viewer.closeContainer();
        }
        editors.clear();
    }

    // ------------------------------------------------------------------ items

    private static ItemStack icon(Item item, String nameKey, String loreKey, TagResolver... r) {
        return Icons.icon(item, nameKey, loreKey, r);
    }
}
