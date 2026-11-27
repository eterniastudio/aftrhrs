package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Selling back at the counters: the fishmonger buys custom fish and shellfish, the grocer crops and meals, the mining
 * store ores, gems and mining blocks. Right-click a counter or its keeper ({@link CounterKeeper}), or {@code /shop sell}
 * near a counter, to open a screen listing what the player carries that this counter buys, with today's prices; nothing
 * is sold until a button is clicked. A sale re-reads the inventory and the prices at the click, and needs the player
 * still within {@code counter-distance} of the counter (or keeper).
 *
 * <p>Price of one item: base by kind ({@code sell.fish.<rarity>}, {@code shellfish}, {@code crop} x quality,
 * {@code meal}, {@code overrides}, or the fixed {@code grocer-goods} and {@code mining} lists), times today's factor {@code max(floor, 1 - drop * units sold today)} for that
 * item id, server-wide; every unit in a sale moves the factor. Sums are kept in hundredths and rounded down once.
 * Prize crops never go in "Sell All" (they are for the Harvest Fair) but can be sold on their own.
 */
final class SellBack {
    enum Counter {
        FISHMONGER("fishmonger"), GROCER("grocer"), MINER("miner");

        final String key;

        Counter(String key) {
            this.key = key;
        }
    }

    enum Kind {FISH, SHELLFISH, CROP, MEAL, GOODS, MINERAL}

    /** Where a sale happens: a counter block or a keeper. The player has to stay near it. */
    interface Booth {
        boolean valid();

        Loc at();

        static Booth of(CounterKeeper k) {
            return new Booth() {
                @Override
                public boolean valid() {
                    return k.isAlive() && !k.isRemoved();
                }

                @Override
                public Loc at() {
                    return Loc.of(k);
                }
            };
        }
    }

    /** What one stack is to a counter: its item id, grade (rarity/quality) and base price in hundredths. */
    record Sellable(String id, Kind kind, String grade, long baseCents) {
        String group() {
            return id + "|" + grade;
        }
    }

    private final ShopModule shop;
    double counterDistance = 6;
    final Map<String, Long> fishPrice = new HashMap<>();
    long shellfishCents = 300;
    long cropCents = 100;
    long mealCents = 600;
    /** Crop quality multipliers in millionths (1.5 = 1_500_000). */
    final Map<String, Long> quality = new HashMap<>();
    final Map<String, String> fishRarity = new HashMap<>();
    final Map<String, Long> overrides = new HashMap<>();
    /** Other things the grocer buys at a fixed price, by item id (sell.grocer-goods: wildlife products and meals). */
    final Map<String, Long> grocerGoods = new HashMap<>();
    /** What the mining store buys, coins for one item in hundredths by item id (sell.mining: vanilla ids bare). */
    final Map<String, Long> minerals = new HashMap<>();
    boolean dynamic = true;
    /** The daily drop per unit sold and the lowest factor, in millionths (whole-number maths, no rounding drift). */
    long dropPpm = 5_000;
    long floorPpm = 400_000;
    int maxRows = 12;

    SellBack(ShopModule shop) {
        this.shop = shop;
    }

