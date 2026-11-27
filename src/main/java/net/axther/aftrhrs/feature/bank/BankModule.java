package net.axther.aftrhrs.feature.bank;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.JoinDigest;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.net.Companion;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Aftr Hrs bank. Goob Coins ({@link Coins}) are the currency; bank chests placed by staff ({@code /bank create})
 * are indestructible and open the bank screen ({@link BankScreen}): buy coins with diamonds, sell them back, deposit,
 * withdraw, history. Deposits earn daily interest. {@code /balance}, {@code /pay}, {@code /baltop} work anywhere.
 * Breaking a bank chest starts a robbery ({@link Robberies}). Settings: {@code bank.yml}; data: {@code data/bank.yml}.
 *
 * <p>Every money change runs on the server thread, checks its inputs again at the moment it happens (a dialog may be
 * minutes old), uses {@code long} with overflow checks, is written to the history and {@code data/bank-log.txt}, and
 * saved straight away. Other modules reach the bank through {@link Hooks.Bank} ({@code feather.service(Hooks.Bank.class)}).
 *
 * <p>Bank chests are vanilla chests whose positions are listed in {@code data/bank.yml} (world name + block), exactly as
 * on Paper, so a migrated world keeps its banks with no conversion.
 */
public final class BankModule implements FeatherModule, Hooks.Bank {
    static final String ADMIN = "feather.bank.admin";
    static final String BYPASS = "feather.bank.robbery.bypass";
    private static final long DAY_MS = 86_400_000L;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** The running bank (Fabric listeners can't be unregistered; they check this). */
    static volatile BankModule active;

    final Feather plugin;
    final Dialogs dialogs;
    final BankStore store;
    final Coins coins;
    final BankScreen screen;
    final Robberies robberies;
    Yaml cfg;
    private Tasks.Task ticker;
    /** The Companion's coin purse; null while modules.companion is off. */
    private WalletCompanion wallet;

    // settings (bank.yml)
    long buyRate;
    long sellRate;
    long maxDiamondsPerTrade;
    double depositFeePercent;
    double withdrawFeePercent;
    long maxBalance;
    double accessDistance;
    /** /bank opens the bank screen anywhere (bank chests stay for robberies and holograms). */
    boolean anywhere = true;
    boolean interestOn;
    long interestPpm;
    long interestCap;
    int interestHour;
    ZoneId zone = ZoneId.systemDefault();
    int catchUpDays;
    int activeDays;
    long payMin;
    long payConfirmAbove;
    long payCooldownMs;
    int payPerMinute;
    long reserveStart;
    long refillPerHour;
    long refillUpTo;
    int baltopSize;

    /** The bank chest each player has open (dialog actions only work near it). */
    final Map<UUID, BankStore.Pos> sessions = new HashMap<>();
    private final Map<UUID, Deque<Long>> payTimes = new HashMap<>();
    /** Interest earned while away, zeroed at the join: told by the join digest, which takes it out. */
    private final Map<UUID, Long> awayNotes = new HashMap<>();

    public BankModule(Feather plugin) {
        this.plugin = plugin;
        this.dialogs = plugin.dialogs();
        this.store = new BankStore(plugin);
        this.coins = new Coins(plugin);
        this.screen = new BankScreen(this);
        this.robberies = new Robberies(this);
    }

    @Override
    public String name() {
        return "Bank";
    }

    @Override
    public void enable() {
        reload();
        store.load(reserveStart);
        active = this;
        plugin.once("bank.events", BankModule::registerEvents);
        robberies.enable();
        plugin.bind("bank", new Bound("bank"));
        plugin.bind("balance", new Bound("balance"));
        plugin.bind("pay", new Bound("pay"));
        plugin.bind("baltop", new Bound("baltop"));
        long now = System.currentTimeMillis();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            BankStore.Account a = store.account(p.getUUID());
            a.name = Names.name(p);
            a.lastSeen = now;
        }
        ticker = Tasks.timer(40L, 20L * 60, this::minuteTick);
        plugin.provide(Hooks.Bank.class, this);
        plugin.provide(Hooks.BankBlocks.class, this::isBankBlock);
        plugin.provide(Hooks.TravelLock.class, this::travelLocked);
        JoinDigest.register("bank", 40, this::collectJoin);
        if (plugin.getConfig().getBoolean("modules.companion", true)) {
            wallet = new WalletCompanion(this);
            store.onRecord = wallet::recorded;
            wallet.start();
        }
    }

