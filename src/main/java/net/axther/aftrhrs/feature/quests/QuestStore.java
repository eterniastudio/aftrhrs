package net.axther.aftrhrs.feature.quests;

import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Yaml;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every player's daily and weekly quests: which quest sits in each slot, its progress, whether it was claimed, and the
 * day/week the set belongs to. Saved to {@code data/quests.yml} through {@link SafeYaml} (temp file + atomic move, a
 * {@code .bak} of the previous good file). A file that can't be read is never saved over.
 */
final class QuestStore {
    /** Keys (an entity or a friend) one slot already counted; at most this many are kept per slot. */
    static final int MAX_KEYS = 200;
    private static final String HEADER = "# Feather quests: each player's daily and weekly quests and progress. Days are epoch days in daily.reset-zone.\n\n";

    static final class Slot {
        String id;
        int progress;
        boolean claimed;
        /** Things that counted once already for this quest (a tamed creature's id, a friend visited). */
        final Set<String> keys = new LinkedHashSet<>();

        Slot(String id) {
            this.id = id;
        }
    }

    static final class Quests {
        final UUID id;
        String name;
        /** Epoch day (in daily.reset-zone) the daily set belongs to; the Monday of the weekly set. */
        long day = Long.MIN_VALUE, week = Long.MIN_VALUE;
        int rerolls;
        final List<Slot> daily = new ArrayList<>();
        final List<Slot> weekly = new ArrayList<>();
        int completed;

        Quests(UUID id) {
            this.id = id;
        }
    }

    private final Path file;
    private final Logger log;
    final Map<UUID, Quests> players = new HashMap<>();
    boolean unreadable;
    boolean dirty;

    QuestStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    Quests get(UUID id) {
        return players.computeIfAbsent(id, Quests::new);
    }

    void load() {
        Yaml yml = SafeYaml.read(file, log, "quest progress");
        if (yml == null) {
            unreadable = true;
            return;
        }
        unreadable = false;
        players.clear();
        Yaml ps = yml.getConfigurationSection("players");
        if (ps == null) return;
        for (String key : ps.getKeys(false)) {
            Yaml s = ps.getConfigurationSection(key);
            if (s == null) continue;
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                log.warn("quests data: skipping a bad player id {}", key);
                continue;
            }
            Quests q = new Quests(id);
            q.name = s.getString("name");
            q.day = s.getLong("day", Long.MIN_VALUE);
            q.week = s.getLong("week", Long.MIN_VALUE);
            q.rerolls = Math.max(0, s.getInt("rerolls"));
            q.completed = Math.max(0, s.getInt("completed"));
            readSlots(s.getMapList("daily"), q.daily);
            readSlots(s.getMapList("weekly"), q.weekly);
            players.put(id, q);
        }
    }

    private static void readSlots(List<Map<?, ?>> list, List<Slot> into) {
        for (Map<?, ?> m : list) {
            Object id = m.get("id");
            if (id == null) continue;
            Slot slot = new Slot(String.valueOf(id));
            slot.progress = m.get("progress") instanceof Number n ? Math.max(0, n.intValue()) : 0;
            slot.claimed = Boolean.TRUE.equals(m.get("claimed"));
            if (m.get("keys") instanceof List<?> keys) for (Object k : keys) slot.keys.add(String.valueOf(k));
            into.add(slot);
        }
    }

    /** A background save failed: the next {@link #saveIfDirtyAsync} tries again. Set on the data thread. */
    private volatile boolean failed;
    /** Saves handed to the data thread in this JVM (game tests count them). */
    int saves;

    /**
     * The whole file as plain maps (server thread): nothing in it is shared with the live quests. Null when empty, like
     * {@link Yaml#snapshot()}. Built directly (no Yaml tree, no second deep copy); the same keys in the same order as a
     * {@code Yaml.set} per field would give.
     */
    Object snapshot() {
        if (players.isEmpty()) return null; // Yaml.dump(null) is "{}", as an empty Yaml gave
        Map<String, Object> ps = new LinkedHashMap<>(players.size() * 4 / 3 + 1);
        for (Quests q : players.values()) {
            Map<String, Object> m = new LinkedHashMap<>(10);
            if (q.name != null) m.put("name", q.name); // Yaml.set(.., null) left the key out
            m.put("day", q.day);
            m.put("week", q.week);
            m.put("rerolls", q.rerolls);
            m.put("completed", q.completed);
            m.put("daily", writeSlots(q.daily)); // fresh maps and lists of immutable leaves
            m.put("weekly", writeSlots(q.weekly));
            ps.put(q.id.toString(), m);
        }
        Map<String, Object> root = new LinkedHashMap<>(2);
        root.put("players", ps);
        return root;
    }

    /**
     * Writes everything and waits for it: the claim path (claimed must be on disk before coins move) and shutdown.
     * Queued behind any background save, so an older one can't land over it. Never while the file couldn't be read.
     */
    boolean saveNow() {
        if (unreadable) return false;
        saves++;
        boolean ok = SafeYaml.writeSync(file, HEADER, snapshot(), log);
        if (ok) {
            dirty = false;
            failed = false;
        }
        return ok;
    }

    /** Snapshots now and writes in the background, when something changed (timers, quits, reloads). */
    void saveIfDirtyAsync() {
        if (unreadable || (!dirty && !failed)) return;
        dirty = false;
        failed = false;
        saves++;
        SafeYaml.writeAsync(file, HEADER, snapshot(), log).thenAccept(ok -> {
            if (!ok) failed = true;
        });
    }

    private static List<Map<String, Object>> writeSlots(List<Slot> slots) {
        List<Map<String, Object>> out = new ArrayList<>(slots.size());
        for (Slot s : slots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("progress", s.progress);
            m.put("claimed", s.claimed);
            if (!s.keys.isEmpty()) m.put("keys", new ArrayList<>(s.keys));
            out.add(m);
        }
        return out;
    }

    /** Forgets players whose quests are older than {@code beforeDay} (they get a fresh set when they come back). */
    void prune(long beforeDay) {
        if (players.values().removeIf(q -> q.day != Long.MIN_VALUE && q.day < beforeDay)) dirty = true;
    }
}