    void reload(Yaml c) {
        fishPrice.clear();
        quality.clear();
        fishRarity.clear();
        overrides.clear();
        grocerGoods.clear();
        minerals.clear();
        fishPrice.put("common", 200L);
        fishPrice.put("uncommon", 600L);
        fishPrice.put("rare", 1800L);
        fishPrice.put("legendary", 8000L);
        quality.put("normal", 1_000_000L);
        quality.put("fine", 1_500_000L);
        quality.put("prize", 3_000_000L);
        if (c == null) return;
        counterDistance = Math.max(2, Math.min(32, c.getDouble("counter-distance", 6)));
        Yaml f = c.getConfigurationSection("fish");
        if (f != null) for (String k : f.getKeys(false)) fishPrice.put(k.toLowerCase(Locale.ROOT), cents(f.getDouble(k)));
        shellfishCents = cents(c.getDouble("shellfish", 3));
        cropCents = cents(c.getDouble("crop", 1));
        mealCents = cents(c.getDouble("meal", 6));
        Yaml q = c.getConfigurationSection("crop-quality");
        if (q != null) for (String k : q.getKeys(false)) quality.put(k.toLowerCase(Locale.ROOT), Math.round(Math.max(0, Math.min(1000, q.getDouble(k))) * 1_000_000));
        Yaml r = c.getConfigurationSection("fish-rarity");
        if (r != null) for (String k : r.getKeys(false)) fishRarity.put(k.toLowerCase(Locale.ROOT), String.valueOf(r.getString(k)).toLowerCase(Locale.ROOT));
        Yaml o = c.getConfigurationSection("overrides");
        if (o != null) for (String k : o.getKeys(false)) overrides.put(k.toLowerCase(Locale.ROOT), cents(o.getDouble(k)));
        Yaml g = c.getConfigurationSection("grocer-goods");
        if (g != null) for (String k : g.getKeys(false)) grocerGoods.put(k.toLowerCase(Locale.ROOT), cents(g.getDouble(k)));
        // a shop.yml from before the mining store has no list: the bundled one's prices, until the owner adds their own
        Yaml m = c.getConfigurationSection("mining");
        if (m == null) {
            Yaml bundled = net.axther.aftrhrs.core.Feather.bundled("shop.yml");
            m = bundled == null ? null : bundled.getConfigurationSection("sell.mining");
        }
        if (m != null) for (String k : m.getKeys(false)) minerals.put(k.toLowerCase(Locale.ROOT).replace("minecraft:", ""), cents(m.getDouble(k)));
        dynamic = c.getBoolean("dynamic.enabled", true);
        dropPpm = Math.round(Math.max(0, Math.min(100, c.getDouble("dynamic.drop-per-unit-percent", 0.5))) * 10_000);
        floorPpm = Math.round(Math.max(0, Math.min(100, c.getDouble("dynamic.floor-percent", 40))) * 10_000);
        maxRows = Math.max(1, Math.min(36, c.getInt("max-rows", 12)));
    }

    private static long cents(double coins) {
        return Math.max(0, Math.min(1_000_000_000_00L, Math.round(coins * 100)));
    }

    // ------------------------------------------------------------------ pricing

    /** What this stack is to a counter, or null if no counter buys it. */
    Sellable classify(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        String id = shop.idOf(s);
        if (id == null) {
            // vanilla items: only the mining store buys them, by their bare id (raw_iron, diamond...)
            Identifier key = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (!"minecraft".equals(key.getNamespace())) return null;
            Long price = minerals.get(key.getPath());
            return price == null ? null : new Sellable(key.getPath(), Kind.MINERAL, "", price);
        }
        Long override = overrides.get(id);
        if (id.startsWith("arcana_fish_")) {
            // Only real fish: a caught fish carries its rarity tag, else the species must be in sell.fish-rarity (or
            // priced in overrides). Other arcana_fish_* items (the fish scale furniture) aren't bought.
            String tagged = Pdc.itemString(s, ShopModule.FISH_RARITY);
            String species = fishRarity.get(id.substring("arcana_fish_".length()));
            if (tagged == null && species == null && override == null) return null;
            String rarity = tagged;
            if (rarity == null || !fishPrice.containsKey(rarity.toLowerCase(Locale.ROOT))) rarity = species != null ? species : "common";
            rarity = rarity.toLowerCase(Locale.ROOT);
            long base = override != null ? override : fishPrice.getOrDefault(rarity, fishPrice.getOrDefault("common", 200L));
            return new Sellable(id, Kind.FISH, rarity, base);
        }
        if (id.startsWith("arcana_shellfish_")) return new Sellable(id, Kind.SHELLFISH, "", override != null ? override : shellfishCents);
        if (id.startsWith("arcana_crop_")) {
            String q = Pdc.itemString(s, ShopModule.CROP_QUALITY);
            q = q == null || !quality.containsKey(q.toLowerCase(Locale.ROOT)) ? "normal" : q.toLowerCase(Locale.ROOT);
            long base = (override != null ? override : cropCents) * quality.get(q) / 1_000_000L;
            return new Sellable(id, Kind.CROP, q, base);
        }
        if (id.startsWith("arcana_meal_")) return new Sellable(id, Kind.MEAL, "", override != null ? override : mealCents);
        Long goods = grocerGoods.get(id);
        if (goods != null) return new Sellable(id, Kind.GOODS, "", override != null ? override : goods);
        Long mineral = minerals.get(id);
        if (mineral != null) return new Sellable(id, Kind.MINERAL, "", override != null ? override : mineral);
        return null;
    }

    static boolean buys(Counter c, Kind k) {
        return switch (c) {
            case FISHMONGER -> k == Kind.FISH || k == Kind.SHELLFISH;
            case GROCER -> k == Kind.CROP || k == Kind.MEAL || k == Kind.GOODS;
            case MINER -> k == Kind.MINERAL;
        };
    }

