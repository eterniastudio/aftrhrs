package net.axther.aftrhrs.entity;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * The MythicMobs conditions the creature skills use (night, outside, raining, biome groups, worldtime ...), with the
 * same meaning as on the Paper server.
 */
public final class Conditions {
    /** gen_mm.py COLD_BIOMES / HOT_BIOMES (the mood brain's idle_cold / idle_hot). */
    static final Set<String> COLD = Set.of("snowy_plains", "snowy_taiga", "snowy_slopes", "snowy_beach", "ice_spikes", "frozen_peaks",
            "jagged_peaks", "grove", "frozen_river", "frozen_ocean", "deep_frozen_ocean");
    static final Set<String> HOT = Set.of("desert", "badlands", "eroded_badlands", "wooded_badlands", "savanna", "savanna_plateau",
            "windswept_savanna", "nether_wastes", "basalt_deltas", "crimson_forest", "warped_forest", "soul_sand_valley");

    private Conditions() {
    }

    /** Time of day 0..23999 (MythicMobs worldtime). */
    public static long time(Level level) {
        return Math.floorMod(level.getDefaultClockTime(), 24000L);
    }

    public static boolean timeIn(Level level, long from, long to) {
        long t = time(level);
        return from <= to ? t >= from && t <= to : t >= from || t <= to;
    }

    /** MythicMobs {@code night}: 13000..23000. */
    public static boolean night(Level level) {
        return !level.dimensionType().hasFixedTime() && timeIn(level, 13000, 23000);
    }

    public static boolean day(Level level) {
        return !level.dimensionType().hasFixedTime() && !night(level);
    }

    /** MythicMobs {@code dawn}: 22000..0 (the last hour before sunrise). */
    public static boolean dawn(Level level) {
        return !level.dimensionType().hasFixedTime() && timeIn(level, 22000, 23999);
    }

    /** Open sky above (MythicMobs {@code outside}). */
    public static boolean outside(Entity e) {
        return e.level().canSeeSky(BlockPos.containing(e.getX(), e.getEyeY(), e.getZ()));
    }

    public static boolean rainingOn(Entity e) {
        return e.level().isRainingAt(BlockPos.containing(e.getX(), e.getEyeY(), e.getZ())) || e.level().isRaining() && outside(e);
    }

    public static boolean thunderOutside(Entity e) {
        return e.level().isThundering() && outside(e);
    }

    public static boolean inBiome(Entity e, Set<String> paths) {
        Holder<Biome> b = e.level().getBiome(e.blockPosition());
        return b.unwrapKey().map(ResourceKey::identifier).map(id -> paths.contains(id.getPath())).orElse(false);
    }

    public static boolean cold(Entity e) {
        return inBiome(e, COLD);
    }

    public static boolean hot(Entity e) {
        return inBiome(e, HOT);
    }

    public static boolean inWater(Entity e) {
        return e.isInWater() || e.level().getFluidState(e.blockPosition()).is(Fluids.WATER);
    }

    /** Below y=56 with no sky (Cairnling's pebble miner). */
    public static boolean underground(Entity e) {
        return !outside(e) && e.getY() < 56;
    }

    /** An untamed monster within {@code r} blocks (MythicMobs {@code ismonster} + {@code hasowner false}). */
    public static boolean monsterNear(Entity e, double r) {
        return !nearby(e, r, LivingEntity.class, x -> isWildMonster(x)).isEmpty();
    }

    public static boolean isWildMonster(Entity x) {
        if (!(x instanceof Enemy) || !x.isAlive()) return false;
        return !(x instanceof OwnableEntity o) || o.getOwnerReference() == null;
    }

    public static <T extends Entity> List<T> nearby(Entity e, double r, Class<T> type, Predicate<? super T> filter) {
        AABB box = e.getBoundingBox().inflate(r);
        double r2 = r * r;
        return e.level().getEntitiesOfClass(type, box, x -> x != e && x.distanceToSqr(e) <= r2 && filter.test(x));
    }

    public static <T extends Entity> List<T> nearby(Level level, net.minecraft.world.phys.Vec3 at, double r, Class<T> type, Predicate<? super T> filter) {
        AABB box = new AABB(at, at).inflate(r);
        double r2 = r * r;
        return level.getEntitiesOfClass(type, box, x -> x.distanceToSqr(at) <= r2 && filter.test(x));
    }

    /** The block the creature stands in or on is a bed (Luna's idle_bed / knead spot). */
    public static boolean onBed(Entity e) {
        BlockState at = e.level().getBlockState(e.blockPosition());
        BlockState below = e.level().getBlockState(e.blockPosition().below());
        return at.is(BlockTags.BEDS) || below.is(BlockTags.BEDS);
    }

    public static boolean onSoftSpot(Entity e) {
        BlockState at = e.level().getBlockState(e.blockPosition());
        BlockState below = e.level().getBlockState(e.blockPosition().below());
        return at.is(BlockTags.BEDS) || at.is(BlockTags.WOOL_CARPETS) || below.is(BlockTags.BEDS) || below.is(BlockTags.WOOL);
    }

