package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The General Store: categories (Seeds, Bait &amp; Rods, Farming, Cosmetics) and this week's featured items at a
 * discount. Everything is paid from the bank balance. A purchase re-checks the item, its price (never more than the
 * screen showed), the balance and inventory room at the click, then takes the coins and hands the items over.
 */
final class StoreScreen {
    private static final int W = 150;
    private final ShopModule shop;

    StoreScreen(ShopModule shop) {
        this.shop = shop;
    }

    private Text text() {
        return shop.text();
    }

    // ------------------------------------------------------------------ catalog

    /** The entry for an id as the store sells it right now, or null. */
    ShopModule.Entry entry(String id) {
        for (List<ShopModule.Entry> list : shop.categories.values()) {
            for (ShopModule.Entry e : list) if (e.id().equals(id)) return e;
        }
        return null;
    }

    /** This week's featured items: a fixed pick per week (seeded by the week), from what exists right now. */
    List<ShopModule.Entry> featured() {
        List<ShopModule.Entry> pool = new ArrayList<>();
        for (List<ShopModule.Entry> list : shop.categories.values()) {
            for (ShopModule.Entry e : list) if (e.featurable() && shop.available(e.id())) pool.add(e);
        }
        pool.sort(Comparator.comparing(ShopModule.Entry::id));
        Random r = new Random(shop.thisWeek() * 31L + 0x5EED);
        List<ShopModule.Entry> out = new ArrayList<>();
        while (!pool.isEmpty() && out.size() < shop.featuredCount) out.add(pool.remove(r.nextInt(pool.size())));
        return out;
    }

    boolean isFeatured(ShopModule.Entry e) {
        for (ShopModule.Entry f : featured()) if (f.id().equals(e.id())) return true;
        return false;
    }

    /** What one bundle costs now (featured items are discounted, never below 1). */
    long price(ShopModule.Entry e) {
        if (shop.featuredDiscount <= 0 || !isFeatured(e)) return e.price();
        return Math.max(1, e.price() * (100 - shop.featuredDiscount) / 100);
    }

    // ------------------------------------------------------------------ screens

    void open(ServerPlayer p) {
        if (!Perms.has(p, ShopModule.PERM)) {
            shop.error(p, "general.no-permission");
            return;
        }
        if (shop.closed(p)) return;
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.store.title"));
        menu.text(text().msg("shop.store.info", shop.n("balance", shop.balance(p)), Text.unparsed("reset", ShopModule.until(shop.nextWeek()))), 300);
        List<ShopModule.Entry> feat = featured();
        if (!feat.isEmpty()) {
            menu.text(text().msg("shop.store.featured-header", Text.unparsed("percent", shop.featuredDiscount)), 300);
            for (ShopModule.Entry e : feat) {
                ItemStack icon = shop.make(e.id());
                if (icon == null) continue;
                menu.button(text().msg("shop.store.featured-button", Text.component("item", ShopModule.nameOf(icon)), Text.unparsed("amount", e.amount()),
                                shop.n("price", price(e)), shop.n("was", e.price())),
                        text().msg("shop.store.buy-tooltip"), W, pl -> openBuy(pl, e.id(), true));
            }
        }
        for (String cat : shop.categories.keySet()) {
            int n = 0;
            for (ShopModule.Entry e : shop.categories.get(cat)) if (shop.available(e.id())) n++;
            menu.button(text().msg("shop.store.category." + cat), text().msg("shop.store.category-tooltip", Text.unparsed("count", n)), W,
                    pl -> openCategory(pl, cat));
        }
        if (shop.bank() != null) menu.button(text().msg("shop.store.bank"), text().msg("shop.store.bank-tooltip"), W, pl -> {
            Hooks.Bank bank = shop.bank();
            if (bank != null) bank.openScreen(pl);
        });
        menu.columns(2);
        shop.plugin.menuExit(menu, p);
        menu.open(p);
        text().sound(p, "menu-open");
    }

    /** /shop featured: the same screen (the featured items are at the top). */
    void openFeatured(ServerPlayer p) {
        open(p);
    }

    void openCategory(ServerPlayer p, String cat) {
        if (shop.closed(p)) return;
        List<ShopModule.Entry> list = shop.categories.get(cat);
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.store.category-title", Text.component("category", text().msg("shop.store.category." + cat))));
        menu.text(text().msg("shop.store.category-body", shop.n("balance", shop.balance(p))), 300);
        int shown = 0;
        if (list != null) {
            for (ShopModule.Entry e : list) {
                ItemStack icon = shop.make(e.id());
                if (icon == null) continue;
                shown++;
                long price = price(e);
                String key = price < e.price() ? "shop.store.item-sale" : "shop.store.item";
                menu.button(text().msg(key, Text.component("item", ShopModule.nameOf(icon)), Text.unparsed("amount", e.amount()),
                        shop.n("price", price), shop.n("was", e.price())), text().msg("shop.store.buy-tooltip"), W, pl -> openBuy(pl, e.id(), false));
            }
        }
        if (shown == 0) menu.text(text().msg("shop.store.category-empty"), 300);
        menu.button(text().msg("menu.back"), null, W, this::open);
        menu.columns(2);
        menu.open(p);
    }