    @Override
    public void reload() {
        cfg = plugin.loadResourceConfig("bank.yml");
        coins.reload(cfg.getConfigurationSection("coin"));
        buyRate = Math.max(1, cfg.getLong("exchange.buy-coins-per-diamond", 10));
        sellRate = Math.max(1, cfg.getLong("exchange.sell-coins-per-diamond", 12));
        if (sellRate < buyRate) {
            // Buying coins for a diamond and buying the diamond back would make coins out of nothing.
            plugin.getLogger().warn("bank.yml: exchange.sell-coins-per-diamond ({}) is below buy-coins-per-diamond ({}); "
                    + "a diamond would buy back for less than it sold. Using {}.", sellRate, buyRate, buyRate);
            sellRate = buyRate;
        }
        maxDiamondsPerTrade = Math.max(1, cfg.getLong("exchange.max-diamonds-per-trade", 2304));
        depositFeePercent = clamp(cfg.getDouble("fees.deposit-percent", 0), 0, 100);
        withdrawFeePercent = clamp(cfg.getDouble("fees.withdraw-percent", 0), 0, 100);
        maxBalance = Math.max(1, Math.min(1_000_000_000_000L, cfg.getLong("max-balance", 1_000_000_000L)));
        accessDistance = clamp(cfg.getDouble("access-distance", 6), 2, 32);
        anywhere = cfg.getBoolean("anywhere", true);
        interestOn = cfg.getBoolean("interest.enabled", true);
        interestPpm = Math.round(clamp(cfg.getDouble("interest.rate-percent", 0.5), 0, 100) * 10_000);
        interestCap = Math.max(0, Math.min(1_000_000_000_000L, cfg.getLong("interest.cap", 10_000)));
        interestHour = (int) clamp(cfg.getInt("interest.hour", 0), 0, 23);
        String tz = cfg.getString("interest.timezone", "");
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (RuntimeException e) {
            plugin.getLogger().warn("bank.yml interest.timezone: unknown zone '{}', using the server's.", tz);
            zone = ZoneId.systemDefault();
        }
        catchUpDays = (int) clamp(cfg.getInt("interest.catch-up-days", 7), 0, 365);
        activeDays = (int) clamp(cfg.getInt("interest.active-days", 14), 1, 3650);
        payMin = Math.max(1, cfg.getLong("pay.min", 1));
        payConfirmAbove = Math.max(0, cfg.getLong("pay.confirm-above", 500));
        payCooldownMs = Math.max(0, cfg.getLong("pay.cooldown-seconds", 3)) * 1000L;
        payPerMinute = Math.max(1, cfg.getInt("pay.max-per-minute", 10));
        reserveStart = Math.max(0, cfg.getLong("vault.start", 2000));
        refillPerHour = Math.max(0, cfg.getLong("vault.refill-per-hour", 100));
        refillUpTo = Math.max(0, cfg.getLong("vault.refill-up-to", 2000));
        baltopSize = (int) clamp(cfg.getInt("baltop-size", 10), 1, 50);
        store.historySize = (int) clamp(cfg.getInt("history-size", 20), 1, 200);
        robberies.reload(cfg.getConfigurationSection("robbery"));
        if (wallet != null) wallet.reloaded();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        JoinDigest.unregister("bank");
        if (wallet != null) wallet.stop();
        store.onRecord = null;
        wallet = null;
        robberies.disable();
        long now = System.currentTimeMillis();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            BankStore.Account a = store.find(p.getUUID());
            if (a != null) a.lastSeen = now;
        }
        store.saveNow();
        store.shutdown();
        plugin.withdraw(Hooks.Bank.class);
        plugin.withdraw(Hooks.BankBlocks.class);
        plugin.withdraw(Hooks.TravelLock.class);
        if (active == this) active = null;
    }

    /**
     * Snapshots the bank now instead of at the end of the tick (written in the background): for another store that
     * saves right after paying coins (quest rollovers), so both files hold the same step.
     */
    public void saveNow() {
        store.flush();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    Text text() {
        return plugin.text();
    }

    // ------------------------------------------------------------------ hooks for the rest of Feather

    /** True while this player is the bank robber and the travel lock applies to them. */
    public boolean travelLocked(ServerPlayer p) {
        return p != null && robberies.locked(p);
    }

    @Override
    public boolean robbing(ServerPlayer p) {
        return p != null && robberies.isRobber(p);
    }

    @Override
    public String chestState() {
        if (store.unreadable) return "closed";
        Robberies.Robbery r = robberies.current();
        if (r != null && !r.ended) return "robbery";
        return robberies.enabled && robbableAt() > System.currentTimeMillis() ? "cooldown" : "open";
    }

    @Override
    public long robbableAt() {
        return store.lastRobbery + robberies.serverCooldownMs;
    }

    @Override
    public boolean robberyDeath(ServerPlayer p) {
        return p != null && (robberies.isRobber(p) || robberies.quitKill(p));
    }

    @Override
    public List<Loc> bankChests() {
        List<Loc> out = new ArrayList<>();
        for (BankStore.Pos pos : store.banks) {
            ServerLevel l = pos.level();
            if (l != null) out.add(new Loc(l, pos.x() + 0.5, pos.y(), pos.z() + 0.5));
        }
        return out;
    }

    /** True for a bank chest (chest locks and other modules leave it alone). */
    public boolean isBankBlock(ServerLevel level, BlockPos pos) {
        // Empty store first: with no bank anywhere (most chests, hoppers) this is one field read.
        return !store.banks.isEmpty() && level != null && pos != null && store.banks.contains(BankStore.Pos.of(level, pos));
    }

    /** True while the bank can't take money (its data file couldn't be read). Rewards wait instead of being lost. */
    public boolean closedForRewards() {
        return store.unreadable;
    }

    // ------------------------------------------------------------------ balance API (Hooks.Bank; server thread only)

    @Override
    public boolean isOpen() {
        return !store.unreadable;
    }

    @Override
    public long balance(UUID id) {
        BankStore.Account a = id == null ? null : store.find(id);
        return a == null ? 0 : a.balance;
    }

    @Override
    public long maxBalance() {
        return maxBalance;
    }

    @Override
    public boolean canCredit(UUID id, long amount) {
        if (id == null || amount < 0 || store.unreadable) return false;
        return balance(id) <= maxBalance - amount;
    }

    private static void mainThread(String what) {
        var s = Feather.serverOrNull();
        if (s != null && !s.isSameThread()) throw new IllegalStateException("bank " + what + " off the server thread");
    }

    @Override
    public long debit(UUID id, String name, long amount, String type, String note) {
        mainThread("debit");
        if (id == null || amount <= 0 || store.unreadable) return -1;
        BankStore.Account a = store.find(id);
        if (a == null || a.balance < amount) return -1;
        if (name != null) a.name = name;
        a.balance -= amount;
        store.record(a, type, -amount, note);
        store.log(type + " " + a.name + " " + id + " coins=-" + amount + " balance=" + a.balance
                + (note == null || note.isEmpty() ? "" : " (" + note + ")"));
        store.save();
        return a.balance;
    }

    @Override
    public boolean transfer(UUID from, String fromName, UUID to, String toName, long amount, String type, String noteFrom, String noteTo) {
        mainThread("transfer");
        if (from == null || to == null || from.equals(to) || amount <= 0 || store.unreadable) return false;
        BankStore.Account a = store.find(from);
        if (a == null || a.balance < amount) return false;
        BankStore.Account b = store.account(to);
        if (b.balance > maxBalance - amount) return false;
        if (fromName != null) a.name = fromName;
        if (b.name == null) b.name = toName != null ? toName : nameOf(to);
        a.balance -= amount;
        b.balance += amount;
        store.record(a, type, -amount, noteFrom);
        store.record(b, type, amount, noteTo);
        store.log(type + " " + a.name + " " + from + " -> " + b.name + " " + to + " coins=" + amount + " balances=" + a.balance + "/" + b.balance
                + (noteFrom == null || noteFrom.isEmpty() ? "" : " (" + noteFrom + ")"));
        store.save();
        // Coins that left the payer don't count toward their deposit quests (no deposit, pay, withdraw loops).
        coinsLeft(plugin.player(from), amount);
        return true;
    }

    @Override
    public void openScreen(ServerPlayer p) {
        if (robberies.isRobber(p)) {
            robberies.reopen(p);
            return;
        }
        BankStore.Pos pos = nearestBank(p, accessDistance);
        screen.open(p, pos == null ? BankStore.Pos.REMOTE : pos, false);
    }

    @Override
    public long credit(UUID id, String name, long amount, String type, String note) {
        mainThread("credit");
        if (id == null || amount <= 0 || store.unreadable) return -1;
        BankStore.Account a = store.account(id);
        if (a.name == null && name != null) a.name = name;
        if (a.balance > maxBalance - amount) return -1;
        a.balance += amount;
        store.record(a, type == null ? "REWARD" : type, amount, note);
        store.log((type == null ? "REWARD" : type) + " " + a.name + " " + id + " coins=" + amount + " balance=" + a.balance
                + (note == null || note.isEmpty() ? "" : " (" + note + ")"));
        store.save();
        return a.balance;
    }

    @Override
    public boolean depositToReserve(long amount, String source, String note) {
        if (amount <= 0 || store.unreadable) return false;
        store.reserve = Math.addExact(store.reserve, amount);
        store.log("RESERVE+ " + (source == null || source.isBlank() ? "OTHER" : source) + " coins=" + amount + " reserve=" + store.reserve
                + (note == null || note.isEmpty() ? "" : " (" + note + ")"));
        store.save();
        return true;
    }

    @Override
    public boolean isCoin(ItemStack item) {
        return coins.isCoin(item);
    }

    @Override
    public ItemStack coin(int count) {
        return coins.stack(count);
    }

    // ------------------------------------------------------------------ helpers

    static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    TagResolver n(String key, long value) {
        return Text.unparsed(key, fmt(value));
    }

    /** "Nobody called ..." (or which players an ambiguous name could mean), with the error sound. */
    void notFound(Sender to, String input) {
        plugin.notFound(to, input);
        if (to.player() != null) text().sound(to.player(), "error");
    }

    void error(Sender to, String key, TagResolver... r) {
        text().send(to, key, r);
        if (to.player() != null) text().sound(to.player(), "error");
    }

    void error(ServerPlayer to, String key, TagResolver... r) {
        text().send(to, key, r);
        text().sound(to, "error");
    }

    boolean closed(Sender to) {
        if (!store.unreadable) return false;
        error(to, "bank.closed");
        return true;
    }

    boolean closed(ServerPlayer to) {
        if (!store.unreadable) return false;
        error(to, "bank.closed");
        return true;
    }

    /** Parses a positive amount ("all"/"max" give {@code max}); -1 when invalid. */
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

    /** A plain diamond: no custom data, model, name or enchantments (nothing "special" is sold as a diamond). */
    static boolean plainDiamond(ItemStack s) {
        if (s == null || s.isEmpty() || !s.is(Items.DIAMOND)) return false;
        return s.getComponentsPatch().isEmpty();
    }

    private static final int[] SLOTS;

    static {
        SLOTS = new int[Inventory.INVENTORY_SIZE + 1];
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) SLOTS[i] = i;
        SLOTS[Inventory.INVENTORY_SIZE] = Inventory.SLOT_OFFHAND;
    }

    static long countDiamonds(ServerPlayer p) {
        Inventory inv = p.getInventory();
        long n = 0;
        for (int i : SLOTS) if (plainDiamond(inv.getItem(i))) n += inv.getItem(i).getCount();
        return n;
    }

    static void takeDiamonds(ServerPlayer p, long amount) {
        Inventory inv = p.getInventory();
        long left = amount;
        for (int i : SLOTS) {
            if (left <= 0) break;
            ItemStack s = inv.getItem(i);
            if (!plainDiamond(s)) continue;
            int use = (int) Math.min(left, s.getCount());
            left -= use;
            if (use >= s.getCount()) inv.setItem(i, ItemStack.EMPTY);
            else s.shrink(use);
        }
        inv.setChanged();
    }

    static long diamondRoom(ServerPlayer p) {
        long room = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) room += 64;
            else if (plainDiamond(s)) room += 64 - s.getCount();
        }
        return room;
    }

    // ------------------------------------------------------------------ bank chests

    /** The bank chest a player is standing at, or null. */
    BankStore.Pos nearestBank(ServerPlayer p, double maxDistance) {
        BankStore.Pos best = null;
        double bestD = maxDistance * maxDistance;
        String world = Worlds.name(p.level());
        for (BankStore.Pos pos : store.banks) {
            if (!pos.world().equals(world)) continue;
            double dx = pos.x() + 0.5 - p.getX(), dy = pos.y() + 0.5 - p.getY(), dz = pos.z() + 0.5 - p.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = pos;
            }
        }
        return best;
    }

    static boolean isChest(BlockState s) {
        return s != null && (s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST));
    }

    /** The other half of a double chest, or null. */
    static BlockPos partner(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!isChest(s) || s.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(s));
        return level.getBlockState(other).is(s.getBlock()) ? other : null;
    }

    /** Keeps a bank chest single: a chest that joined it is split off again. */
    private void keepSingle(ServerLevel level, BlockPos bank) {
        BlockState s = level.getBlockState(bank);
        if (!isChest(s) || s.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return;
        BlockPos other = partner(level, bank);
        level.setBlock(bank, s.setValue(ChestBlock.TYPE, ChestType.SINGLE), 2);
        if (other != null) {
            BlockState os = level.getBlockState(other);
            if (isChest(os)) level.setBlock(other, os.setValue(ChestBlock.TYPE, ChestType.SINGLE), 2);
        }
    }

    // ------------------------------------------------------------------ events

    private static void registerEvents() {
        // Mobs never walk off with dropped coins (Paper: Coins.onMobPickup).
        net.axther.aftrhrs.core.event.FeatherEvents.MOB_PICKUP.register((mob, stack) -> {
            BankModule m = active;
            return m == null || !m.coins.isCoin(stack);
        });
        // Right-click opens the bank (the robber gets their current minigame back).
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            BankModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (!m.isBankBlock(sl, pos)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
            if (!isChest(sl.getBlockState(pos))) return InteractionResult.FAIL;
            if (m.robberies.isRobber(p)) m.robberies.reopen(p);
            else m.screen.open(p, BankStore.Pos.of(sl, pos), false);
            return InteractionResult.SUCCESS;
        });
        // Left-click is a robbery attempt; the chest never starts breaking.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            BankModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
            if (!m.isBankBlock(sl, pos)) return InteractionResult.PASS;
            if (hand == InteractionHand.MAIN_HAND) m.robberies.attempt(p, sl, pos);
            return InteractionResult.FAIL;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            BankModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl) || !m.isBankBlock(sl, pos)) return true;
            if (p.isCreative() && Perms.has(p, ADMIN)) m.text().send(p, "bank.admin.cant-break");
            else m.robberies.attempt(p, sl, pos);
            return false;
        });
        // Backstop: nobody opens the chest's own inventory (spectators, other mods).
        FeatherEvents.CONTAINER_OPEN.register((p, provider) -> {
            BankModule m = active;
            return m == null || !(provider instanceof BlockEntity be) || !(be.getLevel() instanceof ServerLevel sl)
                    || !m.isBankBlock(sl, be.getBlockPos());
        });
        // Explosions, pistons, hoppers, fire.
        FeatherEvents.BLOCK_PROTECTED.register((level, pos) -> {
            BankModule m = active;
            return m != null && level instanceof ServerLevel sl && m.isBankBlock(sl, pos);
        });
        // No chest may join a bank chest into a double chest.
        FeatherEvents.BLOCK_PLACE.register((p, level, pos, state, stack) -> {
            BankModule m = active;
            if (m == null || m.store.banks.isEmpty()) return true;
            if (m.isBankBlock(level, pos)) return false;
            if (!isChest(state) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return true;
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (m.isBankBlock(level, other)) {
                m.text().send(p, "bank.no-double");
                return false;
            }
            return true;
        });
        FeatherEvents.BLOCK_PLACED.register((p, level, pos, state, stack) -> {
            BankModule m = active;
            if (m == null || m.store.banks.isEmpty() || !isChest(state)) return;
            // Belt and braces: whatever joined after placing, split it again next tick.
            Tasks.later(1, () -> {
                for (Direction f : SIDES) {
                    BlockPos n = pos.relative(f);
                    if (m.isBankBlock(level, n)) m.keepSingle(level, n);
                }
            });
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
            BankModule m = active;
            if (m != null) m.onJoin(h.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            BankModule m = active;
            if (m != null) m.onQuit(h.player);
        });
        // Joined on the death screen: the pending coins wait for the respawn.
        FeatherEvents.PLAYER_RESPAWN.register((old, now, alive) -> {
            BankModule m = active;
            if (m == null || alive) return;
            UUID id = now.getUUID();
            Tasks.later(5L, () -> {
                ServerPlayer pl = m.plugin.player(id);
                if (pl != null && active == m) m.deliverPending(pl, true);
            });
        });
        Robberies.registerEvents();
    }

    // ------------------------------------------------------------------ join / quit

    private void onJoin(ServerPlayer p) {
        if (store.unreadable) return;
        BankStore.Account a = store.find(p.getUUID());
        if (a == null) return;
        a.name = Names.name(p);
        a.lastSeen = System.currentTimeMillis();
        long away = a.awayInterest;
        a.awayInterest = 0;
        store.save();
        UUID id = p.getUUID();
        // The interest line goes out with the join digest (collectJoin).
        if (away > 0) awayNotes.merge(id, away, Long::sum);
        Tasks.later(40L, () -> {
            ServerPlayer pl = plugin.player(id);
            if (pl == null || active != this) return;
            deliverPending(pl, true);
        });
    }

    /** Join digest (order 40): the interest earned while away (zeroed and saved at the join). */
    private void collectJoin(ServerPlayer p, List<JoinDigest.Line> out) {
        Long away = awayNotes.remove(p.getUUID());
        if (away == null || away <= 0) return;
        BankStore.Account a = store.find(p.getUUID());
        Component line = JoinDigest.render("bank.interest-away", n("amount", away), n("balance", a == null ? 0 : a.balance));
        if (line != null) out.add(new JoinDigest.Line(40, line, null));
    }

    private void onQuit(ServerPlayer p) {
        robberies.onQuit(p);
        sessions.remove(p.getUUID());
        payTimes.remove(p.getUUID());
        awayNotes.remove(p.getUUID());
        BankStore.Account a = store.find(p.getUUID());
        if (a != null && !store.unreadable) {
            a.lastSeen = System.currentTimeMillis();
            store.save();
        }
    }

    /** Hands out pending coins that now fit. */
    void deliverPending(ServerPlayer p, boolean tell) {
        if (store.unreadable || p.isDeadOrDying()) return;
        BankStore.Account a = store.find(p.getUUID());
        if (a == null || a.pending <= 0) return;
        long want = a.pending;
        long left = coins.give(p, want);
        long given = want - left;
        if (given <= 0) {
            if (tell) text().send(p, "bank.pending-full", n("amount", a.pending));
            return;
        }
        a.pending = left;
        store.record(a, "PENDING", 0, given + " coins handed out");
        store.log("PENDING " + Names.name(p) + " " + p.getUUID() + " given=" + given + " still-pending=" + left);
        store.saveNow();
        saveInventory(p);
        if (tell) text().send(p, left > 0 ? "bank.pending-some" : "bank.pending-all", n("amount", given), n("left", left));
    }

    /**
     * Writes the player's data file now, right after a money step that moved items (the bank file is saved at once
     * too), so a crash can't bring back items whose coins were already counted, or the other way round.
     */
    static void saveInventory(ServerPlayer p) {
        if (p == null || p.hasDisconnected()) return;
        try {
            Feather.server().getPlayerList().save(p);
        } catch (RuntimeException e) {
            Feather.get().getLogger().warn("Could not save {}'s inventory after a bank step: {}", Names.name(p), e.toString());
        }
    }

    /** Coins left this player's balance for someone else: deposit quests and the deposit goal lose that much progress. */
    void coinsLeft(ServerPlayer p, long amount) {
        Hooks.Quests q = plugin.service(Hooks.Quests.class);
        if (p != null && amount > 0 && q != null) q.withdrew(p, amount);
    }

    /** Adds coins the player is owed to their inventory; what doesn't fit goes to their pending pile. */
    long payOut(ServerPlayer p, BankStore.Account a, long amount) {
        long left = coins.give(p, amount);
        if (left > 0) a.pending = Math.addExact(a.pending, left);
        return left;
    }

    // ------------------------------------------------------------------ money operations (the bank screen calls these)

    /** The player's open bank is still there and within reach (no message). */
    boolean nearBank(ServerPlayer p) {
        if (store.unreadable) return false;
        BankStore.Pos pos = sessions.get(p.getUUID());
        // /bank works anywhere (bank.yml anywhere: true): the screen needs no chest nearby.
        if (pos != null && anywhere) return true;
        if (pos == null || !store.banks.contains(pos)) return false;
        ServerLevel l = pos.level();
        if (l == null || l != p.level() || !isChest(l.getBlockState(pos.blockPos()))) return false;
        double dx = pos.x() + 0.5 - p.getX(), dy = pos.y() + 0.5 - p.getY(), dz = pos.z() + 0.5 - p.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= accessDistance + 1.5;
    }

    /** The player's open bank is still there and within reach (and they aren't robbing it). */
    boolean atBank(ServerPlayer p) {
        if (closed(p)) return false;
        if (!nearBank(p)) {
            error(p, "bank.too-far");
            return false;
        }
        if (robberies.isRobber(p)) {
            error(p, "bank.robbery.no-banking");
            return false;
        }
        return true;
    }

    boolean buy(ServerPlayer p, String raw) {
        if (!atBank(p)) return false;
        long have = countDiamonds(p);
        long d = parseAmount(raw, Math.min(have, maxDiamondsPerTrade));
        if (d <= 0) {
            error(p, have <= 0 ? "bank.no-diamonds" : "bank.bad-amount");
            return false;
        }
        if (d > maxDiamondsPerTrade) {
            error(p, "bank.too-many-diamonds", n("max", maxDiamondsPerTrade));
            return false;
        }
        if (d > have) {
            error(p, "bank.not-enough-diamonds", n("have", have));
            return false;
        }
        long coinsOut;
        try {
            coinsOut = Math.multiplyExact(d, buyRate);
        } catch (ArithmeticException e) {
            error(p, "bank.bad-amount");
            return false;
        }
        BankStore.Account a = store.account(p.getUUID());
        a.name = Names.name(p);
        takeDiamonds(p, d);
        long left = payOut(p, a, coinsOut);
        store.record(a, "BUY", coinsOut, d + " diamonds");
        store.log("BUY " + a.name + " " + p.getUUID() + " diamonds=" + d + " coins=" + coinsOut + " pending+=" + left);
        store.saveNow();
        saveInventory(p);
        text().send(p, "bank.bought", n("diamonds", d), n("coins", coinsOut));
        if (left > 0) text().send(p, "bank.pending-added", n("amount", left));
        text().sound(p, "accepted");
        return true;
    }

    boolean sell(ServerPlayer p, String raw) {
        if (!atBank(p)) return false;
        long have = coins.count(p);
        long affordable = have / sellRate;
        long d = parseAmount(raw, Math.min(affordable, maxDiamondsPerTrade));
        if (d <= 0) {
            error(p, affordable <= 0 ? "bank.not-enough-coins-sell" : "bank.bad-amount", n("rate", sellRate));
            return false;
        }
        if (d > maxDiamondsPerTrade) {
            error(p, "bank.too-many-diamonds", n("max", maxDiamondsPerTrade));
            return false;
        }
        long cost;
        try {
            cost = Math.multiplyExact(d, sellRate);
        } catch (ArithmeticException e) {
            error(p, "bank.bad-amount");
            return false;
        }
        if (cost > have) {
            error(p, "bank.not-enough-coins", n("have", have), n("need", cost));
            return false;
        }
        if (diamondRoom(p) < d) {
            error(p, "bank.no-room-diamonds");
            return false;
        }
        BankStore.Account a = store.account(p.getUUID());
        a.name = Names.name(p);
        if (coins.take(p, cost) != cost) {
            error(p, "bank.bad-amount");
            return false;
        }
        long left = d;
        while (left > 0) {
            int n = (int) Math.min(64, left);
            left -= n;
            ItemStack s = new ItemStack(Items.DIAMOND, n);
            p.getInventory().add(s);
            // Room was checked; never lose diamonds if something changed in between.
            if (!s.isEmpty()) p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        long spread = Math.max(0, cost - Math.multiplyExact(d, buyRate));
        store.reserve = Math.addExact(store.reserve, spread);
        store.record(a, "SELL", -cost, d + " diamonds");
        store.log("SELL " + a.name + " " + p.getUUID() + " diamonds=" + d + " coins=" + cost + " reserve+=" + spread);
        store.saveNow();
        saveInventory(p);
        text().send(p, "bank.sold", n("diamonds", d), n("coins", cost));
        text().sound(p, "accepted");
        return true;
    }

    boolean deposit(ServerPlayer p, String raw) {
        if (!atBank(p)) return false;
        long have = coins.count(p);
        long amount = parseAmount(raw, have);
        if (amount <= 0) {
            error(p, have <= 0 ? "bank.no-coins" : "bank.bad-amount");
            return false;
        }
        if (amount > have) {
            error(p, "bank.not-enough-coins", n("have", have), n("need", amount));
            return false;
        }
        long fee = (long) Math.floor(amount * depositFeePercent / 100.0);
        long credit = amount - fee;
        BankStore.Account a = store.account(p.getUUID());
        a.name = Names.name(p);
        if (a.balance > maxBalance - credit) {
            error(p, "bank.balance-cap", n("max", maxBalance));
            return false;
        }
        if (coins.take(p, amount) != amount) {
            error(p, "bank.bad-amount");
            return false;
        }
        a.balance += credit;
        store.reserve = Math.addExact(store.reserve, fee);
        store.record(a, "DEPOSIT", credit, fee > 0 ? "fee " + fee : "");
        store.log("DEPOSIT " + a.name + " " + p.getUUID() + " coins=" + amount + " fee=" + fee + " balance=" + a.balance);
        store.saveNow();
        saveInventory(p);
        text().send(p, fee > 0 ? "bank.deposited-fee" : "bank.deposited", n("amount", credit), n("fee", fee), n("balance", a.balance));
        text().sound(p, "accepted");
        Hooks.Quests q = plugin.service(Hooks.Quests.class);
        if (q != null) q.progress(p, "deposit", null, (int) Math.min(Integer.MAX_VALUE, credit));
        return true;
    }

    boolean withdraw(ServerPlayer p, String raw) {
        if (!atBank(p)) return false;
        BankStore.Account a = store.account(p.getUUID());
        a.name = Names.name(p);
        long amount = parseAmount(raw, a.balance);
        if (amount <= 0) {
            error(p, a.balance <= 0 ? "bank.empty-balance" : "bank.bad-amount");
            return false;
        }
        if (amount > a.balance) {
            error(p, "bank.not-enough-balance", n("balance", a.balance));
            return false;
        }
        long fee = (long) Math.floor(amount * withdrawFeePercent / 100.0);
        long out = amount - fee;
        a.balance -= amount;
        store.reserve = Math.addExact(store.reserve, fee);
        long left = payOut(p, a, out);
        store.record(a, "WITHDRAW", -amount, fee > 0 ? "fee " + fee : "");
        store.log("WITHDRAW " + a.name + " " + p.getUUID() + " coins=" + amount + " fee=" + fee + " pending+=" + left + " balance=" + a.balance);
        store.saveNow();
        saveInventory(p);
        text().send(p, fee > 0 ? "bank.withdrew-fee" : "bank.withdrew", n("amount", out), n("fee", fee), n("balance", a.balance));
        if (left > 0) text().send(p, "bank.pending-added", n("amount", left));
        text().sound(p, "accepted");
        coinsLeft(p, amount);
        return true;
    }

    // ------------------------------------------------------------------ interest

    /** When the payout of {@code epochDay} happens (epoch ms). */
    long payoutTime(long epochDay) {
        return java.time.LocalDate.ofEpochDay(epochDay).atTime(interestHour, 0).atZone(zone).toInstant().toEpochMilli();
    }

    /** The last day whose payout time has passed. */
    long dueDay() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        long day = now.toLocalDate().toEpochDay();
        return now.getHour() < interestHour ? day - 1 : day;
    }

    long nextPayout() {
        return payoutTime(dueDay() + 1);
    }

    long interestFor(long balance) {
        long base = Math.min(Math.max(0, balance), interestCap);
        return base * interestPpm / 1_000_000L;
    }

    private void minuteTick() {
        if (store.unreadable) return;
        refillReserve();
        runInterest();
    }

    private void refillReserve() {
        long now = System.currentTimeMillis();
        if (refillPerHour <= 0 || store.reserve >= refillUpTo) {
            store.reserveRefilledAt = now;
            return;
        }
        long elapsed = Math.max(0, now - store.reserveRefilledAt);
        long add = refillPerHour * elapsed / 3_600_000L;
        if (add <= 0) return;
        store.reserve = Math.min(refillUpTo, store.reserve + add);
        store.reserveRefilledAt = store.reserve >= refillUpTo ? now : store.reserveRefilledAt + add * 3_600_000L / refillPerHour;
        store.save();
    }

    /** Pays every day that is due since the last run, at most {@code catch-up-days} of them. */
    void runInterest() {
        if (!interestOn || store.unreadable) return;
        long due = dueDay();
        if (store.interestDay == Long.MIN_VALUE) {
            // First start: interest begins with the next payout.
            store.interestDay = due;
            store.save();
            return;
        }
        if (due <= store.interestDay) return;
        long missed = due - store.interestDay;
        long days = Math.min(missed, catchUpDays);
        Map<UUID, Long> online = new HashMap<>();
        for (long d = due - days + 1; d <= due; d++) payDay(payoutTime(d), online);
        store.interestDay = due;
        store.log("INTEREST-RUN days=" + days + " missed=" + missed);
        store.save();
        tellOnline(online);
    }

    /** Admin/test hook: pays {@code days} days of interest right now, outside the schedule. */
    long payInterestNow(int days) {
        Map<UUID, Long> online = new HashMap<>();
        long total = 0;
        for (int i = 0; i < days; i++) total += payDay(System.currentTimeMillis(), online);
        store.log("INTEREST-MANUAL days=" + days + " total=" + total);
        store.save();
        tellOnline(online);
        return total;
    }

    private void tellOnline(Map<UUID, Long> online) {
        for (Map.Entry<UUID, Long> e : online.entrySet()) {
            ServerPlayer p = plugin.player(e.getKey());
            BankStore.Account a = store.find(e.getKey());
            if (p != null && a != null && e.getValue() > 0) text().send(p, "bank.interest-paid", n("amount", e.getValue()), n("balance", a.balance));
        }
    }

    /** One day's interest; returns the total paid. */
    private long payDay(long at, Map<UUID, Long> onlineEarned) {
        long total = 0;
        long activeSince = at - activeDays * DAY_MS;
        for (BankStore.Account a : store.accounts.values()) {
            ServerPlayer p = plugin.player(a.id);
            if (p == null && a.lastSeen < activeSince) continue;
            long amount = Math.min(interestFor(a.balance), maxBalance - a.balance);
            if (amount <= 0) continue;
            a.balance += amount;
            total += amount;
            store.record(a, "INTEREST", amount, "");
            if (p != null) onlineEarned.merge(a.id, amount, Long::sum);
            else a.awayInterest = Math.addExact(a.awayInterest, amount);
        }
        return total;
    }

    // ------------------------------------------------------------------ pay

    /** Who a typed name means; {@code exact}: it is that player's username, not a nickname or a prefix. */
    record Found(UUID id, boolean exact) {
    }

    /**
     * A typed name: an exact username first (online, then known to the server or the bank), so "Bob" never pays the
     * online "Bobby" while Bob is away; only then a nickname or a unique prefix (see Feather#findPlayer).
     */
    Found find(Sender viewer, String name) {
        if (name == null || name.isBlank()) return null;
        ServerPlayer v = viewer.player();
        ServerPlayer online = Feather.server().getPlayerList().getPlayerByName(name);
        if (online != null && (v == null || plugin.canSee(v, online))) return new Found(online.getUUID(), true);
        OfflinePlayer cached = plugin.lookup().byName(name);
        if (cached != null && cached.name() != null && name.equalsIgnoreCase(cached.name()) && (cached.isOnline() || cached.hasPlayedBefore())) {
            return new Found(cached.id(), true);
        }
        for (BankStore.Account a : store.accounts.values()) if (name.equalsIgnoreCase(a.name)) return new Found(a.id, true);
        ServerPlayer fuzzy = plugin.findPlayer(viewer, name);
        if (fuzzy != null) return new Found(fuzzy.getUUID(), name.equalsIgnoreCase(Names.name(fuzzy)));
        OfflinePlayer known = plugin.findOfflinePlayer(viewer, name);
        return known != null && known.hasPlayedBefore() ? new Found(known.id(), name.equalsIgnoreCase(known.name())) : null;
    }

    /** {@link #find} as an id. */
    UUID resolve(Sender viewer, String name) {
        Found f = find(viewer, name);
        return f == null ? null : f.id();
    }

    String nameOf(UUID id) {
        ServerPlayer p = plugin.player(id);
        if (p != null) return Names.name(p);
        BankStore.Account a = store.find(id);
        if (a != null && a.name != null) return a.name;
        OfflinePlayer o = plugin.lookup().byId(id);
        String n = o == null ? null : o.name();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    void pay(ServerPlayer from, UUID to, long amount, boolean confirmed) {
        pay(from, to, amount, confirmed, null);
    }

    /** {@code typed}: the name typed when it matched by nickname or prefix (always asks first), else null. */
    void pay(ServerPlayer from, UUID to, long amount, boolean confirmed, String typed) {
        if (closed(from)) return;
        if (to.equals(from.getUUID())) {
            error(from, "bank.pay.self");
            return;
        }
        if (amount < payMin) {
            error(from, "bank.pay.min", n("min", payMin));
            return;
        }
        BankStore.Account a = store.account(from.getUUID());
        a.name = Names.name(from);
        if (amount > a.balance) {
            error(from, "bank.not-enough-balance", n("balance", a.balance));
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = payTimes.computeIfAbsent(from.getUUID(), k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000L) times.pollFirst();
        if (!times.isEmpty() && now - times.peekLast() < payCooldownMs) {
            error(from, "bank.pay.slow-down", Text.unparsed("seconds", (payCooldownMs - (now - times.peekLast()) + 999) / 1000));
            return;
        }
        if (times.size() >= payPerMinute) {
            error(from, "bank.pay.too-many", Text.unparsed("max", payPerMinute));
            return;
        }
        BankStore.Account b = store.account(to);
        if (b.name == null) b.name = nameOf(to);
        if (b.balance > maxBalance - amount) {
            error(from, "bank.pay.their-cap");
            return;
        }
        if (!confirmed && (typed != null || (payConfirmAbove > 0 && amount >= payConfirmAbove))) {
            screen.confirmPay(from, to, b.name, amount, typed);
            return;
        }
        times.addLast(now);
        a.balance -= amount;
        b.balance += amount;
        store.record(a, "PAY", -amount, "to " + b.name);
        store.record(b, "PAY", amount, "from " + a.name);
        store.log("PAY " + a.name + " " + from.getUUID() + " -> " + b.name + " " + to + " coins=" + amount
                + " balances=" + a.balance + "/" + b.balance);
        store.save();
        text().send(from, "bank.pay.sent", n("amount", amount), Text.unparsed("player", b.name), n("balance", a.balance));
        text().sound(from, "accepted");
        coinsLeft(from, amount);
        ServerPlayer target = plugin.player(to);
        if (target != null) {
            text().send(target, "bank.pay.received", n("amount", amount), Text.component("player", Names.display(from)), n("balance", b.balance));
            text().sound(target, "item-pickup");
        }
    }

    // ------------------------------------------------------------------ commands

    /** One of the bank's commands (bank, balance, pay, baltop). */
    private final class Bound implements FeatherCommand {
        private final String command;

        Bound(String command) {
            this.command = command;
        }

        @Override
        public boolean onCommand(Sender sender, String label, String[] args) {
            switch (command) {
                case "balance" -> balanceCommand(sender, args);
                case "pay" -> payCommand(sender, args);
                case "baltop" -> baltop(sender);
                default -> bankCommand(sender, args);
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(Sender sender, String label, String[] args) {
            return complete(command, sender, args);
        }
    }

    private void balanceCommand(Sender sender, String[] args) {
        if (closed(sender)) return;
        if (args.length > 0 && sender.hasPermission(ADMIN)) {
            UUID id = resolve(sender, args[0]);
            if (id == null) {
                notFound(sender, args[0]);
                return;
            }
            BankStore.Account a = store.find(id);
            text().send(sender, "bank.balance-other", Text.unparsed("player", nameOf(id)), n("balance", a == null ? 0 : a.balance),
                    n("pending", a == null ? 0 : a.pending));
            return;
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            text().send(sender, "general.players-only");
            return;
        }
        BankStore.Account a = store.find(p.getUUID());
        long bal = a == null ? 0 : a.balance;
        text().send(p, "bank.balance", n("balance", bal), n("pocket", coins.count(p)), n("pending", a == null ? 0 : a.pending),
                Text.unparsed("next", until(nextPayout())), n("interest", interestFor(bal)));
    }

    private void payCommand(Sender sender, String[] args) {
        ServerPlayer p = sender.player();
        if (p == null) {
            text().send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            text().send(p, "bank.pay.usage");
            return;
        }
        Found to = find(sender, args[0]);
        if (to == null) {
            notFound(sender, args[0]);
            return;
        }
        long amount = parseAmount(args[1], -1);
        if (amount <= 0) {
            error(p, "bank.bad-amount");
            return;
        }
        pay(p, to.id(), amount, false, to.exact() ? null : args[0]);
    }

    private void baltop(Sender sender) {
        if (closed(sender)) return;
        List<BankStore.Account> top = new ArrayList<>(store.accounts.values());
        top.removeIf(a -> a.balance <= 0);
        top.sort(Comparator.comparingLong((BankStore.Account a) -> a.balance).reversed());
        text().send(sender, "bank.baltop.header");
        if (top.isEmpty()) text().send(sender, "bank.baltop.empty");
        for (int i = 0; i < Math.min(baltopSize, top.size()); i++) {
            BankStore.Account a = top.get(i);
            text().send(sender, "bank.baltop.line", Text.unparsed("rank", i + 1), Text.unparsed("player", nameOf(a.id)), n("balance", a.balance));
        }
    }

    /** "3h 12m" style time until {@code epochMs}. */
    static String until(long epochMs) {
        Duration d = Duration.ofMillis(Math.max(0, epochMs - System.currentTimeMillis()));
        long h = d.toHours();
        long m = d.toMinutesPart();
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + d.toSecondsPart() + "s";
        return d.toSecondsPart() + "s";
    }

    private void bankCommand(Sender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = sender.hasPermission(ADMIN);
        ServerPlayer p = sender.player();
        switch (sub) {
            case "" -> {
                if (p == null) {
                    text().send(sender, "bank.help-admin");
                    return;
                }
                BankStore.Pos pos = nearestBank(p, accessDistance);
                if (pos == null && !anywhere) {
                    text().send(p, admin ? "bank.help-admin" : "bank.help");
                    return;
                }
                if (robberies.isRobber(p)) robberies.reopen(p);
                else screen.open(p, pos == null ? BankStore.Pos.REMOTE : pos, false);
            }
            case "defend" -> {
                if (p == null) {
                    text().send(sender, "general.players-only");
                    return;
                }
                robberies.defend(p);
            }
            case "rob" -> {
                if (p == null) return;
                BankStore.Pos pos = nearestBank(p, accessDistance);
                ServerLevel l = pos == null ? null : pos.level();
                if (l == null) error(p, "bank.too-far");
                else robberies.attempt(p, l, pos.blockPos());
            }
            case "create", "remove", "list", "give", "take", "set", "info", "interest", "robbery" -> {
                if (!admin) {
                    error(sender, "general.no-permission");
                    return;
                }
                adminCommand(sender, sub, args);
            }
            default -> text().send(sender, admin ? "bank.help-admin" : "bank.help");
        }
    }

    /** The block the player is looking at within {@code reach} blocks, or null. */
    private static BlockPos target(ServerPlayer p, double reach) {
        HitResult hit = p.pick(reach, 1f, false);
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
    }

    private void adminCommand(Sender sender, String sub, String[] args) {
        switch (sub) {
            case "create", "remove" -> {
                ServerPlayer p = sender.player();
                if (p == null) {
                    text().send(sender, "general.players-only");
                    return;
                }
                if (closed(p)) return;
                ServerLevel level = p.level();
                BlockPos b = target(p, 6);
                if (sub.equals("remove")) {
                    BankStore.Pos pos = b != null && isBankBlock(level, b) ? BankStore.Pos.of(level, b) : nearestBank(p, 3);
                    if (pos == null) {
                        error(p, "bank.admin.not-a-bank");
                        return;
                    }
                    store.banks.remove(pos);
                    store.log("BANK-REMOVE " + pos.pretty() + " by " + Names.name(p));
                    store.save();
                    text().send(p, "bank.admin.removed", Text.unparsed("where", pos.pretty()));
                    return;
                }
                if (b == null || !isChest(level.getBlockState(b))) {
                    error(p, "bank.admin.look-at-chest");
                    return;
                }
                if (isBankBlock(level, b)) {
                    error(p, "bank.admin.already");
                    return;
                }
                if (partner(level, b) != null) {
                    error(p, "bank.admin.single-only");
                    return;
                }
                BankStore.Pos pos = BankStore.Pos.of(level, b);
                store.banks.add(pos);
                store.log("BANK-CREATE " + pos.pretty() + " by " + Names.name(p));
                store.save();
                text().send(p, "bank.admin.created", Text.unparsed("where", pos.pretty()));
                text().sound(p, "accepted");
            }
            case "list" -> {
                text().send(sender, "bank.admin.list-header", Text.unparsed("count", store.banks.size()));
                for (BankStore.Pos pos : store.banks) text().send(sender, "bank.admin.list-line", Text.unparsed("where", pos.pretty()));
            }
            case "give", "take", "set" -> {
                if (closed(sender)) return;
                if (args.length < 3) {
                    text().send(sender, "bank.help-admin");
                    return;
                }
                Found found = find(sender, args[1]);
                long amount = sub.equals("set") && args[2].equals("0") ? 0 : parseAmount(args[2], -1);
                if (found == null) {
                    notFound(sender, args[1]);
                    return;
                }
                if (amount < 0) {
                    error(sender, "bank.bad-amount");
                    return;
                }
                if (!found.exact()) {
                    // A nickname or a prefix: staff confirm who they meant first (console types the exact name).
                    if (sender.player() != null) screen.confirmAdmin(sender.player(), sub, found.id(), nameOf(found.id()), amount, args[1]);
                    else error(sender, "bank.admin.exact-name", Text.unparsed("input", args[1]), Text.unparsed("player", nameOf(found.id())));
                    return;
                }
                adminBalance(sender, sub, found.id(), amount);
            }
            case "info" -> {
                long total = 0, pending = 0;
                for (BankStore.Account a : store.accounts.values()) {
                    total += a.balance;
                    pending += a.pending;
                }
                text().send(sender, "bank.admin.info", n("accounts", store.accounts.size()), n("total", total), n("pending", pending),
                        n("reserve", store.reserve), Text.unparsed("banks", store.banks.size()),
                        Text.unparsed("next", until(nextPayout())), Text.unparsed("rate", rateText()),
                        n("robberies", store.robberies), n("won", store.robberiesWon), n("stolen", store.stolen),
                        Text.unparsed("robbery", robberies.statusLine()));
            }
            case "interest" -> {
                if (closed(sender)) return;
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                int days = 1;
                if (args.length > 2) {
                    long v = parseAmount(args[2], -1);
                    if (v <= 0 || v > 3650) {
                        error(sender, "bank.bad-amount");
                        return;
                    }
                    days = (int) v;
                }
                if (what.equals("pay")) {
                    long total = payInterestNow(days);
                    text().send(sender, "bank.admin.interest-paid", n("total", total), Text.unparsed("days", days));
                } else if (what.equals("simulate-downtime")) {
                    // Pretends the server was down for <days> days: the next check catches up (capped as usual).
                    store.interestDay = dueDay() - days;
                    long before = totalBalances();
                    runInterest();
                    text().send(sender, "bank.admin.interest-caught-up", n("total", totalBalances() - before), Text.unparsed("days", days),
                            Text.unparsed("max", catchUpDays));
                } else {
                    text().send(sender, "bank.help-admin");
                }
            }
            case "robbery" -> robberies.command(sender, args);
            default -> text().send(sender, "bank.help-admin");
        }
    }

    /** /bank give|take|set once the player is certain. */
    void adminBalance(Sender sender, String sub, UUID id, long amount) {
        if (closed(sender)) return;
        BankStore.Account a = store.account(id);
        if (a.name == null) a.name = nameOf(id);
        long before = a.balance;
        long after = switch (sub) {
            case "give" -> a.balance > maxBalance - amount ? -1 : a.balance + amount;
            case "take" -> Math.max(0, a.balance - amount);
            default -> amount > maxBalance ? -1 : amount;
        };
        if (after < 0) {
            error(sender, "bank.balance-cap", n("max", maxBalance));
            return;
        }
        a.balance = after;
        store.record(a, "ADMIN", after - before, sub + " by " + sender.getName());
        store.log("ADMIN-" + sub.toUpperCase(Locale.ROOT) + " " + a.name + " " + id + " " + before + " -> " + after + " by " + sender.getName());
        store.save();
        text().send(sender, "bank.admin.balance-set", Text.unparsed("player", a.name), n("balance", after), n("before", before));
    }

    private long totalBalances() {
        long t = 0;
        for (BankStore.Account a : store.accounts.values()) t += a.balance;
        return t;
    }

    String rateText() {
        return java.math.BigDecimal.valueOf(interestPpm, 4).stripTrailingZeros().toPlainString();
    }

    private List<String> complete(String name, Sender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (name.equals("pay")) {
            if (args.length == 1) out.addAll(plugin.completeOthers(sender, args[0]));
        } else if (name.equals("balance")) {
            if (args.length == 1 && sender.hasPermission(ADMIN)) out.addAll(plugin.completePlayers(sender, args[0]));
        } else if (name.equals("bank")) {
            boolean admin = sender.hasPermission(ADMIN);
            if (args.length == 1) {
                out.add("rob");
                out.add("defend");
                if (admin) out.addAll(List.of("create", "remove", "list", "give", "take", "set", "info", "interest", "robbery"));
            } else if (admin && args.length == 2) {
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "give", "take", "set" -> out.addAll(plugin.completePlayers(sender, args[1]));
                    case "interest" -> out.addAll(List.of("pay", "simulate-downtime"));
                    case "robbery" -> out.addAll(List.of("status", "stop", "cooldown"));
                    default -> {
                    }
                }
            } else if (admin && args.length == 3 && args[0].equalsIgnoreCase("robbery") && args[1].equalsIgnoreCase("cooldown")) {
                out.add("reset");
            } else if (admin && args.length == 4 && args[0].equalsIgnoreCase("robbery") && args[1].equalsIgnoreCase("cooldown")) {
                out.addAll(plugin.completePlayers(sender, args[3]));
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    /** Sends a chat line to every online player and the console. */
    void broadcast(Component msg) {
        for (ServerPlayer o : plugin.onlinePlayers()) Adv.send(o, msg);
        Feather.server().sendSystemMessage(Adv.vanilla(msg));
    }

    /** True if the Companion is on for this player with the wallet feature. */
    static boolean modded(ServerPlayer p, String feature) {
        return Companion.has(p, feature);
    }
}
