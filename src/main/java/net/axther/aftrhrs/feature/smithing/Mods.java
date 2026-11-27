package net.axther.aftrhrs.feature.smithing;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Modifier slots and traits of a build (SMITHING.md 6-7). Slots: U (upgrade), A (ability), D (defense); each level
 * of a slotted modifier takes one, an incremental modifier part-way to its next level holds one too. Extra slots:
 * Masterwork, Inscribed and Harmonic (+1 U each), rose gold parts (+1 U each, up to 2), Trophy-bound (+1 A per level),
 * Starforged (+1 A).
 */
public final class Mods {
    public static final int U = 0, A = 1, D = 2;

    private Mods() {
    }

    public static int slotIndex(String slot) {
        return switch (slot) {
            case "U" -> U;
            case "A" -> A;
            case "D" -> D;
            default -> -1;
        };
    }

    /** Stacks trait levels, up to III. */
    private static final BiFunction<Integer, Integer, Integer> CAP = (a, b) -> Math.min(3, a + b);

    /**
     * {@link #traits} per build instance: item components hand out the same ToolBuild until the stack changes, and
     * tooltips, stats and effects ask for its traits many times. weakKeys() compares by identity (ToolBuild is an
     * immutable record, so a cached map stays right) and drops entries with their build; thread-safe for the client and
     * integrated-server threads. Any future smithing-defs reload must call {@link #invalidate}.
     */
    private static final LoadingCache<ToolBuild, Map<String, Integer>> CACHE = CacheBuilder.newBuilder().weakKeys().maximumSize(4096)
            .build(CacheLoader.from(b -> Collections.unmodifiableMap(compute(b))));

    /** Forgets every cached {@link #traits} map (call after the smithing definitions change). */
    static void invalidate() {
        CACHE.invalidateAll();
    }

    /** The traits a build brings, with levels (the same trait from several parts stacks, up to III). Read-only. */
    public static Map<String, Integer> traits(ToolBuild build) {
        return CACHE.getUnchecked(build);
    }

    private static Map<String, Integer> compute(ToolBuild build) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ToolBuild.PartEntry e : build.parts()) {
            SmithingDefs.Part p = SmithingDefs.part(e.part());
            if (p == null) continue;
            add(out, e.material(), p);
            add(out, e.extra(), p);
            if ("frost".equals(e.quench())) {
                SmithingDefs.Material m = SmithingDefs.material(e.material());
                if (m != null && m.metal()) out.merge("frost_tempered", 1, CAP);
            }
        }
        return out;
    }

    private static void add(Map<String, Integer> out, String matId, SmithingDefs.Part p) {
        SmithingDefs.Material m = SmithingDefs.material(matId);
        if (m == null) return;
        String t = m.trait(p.role());
        if (t != null) out.merge(t, 1, CAP);
    }

    /**
     * One trait's level, without building the map (asked every tick by the smithing mixins). Every source adds 1, so
     * capping the sum at III is the same as capping each merge.
     */
    public static int trait(ToolBuild build, String trait) {
        int n = 0;
        for (ToolBuild.PartEntry e : build.parts()) {
            SmithingDefs.Part p = SmithingDefs.part(e.part());
            if (p == null) continue;
            n += has(e.material(), p, trait) + has(e.extra(), p, trait);
            if ("frost".equals(e.quench()) && "frost_tempered".equals(trait)) {
                SmithingDefs.Material m = SmithingDefs.material(e.material());
                if (m != null && m.metal()) n++;
            }
        }
        return Math.min(3, n);
    }

    private static int has(String matId, SmithingDefs.Part p, String trait) {
        SmithingDefs.Material m = SmithingDefs.material(matId);
        return m != null && trait.equals(m.trait(p.role())) ? 1 : 0;
    }

    /** Slots the build has, {U, A, D}. */
    public static int[] capacity(ToolBuild build) {
        SmithingDefs.ToolType type = SmithingDefs.tool(build.tool());
        int[] c = type == null ? new int[3] : type.slots().clone();
        if (build.masterwork()) c[U]++;
        c[U] += build.level("inscribed") + build.level("harmonic");
        c[A] += build.level("trophy_bound");
        int rose = 0;
        boolean star = false;
        for (ToolBuild.PartEntry e : build.parts()) {
            if (e.material().equals("rose_gold")) rose++;
            if (e.material().equals("starforged")) star = true;
        }
        c[U] += Math.min(2, rose);
        if (star) c[A]++;
        return c;
    }

    /** Slots in use, {U, A, D}. */
    public static int[] used(ToolBuild build) {
        int[] u = new int[3];
        for (ToolBuild.Mod m : build.mods()) {
            SmithingDefs.Modifier def = SmithingDefs.modifier(m.id());
            if (def == null) continue;
            int i = slotIndex(def.slot());
            if (i >= 0) u[i] += m.level();
        }
        for (var e : build.progress().entrySet()) {
            if (!e.getKey().startsWith("mod:") || e.getValue() <= 0) continue;
            SmithingDefs.Modifier def = SmithingDefs.modifier(e.getKey().substring(4));
            if (def == null) continue;
            int i = slotIndex(def.slot());
            if (i >= 0) u[i]++;
        }
        return u;
    }

    /** Can {@code def} go on tools of {@code type} at all? */
    public static boolean fits(SmithingDefs.ToolType type, SmithingDefs.Modifier def) {
        for (String f : def.fits()) {
            boolean ok = switch (f) {
                case "any" -> !type.kind().equals("ammo");
                case "tool" -> type.kind().equals("harvest") || type.kind().equals("melee");
                case "edge" -> edged(type);
                case "bow" -> type.has("bow");
                case "crossbow" -> type.has("crossbow");
                case "rod" -> type.has("fishing");
                case "helmet", "chestplate", "leggings", "boots" -> type.id().equals(f);
                default -> type.kind().equals(f) || type.id().equals(f);
            };
            if (ok) return true;
        }
        return false;
    }

    /** Tools with an Edge (SMITHING.md 5.4). */
    public static boolean edged(SmithingDefs.ToolType type) {
        return switch (type.id()) {
            case "sword", "dagger", "spear", "greatsword", "scythe", "hatchet", "broad_axe", "mattock" -> true;
            default -> false;
        };
    }

    /** Why {@code def} can't take its next level on {@code build} (a lang key), or null when it can. */
    public static String refuse(ToolBuild build, SmithingDefs.Modifier def) {
        SmithingDefs.ToolType type = SmithingDefs.tool(build.tool());
        if (type == null || !fits(type, def)) return "aftrhrs.smithing.rune.wrong_tool";
        int level = build.level(def.id());
        if (level >= def.max()) return "aftrhrs.smithing.rune.max";
        if (def.id().equals("trophy_bound") && level >= 3) return "aftrhrs.smithing.rune.max";
        int i = slotIndex(def.slot());
        if (i < 0) return null;
        boolean holding = build.progress("mod:" + def.id()) > 0;
        if (holding) return null; // its slot is already held
        int[] cap = capacity(build), used = used(build);
        return used[i] >= cap[i] ? "aftrhrs.smithing.rune.no_slot" : null;
    }
}