    /** Today's factor (millionths) for the next unit of an id after {@code soldBefore} were sold today. */
    long factorPpm(long soldBefore) {
        if (!dynamic) return 1_000_000L;
        long drop = soldBefore >= 1_000_000L ? 1_000_000L : Math.min(1_000_000L, dropPpm * soldBefore);
        return Math.max(floorPpm, 1_000_000L - drop);
    }

    /** Hundredths for {@code units} more of an item whose base is {@code baseCents}, after {@code sold} today (each unit rounded down). */
    long quoteCents(long baseCents, long sold, long units) {
        long total = 0;
        for (long i = 0; i < units; i++) {
            total = Math.addExact(total, Math.multiplyExact(baseCents, factorPpm(sold + i)) / 1_000_000L);
        }
        return total;
    }

    long sold(String id) {
        return shop.store.soldToday.getOrDefault(id, 0L);
    }

    /** Today's price of one more unit of {@code s}, in hundredths. */
    long unitCents(Sellable s) {
        return Math.multiplyExact(s.baseCents(), factorPpm(sold(s.id()))) / 1_000_000L;
    }

    static String coins(long cents) {
        if (cents % 100 == 0) return ShopModule.fmt(cents / 100);
        return String.format(Locale.ROOT, "%,.1f", cents / 100.0);
    }

    // ------------------------------------------------------------------ the counter screen

    /** What the player carries for this counter, by group (id + grade), in inventory order. */
    private Map<String, Group> scan(ServerPlayer p, Counter counter, Predicate<Sellable> which) {
        Map<String, Group> out = new LinkedHashMap<>();
        List<ItemStack> storage = ShopModule.storage(p);
        for (int i = 0; i < storage.size(); i++) {
            ItemStack it = storage.get(i);
            Sellable s = classify(it);
            if (s == null || !buys(counter, s.kind()) || !which.test(s)) continue;
            Group g = out.computeIfAbsent(s.group(), k -> new Group(s));
            if (g.sample == null) g.sample = it.copy();
            g.units += it.getCount();
        }
        return out;
    }

    private static final class Group {
        final Sellable what;
        ItemStack sample;
        long units;

        Group(Sellable what) {
            this.what = what;
        }
    }

    private Component gradeLabel(Sellable s) {
        if (s.grade().isEmpty() || s.grade().equals("normal")) return Component.empty();
        return shop.text().msg("shop.grade." + s.grade());
    }

    /** /shop sell: the screen of the nearest fishmonger, grocer or mining store counter in reach. */
    void command(ServerPlayer p) {
        ShopItems.Spot spot = shop.nearestFurniture(p, counterDistance, id -> counterOf(id) != null);
        if (spot == null) {
            shop.error(p, "shop.sell.no-counter", Text.unparsed("distance", (long) counterDistance));
            return;
        }
        open(p, spot, counterOf(spot.id()));
    }

    /** The sell-back counter a furniture id is, or null. */
    static Counter counterOf(String id) {
        if (ShopModule.COUNTER_FISH.equals(id)) return Counter.FISHMONGER;
        if (ShopModule.COUNTER_GROCER.equals(id)) return Counter.GROCER;
        if (ShopModule.COUNTER_MINER.equals(id)) return Counter.MINER;
        return null;
    }

