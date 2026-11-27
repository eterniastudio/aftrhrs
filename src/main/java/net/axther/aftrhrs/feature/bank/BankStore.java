package net.axther.aftrhrs.feature.bank;

import net.axther.aftrhrs.core.DataAlerts;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything the bank remembers, in {@code data/bank.yml} (same format as the Paper plugin): accounts (balance,
 * pending coins, history), the bank chests (world name + block position), the vault reserve, the last interest day and
 * the robbery cooldowns. Only touched on the server thread.
 *
 * <p>A change marks the bank dirty and one snapshot of plain fields is copied on the server thread at the end of that
 * tick ({@link #saveNow} copies it at once, for steps that also write the player's inventory file). One background
 * thread turns the newest snapshot into YAML (older ones still waiting are skipped) and writes it: the old file is
 * copied to {@code bank.yml.bak}, the new one is written to {@code bank.yml.tmp} and moved over. A file that can't be
 * parsed is never overwritten: the bank closes until it is fixed. Every money change is also appended to
 * {@code data/bank-log.txt}.
 */
final class BankStore {
    /** A block position (a bank chest). {@code world} is the Paper-style world name ("world", "world_nether", ...). */
    record Pos(String world, int x, int y, int z) {
        /** A bank screen opened with /bank away from any bank chest. */
        static final Pos REMOTE = new Pos("", 0, Integer.MIN_VALUE, 0);

        static Pos of(ServerLevel level, BlockPos pos) {
            return new Pos(Worlds.name(level), pos.getX(), pos.getY(), pos.getZ());
        }

        ServerLevel level() {
            return this == REMOTE || y == Integer.MIN_VALUE ? null : Worlds.resolve(world);
        }

        BlockPos blockPos() {
            return new BlockPos(x, y, z);
        }

        /** The block center, or null when the world isn't loaded. */
        Loc center() {
            ServerLevel l = level();
            return l == null ? null : new Loc(l, x + 0.5, y + 0.5, z + 0.5);
        }

        String pretty() {
            return x + ", " + y + ", " + z + " (" + world + ")";
        }
    }

    /** One history line. {@code amount} is signed from the player's point of view (+ received, - paid). */
    record Tx(long time, String type, long amount, long balance, String note) {
    }

    static final class Account {
        final UUID id;
        String name;
        long balance;
        /** Coins owed to the player that didn't fit in their inventory (handed out next time). */
        long pending;
        long lastSeen;
        /** Interest paid while offline, told on the next join. */
        long awayInterest;
        final ArrayDeque<Tx> history = new ArrayDeque<>();

        Account(UUID id) {
            this.id = id;
        }
    }

    private final Logger log;
    private final Path file;
    private final Path bak;
    private final Path logFile;
    private ExecutorService io = newIo();

    final Map<UUID, Account> accounts = new HashMap<>();
    final Set<Pos> banks = new LinkedHashSet<>();
    long reserve;
    long reserveRefilledAt;
    /** The last day (epoch day in the interest time zone) whose interest was paid; MIN_VALUE before the first run. */
    long interestDay = Long.MIN_VALUE;
    long lastRobbery;
    /** Player -> when they may rob again (epoch ms). */
    final Map<UUID, Long> robberyCooldowns = new HashMap<>();
    /** Lifetime totals, for /bank info. */
    long robberies;
    long robberiesWon;
    long stolen;
    boolean unreadable;
    int historySize = 20;

    BankStore(Feather plugin) {
        this.log = plugin.getLogger();
        Path data = plugin.getDataFolder().resolve("data");
        this.file = data.resolve("bank.yml");
        this.bak = data.resolve("bank.yml.bak");
        this.logFile = data.resolve("bank-log.txt");
    }

    private static ExecutorService newIo() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Feather-Bank-IO");
            t.setDaemon(true);
            return t;
        });
    }

    Account account(UUID id) {
        return accounts.computeIfAbsent(id, Account::new);
    }

    Account find(UUID id) {
        return accounts.get(id);
    }

    /**
     * Told about every history line right after {@link #record} adds it (server thread), with the account as it is now;
     * the Companion's coin purse ({@link WalletCompanion}) listens. Null when nobody does.
     */
    java.util.function.BiConsumer<Account, Tx> onRecord;

    void record(Account a, String type, long amount, String note) {
        Tx tx = new Tx(System.currentTimeMillis(), type, amount, a.balance, note == null ? "" : note);
        a.history.addFirst(tx);
        while (a.history.size() > Math.max(1, historySize)) a.history.removeLast();
        java.util.function.BiConsumer<Account, Tx> hook = onRecord;
        if (hook != null) {
            try {
                hook.accept(a, tx);
            } catch (RuntimeException e) {
                // A listener must never break a money step that already happened.
                log.warn("Bank history listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ disk

    void load(long startReserve) {
        if (io.isShutdown()) io = newIo();
        accounts.clear();
        banks.clear();
        robberyCooldowns.clear();
        unreadable = false;
        dirty = false;
        flushScheduled = false;
        latest.set(null);
        reserve = startReserve;
        reserveRefilledAt = System.currentTimeMillis();
        interestDay = Long.MIN_VALUE;
        lastRobbery = 0;
        robberies = robberiesWon = stolen = 0;
        if (!Files.exists(file)) {
            DataAlerts.fixed(file);
            return;
        }
        Yaml yml = new Yaml();
        try {
            yml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            unreadable = true;
            log.error("Could not read {}: {}. The bank is CLOSED (no balances change, nothing is saved) until the file is fixed "
                    + "(a copy of the previous save is in bank.yml.bak) and the server restarts.", file, e.toString());
            DataAlerts.broken(file, "bank balances");
            return;
        }
        DataAlerts.fixed(file);
        reserve = Math.max(0, yml.getLong("reserve", startReserve));
        reserveRefilledAt = yml.getLong("reserve-refilled-at", System.currentTimeMillis());
        interestDay = yml.isSet("interest-day") ? yml.getLong("interest-day") : Long.MIN_VALUE;
        lastRobbery = yml.getLong("last-robbery", 0L);
        robberies = yml.getLong("stats.robberies", 0L);
        robberiesWon = yml.getLong("stats.robberies-won", 0L);
        stolen = yml.getLong("stats.stolen", 0L);
        for (Map<?, ?> m : yml.getMapList("banks")) {
            try {
                banks.add(new Pos(String.valueOf(m.get("world")), num(m.get("x")), num(m.get("y")), num(m.get("z"))));
            } catch (RuntimeException e) {
                log.warn("bank.yml: skipping a broken bank entry {}", m);
            }
        }
        Yaml cd = yml.getConfigurationSection("robbery-cooldowns");
        if (cd != null) {
            for (String k : cd.getKeys(false)) {
                try {
                    robberyCooldowns.put(UUID.fromString(k), cd.getLong(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        Yaml acc = yml.getConfigurationSection("accounts");
        if (acc != null) {
            for (String k : acc.getKeys(false)) {
                Yaml s = acc.getConfigurationSection(k);
                if (s == null) continue;
                UUID id;
                try {
                    id = UUID.fromString(k);
                } catch (IllegalArgumentException e) {
                    log.warn("bank.yml: skipping account with a bad id {}", k);
                    continue;
                }
                Account a = new Account(id);
                a.name = s.getString("name");
                a.balance = s.getLong("balance", 0L);
                a.pending = s.getLong("pending", 0L);
                if (a.balance < 0 || a.pending < 0) {
                    log.warn("bank.yml: account {} had a negative amount; set to 0.", k);
                    a.balance = Math.max(0, a.balance);
                    a.pending = Math.max(0, a.pending);
                }
                a.lastSeen = s.getLong("last-seen", 0L);
                a.awayInterest = Math.max(0, s.getLong("away-interest", 0L));
                for (String line : s.getStringList("history")) {
                    String[] p = line.split("\\|", 5);
                    if (p.length < 4) continue;
                    try {
                        a.history.addLast(new Tx(Long.parseLong(p[0]), p[1], Long.parseLong(p[2]), Long.parseLong(p[3]), p.length > 4 ? p[4] : ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
                accounts.put(id, a);
            }
        }
        log.info("Bank: {} account(s), {} bank chest(s), vault reserve {}.", accounts.size(), banks.size(), reserve);
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    /** One account as it was when the snapshot was taken (server thread); rendered on the IO thread. */
    record AccSnap(UUID id, String name, long balance, long pending, long lastSeen, long awayInterest, Tx[] history) {
    }

    /** Everything bank.yml holds, copied on the server thread: plain fields and arrays, no text built yet. */
    record Snap(long reserve, long reserveRefilledAt, long interestDay, long lastRobbery, long robberies, long robberiesWon,
                long stolen, Pos[] banks, Map<UUID, Long> cooldowns, AccSnap[] accounts) {
    }

    /** Changed since the last snapshot (server thread). */
    private boolean dirty;
    private boolean flushScheduled;
    /** The newest snapshot not written yet: the IO thread only ever renders the latest one. */
    private final AtomicReference<Snap> latest = new AtomicReference<>();
    private final AtomicBoolean writerQueued = new AtomicBoolean();
    /** How often bank.yml was rendered and written in this JVM, and how many snapshots were taken (game tests count them). */
    final AtomicInteger writes = new AtomicInteger();
    int snapshots;

    /**
     * Marks the bank changed. One snapshot is taken at the end of the tick for every change made in it (a thousand
     * credits in one tick are one write). Never while the file was unreadable.
     */
    void save() {
        if (unreadable) return;
        dirty = true;
        if (!flushScheduled) {
            flushScheduled = true;
            Tasks.later(1, this::flush);
        }
    }

    /**
     * Snapshots now (still written in the background): for money steps that write the player's inventory file in the
     * same step, so a crash can't keep the coins in one file without the other.
     */
    void saveNow() {
        if (unreadable) return;
        dirty = true;
        flush();
    }

    /** Takes the snapshot of pending changes, if any (a flush left over from before a reload finds nothing to do). */
    void flush() {
        flushScheduled = false;
        if (!dirty || unreadable) return;
        dirty = false;
        snapshots++;
        enqueue(snapshot());
    }

    /** Copies every field the file holds (server thread). */
    Snap snapshot() {
        long now = System.currentTimeMillis();
        Map<UUID, Long> cd = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> e : robberyCooldowns.entrySet()) if (e.getValue() > now) cd.put(e.getKey(), e.getValue());
        AccSnap[] accs = new AccSnap[accounts.size()];
        int i = 0;
        for (Account a : accounts.values()) {
            accs[i++] = new AccSnap(a.id, a.name, a.balance, a.pending, a.lastSeen, a.awayInterest, a.history.toArray(Tx[]::new));
        }
        return new Snap(reserve, reserveRefilledAt, interestDay, lastRobbery, robberies, robberiesWon, stolen,
                banks.toArray(Pos[]::new), cd, accs);
    }

    /** The bank.yml text of a snapshot (IO thread; the format the server thread used to write). */
    static String render(Snap s) {
        Yaml yml = new Yaml();
        yml.set("reserve", s.reserve());
        yml.set("reserve-refilled-at", s.reserveRefilledAt());
        if (s.interestDay() != Long.MIN_VALUE) yml.set("interest-day", s.interestDay());
        yml.set("last-robbery", s.lastRobbery());
        yml.set("stats.robberies", s.robberies());
        yml.set("stats.robberies-won", s.robberiesWon());
        yml.set("stats.stolen", s.stolen());
        List<Map<String, Object>> bl = new ArrayList<>();
        for (Pos p : s.banks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", p.world());
            m.put("x", p.x());
            m.put("y", p.y());
            m.put("z", p.z());
            bl.add(m);
        }
        yml.set("banks", bl);
        for (Map.Entry<UUID, Long> e : s.cooldowns().entrySet()) yml.set("robbery-cooldowns." + e.getKey(), e.getValue());
        for (AccSnap a : s.accounts()) {
            String base = "accounts." + a.id() + ".";
            if (a.name() != null) yml.set(base + "name", a.name());
            yml.set(base + "balance", a.balance());
            if (a.pending() != 0) yml.set(base + "pending", a.pending());
            yml.set(base + "last-seen", a.lastSeen());
            if (a.awayInterest() != 0) yml.set(base + "away-interest", a.awayInterest());
            List<String> h = new ArrayList<>(a.history().length);
            for (Tx t : a.history()) h.add(t.time() + "|" + t.type() + "|" + t.amount() + "|" + t.balance() + "|" + t.note().replace('\n', ' '));
            yml.set(base + "history", h);
        }
        return "# Feather bank. Balances are in Goob Coins. Don't edit while the server runs;\n"
                + "# a parse error closes the bank until the file is fixed (bank.yml.bak holds the previous save).\n"
                + yml.saveToString();
    }

    /** Hands a snapshot to the IO thread; one still waiting there is replaced by it (latest wins). */
    private void enqueue(Snap s) {
        latest.set(s);
        if (writerQueued.compareAndSet(false, true)) submit(this::drain);
    }

    private void drain() {
        // Clear the flag first: a snapshot set after this line queues another drain, so none is left unwritten.
        writerQueued.set(false);
        Snap s = latest.getAndSet(null);
        if (s == null) return;
        writes.incrementAndGet();
        write(render(s));
    }

    private void write(String data) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            // bank.yml.tmp moved over the file; retried briefly while a reader has it open (Windows refuses the move then).
            Yaml.SafeFiles.writeAtomic(file, data);
        } catch (IOException e) {
            log.error("Could not save {}: {}", file, e.toString());
        }
    }

    /** Appends one line to data/bank-log.txt (in the background, in order with the saves). */
    void log(String line) {
        String entry = Instant.now() + " " + line.replace('\n', ' ') + System.lineSeparator();
        submit(() -> {
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Could not write {}: {}", logFile, e.toString());
            }
        });
    }

    private void submit(Runnable r) {
        try {
            io.execute(r);
        } catch (RejectedExecutionException e) {
            r.run();
        }
    }

    void shutdown() {
        flush();
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) log.warn("Saving bank.yml timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
