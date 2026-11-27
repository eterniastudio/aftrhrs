package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shop data in {@code data/shop.yml}: today's sell-back counts (dynamic prices), the market stalls with their stock,
 * offline sale notes and stock waiting to be collected. Main thread only. Saved synchronously through
 * {@link SafeYaml} on every change, so stall stock is on disk before any item reaches a player. A file that can't be
 * read closes the shop ({@link #unreadable}) and is never overwritten. Item and money moves are appended to
 * {@code data/shop-log.txt}. Same format as the plugin: stock items are Paper's base64 item bytes ({@link ShopItems}),
 * worlds are Paper's names ("world", "world_nether"...).
 */
final class ShopStore {
    /** One listing: a single item (amount 1) as base64, how many are in stock and the price of one. */
    static final class Listing {
        final long id;
        final String item;
        int stock;
        long price;
        private ItemStack decoded;
        private boolean tried;

        Listing(long id, String item, int stock, long price) {
            this.id = id;
            this.item = item;
            this.stock = stock;
            this.price = price;
        }

        /** A fresh copy of the listed item (amount 1), or null when it can't be read. */
        ItemStack prototype() {
            if (!tried) {
                tried = true;
                ItemStack s = ShopItems.decode(item);
                decoded = s == null ? null : s.copyWithCount(1);
            }
            return decoded == null ? null : decoded.copy();
        }
    }

    static final class Stall {
        final String id;
        String name;
        String world;
        double x, y, z;
        /** The old Oraxen furniture base entity (kept in the file; the stall is a block now, found by position). */
        UUID entity;
        String furniture;
        UUID owner;
        String ownerName;
        long rentedAt;
        long rentUntil;
        final List<Listing> listings = new ArrayList<>();
        /** The shopkeeper villager ({@link Keepers}): its spot (null world = none), entity, who placed it and its look. */
        String keeperWorld;
        double kx, ky, kz;
        float kyaw;
        UUID keeper;
        /** Placed by staff: it stays when a rental ends (a server-run stall). */
        boolean keeperStaff;
        String keeperType;
        String keeperJob;
        /** Serial of the one valid Stall Keeper item; every new item raises it, so older copies stop working. */
        int keeperIssue;

        Stall(String id) {
            this.id = id;
        }

        boolean hasKeeper() {
            return keeperWorld != null;
        }

        Loc keeperSpot() {
            if (keeperWorld == null) return null;
            ServerLevel l = Worlds.resolve(keeperWorld);
            return l == null ? null : new Loc(l, kx, ky, kz, kyaw, 0);
        }

        void clearKeeper() {
            keeperWorld = null;
            keeper = null;
            keeperStaff = false;
        }

        boolean rented() {
            return owner != null;
        }

        Loc location() {
            ServerLevel l = Worlds.resolve(world);
            return l == null ? null : new Loc(l, x, y, z);
        }

        /** The block the stall furniture stands on (its origin). */
        BlockPos block() {
            return BlockPos.containing(x, y, z);
        }

        Listing listing(long id) {
            for (Listing l : listings) if (l.id == id) return l;
            return null;
        }
    }

    /** Sales made while the owner was offline, told in one letter. */
    static final class Note {
        int sales;
        long coins;
        long since;
    }

    private static final String HEADER = "# Feather shop: today's sell-back counts, market stalls (stock as base64 items), offline sale notes\n"
            + "# and stock waiting for /stall collect. Don't edit while the server runs (shop.yml.bak holds the previous save).\n";

    private final Path file;
    private final Path logFile;
    private final Logger log;
    long day = Long.MIN_VALUE;
    final Map<String, Long> soldToday = new HashMap<>();
    final Map<String, Stall> stalls = new LinkedHashMap<>();
    final Map<UUID, Note> notes = new HashMap<>();
    /** Stall stock that couldn't go out by mail: collected with /stall collect. */
    final Map<UUID, List<String>> returns = new HashMap<>();
    long nextId = 1;
    boolean unreadable;

    ShopStore(Path data, Logger log) {
        this.file = data.resolve("shop.yml");
        this.logFile = data.resolve("shop-log.txt");
        this.log = log;
    }

    static String encode(ItemStack item) {
        return ShopItems.encode(item);
    }

    static ItemStack decode(String data) {
        return ShopItems.decode(data);
    }

    Stall stall(String id) {
        return id == null ? null : stalls.get(id.toLowerCase(Locale.ROOT));
    }

    void load() {
        soldToday.clear();
        stalls.clear();
        notes.clear();
        returns.clear();
        nextId = 1;
        day = Long.MIN_VALUE;
        Yaml yml = SafeYaml.read(file, log, "shop sales counts, market stalls and their stock");
        if (yml == null) {
            unreadable = true;
            return;
        }
        unreadable = false;
        day = yml.getLong("day", Long.MIN_VALUE);
        nextId = Math.max(1, yml.getLong("next-id", 1));
        Yaml s = yml.getConfigurationSection("sold");
        if (s != null) for (String k : s.getKeys(false)) soldToday.put(k, Math.max(0, s.getLong(k)));
        Yaml st = yml.getConfigurationSection("stalls");
        long max = 0;
        if (st != null) {
            for (String k : st.getKeys(false)) {
                Yaml c = st.getConfigurationSection(k);
                if (c == null) continue;
                Stall stall = new Stall(k.toLowerCase(Locale.ROOT));
                stall.name = c.getString("name", k);
                stall.world = c.getString("world", "world");
                stall.x = c.getDouble("x");
                stall.y = c.getDouble("y");
                stall.z = c.getDouble("z");
                stall.entity = uuid(c.getString("entity"));
                stall.furniture = c.getString("furniture");
                stall.owner = uuid(c.getString("owner"));
                stall.ownerName = c.getString("owner-name");
                stall.rentedAt = c.getLong("rented-at");
                stall.rentUntil = c.getLong("rent-until");
                Yaml k2 = c.getConfigurationSection("keeper");
                if (k2 != null) {
                    stall.keeperWorld = k2.getString("world");
                    stall.kx = k2.getDouble("x");
                    stall.ky = k2.getDouble("y");
                    stall.kz = k2.getDouble("z");
                    stall.kyaw = (float) k2.getDouble("yaw");
                    stall.keeper = uuid(k2.getString("entity"));
                    stall.keeperStaff = k2.getBoolean("staff");
                    stall.keeperType = k2.getString("type");
                    stall.keeperJob = k2.getString("profession");
                    stall.keeperIssue = k2.getInt("issue");
                }
                for (Map<?, ?> m : c.getMapList("listings")) {
                    try {
                        Listing l = new Listing(num(m.get("id")), String.valueOf(m.get("item")), (int) num(m.get("stock")), num(m.get("price")));
                        if (l.stock > 0 && l.price > 0) stall.listings.add(l);
                        max = Math.max(max, l.id);
                    } catch (RuntimeException e) {
                        log.warn("shop.yml: a broken listing in stall {} was skipped: {}", k, m);
                    }
                }
                stalls.put(stall.id, stall);
            }
        }
        nextId = Math.max(nextId, max + 1);
        Yaml n = yml.getConfigurationSection("notes");
        if (n != null) {
            for (String k : n.getKeys(false)) {
                UUID u = uuid(k);
                if (u == null) continue;
                Note note = new Note();
                note.sales = n.getInt(k + ".sales");
                note.coins = n.getLong(k + ".coins");
                note.since = n.getLong(k + ".since");
                notes.put(u, note);
            }
        }
        Yaml r = yml.getConfigurationSection("returns");
        if (r != null) {
            for (String k : r.getKeys(false)) {
                UUID u = uuid(k);
                List<String> items = r.getStringList(k);
                if (u != null && !items.isEmpty()) returns.put(u, new ArrayList<>(items));
            }
        }
    }

    /** Writes everything now. False (nothing written) while unreadable or when the write fails. */
    boolean save() {
        if (unreadable) return false;
        Yaml yml = new Yaml();
        if (day != Long.MIN_VALUE) yml.set("day", day);
        yml.set("next-id", nextId);
        for (Map.Entry<String, Long> e : soldToday.entrySet()) if (e.getValue() > 0) yml.set("sold." + e.getKey(), e.getValue());
        for (Stall s : stalls.values()) {
            String b = "stalls." + s.id + ".";
            yml.set(b + "name", s.name);
            yml.set(b + "world", s.world);
            yml.set(b + "x", s.x);
            yml.set(b + "y", s.y);
            yml.set(b + "z", s.z);
            if (s.entity != null) yml.set(b + "entity", s.entity.toString());
            if (s.furniture != null) yml.set(b + "furniture", s.furniture);
            if (s.owner != null) {
                yml.set(b + "owner", s.owner.toString());
                yml.set(b + "owner-name", s.ownerName);
                yml.set(b + "rented-at", s.rentedAt);
                yml.set(b + "rent-until", s.rentUntil);
            }
            if (s.keeperWorld != null) {
                yml.set(b + "keeper.world", s.keeperWorld);
                yml.set(b + "keeper.x", s.kx);
                yml.set(b + "keeper.y", s.ky);
                yml.set(b + "keeper.z", s.kz);
                yml.set(b + "keeper.yaw", (double) s.kyaw);
                if (s.keeper != null) yml.set(b + "keeper.entity", s.keeper.toString());
                if (s.keeperStaff) yml.set(b + "keeper.staff", true);
            }
            if (s.keeperType != null) yml.set(b + "keeper.type", s.keeperType);
            if (s.keeperJob != null) yml.set(b + "keeper.profession", s.keeperJob);
            if (s.keeperIssue != 0) yml.set(b + "keeper.issue", s.keeperIssue);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Listing l : s.listings) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", l.id);
                m.put("price", l.price);
                m.put("stock", l.stock);
                m.put("item", l.item);
                list.add(m);
            }
            yml.set(b + "listings", list);
        }
        for (Map.Entry<UUID, Note> e : notes.entrySet()) {
            yml.set("notes." + e.getKey() + ".sales", e.getValue().sales);
            yml.set("notes." + e.getKey() + ".coins", e.getValue().coins);
            yml.set("notes." + e.getKey() + ".since", e.getValue().since);
        }
        for (Map.Entry<UUID, List<String>> e : returns.entrySet()) if (!e.getValue().isEmpty()) yml.set("returns." + e.getKey(), new ArrayList<>(e.getValue()));
        // A detached copy (the returns lists above are live): dumped and written on the data thread, in order with
        // every other save. Still waited for: stock and coins only move once the file is on disk.
        return SafeYaml.writeSync(file, HEADER, yml.snapshot(), log);
    }

    /** Appends one line to data/shop-log.txt. */
    void log(String line) {
        String entry = Instant.now() + " " + line.replace('\n', ' ') + System.lineSeparator();
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not write {}: {}", logFile, e.toString());
        }
    }

    private static UUID uuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }
}
