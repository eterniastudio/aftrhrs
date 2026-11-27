package net.axther.aftrhrs.feature.graves;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Loc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds where a grave may stand near a death. A spot is a block that is air or a small replaceable plant/snow layer
 * (water only as a last resort), with a solid floor that isn't a grave, no lava touching it, inside the world border,
 * in a loaded chunk, under the Nether roof, outside the keep-clear zones (spawn protection, Feather's spawn, bank
 * chests, boss altar fights) and never in a claim the dead player can't build in. Searched around each anchor in turn
 * (the death spot, a point just outside a keep-clear zone the death was in, just past the edge of a claim the death
 * was in, the player's last spot on solid ground); the closest dry spot wins. The block the player died in comes after
 * its neighbors, so whatever else drops there (a bounty head) isn't shoved out by the grave.
 */
final class GraveSpot {
    /** A circle (in x/z) graves keep out of. */
    record Zone(ServerLevel level, double x, double z, double radius) {
        boolean contains(Loc l) {
            if (l.level() != level) return false;
            double dx = l.x() - x, dz = l.z() - z;
            return dx * dx + dz * dz <= radius * radius;
        }

        /** A point just outside the zone, on the line from its center through {@code l}. */
        Loc outside(Loc l) {
            double dx = l.x() - x, dz = l.z() - z;
            double len = Math.hypot(dx, dz);
            if (len < 0.01) {
                dx = 1;
                dz = 0;
                len = 1;
            }
            double r = radius + 3;
            return new Loc(level, x + dx / len * r, l.y(), z + dz / len * r);
        }
    }

    private final int radius;
    private final List<Zone> zones;
    private final Predicate<BlockPos> isGrave;
    /** Where the dead player may build (claims): graves only go there. */
    private final Predicate<Loc> mayBuild;

    GraveSpot(int radius, List<Zone> zones, Predicate<BlockPos> isGrave, Predicate<Loc> mayBuild) {
        this.radius = radius;
        this.zones = zones;
        this.isGrave = isGrave;
        this.mayBuild = mayBuild;
    }

    /** The best block for a grave, or null when there is none. */
    BlockPos find(Loc death, Loc lastGround) {
        ServerLevel w = death.level();
        List<Loc> anchors = new ArrayList<>();
        anchors.add(death);
        for (Zone z : zones) if (z.contains(death)) anchors.add(z.outside(death));
        // Died in a claim they can't build in, deeper than the search reaches: also try just outside its edge.
        if (!mayBuild.test(death)) {
            Loc edge = edge(death);
            if (edge != null) anchors.add(edge);
        }
        if (lastGround != null && lastGround.level() == w && lastGround.distanceSquared(death) > 4) anchors.add(lastGround);
        BlockPos wet = null;
        for (Loc a : anchors) {
            BlockPos[] best = search(w, a, a == anchors.get(0));
            if (best[0] != null) return best[0];
            if (wet == null) wet = best[1];
        }
        return wet;
    }

    /**
     * The closest loaded spot (in 8 directions, 4 blocks at a time, up to 160 blocks) where the player may build and no
     * keep-clear zone is: just past the edge of the claim they died in. Null when there is none.
     */
    private Loc edge(Loc death) {
        ServerLevel w = death.level();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int d = 4; d <= 160; d += 4) {
            for (int[] dir : dirs) {
                double step = dir[0] != 0 && dir[1] != 0 ? d / Math.sqrt(2) : d;
                Loc l = new Loc(w, death.x() + dir[0] * step, death.y(), death.z() + dir[1] * step);
                if (!w.hasChunk(l.blockX() >> 4, l.blockZ() >> 4) || !w.getWorldBorder().isWithinBounds(l.x(), l.z())) continue;
                if (!mayBuild.test(l)) continue;
                boolean clear = true;
                for (Zone z : zones) if (z.contains(l)) clear = false;
                if (clear) return l;
            }
        }
        return null;
    }

    /** [closest dry spot, closest water spot] around one anchor. */
    private BlockPos[] search(ServerLevel w, Loc a, boolean deathSpot) {
        int top = maxY(w);
        int bottom = w.getMinY() + 1;
        int ax = a.blockX(), az = a.blockZ();
        int ay = a.blockY();
        if (ay < bottom) {
            // Below the world (the void): start from the ground of that column, if it has any.
            if (!w.hasChunk(ax >> 4, az >> 4)) return new BlockPos[2];
            int h = w.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ax, az) - 1;
            if (h < w.getMinY() || w.getBlockState(new BlockPos(ax, h, az)).isAir()) return new BlockPos[2];
            ay = h + 1;
        }
        ay = Math.min(ay, top);
        BlockPos dry = null, wet = null;
        double dryScore = Double.MAX_VALUE, wetScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = ax + dx, z = az + dz;
                if (!w.hasChunk(x >> 4, z >> 4)) continue;
                int lo = Math.max(bottom, ay - 48), hi = Math.min(top, ay + 16);
                for (int y = lo; y <= hi; y++) {
                    double score = dx * dx + dz * dz + (double) (y - ay) * (y - ay) + (deathSpot && dx == 0 && dz == 0 ? 2.5 : 0);
                    if (score >= dryScore && score >= wetScore) continue;
                    m.set(x, y, z);
                    int kind = kind(w, m);
                    if (kind == 1 && score < dryScore) {
                        dryScore = score;
                        dry = m.immutable();
                    } else if (kind == 2 && score < wetScore) {
                        wetScore = score;
                        wet = m.immutable();
                    }
                }
                // A death far above the ground (a long fall, mid-air): also try the surface of the column.
                int h = w.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if ((h > hi && h <= top) || (h < lo && h > bottom)) {
                    double score = dx * dx + dz * dz + (double) (h - ay) * (h - ay);
                    m.set(x, h, z);
                    if (kind(w, m) == 1 && score < dryScore) {
                        dryScore = score;
                        dry = m.immutable();
                    }
                }
            }
        }
        return new BlockPos[]{dry, wet};
    }

    /** The highest y a grave may use: under the roof in worlds with a ceiling (the Nether). */
    private static int maxY(ServerLevel w) {
        int top = w.getMaxY() - 1;
        if (w.dimensionType().hasCeiling()) top = Math.min(top, w.getMinY() + w.dimensionType().logicalHeight() - 6);
        return top;
    }

    /** 0 = no, 1 = a dry spot, 2 = a water spot. */
    private int kind(ServerLevel w, BlockPos b) {
        BlockState s = w.getBlockState(b);
        int kind = replaceable(s);
        if (kind == 0 || isGrave.test(b)) return 0;
        BlockPos floorPos = b.below();
        BlockState floor = w.getBlockState(floorPos);
        if (!floor.isSolid() || isGrave.test(floorPos)) return 0;
        if (floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE)) return 0;
        for (Direction f : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockState n = w.getBlockState(b.relative(f));
            if (n.is(Blocks.LAVA) || n.is(Blocks.FIRE) || n.is(Blocks.SOUL_FIRE)) return 0;
        }
        Loc at = new Loc(w, b.getX() + 0.5, b.getY(), b.getZ() + 0.5);
        if (!w.getWorldBorder().isWithinBounds(at.x(), at.z())) return 0;
        for (Zone zone : zones) if (zone.contains(at)) return 0;
        if (!mayBuild.test(at)) return 0;
        return kind;
    }

    /** Blocks a grave may take the place of: 1 = air or a small plant/snow layer, 2 = water, 0 = never. */
    static int replaceable(BlockState s) {
        if (s.is(Blocks.AIR) || s.is(Blocks.CAVE_AIR)) return 1;
        if (s.is(Blocks.WATER)) return 2;
        if (s.is(Blocks.VOID_AIR) || s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
                || s.is(Blocks.BUBBLE_COLUMN) || s.is(Blocks.LIGHT) || s.is(Blocks.STRUCTURE_VOID)) return 0;
        if (!s.is(BlockTags.REPLACEABLE)) return 0;
        // Never half of a two-block plant (taking one half would break the other).
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return 0;
        if (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED)) return 0;
        return 1;
    }

    /** Vanilla spawn protection around the overworld spawn (only while there are ops, like vanilla). */
    static Zone vanillaSpawn() {
        MinecraftServer server = Feather.serverOrNull();
        if (!(server instanceof DedicatedServer ds)) return null;
        int r = ds.spawnProtectionRadius();
        if (r <= 0 || server.getPlayerList().getOps().isEmpty()) return null;
        ServerLevel w = server.overworld();
        BlockPos s = w.getRespawnData().pos();
        // The protected area is a square; the circle around it covers its corners.
        return new Zone(w, s.getX() + 0.5, s.getZ() + 0.5, (r + 1) * Math.sqrt(2));
    }
}
