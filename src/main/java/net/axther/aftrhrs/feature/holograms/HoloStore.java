package net.axther.aftrhrs.feature.holograms;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Yaml;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * holograms.yml (in the Feather folder, as on Paper): every hologram made with /holo. Written through {@link SafeYaml}
 * (temp file + atomic move, the previous file kept as {@code .bak}); a snapshot of the holograms is taken on the
 * server thread, and turned into YAML and written on the data thread, in order. A file that can't be read is never
 * saved over: edits are refused until it is fixed and reloaded.
 */
final class HoloStore {
    private static final String HEADER = """
            # Holograms made with /holo (docs/holograms.md). Edit them in game with /holo edit <name>,
            # or here and then /holo reload. Lines: text (MiniMessage), #ITEM: <id>, #HEAD: <name>, #BLOCK: <block>,
            # #ENTITY: <type|mythic:id>, #BLANK.
            """;

    private final Logger log;
    final Path file;
    /** True while holograms.yml exists but couldn't be read: nothing is saved. */
    boolean unreadable;
    private long queued;
    private long written;

    HoloStore(Feather plugin) {
        this.log = plugin.getLogger();
        this.file = plugin.getDataFolder().resolve("holograms.yml");
    }

    /** The holograms in the file, or null when it can't be read (then {@link #unreadable} is set). */
    Map<String, Hologram> load(Map<String, String> defaults) {
        Yaml yml = SafeYaml.read(file, log, "holograms");
        if (yml == null) {
            unreadable = true;
            return null;
        }
        unreadable = false;
        Map<String, Hologram> out = new LinkedHashMap<>();
        Yaml all = yml.getConfigurationSection("holograms");
        if (all == null) return out;
        for (String name : all.getKeys(false)) {
            Yaml s = all.getConfigurationSection(name);
            if (s == null || !HologramsModule.validName(name)) {
                log.warn("holograms.yml: skipped '{}' (names are letters, digits, - and _).", name);
                continue;
            }
            Hologram h = new Hologram(name, null, null, defaults);
            h.world = s.getString("location.world", "world");
            h.x = s.getDouble("location.x");
            h.y = s.getDouble("location.y");
            h.z = s.getDouble("location.z");
            Yaml set = s.getConfigurationSection("settings");
            if (set != null) {
                for (String k : set.getKeys(false)) {
                    String v = HoloSettings.normalize(k, set.getString(k));
                    if (v == null) log.warn("holograms.yml: {} has a bad setting {}: {}", name, k, set.getString(k));
                    else h.settings.put(k, v);
                }
            }
            for (Map<?, ?> pm : s.getMapList("pages")) {
                HoloPage page = new HoloPage();
                Object lines = pm.get("lines");
                if (lines instanceof List<?> list) {
                    for (Object o : list) {
                        HoloLine l = line(o);
                        if (l != null) page.lines.add(l);
                    }
                }
                Object actions = pm.get("actions");
                if (actions instanceof List<?> list) {
                    for (Object o : list) {
                        HoloPage.Action a = HoloPage.Action.parse(String.valueOf(o));
                        if (a == null) log.warn("holograms.yml: {} has a bad action: {}", name, o);
                        else page.actions.add(a);
                    }
                }
                h.pages.add(page);
            }
            if (h.pages.isEmpty()) h.pages.add(new HoloPage());
            out.put(name.toLowerCase(java.util.Locale.ROOT), h);
        }
        return out;
    }

    private static HoloLine line(Object o) {
        if (o instanceof String s) return new HoloLine(s);
        if (o instanceof Map<?, ?> m) {
            Object c = m.get("content");
            HoloLine l = new HoloLine(c == null ? "" : String.valueOf(c));
            Object h = m.get("height");
            if (h instanceof Number n) l.height = n.doubleValue();
            if (m.get("offset") instanceof List<?> off && off.size() == 3) {
                l.ox = num(off.get(0));
                l.oy = num(off.get(1));
                l.oz = num(off.get(2));
            }
            return l;
        }
        return o == null ? null : new HoloLine(String.valueOf(o));
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** The file's tree as plain maps (server thread; nothing in it is shared with the live holograms). */
    Object snapshot(Iterable<Hologram> holograms) {
        Yaml yml = new Yaml();
        yml.createSection("holograms");
        for (Hologram h : holograms) {
            if (h.temporary()) continue;
            String p = "holograms." + h.name();
            yml.set(p + ".location.world", h.world);
            yml.set(p + ".location.x", round(h.x));
            yml.set(p + ".location.y", round(h.y));
            yml.set(p + ".location.z", round(h.z));
            if (!h.settings.isEmpty()) yml.set(p + ".settings", new LinkedHashMap<>(h.settings));
            List<Map<String, Object>> pages = new ArrayList<>();
            for (HoloPage page : h.pages) {
                Map<String, Object> pm = new LinkedHashMap<>();
                List<Object> lines = new ArrayList<>();
                for (HoloLine l : page.lines) {
                    if (l.height == null && !l.hasOffset()) {
                        lines.add(l.content());
                        continue;
                    }
                    Map<String, Object> lm = new LinkedHashMap<>();
                    lm.put("content", l.content());
                    if (l.height != null) lm.put("height", l.height);
                    if (l.hasOffset()) lm.put("offset", List.of(l.ox, l.oy, l.oz));
                    lines.add(lm);
                }
                pm.put("lines", lines);
                if (!page.actions.isEmpty()) pm.put("actions", page.actions.stream().map(HoloPage.Action::toString).toList());
                pages.add(pm);
            }
            yml.set(p + ".pages", pages);
        }
        return yml.snapshot();
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    /**
     * Saves off the server thread: the snapshot is taken now, dumped and written on the data thread, in order (a newer
     * save is always written after an older one). False when the file is unreadable.
     */
    boolean saveLater(Iterable<Hologram> holograms) {
        if (unreadable) return false;
        Object snap = snapshot(holograms);
        long n = next();
        SafeYaml.writeAsync(file, HEADER, snap, log).thenAccept(ok -> {
            if (ok) done(n);
        });
        return true;
    }

    /** Saves now and waits for it, behind any save still queued (reload, shutdown). False when it wasn't written. */
    boolean saveNow(Iterable<Hologram> holograms) {
        if (unreadable) return false;
        Object snap = snapshot(holograms);
        long n = next();
        boolean ok = SafeYaml.writeSync(file, HEADER, snap, log);
        if (ok) done(n);
        return ok;
    }

    private synchronized long next() {
        return ++queued;
    }

    private synchronized void done(long n) {
        written = Math.max(written, n);
    }

    /** True while a save hasn't reached the disk yet. */
    synchronized boolean pending() {
        return queued > written;
    }
}
