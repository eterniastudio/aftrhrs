package net.axther.aftrhrs.feature.quests;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The weekly server goal: one community goal at a time (from {@code quests.yml goals}), that everyone's matching
 * actions add to. Short broadcasts at 25/50/75/100%. On completion every player who contributed at least the minimum
 * gets a reward to claim in {@code /goal} (kept until they do, so it works for players who were offline), the goal's
 * boosts start (e.g. +50% quest coins for 48h) and its console commands run. A new goal starts each Monday (in
 * {@code daily.reset-zone}). Everything is saved in {@code data/server-goal.yml}; an unreadable file is never saved over.
 */
final class ServerGoal {
    record Boost(String type, double multiplier, long until) {
    }

    /** A completed goal's reward one player hasn't claimed yet. {@code label} is the goal's task, as plain text. */
    record Pending(String goal, String label, long coins, int bond, long at) {
    }

    private static final int[] MILESTONES = {25, 50, 75};
    private static final int MAX_PENDING = 20;
    private static final String HEADER = "# Feather server goal: this week's goal, contributions, active boosts and unclaimed rewards. Times are epoch ms.\n\n";

    private final QuestsModule quests;
    private final Path file;

    String id;
    long week = Long.MIN_VALUE;
    long target;
    long progress;
    /** How many of the 25/50/75 broadcasts went out. */
    int announced;
    long completedAt;
    final Map<UUID, Long> contributions = new HashMap<>();
    final Map<UUID, String> names = new HashMap<>();
    /** "player|key" pairs that counted once already (a tamed creature, a friend visited). */
    final Set<String> keys = new LinkedHashSet<>();
    final List<String> recent = new ArrayList<>();
    final Map<String, Boost> boosts = new LinkedHashMap<>();
    final Map<UUID, List<Pending>> rewards = new HashMap<>();
    boolean unreadable;
    private boolean dirty;

    ServerGoal(QuestsModule quests, Path file) {
        this.quests = quests;
        this.file = file;
    }

    QuestPool.Goal def() {
        return id == null ? null : quests.pool.goals.get(id);
    }

    boolean completed() {
        return completedAt > 0;
    }

    /** The smallest contribution that earns the reward. */
    long minContribution() {
        QuestPool.Goal g = def();
        if (g != null && g.minContribution() > 0) return g.minContribution();
        return Math.max(1, (long) Math.ceil(target * quests.goalMinPercent / 100.0));
    }

    /** The scale applied when the running goal started (or was rescaled), and the active players it was based on. */
    double scale = 1.0;
    int activePlayers = -1;

    /**
     * The goal's amount for this server: {@code amount x multiplier x clamp(active players / reference, min, max)}
     * (see {@link QuestsModule#goalScale}); remembers the scale used in {@link #scale}.
     */
    long scaled(QuestPool.Goal g) {
        double[] sc = quests.goalScale();
        scale = sc[0];
        activePlayers = (int) sc[1];
        return Math.max(1, Math.round(g.amount() * quests.goalMultiplier * scale));
    }

    /**
     * /goal rescale: the running goal's target is worked out again for today's active players. Progress stays; a
     * goal that is now reached completes. False when no goal runs or it is already complete.
     */
    boolean rescale() {
        QuestPool.Goal g = def();
        if (unreadable || g == null || completed()) return false;
        long before = target;
        target = scaled(g);
        announced = 0;
        int pct = percent();
        while (announced < MILESTONES.length && pct >= MILESTONES[announced]) announced++;
        quests.plugin().getLogger().info("Server goal {} rescaled: {} -> {} (scale {}, {} active players).", id, before, target, scale, activePlayers);
        if (progress >= target) complete();
        else save();
        return true;
    }

    // ------------------------------------------------------------------ the week

    /** Starts this week's goal when the week turned over (or none runs yet). Checked every minute and on use. */
    void check() {
        if (unreadable) return;
        long now = System.currentTimeMillis();
        if (boosts.values().removeIf(b -> b.until() <= now)) dirty = true;
        long thisWeek = quests.thisWeek();
        if (id != null && week == thisWeek && def() != null) {
            saveIfDirtyAsync();
            return;
        }
        if (id != null && week != thisWeek && !completed() && def() != null) {
            quests.broadcast(quests.text().msg("quests.goal.ended", Text.unparsed("goal", label()), Text.unparsed("percent", percent())));
        }
        if (!start(pick(), thisWeek, id != null)) saveIfDirtyAsync();
    }

