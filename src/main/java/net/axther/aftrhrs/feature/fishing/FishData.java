package net.axther.aftrhrs.feature.fishing;

import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Yaml;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * data/fishing.yml: every player's Fish Journal, the server records (biggest fish per species) and a running
 * tournament (so a restart doesn't end it). Crab pots keep their data in the chunk Pdc (see {@link CrabPots}).
 */
final class FishData {
    static final class Entry {
        int count;
        int best;
        long first;
    }

    static final class Journal {
        String name;
        final Map<String, Entry> species = new LinkedHashMap<>();
        final Set<String> paid = new HashSet<>();
    }

    record Record(int grams, UUID holder, String name, long at) {
    }

    record Entrant(String name, String species, int grams) {
    }

    final Map<UUID, Journal> journals = new HashMap<>();
    final Map<String, Record> records = new LinkedHashMap<>();
    long tournamentUntil, tournamentStarted;
    final Map<UUID, Entrant> tournament = new LinkedHashMap<>();
    long lastScheduled;

    private final Path file;
    private final Logger log;
    boolean dirty;
    boolean unreadable;
    /** A background save failed: the next {@link #saveIfDirty} tries again. Set on the data thread. */
    private volatile boolean failed;

    FishData(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    Journal journal(UUID id) {
        return journals.computeIfAbsent(id, k -> new Journal());
    }

    void load() {
        Yaml y = SafeYaml.read(file, log, "fish journals, records and the tournament");
        if (y == null) {
            unreadable = true;
            return;
        }
        unreadable = false;
        journals.clear();
        records.clear();
        tournament.clear();
        Yaml js = y.getConfigurationSection("journal");
        if (js != null) {
            for (String key : js.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                Yaml c = js.getConfigurationSection(key);
                if (c == null) continue;
                Journal j = journal(id);
                j.name = c.getString("name");
                j.paid.addAll(c.getStringList("paid"));
                Yaml sp = c.getConfigurationSection("species");
                if (sp != null) {
                    for (String s : sp.getKeys(false)) {
                        Entry e = new Entry();
                        e.count = sp.getInt(s + ".count");
                        e.best = sp.getInt(s + ".best");
                        e.first = sp.getLong(s + ".first");
                        j.species.put(s, e);
                    }
                }
            }
        }
        Yaml rs = y.getConfigurationSection("records");
        if (rs != null) {
            for (String s : rs.getKeys(false)) {
                UUID holder = null;
                try {
                    String h = rs.getString(s + ".holder");
                    if (h != null) holder = UUID.fromString(h);
                } catch (IllegalArgumentException ignored) {
                }
                records.put(s, new Record(rs.getInt(s + ".grams"), holder, rs.getString(s + ".name", "?"), rs.getLong(s + ".at")));
            }
        }
        tournamentUntil = y.getLong("tournament.until");
        tournamentStarted = y.getLong("tournament.started");
        lastScheduled = y.getLong("tournament.last-scheduled");
        Yaml ts = y.getConfigurationSection("tournament.entrants");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                try {
                    tournament.put(UUID.fromString(key), new Entrant(ts.getString(key + ".name", "?"), ts.getString(key + ".species", ""),
                            ts.getInt(key + ".grams")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    /** Writes everything and waits for it (callers that act on the result, reloads and shutdown). */
    boolean save() {
        if (unreadable) return false;
        boolean ok = SafeYaml.writeSync(file, null, snapshot(), log);
        if (ok) {
            dirty = false;
            failed = false;
        }
        return ok;
    }

    /** The timer, reload and quit save: snapshot now, written in the background, only when something changed. */
    void saveIfDirty() {
        if (unreadable || (!dirty && !failed)) return;
        dirty = false;
        failed = false;
        SafeYaml.writeAsync(file, null, snapshot(), log).thenAccept(ok -> {
            if (!ok) failed = true;
        });
    }

    /** The whole file as plain maps (server thread): nothing in it is shared with the live data. */
    private Object snapshot() {
        Yaml y = new Yaml();
        for (Map.Entry<UUID, Journal> e : journals.entrySet()) {
            String base = "journal." + e.getKey();
            Journal j = e.getValue();
            if (j.name != null) y.set(base + ".name", j.name);
            if (!j.paid.isEmpty()) y.set(base + ".paid", List.copyOf(j.paid));
            for (Map.Entry<String, Entry> s : j.species.entrySet()) {
                y.set(base + ".species." + s.getKey() + ".count", s.getValue().count);
                y.set(base + ".species." + s.getKey() + ".best", s.getValue().best);
                y.set(base + ".species." + s.getKey() + ".first", s.getValue().first);
            }
        }
        for (Map.Entry<String, Record> e : records.entrySet()) {
            Record r = e.getValue();
            y.set("records." + e.getKey() + ".grams", r.grams());
            if (r.holder() != null) y.set("records." + e.getKey() + ".holder", r.holder().toString());
            y.set("records." + e.getKey() + ".name", r.name());
            y.set("records." + e.getKey() + ".at", r.at());
        }
        y.set("tournament.until", tournamentUntil);
        y.set("tournament.started", tournamentStarted);
        y.set("tournament.last-scheduled", lastScheduled);
        for (Map.Entry<UUID, Entrant> e : tournament.entrySet()) {
            String base = "tournament.entrants." + e.getKey();
            y.set(base + ".name", e.getValue().name());
            y.set(base + ".species", e.getValue().species());
            y.set(base + ".grams", e.getValue().grams());
        }
        return y.snapshot();
    }
}
