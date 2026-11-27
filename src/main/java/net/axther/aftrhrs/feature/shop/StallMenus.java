package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ByteTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The keeper's chest screens: the buying screen for everyone else and the selling screen for the renter, plus the
 * keeper's look picker. Three rows: two shelves of 18 listings (paged when a stall holds more) and a row of buttons;
 * the background is the {@code feather_stall_gui} glyph in the title ({@link StallArt}).
 *
 * <p>Nothing in these screens is a real item: every slot shows a copy in a locked {@link Gui} slot, and the button or
 * listing behind a slot runs after the click through the same checked paths as the stall dialogs ({@link Stalls#buy},
 * {@link Stalls#addFrom}, {@link Stalls#takeBack}, {@link Stalls#reprice}). A player has at most one click waiting at a
 * time. Number keys, double-click collect, drops, middle-clicks and drags do nothing. A screen keeps its slot layout
 * while others buy (a sold-out listing turns into a "sold out" pane), so a click can't land on a listing that moved
 * under the cursor, and a price that went up since the screen showed it is refused once.
 */
final class StallMenus {
    static final int SIZE = 27;
    static final int PER_PAGE = 18;
    /** The plugin's preview tag (custom_data), so a copy that somehow left a screen is recognized and taken away. */
    static final String PREVIEW_KEY = "feather:stall_preview";

    enum Kind { BUY, SELL, LOOK }

    /** What a screen shows and what each slot does. */
    static final class View {
        final Kind kind;
        final String stall;
        int page;
        Gui gui;
        /** Listing ids by slot on this page, fixed until the viewer acts or changes page. */
        final List<Long> layout = new ArrayList<>();
        /** Listing id -> the price this screen showed (a click never pays more). */
        final Map<Long, Long> quoted = new HashMap<>();

        View(Kind kind, String stall, int page) {
            this.kind = kind;
            this.stall = stall;
            this.page = page;
        }
    }

    private final ShopModule shop;
    private final Set<UUID> pending = new HashSet<>();

    StallMenus(ShopModule shop) {
        this.shop = shop;
    }

    private Text text() {
        return shop.text();
    }

    private Stalls stalls() {
        return shop.stalls;
    }

    // ------------------------------------------------------------------ opening

    void openBuy(ServerPlayer p, ShopStore.Stall s, int page) {
        if (shop.closed(p)) return;
        if (!s.rented() || s.rentUntil <= System.currentTimeMillis()) {
            shop.error(p, "shop.stall.closed-now");
            return;
        }
        if (!stalls().near(p, s)) {
            shop.error(p, "shop.stall.too-far");
            return;
        }
        open(p, new View(Kind.BUY, s.id, page), text().msg("shop.stall.gui.buy-title", owner(s), Text.unparsed("name", s.name)));
    }

    void openSell(ServerPlayer p, ShopStore.Stall s, int page) {
        if (shop.closed(p)) return;
        if (stalls().ownedHere(p, s.id) == null) return;
        open(p, new View(Kind.SELL, s.id, page), text().msg("shop.stall.gui.sell-title", Text.unparsed("name", s.name)));
    }

    void openLook(ServerPlayer p, ShopStore.Stall s) {
        if (stalls().ownedHere(p, s.id) == null) return;
        open(p, new View(Kind.LOOK, s.id, 0), text().msg("shop.stall.gui.look-title", Text.unparsed("name", s.name)));
    }

    private void open(ServerPlayer p, View v, Component title) {
        ShopStore.Stall s = stalls().current(v.stall);
        if (s == null) return;
        Gui gui = Gui.create(3, StallArt.title(title));
        v.gui = gui;
        gui.attach(v);
        gui.onBottomClick(c -> bottomClick(c, v));
        gui.onClose(pl -> Tasks.later(1L, () -> {
            if (!pl.hasDisconnected()) sweep(pl);
        }));
        layout(v, s);
        render(p, v, s);
        gui.open(p);
        text().sound(p, "menu-open");
    }

    private static TagResolver owner(ShopStore.Stall s) {
        return Text.unparsed("owner", s.ownerName == null ? "?" : s.ownerName);
    }

    private void reopenBuy(ServerPlayer p, String id, int page) {
        ShopStore.Stall s = stalls().current(id);
        if (s != null && s.rented() && stalls().near(p, s)) openBuy(p, s, page);
    }

    private void reopenSell(ServerPlayer p, String id, int page) {
        ShopStore.Stall s = stalls().current(id);
        if (s != null && p.getUUID().equals(s.owner) && stalls().near(p, s)) openSell(p, s, page);
    }

    /** The stall view this player has open, or null. */
    private static View viewOf(ServerPlayer p) {
        Gui g = Gui.of(p);
        return g != null && g.attachment() instanceof View v ? v : null;
    }

    // ------------------------------------------------------------------ keeping open screens current

    /** The views of a stall that are open right now. */
    private List<Map.Entry<ServerPlayer, View>> viewers(String stallId) {
        List<Map.Entry<ServerPlayer, View>> out = new ArrayList<>();
        for (ServerPlayer p : shop.plugin.onlinePlayers()) {
            View v = viewOf(p);
            if (v != null && v.stall.equals(stallId)) out.add(Map.entry(p, v));
        }
        return out;
    }

    /** Something changed on a stall: every open screen of it shows the new state (same slots). */
    void refresh(String stallId) {
        for (Map.Entry<ServerPlayer, View> e : viewers(stallId)) {
            ShopStore.Stall s = stalls().current(stallId);
            if (s == null || !s.rented()) {
                e.getKey().closeContainer();
                continue;
            }
            render(e.getKey(), e.getValue(), s);
        }
    }

    /** The rental ended or the stall went: its screens close. */
    void closeAll(String stallId) {
        for (Map.Entry<ServerPlayer, View> e : viewers(stallId)) e.getKey().closeContainer();
    }

    void closeEverything() {
        for (ServerPlayer p : shop.plugin.onlinePlayers()) if (viewOf(p) != null) p.closeContainer();
    }

    /** After the viewer's own action: slots are laid out afresh (sold-out panes go). */
    private void relayout(ServerPlayer p, View v) {
        if (viewOf(p) != v) return;
        ShopStore.Stall s = stalls().current(v.stall);
        if (s == null || !s.rented()) {
            p.closeContainer();
            return;
        }
        layout(v, s);
        render(p, v, s);
    }

    private void layout(View v, ShopStore.Stall s) {
        v.layout.clear();
        if (v.kind == Kind.LOOK) return;
        int pages = pages(s);
        v.page = Math.max(0, Math.min(v.page, pages - 1));
        for (int i = v.page * PER_PAGE; i < Math.min(s.listings.size(), (v.page + 1) * PER_PAGE); i++) v.layout.add(s.listings.get(i).id);
    }

    private static int pages(ShopStore.Stall s) {
        return Math.max(1, (s.listings.size() + PER_PAGE - 1) / PER_PAGE);
    }

    private void render(ServerPlayer p, View v, ShopStore.Stall s) {
        v.gui.clear();
        switch (v.kind) {
            case BUY -> renderBuy(p, v, s);
            case SELL -> renderSell(p, v, s);
            case LOOK -> renderLook(v, s);
        }
    }

    /** A slot whose click runs {@code action} (left/right, with or without shift), one waiting click per player. */
    private void action(View v, int slot, ItemStack item, BiConsumer<ServerPlayer, Gui.ClickKind> action) {
        v.gui.set(slot, item, c -> {
            Gui.ClickKind k = c.kind();
            if (k != Gui.ClickKind.LEFT && k != Gui.ClickKind.RIGHT && k != Gui.ClickKind.SHIFT_LEFT && k != Gui.ClickKind.SHIFT_RIGHT) return;
            text().sound(c.player(), "click");
            later(c.player(), () -> action.accept(c.player(), k));
        });
    }

    /** Listings that came in since the layout was made fill free slots on the last page. */
    private void growLayout(View v, ShopStore.Stall s) {
        if (v.page != pages(s) - 1 && !v.layout.isEmpty()) return;
        Set<Long> shown = new HashSet<>(v.layout);
        int skip = v.page * PER_PAGE;
        for (int i = skip; i < s.listings.size() && v.layout.size() < PER_PAGE; i++) {
            long id = s.listings.get(i).id;
            if (!shown.contains(id)) v.layout.add(id);
        }
    }

    // ------------------------------------------------------------------ buying

    private void renderBuy(ServerPlayer p, View v, ShopStore.Stall s) {
        growLayout(v, s);
        Gui inv = v.gui;
        long balance = shop.balance(p);
        for (int slot = 0; slot < v.layout.size(); slot++) {
            long lid = v.layout.get(slot);
            ShopStore.Listing l = s.listing(lid);
            ItemStack proto = l == null ? null : l.prototype();
            if (l == null || proto == null || l.stock <= 0) {
                inv.set(slot, icon(Items.STAINED_GLASS_PANE.gray(), "shop.stall.gui.sold-out", null));
                continue;
            }
            // a price that went up keeps the old quote, so the next click is refused once instead of paying more
            Long quote = v.quoted.get(lid);
            if (quote == null || l.price < quote) v.quoted.put(lid, l.price);
            int stack = Math.max(1, Math.min(l.stock, proto.getMaxStackSize()));
            List<Component> lore = new ArrayList<>(text().lines("shop.stall.gui.buy-lore", shop.n("price", l.price), Text.unparsed("stock", l.stock),
                    Text.unparsed("stack", stack)));
            if (shop.keepers.confirmAbove > 0 && l.price * (long) stack >= shop.keepers.confirmAbove) {
                lore.addAll(text().lines("shop.stall.gui.buy-lore-confirm", shop.n("confirm", shop.keepers.confirmAbove)));
            }
            if (balance < l.price) lore.addAll(text().lines("shop.stall.gui.buy-lore-poor", shop.n("balance", balance)));
            action(v, slot, preview(proto, l.stock, lore), (pl, click) -> buyClick(pl, v, lid, click));
        }
        if (v.layout.isEmpty()) inv.set(4, icon(Items.OAK_SIGN, "shop.stall.gui.empty", "shop.stall.gui.empty-lore", owner(s)));
        nav(v, s);
        inv.set(20, head(s, "shop.stall.gui.owner", "shop.stall.gui.owner-lore", owner(s), Text.unparsed("name", s.name),
                Text.unparsed("count", s.listings.size())));
        var bank = shop.bank();
        ItemStack coin = bank == null ? null : bank.coin(1);
        ItemStack bal = coin != null && !coin.isEmpty() ? styled(coin, "shop.stall.gui.balance", "shop.stall.gui.balance-lore", shop.n("balance", balance))
                : icon(Items.GOLD_NUGGET, "shop.stall.gui.balance", "shop.stall.gui.balance-lore", shop.n("balance", balance));
        inv.set(22, bal);
        action(v, 24, icon(Items.BARRIER, "gui.nav.close", null), (pl, click) -> pl.closeContainer());
    }

    private void buyClick(ServerPlayer p, View v, long lid, Gui.ClickKind click) {
        ShopStore.Stall s = stalls().current(v.stall);
        ShopStore.Listing l = s == null ? null : s.listing(lid);
        ItemStack proto = l == null ? null : l.prototype();
        if (s == null || !s.rented()) {
            shop.error(p, "shop.stall.closed-now");
            p.closeContainer();
            return;
        }
        if (l == null || proto == null || l.stock <= 0) {
            shop.error(p, "shop.stall.listing-gone");
            relayout(p, v);
            return;
        }
        long quoted = v.quoted.getOrDefault(lid, l.price);
        if (l.price > quoted) {
            shop.error(p, "shop.store.price-changed", shop.n("price", l.price), shop.n("was", quoted));
            v.quoted.put(lid, l.price);
            render(p, v, s);
            return;
        }
        if (click == Gui.ClickKind.RIGHT) {
            p.closeContainer();
            openAmount(p, v, lid, quoted);
            return;
        }
        boolean shift = click == Gui.ClickKind.SHIFT_LEFT || click == Gui.ClickKind.SHIFT_RIGHT;
        int qty = shift ? Math.max(1, Math.min(l.stock, proto.getMaxStackSize())) : 1;
        if (shop.keepers.confirmAbove > 0 && l.price * (long) qty >= shop.keepers.confirmAbove) {
            p.closeContainer();
            openConfirm(p, v, lid, quoted, qty);
            return;
        }
        stalls().buy(p, s.id, lid, quoted, String.valueOf(qty));
        relayout(p, v);
    }

    /** Big purchases ask first; the button re-checks everything (price at most what this showed). */
    private void openConfirm(ServerPlayer p, View v, long lid, long quoted, int qty) {
        ShopStore.Stall s = stalls().current(v.stall);
        ShopStore.Listing l = s == null ? null : s.listing(lid);
        ItemStack proto = l == null ? null : l.prototype();
        if (proto == null) {
            shop.error(p, "shop.stall.listing-gone");
            return;
        }
        final String id = s.id;
        final int page = v.page;
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.gui.confirm-title"));
        menu.item(proto.copyWithCount(Math.max(1, Math.min(qty, proto.getMaxStackSize()))), text().msg("shop.stall.gui.confirm-body",
                Text.unparsed("count", qty), Text.component("item", ShopModule.nameOf(proto)), shop.n("price", l.price * qty), owner(s),
                shop.n("balance", shop.balance(p))), 250);
        menu.button(text().msg("shop.stall.gui.confirm-yes", shop.n("price", l.price * qty)), null, 150, pl -> {
            stalls().buy(pl, id, lid, quoted, String.valueOf(qty));
            reopenBuy(pl, id, page);
        });
        menu.button(text().msg("menu.back"), null, 150, pl -> reopenBuy(pl, id, page));
        menu.columns(2);
        menu.open(p);
    }

    /** Right-click: type how many. */
    private void openAmount(ServerPlayer p, View v, long lid, long quoted) {
        ShopStore.Stall s = stalls().current(v.stall);
        ShopStore.Listing l = s == null ? null : s.listing(lid);
        ItemStack proto = l == null ? null : l.prototype();
        if (proto == null) {
            shop.error(p, "shop.stall.listing-gone");
            return;
        }
        final String id = s.id;
        final int page = v.page;
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.buy-title"));
        menu.item(proto, text().msg("shop.stall.buy-body", Text.component("item", ShopModule.nameOf(proto)), Text.unparsed("stock", l.stock),
                shop.n("price", l.price), shop.n("balance", shop.balance(p))), 250);
        menu.input(Dialogs.Input.text("amount", text().msg("shop.stall.amount")).initial("1").maxLength(7).width(120).build());
        menu.button(text().msg("shop.stall.buy-confirm"), null, 150, (pl, view) ->
                stalls().buyOrConfirm(pl, id, lid, quoted, view == null ? null : view.getText("amount"), pp -> reopenBuy(pp, id, page)));
        menu.button(text().msg("menu.back"), null, 150, pl -> reopenBuy(pl, id, page));
        menu.columns(2);
        menu.open(p);
    }

    // ------------------------------------------------------------------ selling

    private void renderSell(ServerPlayer p, View v, ShopStore.Stall s) {
        growLayout(v, s);
        Gui inv = v.gui;
        for (int slot = 0; slot < v.layout.size(); slot++) {
            long lid = v.layout.get(slot);
            ShopStore.Listing l = s.listing(lid);
            ItemStack proto = l == null ? null : l.prototype();
            if (l == null || proto == null || l.stock <= 0) {
                inv.set(slot, icon(Items.STAINED_GLASS_PANE.gray(), "shop.stall.gui.sold-out", null));
                continue;
            }
            int stack = Math.max(1, Math.min(l.stock, proto.getMaxStackSize()));
            action(v, slot, preview(proto, l.stock, text().lines("shop.stall.gui.sell-lore", shop.n("price", l.price), Text.unparsed("stock", l.stock),
                    Text.unparsed("stack", stack))), (pl, click) -> sellClick(pl, v, lid, click));
        }
        if (v.layout.isEmpty()) inv.set(4, icon(Items.OAK_SIGN, "shop.stall.gui.sell-empty", "shop.stall.gui.sell-empty-lore"));
        nav(v, s);
        final String id = s.id;
        action(v, 19, icon(Items.CLOCK, "shop.stall.gui.rent", "shop.stall.gui.rent-lore", Text.unparsed("time", ShopModule.until(s.rentUntil)),
                shop.n("rent", stalls().rent), Text.unparsed("weeks", stalls().maxWeeksAhead)), (pl, click) -> {
            stalls().renew(pl, id);
            relayout(pl, v);
        });
        List<String> waiting = shop.store.returns.get(p.getUUID());
        int w = waiting == null ? 0 : waiting.size();
        ItemStack collect = icon(w > 0 ? Items.CHEST : Items.BARREL, w > 0 ? "shop.stall.gui.collect" : "shop.stall.gui.collect-none",
                w > 0 ? "shop.stall.gui.collect-lore" : "shop.stall.gui.collect-none-lore", Text.unparsed("count", w));
        if (w > 0) {
            action(v, 20, collect, (pl, click) -> {
                stalls().collect(pl);
                relayout(pl, v);
            });
        } else {
            inv.set(20, collect);
        }
        inv.set(21, icon(Items.OAK_HANGING_SIGN, "shop.stall.gui.how", "shop.stall.gui.how-lore", Text.unparsed("count", s.listings.size()),
                Text.unparsed("max", stalls().maxListings)));
        inv.set(22, head(s, "shop.stall.gui.sell-owner", "shop.stall.gui.sell-owner-lore", owner(s), Text.unparsed("name", s.name),
                Text.unparsed("count", s.listings.size()), Text.unparsed("max", stalls().maxListings)));
        action(v, 23, icon(Items.VILLAGER_SPAWN_EGG, "shop.stall.gui.look", "shop.stall.gui.look-lore"), (pl, click) -> {
            ShopStore.Stall now = stalls().current(id);
            if (now != null) openLook(pl, now);
        });
        if (s.hasKeeper() && !s.keeperStaff) {
            action(v, 24, icon(Items.LEAD, "shop.stall.gui.pickup", "shop.stall.gui.pickup-lore"), (pl, click) -> {
                ShopStore.Stall now = stalls().ownedHere(pl, id);
                if (now != null) shop.keepers.pickup(pl, now);
                relayout(pl, v);
            });
        } else if (!s.hasKeeper()) {
            action(v, 24, icon(Items.PAPER, "shop.stall.gui.token", "shop.stall.gui.token-lore", Text.unparsed("distance", (int) shop.keepers.distance)),
                    (pl, click) -> {
                        ShopStore.Stall now = stalls().ownedHere(pl, id);
                        if (now != null && shop.keepers.giveToken(pl, now, false)) text().send(pl, "shop.stall.keeper.given", Text.unparsed("name", now.name),
                                Text.unparsed("distance", (int) shop.keepers.distance));
                    });
        }
        action(v, 25, icon(Items.DYE.red(), "shop.stall.gui.leave", "shop.stall.gui.leave-lore"), (pl, click) -> {
            pl.closeContainer();
            stalls().confirmLeave(pl, id, pp -> reopenSell(pp, id, v.page));
        });
    }

    private void sellClick(ServerPlayer p, View v, long lid, Gui.ClickKind click) {
        ShopStore.Stall s = stalls().ownedHere(p, v.stall);
        if (s == null) {
            p.closeContainer();
            return;
        }
        ShopStore.Listing l = s.listing(lid);
        ItemStack proto = l == null ? null : l.prototype();
        if (l == null || proto == null) {
            shop.error(p, "shop.stall.listing-gone");
            relayout(p, v);
            return;
        }
        final String id = s.id;
        final int page = v.page;
        if (click == Gui.ClickKind.LEFT) {
            p.closeContainer();
            stalls().openPrice(p, id, lid, pl -> reopenSell(pl, id, page));
            return;
        }
        boolean shift = click == Gui.ClickKind.SHIFT_LEFT || click == Gui.ClickKind.SHIFT_RIGHT;
        int limit = shift ? Integer.MAX_VALUE : Math.max(1, proto.getMaxStackSize());
        stalls().takeBack(p, id, lid, limit);
        relayout(p, v);
    }

    /** A click in the player's own inventory: on the selling screen, a plain click on a stack offers it for sale. */
    private void bottomClick(Gui.Click c, View v) {
        if (v.kind != Kind.SELL) return;
        if (c.kind() != Gui.ClickKind.LEFT && c.kind() != Gui.ClickKind.RIGHT) return;
        int r = c.slot() - c.gui().size();
        if (r < 0 || r >= 36) return;
        // the menu lists the 27 main slots (inventory 9-35) then the hotbar (0-8)
        int slot = r < 27 ? r + 9 : r - 27;
        ServerPlayer p = c.player();
        ItemStack item = p.getInventory().getItem(slot);
        if (item.isEmpty()) return;
        ItemStack snapshot = item.copy();
        text().sound(p, "click");
        later(p, () -> addFromSlot(p, v, slot, snapshot));
    }

    /** The owner clicked a stack in their own inventory: a price form for it (the stack stays put until confirmed). */
    private void addFromSlot(ServerPlayer p, View v, int slot, ItemStack snapshot) {
        ShopStore.Stall s = stalls().ownedHere(p, v.stall);
        if (s == null) return;
        if (shop.keepers.isToken(snapshot) || isPreview(snapshot)) {
            shop.error(p, "shop.stall.keeper.cant-sell-token");
            return;
        }
        ShopStore.Listing same = stalls().sameListing(s, snapshot);
        if (same == null && s.listings.size() >= stalls().maxListings) {
            shop.error(p, "shop.stall.full", Text.unparsed("max", stalls().maxListings));
            return;
        }
        final String id = s.id;
        final int page = v.page;
        p.closeContainer();
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.stall.add-title"));
        menu.item(snapshot, text().msg(same == null ? "shop.stall.gui.add-body" : "shop.stall.gui.add-body-more",
                Text.component("item", ShopModule.nameOf(snapshot)), Text.unparsed("count", snapshot.getCount()), shop.n("max", stalls().maxPrice),
                Text.unparsed("stock", same == null ? 0 : same.stock), shop.n("price", same == null ? 0 : same.price)), 250);
        menu.input(Dialogs.Input.text("price", text().msg("shop.stall.price")).initial(same == null ? "" : String.valueOf(same.price)).maxLength(12).width(150).build());
        menu.button(text().msg("shop.stall.add-confirm"), null, 150, (pl, view) -> {
            stalls().addFrom(pl, id, slot, snapshot, view == null ? null : view.getText("price"), "shop.stall.gui.slot-changed");
            reopenSell(pl, id, page);
        });
        menu.button(text().msg("menu.back"), null, 150, pl -> reopenSell(pl, id, page));
        menu.columns(2);
        menu.open(p);
    }

    // ------------------------------------------------------------------ the keeper's look

    private static final Map<String, Item> TYPE_ICON = Map.of("plains", Items.GRASS_BLOCK, "desert", Items.SAND, "jungle", Items.JUNGLE_LOG,
            "savanna", Items.ACACIA_LOG, "snow", Items.SNOW_BLOCK, "swamp", Items.LILY_PAD, "taiga", Items.SPRUCE_LOG);
    private static final Map<String, Item> JOB_ICON = Map.ofEntries(Map.entry("none", Items.WOOL.brown()), Map.entry("armorer", Items.BLAST_FURNACE),
            Map.entry("butcher", Items.SMOKER), Map.entry("cartographer", Items.CARTOGRAPHY_TABLE), Map.entry("cleric", Items.BREWING_STAND),
            Map.entry("farmer", Items.COMPOSTER), Map.entry("fisherman", Items.BARREL), Map.entry("fletcher", Items.FLETCHING_TABLE),
            Map.entry("leatherworker", Items.CAULDRON), Map.entry("librarian", Items.LECTERN), Map.entry("mason", Items.STONECUTTER),
            Map.entry("nitwit", Items.WOOL.green()), Map.entry("shepherd", Items.LOOM), Map.entry("toolsmith", Items.SMITHING_TABLE),
            Map.entry("weaponsmith", Items.GRINDSTONE));
    private static final Map<String, String> TYPE_NAME = Map.of("plains", "biome.minecraft.plains", "desert", "biome.minecraft.desert", "jungle",
            "biome.minecraft.jungle", "savanna", "biome.minecraft.savanna", "snow", "biome.minecraft.snowy_plains", "swamp", "biome.minecraft.swamp", "taiga",
            "biome.minecraft.taiga");

    private void renderLook(View v, ShopStore.Stall s) {
        String type = shop.keepers.typeOf(s), job = shop.keepers.jobOf(s);
        final String id = s.id;
        List<String> types = Keepers.types();
        for (int i = 0; i < types.size(); i++) {
            String t = types.get(i);
            boolean on = t.equals(type);
            ItemStack it = icon(TYPE_ICON.getOrDefault(t, Items.GRASS_BLOCK), null, on ? "shop.stall.look.selected" : "shop.stall.look.pick-type");
            Icons.name(it, text().item("<text><n>", Text.component("n", Component.translatable(TYPE_NAME.getOrDefault(t, "biome.minecraft.plains")))));
            if (on) it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            action(v, 1 + i, it, (pl, click) -> look(pl, v, id, t, null));
        }
        List<String> jobs = Keepers.jobs();
        for (int i = 0; i < jobs.size(); i++) {
            String j = jobs.get(i);
            boolean on = j.equals(job);
            ItemStack it = icon(JOB_ICON.getOrDefault(j, Items.WOOL.brown()), null, on ? "shop.stall.look.selected" : "shop.stall.look.pick-job");
            Component n = j.equals("none") ? text().msg("shop.stall.look.no-job") : Component.translatable("entity.minecraft.villager." + j);
            Icons.name(it, text().item("<text><n>", Text.component("n", n)));
            if (on) it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            action(v, 9 + i, it, (pl, click) -> look(pl, v, id, null, j));
        }
        // Top left, where the recipe editor's Back sits (the jobs fill 9-23): slot 26 only ever means Next.
        action(v, 0, icon(Items.FEATHER, "shop.stall.gui.back", null), (pl, click) -> reopenSell(pl, id, 0));
    }

    private void look(ServerPlayer p, View v, String id, String type, String job) {
        ShopStore.Stall s = stalls().ownedHere(p, id);
        if (s == null) {
            p.closeContainer();
            return;
        }
        if (shop.keepers.setLook(p, s, type, job)) {
            text().sound(p, "click");
            render(p, v, s);
        }
    }

    // ------------------------------------------------------------------ shared pieces

    private void nav(View v, ShopStore.Stall s) {
        int pages = pages(s);
        final String id = s.id;
        if (v.page > 0) {
            action(v, 18, icon(Items.ARROW, "gui.nav.previous", "gui.nav.page", Text.unparsed("page", v.page), Text.unparsed("pages", pages)),
                    (pl, click) -> page(pl, v, id, v.page - 1));
        }
        if (v.page < pages - 1) {
            action(v, 26, icon(Items.ARROW, "gui.nav.next", "gui.nav.page", Text.unparsed("page", v.page + 2), Text.unparsed("pages", pages)),
                    (pl, click) -> page(pl, v, id, v.page + 1));
        }
    }

    private void page(ServerPlayer p, View v, String id, int page) {
        ShopStore.Stall s = stalls().current(id);
        if (s == null) {
            p.closeContainer();
            return;
        }
        v.page = page;
        layout(v, s);
        render(p, v, s);
    }

    private static void markPreview(ItemStack it) {
        Pdc.itemPut(it, PREVIEW_KEY, ByteTag.valueOf((byte) 1));
    }

    /** A copy of a listing's item as it really is (enchantments, lore, custom data), with the stall's lines under it. */
    private ItemStack preview(ItemStack proto, int stock, List<Component> extra) {
        ItemStack it = proto.copyWithCount(Math.max(1, Math.min(stock, proto.getMaxStackSize())));
        List<net.minecraft.network.chat.Component> lore = new ArrayList<>(it.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        for (Component c : extra) lore.add(Adv.vanilla(c));
        it.set(DataComponents.LORE, new ItemLore(lore));
        markPreview(it);
        return it;
    }

    private ItemStack icon(Item material, String nameKey, String loreKey, TagResolver... r) {
        Icons.Icon i = Icons.icon(material).flags();
        if (nameKey != null) i.name(text().itemMsg(nameKey, r));
        if (loreKey != null) i.lore(text().lines(loreKey, r));
        ItemStack stack = i.build();
        markPreview(stack);
        return stack;
    }

    private ItemStack styled(ItemStack base, String nameKey, String loreKey, TagResolver... r) {
        ItemStack stack = Icons.icon(base.copyWithCount(1)).flags().name(text().itemMsg(nameKey, r)).lore(text().lines(loreKey, r)).build();
        markPreview(stack);
        return stack;
    }

    private ItemStack head(ShopStore.Stall s, String nameKey, String loreKey, TagResolver... r) {
        ServerPlayer online = shop.online(s.owner);
        ItemStack it = s.owner == null ? new ItemStack(Items.PLAYER_HEAD) : Icons.head(s.owner, online);
        it = Icons.icon(it).flags().name(text().itemMsg(nameKey, r)).lore(text().lines(loreKey, r)).build();
        markPreview(it);
        return it;
    }

    boolean isPreview(ItemStack it) {
        return it != null && !it.isEmpty() && Pdc.item(it).contains(PREVIEW_KEY);
    }

    /** Belt and braces: a screen copy that somehow reached a player is taken away again. */
    void sweep(ServerPlayer p) {
        var inv = p.getInventory();
        boolean found = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isPreview(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
                found = true;
            }
        }
        if (isPreview(p.containerMenu.getCarried())) {
            p.containerMenu.setCarried(ItemStack.EMPTY);
            found = true;
        }
        if (found) {
            shop.store.log("STALL-PREVIEW-SWEPT from " + Names.name(p) + " " + p.getUUID());
            shop.plugin.getLogger().warn("Took a stall screen copy away from {} (it should never leave the screen).", Names.name(p));
        }
    }

    // ------------------------------------------------------------------ clicks

    /** One waiting click per player; it runs after the current click (opening and closing inside a click handler is unsafe). */
    private void later(ServerPlayer p, Runnable r) {
        UUID id = p.getUUID();
        if (!pending.add(id)) return;
        Tasks.sync(() -> {
            pending.remove(id);
            if (!p.hasDisconnected()) r.run();
        });
    }
}