    private String pick() {
        List<QuestPool.Goal> all = new ArrayList<>();
        for (QuestPool.Goal g : quests.pool.goals.values()) if (g.weight() > 0 && quests.goalAvailable(g)) all.add(g);
        if (all.isEmpty()) return null;
        List<QuestPool.Goal> fresh = new ArrayList<>(all);
        fresh.removeIf(g -> recent.contains(g.id()) || g.id().equals(id));
        return weighted(fresh.isEmpty() ? all : fresh).id();
    }

    private static QuestPool.Goal weighted(List<QuestPool.Goal> list) {
        int total = 0;
        for (QuestPool.Goal g : list) total += g.weight();
        int r = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (QuestPool.Goal g : list) {
            r -= g.weight();
            if (r < 0) return g;
        }
        return list.get(0);
    }

    /** Starts goal {@code newId} now (fresh progress). False (and nothing changes) when there is no such goal. */
    boolean start(String newId, long forWeek, boolean announce) {
        QuestPool.Goal g = newId == null ? null : quests.pool.goals.get(newId);
        if (g == null) return false;
        if (id != null) {
            recent.remove(id);
            recent.add(0, id);
            while (recent.size() > 3) recent.remove(recent.size() - 1);
        }
        id = g.id();
        week = forWeek;
        target = scaled(g);
        progress = 0;
        announced = 0;
        completedAt = 0;
        contributions.clear();
        names.clear();
        keys.clear();
        save();
        quests.plugin().getLogger().info("Server goal: {} ({}) started.", id, target);
        if (announce) quests.broadcast(quests.text().msg("quests.goal.new", Text.unparsed("goal", label())));
        return true;
    }

    /** The goal's task as plain text ("Defeat 25 bosses at an altar"). */
    String label() {
        QuestPool.Goal g = def();
        return g == null ? (id == null ? "" : id) : quests.goalTask(g, target);
    }

    int percent() {
        return target <= 0 ? 0 : (int) Math.min(100, progress * 100 / target);
    }

    // ------------------------------------------------------------------ progress

    void progress(ServerPlayer p, String type, String reported, long amount, String key, int party) {
        if (unreadable || id == null || completed() || amount <= 0) return;
        QuestPool.Goal g = def();
        if (g == null || !g.type().equals(type) || !g.matches(reported)) return;
        if (key != null && !keys.add(p.getUUID() + "|" + key)) return;
        long add = Math.min(amount, target - progress);
        if (add <= 0) return;
        progress += add;
        contributions.merge(p.getUUID(), add, Long::sum);
        names.put(p.getUUID(), Names.name(p));
        dirty = true;
        afterProgress();
    }

    /** Takes back deposit progress for withdrawn coins (only what this player put in, and only before completion). */
    void undoDeposit(ServerPlayer p, long amount) {
        if (unreadable || id == null || completed()) return;
        QuestPool.Goal g = def();
        if (g == null || !g.type().equals("deposit")) return;
        long mine = contributions.getOrDefault(p.getUUID(), 0L);
        long back = Math.min(mine, amount);
        if (back <= 0) return;
        contributions.put(p.getUUID(), mine - back);
        progress = Math.max(0, progress - back);
        dirty = true;
    }

    /** Admin/test: adds progress in a player's name (online or not). */
    void addAdmin(UUID who, String name, long amount) {
        if (unreadable || id == null || completed() || amount <= 0) return;
        long add = Math.min(amount, target - progress);
        progress += add;
        if (who != null) {
            contributions.merge(who, add, Long::sum);
            if (name != null) names.put(who, name);
        }
        dirty = true;
        afterProgress();
    }

    private void afterProgress() {
        int pct = percent();
        while (announced < MILESTONES.length && pct >= MILESTONES[announced]) {
            if (progress < target) {
                quests.broadcast(quests.text().msg("quests.goal.milestone", Text.unparsed("goal", label()),
                        Text.unparsed("percent", MILESTONES[announced])));
            }
            announced++;
        }
        if (progress >= target) complete();
    }

