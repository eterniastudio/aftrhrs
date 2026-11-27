package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The shop ({@code modules.shop}, settings {@code shop.yml}): the General Store ({@link StoreScreen}, {@code /shop}),
 * the fishmonger, grocer and mining store sell-back counters ({@link SellBack}), the counter keepers that stand in for
 * a counter ({@link CounterKeeper}) and the rentable market stalls ({@link Stalls}, {@code /stall}). Every coin moves through the bank balance ({@link Hooks.Bank}): main thread, long maths, history,
 * bank log, saved at once. Shop data ({@link ShopStore}) is saved synchronously before any item is handed out.
 *
 * <p>The counters and stalls were Oraxen furniture on Paper; they are native blocks with the same ids now
 * ({@code aftrhrs:arcana_shop_counter_general}, {@code aftrhrs:arcana_market_stall_red}...), clicked through
 * {@link ShopEvents}.
 */
public final class ShopModule implements FeatherModule {
    static final String PERM = "feather.shop";
    static final String SELL_PERM = "feather.shop.sell";
    static final String ADMIN = "feather.shop.admin";
    static final String STALL_PERM = "feather.stall";
    static final String STALL_ADMIN = "feather.stall.admin";
    static final String COUNTER_GENERAL = "arcana_shop_counter_general";
    static final String COUNTER_FISH = "arcana_shop_counter_fishmonger";
    static final String COUNTER_GROCER = "arcana_shop_counter_grocer";
    static final String COUNTER_MINER = "arcana_shop_counter_miner";
    static final String STALL_PREFIX = "arcana_market_stall_";
    /** Counter keepers: at most this many in one chunk, and this many placed by one player (staff: no limit). */
    static final int KEEPERS_PER_CHUNK = 4, KEEPERS_PER_PLAYER = 8;
    /** The fishing and farming ports keep these item tags (custom_data) under the plugin's names. */
    static final String FISH_RARITY = "feather:fish_rarity";
    static final String CROP_QUALITY = "feather:crop_quality";

    /** One thing the General Store sells. */
    record Entry(String id, String category, long price, int amount, boolean featurable) {
    }

    /** The running module (Fabric listeners can't be unregistered; they ask this). */
    static volatile ShopModule active;

    final Feather plugin;
    final Dialogs dialogs;
    final ShopStore store;
    final StoreScreen screen;
    final SellBack sell;
    final Stalls stalls;
    final Keepers keepers;
    final StallMenus menus;
    Yaml cfg;
    ZoneId zone = ZoneId.of("America/Toronto");
    /** Category id -> its entries, in config order. */
    final Map<String, List<Entry>> categories = new LinkedHashMap<>();
    final Map<String, String> categoryIcons = new LinkedHashMap<>();
    int maxBundles = 64;
    int featuredCount = 3;
    int featuredDiscount = 25;
    private Tasks.Task ticker;
    private final Set<String> warned = new HashSet<>();

    public ShopModule(Feather plugin) {
        this.plugin = plugin;
        this.dialogs = plugin.dialogs();
        this.store = new ShopStore(plugin.getDataFolder().resolve("data"), plugin.getLogger());
        this.screen = new StoreScreen(this);
        this.sell = new SellBack(this);
        this.stalls = new Stalls(this);
        this.keepers = new Keepers(this);
        this.menus = new StallMenus(this);
    }

    @Override
    public String name() {
        return "Shop";
    }

    @Override
    public void enable() {
        reload();
        store.load();
        rollDay();
        active = this;
        ShopEvents.register(plugin);
        keepers.enable();
        plugin.bind("shop", new ShopCommand());
        plugin.bind("stall", new StallCommand());
        plugin.hub().add("shop", Hub.Group.COMMUNITY, Hub.ORDER_SHOP, "hub-social", PERM, this::openStore);
        ticker = Tasks.timer(20L * 30, 20L * 60, this::tick);
        if (bank() == null) plugin.getLogger().warn("Shop: the bank (modules.bank) is off; the shop stays closed until it runs.");
    }

