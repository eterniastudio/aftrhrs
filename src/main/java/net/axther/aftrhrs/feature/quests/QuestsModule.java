package net.axther.aftrhrs.feature.quests;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.JoinDigest;
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
import net.axther.aftrhrs.net.Companion;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Daily and weekly quests and the weekly server goal ({@code modules.quests}). Every player gets a set of daily quests
 * (new each day at 00:00 in {@code daily.reset-zone}) and weekly quests (new each Monday), picked from {@code quests.yml}.
 * Progress comes from other modules through {@link Hooks.Quests} (taming, the codex, bonds, altar bosses, the bank,
 * teleport requests, farming, fishing, wildlife) and from game events ({@link QuestEvents}: mining, Arcana tools, crops,
 * fishing, mobs, walking and riding).
 *
 * <p>Nothing counts for players who haven't passed the auth gate, are AFK or in creative/spectator. Rewards (Goob
 * Coins into the bank balance, bond XP for the pets following the player) are claimed in the menu ({@link QuestMenu});
 * completed quests left unclaimed at the reset are paid automatically. Data: {@code data/quests.yml} and
 * {@code data/server-goal.yml}.
 */
public final class QuestsModule implements FeatherModule, Hooks.Quests {
    static final String PERM = "feather.quests", GOAL_PERM = "feather.goal";
    static final String ADMIN = "feather.quests.admin", GOAL_ADMIN = "feather.goal.admin";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static volatile QuestsModule active;

    private final Feather plugin;
    final QuestPool pool = new QuestPool();
    final QuestStore store;
    final ServerGoal goal;
    final QuestMenu menu;
    private final QuestEvents events;
    /** The Companion's quest tracker. */
    private final QuestsCompanion bridge;
    private Tasks.Task ticker;
    /** True while {@link #tick} turns players over: their saves wait for the one after the loop. */
    private boolean rolloverBatch;
    /** A rollover in this batch paid unclaimed quests. */
    private boolean batchPaid;
    /** The day old players were last pruned. */
    private long prunedDay = Long.MIN_VALUE;

    // settings (quests.yml settings + config.yml daily.reset-zone)
    ZoneId zone = ZoneId.of("America/Toronto");
    List<String> dailySlots = List.of("easy", "medium", "hard"), weeklySlots = List.of("easy", "medium", "hard");
    int rerollsPerDay = 1;
    boolean goalEnabled = true;
    double goalMultiplier = 1.0;
    double goalMinPercent = 1.0;
    int goalTopShown = 5;
    boolean goalScaling = true;
    double goalScaleReference = 10;
    double goalScaleMin = 0.15;
    double goalScaleMax = 3;
    int goalScaleDays = 7;
    private final Map<String, Sound> sounds = new HashMap<>();
    /** Players whose day or week couldn't turn over (the bank couldn't pay their finished quests): when to try again. */
    private final Map<UUID, Long> rollRetry = new HashMap<>();
    /**
     * Per player: {day, coins, week, coins}. Coins taken out of the bank after a claimed deposit quest counted them
     * (there's no taking a reward back): deposits that day (week) pay these back before they count again.
     */
    private final Map<UUID, long[]> depositDebt = new HashMap<>();

    public QuestsModule(Feather plugin) {
        this.plugin = plugin;
        Path data = plugin.getDataFolder().resolve("data");
        this.store = new QuestStore(data.resolve("quests.yml"), plugin.getLogger());
        this.goal = new ServerGoal(this, data.resolve("server-goal.yml"));
        this.menu = new QuestMenu(this);
        this.events = new QuestEvents(this);
        this.bridge = new QuestsCompanion(this);
    }

    /** The running quests module, or null. */
    static QuestsModule active() {
        return active;
    }

    @Override
    public String name() {
        return "Quests";
    }

    @Override
    public void enable() {
        reload();
        store.load();
        goal.load();
        if (goalEnabled) goal.check();
        prunedDay = today();
        store.prune(prunedDay - 60);
        active = this;
        listen(plugin);
        events.start();
        plugin.bind("quests", new Cmd(false));
        plugin.bind("goal", new Cmd(true));
        ticker = Tasks.timer(20L * 30, 20L * 30, this::tick);
        // Goal-progress deltas for the HUD, switched with the quests feature (companion.features.quests). addFeature
        // re-reads the companion config when that module is already running, so the feature is offered at once.
        Companion.addFeature("quest_goal", "quests");
        bridge.start();
        plugin.provide(Hooks.Quests.class, this);
        Hub hub = plugin.hub();
        hub.add("quests", Hub.Group.DAILY, Hub.ORDER_QUESTS, "hub-social", PERM, p -> menu.open(p));
        hub.add("goal", Hub.Group.DAILY, Hub.ORDER_GOAL, "hub-social", p -> goalEnabled && Perms.has(p, GOAL_PERM), p -> menu.openGoal(p));
        JoinDigest.register("quests", 20, this::collectJoin);
    }