    /** One item: what it is, the price, how many bundles; Buy re-checks everything. */
    void openBuy(ServerPlayer p, String id, boolean fromFeatured) {
        if (shop.closed(p)) return;
        ShopModule.Entry e = entry(id);
        ItemStack icon = e == null ? null : shop.make(e.id());
        if (icon == null) {
            shop.error(p, "shop.store.gone");
            return;
        }
        long price = price(e);
        long balance = shop.balance(p);
        Dialogs.Menu menu = shop.dialogs.menu(text().msg("shop.store.buy-title"));
        menu.item(icon.copyWithCount(Math.min(icon.getMaxStackSize(), e.amount())), text().msg("shop.store.buy-body",
                Text.component("item", ShopModule.nameOf(icon)), Text.unparsed("amount", e.amount()), shop.n("price", price),
                shop.n("balance", balance), Text.unparsed("max", shop.maxBundles)), 250);
        menu.input(Dialogs.Input.text("bundles", text().msg("shop.store.bundles")).initial("1").maxLength(6).width(120).build());
        final long quoted = price;
        menu.button(text().msg("shop.store.buy-confirm"), null, W, (pl, view) -> {
            String raw = view == null ? null : view.getText("bundles");
            if (buy(pl, id, quoted, raw)) open(pl);
        });
        menu.button(text().msg("menu.back"), null, W, pl -> {
            if (fromFeatured) open(pl);
            else openCategory(pl, e.category());
        });
        menu.columns(2);
        menu.open(p);
    }

    /**
     * Buys {@code raw} bundles of {@code id} at no more than {@code quoted} each. Every check happens now: the item is
     * still sold and exists, the price, the number, the inventory room and the balance (taken in one step by the bank).
     */
    boolean buy(ServerPlayer p, String id, long quoted, String raw) {
        if (!Perms.has(p, ShopModule.PERM)) {
            shop.error(p, "general.no-permission");
            return false;
        }
        if (shop.closed(p)) return false;
        Hooks.Bank bank = shop.bank();
        if (bank.robbing(p)) {
            shop.error(p, "bank.robbery.no-banking");
            return false;
        }
        ShopModule.Entry e = entry(id);
        ItemStack proto = e == null ? null : shop.make(e.id());
        if (proto == null) {
            shop.error(p, "shop.store.gone");
            return false;
        }
        long price = price(e);
        if (price > quoted) {
            shop.error(p, "shop.store.price-changed", shop.n("price", price), shop.n("was", quoted));
            return false;
        }
        long bundles = ShopModule.parseAmount(raw, shop.maxBundles);
        if (bundles <= 0 || bundles > shop.maxBundles) {
            shop.error(p, "shop.bad-amount", Text.unparsed("max", shop.maxBundles));
            return false;
        }
        long total, count;
        try {
            total = Math.multiplyExact(price, bundles);
            count = Math.multiplyExact((long) e.amount(), bundles);
        } catch (ArithmeticException ex) {
            shop.error(p, "shop.bad-amount", Text.unparsed("max", shop.maxBundles));
            return false;
        }
        long balance = bank.balance(p.getUUID());
        if (balance < total) {
            shop.error(p, "shop.not-enough", shop.n("price", total), shop.n("balance", balance));
            return false;
        }
        List<ItemStack> items = ShopModule.stacks(proto, count);
        if (!ShopModule.fits(p, items)) {
            shop.error(p, "shop.no-room");
            return false;
        }
        long after = bank.debit(p.getUUID(), p.getScoreboardName(), total, "SHOP", bundles + "x " + e.amount() + " " + e.id());
        if (after < 0) {
            shop.error(p, "shop.not-enough", shop.n("price", total), shop.n("balance", bank.balance(p.getUUID())));
            return false;
        }
        ShopModule.give(p, items);
        ShopModule.saveInventory(p);
        shop.store.log("STORE " + p.getScoreboardName() + " " + p.getUUID() + " bought " + count + " " + e.id() + " for " + total + " balance=" + after);
        Component name = ShopModule.nameOf(proto);
        text().send(p, "shop.store.bought", Text.unparsed("count", ShopModule.fmt(count)), Text.component("item", name), shop.n("price", total),
                shop.n("balance", after));
        text().sound(p, "accepted");
        return true;
    }
}