    void open(ServerPlayer p, Booth counterSpot, Counter counter) {
        if (!Perms.has(p, ShopModule.SELL_PERM)) {
            shop.error(p, "general.no-permission");
            return;
        }
        if (shop.closed(p)) return;
        shop.rollDay();
        String key = counter.key;
        Map<String, Group> groups = scan(p, counter, s -> true);
        Dialogs.Menu menu = shop.dialogs.menu(shop.text().msg("shop.sell." + key + "-title"));
        menu.text(shop.text().msg("shop.sell." + key + "-body", shop.n("balance", shop.balance(p))), 300);
        if (groups.isEmpty()) {
            menu.text(shop.text().msg("shop.sell.nothing-" + key), 300);
        } else {
            Predicate<Sellable> inAll = s -> counter != Counter.GROCER || !"prize".equals(s.grade());
            // What Sell All pays, worked out exactly as the sale does it (slot by slot), so the lines add up to it.
            Map<String, Long> allCents = quoteByGroup(p, counter, inAll);
            long totalAll = 0;
            for (long c : allCents.values()) totalAll += c;
            long legendary = 0;
            List<Group> list = new ArrayList<>(groups.values());
            Component lines = Component.empty();
            boolean first = true;
            for (Group g : list) {
                boolean all = inAll.test(g.what);
                long cents = all ? allCents.getOrDefault(g.what.group(), 0L) : quoteCents(g.what.baseCents(), sold(g.what.id()), g.units);
                if (all && g.what.kind() == Kind.FISH && "legendary".equals(g.what.grade())) legendary += g.units;
                int pct = (int) ((factorPpm(sold(g.what.id())) + 5_000) / 10_000);
                if (!first) lines = lines.append(Component.newline());
                first = false;
                lines = lines.append(shop.text().msg(pct < 100 ? "shop.sell.line-dropped" : "shop.sell.line",
                        Text.component("item", ShopModule.nameOf(g.sample)), Text.component("grade", gradeLabel(g.what)),
                        Text.unparsed("count", ShopModule.fmt(g.units)), Text.unparsed("each", coins(unitCents(g.what))),
                        Text.unparsed("total", coins(cents)), Text.unparsed("percent", pct)));
            }
            menu.text(lines, 300);
            int rows = 0;
            for (Group g : list) {
                if (rows++ >= maxRows) break;
                String group = g.what.group();
                menu.button(shop.text().msg("shop.sell.sell-one", Text.component("item", ShopModule.nameOf(g.sample)), Text.component("grade", gradeLabel(g.what))),
                        shop.text().msg("shop.sell.sell-one-tooltip"), 150, pl -> {
                            sell(pl, counterSpot, counter, s -> s.group().equals(group));
                            reopen(pl, counterSpot, counter);
                        });
            }
            boolean anyAll = list.stream().anyMatch(g -> inAll.test(g.what));
            if (anyAll) {
                final long legendaryAll = legendary, coinsAll = totalAll / 100;
                menu.button(shop.text().msg("shop.sell.sell-all", Text.unparsed("total", ShopModule.fmt(coinsAll))),
                        shop.text().msg(counter == Counter.GROCER ? "shop.sell.sell-all-tooltip-grocer" : counter == Counter.MINER ? "shop.sell.sell-all-tooltip-miner" : "shop.sell.sell-all-tooltip"), 150, pl -> {
                            // Picked up a legendary fish since the screen opened: show the counter again (with the ask).
                            if (legendaryAll == 0 && legendaryIn(pl, counter, inAll) > 0) {
                                reopen(pl, counterSpot, counter);
                                return;
                            }
                            if (legendaryAll > 0) {
                                // Legendary fish are rare: Sell All asks before they go with the rest.
                                confirmSellAll(pl, counterSpot, counter, inAll, legendaryAll, coinsAll);
                                return;
                            }
                            sell(pl, counterSpot, counter, inAll);
                            reopen(pl, counterSpot, counter);
                        });
            }
        }
        if (counter == Counter.GROCER && CommandBridge.isBound("farming")) {
            menu.button(shop.text().msg("shop.sell.fair"), shop.text().msg("shop.sell.fair-tooltip"), 150,
                    pl -> CommandBridge.dispatch(Sender.of(pl), "farming fair submit"));
        }
        menu.columns(2);
        menu.exit(shop.text().msg("menu.close"), null);
        menu.open(p);
        shop.text().sound(p, "menu-open");
    }

    /** What a sale of {@code which} pays per group (id + grade), in hundredths, going through the slots like {@link #sell}. */
    private Map<String, Long> quoteByGroup(ServerPlayer p, Counter counter, Predicate<Sellable> which) {
        Map<String, Long> out = new HashMap<>();
        Map<String, Long> units = new HashMap<>();
        for (ItemStack it : ShopModule.storage(p)) {
            Sellable s = classify(it);
            if (s == null || !buys(counter, s.kind()) || !which.test(s)) continue;
            long before = sold(s.id()) + units.getOrDefault(s.id(), 0L);
            out.merge(s.group(), quoteCents(s.baseCents(), before, it.getCount()), Long::sum);
            units.merge(s.id(), (long) it.getCount(), Long::sum);
        }
        return out;
    }

    /** Legendary fish in the inventory that {@code which} would sell now. */
    private long legendaryIn(ServerPlayer p, Counter counter, Predicate<Sellable> which) {
        long n = 0;
        for (ItemStack it : ShopModule.storage(p)) {
            Sellable s = classify(it);
            if (s != null && buys(counter, s.kind()) && which.test(s) && s.kind() == Kind.FISH && "legendary".equals(s.grade())) n += it.getCount();
        }
        return n;
    }

