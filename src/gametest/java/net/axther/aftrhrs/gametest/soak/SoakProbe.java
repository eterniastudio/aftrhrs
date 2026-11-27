package net.axther.aftrhrs.gametest.soak;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Leak probe: walks the mod's static fields and every running Feather module (a few levels into the mod's own objects)
 * and records the size of every collection, plus how many of their sampled elements are dead game objects (a quit
 * player, a removed entity, a level of a stopped server). {@code soak/probe.log} gets the sizes; a second probe shows
 * what grew.
 */
final class SoakProbe {
    private static final Map<String, Integer> last = new TreeMap<>();
    private static List<Class<?>> classes;

    private SoakProbe() {
    }

    static synchronized void probe(String label) {
        Map<String, Integer> sizes = new TreeMap<>();
        Map<String, String> stale = new TreeMap<>();
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> c : classes()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fs) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    walk(c.getSimpleName() + "." + f.getName(), f.get(null), 0, sizes, stale, seen);
                } catch (Throwable ignored) {
                }
            }
        }
        Feather feather = Feather.get();
        if (feather != null) {
            walk("Feather", feather, 0, sizes, stale, seen);
            for (FeatherModule m : feather.modules()) walk(m.getClass().getSimpleName(), m, 0, sizes, stale, seen);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== probe ").append(label).append(' ').append(LocalTime.now().withNano(0)).append('\n');
        List<Map.Entry<String, Integer>> grew = new ArrayList<>();
        for (var e : sizes.entrySet()) {
            Integer before = last.get(e.getKey());
            if (before != null && e.getValue() > before + 8 && e.getValue() > before * 1.5) grew.add(e);
        }
        sb.append("grew since last probe:\n");
        for (var e : grew) sb.append("  ").append(e.getKey()).append(' ').append(last.get(e.getKey())).append(" -> ").append(e.getValue()).append('\n');
        sb.append("dead references held:\n");
        for (var e : stale.entrySet()) sb.append("  ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        sb.append("largest:\n");
        sizes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
                .forEach(e -> sb.append("  ").append(e.getKey()).append(' ').append(e.getValue()).append('\n'));
        last.clear();
        last.putAll(sizes);
        try {
            Files.writeString(SoakDriver.out.resolve("probe.log"), sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AftrHrs.LOG.warn("probe", e);
        }
        SoakDriver.log("probe " + label + ": " + sizes.size() + " collections, " + grew.size() + " grew, " + stale.size() + " hold dead objects");
        for (var e : stale.entrySet()) SoakDriver.log("  dead refs " + e.getKey() + " " + e.getValue());
    }

    private static void walk(String path, Object o, int depth, Map<String, Integer> sizes, Map<String, String> stale, Set<Object> seen) {
        if (o == null || depth > 4 || !seen.add(o)) return;
        if (o instanceof Map<?, ?> m) {
            int n;
            List<Object> sample = new ArrayList<>();
            try {
                n = m.size();
                int i = 0;
                for (var e : new ArrayList<>(m.entrySet())) {
                    if (i++ >= 64) break;
                    sample.add(e.getKey());
                    sample.add(e.getValue());
                }
            } catch (Throwable t) {
                return;
            }
            sizes.put(path, n);
            check(path, sample, stale);
            for (Object v : sample) if (own(v) || v instanceof Collection || v instanceof Map) walk(path + "[]", v, depth + 1, sizes, stale, seen);
            return;
        }
        if (o instanceof Collection<?> c) {
            int n;
            List<Object> sample = new ArrayList<>();
            try {
                n = c.size();
                int i = 0;
                for (Object v : new ArrayList<>(c)) {
                    if (i++ >= 64) break;
                    sample.add(v);
                }
            } catch (Throwable t) {
                return;
            }
            sizes.put(path, n);
            check(path, sample, stale);
            for (Object v : sample) if (own(v) || v instanceof Collection || v instanceof Map) walk(path + "[]", v, depth + 1, sizes, stale, seen);
            return;
        }
        if (o instanceof ServerPlayer || o instanceof Entity || o instanceof ServerLevel) {
            check(path, List.of(o), stale);
            return;
        }
        if (!own(o)) return;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (!k.getName().startsWith("net.axther.aftrhrs")) break;
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    walk(path + "." + f.getName(), f.get(o), depth + 1, sizes, stale, seen);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean own(Object o) {
        return o != null && o.getClass().getName().startsWith("net.axther.aftrhrs") && !o.getClass().isEnum();
    }

    private static void check(String path, List<Object> sample, Map<String, String> stale) {
        MinecraftServer s = SoakDriver.server();
        int quit = 0, removed = 0, oldLevel = 0;
        for (Object v : sample) {
            if (v instanceof ServerPlayer p) {
                if (p.hasDisconnected() || s != null && s.getPlayerList().getPlayer(p.getUUID()) != p) quit++;
            } else if (v instanceof Entity e) {
                if (e.isRemoved()) removed++;
            }
            if (v instanceof ServerLevel l && s != null && s.getLevel(l.dimension()) != l) oldLevel++;
        }
        if (quit + removed + oldLevel > 0) stale.put(path, "quit-players=" + quit + " removed-entities=" + removed + " old-levels=" + oldLevel);
    }

    private static List<Class<?>> classes() {
        if (classes != null) return classes;
        List<Class<?>> out = new ArrayList<>();
        try {
            URI src = AftrHrs.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path root = Path.of(src);
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) files::iterator) {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        if (!rel.endsWith(".class") || rel.contains("/mixin/") || rel.matches(".*\\$\\d.*")) continue;
                        String name = rel.substring(0, rel.length() - 6).replace('/', '.');
                        try {
                            out.add(Class.forName(name, false, AftrHrs.class.getClassLoader()));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            AftrHrs.LOG.warn("probe classes", e);
        }
        classes = out;
        SoakDriver.log("probe: " + out.size() + " classes");
        return out;
    }

    static Map<String, Integer> lastSizes() {
        return new LinkedHashMap<>(last);
    }
}
