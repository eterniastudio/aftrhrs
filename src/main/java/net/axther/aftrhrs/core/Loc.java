package net.axther.aftrhrs.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A place in a level: Bukkit's {@code Location} for ported code. Immutable; {@link #add} and friends return copies.
 * Persist with {@link Profiles.Home} (world name + coordinates), never a {@code Loc}: levels come and go.
 */
public record Loc(ServerLevel level, double x, double y, double z, float yaw, float pitch) {

    public Loc(ServerLevel level, double x, double y, double z) {
        this(level, x, y, z, 0f, 0f);
    }

    public static Loc of(Entity e) {
        return new Loc((ServerLevel) e.level(), e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot());
    }

    public static Loc of(ServerLevel level, Vec3 v) {
        return new Loc(level, v.x, v.y, v.z);
    }

    /** The bottom center of a block. */
    public static Loc of(ServerLevel level, BlockPos pos) {
        return new Loc(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    public Vec3 vec() {
        return new Vec3(x, y, z);
    }

    public BlockPos blockPos() {
        return BlockPos.containing(x, y, z);
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public Loc add(double dx, double dy, double dz) {
        return new Loc(level, x + dx, y + dy, z + dz, yaw, pitch);
    }

    public Loc withRotation(float yaw, float pitch) {
        return new Loc(level, x, y, z, yaw, pitch);
    }

    public Loc withLevel(ServerLevel l) {
        return new Loc(l, x, y, z, yaw, pitch);
    }

    /** Squared distance, or {@link Double#MAX_VALUE} across levels. */
    public double distanceSquared(Loc o) {
        if (o == null || o.level != level) return Double.MAX_VALUE;
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean sameLevel(Loc o) {
        return o != null && o.level == level;
    }

    /** "x, y, z" block coordinates (Feather's {@code coords}). */
    public String coords() {
        return blockX() + ", " + blockY() + ", " + blockZ();
    }

    /** The Paper-style world name ("world", "world_nether", ...) of the level. */
    public String worldName() {
        return Worlds.name(level);
    }
}
