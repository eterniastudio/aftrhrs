package net.axther.aftrhrs.feature.chatgames;

import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Yaml;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Chat game stats in {@code data/chatgames.yml}: per player wins (all time and this week), the fastest win, the current
 * and best win streak, when their last rewarded wins were (for the hourly cap), plus who turned chat games off and who
 * won last. Changed on the server thread; snapshots are written in the background through {@link SafeYaml} (temp file,
 * atomic move, {@code .bak}). A file that can't be read is never saved over.
 */
final class ChatGamesStore {
    static final long NEVER = Long.MIN_VALUE;
    static final long HOUR_MS = 3_600_000L;
    private static final String HEADER = "# Feather chat games: wins, fastest wins (ms), streaks, this week's wins and the\n"
            + "# rewarded wins of the last hour (epoch ms), plus who turned chat games off.\n\n";

    static final class Rec {
        final UUID id;
        String name;
        int wins;
        /** Fastest win in ms, 0 = none yet. */
        long fastestMs;
        int streak;
        int bestStreak;
        /** The Monday (epoch day) {@link #weekWins} belongs to. */
        long week = NEVER;
        int weekWins;
        /** When the rewarded wins of the last hour were (epoch ms), oldest first. */
        final Deque<Long> rewarded = new ArrayDeque<>();

        Rec(UUID id) {
            this.id = id;
        }

        int weekWins(long thisWeek) {
            return week == thisWeek ? weekWins : 0;
        }

        /** Rewarded wins in the hour before {@code now} (older ones are dropped). */
        int rewardedInHour(long now) {
            while (!rewarded.isEmpty() && rewarded.peekFirst() <= now - HOUR_MS) rewarded.pollFirst();
            // A clock that went backwards: keep the entries, they still count.
            return rewarded.size();
        }
    }

    private final Path file;
    private final Logger log;
    final Map<UUID, Rec> recs = new HashMap<>();
    /** Players who turned chat games off (read from the async chat thread too). */
    final Set<UUID> off = ConcurrentHashMap.newKeySet();
    UUID lastWinner;
    boolean unreadable;
    boolean dirty;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AftrHrs-ChatGames-IO");
        t.setDaemon(true);
        return t;
    });
    private final Object writeLock = new Object();
    private long snapshotSeq;
    private long writtenSeq;

    ChatGamesStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    Rec rec(UUID id, String name) {
        Rec r = recs.computeIfAbsent(id, Rec::new);
        if (name != null) r.name = name;
        return r;
    }

    /** Top {@code n} players by all-time wins (ties: faster first). */
    List<Rec> topAllTime(int n) {
        List<Rec> list = new ArrayList<>();
        for (Rec r : recs.values()) if (r.wins > 0) list.add(r);
        list.sort(Comparator.comparingInt((Rec r) -> -r.wins).thenComparingLong(r -> r.fastestMs <= 0 ? Long.MAX_VALUE : r.fastestMs));
        return list.size() > n ? list.subList(0, n) : list;
    }

    /** Top {@code n} players by wins this week. */
    List<Rec> topWeek(int n, long week) {
        List<Rec> list = new ArrayList<>();
        for (Rec r : recs.values()) if (r.weekWins(week) > 0) list.add(r);
        list.sort(Comparator.comparingInt((Rec r) -> -r.weekWins(week)).thenComparingInt(r -> -r.wins));
        return list.size() > n ? list.subList(0, n) : list;
    }

    // ------------------------------------------------------------------ disk

    void load() {
        Yaml yml = SafeYaml.read(file, log, "chat game stats");
        if (yml == null) {
            unreadable = true;
            return;
        }
        unreadable = false;
        recs.clear();
        off.clear();
        lastWinner = null;
        Yaml ps = yml.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                Yaml s = ps.getConfigurationSection(key);
                UUID id = uuid(key);
                if (s == null || id == null) {
                    log.warn("chatgames.yml: skipping {} (not a UUID).", key);
                    continue;
                }
                Rec r = new Rec(id);
                r.name = s.getString("name");
                r.wins = Math.max(0, s.getInt("wins"));
                r.fastestMs = Math.max(0, s.getLong("fastest-ms"));
                r.streak = Math.max(0, s.getInt("streak"));
                r.bestStreak = Math.max(r.streak, s.getInt("best-streak"));
                r.week = s.contains("week") ? s.getLong("week") : NEVER;
                r.weekWins = Math.max(0, s.getInt("week-wins"));
                for (Object o : s.getList("rewarded", List.of())) if (o instanceof Number n) r.rewarded.addLast(n.longValue());
                recs.put(id, r);
            }
        }
        for (String s : yml.getStringList("off")) {
            UUID id = uuid(s);
            if (id != null) off.add(id);
        }
        lastWinner = uuid(yml.getString("last-winner", ""));
        dirty = false;
    }

    private static UUID uuid(String s) {
        try {
            return s == null || s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The data as plain maps (server thread, nothing shared with the records), or null while the file is unreadable. */
    private Object snapshot() {
        if (unreadable) return null;
        Yaml yml = new Yaml();
        long now = System.currentTimeMillis();
        for (Rec r : recs.values()) {
            String b = "players." + r.id + ".";
            yml.set(b + "name", r.name);
            yml.set(b + "wins", r.wins);
            if (r.fastestMs > 0) yml.set(b + "fastest-ms", r.fastestMs);
            yml.set(b + "streak", r.streak);
            yml.set(b + "best-streak", r.bestStreak);
            if (r.week != NEVER) {
                yml.set(b + "week", r.week);
                yml.set(b + "week-wins", r.weekWins);
            }
            r.rewardedInHour(now);
            if (!r.rewarded.isEmpty()) yml.set(b + "rewarded", new ArrayList<>(r.rewarded));
        }
        if (!off.isEmpty()) yml.set("off", off.stream().map(UUID::toString).sorted().toList());
        if (lastWinner != null) yml.set("last-winner", lastWinner.toString());
        snapshotSeq++;
        Object snap = yml.snapshot();
        return snap != null ? snap : new java.util.LinkedHashMap<String, Object>();
    }

    /** Dumps and writes a snapshot unless a newer one is already on disk (any thread). */
    private boolean write(Object snap, long seq) {
        synchronized (writeLock) {
            if (seq <= writtenSeq) return true;
            boolean ok = SafeYaml.write(file, HEADER + Yaml.dump(snap), log);
            if (ok) writtenSeq = seq;
            return ok;
        }
    }

    /** Snapshot now, dump and write in the background. */
    void save() {
        Object data = snapshot();
        if (data == null) return;
        long seq = snapshotSeq;
        dirty = false;
        try {
            io.execute(() -> write(data, seq));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            write(data, seq);
        }
    }

    /** Shutdown: waits for queued writes, then writes the last state. */
    void close() {
        Object data = snapshot();
        long seq = snapshotSeq;
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (data != null) write(data, seq);
    }
}
