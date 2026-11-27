package net.axther.aftrhrs.feature.storage;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.content.ArcanaContent;
import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The storage view (the chest window; Companion players get the mod's screen instead): rows 1-5 show the network's
 * items (one icon per kind, the total in the lore), the bottom row Search, Clear, Sort, pages, Network, Deposit All
 * and Craft (Quick Craft from this storage).
 *
 * <p>Clicking an icon takes a stack (right: half; shift: straight into the inventory); clicking with an item on
 * the cursor, or shift-clicking in your own inventory, puts it into the network. Each of these reads the chests
 * again at that moment, moves the real items, and hands back whatever didn't fit.
 */
final class StorageView extends View {
    static final int ITEMS = 45;
    static final int SEARCH = 45, CLEAR = 46, SORT = 47, PREV = 48, PAGE = 49, NEXT = 50, INFO = 51, DEPOSIT = 52, CRAFT = 53;

    enum Sort {
        AMOUNT, NAME;

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** How long icons hold their places after the player's own click. */
    static final long HOLD_MS = 1500;

    String query;
    int page;
    Sort sort;
    private final List<Network.Entry> shown = new ArrayList<>();
    private int pages = 1;

    StorageView(StorageModule module, ServerPlayer viewer, StorageBlock.Terminal.At terminal) {
        super(module, viewer, terminal);
        this.sort = module.sortOf(viewer);
        this.title = text().msg(terminal.kind() == Kind.CRAFTING ? "storage.view.title-crafting" : "storage.view.title");
    }

    static String nameOf(ItemStack it) {
        return it.getHoverName().getString();
    }

    /** Search: the item's name, its type ({@code oak planks}, {@code oak_planks}), its custom id or a lore line. */
    static boolean matches(ItemStack it, String q) {
        if (nameOf(it).toLowerCase(Locale.ROOT).contains(q)) return true;
        String type = BuiltInRegistries.ITEM.getKey(it.getItem()).getPath();
        if (type.contains(q) || type.replace('_', ' ').contains(q)) return true;
        String content = ArcanaContent.id(it); // ex-Oraxen ids (the old search found Oraxen ids too)
        if (content != null && content.toLowerCase(Locale.ROOT).contains(q)) return true;
        ItemLore lore = it.get(DataComponents.LORE);
        if (lore != null) for (var line : lore.lines()) if (line.getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    @Override
    void render(ServerPlayer p) {
        drawing();
        Network net = module.network(p, terminal, false);
        lastSeen = StorageModule.touched(terminal, net);
        List<Network.Entry> all = net.aggregate();
        long items = 0;
        for (Network.Entry e : all) items += e.total;
        int kinds = all.size();
        if (query != null && !query.isBlank()) {
            String q = query.toLowerCase(Locale.ROOT).trim();
            all.removeIf(e -> !matches(e.proto, q));
        }
        holdPending = System.currentTimeMillis() - lastAction < HOLD_MS && !shown.isEmpty();
        if (holdPending) {
            // Right after the player's own click the icons keep their places (a used-up item leaves a gap), so a
            // quick second click never lands on whatever moved into that slot.
            for (int i = 0; i < shown.size(); i++) {
                Network.Entry old = shown.get(i);
                Network.Entry now = null;
                if (old != null) for (Network.Entry e : all) if (Network.similar(e.proto, old.proto)) now = e;
                shown.set(i, now);
            }
        } else {
            Map<Network.Entry, String> names = new HashMap<>();
            for (Network.Entry e : all) names.put(e, nameOf(e.proto).toLowerCase(Locale.ROOT));
            Comparator<Network.Entry> byName = Comparator.comparing(names::get);
            all.sort(sort == Sort.NAME ? byName : Comparator.comparingLong((Network.Entry e) -> -e.total).thenComparing(byName));
            pages = Math.max(1, (all.size() + ITEMS - 1) / ITEMS);
            page = Math.max(0, Math.min(page, pages - 1));
            shown.clear();
            for (int i = page * ITEMS; i < Math.min(all.size(), (page + 1) * ITEMS); i++) shown.add(all.get(i));
        }
        // Only the shown kinds' changes are read (at most a page of them).
        List<String> keys = new ArrayList<>(shown.size());
        for (Network.Entry e : shown) if (e != null) keys.add(StorageLog.key(e.proto));
        Map<String, List<StorageLog.Change>> logs = StorageLog.collect(net, StorageBridge.LOG_LINES, keys);
        for (int slot = 0; slot < ITEMS; slot++) put(slot, slot < shown.size() && shown.get(slot) != null ? icon(shown.get(slot), logs) : null);

        boolean searching = query != null && !query.isBlank();
        put(SEARCH, searching ? button(Items.COMPASS, "view.search-active", Text.unparsed("query", query)) : button(Items.COMPASS, "view.search"));
        put(CLEAR, searching ? button(Items.BARRIER, "view.clear") : filler());
        put(SORT, button(Items.HOPPER, "view.sort", Text.component("mode", text().msg("storage.view.sort-" + sort.name().toLowerCase(Locale.ROOT)))));
        put(PREV, page > 0 ? button(Items.ARROW, "view.prev") : filler());
        ItemStack pageIcon = button(Items.PAPER, "view.page", Text.unparsed("page", page + 1), Text.unparsed("pages", pages));
        pageIcon.setCount(Math.min(64, page + 1));
        put(PAGE, pageIcon);
        put(NEXT, page < pages - 1 ? button(Items.ARROW, "view.next") : filler());
        put(INFO, button(Items.CHEST, net.truncated ? "view.info-truncated" : "view.info",
                Text.unparsed("containers", net.containers), Text.unparsed("connectors", net.connectors),
                Text.unparsed("used", net.usedSlots()), Text.unparsed("slots", net.totalSlots()),
                Text.unparsed("items", String.format(Locale.US, "%,d", items)), Text.unparsed("kinds", kinds),
                Text.unparsed("max", module.maxBlocks())));
        put(DEPOSIT, button(Items.HOPPER_MINECART, "view.deposit"));
        put(CRAFT, module.craftInTerminals() ? button(Items.CRAFTING_TABLE, "view.craft") : filler());
    }

    private ItemStack icon(Network.Entry e, Map<String, List<StorageLog.Change>> logs) {
        ItemStack icon = e.proto.copyWithCount((int) Math.max(1, Math.min(e.total, e.proto.getMaxStackSize())));
        List<net.minecraft.network.chat.Component> lore = new ArrayList<>(icon.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        for (Component line : text().lines("storage.view.item-lore", Text.unparsed("total", String.format(Locale.US, "%,d", e.total)))) {
            lore.add(Adv.vanilla(line));
        }
        // Who changed it last (the chest window can't know the player's time zone: the server's is used).
        List<StorageLog.Change> log = logs.get(StorageLog.key(e.proto));
        if (log != null && !log.isEmpty()) {
            long now = System.currentTimeMillis();
            StorageLog.Change last = log.getFirst();
            lore.add(Adv.vanilla(text().itemMsg("storage.view.log-head", Text.unparsed("who", last.who()),
                    Text.unparsed("when", StorageLog.when(last.at(), java.time.ZoneId.systemDefault())))));
            for (StorageLog.Change c : log) {
                lore.add(Adv.vanilla(text().itemMsg("storage.view.log-line", Text.unparsed("amount", StorageLog.signed(c.delta())),
                        Text.unparsed("who", c.who()), Text.unparsed("ago", StorageLog.ago(c.at(), now)))));
            }
        }
        icon.set(DataComponents.LORE, new ItemLore(lore));
        return icon;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    void click(ServerPlayer p, Menu menu, int raw, Gui.ClickKind click) {
        if (raw < ITEMS) {
            itemClick(p, menu, raw, click);
            return;
        }
        if (click == Gui.ClickKind.NUMBER_KEY || click == Gui.ClickKind.SWAP_OFFHAND || click == Gui.ClickKind.MIDDLE) return;
        switch (raw) {
            case SEARCH -> later(p, () -> openSearch(p));
            case CLEAR -> {
                if (query == null) return;
                query = null;
                page = 0;
                lastAction = 0;
                module.click(p);
                render(p);
            }
            case SORT -> {
                sort = sort.next();
                lastAction = 0;
                module.sortOf(p, sort);
                module.click(p);
                render(p);
            }
            case PREV, NEXT -> {
                int to = page + (raw == NEXT ? 1 : -1);
                if (to < 0 || to >= pages) return;
                page = to;
                lastAction = 0;
                module.page(p);
                render(p);
            }
            case INFO -> {
                Network net = module.network(p, terminal, true);
                long items = 0;
                List<Network.Entry> all = net.aggregate();
                for (Network.Entry en : all) items += en.total;
                text().send(p, "storage.view.info-chat", Text.unparsed("containers", net.containers), Text.unparsed("connectors", net.connectors),
                        Text.unparsed("used", net.usedSlots()), Text.unparsed("slots", net.totalSlots()),
                        Text.unparsed("items", String.format(Locale.US, "%,d", items)), Text.unparsed("kinds", all.size()));
                module.click(p);
            }
            case DEPOSIT -> depositAll(p);
            case CRAFT -> {
                if (module.craftInTerminals()) later(p, () -> module.openCraft(p, terminal, this));
            }
            default -> {
            }
        }
    }

    @Override
    void shiftBottom(ServerPlayer p, Menu menu, Slot slot) {
        ItemStack item = slot.getItem();
        if (item.isEmpty()) return;
        int before = item.getCount();
        Network net = module.network(p, terminal, true);
        ItemStack rest = net.insert(item.copy());
        slot.set(rest == null ? ItemStack.EMPTY : rest);
        changed(p, net, rest == null || rest.getCount() < before, rest != null);
    }

    /** Runs next tick (no closing or opening windows inside a click). */
    private void later(ServerPlayer p, Runnable r) {
        Tasks.sync(() -> {
            if (!p.hasDisconnected() && !dead) r.run();
        });
    }

    private void itemClick(ServerPlayer p, Menu menu, int slot, Gui.ClickKind click) {
        ItemStack cursor = menu.getCarried();
        Network net = module.network(p, terminal, true);
        if (!cursor.isEmpty()) {
            if (click != Gui.ClickKind.LEFT && click != Gui.ClickKind.RIGHT) return;
            ItemStack put = cursor.copy();
            if (click == Gui.ClickKind.RIGHT) put.setCount(1);
            ItemStack rest = net.insert(put);
            int stored = put.getCount() - (rest == null ? 0 : rest.getCount());
            if (stored > 0) menu.setCarried(cursor.getCount() - stored > 0 ? cursor.copyWithCount(cursor.getCount() - stored) : ItemStack.EMPTY);
            changed(p, net, stored > 0, rest != null);
            return;
        }
        if (slot >= shown.size() || shown.get(slot) == null) return;
        boolean shift = click == Gui.ClickKind.SHIFT_LEFT || click == Gui.ClickKind.SHIFT_RIGHT;
        if (!shift && click != Gui.ClickKind.LEFT && click != Gui.ClickKind.RIGHT) return;
        ItemStack proto = shown.get(slot).proto;
        long live = net.count(proto);
        if (live <= 0) {
            render(p);
            return;
        }
        int max = Math.max(1, proto.getMaxStackSize());
        int want = (int) Math.min(max, live);
        if (shift) {
            long room = QuickCraft.space(p, proto, null);
            want = (int) Math.min(want, room);
            if (want <= 0) {
                text().send(p, "storage.view.inventory-full");
                module.error(p);
                return;
            }
            int got = net.extract(proto, want);
            if (got > 0) {
                ItemStack rest = QuickCraft.addToInventory(p.getInventory(), proto.copyWithCount(got));
                if (!rest.isEmpty()) giveBack(p, net, rest);
            }
            changed(p, net, got > 0, false);
            return;
        }
        if (click == Gui.ClickKind.RIGHT) want = (want + 1) / 2;
        int got = net.extract(proto, want);
        if (got > 0) menu.setCarried(proto.copyWithCount(got));
        changed(p, net, got > 0, false);
    }

    /** Something that couldn't go to the player goes back into the network, else to their feet. */
    private static void giveBack(ServerPlayer p, Network net, ItemStack item) {
        ItemStack rest = net.insert(item);
        if (rest != null) QuickCraft.deliver(p, List.of(rest), null);
    }

    /** Deposit All: the main inventory except the hotbar. */
    private void depositAll(ServerPlayer p) {
        Network net = module.network(p, terminal, true);
        Inventory pi = p.getInventory();
        int moved = 0;
        boolean full = false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack it = pi.getItem(slot);
            if (it.isEmpty()) continue;
            ItemStack rest = net.insert(it.copy());
            int stored = it.getCount() - (rest == null ? 0 : rest.getCount());
            if (stored > 0) {
                pi.setItem(slot, rest == null ? ItemStack.EMPTY : rest);
                moved += stored;
            }
            if (rest != null) full = true;
        }
        if (moved > 0) text().send(p, "storage.view.deposited", Text.unparsed("count", String.format(Locale.US, "%,d", moved)));
        changed(p, net, moved > 0, full);
    }

    /** After a take or put through {@code net}: this window redraws now, other windows on the same storage next tick. */
    private void changed(ServerPlayer p, Network net, boolean moved, boolean full) {
        lastAction = System.currentTimeMillis();
        if (full) {
            text().send(p, "storage.view.network-full");
            module.error(p);
        } else if (moved) module.click(p);
        render(p);
        if (moved) module.refreshSoon(StorageModule.touched(terminal, net), this);
    }

    // ------------------------------------------------------------------ search

    private void openSearch(ServerPlayer p) {
        lastAction = 0;
        module.suspend(this);
        p.closeContainer();
        Dialogs.Menu menu = module.plugin().dialogs().menu(text().msg("storage.search.title"));
        menu.text(text().msg("storage.search.body"), 280);
        menu.input(Dialogs.Input.text("query", text().msg("storage.search.label")).initial(query == null ? "" : query).maxLength(40).width(260).build());
        menu.button(text().msg("storage.search.go"), null, 150, (pl, view) -> {
            String q = view == null ? null : view.getText("query");
            query = q == null || q.isBlank() ? null : q.trim();
            page = 0;
            module.reopen(pl, this);
        });
        if (query != null) {
            menu.button(text().msg("storage.search.clear"), null, 150, pl -> {
                query = null;
                page = 0;
                module.reopen(pl, this);
            });
        }
        menu.columns(2);
        menu.exit(text().msg("menu.back"), pl -> module.reopen(pl, this));
        menu.open(p);
    }
}
