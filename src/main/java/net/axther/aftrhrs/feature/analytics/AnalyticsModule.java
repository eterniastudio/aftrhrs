package net.axther.aftrhrs.feature.analytics;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalEntityTypeTags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Analytics: per-player counters (blocks, playtime, kills, distance, fishing ...) and server-wide daily buckets
 * (unique and new players, peak online, session lengths, an hour-of-day histogram). Everything is counted in
 * memory; a background thread writes {@code aftrhrs/data/stats/} every few minutes and on shutdown (same files and
 * format as Feather's {@code plugins/Feather/data/stats/}).
 *
 * <p>Other features count through {@link Hooks.Stats} ({@code feather.stat(uuid, stat, n)}), read with {@link #get} and
 * rank with {@link #top}. Units: playtime and sessions in seconds, distance in blocks, damage in health points
 * (2 per heart), times in epoch ms. {@code /stats} shows a player their numbers, {@code /analytics} gives staff the
 * server view and CSV export, and {@link LeaderboardMenu} ranks players.
 */
public final class AnalyticsModule implements FeatherModule {
    /** A player's place on a ranking. */
    public record Ranked(UUID id, String name, long value) {
    }

    /** One day of server-wide numbers. */
    static final class Day {
        final Set<UUID> unique = new HashSet<>();
        int newPlayers;
        int peak;
        long joins;
        long sessions;
        long sessionSeconds;
    }

    /** Stats that are points in time or records, not counters; left out of totals. */
    static final Set<String> NOT_SUMMED = Set.of("first-join", "last-join", "longest-session");
    /** Every counter, in the order the totals page lists them. */
    static final List<String> COUNTERS = List.of("blocks-mined", "blocks-placed", "playtime", "playtime-active", "playtime-afk",
            "mob-kills", "player-kills", "deaths", "damage-dealt", "damage-taken", "distance", "distance-walked",
            "distance-flown", "distance-swum", "distance-ridden", "fish-caught", "items-crafted", "items-enchanted",
            "messages-sent", "commands-used", "teleports", "homes-set", "pets-tamed", "bosses-defeated",
            "bounties-placed", "bounties-claimed", "sessions");
    static final List<String> GROUPS = List.of("blocks", "playtime", "kills", "damage", "distance", "fishing", "crafting",
            "enchanting", "chat", "commands", "teleports", "homes", "pets", "bosses", "bounties", "sessions");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    private static volatile AnalyticsModule active;

    private final Feather plugin;
    private final Path folder;
    private final Path playersFolder;
    private final Map<UUID, Map<String, Long>> stats = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    /** {mtimeMillis, size} of each player file as last listed or written (no entry: no file of ours yet). */
    private final Map<UUID, long[]> stamps = new ConcurrentHashMap<>();
    /** Flushes handed to the IO thread and not written yet: only the last one writes the index. */
    private final AtomicInteger pendingFlushes = new AtomicInteger();
    /** A rewrite of the index was skipped for a flush still queued: that flush writes it even with no players (IO thread). */
    private volatile boolean indexStale;
    /** The cache of every player file ({@link #writeIndex}); outside players/, so the *.yml listing never sees it. */
    private final Path indexFile;
    /** The last load: players taken from the index, player files read (tests, log). */
    private int loadedFromIndex, loadedRead;
    // Server thread only from here on.
    private final TreeMap<String, Day> days = new TreeMap<>();
    private final long[] hours = new long[24];
    private long totalJoins;
    private long sessionsTotal;
    private long sessionSecondsTotal;
    private int peakEver;
    private boolean serverDirty;
    private final Map<UUID, Long> sessionStart = new HashMap<>();
    private final Map<UUID, Loc> lastPos = new HashMap<>();
    private final Map<UUID, Map<String, Double>> fractions = new HashMap<>();
    /** Health + absorption just before a hit, so damage counts what actually came off (Paper's final damage). */
    private final Map<LivingEntity, Float> healthBefore = new WeakHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Feather-Stats");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> off = new HashSet<>();
    private Tasks.Task sampler;
    private Tasks.Task flusher;
    private Tasks.Task minutely;
    private int sampleSeconds;
    private int flushMinutes = -1;
    private int retentionDays;
    private int topMobs;
    private ZoneId zone = ZoneId.systemDefault();
    private List<String> bossTypes = List.of();
    private double bossRadius;
    private LeaderboardMenu leaderboards;

    public AnalyticsModule(Feather plugin) {
        this(plugin, plugin.getDataFolder().resolve("data").resolve("stats"));
    }

    private AnalyticsModule(Feather plugin, Path folder) {
        this.plugin = plugin;
        this.folder = folder;
        this.playersFolder = folder.resolve("players");
        this.indexFile = folder.resolve("players.idx");
    }

    /** A module on its own stats folder, never enabled: only its load / flush / index (tests). */
    public static AnalyticsModule detached(Feather plugin, Path statsFolder) {
        return new AnalyticsModule(plugin, statsFolder);
    }

    /** Loads the stats folder as {@link #enable} does; returns {players taken from the index, player files read} (tests). */
    public int[] loadForTest() {
        stats.clear();
        names.clear();
        stamps.clear();
        load();
        return new int[]{loadedFromIndex, loadedRead};
    }

    /** Waits for the IO thread to finish what is queued (tests). */
    public void drainForTest() throws Exception {
        io.submit(() -> {
        }).get(30, TimeUnit.SECONDS);
    }

    /** Writes every dirty player now, as the flush timer does (tests). */
    public void flushForTest() {
        flush(false);
    }

    /** Stops the IO thread of a {@link #detached} module (tests). */
    public void closeForTest() {
        io.shutdown();
    }

    @Override
    public String name() {
        return "Analytics";
    }

    @Override
    public void enable() {
        readConfig();
        load();
        active = this;
        plugin.once("analytics.events", AnalyticsModule::registerEvents);
        plugin.provide(Hooks.Stats.class, this::increment);
        plugin.bind("stats", new StatsCommand());
        plugin.bind("analytics", new AdminCommand());
        leaderboards = new LeaderboardMenu(plugin, this);
        plugin.bind("leaderboard", leaderboards);
        plugin.hub().add("leaderboards", Hub.Group.COMMUNITY, Hub.ORDER_LEADERBOARDS, "hub-social", "feather.leaderboard", this::openLeaderboards);
        plugin.hub().add("stats", Hub.Group.COMMUNITY, Hub.ORDER_STATS, "hub-social", "feather.stats", this::openStats);
        long now = System.currentTimeMillis();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            names.put(p.getUUID(), Names.name(p));
            sessionStart.put(p.getUUID(), now);
            lastPos.put(p.getUUID(), Loc.of(p));
        }
        schedule();
        leaderboards.reload();
    }

    @Override
    public void reload() {
        int oldFlush = flushMinutes, oldSample = sampleSeconds;
        readConfig();
        if (oldFlush != flushMinutes || oldSample != sampleSeconds) schedule();
        if (leaderboards != null) leaderboards.reload();
    }

    private void readConfig() {
        Yaml c = plugin.getConfig();
        off.clear();
        for (String g : GROUPS) if (!c.getBoolean("analytics.track." + g, true)) off.add(g);
        sampleSeconds = Math.max(1, c.getInt("analytics.sample-seconds", 5));
        flushMinutes = Math.max(1, c.getInt("analytics.flush-minutes", 5));
        retentionDays = Math.max(1, c.getInt("analytics.retention-days", 90));
        topMobs = Math.max(1, c.getInt("analytics.top-mobs", 5));
        String z = c.getString("analytics.timezone", "system");
        try {
            zone = z == null || z.isBlank() || z.equalsIgnoreCase("system") ? ZoneId.systemDefault() : ZoneId.of(z);
        } catch (RuntimeException e) {
            plugin.getLogger().warn("analytics.timezone '{}' is not a time zone; using the system one.", z);
            zone = ZoneId.systemDefault();
        }
        bossTypes = c.getStringList("analytics.bosses.types").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        bossRadius = Math.max(0, c.getDouble("analytics.bosses.share-radius", 32));
    }

    private void schedule() {
        if (sampler != null) sampler.cancel();
        if (flusher != null) flusher.cancel();
        if (minutely != null) minutely.cancel();
        sampler = Tasks.timer(sampleSeconds * 20L, sampleSeconds * 20L, this::sample);
        minutely = Tasks.timer(1200L, 1200L, this::minute);
        long flush = flushMinutes * 1200L;
        flusher = Tasks.timer(flush, flush, () -> flush(false));
    }

    @Override
    public void disable() {
        if (sampler != null) sampler.cancel();
        if (flusher != null) flusher.cancel();
        if (minutely != null) minutely.cancel();
        if (leaderboards != null) leaderboards.stop();
        if (active == this) active = null;
        plugin.withdraw(Hooks.Stats.class);
        plugin.hub().remove("leaderboards");
        plugin.hub().remove("stats");
        for (ServerPlayer p : plugin.onlinePlayers()) endSession(p);
        flush(true);
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warn("Some stats writes timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    LeaderboardMenu leaderboards() {
        return leaderboards;
    }

    // ------------------------------------------------------------------ events (registered once per JVM)

    private static void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
            AnalyticsModule m = active;
            if (m != null) m.onJoin(h.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            AnalyticsModule m = active;
            if (m != null) m.endSession(h.player);
        });
        FeatherEvents.PLAYER_TELEPORTED.register((p, from, to, cause) -> {
            AnalyticsModule m = active;
            if (m != null) m.onTeleport(p, from, to, cause);
        });
        FeatherEvents.PLAYER_RESPAWN.register((o, n, alive) -> {
            AnalyticsModule m = active;
            if (m != null) m.lastPos.put(n.getUUID(), Loc.of(n));
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, origin, dest) -> {
            AnalyticsModule m = active;
            if (m != null) m.lastPos.put(p.getUUID(), Loc.of(p));
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            AnalyticsModule m = active;
            if (m != null) m.increment(player.getUUID(), "blocks-mined", 1);
        });
        FeatherEvents.BLOCK_PLACED.register((p, level, pos, state, stack) -> {
            AnalyticsModule m = active;
            if (m != null) m.increment(p.getUUID(), "blocks-placed", 1);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            AnalyticsModule m = active;
            if (m != null) m.healthBefore.put(entity, entity.getHealth() + entity.getAbsorptionAmount());
            return true;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            AnalyticsModule m = active;
            if (m != null) m.onDamage(entity, source, taken, false);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            AnalyticsModule m = active;
            if (m == null) return;
            m.onDamage(entity, source, 0, true);
            m.onDeath(entity, source);
        });
        FeatherEvents.FISH_CAUGHT.register((p, rod, hook, loot) -> {
            AnalyticsModule m = active;
            // While the fishing module runs (it provides FishRarity) it counts its own catches through feather.stat.
            if (m != null && m.plugin.service(net.axther.aftrhrs.core.Hooks.FishRarity.class) == null) m.increment(p.getUUID(), "fish-caught", 1);
        });
        FeatherEvents.STAT_AWARDED.register((p, stat, n) -> {
            AnalyticsModule m = active;
            if (m != null) m.onStat(p, stat, n);
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            AnalyticsModule m = active;
            if (m != null && !m.plugin.authPending(sender)) m.increment(sender.getUUID(), "messages-sent", 1);
        });
        FeatherEvents.COMMAND.register((p, cmd) -> {
            AnalyticsModule m = active;
            if (m != null) m.increment(p.getUUID(), "commands-used", 1);
            return true;
        });
        FeatherEvents.ANIMAL_TAMED.register((p, animal) -> {
            AnalyticsModule m = active;
            if (m != null) m.increment(p.getUUID(), "pets-tamed", 1);
        });
    }

    // ------------------------------------------------------------------ API

    /** Which config group ({@code analytics.track.<group>}) a stat belongs to; "other" stats are always counted. */
    static String groupOf(String stat) {
        if (stat.startsWith("blocks-")) return "blocks";
        if (stat.startsWith("playtime")) return "playtime";
        if (stat.equals("mob-kills") || stat.startsWith("mob.") || stat.equals("player-kills") || stat.equals("deaths")) return "kills";
        if (stat.startsWith("damage-")) return "damage";
        if (stat.startsWith("distance")) return "distance";
        if (stat.startsWith("bosses-") || stat.startsWith("boss.")) return "bosses";
        if (stat.startsWith("bounties-")) return "bounties";
        return switch (stat) {
            case "fish-caught" -> "fishing";
            case "items-crafted" -> "crafting";
            case "items-enchanted" -> "enchanting";
            case "messages-sent" -> "chat";
            case "commands-used" -> "commands";
            case "teleports" -> "teleports";
            case "homes-set" -> "homes";
            case "pets-tamed" -> "pets";
            case "sessions", "first-join", "last-join", "longest-session" -> "sessions";
            default -> "other";
        };
    }

    /** Whether a stat is being counted (its group is on in config.yml). */
    public boolean tracking(String stat) {
        return !off.contains(groupOf(stat));
    }

    /** Adds to a player's counter. Thread-safe and cheap: no disk work. */
    public void increment(UUID id, String stat, long amount) {
        if (id == null || stat == null || amount == 0 || !tracking(stat)) return;
        stats.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).merge(stat, amount, Long::sum);
        dirty.add(id);
    }

    /** A player's value for a stat (0 if never counted). */
    public long get(UUID id, String stat) {
        Map<String, Long> m = stats.get(id);
        if (m == null) return 0;
        Long v = m.get(stat);
        return v == null ? 0 : v;
    }

    /** The {@code n} players with the highest value for a stat (ties by name), skipping zeros. Thread-safe. */
    public List<Ranked> top(String stat, int n) {
        List<Ranked> all = ranking(stat, Set.of());
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    /** Everyone with a non-zero value for a stat, best first. */
    List<Ranked> ranking(String stat, Set<String> hidden) {
        List<Ranked> out = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Long>> e : stats.entrySet()) {
            Long v = e.getValue().get(stat);
            if (v == null || v <= 0) continue;
            String name = nameOf(e.getKey());
            if (!hidden.isEmpty() && (hidden.contains(name.toLowerCase(Locale.ROOT)) || hidden.contains(e.getKey().toString()))) continue;
            out.add(new Ranked(e.getKey(), name, v));
        }
        out.sort(Comparator.comparingLong(Ranked::value).reversed().thenComparing(Ranked::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The last name a player was seen with. */
    public String nameOf(UUID id) {
        String n = names.get(id);
        if (n != null) return n;
        MinecraftServer server = Feather.serverOrNull();
        if (server == null || !server.isSameThread()) return id.toString().substring(0, 8);
        OfflinePlayer op = plugin.lookup().byId(id);
        return op != null && op.name() != null ? op.name() : id.toString().substring(0, 8);
    }

    /** Finds a known player by name (case-insensitive), online or not. */
    UUID findKnown(Sender viewer, String name) {
        ServerPlayer viewerPlayer = viewer == null ? null : viewer.player();
        for (ServerPlayer online : plugin.onlinePlayers()) {
            if (Names.name(online).equalsIgnoreCase(name) && plugin.canSee(viewerPlayer, online)) return online.getUUID();
        }
        for (Map.Entry<UUID, String> e : names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        // Nicknames (online or saved) and unique prefixes.
        OfflinePlayer known = plugin.findOfflinePlayer(viewer, name);
        return known != null && (known.isOnline() || known.hasPlayedBefore()) ? known.id() : null;
    }

    private void put(UUID id, String stat, long value) {
        if (!tracking(stat)) return;
        stats.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(stat, value);
        dirty.add(id);
    }

    private void max(UUID id, String stat, long value) {
        if (!tracking(stat)) return;
        stats.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).merge(stat, value, Math::max);
        dirty.add(id);
    }

    /** Adds a fractional amount (distance, damage): the whole part is counted, the rest carried over. Server thread. */
    private void addFraction(UUID id, String stat, double amount) {
        if (amount <= 0 || !tracking(stat)) return;
        Map<String, Double> f = fractions.computeIfAbsent(id, k -> new HashMap<>());
        double total = f.getOrDefault(stat, 0.0) + amount;
        long whole = (long) total;
        f.put(stat, total - whole);
        if (whole > 0) increment(id, stat, whole);
    }

    /** Server-wide sums of every counter. */
    Map<String, Long> totals() {
        Map<String, Long> out = new TreeMap<>();
        for (Map<String, Long> m : stats.values()) {
            for (Map.Entry<String, Long> e : m.entrySet()) {
                if (!NOT_SUMMED.contains(e.getKey())) out.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        return out;
    }

    private String today() {
        return LocalDate.now(zone).toString();
    }

    private Day day(String key) {
        return days.computeIfAbsent(key, k -> new Day());
    }

    // ------------------------------------------------------------------ sessions

    private void onJoin(ServerPlayer p) {
        UUID id = p.getUUID();
        long now = System.currentTimeMillis();
        names.put(id, Names.name(p));
        dirty.add(id);
        Day d = day(today());
        d.unique.add(id);
        d.joins++;
        totalJoins++;
        if (plugin.profiles().get(p).firstJoin) d.newPlayers++;
        int online = plugin.onlinePlayers().size();
        d.peak = Math.max(d.peak, online);
        peakEver = Math.max(peakEver, online);
        serverDirty = true;
        increment(id, "sessions", 1);
        if (get(id, "first-join") == 0) put(id, "first-join", now);
        put(id, "last-join", now);
        sessionStart.put(id, now);
        lastPos.put(id, Loc.of(p));
    }

    private void endSession(ServerPlayer p) {
        UUID id = p.getUUID();
        Long start = sessionStart.remove(id);
        lastPos.remove(id);
        fractions.remove(id);
        if (start == null) return;
        long secs = Math.max(0, (System.currentTimeMillis() - start) / 1000);
        Day d = day(today());
        d.sessions++;
        d.sessionSeconds += secs;
        sessionsTotal++;
        sessionSecondsTotal += secs;
        serverDirty = true;
        max(id, "longest-session", secs);
    }

    /** Current session length in seconds (0 when offline). */
    long currentSession(UUID id) {
        Long start = sessionStart.get(id);
        return start == null ? 0 : (System.currentTimeMillis() - start) / 1000;
    }

    // ------------------------------------------------------------------ sampling (playtime, distance)

    private void sample() {
        for (ServerPlayer p : plugin.onlinePlayers()) {
            UUID id = p.getUUID();
            increment(id, "playtime", sampleSeconds);
            boolean away = plugin.isAfk(p);
            increment(id, away ? "playtime-afk" : "playtime-active", sampleSeconds);

            Loc now = Loc.of(p);
            Loc before = lastPos.put(id, now);
            if (before == null || before.level() != now.level() || p.isSpectator()) continue;
            String kind;
            double d;
            if (p.isPassenger()) {
                kind = "distance-ridden";
                d = Math.sqrt(before.distanceSquared(now));
            } else if (p.isFallFlying() || p.getAbilities().flying) {
                kind = "distance-flown";
                d = Math.sqrt(before.distanceSquared(now));
            } else if (p.isSwimming() || p.isInWater()) {
                kind = "distance-swum";
                d = Math.sqrt(before.distanceSquared(now));
            } else {
                kind = "distance-walked";
                double dx = now.x() - before.x(), dz = now.z() - before.z();
                d = Math.sqrt(dx * dx + dz * dz);
            }
            // Teleports reset the sampler; anything faster than this is a move we didn't see, not travel.
            if (d < 0.05 || d > 60.0 * sampleSeconds) continue;
            addFraction(id, kind, d);
            addFraction(id, "distance", d);
        }
    }

    private void minute() {
        healthBefore.clear();
        int online = plugin.onlinePlayers().size();
        if (online == 0) return;
        hours[LocalDateTime.now(zone).getHour()] += online;
        Day d = day(today());
        d.peak = Math.max(d.peak, online);
        serverDirty = true;
    }

    private void onTeleport(ServerPlayer p, Loc from, Loc to, TeleportCause cause) {
        lastPos.put(p.getUUID(), to);
        boolean far = to.level() != from.level() || to.distanceSquared(from) > 32 * 32;
        if (cause == TeleportCause.COMMAND || (cause == TeleportCause.PLUGIN && far)) increment(p.getUUID(), "teleports", 1);
    }

    // ------------------------------------------------------------------ counters

    /** Damage dealt and taken: what actually came off (health + absorption before minus after). */
    private void onDamage(LivingEntity hurt, DamageSource source, float taken, boolean died) {
        Float before = healthBefore.remove(hurt);
        double dmg = before == null ? taken : died ? before : Math.max(0, before - (hurt.getHealth() + hurt.getAbsorptionAmount()));
        if (dmg <= 0) return;
        if (hurt instanceof ServerPlayer victim) addFraction(victim.getUUID(), "damage-taken", dmg);
        ServerPlayer attacker = FeatherEvents.attacker(source);
        if (attacker != null && attacker != hurt) addFraction(attacker.getUUID(), "damage-dealt", dmg);
    }

    private void onDeath(LivingEntity victim, DamageSource source) {
        Player last = victim.getLastHurtByPlayer();
        ServerPlayer killer = last instanceof ServerPlayer sp ? sp : FeatherEvents.attacker(source);
        if (victim instanceof ServerPlayer dead) {
            increment(dead.getUUID(), "deaths", 1);
            if (killer != null && killer != dead) increment(killer.getUUID(), "player-kills", 1);
            return;
        }
        if (isBoss(victim)) bossDeath(victim, killer);
        if (killer == null || victim instanceof ArmorStand) return;
        increment(killer.getUUID(), "mob-kills", 1);
        increment(killer.getUUID(), "mob." + EntityType.getKey(victim.getType()).getPath(), 1);
    }

    private void onStat(ServerPlayer p, Stat<?> stat, int n) {
        if (stat.getType() == Stats.ITEM_CRAFTED) {
            // The crafting grid only (not furnaces, smithing, stonecutters or trades), as Bukkit's CraftItemEvent.
            if (p.containerMenu instanceof CraftingMenu || p.containerMenu instanceof InventoryMenu) increment(p.getUUID(), "items-crafted", n);
        } else if (stat.getType() == Stats.CUSTOM && Stats.ENCHANT_ITEM.equals(stat.getValue())) {
            increment(p.getUUID(), "items-enchanted", 1);
        }
    }

    /**
     * Whether a dying mob counts as a boss: listed in {@code analytics.bosses.types} (native ids like
     * {@code aftrhrs:frost_wyrm} or the old MythicMobs names like {@code ArcanaFrostWyrm}), or, with an empty list, in
     * the {@code c:bosses} entity tag (MythicMobs' "has a boss bar" doesn't exist natively).
     */
    private boolean isBoss(LivingEntity mob) {
        Identifier id = EntityType.getKey(mob.getType());
        if (bossTypes.isEmpty()) return mob.getType().builtInRegistryHolder().is(ConventionalEntityTypeTags.BOSSES);
        for (String t : bossTypes) {
            if (t.equals(id.toString()) || t.equals(id.getPath()) || t.equals(bossKey(id))) return true;
            Optional<EntityType<?>> mapped = Legacy.mythicType(t);
            if (mapped.isPresent() && mapped.get() == mob.getType()) return true;
        }
        return false;
    }

    /**
     * The {@code boss.<type>} stat name: Feather counted MythicMobs internal names lower-cased ({@code ArcanaFrostWyrm}
     * → {@code arcanafrostwyrm}); a native Arcana mob ({@code aftrhrs:frost_wyrm}) keeps that name so old counts go on.
     */
    static String bossKey(Identifier id) {
        if (id.getNamespace().equals("aftrhrs")) return "arcana" + id.getPath().replace("_", "");
        return id.getPath();
    }

    /** A boss died: the killer and everyone near it get the kill. */
    private void bossDeath(LivingEntity mob, ServerPlayer killer) {
        String t = bossKey(EntityType.getKey(mob.getType())).replace('.', '_');
        Set<ServerPlayer> credit = new HashSet<>();
        if (killer != null) credit.add(killer);
        if (bossRadius > 0 && mob.level() instanceof ServerLevel level) {
            Vec3 at = mob.position();
            for (ServerPlayer p : level.players()) {
                if (!p.isSpectator() && p.position().distanceToSqr(at) <= bossRadius * bossRadius) credit.add(p);
            }
        }
        for (ServerPlayer p : credit) {
            increment(p.getUUID(), "bosses-defeated", 1);
            increment(p.getUUID(), "boss." + t, 1);
        }
    }

    // ------------------------------------------------------------------ disk

    /** A player file waiting to be read at load, with its listing attributes. */
    private record Listed(UUID id, Path file, long mtime, long size) {
    }

    /** One player of players.idx: the file it was made from ({mtime, size}) and that file's name and stats. */
    private record IndexEntry(long mtime, long size, String name, Map<String, Long> stats) {
    }

    private static final int INDEX_MAGIC = ('A' << 24) | ('H' << 16) | ('S' << 8) | 'I';
    private static final int INDEX_VERSION = 1;
    /** Up to this many changed player files are read on the server thread; more go to a small pool. */
    private static final int INLINE_READS = 32;

    /**
     * Reads every player file, or rather its copy in players.idx when the file still has exactly the size and modified
     * time the index saw: with thousands of players the YAML parse was most of the boot (the files stay the source of
     * truth, the index is only a cache and is rebuilt from them whenever it is missing or wrong).
     */
    private void load() {
        long t0 = System.currentTimeMillis();
        boolean hadIndex = Files.exists(indexFile);
        Map<UUID, IndexEntry> index = readIndex();
        boolean indexOk = hadIndex && index != null;
        if (index == null) index = Map.of();
        int fromIndex = 0, dropped = 0;
        List<Listed> queue = new ArrayList<>();
        if (Files.isDirectory(playersFolder)) {
            List<Listed> listed = new ArrayList<>();
            try {
                Files.walkFileTree(playersFolder, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String base = file.getFileName().toString();
                        if (!attrs.isRegularFile() || !base.endsWith(".yml")) return FileVisitResult.CONTINUE;
                        try {
                            listed.add(new Listed(UUID.fromString(base.substring(0, base.length() - 4)), file, attrs.lastModifiedTime().toMillis(), attrs.size()));
                        } catch (IllegalArgumentException ignored) {
                            // not a player file
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                plugin.getLogger().error("Could not list {}: {}", playersFolder, e.toString());
            }
            Set<UUID> seen = new HashSet<>();
            for (Listed l : listed) {
                seen.add(l.id());
                IndexEntry e = index.get(l.id());
                if (e != null && e.mtime() == l.mtime() && e.size() == l.size()) {
                    if (e.name() != null) names.put(l.id(), e.name());
                    stats.put(l.id(), new ConcurrentHashMap<>(e.stats()));
                    stamps.put(l.id(), new long[]{l.mtime(), l.size()});
                    fromIndex++;
                } else {
                    queue.add(l);
                }
            }
            for (UUID id : index.keySet()) if (!seen.contains(id)) dropped++;   // the file is gone: so is the player
        } else {
            dropped = index.size();
        }
        readPlayers(queue);
        Yaml srv = read(folder.resolve("server.yml"));
        if (srv != null) {
            totalJoins = srv.getLong("total-joins");
            peakEver = srv.getInt("peak-online");
            sessionsTotal = srv.getLong("sessions");
            sessionSecondsTotal = srv.getLong("session-seconds");
            List<Long> h = srv.getLongList("hours");
            for (int i = 0; i < 24 && i < h.size(); i++) hours[i] = h.get(i);
            Yaml ds = srv.getConfigurationSection("days");
            if (ds != null) {
                for (String key : ds.getKeys(false)) {
                    Yaml s = ds.getConfigurationSection(key);
                    if (s == null) continue;
                    Day d = new Day();
                    for (String u : s.getStringList("unique")) {
                        try {
                            d.unique.add(UUID.fromString(u));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    d.newPlayers = s.getInt("new");
                    d.peak = s.getInt("peak");
                    d.joins = s.getLong("joins");
                    d.sessions = s.getLong("sessions");
                    d.sessionSeconds = s.getLong("session-seconds");
                    days.put(key, d);
                }
            }
        }
        loadedFromIndex = fromIndex;
        loadedRead = queue.size();
        plugin.getLogger().info("Analytics: loaded stats of {} player(s) ({} from index, {} read) in {} ms.", stats.size(), fromIndex, queue.size(),
                System.currentTimeMillis() - t0);
        if (!queue.isEmpty() || !indexOk || dropped > 0) {
            try {
                io.execute(this::writeIndex);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // shutting down: the next start reads the files
            }
        }
    }

    /** Reads player files (the existing parse) into names, stats and stamps: inline when few, else on a small pool. */
    private void readPlayers(List<Listed> queue) {
        if (queue.isEmpty()) return;
        if (queue.size() <= INLINE_READS) {
            for (Listed l : queue) readPlayer(l);
            return;
        }
        int threads = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Feather-Stats-Load-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try {
            List<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>(queue.size());
            for (Listed l : queue) tasks.add(() -> {
                readPlayer(l);
                return null;
            });
            for (java.util.concurrent.Future<Void> f : pool.invokeAll(tasks)) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    plugin.getLogger().error("Could not read a stats file: {}", String.valueOf(e.getCause()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    private void readPlayer(Listed l) {
        Yaml yml = read(l.file());
        if (yml == null) return;
        String name = yml.getString("name");
        if (name != null) names.put(l.id(), name);
        Map<String, Long> m = new ConcurrentHashMap<>();
        Yaml s = yml.getConfigurationSection("stats");
        if (s != null) {
            for (String key : s.getKeys(true)) if (!s.isConfigurationSection(key)) m.put(key, s.getLong(key));
        }
        stats.put(l.id(), m);
        stamps.put(l.id(), new long[]{l.mtime(), l.size()});
    }

    /** players.idx as {player: entry}; null when there is none or it can't be read (then every file is read). */
    private Map<UUID, IndexEntry> readIndex() {
        if (!Files.exists(indexFile)) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexFile), 1 << 16))) {
            if (in.readInt() != INDEX_MAGIC) throw new IOException("not a stats index");
            int version = in.readInt();
            if (version != INDEX_VERSION) throw new IOException("index version " + version);
            int count = in.readInt();
            if (count < 0) throw new IOException("bad entry count " + count);
            Map<UUID, IndexEntry> out = new HashMap<>(Math.min(count, 1 << 16) * 2);
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                long mtime = in.readLong();
                long size = in.readLong();
                String name = in.readBoolean() ? in.readUTF() : null;
                int n = in.readInt();
                if (n < 0) throw new IOException("bad stat count " + n);
                Map<String, Long> m = new HashMap<>(Math.min(n, 1 << 12) * 2);
                for (int j = 0; j < n; j++) {
                    String key = in.readUTF();
                    m.put(key, in.readLong());
                }
                out.put(id, new IndexEntry(mtime, size, name, m));
            }
            if (in.read() != -1) throw new IOException("trailing bytes");
            return out;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warn("Analytics: {} can't be used ({}); reading every player file.", indexFile.getFileName(), e.toString());
            return null;
        }
    }

    /**
     * Writes players.idx from the players whose file is known ({@link #stamps}) and in memory exactly as on disk (IO
     * thread, after the flush's file writes). A player counted since their file was written (dirty) is left out, so
     * the next start reads their file; when another flush is still queued it writes the index instead.
     */
    private void writeIndex() {
        if (pendingFlushes.get() > 0) {
            indexStale = true;
            return;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(1 << 16);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            List<UUID> ids = new ArrayList<>();
            List<long[]> st = new ArrayList<>();
            List<Map<String, Long>> values = new ArrayList<>();
            for (Map.Entry<UUID, Map<String, Long>> e : stats.entrySet()) {
                UUID id = e.getKey();
                long[] s = stamps.get(id);
                if (s == null) continue;
                Map<String, Long> copy = new HashMap<>(e.getValue());
                if (dirty.contains(id)) continue;   // counted since its file was written: not what the file says
                ids.add(id);
                st.add(s);
                values.add(copy);
            }
            // a flush that began meanwhile may have taken players out of dirty before writing them: leave it to that one
            if (pendingFlushes.get() > 0) {
                indexStale = true;
                return;
            }
            out.writeInt(INDEX_MAGIC);
            out.writeInt(INDEX_VERSION);
            out.writeInt(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                UUID id = ids.get(i);
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
                out.writeLong(st.get(i)[0]);
                out.writeLong(st.get(i)[1]);
                String name = names.get(id);
                out.writeBoolean(name != null);
                if (name != null) out.writeUTF(name);
                Map<String, Long> m = values.get(i);
                out.writeInt(m.size());
                for (Map.Entry<String, Long> e : m.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeLong(e.getValue());
                }
            }
        } catch (IOException | RuntimeException e) {
            indexFailed(e);
            return;
        }
        if (!write(indexFile, bytes.toByteArray())) indexFailed(null);
        else indexStale = false;
    }

    /** A stale index must not outlive a failed rewrite: the next start reads the files. */
    private void indexFailed(Exception e) {
        if (e != null) plugin.getLogger().error("Could not save {}: {}", indexFile, e.toString());
        try {
            Files.deleteIfExists(indexFile);
        } catch (IOException ex) {
            plugin.getLogger().error("Could not delete the stale {}: {}", indexFile, ex.toString());
        }
    }

    private Yaml read(Path file) {
        if (!Files.exists(file)) return null;
        try {
            return Yaml.loadStrict(file);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().error("Could not read {}: {}", file, e.toString());
            return null;
        }
    }

    /** A player's stats handed to the writer: the live (concurrent) counter map, read there. */
    private record PlayerSnap(UUID id, String name, Map<String, Long> stats) {
    }

    /** One day of server numbers, copied on the server thread ({@link Day#unique} is a plain set changed by joins). */
    private record DaySnap(String key, UUID[] unique, int newPlayers, int peak, long joins, long sessions, long sessionSeconds) {
    }

    /** server.yml's numbers, copied on the server thread. */
    private record ServerSnap(long totalJoins, int peakEver, long sessions, long sessionSeconds, long[] hours, List<DaySnap> days) {
    }

    /** Copies what changed on the calling (server) thread; the files are built and written in the background. */
    void flush(boolean shuttingDown) {
        // Counted before anyone leaves dirty: an index written meanwhile on the IO thread sees it and leaves it to us.
        pendingFlushes.incrementAndGet();
        List<PlayerSnap> players = new ArrayList<>();
        for (UUID id : new ArrayList<>(dirty)) {
            // Removed first: a count after this line marks the player dirty again for the next flush.
            dirty.remove(id);
            players.add(new PlayerSnap(id, names.get(id), stats.get(id)));
        }
        ServerSnap srv = null;
        if (serverDirty || shuttingDown) {
            serverDirty = false;
            String cutoff = LocalDate.now(zone).minusDays(retentionDays).toString();
            days.headMap(cutoff, false).clear();
            List<DaySnap> list = new ArrayList<>(days.size());
            for (Map.Entry<String, Day> e : days.entrySet()) {
                Day d = e.getValue();
                list.add(new DaySnap(e.getKey(), d.unique.toArray(new UUID[0]), d.newPlayers, d.peak, d.joins, d.sessions, d.sessionSeconds));
            }
            srv = new ServerSnap(totalJoins, peakEver, sessionsTotal, sessionSecondsTotal, hours.clone(), list);
        }
        if (players.isEmpty() && srv == null) {
            pendingFlushes.decrementAndGet();
            return;
        }
        ServerSnap s = srv;
        try {
            io.execute(() -> persist(players, s));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            persist(players, s);
        }
    }

    /** Builds and writes the player files and server.yml of a {@link #flush} (the IO thread), then the index. */
    private void persist(List<PlayerSnap> players, ServerSnap srv) {
        try {
            persistFiles(players, srv);
        } finally {
            pendingFlushes.decrementAndGet();
        }
        // After the files it describes, on the same thread: the index never claims more than the files hold.
        if (!players.isEmpty() || indexStale || !Files.exists(indexFile)) writeIndex();
    }

    private void persistFiles(List<PlayerSnap> players, ServerSnap srv) {
        for (PlayerSnap p : players) {
            Path file = playersFolder.resolve(p.id() + ".yml");
            try {
                Yaml yml = new Yaml();
                yml.set("name", p.name());
                if (p.stats() != null) for (Map.Entry<String, Long> e : new TreeMap<>(p.stats()).entrySet()) yml.set("stats." + e.getKey(), e.getValue());
                if (write(file, yml.saveToString())) {
                    BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                    stamps.put(p.id(), new long[]{a.lastModifiedTime().toMillis(), a.size()});
                } else {
                    stamps.remove(p.id());   // what's on disk is unknown now: the next start reads it
                }
            } catch (IOException | RuntimeException e) {
                stamps.remove(p.id());
                plugin.getLogger().error("Could not save {}: {}", file, e.toString());
            }
        }
        if (srv == null) return;
        Path file = folder.resolve("server.yml");
        try {
            Yaml y = new Yaml();
            y.set("total-joins", srv.totalJoins());
            y.set("peak-online", srv.peakEver());
            y.set("sessions", srv.sessions());
            y.set("session-seconds", srv.sessionSeconds());
            List<Long> h = new ArrayList<>();
            for (long v : srv.hours()) h.add(v);
            y.set("hours", h);
            for (DaySnap d : srv.days()) {
                String base = "days." + d.key() + ".";
                y.set(base + "unique", Arrays.stream(d.unique()).map(UUID::toString).sorted().toList());
                y.set(base + "new", d.newPlayers());
                y.set(base + "peak", d.peak());
                y.set(base + "joins", d.joins());
                y.set(base + "sessions", d.sessions());
                y.set(base + "session-seconds", d.sessionSeconds());
            }
            write(file, y.saveToString());
        } catch (RuntimeException e) {
            plugin.getLogger().error("Could not save {}: {}", file, e.toString());
        }
    }

    private boolean write(Path file, String data) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, data, StandardCharsets.UTF_8);
            moveOver(tmp, file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().error("Could not save {}: {}", file, e.toString());
            return false;
        }
    }

    /** {@link #write(Path, String)} for bytes (the index). */
    private boolean write(Path file, byte[] data) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            moveOver(tmp, file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().error("Could not save {}: {}", file, e.toString());
            return false;
        }
    }

    private static void moveOver(Path tmp, Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ CSV export

    private void export(Sender sender) {
        Text text = plugin.text();
        text.send(sender, "analytics.exporting");
        // Snapshot on the server thread, write off it.
        Map<UUID, Map<String, Long>> snap = new TreeMap<>();
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<UUID, Map<String, Long>> e : stats.entrySet()) {
            Map<String, Long> m = new TreeMap<>(e.getValue());
            snap.put(e.getKey(), m);
            keys.addAll(m.keySet());
        }
        Map<UUID, String> nameSnap = new HashMap<>(names);
        Map<String, long[]> daySnap = new TreeMap<>();
        for (Map.Entry<String, Day> e : days.entrySet()) {
            Day d = e.getValue();
            daySnap.put(e.getKey(), new long[]{d.unique.size(), d.newPlayers, d.peak, d.joins, d.sessions, d.sessions == 0 ? 0 : d.sessionSeconds / d.sessions});
        }
        long[] hourSnap = hours.clone();
        String stamp = STAMP.format(LocalDateTime.now(zone));
        Path out = plugin.getDataFolder().resolve("exports");
        io.execute(() -> {
            List<String> files = new ArrayList<>();
            try {
                Files.createDirectories(out);
                StringBuilder sb = new StringBuilder("uuid,name");
                for (String k : keys) sb.append(',').append(k);
                sb.append('\n');
                for (Map.Entry<UUID, Map<String, Long>> e : snap.entrySet()) {
                    sb.append(e.getKey()).append(',').append(csv(nameSnap.getOrDefault(e.getKey(), "")));
                    for (String k : keys) sb.append(',').append(e.getValue().getOrDefault(k, 0L));
                    sb.append('\n');
                }
                files.add(save(out, "players-" + stamp + ".csv", sb));
                sb = new StringBuilder("date,unique_players,new_players,peak_online,joins,sessions,avg_session_seconds\n");
                for (Map.Entry<String, long[]> e : daySnap.entrySet()) {
                    sb.append(e.getKey());
                    for (long v : e.getValue()) sb.append(',').append(v);
                    sb.append('\n');
                }
                files.add(save(out, "daily-" + stamp + ".csv", sb));
                sb = new StringBuilder("hour,player_minutes\n");
                for (int i = 0; i < 24; i++) sb.append(i).append(',').append(hourSnap[i]).append('\n');
                files.add(save(out, "hours-" + stamp + ".csv", sb));
                Tasks.sync(() -> text.send(sender, "analytics.exported", Text.unparsed("files", String.join(", ", files))));
            } catch (IOException e) {
                Tasks.sync(() -> text.send(sender, "analytics.export-failed", Text.unparsed("error", String.valueOf(e.getMessage()))));
            }
        });
    }

    private static String save(Path dir, String name, StringBuilder sb) throws IOException {
        Files.writeString(dir.resolve(name), sb.toString(), StandardCharsets.UTF_8);
        return name;
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ------------------------------------------------------------------ formatting

    static String num(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** Blocks as "345 m" or "12.3 km". */
    static String dist(long blocks) {
        return blocks < 1000 ? blocks + " m" : String.format(Locale.US, "%.1f km", blocks / 1000.0);
    }

    static String time(long seconds) {
        return Placeholders.duration(seconds * 1000L);
    }

    /** A stat value in its own unit (time, distance, hearts, or a count). */
    static String format(String stat, long v) {
        if (stat.startsWith("playtime") || stat.equals("longest-session")) return time(v);
        if (stat.startsWith("distance")) return dist(v);
        if (stat.startsWith("damage-")) return String.format(Locale.US, "%,.1f ❤", v / 2.0);
        return num(v);
    }

    static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("[_\\-]")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private String date(long epochMs) {
        if (epochMs <= 0) return "-";
        return java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString();
    }

    private String label(String stat) {
        String key = "analytics.labels." + stat;
        List<String> v = plugin.text().variants(key);
        return v.isEmpty() ? pretty(stat) : v.get(0);
    }

    // ------------------------------------------------------------------ /stats

    /** Opens the viewer's own stats. */
    public void openStats(ServerPlayer viewer) {
        openStats(viewer, viewer.getUUID());
    }

    /** Opens a player's stats dialog for the viewer. */
    public void openStats(ServerPlayer viewer, UUID target) {
        Text text = plugin.text();
        String name = nameOf(target);
        ServerPlayer online = plugin.player(target);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("analytics.stats.title", Text.unparsed("player", name)));

        ItemStack head = Icons.head(target, online);
        long kills = get(target, "player-kills"), deaths = get(target, "deaths");
        String kd = deaths == 0 ? num(kills) : String.format(Locale.US, "%.2f", kills / (double) deaths);
        List<TagResolver> r = new ArrayList<>();
        r.add(Text.component("player", online != null ? Names.display(online) : Component.text(name)));
        r.add(Text.unparsed("first-join", date(get(target, "first-join"))));
        r.add(Text.component("last-join", online != null ? text.msg("analytics.stats.online-now") : Component.text(date(get(target, "last-join")))));
        r.add(Text.unparsed("session", time(currentSession(target))));
        r.add(Text.unparsed("kd", kd));
        for (String stat : COUNTERS) r.add(Text.unparsed(stat, format(stat, get(target, stat))));
        r.add(Text.unparsed("longest-session", time(Math.max(get(target, "longest-session"), currentSession(target)))));
        // Top mobs
        Map<String, Long> m = stats.getOrDefault(target, Map.of());
        List<Map.Entry<String, Long>> mobs = new ArrayList<>();
        for (Map.Entry<String, Long> e : m.entrySet()) if (e.getKey().startsWith("mob.")) mobs.add(e);
        mobs.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(topMobs, mobs.size()); i++) {
            parts.add(text.raw("analytics.stats.mob-entry").replace("<mob>", pretty(mobs.get(i).getKey().substring(4)))
                    .replace("<count>", num(mobs.get(i).getValue())));
        }
        r.add(Text.component("top-mobs", parts.isEmpty() ? text.msg("analytics.stats.no-mobs") : text.parse(String.join(text.raw("analytics.stats.mob-separator"), parts))));
        TagResolver[] all = r.toArray(TagResolver[]::new);

        menu.item(head, text.msg("analytics.stats.header", all), 260);
        menu.text(text.msg("analytics.stats.body", all), 320);
        if (Perms.has(viewer, "feather.leaderboard") && leaderboards != null) {
            menu.button(text.msg("analytics.stats.leaderboards"), text.msg("analytics.stats.leaderboards-tooltip"), 150, leaderboards::openCategories);
        }
        if (Perms.has(viewer, "feather.analytics")) {
            menu.button(text.msg("analytics.stats.server"), null, 150, this::openAdmin);
        }
        menu.columns(2);
        plugin.exitButton(menu, viewer);
        menu.open(viewer);
        text.sound(viewer, "menu-open");
    }

    /** The viewer's own leaderboards (see {@link LeaderboardMenu}). */
    public void openLeaderboards(ServerPlayer viewer) {
        if (leaderboards != null) leaderboards.openCategories(viewer);
    }

    // ------------------------------------------------------------------ /analytics

    private String avg(long sessions, long seconds) {
        return sessions == 0 ? "-" : time(seconds / sessions);
    }

    private TagResolver[] dayTags(String key, Day d) {
        LocalDate date = LocalDate.parse(key);
        return new TagResolver[]{
                Text.unparsed("date", key),
                Text.unparsed("day", date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) + " " + date.getDayOfMonth()),
                Text.unparsed("unique", num(d.unique.size())),
                Text.unparsed("new", num(d.newPlayers)),
                Text.unparsed("peak", num(d.peak)),
                Text.unparsed("joins", num(d.joins)),
                Text.unparsed("avg", avg(d.sessions, d.sessionSeconds))};
    }

    private Component week(int count) {
        Text text = plugin.text();
        List<Component> rows = new ArrayList<>();
        LocalDate today = LocalDate.now(zone);
        for (int i = 0; i < count; i++) {
            String key = today.minusDays(i).toString();
            Day d = days.getOrDefault(key, new Day());
            rows.add(text.msg("analytics.menu.day-row", dayTags(key, d)));
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), rows);
    }

    private List<Integer> busiest(int n) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 24; i++) if (hours[i] > 0) order.add(i);
        order.sort((a, b) -> Long.compare(hours[b], hours[a]));
        return order.size() > n ? order.subList(0, n) : order;
    }

    private long hoursTotal() {
        long sum = 0;
        for (long v : hours) sum += v;
        return sum;
    }

    void openAdmin(ServerPlayer viewer) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("analytics.menu.title"));
        String t = today();
        Day d = days.getOrDefault(t, new Day());
        Map<String, Long> totals = totals();
        long sum = hoursTotal();
        List<String> top = new ArrayList<>();
        for (int h : busiest(3)) {
            top.add(text.raw("analytics.menu.top-hour").replace("<hour>", String.format(Locale.ROOT, "%02d:00", h))
                    .replace("<share>", String.valueOf(Math.round(hours[h] * 100.0 / Math.max(1, sum)))));
        }
        TagResolver[] today = dayTags(t, d);
        List<TagResolver> r = new ArrayList<>(List.of(today));
        r.add(Text.component("week", week(7)));
        r.add(Text.unparsed("players", num(stats.size())));
        r.add(Text.unparsed("total-joins", num(totalJoins)));
        r.add(Text.unparsed("peak-ever", num(peakEver)));
        r.add(Text.unparsed("avg-ever", avg(sessionsTotal, sessionSecondsTotal)));
        r.add(Text.unparsed("online", num(plugin.onlinePlayers().size())));
        for (String stat : COUNTERS) r.add(Text.unparsed("total-" + stat, format(stat, totals.getOrDefault(stat, 0L))));
        r.add(Text.component("hours", top.isEmpty() ? text.msg("analytics.menu.no-hours") : text.parse(String.join(text.raw("analytics.menu.hour-separator"), top))));
        menu.text(text.msg("analytics.menu.overview", r.toArray(TagResolver[]::new)), 360);

        int w = 150;
        menu.button(text.msg("analytics.menu.days"), null, w, this::openDays);
        menu.button(text.msg("analytics.menu.totals"), null, w, this::openTotals);
        menu.button(text.msg("analytics.menu.hours-button"), null, w, this::openHours);
        menu.button(text.msg("analytics.menu.mobs"), null, w, this::openMobs);
        if (leaderboards != null) menu.button(text.msg("analytics.menu.leaderboards"), null, w, leaderboards::openCategories);
        menu.button(text.msg("analytics.menu.export"), text.msg("analytics.menu.export-tooltip"), w, p -> export(Sender.of(p)));
        menu.columns(2);
        menu.exit(text.msg("menu.close"), null);
        menu.open(viewer);
        text.sound(viewer, "menu-open");
    }

    private void subPage(ServerPlayer viewer, String titleKey, Component body) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg(titleKey));
        menu.text(body, 360);
        menu.exit(text.msg("menu.back"), this::openAdmin);
        menu.open(viewer);
        text.sound(viewer, "page");
    }

    private void openDays(ServerPlayer viewer) {
        Text text = plugin.text();
        subPage(viewer, "analytics.menu.days-title", text.msg("analytics.menu.days-body", Text.component("rows", week(14))));
    }

    private void openTotals(ServerPlayer viewer) {
        Text text = plugin.text();
        Map<String, Long> totals = totals();
        List<String> rows = new ArrayList<>();
        String row = text.raw("analytics.menu.total-row");
        for (String stat : COUNTERS) {
            if (!tracking(stat)) continue;
            rows.add(row.replace("<label>", label(stat)).replace("<value>", format(stat, totals.getOrDefault(stat, 0L))));
        }
        subPage(viewer, "analytics.menu.totals-title", text.msg("analytics.menu.totals-body",
                Text.unparsed("players", num(stats.size())), Text.unparsed("total-joins", num(totalJoins)),
                Text.unparsed("peak-ever", num(peakEver)), Text.unparsed("avg-ever", avg(sessionsTotal, sessionSecondsTotal)),
                Text.component("rows", text.parse(String.join("\n", rows)))));
    }

    private void openHours(ServerPlayer viewer) {
        Text text = plugin.text();
        long max = 1;
        for (long v : hours) max = Math.max(max, v);
        long sum = Math.max(1, hoursTotal());
        List<String> rows = new ArrayList<>();
        String row = text.raw("analytics.menu.hour-row");
        String barChar = text.raw("analytics.menu.bar");
        for (int h = 0; h < 24; h++) {
            int len = (int) Math.round(hours[h] * 20.0 / max);
            rows.add(row.replace("<hour>", String.format(Locale.ROOT, "%02d:00", h)).replace("<bar>", barChar.repeat(len))
                    .replace("<share>", String.valueOf(Math.round(hours[h] * 100.0 / sum))));
        }
        subPage(viewer, "analytics.menu.hours-title", text.msg("analytics.menu.hours-body", Text.component("rows", text.parse(String.join("\n", rows)))));
    }

    private void openMobs(ServerPlayer viewer) {
        Text text = plugin.text();
        Map<String, Long> totals = totals();
        List<Map.Entry<String, Long>> mobs = new ArrayList<>();
        for (Map.Entry<String, Long> e : totals.entrySet()) if (e.getKey().startsWith("mob.") || e.getKey().startsWith("boss.")) mobs.add(e);
        mobs.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        List<String> rows = new ArrayList<>();
        String row = text.raw("analytics.menu.total-row");
        for (int i = 0; i < Math.min(20, mobs.size()); i++) {
            String key = mobs.get(i).getKey();
            String label = key.startsWith("boss.") ? text.raw("analytics.menu.boss-label").replace("<boss>", pretty(key.substring(5))) : pretty(key.substring(4));
            rows.add(row.replace("<label>", label).replace("<value>", num(mobs.get(i).getValue())));
        }
        subPage(viewer, "analytics.menu.mobs-title", rows.isEmpty() ? text.msg("analytics.menu.no-mobs")
                : text.msg("analytics.menu.mobs-body", Text.component("rows", text.parse(String.join("\n", rows)))));
    }

    // ------------------------------------------------------------------ commands

    /** {@code /stats [player]}. */
    private final class StatsCommand implements FeatherCommand {
        @Override
        public boolean onCommand(Sender sender, String label, String[] args) {
            Text text = plugin.text();
            ServerPlayer p = sender.player();
            if (p == null) {
                text.send(sender, "general.players-only");
                return true;
            }
            if (args.length == 0) {
                openStats(p);
                return true;
            }
            UUID target = findKnown(sender, args[0]);
            if (target == null) {
                text.send(p, "analytics.unknown-player", Text.unparsed("input", args[0]));
                return true;
            }
            if (!target.equals(p.getUUID()) && !sender.hasPermission("feather.stats.others")) {
                text.send(p, "general.no-permission");
                return true;
            }
            openStats(p, target);
            return true;
        }

        @Override
        public List<String> onTabComplete(Sender sender, String label, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length == 1 && sender.hasPermission("feather.stats.others")) out.addAll(plugin.completePlayers(sender, args[0]));
            return out;
        }
    }

    /** {@code /analytics [export|get <player> <stat>|flush]}. */
    private final class AdminCommand implements FeatherCommand {
        @Override
        public boolean onCommand(Sender sender, String label, String[] args) {
            Text text = plugin.text();
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "" -> {
                    if (sender.player() != null) openAdmin(sender.player());
                    else text.send(sender, "analytics.usage");
                }
                case "export" -> export(sender);
                case "flush", "save" -> {
                    flush(false);
                    text.send(sender, "analytics.flushed");
                }
                case "get" -> {
                    if (args.length < 3) {
                        text.send(sender, "analytics.usage");
                        return true;
                    }
                    UUID target = findKnown(sender, args[1]);
                    if (target == null) {
                        text.send(sender, "analytics.unknown-player", Text.unparsed("input", args[1]));
                        return true;
                    }
                    String stat = args[2].toLowerCase(Locale.ROOT);
                    long v = get(target, stat);
                    text.send(sender, "analytics.get", Text.unparsed("player", nameOf(target)), Text.unparsed("stat", stat),
                            Text.unparsed("value", format(stat, v)), Text.unparsed("raw", v));
                }
                default -> text.send(sender, "analytics.usage");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(Sender sender, String label, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length == 1) {
                out.addAll(List.of("export", "get", "flush"));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("get")) {
                out.addAll(plugin.completePlayers(sender, args[1]));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("get")) {
                out.addAll(COUNTERS);
                out.addAll(NOT_SUMMED);
            }
            String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
            return out;
        }
    }
}
