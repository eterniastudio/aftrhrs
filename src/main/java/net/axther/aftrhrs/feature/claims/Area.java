package net.axther.aftrhrs.feature.claims;

import java.util.ArrayList;
import java.util.List;

/**
 * One piece of a claim, full height: a block rectangle (inclusive x/z corners), or with {@code round} a circle around
 * the center block of that (square, odd-sided) box. {@code chunk} marks a whole chunk claimed with /claim chunk; it only
 * matters for how the area is shown and described, never for protection.
 */
record Area(int minX, int minZ, int maxX, int maxZ, boolean chunk, boolean round) {
    Area(int minX, int minZ, int maxX, int maxZ, boolean chunk) {
        this(minX, minZ, maxX, maxZ, chunk, false);
    }

    Area {
        if (minX > maxX) {
            int t = minX;
            minX = maxX;
            maxX = t;
        }
        if (minZ > maxZ) {
            int t = minZ;
            minZ = maxZ;
            maxZ = t;
        }
    }

    static Area rect(int x1, int z1, int x2, int z2) {
        return new Area(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), false);
    }

    static Area ofChunk(int cx, int cz) {
        return new Area(cx << 4, cz << 4, (cx << 4) + 15, (cz << 4) + 15, true);
    }

    /** A circle: every column whose center is within {@code r + 0.5} blocks of the center block's center. */
    static Area circle(int cx, int cz, int r) {
        r = Math.max(0, r);
        return new Area(cx - r, cz - r, cx + r, cz + r, false, true);
    }

    int centerX() {
        return Math.floorDiv(minX + maxX, 2);
    }

    int centerZ() {
        return Math.floorDiv(minZ + maxZ, 2);
    }

    int radius() {
        return (maxX - minX) / 2;
    }

    /** Half the width of a circle's row {@code dz} away from the center (-1 = no such row). */
    private int half(int dz) {
        long r = radius();
        long v = r * r + r - (long) dz * dz;
        return v < 0 ? -1 : (int) Math.sqrt((double) v);
    }

    /** The x range {@code [from, to]} this area covers in row z, or null. */
    int[] span(int z) {
        if (z < minZ || z > maxZ) return null;
        if (!round) return new int[]{minX, maxX};
        int h = half(z - centerZ());
        if (h < 0) return null;
        int cx = centerX();
        return new int[]{cx - h, cx + h};
    }

    /** Claim chunks a number of claimed columns costs: every started chunk (256 columns) counts. */
    static long chunks(long blocks) {
        return blocks <= 0 ? 0 : (blocks + 255) / 256;
    }

    int width() {
        return maxX - minX + 1;
    }

    int length() {
        return maxZ - minZ + 1;
    }

    long blocks() {
        if (!round) return (long) width() * length();
        long n = 0;
        for (int dz = -radius(); dz <= radius(); dz++) n += 2L * half(dz) + 1;
        return n;
    }

    boolean contains(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
        if (!round) return true;
        long dx = x - centerX(), dz = z - centerZ(), r = radius();
        return dx * dx + dz * dz <= r * r + r;
    }

    private boolean boxesOverlap(Area o, int grow) {
        return minX - grow <= o.maxX && o.minX <= maxX + grow && minZ - grow <= o.maxZ && o.minZ <= maxZ + grow;
    }

    boolean overlaps(Area o) {
        if (!boxesOverlap(o, 0)) return false;
        if (!round && !o.round) return true;
        for (int z = Math.max(minZ, o.minZ); z <= Math.min(maxZ, o.maxZ); z++) {
            int[] s = span(z), t = o.span(z);
            if (s != null && t != null && s[0] <= t[1] && t[0] <= s[1]) return true;
        }
        return false;
    }

    /** Overlaps, or shares an edge (a corner alone doesn't count). */
    boolean touches(Area o) {
        if (!round && !o.round) {
            boolean xOverlap = minX <= o.maxX && o.minX <= maxX;
            boolean zOverlap = minZ <= o.maxZ && o.minZ <= maxZ;
            if (xOverlap && zOverlap) return true;
            if (zOverlap && (maxX + 1 == o.minX || o.maxX + 1 == minX)) return true;
            return xOverlap && (maxZ + 1 == o.minZ || o.maxZ + 1 == minZ);
        }
        if (!boxesOverlap(o, 1)) return false;
        for (int z = Math.max(minZ, o.minZ - 1); z <= Math.min(maxZ, o.maxZ + 1); z++) {
            int[] s = span(z);
            if (s == null) continue;
            int[] t = o.span(z);
            // Same row: overlapping or side by side; the rows above and below: overlapping x.
            if (t != null && s[0] <= t[1] + 1 && t[0] <= s[1] + 1) return true;
            for (int dz = -1; dz <= 1; dz += 2) {
                t = o.span(z + dz);
                if (t != null && s[0] <= t[1] && t[0] <= s[1]) return true;
            }
        }
        return false;
    }

    boolean containsArea(Area o) {
        if (o.minX < minX || o.maxX > maxX || o.minZ < minZ || o.maxZ > maxZ) return false;
        if (!round) return true;
        for (int z = o.minZ; z <= o.maxZ; z++) {
            int[] t = o.span(z);
            if (t == null) continue;
            int[] s = span(z);
            if (s == null || t[0] < s[0] || t[1] > s[1]) return false;
        }
        return true;
    }

    /** This area grown by {@code n} blocks on every side (a circle: a bigger circle). */
    Area grow(int n) {
        if (round) return circle(centerX(), centerZ(), radius() + n);
        return new Area(minX - n, minZ - n, maxX + n, maxZ + n, false);
    }

    /** Is (x, z) one of this rectangle's four corner blocks? Circles have none. */
    boolean isCorner(int x, int z) {
        return !round && (x == minX || x == maxX) && (z == minZ || z == maxZ);
    }

    /** The corner block diagonally across from the corner (x, z). */
    int[] opposite(int x, int z) {
        return new int[]{x == minX ? maxX : minX, z == minZ ? maxZ : minZ};
    }

    /** What is left of this area once {@code cut} is taken out: up to four rectangles. */
    List<Area> minus(Area cut) {
        List<Area> out = new ArrayList<>(4);
        if (!overlaps(cut)) {
            out.add(this);
            return out;
        }
        // Strips: west, east (full length), then north and south between them.
        if (cut.minX > minX) out.add(new Area(minX, minZ, cut.minX - 1, maxZ, false));
        if (cut.maxX < maxX) out.add(new Area(cut.maxX + 1, minZ, maxX, maxZ, false));
        int x1 = Math.max(minX, cut.minX), x2 = Math.min(maxX, cut.maxX);
        if (cut.minZ > minZ) out.add(new Area(x1, minZ, x2, cut.minZ - 1, false));
        if (cut.maxZ < maxZ) out.add(new Area(x1, cut.maxZ + 1, x2, maxZ, false));
        return out;
    }

    /** Saved form: {@code chunk:<cx>:<cz>}, {@code rect:<minX>:<minZ>:<maxX>:<maxZ>} or {@code circle:<x>:<z>:<radius>}. */
    String serialize() {
        if (round) return "circle:" + centerX() + ":" + centerZ() + ":" + radius();
        if (chunk && (minX & 15) == 0 && (minZ & 15) == 0 && width() == 16 && length() == 16) return "chunk:" + (minX >> 4) + ":" + (minZ >> 4);
        return "rect:" + minX + ":" + minZ + ":" + maxX + ":" + maxZ;
    }

    static Area parse(String s) {
        String[] p = s.trim().split(":");
        if (p[0].equalsIgnoreCase("chunk") && p.length == 3) return ofChunk(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        if (p[0].equalsIgnoreCase("circle") && p.length == 4) {
            int r = Integer.parseInt(p[3]);
            if (r < 0) throw new IllegalArgumentException("bad radius in '" + s + "'");
            return circle(Integer.parseInt(p[1]), Integer.parseInt(p[2]), r);
        }
        if (p[0].equalsIgnoreCase("rect") && p.length == 5) {
            return rect(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        }
        throw new IllegalArgumentException("bad area '" + s + "'");
    }

    /** Number of distinct columns the areas cover together (overlaps counted once). */
    static long union(List<Area> areas) {
        if (areas.isEmpty()) return 0;
        if (areas.size() == 1) return areas.get(0).blocks();
        for (Area a : areas) if (a.round) return rowUnion(areas);
        java.util.TreeSet<Integer> xs = new java.util.TreeSet<>(), zs = new java.util.TreeSet<>();
        for (Area a : areas) {
            xs.add(a.minX);
            xs.add(a.maxX + 1);
            zs.add(a.minZ);
            zs.add(a.maxZ + 1);
        }
        Integer[] x = xs.toArray(new Integer[0]);
        Integer[] z = zs.toArray(new Integer[0]);
        long total = 0;
        for (int i = 0; i + 1 < x.length; i++) {
            for (int j = 0; j + 1 < z.length; j++) {
                int cx = x[i], cz = z[j];
                for (Area a : areas) {
                    if (a.contains(cx, cz)) {
                        total += (long) (x[i + 1] - x[i]) * (z[j + 1] - z[j]);
                        break;
                    }
                }
            }
        }
        return total;
    }

    /** {@link #union} row by row (needed once a circle is involved): merges each row's x ranges. */
    private static long rowUnion(List<Area> areas) {
        int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (Area a : areas) {
            z0 = Math.min(z0, a.minZ);
            z1 = Math.max(z1, a.maxZ);
        }
        long total = 0;
        List<int[]> row = new ArrayList<>(areas.size());
        for (int z = z0; z <= z1; z++) {
            row.clear();
            for (Area a : areas) {
                int[] s = a.span(z);
                if (s != null) row.add(s);
            }
            if (row.isEmpty()) continue;
            row.sort((x, y) -> Integer.compare(x[0], y[0]));
            int from = row.get(0)[0], to = row.get(0)[1];
            for (int i = 1; i < row.size(); i++) {
                int[] s = row.get(i);
                if (s[0] > to + 1) {
                    total += to - from + 1;
                    from = s[0];
                    to = s[1];
                } else {
                    to = Math.max(to, s[1]);
                }
            }
            total += to - from + 1;
        }
        return total;
    }
}