    @Override
    public void reload() {
        String z = plugin.getConfig().getString("daily.reset-zone", "America/Toronto");
        try {
            zone = z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z.trim());
        } catch (RuntimeException e) {
            zone = ZoneId.of("America/Toronto");
        }
        cfg = plugin.loadResourceConfig("shop.yml");
        categories.clear();
        categoryIcons.clear();
        maxBundles = Math.max(1, Math.min(2304, cfg.getInt("store.max-bundles", 64)));
        featuredCount = Math.max(0, Math.min(10, cfg.getInt("store.featured.count", 3)));
        featuredDiscount = Math.max(0, Math.min(95, cfg.getInt("store.featured.discount-percent", 25)));
        Yaml cats = cfg.getConfigurationSection("store.categories");
        Set<String> seen = new HashSet<>();
        if (cats != null) {
            for (String cat : cats.getKeys(false)) {
                List<Entry> list = new ArrayList<>();
                for (Map<?, ?> m : cats.getMapList(cat + ".items")) {
                    Object rawId = m.get("id");
                    if (rawId == null) continue;
                    String id = String.valueOf(rawId).trim().toLowerCase(Locale.ROOT);
                    long price = m.get("price") instanceof Number n ? n.longValue() : -1;
                    int amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
                    if (id.isEmpty() || price <= 0 || price > 1_000_000_000L || amount < 1 || amount > 2304) {
                        plugin.getLogger().warn("shop.yml: store item {} in {} needs an id, a price (1+) and an amount (1-2304); skipped.", m, cat);
                        continue;
                    }
                    if (!seen.add(id)) {
                        plugin.getLogger().warn("shop.yml: {} is listed twice in the store; only the first counts.", id);
                        continue;
                    }
                    list.add(new Entry(id, cat, price, amount, !Boolean.FALSE.equals(m.get("featured"))));
                }
                categories.put(cat, list);
                categoryIcons.put(cat, cats.getString(cat + ".icon", ""));
            }
        }
        sell.reload(cfg.getConfigurationSection("sell"));
        stalls.reload(cfg.getConfigurationSection("stalls"));
        keepers.reload(cfg.getConfigurationSection("stalls.keeper"));
        warned.clear();
        if (store.unreadable) {
            store.load();
            if (!store.unreadable && ticker != null) keepers.scanLoaded();
        }
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        keepers.disable();
        menus.closeEverything();
        if (!store.unreadable) store.save();
        plugin.hub().remove("shop");
        if (active == this) active = null;
    }

    private void tick() {
        rollDay();
        stalls.tick();
    }

    // ------------------------------------------------------------------ shared helpers

    Text text() {
        return plugin.text();
    }

    /** The bank, or null while modules.bank is off. */
    Hooks.Bank bank() {
        return plugin.service(Hooks.Bank.class);
    }

    static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    TagResolver n(String key, long v) {
        return Text.unparsed(key, fmt(v));
    }

    void error(ServerPlayer to, String key, TagResolver... r) {
        text().send(to, key, r);
        text().sound(to, "error");
    }

    void error(Sender to, String key, TagResolver... r) {
        if (to.player() != null) error(to.player(), key, r);
        else text().send(to, key, r);
    }

    /** Tells the player and returns true while the bank or the shop data can't be used. */
    boolean closed(ServerPlayer to) {
        Hooks.Bank b = bank();
        if (b == null || !b.isOpen()) {
            error(to, "bank.closed");
            return true;
        }
        if (store.unreadable) {
            error(to, "shop.closed");
            return true;
        }
        return false;
    }

    long balance(ServerPlayer p) {
        Hooks.Bank b = bank();
        return b == null ? 0 : b.balance(p.getUUID());
    }

    void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.getLogger().warn(message);
    }

    /** A fresh item: {@code minecraft:<id>} for vanilla, otherwise an ex-Oraxen id (null when the mod has no such item). */
    ItemStack make(String id) {
        if (id == null || id.isEmpty()) return null;
        ItemStack s = ShopItems.make(id);
        if (s == null) {
            warnOnce("item:" + id, id.startsWith("minecraft:") ? "shop.yml: '" + id + "' is not a vanilla item."
                    : "shop.yml: item '" + id + "' doesn't exist (yet); it isn't offered.");
        }
        return s;
    }

    boolean available(String id) {
        return ShopItems.item(id) != null;
    }

    /** The ex-Oraxen id of an item, or null. */
    String idOf(ItemStack item) {
        return ShopItems.idOf(item);
    }

    /** The item's name as players see it. */
    static Component nameOf(ItemStack item) {
        return Adv.adventure(item.getHoverName());
    }

    /** "3d 4h" / "2h 5m" / "7m" until a time. */
    static String until(long epochMs) {
        java.time.Duration d = java.time.Duration.ofMillis(Math.max(0, epochMs - System.currentTimeMillis()));
        long h = d.toHours();
        long m = d.toMinutesPart();
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + m + "m";
        return Math.max(1, m) + "m";
    }

    long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    long thisWeek() {
        return LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    /** Next Monday 00:00 in the reset zone (epoch ms). */
    long nextWeek() {
        return LocalDate.ofEpochDay(thisWeek() + 7).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** New day: today's sell-back counts start over (prices recover). */
    void rollDay() {
        if (store.unreadable) return;
        long t = today();
        if (store.day == t) return;
        store.day = t;
        store.soldToday.clear();
        store.save();
    }

    // ------------------------------------------------------------------ inventory

    /** The 36 storage slots (0-8 hotbar, 9-35 main), like Bukkit's storage contents. */
    static List<ItemStack> storage(ServerPlayer p) {
        return p.getInventory().getNonEquipmentItems();
    }

    static boolean similar(ItemStack a, ItemStack b) {
        return a != null && b != null && !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    /** Whether the items all fit in the player's storage slots right now (nothing is moved). */
    static boolean fits(ServerPlayer p, List<ItemStack> items) {
        List<ItemStack> sim = new ArrayList<>();
        for (ItemStack s : storage(p)) sim.add(s.copy());
        for (ItemStack it : items) {
            int left = it.getCount();
            int max = Math.max(1, it.getMaxStackSize());
            for (int i = 0; i < sim.size() && left > 0; i++) {
                ItemStack s = sim.get(i);
                if (!similar(s, it) || s.getCount() >= max) continue;
                int add = Math.min(left, max - s.getCount());
                s.setCount(s.getCount() + add);
                left -= add;
            }
            for (int i = 0; i < sim.size() && left > 0; i++) {
                if (!sim.get(i).isEmpty()) continue;
                int add = Math.min(left, max);
                sim.set(i, it.copyWithCount(add));
                left -= add;
            }
            if (left > 0) return false;
        }
        return true;
    }

    /** How many of {@code proto} fit in the player's storage slots right now (nothing is moved). */
    static long room(ServerPlayer p, ItemStack proto) {
        int max = Math.max(1, proto.getMaxStackSize());
        long n = 0;
        for (ItemStack s : storage(p)) {
            if (s.isEmpty()) n += max;
            else if (similar(s, proto) && s.getCount() < max) n += max - s.getCount();
        }
        return n;
    }

    /**
     * Writes the player's data file now, right after a step that moved items and was saved to shop.yml or the bank,
     * so a crash can't undo one side of it (items back in the inventory and on the stall, or coins paid twice).
     */
    static void saveInventory(ServerPlayer p) {
        if (p == null || p.hasDisconnected()) return;
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return;
        try {
            server.getPlayerList().save(p);
        } catch (RuntimeException e) {
            Feather.get().getLogger().warn("Could not save {}'s inventory after a shop step: {}", p.getScoreboardName(), e.toString());
        }
    }

    /** {@code count} of {@code proto} split into full stacks. */
    static List<ItemStack> stacks(ItemStack proto, long count) {
        List<ItemStack> out = new ArrayList<>();
        int max = Math.max(1, proto.getMaxStackSize());
        long left = count;
        while (left > 0) {
            int n = (int) Math.min(max, left);
            out.add(proto.copyWithCount(n));
            left -= n;
        }
        return out;
    }

    /** Hands items over; whatever doesn't fit after all lands at the player's feet, for them only. */
    static void give(ServerPlayer p, List<ItemStack> items) {
        for (ItemStack s : items) {
            ItemStack copy = s.copy();
            p.getInventory().add(copy);
            if (!copy.isEmpty()) drop(p, copy);
        }
        p.inventoryMenu.broadcastChanges();
    }

    static void drop(ServerPlayer p, ItemStack stack) {
        ItemEntity e = new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), stack);
        e.setDefaultPickUpDelay();
        e.setTarget(p.getUUID());
        p.level().addFreshEntity(e);
    }

    /** Parses a positive whole number ("all"/"max" give {@code max}); -1 when invalid. */
    static long parseAmount(String raw, long max) {
        if (raw == null) return -1;
        String s = raw.trim().replace(",", "").replace("_", "").toLowerCase(Locale.ROOT);
        if (s.equals("all") || s.equals("max")) return max;
        if (s.isEmpty() || s.length() > 15 || !s.chars().allMatch(Character::isDigit)) return -1;
        try {
            long v = Long.parseLong(s);
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Within {@code distance} (+ a little slack) of a spot, same world. */
    static boolean near(ServerPlayer p, Loc at, double distance) {
        if (at == null || at.level() != p.level()) return false;
        double dx = p.getX() - at.x(), dy = p.getY() - at.y(), dz = p.getZ() - at.z();
        return dx * dx + dy * dy + dz * dz <= (distance + 1.5) * (distance + 1.5);
    }

    /** The nearest shop furniture block within {@code radius} whose id passes {@code accept}, or null. */
    ShopItems.Spot nearestFurniture(ServerPlayer p, double radius, Predicate<String> accept) {
        return ShopItems.nearest(p.level(), p.getX(), p.getY(), p.getZ(), radius, accept);
    }

    /** Shop NPCs anyone may right-click in any claim: counter keepers and stall keepers. */
    public static boolean publicNpc(net.minecraft.world.entity.Entity e) {
        return e instanceof CounterKeeper || Keepers.isKeeper(e);
    }

    static boolean isShopFurniture(String id) {
        return id != null && (id.equals(COUNTER_GENERAL) || id.equals(COUNTER_FISH) || id.equals(COUNTER_GROCER) || id.equals(COUNTER_MINER) || id.startsWith(STALL_PREFIX));
    }

    // ------------------------------------------------------------------ counter keepers

    /** A right-click on a counter keeper: its counter's screen; sneaking, its owner (or staff) picks it up. */
    boolean keeperClick(ServerPlayer p, CounterKeeper k) {
        if (keepers.justClicked(p)) return false;
        // whoever placed it, staff, or (for a keeper whose owner may not build where it stands, e.g. left in the wild
        // before the land was claimed) whoever may build there; a fellow builder can't take a trusted member's keeper
        Loc at = Loc.of(k);
        boolean foreign = plugin.claimed(at) && (k.owner() == null || !plugin.claimAllows(k.owner(), at, ClaimAction.BUILD));
        boolean staff = Perms.has(p, ADMIN) || foreign && plugin.claimAllows(p, at, ClaimAction.BUILD);
        if (p.isShiftKeyDown() && k.mayPickUp(p, staff)) {
            k.discard();
            ItemStack back = new ItemStack(KeeperContent.ITEMS.get(k.trade()));
            give(p, List.of(back));
            text().send(p, "shop.keeper.picked-up", Text.component("name", Adv.adventure(k.getName())));
            text().sound(p, "accepted");
            return true;
        }
        switch (k.trade()) {
            case GENERAL -> {
                if (!Perms.has(p, PERM)) {
                    error(p, "general.no-permission");
                    return false;
                }
                screen.open(p);
            }
            case FISHMONGER -> sell.open(p, SellBack.Booth.of(k), SellBack.Counter.FISHMONGER);
            case GROCER -> sell.open(p, SellBack.Booth.of(k), SellBack.Counter.GROCER);
            case MINER -> sell.open(p, SellBack.Booth.of(k), SellBack.Counter.MINER);
        }
        return true;
    }

    /** A keeper item used on a block: the keeper stands on that face, turned to the player, if there is room and the claim allows it. */
    boolean placeKeeper(ServerPlayer p, UseOnContext ctx, KeeperContent.Trade trade) {
        ServerLevel level = (ServerLevel) ctx.getLevel();
        BlockPos at = ctx.getClickedPos();
        if (!level.getBlockState(at).canBeReplaced()) at = at.relative(ctx.getClickedFace());
        Loc where = Loc.of(level, at);
        EntityType<CounterKeeper> type = KeeperContent.TYPES.get(trade);
        AABB box = type.getDimensions().makeBoundingBox(where.x(), where.y(), where.z());
        if (!level.noCollision(box) || level.containsAnyLiquid(box)) {
            error(p, "shop.keeper.no-room");
            return false;
        }
        if (!plugin.claimAllows(p, where, ClaimAction.BUILD)) {
            error(p, "shop.keeper.claimed");
            return false;
        }
        // a few per chunk and per player, so they can't be spammed
        int inChunk = 0, mine = 0;
        for (CounterKeeper other : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(CounterKeeper.class), e -> true)) {
            if (other.chunkPosition().equals(net.minecraft.world.level.ChunkPos.containing(at))) inChunk++;
            if (p.getUUID().equals(other.owner())) mine++;
        }
        if (inChunk >= KEEPERS_PER_CHUNK) {
            error(p, "shop.keeper.too-many-here", Text.unparsed("max", KEEPERS_PER_CHUNK));
            return false;
        }
        if (mine >= KEEPERS_PER_PLAYER && !Perms.has(p, ADMIN)) {
            error(p, "shop.keeper.too-many", Text.unparsed("max", KEEPERS_PER_PLAYER));
            return false;
        }
        CounterKeeper k = type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (k == null) return false;
        double lx = p.getX() - where.x(), lz = p.getZ() - where.z();
        float yaw = lx * lx + lz * lz > 1.0E-4 ? (float) Math.toDegrees(Math.atan2(-lx, lz)) : p.getYRot() + 180;
        k.snapTo(where.x(), where.y(), where.z(), yaw, 0);
        k.setYHeadRot(yaw);
        k.setYBodyRot(yaw);
        k.setOwner(p.getUUID());
        k.setCustomName(ctx.getItemInHand().getHoverName().copy().withStyle(s -> s.withItalic(false)));
        if (!level.addFreshEntity(k)) return false;
        if (!p.getAbilities().instabuild) ctx.getItemInHand().shrink(1);
        level.playSound(null, k.getX(), k.getY(), k.getZ(), net.minecraft.sounds.SoundEvents.VILLAGER_CELEBRATE, net.minecraft.sounds.SoundSource.NEUTRAL, 1f, 1f);
        return true;
    }

    // ------------------------------------------------------------------ for the hub and other modules

    /** Opens the General Store (the hub's Shop button). */
    public void openStore(ServerPlayer p) {
        screen.open(p);
    }

    /** A click on shop furniture (counters, stalls) at {@code spot} (its origin). True when used. */
    boolean furnitureClick(ServerPlayer p, ShopItems.Spot spot) {
        ShopStore.Stall stall = stalls.byBlock(spot);
        if (stall != null) {
            stalls.click(p, stall);
            return true;
        }
        switch (spot.id()) {
            case COUNTER_GENERAL -> {
                screen.open(p);
                return true;
            }
            case COUNTER_FISH -> {
                sell.open(p, spot, SellBack.Counter.FISHMONGER);
                return true;
            }
            case COUNTER_GROCER -> {
                sell.open(p, spot, SellBack.Counter.GROCER);
                return true;
            }
            case COUNTER_MINER -> {
                sell.open(p, spot, SellBack.Counter.MINER);
                return true;
            }
            default -> {
                if (spot.id().startsWith(STALL_PREFIX)) {
                    text().send(p, Perms.has(p, STALL_ADMIN) ? "shop.stall.not-set-up-staff" : "shop.stall.not-set-up");
                    return true;
                }
                return false;
            }
        }
    }

    /** The stall lines go out with the join digest (Stalls.collectJoin). */
    void joined(ServerPlayer p) {
        menus.sweep(p);
    }

    /** Players by UUID for messages. */
    ServerPlayer online(UUID id) {
        return id == null ? null : plugin.player(id);
    }

    // ------------------------------------------------------------------ commands

    private final class ShopCommand implements FeatherCommand {
        @Override
        public boolean onCommand(Sender sender, String label, String[] args) {
            String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
            ServerPlayer p = sender.player();
            if (p == null) {
                text().send(sender, "general.players-only");
                return true;
            }
            switch (sub) {
                case "" -> screen.open(p);
                case "sell" -> sell.command(p);
                case "featured" -> screen.openFeatured(p);
                default -> text().send(p, "shop.help");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(Sender sender, String label, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length == 1) out.addAll(List.of("sell", "featured"));
            return filter(out, args);
        }
    }

    private final class StallCommand implements FeatherCommand {
        @Override
        public boolean onCommand(Sender sender, String label, String[] args) {
            stalls.command(sender, args);
            return true;
        }

        @Override
        public List<String> onTabComplete(Sender sender, String label, String[] args) {
            return filter(new ArrayList<>(stalls.complete(sender, args)), args);
        }
    }

    private static List<String> filter(List<String> out, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
