package net.axther.aftrhrs.feature.civic;

import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.Coast;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hushwing: while a player's Hushwing (a pet of theirs that follows them) is within the radius, the vibrations they
 * cause (steps, blocks, projectiles they shot, the boat or mount they ride) never reach sculk sensors, calibrated
 * sensors or shriekers ({@code FeatherEvents.VIBRATION}, fired before a listener takes a game event).
 */
final class Hushwing {
    private final CivicModule m;
    private boolean enabled, silenceSelf;
    private String mob = "ArcanaHushwing";
    private double radius = 8;
    /** player -> [tick checked, 1/0]: one pet lookup per player per second at most. */
    private final Map<UUID, long[]> cache = new HashMap<>();

    Hushwing(CivicModule m) {
        this.m = m;
    }

    void load(Yaml c) {
        enabled = c.getBoolean("enabled", true);
        mob = c.getString("mob", "ArcanaHushwing");
        radius = Math.max(1, c.getDouble("radius", 8));
        silenceSelf = c.getBoolean("silence-self", true);
        cache.clear();
    }

    /** False when a sensor or shrieker must ignore this vibration. */
    boolean allow(VibrationSystem receiver, GameEvent.Context context) {
        if (!enabled || !(receiver instanceof BlockEntity be)) return true;
        BlockState st = be.getBlockState();
        if (!st.is(Blocks.SCULK_SENSOR) && !st.is(Blocks.CALIBRATED_SCULK_SENSOR) && !st.is(Blocks.SCULK_SHRIEKER)) return true;
        Entity src = context.sourceEntity();
        if (src == null) return true;
        EntityType<?> type = Coast.creatureType(mob);
        if (silenceSelf && type != null && src.getType() == type) return false;
        ServerPlayer p = playerOf(src);
        return p == null || !silenced(p);
    }

    private static ServerPlayer playerOf(Entity e) {
        if (e instanceof ServerPlayer p) return p;
        if (e instanceof Projectile pr && pr.getOwner() instanceof ServerPlayer p) return p;
        for (Entity passenger : e.getPassengers()) if (passenger instanceof ServerPlayer p) return p;
        return null;
    }

    boolean silenced(ServerPlayer p) {
        if (!enabled) return false;
        long now = p.level().getGameTime();
        long[] c = cache.get(p.getUUID());
        if (c != null && now - c[0] >= 0 && now - c[0] < 20) return c[1] == 1;
        boolean yes = !Coast.followingNearby(p, mob, radius).isEmpty();
        if (cache.size() > 512) cache.clear();
        cache.put(p.getUUID(), new long[]{now, yes ? 1 : 0});
        return yes;
    }
}
