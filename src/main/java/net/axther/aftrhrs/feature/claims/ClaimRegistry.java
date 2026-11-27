package net.axther.aftrhrs.feature.claims;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Worlds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every claim, filed by chunk: world → chunk key → the claims touching that chunk, so {@link #at} is a map lookup plus
 * a few rectangle checks. Claims never overlap, so a spot has at most one claim.
 *
 * <p>Edits (server thread only) go to the per-world {@link #building} maps; {@link #publish} then swaps a copy of each
 * changed world into the volatile {@link #index}. Readers on any thread only ever see a finished snapshot, keyed by
 * primitive {@code long}s and holding plain arrays (no boxing, no locks).
 */
final class ClaimRegistry {
    private static final Claim[] NONE = new Claim[0];

    final Map<Integer, Claim> byId = new LinkedHashMap<>();
    /** The published snapshot: never changed once assigned, so lookups from other threads never see a half-made change. */
    private volatile Map<String, Long2ObjectOpenHashMap<Claim[]>> index = Map.of();
    /** Server-thread working copy. Its arrays are replaced, never changed in place, so published copies can share them. */
    private final Map<String, Long2ObjectOpenHashMap<Claim[]>> building = new HashMap<>();
    /** Worlds edited since the last publish. */
    private final Set<String> changed = new java.util.HashSet<>();
    /** Above 0 inside {@link #bulk}: edits are published once at the end. */
    private int deferPublish;
    int nextId = 1;

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** Forgets every claim (before loading the file again). */
    void clear() {
        // Open menus and tool sessions may still hold these: they read as gone from now on.
        for (Claim c : byId.values()) {
            c.removed = true;
            c.indexed.clear();
        }
        byId.clear();
        building.clear();
        changed.clear();
        index = Map.of();
        nextId = 1;
    }

    /** Runs {@code edits} (many adds or removes, e.g. loading the file) and publishes the index once at the end. */
    void bulk(Runnable edits) {
        deferPublish++;
        try {
            edits.run();
        } finally {
            if (--deferPublish == 0) publish();
        }
    }

    /** Makes the edited worlds visible to readers: a fresh copy of each changed world, the others shared as they were. */
    private void publish() {
        if (deferPublish > 0 || changed.isEmpty()) return;
        Map<String, Long2ObjectOpenHashMap<Claim[]>> next = new HashMap<>(index);
        for (String world : changed) {
            Long2ObjectOpenHashMap<Claim[]> w = building.get(world);
            if (w == null || w.isEmpty()) {
                next.remove(world);
                building.remove(world);
            } else {
                next.put(world, new Long2ObjectOpenHashMap<>(w));
            }
        }
        changed.clear();
        index = next;
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }

    Collection<Claim> all() {
        return byId.values();
    }

    Claim get(int id) {
        return byId.get(id);
    }

    void add(Claim c) {
        byId.put(c.id, c);
        nextId = Math.max(nextId, c.id + 1);
        file(c);
        publish();
    }

    void remove(Claim c) {
        c.removed = true;
        byId.remove(c.id);
        unfile(c);
        publish();
    }

    /** Gives a claim new areas and re-files it. */
    void setAreas(Claim c, List<Area> areas) {
        unfile(c);
        c.setAreas(areas);
        file(c);
        publish();
    }

    private void file(Claim c) {
        Long2ObjectOpenHashMap<Claim[]> w = building.computeIfAbsent(c.world, k -> new Long2ObjectOpenHashMap<>());
        changed.add(c.world);
        for (Area a : c.areas) {
            for (int cx = a.minX() >> 4; cx <= a.maxX() >> 4; cx++) {
                for (int cz = a.minZ() >> 4; cz <= a.maxZ() >> 4; cz++) {
                    long k = key(cx, cz);
                    if (!c.indexed.add(k)) continue;
                    Claim[] old = w.get(k);
                    if (old == null) {
                        w.put(k, new Claim[] {c});
                    } else {
                        Claim[] arr = java.util.Arrays.copyOf(old, old.length + 1);
                        arr[old.length] = c;
                        w.put(k, arr);
                    }
                }
            }
        }
    }

    private void unfile(Claim c) {
        Long2ObjectOpenHashMap<Claim[]> w = building.get(c.world);
        if (w != null) {
            changed.add(c.world);
            for (long k : c.indexed) {
                Claim[] old = w.get(k);
                if (old == null) continue;
                int i = 0;
                while (i < old.length && old[i] != c) i++;
                if (i == old.length) continue;
                if (old.length == 1) {
                    w.remove(k);
                    continue;
                }
                Claim[] arr = new Claim[old.length - 1];
                System.arraycopy(old, 0, arr, 0, i);
                System.arraycopy(old, i + 1, arr, i, old.length - i - 1);
                w.put(k, arr);
            }
        }
        c.indexed.clear();
    }

    /** The claims filed under this chunk of a published world (never null). */
    private static Claim[] in(Long2ObjectOpenHashMap<Claim[]> w, int cx, int cz) {
        Claim[] arr = w.get(key(cx, cz));
        return arr == null ? NONE : arr;
    }

    /** The claim covering this column, or null. */
    Claim at(String world, int x, int z) {
        Long2ObjectOpenHashMap<Claim[]> w = index.get(world);
        if (w == null) return null;
        Claim[] arr = w.get(key(x >> 4, z >> 4));
        if (arr == null) return null;
        for (int i = 0; i < arr.length; i++) if (arr[i].contains(x, z)) return arr[i];
        return null;
    }

    Claim at(Loc l) {
        if (l == null || l.level() == null || byId.isEmpty()) return null;
        return at(Worlds.name(l.level()), l.blockX(), l.blockZ());
    }

    Claim at(Level level, BlockPos b) {
        if (b == null || level == null || byId.isEmpty()) return null;
        return at(Worlds.name(level), b.getX(), b.getZ());
    }

    Claim at(Level level, int x, int z) {
        if (level == null || byId.isEmpty()) return null;
        return at(Worlds.name(level), x, z);
    }

    /** Claims with any chunk within {@code radius} blocks of (x, z). */
    Set<Claim> near(String world, int x, int z, int radius) {
        Set<Claim> out = new LinkedHashSet<>();
        Long2ObjectOpenHashMap<Claim[]> w = index.get(world);
        if (w == null) return out;
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                Claim[] arr = in(w, cx, cz);
                for (int i = 0; i < arr.length; i++) out.add(arr[i]);
            }
        }
        return out;
    }

    /**
     * Claims (other than {@code except}) with an area overlapping {@code a}. Server thread only: it reads the working
     * copy, so it also sees claims added inside a {@link #bulk} that is not published yet (the load's clash check).
     */
    List<Claim> overlapping(String world, Area a, Claim except) {
        List<Claim> out = new ArrayList<>();
        Long2ObjectOpenHashMap<Claim[]> w = building.get(world);
        if (w == null) return out;
        Set<Claim> seen = new java.util.HashSet<>();
        for (int cx = a.minX() >> 4; cx <= a.maxX() >> 4; cx++) {
            for (int cz = a.minZ() >> 4; cz <= a.maxZ() >> 4; cz++) {
                Claim[] arr = in(w, cx, cz);
                for (int i = 0; i < arr.length; i++) {
                    Claim c = arr[i];
                    if (c == except || !seen.add(c)) continue;
                    for (Area b : c.areas) {
                        if (b.overlaps(a)) {
                            out.add(c);
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Claims of this owner (admin claims never join) whose areas overlap or share an edge with {@code a}. */
    List<Claim> touching(String world, Area a, UUID owner) {
        List<Claim> out = new ArrayList<>();
        if (owner == null) return out;
        for (Claim c : overlapping(world, a.grow(1), null)) {
            if (!owner.equals(c.owner)) continue;
            for (Area b : c.areas) {
                if (b.touches(a)) {
                    out.add(c);
                    break;
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(c -> c.id));
        return out;
    }

    List<Claim> ownedBy(UUID owner) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : byId.values()) if (owner != null && owner.equals(c.owner)) out.add(c);
        return out;
    }

    long used(UUID owner) {
        long n = 0;
        for (Claim c : byId.values()) if (owner != null && owner.equals(c.owner)) n += c.cost;
        return n;
    }

    /** An admin claim by name (case-insensitive, {@code _} for spaces) or {@code #id}. */
    Claim find(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.startsWith("#")) {
            try {
                return byId.get(Integer.parseInt(t.substring(1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String k = t.replace('_', ' ');
        for (Claim c : byId.values()) if (c.admin() && c.name.equalsIgnoreCase(k)) return c;
        for (Claim c : byId.values()) if (c.name.equalsIgnoreCase(k)) return c;
        return null;
    }

    /** Groups areas into pieces that touch (each piece one claim). */
    static List<List<Area>> components(List<Area> areas) {
        int n = areas.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (areas.get(i).touches(areas.get(j))) {
                    int a = root(parent, i), b = root(parent, j);
                    if (a != b) parent[a] = b;
                }
            }
        }
        Map<Integer, List<Area>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(root(parent, i), k -> new ArrayList<>()).add(areas.get(i));
        return new ArrayList<>(groups.values());
    }

    private static int root(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }
}
