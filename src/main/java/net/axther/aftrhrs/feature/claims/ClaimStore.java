package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@code data/claims.yml}: every claim and every player's claim-chunk account. Changes are saved a moment later on a
 * background thread (a snapshot is taken on the server thread first) and synchronously on shutdown. A file that can't
 * be read is never written over: claims stay off for changes until it is fixed and Feather is reloaded.
 */
final class ClaimStore {
    /** A player's claim chunks: active minutes played (chunks accrue from these) and the staff bonus in chunks (may be negative). */
    static final class Budget {
        String name = "";
        long minutes;
        long bonus;
    }

    private final Feather plugin;
    private final Logger log;
    private final Path file;
    final ClaimRegistry registry = new ClaimRegistry();
    final Map<UUID, Budget> budgets = new HashMap<>();
    boolean unreadable;
    private boolean dirty;
    private Tasks.Task pending;
    private ExecutorService io;

    ClaimStore(Feather plugin, Path file) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.file = file;
    }

    Budget budget(UUID id) {
        return budgets.computeIfAbsent(id, k -> new Budget());
    }

    void load() {
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Feather-Claims-IO");
            t.setDaemon(true);
            return t;
        });
        registry.clear();
        budgets.clear();
        unreadable = false;
        Yaml yml = SafeYaml.read(file, log, "land claims");
        if (yml == null) {
            unreadable = true;
            log.error("LAND CLAIMS ARE NOT LOADED: data/claims.yml could not be read. Nothing is protected and claim changes are "
                    + "refused until the file is fixed and Feather is reloaded. The file was left untouched.");
            return;
        }
        int next = Math.max(1, yml.getInt("next-id", 1));
        Yaml cs = yml.getConfigurationSection("claims");
        List<Claim> loaded = new ArrayList<>();
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                Yaml s = cs.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    loaded.add(read(Integer.parseInt(key), s));
                } catch (RuntimeException e) {
                    // Keep the raw entry safe: refuse saving rather than dropping a claim we couldn't read.
                    unreadable = true;
                    log.error("data/claims.yml: claim {} can't be read ({}). Claim changes are not saved until it is fixed and Feather is reloaded.",
                            key, e.getMessage());
                }
            }
        }
        // One index publish for the whole file instead of one copy per claim.
        registry.bulk(() -> {
            for (Claim c : loaded) {
                boolean clash = false;
                for (Area a : c.areas) {
                    if (!registry.overlapping(c.world, a, c).isEmpty()) {
                        clash = true;
                        break;
                    }
                }
                if (clash) log.warn("data/claims.yml: claim {} ({}) overlaps another claim; loaded anyway, the older one wins there.", c.id, c.name);
                registry.add(c);
            }
        });
        registry.nextId = Math.max(registry.nextId, next);
        Yaml ps = yml.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    Budget b = budget(id);
                    b.name = ps.getString(key + ".name", "");
                    b.minutes = ps.getLong(key + ".minutes", 0);
                    if (ps.contains(key + ".bonus-chunks")) {
                        b.bonus = ps.getLong(key + ".bonus-chunks", 0);
                    } else if (ps.contains(key + ".bonus")) {
                        // Before 1.2.1 the bonus was in claim blocks (256 to a chunk).
                        b.bonus = Math.round(ps.getLong(key + ".bonus", 0) / 256.0);
                        dirty = true;
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("data/claims.yml: skipping player entry {}", key);
                }
            }
        }
        log.info("Loaded {} land claim(s).", registry.byId.size());
    }

    private static Claim read(int id, Yaml s) {
        String ownerRaw = s.getString("owner", "");
        UUID owner = ownerRaw == null || ownerRaw.isBlank() || ownerRaw.equals("admin") ? null : UUID.fromString(ownerRaw);
        String world = s.getString("world");
        if (world == null || world.isBlank()) throw new IllegalArgumentException("no world");
        Claim c = new Claim(id, owner, s.getString("owner-name", ""), s.getString("name", "Claim " + id), world);
        c.created = s.getLong("created", System.currentTimeMillis());
        List<Area> areas = new ArrayList<>();
        for (String a : s.getStringList("areas")) areas.add(Area.parse(a));
        if (areas.isEmpty()) throw new IllegalArgumentException("no areas");
        c.setAreas(areas);
        Yaml t = s.getConfigurationSection("trust");
        if (t != null) {
            for (String k : t.getKeys(false)) {
                UUID u = UUID.fromString(k);
                TrustLevel lv = TrustLevel.parse(t.getString(k + ".level"));
                if (lv == null) continue;
                c.trust.put(u, lv);
                c.trustNames.put(u, t.getString(k + ".name", k.substring(0, 8)));
            }
        }
        c.publicTrust = TrustLevel.parse(s.getString("public", "none"));
        Yaml f = s.getConfigurationSection("flags");
        if (f != null) {
            for (String k : f.getKeys(false)) {
                ClaimFlag flag = ClaimFlag.parse(k);
                if (flag != null) c.flags.put(flag, f.getBoolean(k));
            }
        }
        c.allowUse.addAll(s.getStringList("allow-use"));
        c.allowBreak.addAll(s.getStringList("allow-break"));
        c.greeting = s.getString("greeting", "");
        return c;
    }

    // ------------------------------------------------------------------ saving

    /** Something changed: save in a moment (several changes in a row are written once). */
    void markDirty() {
        dirty = true;
        if (pending != null || unreadable) return;
        if (!plugin.isEnabled()) {
            saveNow();
            return;
        }
        pending = Tasks.later(40L, () -> {
            pending = null;
            save(false);
        });
    }

    void saveNow() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        save(true);
    }

    private void save(boolean sync) {
        if (unreadable) {
            if (dirty) log.error("Not saving land claims: data/claims.yml could not be read earlier (fix it and /feather reload).");
            return;
        }
        if (!dirty) return;
        dirty = false;
        // The snapshot (plain maps, nothing shared with the live claims) on this thread; dumping and writing on the IO thread.
        Map<String, Object> snap = snapshot();
        Runnable job = () -> {
            try {
                SafeYaml.write(file, HEADER + Yaml.dump(snap), log);
            } catch (RuntimeException e) {
                log.error("Could not save {}: {}", file, e.toString());
            }
        };
        if (io == null || io.isShutdown()) {
            job.run();
        } else if (sync) {
            // Through the same queue as the background writes, so an older snapshot still waiting there can't land
            // after (and over) this newer one.
            try {
                io.submit(job).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.run();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.RejectedExecutionException e) {
                job.run();
            }
        } else {
            io.execute(job);
        }
    }

    private static final String HEADER = "# Feather land claims. Edit only while the server is stopped (or /feather reload right after).\n"
            + "# areas: chunk:<chunkX>:<chunkZ>, rect:<minX>:<minZ>:<maxX>:<maxZ> (block corners) or circle:<x>:<z>:<radius>; full height.\n";

    /**
     * The whole file as plain nested maps, in the order (and with the same keys) the old Yaml.set path wrote: a null
     * value means no key, an empty section is left out.
     */
    private Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("next-id", registry.nextId);
        Map<String, Object> claims = null;
        for (Claim c : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", c.owner == null ? "admin" : c.owner.toString());
            m.put("owner-name", c.ownerName == null ? "" : c.ownerName);
            put(m, "name", c.name);
            put(m, "world", c.world);
            m.put("created", c.created);
            m.put("areas", new ArrayList<>(c.areas.stream().map(Area::serialize).toList()));
            if (!c.trust.isEmpty()) {
                Map<String, Object> trust = new LinkedHashMap<>();
                for (Map.Entry<UUID, TrustLevel> e : c.trust.entrySet()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    put(t, "level", e.getValue().key());
                    put(t, "name", c.trustNames.getOrDefault(e.getKey(), ""));
                    trust.put(e.getKey().toString(), t);
                }
                m.put("trust", trust);
            }
            m.put("public", c.publicTrust == null ? "none" : c.publicTrust.key());
            if (!c.flags.isEmpty()) {
                Map<String, Object> flags = new LinkedHashMap<>();
                for (Map.Entry<ClaimFlag, Boolean> e : c.flags.entrySet()) put(flags, e.getKey().key(), e.getValue());
                if (!flags.isEmpty()) m.put("flags", flags);
            }
            if (c.admin()) {
                m.put("allow-use", new ArrayList<>(c.allowUse));
                m.put("allow-break", new ArrayList<>(c.allowBreak));
                if (c.greeting != null && !c.greeting.isEmpty()) m.put("greeting", c.greeting);
            }
            if (claims == null) claims = new LinkedHashMap<>();
            claims.put(String.valueOf(c.id), m);
        }
        if (claims != null) root.put("claims", claims);
        Map<String, Object> players = null;
        for (Map.Entry<UUID, Budget> e : budgets.entrySet()) {
            Budget b = e.getValue();
            if (b.minutes == 0 && b.bonus == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            put(m, "name", b.name);
            m.put("minutes", b.minutes);
            m.put("bonus-chunks", b.bonus);
            if (players == null) players = new LinkedHashMap<>();
            players.put(e.getKey().toString(), m);
        }
        if (players != null) root.put("players", players);
        return root;
    }

    /** Like Yaml.set: a null value writes no key. */
    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }

    /** Waits for background writes, then writes what is left on this thread. */
    void close() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(10, TimeUnit.SECONDS)) log.warn("Saving data/claims.yml timed out.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        save(true);
    }
}
