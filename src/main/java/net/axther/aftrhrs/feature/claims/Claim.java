package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.ClaimAction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A claim: an owner (null for an admin claim), a name, one world, a set of touching {@link Area}s, who is trusted how
 * far, and its flags. Admin claims also have allow lists: what anyone may still use or break there.
 */
final class Claim {
    final int id;
    UUID owner;
    String ownerName;
    String name;
    final String world;
    /** Replaced as a whole (never changed in place), so other threads can read it safely. */
    volatile List<Area> areas = List.of();
    final Map<UUID, TrustLevel> trust = new LinkedHashMap<>();
    final Map<UUID, String> trustNames = new LinkedHashMap<>();
    /** What everyone gets here; null = nothing. */
    TrustLevel publicTrust;
    final EnumMap<ClaimFlag, Boolean> flags = new EnumMap<>(ClaimFlag.class);
    /** Admin claims: block types (lower-case keys), {@code #tag}s or {@code bank-chest} anyone may use / break. */
    final Set<String> allowUse = new LinkedHashSet<>();
    final Set<String> allowBreak = new LinkedHashSet<>();
    /** Admin claims: shown instead of "Entering Spawn" (MiniMessage); empty = the default line. */
    String greeting = "";
    long created;
    /** Columns covered (overlaps once) and what that costs in claim chunks (every started chunk); set by {@link #setAreas}. */
    long area;
    long cost;
    /** Index keys (chunk keys) this claim is filed under. */
    final Set<Long> indexed = new java.util.HashSet<>();
    boolean removed;

    Claim(int id, UUID owner, String ownerName, String name, String world) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.name = name;
        this.world = world;
        this.created = System.currentTimeMillis();
    }

    boolean admin() {
        return owner == null;
    }

    /** Same owner for cross-border rules: null = the wilderness, "admin" for every admin claim, else the owner's id. */
    static String ownerKey(Claim c) {
        if (c == null) return null;
        return c.owner == null ? "admin" : c.owner.toString();
    }

    static boolean sameOwner(Claim a, Claim b) {
        return a == b || java.util.Objects.equals(ownerKey(a), ownerKey(b));
    }

    boolean contains(int x, int z) {
        for (Area a : areas) if (a.contains(x, z)) return true;
        return false;
    }

    boolean flag(ClaimFlag f) {
        Boolean b = flags.get(f);
        return b != null ? b : f.def;
    }

    /** The trust this player has here (owner = MANAGER plus owner-only rights); null = none. */
    TrustLevel levelOf(UUID player) {
        if (player == null) return publicTrust;
        if (player.equals(owner)) return TrustLevel.MANAGER;
        TrustLevel t = trust.get(player);
        if (publicTrust != null && (t == null || publicTrust.ordinal() > t.ordinal())) return publicTrust;
        return t;
    }

    boolean allows(UUID player, ClaimAction action) {
        if (player != null && player.equals(owner)) return true;
        TrustLevel t = levelOf(player);
        return t != null && t.allows(action);
    }

    boolean isOwner(UUID player) {
        return player != null && player.equals(owner);
    }

    /** Sets the areas (dropping any that another area already covers) and recomputes the cost. */
    void setAreas(List<Area> list) {
        List<Area> copy = new ArrayList<>(list);
        for (int i = copy.size() - 1; i >= 0; i--) {
            Area a = copy.get(i);
            for (int j = 0; j < copy.size(); j++) {
                if (j != i && copy.get(j).containsArea(a) && !(a.equals(copy.get(j)) && j > i)) {
                    copy.remove(i);
                    break;
                }
            }
        }
        areas = List.copyOf(copy);
        area = Area.union(areas);
        cost = Area.chunks(area);
    }

    /** The middle of the claim's bounding box, for lists ("x, z"). */
    int[] center() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Area a : areas) {
            minX = Math.min(minX, a.minX());
            minZ = Math.min(minZ, a.minZ());
            maxX = Math.max(maxX, a.maxX());
            maxZ = Math.max(maxZ, a.maxZ());
        }
        return new int[]{(minX + maxX) / 2, (minZ + maxZ) / 2};
    }

    int chunkAreas() {
        int n = 0;
        for (Area a : areas) if (a.chunk()) n++;
        return n;
    }

    int roundAreas() {
        int n = 0;
        for (Area a : areas) if (a.round()) n++;
        return n;
    }
}