    /** Sell All with legendary fish in it: one more look first. */
    private void confirmSellAll(ServerPlayer p, Booth spot, Counter counter, Predicate<Sellable> which, long legendary, long coins) {
        Dialogs.Menu menu = shop.dialogs.menu(shop.text().msg("shop.sell.confirm-title"));
        menu.text(shop.text().msg("shop.sell.confirm-body", Text.unparsed("count", ShopModule.fmt(legendary)), shop.n("total", coins)), 300);
        menu.button(shop.text().msg("shop.sell.confirm-yes", shop.n("total", coins)), null, 150, pl -> {
            sell(pl, spot, counter, which);
            reopen(pl, spot, counter);
        });
        menu.button(shop.text().msg("menu.back"), null, 150, pl -> reopen(pl, spot, counter));
        menu.columns(2);
        menu.open(p);
    }

    private void reopen(ServerPlayer p, Booth spot, Counter counter) {
        if (spot.valid() && ShopModule.near(p, spot.at(), counterDistance)) open(p, spot, counter);
    }

    /**
     * Sells what matches {@code which}, checked now: the player is still at the counter, the items are re-read from the
     * inventory, prices are today's, the balance has room. Items leave the inventory, then the coins are credited; if
     * the bank refuses, the items go back where they were.
     */
    boolean sell(ServerPlayer p, Booth spot, Counter counter, Predicate<Sellable> which) {
        if (!Perms.has(p, ShopModule.SELL_PERM)) {
            shop.error(p, "general.no-permission");
            return false;
        }
        if (shop.closed(p)) return false;
        Hooks.Bank bank = shop.bank();
        if (bank.robbing(p)) {
            shop.error(p, "bank.robbery.no-banking");
            return false;
        }
        if (spot == null || !spot.valid() || !ShopModule.near(p, spot.at(), counterDistance)) {
            shop.error(p, "shop.sell.too-far");
            return false;
        }
        shop.rollDay();
        List<ItemStack> storage = ShopModule.storage(p);
        Map<String, Long> units = new LinkedHashMap<>();
        List<Integer> slots = new ArrayList<>();
        long cents = 0;
        long count = 0;
        for (int i = 0; i < storage.size(); i++) {
            Sellable s = classify(storage.get(i));
            if (s == null || !buys(counter, s.kind()) || !which.test(s)) continue;
            int n = storage.get(i).getCount();
            long before = sold(s.id()) + units.getOrDefault(s.id(), 0L);
            cents = Math.addExact(cents, quoteCents(s.baseCents(), before, n));
            units.merge(s.id(), (long) n, Long::sum);
            slots.add(i);
            count += n;
        }
        if (slots.isEmpty()) {
            shop.error(p, "shop.sell.nothing");
            return false;
        }
        long coins = cents / 100;
        if (coins <= 0) {
            shop.error(p, "shop.sell.too-little");
            return false;
        }
        if (!bank.canCredit(p.getUUID(), coins)) {
            shop.error(p, "bank.balance-cap", shop.n("max", bank.maxBalance()));
            return false;
        }
        Map<Integer, ItemStack> backup = new LinkedHashMap<>();
        for (int i : slots) {
            backup.put(i, storage.get(i));
            p.getInventory().setItem(i, ItemStack.EMPTY);
        }
        long after = bank.credit(p.getUUID(), p.getScoreboardName(), coins, "SELLBACK", count + " items at the " + counter.name().toLowerCase(Locale.ROOT));
        if (after < 0) {
            List<ItemStack> lost = new ArrayList<>();
            for (Map.Entry<Integer, ItemStack> e : backup.entrySet()) {
                if (p.getInventory().getItem(e.getKey()).isEmpty()) p.getInventory().setItem(e.getKey(), e.getValue());
                else lost.add(e.getValue());
            }
            ShopModule.give(p, lost);
            shop.error(p, "bank.balance-cap", shop.n("max", bank.maxBalance()));
            return false;
        }
        for (Map.Entry<String, Long> e : units.entrySet()) shop.store.soldToday.merge(e.getKey(), e.getValue(), Long::sum);
        shop.store.save();
        ShopModule.saveInventory(p);
        shop.store.log("SELLBACK " + p.getScoreboardName() + " " + p.getUUID() + " at " + counter + " items=" + units + " coins=" + coins + " balance=" + after);
        shop.text().send(p, "shop.sell.sold", Text.unparsed("count", ShopModule.fmt(count)), shop.n("coins", coins), shop.n("balance", after));
        shop.text().sound(p, "accepted");
        return true;
    }
}
