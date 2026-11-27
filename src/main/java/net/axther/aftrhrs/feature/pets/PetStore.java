package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Legacy;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Yaml;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every pet on the server, in {@code aftrhrs/data/pets.yml} (the Paper server's {@code plugins/Feather/data/pets.yml},
 * same format plus a {@code journal-seq} key). Kept in memory; changes mark the store dirty and a timer (and shutdown)
 * writes it: plain maps are copied on the server thread, then dumped and written on the store's own thread.
 * <p>
 * Changes that must survive a crash right away (stable in/out, a birth, a death, a release, a work payout) are not a
 * full save: each appends one line to {@code data/pets.journal} ({@link #journal}). Every line carries a sequence
 * number; pets.yml records the highest one it holds, so a load replays only the newer lines, and a finished full save
 * empties the journal when nothing newer was appended meanwhile.
 */
final class PetStore {
    private final Feather feather;
    private final Path file;
    private final Map<UUID, Pet> pets = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> byEntity = new ConcurrentHashMap<>();
    /** Owner -> their pets by id (kept with {@link #pets}), so per-owner lookups don't scan every pet on the server. */
    private final Map<UUID, Map<UUID, Pet>> byOwner = new ConcurrentHashMap<>();
    /** Released record ids (with when): an entity of theirs that loads later goes wild instead of being adopted. */
    private final Map<UUID, Long> released = new ConcurrentHashMap<>();
    /**
     * Pets whose entity may be loaded, by record id: every living record whose entity loaded or spawned since, minus
     * those found gone (the pets tick prunes it). A superset, so the per-second timers don't scan every record.
     */
    private final Map<UUID, Pet> loaded = new ConcurrentHashMap<>();
    /** Old entity -> the pet it was replaced by ({@link Pet#replaced}), for {@link #replacedOwner}. */
    private final Map<UUID, Pet> replacedIndex = new ConcurrentHashMap<>();
    private static final long RELEASED_KEEP_MS = 180L * 24 * 3_600_000L;
    private static final String HEADER = "# Feather pets. Edit only while the server is stopped.\n";
    /**
     * pets.yml's own writer. Not the shared data thread: stores there save synchronously on the server thread and
     * would wait behind this multi-megabyte dump. One thread keeps this file's writes in order.
     */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AftrHrs-Pets");
        t.setDaemon(true);
        return t;
    });
    private final Path journalFile;
    /** Held for one journal append or truncate only (never while dumping). */
    private final Object journalLock = new Object();
    /** The last journal sequence number handed out (server thread; the writer reads it before it truncates). */
    private final AtomicLong seq = new AtomicLong();
    /** Full saves handed to the writer and not finished yet. */
    private final AtomicInteger inFlight = new AtomicInteger();
    /** The journal ends in a torn line (a crash mid-append): the next append starts a new line first. Under journalLock. */
    private boolean journalTorn;
    private volatile boolean dirty;
    /** The last full save failed: the next timer tick tries again even without new changes. */
    private volatile boolean failed;
    private boolean readOnly;

    PetStore(Feather feather) {
        this(feather, feather.dataFile("pets.yml"), feather.dataFile("pets.journal"));
    }

    /** A store on other files (game tests). */
    PetStore(Feather feather, Path file, Path journalFile) {
        this.feather = feather;
        this.file = file;
        this.journalFile = journalFile;
    }

    // ------------------------------------------------------------------ lookups

    Pet get(UUID id) {
        return id == null ? null : pets.get(id);
    }

    Pet byEntity(UUID entity) {
        UUID id = entity == null ? null : byEntity.get(entity);
        return id == null ? null : pets.get(id);
    }

    Collection<Pet> all() {
        return pets.values();
    }

    /** Pets whose entity may be loaded (a live view, safe to change while iterating; check each with its entity). */
    Collection<Pet> loaded() {
        return loaded.values();
    }

    /** Notes that a living record's entity is loaded (it loaded, spawned or moved worlds). */
    void markLoaded(Pet p) {
        if (p.alive() && p.entity != null && pets.get(p.id) == p) loaded.put(p.id, p);
    }

    /** The record's entity is gone (unloaded, dead, stabled). */
    void unmarkLoaded(Pet p) {
        loaded.remove(p.id, p);
    }

    /** A player's pets: living first, then by creation. A fresh list the caller may change. */
    List<Pet> of(UUID owner) {
        List<Pet> out = new ArrayList<>(ofUnsorted(owner));
        out.sort(Comparator.comparing((Pet p) -> !p.alive()).thenComparingLong(p -> p.created));
        return out;
    }

    /** A player's pets in no order (a live view: don't change it, copy it to keep it). */
    Collection<Pet> ofUnsorted(UUID owner) {
        Map<UUID, Pet> mine = owner == null ? null : byOwner.get(owner);
        return mine == null ? List.of() : mine.values();
    }

    private void index(Pet pet) {
        byOwner.computeIfAbsent(pet.owner, k -> new ConcurrentHashMap<>()).put(pet.id, pet);
    }

    private void unindex(Pet pet) {
        byOwner.computeIfPresent(pet.owner, (k, mine) -> {
            mine.remove(pet.id, pet);
            return mine.isEmpty() ? null : mine;
        });
    }

    boolean isReleased(UUID id) {
        return id != null && released.containsKey(id);
    }

    /** True when data/pets.yml couldn't be read: pets aren't loaded and nothing is written. */
    boolean unreadable() {
        return readOnly;
    }

    /** The pet an old (replaced or stabled) entity belonged to, or null. */
    Pet replacedOwner(UUID entity) {
        Pet p = entity == null ? null : replacedIndex.get(entity);
        return p != null && pets.get(p.id) == p && p.replaced.contains(entity) ? p : null;
    }

    /** {@link Pet#addReplaced} through the store, which keeps {@link #replacedOwner}'s index. */
    void addReplaced(Pet p, UUID old) {
        if (old == null || p.replaced.contains(old)) return;
        UUID evicted = p.replaced.size() >= 16 ? p.replaced.get(0) : null;   // Pet keeps the newest 16
        p.addReplaced(old);
        if (pets.get(p.id) != p) return;
        replacedIndex.put(old, p);
        if (evicted != null && !p.replaced.contains(evicted)) replacedIndex.remove(evicted, p);
    }

    private void indexReplaced(Pet pet) {
        for (UUID u : pet.replaced) replacedIndex.put(u, pet);
    }

    private void unindexReplaced(Pet pet) {
        for (UUID u : pet.replaced) replacedIndex.remove(u, pet);
    }

    // ------------------------------------------------------------------ changes

    void add(Pet pet) {
        Pet old = pets.put(pet.id, pet);
        if (old != null && old != pet) {
            unindex(old);
            unindexReplaced(old);
        }
        index(pet);
        indexReplaced(pet);
        if (pet.entity != null) byEntity.put(pet.entity, pet.id);
        released.remove(pet.id);
        loaded.remove(pet.id);
        markLoaded(pet);
        dirty = true;
    }

    void setEntity(Pet pet, UUID entity) {
        if (pet.entity != null) byEntity.remove(pet.entity, pet.id);
        pet.entity = entity;
        if (entity != null) {
            byEntity.put(entity, pet.id);
            markLoaded(pet);
        } else {
            unmarkLoaded(pet);
        }
        dirty = true;
    }

    /** Forgets the pet. {@code leftovers}: an entity of it may still be out there unloaded; it goes wild when it loads. */
    void release(Pet pet, boolean leftovers) {
        forget(pet);
        if (leftovers) released.put(pet.id, System.currentTimeMillis());
        dirty = true;
    }

    /** Drops a record that never really existed (a bred baby whose spawn failed). */
    void remove(Pet pet) {
        forget(pet);
        dirty = true;
    }

    private void forget(Pet pet) {
        Pet had = pets.remove(pet.id);
        unindex(pet);
        unindexReplaced(pet);
        if (had != null && had != pet) {
            unindex(had);
            unindexReplaced(had);
        }
        if (pet.entity != null) byEntity.remove(pet.entity, pet.id);
        loaded.remove(pet.id);
    }

    void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ disk

    void load() {
        pets.clear();
        byEntity.clear();
        byOwner.clear();
        released.clear();
        loaded.clear();   // filled by the module as entities load (scanLoaded, ENTITY_LOAD)
        replacedIndex.clear();
        readOnly = false;
        dirty = false;
        failed = false;
        Yaml yml = SafeYaml.read(file, feather.getLogger(), "pets");
        if (yml == null) {
            feather.getLogger().error("Could not read {} (pets are not loaded, and nothing is written over it).", file);
            readOnly = true;   // the journal is neither replayed nor written: it belongs to the unreadable file
            return;
        }
        long now = System.currentTimeMillis();
        for (String s : yml.getStringList("released")) {
            // "uuid epoch-ms"; files from before only have the uuid (then the clock starts now)
            String[] parts = s.trim().split("\\s+");
            UUID id = uuid(parts[0]);
            if (id == null) continue;
            long at = now;
            if (parts.length > 1) {
                try {
                    at = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (now - at < RELEASED_KEEP_MS) released.put(id, at);
        }
        Yaml sec = yml.getConfigurationSection("pets");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Pet p = readPet(uuid(key), sec.getConfigurationSection(key));
                if (p == null) {
                    feather.getLogger().warn("Skipping a broken pet entry '{}' in data/pets.yml.", key);
                    continue;
                }
                pets.put(p.id, p);
            }
        }
        long fileSeq = Math.max(0, yml.getLong("journal-seq", 0));
        if (!yml.isSet("journal-seq") && journalOlderThanFile()) {
            // pets.yml was written after the journal by a version that doesn't know it (a downgrade, then this one
            // again): the journal's lines are older than the records, so replaying them would undo newer changes
            Path aside = setJournalAside("stale");
            feather.getLogger().warn("data/pets.journal is older than data/pets.yml (saved by a version without the journal): not replayed{}.",
                    aside != null ? ", kept as " + aside.getFileName() : "");
        }
        long last = replay(fileSeq, now);
        seq.set(Math.max(fileSeq, last));
        for (Pet p : pets.values()) {
            if (p.entity != null) byEntity.put(p.entity, p.id);
            index(p);
            indexReplaced(p);
        }
        // Not dirty after a replay: the journal stays until the next real save holds what it replayed.
    }

    /** One record of pets.yml or of a journal line, or null when it lacks its id, owner or species. */
    private static Pet readPet(UUID id, Yaml c) {
        UUID owner = c == null ? null : uuid(c.getString("owner"));
        String species = c == null ? null : c.getString("species");
        if (id == null || owner == null || species == null) return null;
        Pet p = new Pet(id, owner, species);
        p.name = c.getString("name");
        p.namePending = c.getBoolean("name-pending");
        p.entity = uuid(c.getString("entity"));
        String state = c.getString("state", "alive");
        p.state = "fallen".equalsIgnoreCase(state) ? Pet.State.FALLEN
                : "stabled".equalsIgnoreCase(state) ? Pet.State.STABLED : Pet.State.ALIVE;
        p.color = c.getString(Legacy.key(c, "color", "colour")); // pets saved before the spelling change
        p.freeReviveAt = c.getLong("free-revive");
        p.stabledAt = c.getLong("stabled");
        p.xp = Math.max(0, c.getInt("bond-xp"));
        p.level = Math.max(0, c.getInt("bond-level"));
        p.stay = c.getBoolean("stay");
        p.health = c.getDouble("health");
        p.maxHealth = c.getDouble("max-health");
        p.created = c.getLong("created");
        p.diedAt = c.getLong("died");
        p.deathUnseen = c.getBoolean("death-unseen");
        p.spot = spot(c.getConfigurationSection("location"));
        p.variant = c.getString("variant");
        p.shiny = c.getBoolean("shiny");
        p.breedReadyAt = c.getLong("breed-ready");
        p.bred = c.getBoolean("bred");
        Yaml w = c.getConfigurationSection("work");
        if (w != null) {
            p.work = spot(w);
            p.workClock = w.getLong("clock");
            p.workBank = Math.max(0, w.getLong("bank"));
        }
        for (String r : c.getStringList("replaced")) {
            UUID u = uuid(r);
            if (u != null) p.replaced.add(u);
        }
        if (!p.alive()) p.entity = null;
        return p;
    }

    /**
     * Applies the journal lines newer than {@code fileSeq} to the records just read, in order. Lines that don't parse
     * (a torn last line after a crash) are skipped. Returns the highest sequence number in the journal (0 when none).
     */
    private long replay(long fileSeq, long now) {
        if (!Files.isRegularFile(journalFile)) return 0;
        byte[] raw;
        try {
            raw = Files.readAllBytes(journalFile);
        } catch (IOException e) {
            // Its lines may hold numbers above fileSeq: never hand those out again. Moved aside, the new journal starts
            // fresh; left in place, new lines number far above anything it can hold (and start on a line of their own).
            Path aside = setJournalAside("unread");
            feather.getLogger().error("Could not read {}: {} (changes since the last full save of pets.yml are not replayed{}).",
                    journalFile, e.toString(), aside != null ? "; the journal was kept as " + aside.getFileName() : "");
            if (aside != null) return 0;
            synchronized (journalLock) {
                journalTorn = true;
            }
            return fileSeq + UNREAD_SEQ_GAP;
        }
        // Decoded leniently: a crash mid-character leaves malformed bytes, which become U+FFFD and fail only their line.
        String text = new String(raw, StandardCharsets.UTF_8);
        synchronized (journalLock) {
            journalTorn = raw.length > 0 && raw[raw.length - 1] != '\n';
        }
        long last = 0;
        int applied = 0, bad = 0;
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            Yaml e;
            try {
                e = Yaml.fromString(line);
            } catch (RuntimeException ex) {
                bad++;
                continue;
            }
            long n = e.getLong("seq", 0);
            UUID id = uuid(e.getString("id"));
            if (n <= 0 || id == null) {
                bad++;
                continue;
            }
            last = Math.max(last, n);
            if (n <= fileSeq) continue;   // pets.yml already holds it
            switch (e.getString("op", "")) {
                case "put" -> {
                    Pet p = readPet(id, e.getConfigurationSection("pet"));
                    if (p == null) {
                        bad++;
                        continue;
                    }
                    pets.put(id, p);
                    released.remove(id);
                }
                case "release" -> {
                    pets.remove(id);
                    long at = e.getLong("at", now);
                    if (now - at < RELEASED_KEEP_MS) released.put(id, at);
                }
                case "drop" -> pets.remove(id);
                default -> {
                    bad++;
                    continue;
                }
            }
            applied++;
        }
        if (applied > 0) feather.getLogger().info("Pets: replayed {} change(s) from data/pets.journal.", applied);
        if (bad > 0) feather.getLogger().warn("Skipped {} unreadable line(s) in data/pets.journal (a crash mid-write?).", bad);
        return last;
    }

    /** Past any sequence number an unreadable journal could hold (each line takes far more than one number). */
    private static final long UNREAD_SEQ_GAP = 1L << 40;

    /** A non-empty journal last written before pets.yml was. */
    private boolean journalOlderThanFile() {
        try {
            return Files.isRegularFile(file) && Files.isRegularFile(journalFile) && Files.size(journalFile) > 0
                    && Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(journalFile)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Renames the journal to {@code pets.journal.<why>-<millis>}; the new name, or null when it couldn't be moved. */
    private Path setJournalAside(String why) {
        Path to = journalFile.resolveSibling(journalFile.getFileName() + "." + why + "-" + System.currentTimeMillis());
        try {
            Files.move(journalFile, to);
            return to;
        } catch (IOException e) {
            feather.getLogger().warn("Could not move {} to {}: {}", journalFile, to.getFileName(), e.toString());
            return null;
        }
    }

    private static Pet.Spot spot(Yaml l) {
        if (l == null) return null;
        return new Pet.Spot(uuid(l.getString("world-id")), l.getString("world-key"), l.getString("world"),
                l.getDouble("x"), l.getDouble("y"), l.getDouble("z"));
    }

    private static void putSpot(Map<String, Object> m, Pet.Spot s) {
        if (s.worldName() != null) m.put("world", s.worldName());
        if (s.worldKey() != null) m.put("world-key", s.worldKey());
        if (s.worldId() != null) m.put("world-id", s.worldId().toString());
        m.put("x", round(s.x()));
        m.put("y", round(s.y()));
        m.put("z", round(s.z()));
    }

    /**
     * One pet as plain maps, with the keys, order, rounding and omissions pets.yml has always had (nulls and defaults
     * left out). Only fresh objects, nothing shared with the live record, so another thread may dump it.
     */
    static Map<String, Object> record(Pet p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("owner", p.owner.toString());
        if (p.species != null) r.put("species", p.species);
        if (p.name != null) r.put("name", p.name);
        if (p.namePending) r.put("name-pending", true);
        if (p.entity != null) r.put("entity", p.entity.toString());
        r.put("state", p.state.name().toLowerCase(Locale.ROOT));
        r.put("bond-xp", p.xp);
        r.put("bond-level", p.level);
        r.put("stay", p.stay);
        r.put("health", round(p.health));
        r.put("max-health", round(p.maxHealth));
        r.put("created", p.created);
        if (p.diedAt > 0) r.put("died", p.diedAt);
        if (p.deathUnseen) r.put("death-unseen", true);
        if (p.color != null) r.put("color", p.color);
        if (p.freeReviveAt > 0) r.put("free-revive", p.freeReviveAt);
        if (p.stabledAt > 0) r.put("stabled", p.stabledAt);
        if (!p.replaced.isEmpty()) {
            List<String> rep = new ArrayList<>(p.replaced.size());
            for (UUID u : p.replaced) rep.add(u.toString());
            r.put("replaced", rep);
        }
        if (p.variant != null) r.put("variant", p.variant);
        if (p.shiny) r.put("shiny", true);
        if (p.breedReadyAt > 0) r.put("breed-ready", p.breedReadyAt);
        if (p.bred) r.put("bred", true);
        if (p.work != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            putSpot(w, p.work);
            w.put("clock", p.workClock);
            if (p.workBank > 0) w.put("bank", p.workBank);
            r.put("work", w);
        }
        if (p.spot != null) {
            Map<String, Object> l = new LinkedHashMap<>();
            putSpot(l, p.spot);
            r.put("location", l);
        }
        return r;
    }

    private record Row(String owner, long created, Pet pet) {
    }

    /**
     * The whole file as plain maps (server thread): {@code journal-seq} (the journal lines it holds), {@code released},
     * then {@code pets} by owner and creation. Prunes old release tombstones first.
     */
    private Object snapshot(long upTo) {
        long now = System.currentTimeMillis();
        released.values().removeIf(at -> now - at >= RELEASED_KEEP_MS);
        List<String> rel = new ArrayList<>(released.size());
        for (Map.Entry<UUID, Long> en : released.entrySet()) rel.add(en.getKey() + " " + en.getValue());
        List<Row> rows = new ArrayList<>(pets.size());
        for (Pet p : pets.values()) rows.add(new Row(p.owner.toString(), p.created, p));
        rows.sort(Comparator.comparing(Row::owner).thenComparingLong(Row::created));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("journal-seq", upTo);
        root.put("released", rel);
        if (!rows.isEmpty()) {
            Map<String, Object> all = new LinkedHashMap<>();
            for (Row row : rows) all.put(row.pet().id.toString(), record(row.pet()));
            root.put("pets", all);
        }
        return root;
    }

    /**
     * The timer's save: when anything changed (or the last save failed), copies the records now and dumps and writes
     * them on the pets thread. Skips while a save is still running (still dirty, so the next tick saves). Server thread.
     */
    void saveAsync() {
        if (readOnly || (!dirty && !failed)) return;
        if (inFlight.get() > 0) return;
        dirty = false;
        failed = false;
        long s = seq.get();
        submit(snapshot(s), s);
    }

    /** Hands a snapshot to the pets thread (runs it inline when that is shut down). On success the journal up to {@code s} goes. */
    private Future<?> submit(Object snap, long s) {
        inFlight.incrementAndGet();
        Runnable job = () -> {
            try {
                boolean ok;
                try {
                    ok = SafeYaml.write(file, HEADER + Yaml.dump(snap), feather.getLogger());
                } catch (RuntimeException e) {
                    feather.getLogger().error("Could not save {}: {}", file, e.toString());
                    ok = false;
                }
                if (ok) {
                    truncateJournalUpTo(s);
                } else {
                    failed = true;
                }
            } finally {
                inFlight.decrementAndGet();
            }
        };
        try {
            return io.submit(job);
        } catch (RejectedExecutionException e) {
            job.run();
            return CompletableFuture.completedFuture(null);
        }
    }

    /** A full save now that is waited for (at most {@code seconds}). False when it failed or timed out. */
    private boolean saveBlocking(long seconds) {
        if (readOnly) return false;
        dirty = false;
        failed = false;
        long s = seq.get();
        Future<?> f = submit(snapshot(s), s);
        try {
            f.get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            feather.getLogger().warn("Writing data/pets.yml took too long: {}", e.toString());
            return false;
        }
        return !failed;
    }

    /** Empties the journal after a full save that holds every line in it (writer thread). */
    private void truncateJournalUpTo(long s) {
        synchronized (journalLock) {
            if (seq.get() > s) return;   // newer lines went in meanwhile: keep them (replay skips the older ones by seq)
            if (!Files.exists(journalFile)) return;
            try (FileChannel ch = FileChannel.open(journalFile, StandardOpenOption.WRITE)) {
                ch.truncate(0);
                journalTorn = false;
            } catch (IOException e) {
                feather.getLogger().warn("Could not empty {}: {}", journalFile, e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ the journal

    /**
     * Makes these records durable now (stable in/out, a birth, a death, a work payout): one journal line each, all
     * appended in one write. Server thread; costs a small append, not a save. The next timer save folds them in.
     */
    void journal(Pet... ps) {
        if (readOnly || ps.length == 0) return;
        List<Map<String, Object>> entries = new ArrayList<>(ps.length);
        for (Pet p : ps) {
            if (p == null || pets.get(p.id) != p) continue;   // not (or no longer) on record
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("op", "put");
            e.put("id", p.id.toString());
            e.put("pet", record(p));
            entries.add(e);
        }
        append(entries);
    }

    /**
     * The journal line of {@link #release}: {@code release} (with the tombstone's time) when an entity of it may be
     * left over, else {@code drop}. Call right after {@code release(pet, leftovers)}.
     */
    void journalRelease(Pet p, boolean leftovers) {
        if (readOnly || p == null) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("op", leftovers ? "release" : "drop");
        e.put("id", p.id.toString());
        if (leftovers) {
            Long at = released.get(p.id);
            e.put("at", at == null ? System.currentTimeMillis() : at);
        }
        append(List.of(e));
    }

    private void append(List<Map<String, Object>> entries) {
        if (entries.isEmpty()) return;
        dirty = true;
        long first = seq.getAndAdd(entries.size()) + 1;   // before the write, so a truncate that runs meanwhile keeps it
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entries.size(); i++) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("seq", first + i);
                e.putAll(entries.get(i));
                String line = LINE.get().dump(e).strip();
                if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw new IOException("a journal entry spans lines");
                sb.append(line).append('\n');
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (journalLock) {
                if (journalFile.getParent() != null) Files.createDirectories(journalFile.getParent());
                try (FileChannel ch = FileChannel.open(journalFile, StandardOpenOption.APPEND, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    if (journalTorn) {
                        ch.write(ByteBuffer.wrap(new byte[]{'\n'}));
                        journalTorn = false;
                    }
                    ByteBuffer buf = ByteBuffer.wrap(bytes);
                    while (buf.hasRemaining()) ch.write(buf);
                }
            }
        } catch (IOException | RuntimeException e) {
            feather.getLogger().warn("Could not append to {} ({}); saving pets.yml now instead.", journalFile, e.toString());
            saveBlocking(10);
        }
    }

    /** One flow-style map per line (line breaks in names come out escaped); not {@link Yaml#dump}'s block style. */
    private static final ThreadLocal<org.yaml.snakeyaml.Yaml> LINE = ThreadLocal.withInitial(() -> {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        o.setSplitLines(false);
        o.setWidth(Integer.MAX_VALUE);
        o.setAllowUnicode(true);
        return new org.yaml.snakeyaml.Yaml(new Representer(o), o);
    });

    /** Writes everything now and waits (a game test calls it; game code uses {@link #journal}). */
    void saveNow() {
        dirty = true;
        saveBlocking(10);
    }

    /** Server stop: a last full save (when anything changed), waited for; if it fails the journal still holds the rest. */
    void shutdown() {
        if (!readOnly && (dirty || failed)) {
            dirty = false;
            failed = false;
            long s = seq.get();
            Future<?> f = submit(snapshot(s), s);
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                feather.getLogger().warn("Writing data/pets.yml timed out: {}", e.toString());
            }
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) feather.getLogger().warn("Writing data/pets.yml timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