    /** The Fabric listeners (once per JVM; they do nothing while quests are off). */
    private static void listen(Feather plugin) {
        plugin.once("quests.events", () -> {
            FeatherEvents.BLOCK_PLACED.register((p, level, pos, state, stack) -> {
                QuestsModule m = active;
                if (m != null) m.events.mark(level, pos);
            });
            FeatherEvents.BLOCK_FORMED.register((level, pos) -> {
                QuestsModule m = active;
                if (m != null) m.events.mark(level, pos);
            });
            // Blocks dispensers place (shulker boxes, pumpkins, ...): the spot in front of the dispenser (Paper's onDispense).
            FeatherEvents.DISPENSE.register((level, pos, state) -> {
                QuestsModule m = active;
                if (m != null && state.hasProperty(net.minecraft.world.level.block.DispenserBlock.FACING)) {
                    m.events.mark(level, pos.relative(state.getValue(net.minecraft.world.level.block.DispenserBlock.FACING)));
                }
                return true;
            });
            FeatherEvents.SPAWNER_SPAWN.register((level, e, trial) -> {
                QuestsModule m = active;
                if (m != null) m.events.spawnerSpawned(e, trial);
            });
            PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
                QuestsModule m = active;
                if (m != null && player instanceof ServerPlayer sp) m.events.broke(sp, level, pos, state);
            });
            ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
                QuestsModule m = active;
                if (m != null) m.events.died(entity);
            });
            FeatherEvents.FISH_CAUGHT.register((p, rod, hook, loot) -> {
                // While the fishing module runs (it provides FishRarity) it reports rod catches itself, after its own
                // fish replaced the loot; counting here too would count them twice.
                QuestsModule m = active;
                if (m != null && m.plugin.service(Hooks.FishRarity.class) == null) m.events.fished(p, loot);
            });
            FeatherEvents.PLAYER_TELEPORTED.register((p, from, to, cause) -> {
                QuestsModule m = active;
                if (m != null) m.events.teleported(p, to);
            });
            ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
                QuestsModule m = active;
                if (m != null) m.events.joined(h.player);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                QuestsModule m = active;
                if (m != null) m.events.quit(h.player);
            });
            Companion.onQuit(p -> {
                QuestsModule m = active;
                if (m != null) m.bridge.quit(p);
            });
        });
    }

    @Override
    public void reload() {
        // Data stays in memory across a reload; write it first so a reload never loses progress.
        store.saveIfDirtyAsync();
        goal.saveIfDirty();
        String z = plugin.getConfig().getString("daily.reset-zone", "America/Toronto");
        try {
            zone = z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z.trim());
        } catch (RuntimeException e) {
            plugin.getLogger().warn("daily.reset-zone: unknown time zone '{}'; using America/Toronto.", z);
            zone = ZoneId.of("America/Toronto");
        }
        Yaml cfg = plugin.loadResourceConfig("quests.yml");
        dailySlots = slots(cfg.getStringList("settings.daily.slots"));
        weeklySlots = slots(cfg.getStringList("settings.weekly.slots"));
        rerollsPerDay = Math.max(0, Math.min(10, cfg.getInt("settings.daily.rerolls", 1)));
        goalEnabled = cfg.getBoolean("server-goal.enabled", true);
        goalMultiplier = Math.max(0.01, Math.min(100, cfg.getDouble("server-goal.multiplier", 1.0)));
        goalMinPercent = Math.max(0, Math.min(100, cfg.getDouble("server-goal.min-contribution-percent", 1.0)));
        goalTopShown = Math.max(1, Math.min(10, cfg.getInt("server-goal.top-shown", 5)));
        goalScaling = cfg.getBoolean("server-goal.scaling.enabled", true);
        goalScaleReference = Math.max(1, cfg.getDouble("server-goal.scaling.reference", 10));
        goalScaleMin = Math.max(0.01, cfg.getDouble("server-goal.scaling.min", 0.15));
        goalScaleMax = Math.max(goalScaleMin, cfg.getDouble("server-goal.scaling.max", 3));
        goalScaleDays = Math.max(1, Math.min(365, cfg.getInt("server-goal.scaling.active-days", 7)));
        sounds.clear();
        for (String key : List.of("complete", "claim", "reroll")) {
            String spec = cfg.getString("settings.sounds." + key, "");
            Sound s = Text.parseSound(spec);
            if (s == null && spec != null && !spec.isBlank() && !spec.equalsIgnoreCase("none")) {
                plugin.getLogger().warn("quests.yml: bad sound '{}' (use: <sound key> [volume] [pitch]).", spec);
            }
            if (s != null) sounds.put(key, s);
        }
        events.reload(cfg);
        pool.load(cfg, plugin.getLogger());
        // Data files that couldn't be read are tried again (after an admin fixed them).
        if (store.unreadable) store.load();
        if (goal.unreadable) goal.load();
        bridge.reload();
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (ticker != null) ticker.cancel();
        ticker = null;
        bridge.stop();
        events.stop();
        plugin.withdraw(Hooks.Quests.class);
        plugin.hub().remove("quests");
        plugin.hub().remove("goal");
        JoinDigest.unregister("quests");
        store.saveNow();
        goal.save();
    }

    private static List<String> slots(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String d = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            if (QuestPool.DIFFICULTIES.contains(d)) out.add(d);
        }
        if (out.isEmpty()) out.addAll(List.of("easy", "medium", "hard"));
        return out.size() > 6 ? out.subList(0, 6) : out;
    }

    void sound(ServerPlayer p, String key) {
        Sound s = sounds.get(key);
        if (s != null && p != null) Adv.sound(p, s);
    }

    Feather plugin() {
        return plugin;
    }

    Text text() {
        return plugin.text();
    }

    /** The pets' quest hooks, or null while pets are off. */
    Hooks.PetProgress pets() {
        return plugin.service(Hooks.PetProgress.class);
    }

    /** Bukkit.broadcast: every player and the console. */
    void broadcast(Component c) {
        for (ServerPlayer p : plugin.onlinePlayers()) Adv.send(p, c);
        MinecraftServer s = Feather.serverOrNull();
        if (s != null) s.sendSystemMessage(Adv.vanilla(c));
    }

    void tick() {
        long today = today(), week = thisWeek();
        if (today != prunedDay) {
            prunedDay = today;
            store.prune(today - 60);
        }
        // Every player turned over in this tick shares one save after the loop (state() would save each one).
        batchRollovers(() -> {
            for (ServerPlayer p : plugin.onlinePlayers()) {
                if (plugin.authPending(p)) continue;
                QuestStore.Quests q = store.players.get(p.getUUID());
                if (q == null) continue;
                boolean newDay = q.day != today, newWeek = q.week != week;
                if (!newDay && !newWeek) continue;
                state(p);
                boolean dayTurned = newDay && q.day == today, weekTurned = newWeek && q.week == week;
                if (!dayTurned && !weekTurned) continue;   // still waiting for the bank (see roll)
                text().send(p, weekTurned ? "quests.new-week" : "quests.new-day");
                sound(p, "reroll");
            }
        });
        depositDebt.values().removeIf(d -> d[0] != today && d[2] != week);
        if (goalEnabled) goal.check();
        saveAfterRollover(false);
        goal.saveIfDirtyAsync();
    }

    /**
     * Runs {@code r} (which may turn several players over through {@link #state}) with their saves batched: every
     * rollover inside shares one bank snapshot and one quests.yml snapshot, taken after {@code r} (in a finally, so coins
     * already paid to an earlier player are saved with their claimed flags even when a later player's rollover throws).
     * Nested calls join the outer batch. Server thread.
     */
    void batchRollovers(Runnable r) {
        if (rolloverBatch) {
            r.run();
            return;
        }
        rolloverBatch = true;
        batchPaid = false;
        try {
            r.run();
        } finally {
            rolloverBatch = false;
            if (batchPaid) {
                batchPaid = false;
                saveAfterRollover(true);
            }
        }
    }

    /**
     * After rollovers. When unclaimed quests were paid, the bank snapshot is taken first and then the quests.yml one
     * (claimed flags): both snapshots are taken in the same tick and written in the background. The bank uses its own
     * writer thread, so even a synchronous quests write never ordered the two files; what matters is that both hold the
     * same step. Otherwise only quests.yml, in the background, when something changed.
     */
    private void saveAfterRollover(boolean paid) {
        if (paid) {
            if (plugin.service(Hooks.Bank.class) instanceof net.axther.aftrhrs.feature.bank.BankModule bank) bank.saveNow();
            store.dirty = true;
        }
        store.saveIfDirtyAsync();
    }

    // ------------------------------------------------------------------ days and weeks

    long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    long thisWeek() {
        return LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    /** Epoch ms of the next daily reset. */
    long nextDay() {
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** Epoch ms of the next weekly reset (Monday 00:00). */
    long nextWeek() {
        return LocalDate.ofEpochDay(thisWeek()).plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------ players' quests

    /**
     * The player's quests, rolled over to today's and this week's set first (completed quests left unclaimed are paid
     * then). Removed quests (no longer in quests.yml) are swapped for new ones.
     */
    QuestStore.Quests state(ServerPlayer p) {
        QuestStore.Quests q = store.get(p.getUUID());
        String name = Names.name(p);
        if (!name.equals(q.name)) {
            q.name = name;
            store.dirty = true;
        }
        if (store.unreadable) return q;
        long today = today(), week = thisWeek();
        Long retry = rollRetry.get(p.getUUID());
        if (retry == null || System.currentTimeMillis() >= retry) {
            boolean paid = false;
            if (q.day != today) paid |= roll(p, q, false, today);
            if (q.week != week) paid |= roll(p, q, true, week);
            // Paid quests: the claimed flags are snapshotted in this tick with the bank (saveAfterRollover). A turnover
            // that paid nothing is saved with the next save.
            if (paid) {
                if (rolloverBatch) batchPaid = true;
                else saveAfterRollover(true);
            }
        }
        fixMissing(p, q, false);
        fixMissing(p, q, true);
        return q;
    }

    /**
     * A new set: pays completed, unclaimed quests of the old one, then picks fresh quests. When one of them can't be
     * paid (the bank is closed) the old set stays, to be paid and turned over on a later try (a minute on).
     * Only marks the store dirty; true when it paid something (the caller snapshots the bank and quests at once then).
     */
    boolean roll(ServerPlayer p, QuestStore.Quests q, boolean weekly, long stamp) {
        List<QuestStore.Slot> old = weekly ? q.weekly : q.daily;
        long coins = 0;
        int bond = 0, paid = 0;
        boolean unpaid = false;
        boolean firstTry = !rollRetry.containsKey(p.getUUID());
        for (QuestStore.Slot s : old) {
            QuestPool.Def d = pool.def(s.id);
            if (d == null || s.claimed || s.progress < d.amount()) continue;
            long c = boosted(d.reward().coins());
            if (!credit(p, c, "unclaimed quest " + d.id())) {
                if (firstTry) {
                    plugin.getLogger().warn("Quests: could not pay {}'s unclaimed quest {} ({} coins): the bank is closed. Their {} quests turn over once it opens.",
                            Names.name(p), d.id(), c, weekly ? "weekly" : "daily");
                }
                unpaid = true;
                continue;
            }
            bondXp(p, d.reward().bond());
            s.claimed = true;
            q.completed++;
            coins += c;
            bond += d.reward().bond();
            paid++;
        }
        if (paid > 0) {
            text().send(p, "quests.auto-claimed", Text.unparsed("count", paid), Text.component("reward", rewardText(coins, bond, -1)));
        }
        if (unpaid) {
            rollRetry.put(p.getUUID(), System.currentTimeMillis() + 60_000L);
            if (firstTry) text().send(p, "quests.rollover-waiting");
            store.dirty = true;
            changed(p);
            return paid > 0;
        }
        rollRetry.remove(p.getUUID());
        Set<String> avoid = new HashSet<>();
        for (QuestStore.Slot s : old) avoid.add(s.id);
        List<QuestStore.Slot> fresh = assign(p, weekly, avoid);
        old.clear();
        old.addAll(fresh);
        if (weekly) q.week = stamp;
        else {
            q.day = stamp;
            q.rerolls = 0;
            events.forgetDay(p.getUUID());
        }
        store.dirty = true;
        changed(p);
        return paid > 0;
    }

    private void fixMissing(ServerPlayer p, QuestStore.Quests q, boolean weekly) {
        List<QuestStore.Slot> slots = weekly ? q.weekly : q.daily;
        List<String> diffs = weekly ? weeklySlots : dailySlots;
        boolean changed = false;
        for (int i = 0; i < slots.size(); i++) {
            if (pool.def(slots.get(i).id) != null) continue;
            Set<String> taken = new HashSet<>();
            Set<String> types = new HashSet<>();
            for (QuestStore.Slot s : slots) {
                taken.add(s.id);
                QuestPool.Def d = pool.def(s.id);
                if (d != null) types.add(d.type());
            }
            QuestPool.Def pick = pickOne(p, weekly, i < diffs.size() ? diffs.get(i) : "easy", taken, types, Set.of());
            if (pick == null) continue;
            slots.set(i, new QuestStore.Slot(pick.id()));
            changed = true;
        }
        if (changed) {
            store.dirty = true;
            changed(p);
        }
    }

    private List<QuestStore.Slot> assign(ServerPlayer p, boolean weekly, Set<String> avoid) {
        List<QuestStore.Slot> out = new ArrayList<>();
        Set<String> taken = new HashSet<>(), types = new HashSet<>();
        for (String diff : weekly ? weeklySlots : dailySlots) {
            QuestPool.Def d = pickOne(p, weekly, diff, taken, types, avoid);
            if (d == null) continue;
            taken.add(d.id());
            types.add(d.type());
            out.add(new QuestStore.Slot(d.id()));
        }
        return out;
    }

    /**
     * A random quest (by weight) for a slot: the slot's difficulty, a type the set doesn't have yet and not one of
     * {@code avoid} (yesterday's) if possible; failing that, gradually less picky.
     */
    QuestPool.Def pickOne(ServerPlayer p, boolean weekly, String diff, Set<String> taken, Set<String> types, Set<String> avoid) {
        List<QuestPool.Def> all = new ArrayList<>();
        for (QuestPool.Def d : (weekly ? pool.weekly : pool.daily).values()) {
            if (d.weight() > 0 && !taken.contains(d.id()) && available(p, d)) all.add(d);
        }
        List<java.util.function.Predicate<QuestPool.Def>> tiers = List.of(
                d -> d.difficulty().equals(diff) && !types.contains(d.type()) && !avoid.contains(d.id()),
                d -> d.difficulty().equals(diff) && !avoid.contains(d.id()),
                d -> d.difficulty().equals(diff),
                d -> !types.contains(d.type()),
                d -> true);
        for (var tier : tiers) {
            List<QuestPool.Def> list = all.stream().filter(tier).toList();
            if (!list.isEmpty()) return weighted(list);
        }
        return null;
    }

    private static QuestPool.Def weighted(List<QuestPool.Def> list) {
        int total = 0;
        for (QuestPool.Def d : list) total += d.weight();
        int r = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (QuestPool.Def d : list) {
            r -= d.weight();
            if (r < 0) return d;
        }
        return list.get(0);
    }

    /** Whether the feature a quest needs runs on this server (and, for the codex, the player has something left to find). */
    boolean available(ServerPlayer p, QuestPool.Def d) {
        if (!typeAvailable(d.type())) return false;
        if (d.type().equals("codex") && p != null) {
            try {
                Hooks.PetProgress pets = pets();
                return pets != null && pets.codexUnseen(p.getUUID()) >= d.amount();
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * The server-goal scale: {@code clamp(players active in the last scaling.active-days days / reference, min, max)}
     * (1 with scaling off). Returns {scale, active players}. Online players always count; the others by when their
     * player file was last written (it is saved when they leave).
     */
    double[] goalScale() {
        if (!goalScaling) return new double[]{1.0, -1};
        long cutoff = System.currentTimeMillis() - goalScaleDays * 86_400_000L;
        Set<UUID> active = new HashSet<>();
        for (ServerPlayer p : plugin.onlinePlayers()) active.add(p.getUUID());
        // Profiles knows when every player file was written (startup walk + saves since); only before that walk
        // finished (at boot) is the folder scanned here.
        int indexed = plugin.profiles().writtenSince(cutoff, active);
        Path folder = plugin.profiles().folder();
        if (indexed < 0 && Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.forEach(f -> {
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".yml")) return;
                    try {
                        if (Files.getLastModifiedTime(f).toMillis() >= cutoff) active.add(UUID.fromString(n.substring(0, n.length() - 4)));
                    } catch (IOException | IllegalArgumentException ignored) {
                    }
                });
            } catch (IOException e) {
                plugin.getLogger().warn("Server goal: could not list player files: {}", e.toString());
            }
        }
        double scale = Math.max(goalScaleMin, Math.min(goalScaleMax, active.size() / goalScaleReference));
        return new double[]{scale, active.size()};
    }

    boolean goalAvailable(QuestPool.Goal g) {
        return typeAvailable(g.type()) && !g.type().equals("codex");
    }

    private boolean typeAvailable(String type) {
        Yaml c = plugin.getConfig();
        return switch (type) {
            case "tame", "bond", "codex" -> pets() != null;
            case "boss" -> c.getBoolean("modules.boss-altars", true);
            case "scythe", "hammer", "fell" -> c.getBoolean("modules.arcana-tools", true);
            case "deposit" -> plugin.service(Hooks.Bank.class) != null;
            case "visit" -> c.getBoolean("modules.teleport-requests", true);
            case "chatgame" -> c.getBoolean("modules.chatgames", true);
            case "undead_cull" -> c.getBoolean("modules.wildlife", true);
            default -> true;
        };
    }

    // ------------------------------------------------------------------ progress

    /** Whether what this player does counts right now: past the auth gate, not AFK, in survival or adventure. */
    boolean counts(ServerPlayer p) {
        if (p == null || p.hasDisconnected() || plugin.authPending(p)) return false;
        if (plugin.isAfk(p)) return false;
        GameType m = p.gameMode();
        return m == GameType.SURVIVAL || m == GameType.ADVENTURE;
    }

    /**
     * Reports something a player did, for their quests and the server goal: {@code type} is one of
     * {@link QuestPool#TYPES} ("mine", "tame", "boss" ...), {@code target} what it was (a block, item or entity id, a
     * creature species or boss type; null or "" when it doesn't matter) and {@code amount} how much. Ignored for players
     * who are AFK, haven't passed the auth gate or are in creative/spectator. Server thread.
     */
    @Override
    public void progress(ServerPlayer player, String type, String target, int amount) {
        record(player, type, target, amount, null, 1);
    }

    /** {@link #progress(ServerPlayer, String, String, int)} that counts {@code key} (an entity id, a friend) once per quest. */
    @Override
    public void progress(ServerPlayer player, String type, String target, int amount, String key) {
        record(player, type, target, amount, key, 1);
    }

    /**
     * Coins taken out of the bank: deposit quests and a deposit goal lose that much progress (no deposit/withdraw loops).
     * Deposits count net of withdrawals within the quest's day (week): coins a claimed quest already counted must be put
     * back that day (week) before new deposits count again.
     */
    @Override
    public void withdrew(ServerPlayer player, long amount) {
        if (player == null || amount <= 0 || store.unreadable) return;
        QuestStore.Quests q = state(player);
        long[] debt = depositDebt(player.getUUID());
        long[] claimed = new long[2];
        for (QuestStore.Slot s : all(q)) {
            QuestPool.Def d = pool.def(s.id);
            if (d == null || !d.type().equals("deposit")) continue;
            if (s.claimed) {
                claimed[d.weekly() ? 1 : 0] += d.amount();
                continue;
            }
            // Finished-but-unclaimed counts too: depositing, withdrawing and claiming must not pay for coins that left.
            s.progress = (int) Math.max(0, s.progress - amount);
            store.dirty = true;
            changed(player);
        }
        for (int w = 0; w < 2; w++) {
            int i = w == 0 ? 1 : 3;
            if (claimed[w] > 0) debt[i] = Math.min(claimed[w], debt[i] + amount);
        }
        if (goalEnabled) goal.undoDeposit(player, amount);
    }

    /** The player's deposit ledger ({day, coins, week, coins}), started over when the day or week turned. */
    private long[] depositDebt(UUID id) {
        long today = today(), week = thisWeek();
        long[] d = depositDebt.computeIfAbsent(id, k -> new long[]{today, 0, week, 0});
        if (d[0] != today) {
            d[0] = today;
            d[1] = 0;
        }
        if (d[2] != week) {
            d[2] = week;
            d[3] = 0;
        }
        return d;
    }

    /** This week's server goal progress, 0-100 (100 once completed), or -1 while the goal is off or none runs. For civic fountains. */
    @Override
    public int goalPercent() {
        if (!goalEnabled || goal == null || goal.id == null || goal.def() == null) return -1;
        return goal.completed() ? 100 : goal.percent();
    }

    /**
     * An altar boss died. {@code damage}: who fought and how much damage each dealt. Players with at least
     * {@code settings.boss-min-share} of the damage (the top damager always) count, and the party size is how many count.
     */
    @Override
    public void bossDefeated(String boss, Map<UUID, Double> damage) {
        if (damage == null || damage.isEmpty()) return;
        double total = 0;
        for (double d : damage.values()) total += Math.max(0, d);
        if (total <= 0) return;
        List<UUID> party = new ArrayList<>();
        UUID top = null;
        double best = -1;
        for (Map.Entry<UUID, Double> e : damage.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                top = e.getKey();
            }
            if (e.getValue() / total >= events.bossMinShare) party.add(e.getKey());
        }
        if (party.isEmpty() && top != null) party.add(top);
        for (UUID id : party) record(plugin.player(id), "boss", boss, 1, null, party.size());
    }

    void record(ServerPlayer p, String type, String target, long amount, String key, int party) {
        if (amount <= 0 || type == null || !counts(p) || store.unreadable) return;
        String t = type.toLowerCase(Locale.ROOT);
        QuestStore.Quests q = state(p);
        long[] counted = {amount, amount};
        if (t.equals("deposit")) {
            long[] debt = depositDebt(p.getUUID());
            for (int w = 0; w < 2; w++) {
                int i = w == 0 ? 1 : 3;
                long back = Math.min(debt[i], amount);
                debt[i] -= back;
                counted[w] = amount - back;
            }
        }
        for (int w = 0; w < 2; w++) {
            // A set still waiting to turn over (see roll) takes nothing new.
            if (w == 0 ? q.day != today() : q.week != thisWeek()) continue;
            if (counted[w] <= 0) continue;
            long add = counted[w];
            List<QuestStore.Slot> slots = w == 0 ? q.daily : q.weekly;
            for (QuestStore.Slot s : slots) {
                QuestPool.Def d = pool.def(s.id);
                if (d == null || s.claimed || s.progress >= d.amount() || !d.type().equals(t) || !d.matches(target)) continue;
                if (party < d.party()) continue;
                if (key != null) {
                    if (s.keys.contains(key)) continue;
                    s.keys.add(key);
                    while (s.keys.size() > QuestStore.MAX_KEYS) s.keys.remove(s.keys.iterator().next());
                }
                s.progress = (int) Math.min(d.amount(), s.progress + add);
                store.dirty = true;
                changed(p);
                if (s.progress >= d.amount()) completed(p, d);
            }
        }
        if (goalEnabled) goal.progress(p, t, target, amount, key, party);
    }

    private void completed(ServerPlayer p, QuestPool.Def d) {
        // record() marked the store dirty; completing pays nothing, so the next save takes it along.
        changed(p);
        text().send(p, d.weekly() ? "quests.completed-weekly" : "quests.completed", Text.unparsed("task", task(d)));
        sound(p, "complete");
    }

    /** The player's quests changed: the Companion's tracker follows (at most once a second). */
    private void changed(ServerPlayer p) {
        bridge.changed(p);
    }

    static List<QuestStore.Slot> all(QuestStore.Quests q) {
        List<QuestStore.Slot> out = new ArrayList<>(q.daily);
        out.addAll(q.weekly);
        return out;
    }

    // ------------------------------------------------------------------ rewards

    long boosted(long coins) {
        return goalEnabled ? Math.round(coins * goal.boost("quest-coins")) : coins;
    }

    /** Multiplies pet bond XP while a bond-xp boost runs (the pets module asks for every gain). */
    @Override
    public int bondXp(int amount) {
        if (!goalEnabled || amount <= 0) return amount;
        double m = goal.boost("bond-xp");
        return m <= 1 ? amount : (int) Math.round(amount * m);
    }

    /** Pays coins into the bank balance. True when paid (or nothing to pay / no bank); false while the bank is closed. */
    boolean credit(ServerPlayer p, long coins, String reason) {
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        if (coins <= 0 || bank == null) return true;
        return bank.credit(p.getUUID(), Names.name(p), coins, reason);
    }

    /** Bond XP for the pets following the player; how many got it. */
    int bondXp(ServerPlayer p, int xp) {
        Hooks.PetProgress pets = pets();
        if (xp <= 0 || pets == null) return 0;
        try {
            return pets.addBondToFollowers(p, xp);
        } catch (RuntimeException e) {
            plugin.getLogger().warn("Quests: could not give bond XP: {}", e.toString());
            return 0;
        }
    }

    /** "25 ⛃ · 20 bond XP"; {@code pets} = how many pets got the XP (-1: don't say). Coins only while the bank runs. */
    Component rewardText(long coins, int bond, int pets) {
        List<Component> parts = new ArrayList<>();
        if (coins > 0 && plugin.service(Hooks.Bank.class) != null) parts.add(text().msg("quests.reward.coins", Text.unparsed("amount", fmt(coins))));
        if (bond > 0 && pets() != null) {
            parts.add(text().msg(pets == 0 ? "quests.reward.bond-no-pets" : "quests.reward.bond", Text.unparsed("amount", fmt(bond))));
        }
        if (parts.isEmpty()) return text().msg("quests.reward.none");
        return Component.join(JoinConfiguration.separator(text().msg("quests.reward.separator")), parts);
    }

    /** Claims a finished quest from the menu: marked claimed and saved first, then paid. {@code id}: the quest the button was for. */
    void claim(ServerPlayer p, boolean weekly, int index, String id) {
        QuestStore.Quests q = state(p);
        List<QuestStore.Slot> slots = weekly ? q.weekly : q.daily;
        QuestStore.Slot s = index >= 0 && index < slots.size() ? slots.get(index) : null;
        QuestPool.Def d = s == null || !s.id.equals(id) ? null : pool.def(s.id);
        if (d == null || s.claimed || s.progress < d.amount()) {
            error(p, "quests.not-ready");
            menu.open(p);
            return;
        }
        long coins = boosted(d.reward().coins());
        s.claimed = true;
        if (!store.saveNow()) {
            s.claimed = false;
            error(p, "quests.closed");
            return;
        }
        if (!credit(p, coins, (weekly ? "weekly" : "daily") + " quest " + d.id())) {
            s.claimed = false;
            // In the background: a crash before it lands leaves the quest claimed but unpaid (the safe direction).
            store.dirty = true;
            store.saveIfDirtyAsync();
            error(p, "quests.bank-closed");
            menu.open(p);
            return;
        }
        int pets = bondXp(p, d.reward().bond());
        q.completed++;
        store.dirty = true;
        text().send(p, "quests.claimed", Text.unparsed("task", task(d)), Text.component("reward", rewardText(coins, d.reward().bond(), pets)));
        sound(p, "claim");
        menu.open(p);
    }

    /** Swaps an unfinished daily quest for another of the same difficulty (rerolls-per-day times a day). */
    void reroll(ServerPlayer p, int index, String id) {
        QuestStore.Quests q = state(p);
        QuestStore.Slot s = index >= 0 && index < q.daily.size() ? q.daily.get(index) : null;
        QuestPool.Def old = s == null || !s.id.equals(id) ? null : pool.def(s.id);
        if (q.rerolls >= rerollsPerDay) {
            error(p, "quests.reroll-none");
            menu.open(p);
            return;
        }
        if (old == null || s.claimed || s.progress >= old.amount()) {
            error(p, "quests.reroll-done");
            menu.open(p);
            return;
        }
        Set<String> taken = new HashSet<>(), types = new HashSet<>();
        for (QuestStore.Slot o : q.daily) {
            taken.add(o.id);
            QuestPool.Def d = pool.def(o.id);
            if (d != null && o != s) types.add(d.type());
        }
        types.add(old.type());
        QuestPool.Def pick = pickOne(p, false, old.difficulty(), taken, types, Set.of());
        if (pick == null) {
            error(p, "quests.reroll-nothing");
            menu.open(p);
            return;
        }
        q.daily.set(index, new QuestStore.Slot(pick.id()));
        q.rerolls++;
        store.dirty = true;
        store.saveIfDirtyAsync();
        changed(p);
        text().send(p, "quests.rerolled", Text.unparsed("old", task(old)), Text.unparsed("task", task(pick)));
        sound(p, "reroll");
        menu.open(p);
    }

    /** Claims every unclaimed server-goal reward of the player. */
    void claimGoal(ServerPlayer p) {
        List<ServerGoal.Pending> list = goal.rewards.get(p.getUUID());
        if (list == null || list.isEmpty()) {
            error(p, "quests.goal.nothing-to-claim");
            menu.openGoal(p);
            return;
        }
        long coins = 0;
        int bond = 0;
        for (ServerGoal.Pending r : list) {
            coins += r.coins();
            bond += r.bond();
        }
        List<ServerGoal.Pending> taken = new ArrayList<>(list);
        goal.rewards.remove(p.getUUID());
        if (!goal.save()) {
            goal.rewards.put(p.getUUID(), taken);
            error(p, "quests.closed");
            return;
        }
        if (!credit(p, coins, "server goal reward")) {
            goal.rewards.put(p.getUUID(), taken);
            goal.save();
            error(p, "quests.bank-closed");
            menu.openGoal(p);
            return;
        }
        int pets = bondXp(p, bond);
        text().send(p, "quests.goal.claimed", Text.component("reward", rewardText(coins, bond, pets)));
        sound(p, "claim");
        menu.openGoal(p);
    }

    void error(ServerPlayer to, String key, TagResolver... r) {
        text().send(to, key, r);
        text().sound(to, "error");
    }

    void error(Sender to, String key, TagResolver... r) {
        text().send((Audience) to, key, r);
        if (to.player() != null) text().sound(to.player(), "error");
    }

    // ------------------------------------------------------------------ text

    static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** A quest's task as plain text: "Mine 200 stone". */
    String task(QuestPool.Def d) {
        return taskText(d.type(), d.text(), d.targetName(), d.amount(), d.party());
    }

    String goalTask(QuestPool.Goal g, long amount) {
        return taskText(g.type(), g.text(), g.targetName(), amount, 1);
    }

    private String taskText(String type, String custom, String targetName, long amount, int party) {
        String target = targetName != null && !targetName.isBlank() ? targetName
                : text().raw(amount == 1 ? "quests.target-one." + type : "quests.target." + type);
        String raw = custom != null && !custom.isBlank() ? custom : text().raw("quests.task." + type);
        Component c = text().parse(raw, Text.unparsed("amount", fmt(amount)), Text.unparsed("target", target));
        if (party > 1) c = c.append(text().msg("quests.task.party", Text.unparsed("party", party)));
        return PLAIN.serialize(c);
    }

    Component boostName(String type, double multiplier) {
        return text().msg("quests.boost." + type, Text.unparsed("percent", Math.round((multiplier - 1) * 100)));
    }

    // ------------------------------------------------------------------ join notices

    /**
     * Join digest (order 20): turns the player's quests over (paying finished ones) and tells what waits. With the
     * digest on, one {@code quests.join} line whose {@code <status>} lists "new today" or "n to claim", the server
     * boosts running and a goal reward waiting (each part only when it applies); with it off, the separate lines as
     * before.
     */
    private void collectJoin(ServerPlayer p, List<JoinDigest.Line> out) {
        if (plugin.authPending(p) || store.unreadable) return;
        QuestStore.Quests before = store.players.get(p.getUUID());
        boolean fresh = before == null || before.day != today();
        QuestStore.Quests q = state(p);
        boolean questPerm = Perms.has(p, PERM);
        int ready = 0;
        if (!fresh) {
            for (QuestStore.Slot s : all(q)) {
                QuestPool.Def d = pool.def(s.id);
                if (d != null && !s.claimed && s.progress >= d.amount()) ready++;
            }
        }
        List<Component[]> boosts = new ArrayList<>();   // {boost name, time left}
        boolean reward = false;
        if (goalEnabled) {
            goal.check();
            long now = System.currentTimeMillis();
            for (ServerGoal.Boost b : goal.boosts.values()) {
                if (b.until() > now) boosts.add(new Component[]{boostName(b.type(), b.multiplier()), Component.text(Placeholders.duration(b.until() - now))});
            }
            List<ServerGoal.Pending> waiting = goal.rewards.get(p.getUUID());
            reward = waiting != null && !waiting.isEmpty() && Perms.has(p, GOAL_PERM);
        }
        if (!JoinDigest.on()) {
            if (fresh && questPerm) add(out, JoinDigest.render("quests.join-new"));
            else if (ready > 0 && questPerm) add(out, JoinDigest.render("quests.join-ready", Text.unparsed("count", ready)));
            for (Component[] b : boosts) {
                add(out, JoinDigest.render("quests.goal.boost-join", Text.component("boost", b[0]), Text.component("time", b[1])));
            }
            if (reward) add(out, JoinDigest.render("quests.goal.reward-waiting"));
            return;
        }
        List<Component> parts = new ArrayList<>();
        if (fresh && questPerm) parts.add(text().msg("quests.join-status.new"));
        else if (ready > 0 && questPerm) parts.add(text().msg("quests.join-status.ready", Text.unparsed("count", ready)));
        for (Component[] b : boosts) parts.add(text().msg("quests.join-status.boost", Text.component("boost", b[0]), Text.component("time", b[1])));
        if (reward) parts.add(text().msg("quests.join-status.goal-reward"));
        if (parts.isEmpty()) return;
        Component status = Component.join(JoinConfiguration.separator(text().msg("quests.join-status.separator")), parts);
        add(out, JoinDigest.render("quests.join", Text.component("status", status)));
    }

    private static void add(List<JoinDigest.Line> out, Component line) {
        if (line != null) out.add(new JoinDigest.Line(20, line, null));
    }

    void quit(ServerPlayer p) {
        rollRetry.remove(p.getUUID());
        store.saveIfDirtyAsync();
        goal.saveIfDirtyAsync();
    }

    // ------------------------------------------------------------------ commands

    /** /quests ({@code goal} false) and /goal. */
    private final class Cmd implements FeatherCommand {
        private final boolean isGoal;

        Cmd(boolean isGoal) {
            this.isGoal = isGoal;
        }

        @Override
        public boolean onCommand(Sender sender, String label, String[] args) {
            return command(sender, isGoal, args);
        }

        @Override
        public List<String> onTabComplete(Sender sender, String label, String[] args) {
            return complete(sender, isGoal, args);
        }
    }

    private boolean command(Sender sender, boolean isGoal, String[] args) {
        if (isGoal && args.length > 0 && args[0].equalsIgnoreCase("rescale")) {
            // /goal rescale is short for /goal admin rescale.
            if (!sender.hasPermission(GOAL_ADMIN)) {
                error(sender, "general.no-permission");
                return true;
            }
            goalAdmin(sender, new String[]{"admin", "rescale"});
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission(isGoal ? GOAL_ADMIN : ADMIN)) {
                error(sender, "general.no-permission");
                return true;
            }
            if (isGoal) goalAdmin(sender, args);
            else questAdmin(sender, args);
            return true;
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            text().send(sender, isGoal ? "quests.goal.admin.help" : "quests.admin.help");
            return true;
        }
        if (store.unreadable) {
            error(p, "quests.closed");
            return true;
        }
        if (isGoal) menu.openGoal(p);
        else menu.open(p);
        return true;
    }

    private ServerPlayer online(Sender sender, String name) {
        ServerPlayer p = plugin.findPlayer(sender, name);
        if (p == null) {
            plugin.notFound(sender, name);
            if (sender.player() != null) text().sound(sender.player(), "error");
        }
        return p;
    }

    private void questAdmin(Sender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (store.unreadable && !sub.isEmpty()) {
            error(sender, "quests.closed");
            return;
        }
        switch (sub) {
            case "reset" -> {
                // /quests admin reset <player|all> [daily|weekly|both]: a new set now, as if the day (week) turned over.
                if (args.length < 3) {
                    text().send(sender, "quests.admin.help");
                    return;
                }
                String which = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "daily";
                List<ServerPlayer> targets = new ArrayList<>();
                if (args[2].equalsIgnoreCase("all")) targets.addAll(plugin.onlinePlayers());
                else {
                    ServerPlayer p = online(sender, args[2]);
                    if (p == null) return;
                    targets.add(p);
                }
                boolean paid = false;
                for (ServerPlayer p : targets) {
                    QuestStore.Quests q = state(p);
                    if (!which.equals("weekly")) paid |= roll(p, q, false, today());
                    if (which.equals("weekly") || which.equals("both")) paid |= roll(p, q, true, thisWeek());
                    text().send(p, which.equals("weekly") ? "quests.new-week" : "quests.new-day");
                }
                saveAfterRollover(paid);
                text().send(sender, "quests.admin.reset", Text.unparsed("count", targets.size()), Text.unparsed("which", which));
            }
            case "give", "complete" -> {
                // give <player> <daily|weekly> <slot> <quest> | complete <player> <daily|weekly> <slot>
                if (args.length < (sub.equals("give") ? 6 : 5)) {
                    text().send(sender, "quests.admin.help");
                    return;
                }
                ServerPlayer p = online(sender, args[2]);
                if (p == null) return;
                boolean weekly = args[3].equalsIgnoreCase("weekly");
                QuestStore.Quests q = state(p);
                List<QuestStore.Slot> slots = weekly ? q.weekly : q.daily;
                int slot;
                try {
                    slot = Integer.parseInt(args[4]) - 1;
                } catch (NumberFormatException e) {
                    slot = -1;
                }
                if (slot < 0 || slot >= slots.size()) {
                    error(sender, "quests.admin.bad-slot", Text.unparsed("max", slots.size()));
                    return;
                }
                if (sub.equals("give")) {
                    QuestPool.Def d = (weekly ? pool.weekly : pool.daily).get(args[5]);
                    if (d == null) {
                        error(sender, "quests.admin.unknown-quest", Text.unparsed("id", args[5]));
                        return;
                    }
                    slots.set(slot, new QuestStore.Slot(d.id()));
                    store.dirty = true;
                    store.saveIfDirtyAsync();
                    changed(p);
                    text().send(sender, "quests.admin.given", Text.unparsed("player", Names.name(p)), Text.unparsed("task", task(d)));
                } else {
                    QuestStore.Slot s = slots.get(slot);
                    QuestPool.Def d = pool.def(s.id);
                    if (d == null || s.claimed || s.progress >= d.amount()) {
                        error(sender, "quests.not-ready");
                        return;
                    }
                    s.progress = d.amount();
                    store.dirty = true;
                    completed(p, d);
                    text().send(sender, "quests.admin.completed", Text.unparsed("player", Names.name(p)), Text.unparsed("task", task(d)));
                }
            }
            case "progress" -> {
                // progress <player> <type> <target|-> <amount>: as if the player did it (AFK and the auth gate apply).
                if (args.length < 6) {
                    text().send(sender, "quests.admin.help");
                    return;
                }
                ServerPlayer p = online(sender, args[2]);
                if (p == null) return;
                String type = args[3].toLowerCase(Locale.ROOT);
                if (!QuestPool.TYPES.contains(type)) {
                    error(sender, "quests.admin.unknown-type", Text.unparsed("types", String.join(", ", QuestPool.TYPES)));
                    return;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[5]);
                } catch (NumberFormatException e) {
                    amount = -1;
                }
                if (amount <= 0) {
                    error(sender, "quests.admin.bad-amount");
                    return;
                }
                String target = args[4].equals("-") ? "" : args[4];
                record(p, type, target, amount, null, args.length > 6 ? Math.max(1, parseInt(args[6])) : 1);
                text().send(sender, "quests.admin.progressed", Text.unparsed("player", Names.name(p)), Text.unparsed("type", type),
                        Text.unparsed("amount", amount));
            }
            case "list" -> text().send(sender, "quests.admin.list", Text.unparsed("daily", String.join(", ", pool.daily.keySet())),
                    Text.unparsed("weekly", String.join(", ", pool.weekly.keySet())));
            default -> text().send(sender, "quests.admin.help");
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void goalAdmin(Sender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!goalEnabled) {
            error(sender, "quests.goal.off");
            return;
        }
        if (goal.unreadable && !sub.isEmpty()) {
            error(sender, "quests.closed");
            return;
        }
        goal.check();
        switch (sub) {
            case "set" -> {
                if (args.length < 3 || !pool.goals.containsKey(args[2])) {
                    error(sender, "quests.goal.admin.unknown", Text.unparsed("ids", String.join(", ", pool.goals.keySet())));
                    return;
                }
                goal.start(args[2], thisWeek(), true);
                text().send(sender, "quests.goal.admin.set", Text.unparsed("goal", goal.label()));
            }
            case "skip" -> {
                String before = goal.id;
                List<String> ids = new ArrayList<>(pool.goals.keySet());
                ids.removeIf(id -> id.equals(before) || !goalAvailable(pool.goals.get(id)) || pool.goals.get(id).weight() <= 0);
                if (ids.isEmpty()) {
                    error(sender, "quests.goal.admin.unknown", Text.unparsed("ids", String.join(", ", pool.goals.keySet())));
                    return;
                }
                goal.start(ids.get(ThreadLocalRandom.current().nextInt(ids.size())), thisWeek(), true);
                text().send(sender, "quests.goal.admin.set", Text.unparsed("goal", goal.label()));
            }
            case "complete" -> {
                if (goal.completed()) {
                    error(sender, "quests.goal.admin.already");
                    return;
                }
                goal.complete();
                text().send(sender, "quests.goal.admin.completed", Text.unparsed("goal", goal.label()));
            }
            case "add" -> {
                // add <amount> [player]: progress in that player's name (they need not be online).
                long amount = args.length > 2 ? parseLong(args[2]) : -1;
                if (amount <= 0) {
                    error(sender, "quests.admin.bad-amount");
                    return;
                }
                UUID who = null;
                String name = null;
                if (args.length > 3) {
                    OfflinePlayer op = plugin.findOfflinePlayer(sender, args[3]);
                    if (op == null) {
                        error(sender, "general.player-not-found", Text.unparsed("input", args[3]));
                        return;
                    }
                    who = op.id();
                    name = op.name() != null ? op.name() : args[3];
                }
                if (goal.completed()) {
                    error(sender, "quests.goal.admin.already");
                    return;
                }
                goal.addAdmin(who, name, amount);
                goal.save();
                text().send(sender, "quests.goal.admin.added", Text.unparsed("amount", fmt(amount)), Text.unparsed("progress", fmt(goal.progress)),
                        Text.unparsed("target", fmt(goal.target)));
            }
            case "rescale" -> {
                long before = goal.target;
                if (!goal.rescale()) {
                    error(sender, goal.completed() ? "quests.goal.admin.already" : "quests.goal.admin.none");
                    return;
                }
                text().send(sender, "quests.goal.admin.rescaled", Text.unparsed("goal", goal.label()), Text.unparsed("before", fmt(before)),
                        Text.unparsed("target", fmt(goal.target)), Text.unparsed("scale", String.format(Locale.ROOT, "%.2f", goal.scale)),
                        Text.unparsed("players", goal.activePlayers < 0 ? "-" : String.valueOf(goal.activePlayers)));
            }
            case "info" -> text().send(sender, "quests.goal.admin.info", Text.unparsed("goal", goal.id == null ? "-" : goal.id),
                    Text.unparsed("progress", fmt(goal.progress)), Text.unparsed("target", fmt(goal.target)),
                    Text.unparsed("contributors", goal.contributions.size()), Text.unparsed("pending", goal.rewards.size()),
                    Text.unparsed("boosts", goal.boosts.isEmpty() ? "-" : String.join(", ", goal.boosts.keySet())));
            default -> text().send(sender, "quests.goal.admin.help");
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private List<String> complete(Sender sender, boolean isGoal, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(isGoal ? GOAL_ADMIN : ADMIN)) return out;
        if (args.length == 1) {
            out.add("admin");
            if (isGoal) out.add("rescale");
        } else if (args[0].equalsIgnoreCase("admin")) {
            if (args.length == 2) out.addAll(isGoal ? List.of("set", "skip", "complete", "add", "info", "rescale") : List.of("reset", "give", "complete", "progress", "list"));
            else if (isGoal && args.length == 3 && args[1].equalsIgnoreCase("set")) out.addAll(pool.goals.keySet());
            else if (isGoal && args.length == 4 && args[1].equalsIgnoreCase("add")) out.addAll(plugin.completePlayers((Sender) null, ""));
            else if (!isGoal && args.length == 3) {
                out.addAll(plugin.completePlayers((Sender) null, ""));
                if (args[1].equalsIgnoreCase("reset")) out.add("all");
            } else if (!isGoal && args.length == 4) {
                if (args[1].equalsIgnoreCase("progress")) out.addAll(QuestPool.TYPES);
                else if (args[1].equalsIgnoreCase("reset")) out.addAll(List.of("daily", "weekly", "both"));
                else out.addAll(List.of("daily", "weekly"));
            } else if (!isGoal && args.length == 6 && args[1].equalsIgnoreCase("give")) {
                out.addAll((args[3].equalsIgnoreCase("weekly") ? pool.weekly : pool.daily).keySet());
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