    /** In a chest block (Kevin's idle_box: a chest is shorter than a block, so he stands inside it). */
    public static boolean inChestBlock(Entity e) {
        BlockState at = e.level().getBlockState(e.blockPosition());
        return at.is(Blocks.CHEST) || at.is(Blocks.TRAPPED_CHEST) || at.is(Blocks.ENDER_CHEST);
    }

    public static boolean onContainer(Entity e) {
        BlockState below = e.level().getBlockState(e.blockPosition().below());
        return below.is(Blocks.BARREL) || below.is(Blocks.CHEST) || below.is(Blocks.TRAPPED_CHEST) || below.is(Blocks.ENDER_CHEST);
    }

    /** On stairs or a slab (Kitty's idle_lounge: the couch armrest). The creature stands in the block or on top of it. */
    public static boolean onSeat(Entity e) {
        BlockState at = e.level().getBlockState(e.blockPosition());
        BlockState below = e.level().getBlockState(e.blockPosition().below());
        return at.is(BlockTags.STAIRS) || at.is(BlockTags.SLABS) || below.is(BlockTags.STAIRS) || below.is(BlockTags.SLABS);
    }

    /** On a carpet (Luna But The Second One's idle_shoes: loafed among your shoes in the hall). */
    public static boolean onCarpet(Entity e) {
        BlockState at = e.level().getBlockState(e.blockPosition());
        BlockState below = e.level().getBlockState(e.blockPosition().below());
        return at.is(BlockTags.WOOL_CARPETS) || below.is(BlockTags.WOOL_CARPETS);
    }

    /** On a lectern, bookshelf or cartography table (Clover's idle_paperwork: sitting on your papers). */
    public static boolean onPaperwork(Entity e) {
        BlockState at = e.level().getBlockState(e.blockPosition());
        BlockState below = e.level().getBlockState(e.blockPosition().below());
        return isPaperwork(at) || isPaperwork(below);
    }

    private static boolean isPaperwork(BlockState s) {
        return s.is(Blocks.LECTERN) || s.is(Blocks.BOOKSHELF) || s.is(Blocks.CHISELED_BOOKSHELF) || s.is(Blocks.CARTOGRAPHY_TABLE);
    }

    /** On grass with a flower within 3 blocks (Clover's idle_meadow: loafing in the clover). */
    public static boolean inMeadow(Entity e) {
        BlockState below = e.level().getBlockState(e.blockPosition().below());
        return below.is(Blocks.GRASS_BLOCK) && blockNear(e, 3, 1, s -> s.is(BlockTags.FLOWERS));
    }

    /** Kevin's sunbeam: outdoors, 1000..11000, not raining. */
    public static boolean sunbeam(Entity e) {
        return outside(e) && timeIn(e.level(), 1000, 11000) && !e.level().isRaining();
    }

    /**
     * A block matching {@code test} within {@code r} blocks horizontally and {@code ry} vertically (a box, edges
     * included). Never loads chunks: a chunk that is not loaded counts as not found. Skips sections whose palette cannot
     * hold a match ({@link #scanBox}).
     */
    public static boolean blockNear(Entity e, int r, int ry, Predicate<BlockState> test) {
        BlockPos c = e.blockPosition();
        return scanBox(e.level(), c.getX() - r, c.getY() - ry, c.getZ() - r, c.getX() + r, c.getY() + ry, c.getZ() + r, test, p -> true);
    }

    /**
     * Visits the positions in the box [x0..x1] x [y0..y1] x [z0..z1] (inclusive) whose state matches {@code test} and
     * stops as soon as {@code hit} returns true (the result is then true). Walks chunk by chunk and section by section:
     * chunks that are not loaded are skipped (never loads one; {@code getChunkNow}), and so are sections whose palette
     * cannot contain a match ({@code maybeHas}, which also covers all-air sections). The position passed to {@code hit}
     * is mutable: copy it ({@code immutable()}) to keep it.
     */
    public static boolean scanBox(Level lvl, int x0, int y0, int z0, int x1, int y1, int z1,
                                  Predicate<BlockState> test, Predicate<BlockPos.MutableBlockPos> hit) {
        y0 = Math.max(y0, lvl.getMinY());
        y1 = Math.min(y1, lvl.getMaxY());
        if (y0 > y1 || x0 > x1 || z0 > z1) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                LevelChunk ch = lvl.getChunkSource().getChunkNow(cx, cz);   // null = not loaded: skip, never load
                if (ch == null) continue;
                LevelChunkSection[] secs = ch.getSections();
                int xa = Math.max(x0, cx << 4), xb = Math.min(x1, (cx << 4) + 15);
                int za = Math.max(z0, cz << 4), zb = Math.min(z1, (cz << 4) + 15);
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int si = ch.getSectionIndexFromSectionY(sy);
                    if (si < 0 || si >= secs.length) continue;
                    LevelChunkSection s = secs[si];
                    if (s == null || !s.maybeHas(test)) continue;          // palette check
                    int ya = Math.max(y0, sy << 4), yb = Math.min(y1, (sy << 4) + 15);
                    for (int y = ya; y <= yb; y++) {
                        for (int z = za; z <= zb; z++) {
                            for (int x = xa; x <= xb; x++) {
                                if (test.test(s.getBlockState(x & 15, y & 15, z & 15)) && hit.test(m.set(x, y, z))) return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    public static boolean isMob(Entity e) {
        return e instanceof Mob;
    }
}
