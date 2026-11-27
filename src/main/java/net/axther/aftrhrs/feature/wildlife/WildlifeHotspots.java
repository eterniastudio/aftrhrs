package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.core.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fishing hotspots: a fed Squallgull that follows a player's boat dives at a patch of open water every minute; the
 * patch stays a hotspot for {@code hotspots.seconds}. The fishing module reads it through {@link Hooks.WildlifeHotspots}
 * ({@code feather.service(Hooks.WildlifeHotspots.class)}) when a fish bites (wildlife.yml {@code hotspots.*}).
 *
 * <p>A handful of spots at most, checked by squared distance in the same level; expired spots are dropped as they are
 * read. Safe from any thread.
 */
public final class WildlifeHotspots implements Hooks.WildlifeHotspots {
    record Spot(String level, double x, double y, double z, double radius, long until) {
        boolean covers(String lvl, Vec3 p, long now) {
            if (now >= until || !level.equals(lvl)) return false;
            double dx = p.x - x, dz = p.z - z, dy = p.y - y;
            return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= Math.max(4, radius);
        }
    }

    static final WildlifeHotspots INSTANCE = new WildlifeHotspots();
    private static final List<Spot> SPOTS = new CopyOnWriteArrayList<>();
    private static volatile double bite = 1.25, rare = 1.3;
    private static final int MAX = 64;

    private WildlifeHotspots() {
    }

    static String key(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    @Override
    public boolean isHotspot(ServerLevel level, Vec3 pos) {
        return isHotspot(key(level), pos);
    }

    static boolean isHotspot(String level, Vec3 pos) {
        if (pos == null || SPOTS.isEmpty()) return false;
        long now = System.currentTimeMillis();
        boolean expired = false;
        for (Spot s : SPOTS) {
            if (s.until() <= now) {
                expired = true;
                continue;
            }
            if (s.covers(level, pos, now)) return true;
        }
        if (expired) SPOTS.removeIf(s -> s.until() <= now);
        return false;
    }

    @Override
    public double biteSpeedMultiplier() {
        return bite;
    }

    @Override
    public double rareWeightMultiplier() {
        return rare;
    }

    static void configure(double biteMultiplier, double rareMultiplier) {
        bite = Math.max(1, biteMultiplier);
        rare = Math.max(1, rareMultiplier);
    }

    static void add(ServerLevel level, Vec3 center, double radius, long until) {
        long now = System.currentTimeMillis();
        SPOTS.removeIf(s -> s.until() <= now);
        while (SPOTS.size() >= MAX) SPOTS.remove(0);
        SPOTS.add(new Spot(key(level), center.x, center.y, center.z, radius, until));
    }

    /** Live spots (for particles and /wildlife hotspot). */
    static List<Spot> active() {
        long now = System.currentTimeMillis();
        List<Spot> out = new ArrayList<>();
        for (Spot s : SPOTS) if (s.until() > now) out.add(s);
        return out;
    }

    static void clear() {
        SPOTS.clear();
    }
}