    /** Completes the goal: rewards for contributors, the boosts, the commands, the broadcast. */
    void complete() {
        if (completed() || id == null) return;
        QuestPool.Goal g = def();
        progress = Math.max(progress, target);
        announced = MILESTONES.length;
        completedAt = System.currentTimeMillis();
        String label = label();
        long min = minContribution();
        int rewarded = 0;
        List<ServerPlayer> onlineRewarded = new ArrayList<>();
        if (g != null && (g.reward().coins() > 0 || g.reward().bond() > 0)) {
            for (Map.Entry<UUID, Long> e : contributions.entrySet()) {
                if (e.getValue() < min) continue;
                List<Pending> list = rewards.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                list.add(new Pending(id, label, g.reward().coins(), g.reward().bond(), completedAt));
                while (list.size() > MAX_PENDING) list.remove(0);
                rewarded++;
                ServerPlayer p = quests.plugin().player(e.getKey());
                if (p != null) onlineRewarded.add(p);
            }
        }
        if (g != null) {
            for (QuestPool.Boost b : g.boosts()) {
                long until = completedAt + b.hours() * 3_600_000L;
                Boost old = boosts.get(b.type());
                // A second boost of the same kind extends the longer one and keeps the bigger multiplier.
                boosts.put(b.type(), old == null ? new Boost(b.type(), b.multiplier(), until)
                        : new Boost(b.type(), Math.max(old.multiplier(), b.multiplier()), Math.max(old.until(), until)));
            }
        }
        save();
        quests.plugin().getLogger().info("Server goal {} completed; {} contributor(s) rewarded.", id, rewarded);
        quests.broadcast(quests.text().msg("quests.goal.complete", Text.unparsed("goal", label)));
        if (g != null) {
            for (QuestPool.Boost b : g.boosts()) {
                quests.broadcast(quests.text().msg("quests.goal.boost-started", Text.component("boost", quests.boostName(b.type(), b.multiplier())),
                        Text.unparsed("hours", b.hours())));
            }
            MinecraftServer server = Feather.serverOrNull();
            for (String raw : g.commands()) {
                if (raw == null || raw.isBlank() || server == null) continue;
                String cmd = raw.replace("<goal>", id).trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                } catch (RuntimeException ex) {
                    quests.plugin().getLogger().warn("Server goal command failed: {} ({})", cmd, ex.toString());
                }
            }
        }
        for (ServerPlayer p : onlineRewarded) {
            quests.text().send(p, "quests.goal.reward-ready");
            quests.sound(p, "complete");
        }
    }

    /** The boost multiplier active for {@code type} (1 when none). */
    double boost(String type) {
        Boost b = boosts.get(type);
        return b == null || b.until() <= System.currentTimeMillis() ? 1.0 : b.multiplier();
    }

    List<Map.Entry<UUID, Long>> top(int n) {
        List<Map.Entry<UUID, Long>> list = new ArrayList<>(contributions.entrySet());
        list.removeIf(e -> e.getValue() <= 0);
        list.sort(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()));
        return list.size() > n ? list.subList(0, n) : list;
    }

    String nameOf(UUID id) {
        String n = names.get(id);
        if (n != null) return n;
        OfflinePlayer off = quests.plugin().lookup().byId(id);
        return off == null || off.name() == null ? id.toString().substring(0, 8) : off.name();
    }

    void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ disk

    void load() {
        Yaml yml = SafeYaml.read(file, quests.plugin().getLogger(), "server goal progress and rewards");
        if (yml == null) {
            unreadable = true;
            return;
        }
        unreadable = false;
        id = yml.getString("goal.id");
        week = yml.getLong("goal.week", Long.MIN_VALUE);
        target = Math.max(1, yml.getLong("goal.target", 1));
        progress = Math.max(0, yml.getLong("goal.progress"));
        announced = Math.max(0, Math.min(MILESTONES.length, yml.getInt("goal.announced")));
        completedAt = yml.getLong("goal.completed-at");
        scale = yml.getDouble("goal.scale", 1.0);
        activePlayers = yml.getInt("goal.active-players", -1);
        contributions.clear();
        names.clear();
        Yaml c = yml.getConfigurationSection("goal.contributors");
        if (c != null) {
            for (String k : c.getKeys(false)) {
                UUID u = uuid(k);
                if (u == null) continue;
                contributions.put(u, Math.max(0, c.getLong(k + ".amount")));
                String n = c.getString(k + ".name");
                if (n != null) names.put(u, n);
            }
        }
        keys.clear();
        keys.addAll(yml.getStringList("goal.keys"));
        recent.clear();
        recent.addAll(yml.getStringList("recent"));
        boosts.clear();
        Yaml b = yml.getConfigurationSection("boosts");
        if (b != null) {
            for (String k : b.getKeys(false)) {
                boosts.put(k, new Boost(k, b.getDouble(k + ".multiplier", 1), b.getLong(k + ".until")));
            }
        }
        rewards.clear();
        Yaml r = yml.getConfigurationSection("rewards");
        if (r != null) {
            for (String k : r.getKeys(false)) {
                UUID u = uuid(k);
                if (u == null) continue;
                List<Pending> list = new ArrayList<>();
                for (Map<?, ?> m : r.getMapList(k)) {
                    list.add(new Pending(String.valueOf(m.get("goal")), String.valueOf(m.get("label")),
                            m.get("coins") instanceof Number n ? n.longValue() : 0, m.get("bond-xp") instanceof Number n ? n.intValue() : 0,
                            m.get("at") instanceof Number n ? n.longValue() : 0));
                }
                if (!list.isEmpty()) rewards.put(u, list);
            }
        }
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@link #save} when something changed (reload). */
    void saveIfDirty() {
        if (dirty) save();
    }

    /**
     * Snapshots now and writes in the background when something changed (the minute check, quits): the server thread
     * never waits for it. A failed write marks the goal dirty again.
     */
    void saveIfDirtyAsync() {
        if (unreadable || !dirty) return;
        dirty = false;
        SafeYaml.writeAsync(file, HEADER, snapshot(), quests.plugin().getLogger()).thenAccept(ok -> {
            if (!ok) Tasks.sync(() -> dirty = true);
        });
    }

    /**
     * Writes everything and waits for it: where the result is checked (a reward claim), for rare steps that must be on
     * disk before what follows (a goal starting or completing runs commands and announces), reload and shutdown. Queued
     * behind the background saves on the data thread, so an older one can't land over it.
     */
    boolean save() {
        if (unreadable) return false;
        boolean ok = SafeYaml.writeSync(file, HEADER, snapshot(), quests.plugin().getLogger());
        if (ok) dirty = false;
        return ok;
    }

    /** The whole file as plain maps, taken on the server thread (nothing in it is shared with the live goal). */
    private Object snapshot() {
        Yaml yml = new Yaml();
        yml.set("goal.id", id);
        yml.set("goal.week", week);
        yml.set("goal.target", target);
        yml.set("goal.progress", progress);
        yml.set("goal.announced", announced);
        yml.set("goal.completed-at", completedAt);
        yml.set("goal.scale", scale);
        yml.set("goal.active-players", activePlayers);
        for (Map.Entry<UUID, Long> e : contributions.entrySet()) {
            yml.set("goal.contributors." + e.getKey() + ".name", names.get(e.getKey()));
            yml.set("goal.contributors." + e.getKey() + ".amount", e.getValue());
        }
        yml.set("goal.keys", new ArrayList<>(keys));
        yml.set("recent", new ArrayList<>(recent));
        for (Boost b : boosts.values()) {
            yml.set("boosts." + b.type() + ".multiplier", b.multiplier());
            yml.set("boosts." + b.type() + ".until", b.until());
        }
        for (Map.Entry<UUID, List<Pending>> e : rewards.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Pending p : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("goal", p.goal());
                m.put("label", p.label());
                m.put("coins", p.coins());
                m.put("bond-xp", p.bond());
                m.put("at", p.at());
                list.add(m);
            }
            if (!list.isEmpty()) yml.set("rewards." + e.getKey(), list);
        }
        return yml.snapshot();
    }
}
