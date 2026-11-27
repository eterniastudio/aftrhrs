package net.axther.aftrhrs.migrate;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureSpec;
import net.axther.aftrhrs.core.Tasks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Oraxen furniture from the Paper world becomes native furniture blocks.
 *
 * <p>On Paper a piece was a base ItemDisplay ({@code oraxen:furniture = <id>}, yaw = the piece's turn), plus barrier
 * blocks (one per Oraxen barrier cell), LIGHT blocks, Interaction hitbox entities ({@code oraxen:base_entity}) and
 * seat armor stands ({@code oraxen:seat}). Here:
 * <ul>
 *   <li>hitbox and seat entities are removed as they load (seat riders are let off);</li>
 *   <li>a base entity is placed as its block on the next tick ({@link ArcanaContent#placeState}): facing / 16-way
 *       rotation from the yaw ({@code round(yaw / 22.5)}), lit hearth ids keep {@code lit}. The barrier and LIGHT
 *       blocks in its footprint (and LIGHT blocks next to a lit piece) are cleared first; cells that held water stay
 *       waterlogged. Then every {@link Legacy#onFurniture} listener gets the old BukkitValues and the entity goes.</li>
 * </ul>
 * Idempotent: an origin that already holds the piece is not placed again (the entity is just removed). Unknown ids
 * are logged and their entity is kept. A piece whose cells are blocked by something solid drops as its item.
 */
public final class OraxenFurniture {
    private record Job(ServerLevel level, Entity entity, String id, CompoundTag bv, int tries) {
        /** Fountain tiers / spouts go after everything else (they stack on a basin), then bottom-up. */
        int order() {
            return (stacks(id) ? 1 << 20 : 0) + entity.blockPosition().getY();
        }
    }

    private static final Set<UUID> PENDING = new HashSet<>();
    private static final List<Job> QUEUE = new ArrayList<>();
    private static boolean flushScheduled;
    private static final Set<String> UNKNOWN = new HashSet<>();
    private static int converted;

    private OraxenFurniture() {
    }

    static void init() {
        Legacy.converter(OraxenFurniture::convert);
    }

    /** Pieces converted since the server started (for logs / debug commands). */
    public static int converted() {
        return converted;
    }

    /**
     * Only the base entity (an item display, or an item frame for old pieces) is the piece. Oraxen's hitbox
     * Interactions ({@code oraxen:base_entity}) and seat armor stands carry {@code oraxen:furniture = <id>} too; the
     * base itself carries {@code oraxen:seat} (its seat's UUID), so that key never marks a seat.
     */
    static boolean isBase(Entity e) {
        return e instanceof net.minecraft.world.entity.Display.ItemDisplay
                || e instanceof net.minecraft.world.entity.decoration.ItemFrame;
    }

    private static boolean convert(ServerLevel level, Entity e, CompoundTag bv) {
        boolean oraxen = bv.contains("oraxen:furniture") || bv.contains("oraxen:base_entity") || bv.contains("oraxen:seat");
        if (!oraxen) return false;
        // only the entity kinds Oraxen spawned for furniture (never a player or a mob that happens to carry a key)
        boolean part = e instanceof net.minecraft.world.entity.Interaction || e instanceof net.minecraft.world.entity.decoration.ArmorStand
                || e instanceof net.minecraft.world.entity.Display;
        if (!isBase(e) && !part) return false;
        if (!isBase(e) || bv.contains("oraxen:base_entity")) {
            // hitbox / seat: the native piece has its own; let riders off
            e.ejectPassengers();
            e.discard();
            return true;
        }
        String id = bv.getStringOr("oraxen:furniture", "");
        if (id.isEmpty()) return false;
        id = strip(id);
        if (ArcanaContent.stateFor(id) == null) {
            if (UNKNOWN.add(id)) AftrHrs.LOG.warn("Oraxen furniture '{}' has no native block; keeping its entity {} at {}", id, e.getUUID(), e.blockPosition());
            return false;
        }
        // Legacy took the tag off as the entity loaded: put it back until the piece is placed, so an entity that
        // unloads first (or whose neighbor chunks never load in time) is saved with it and converts on its next load
        e.setAttached(net.axther.aftrhrs.core.Pdc.LEGACY, bv.copy());
        if (!PENDING.add(e.getUUID())) return true;
        enqueue(new Job(level, e, id, bv.copy(), 0), 1);
        return true;
    }

    private static void enqueue(Job job, int delay) {
        QUEUE.add(job);
        if (flushScheduled) return;
        flushScheduled = true;
        Tasks.later(delay, OraxenFurniture::flush);
    }

    private static void flush() {
        flushScheduled = false;
        List<Job> jobs = new ArrayList<>(QUEUE);
        QUEUE.clear();
        jobs.sort(java.util.Comparator.comparingInt(Job::order));
        for (Job j : jobs) {
            try {
                attempt(j);
            } catch (RuntimeException ex) {
                PENDING.remove(j.entity().getUUID());
                AftrHrs.LOG.warn("Oraxen furniture {} at {} failed to convert", j.id(), j.entity().blockPosition(), ex);
            }
        }
    }

    /** Pieces Feather stacked into another piece's cell (fountain tiers / spouts in a basin's center). */
    private static boolean stacks(String id) {
        return id.contains("_fountain_tier") || id.contains("_fountain_spout");
    }

    private static boolean free(ServerLevel level, List<BlockPos> cells) {
        for (BlockPos c : cells) {
            BlockState s = level.getBlockState(c);
            if (!s.canBeReplaced() && !s.is(Blocks.BARRIER) && !s.is(Blocks.LIGHT)) return false;
        }
        return true;
    }

    private static void attempt(Job job) {
        ServerLevel level = job.level();
        Entity e = job.entity();
        String id = job.id();
        CompoundTag bv = job.bv();
        int tries = job.tries();
        if (e.isRemoved()) {
            // unloaded again before we got to it: it is still saved and converts on its next load
            PENDING.remove(e.getUUID());
            return;
        }
        BlockPos origin = e.blockPosition();
        float yaw = e.getYRot();
        BlockState target = orient(ArcanaContent.stateFor(id), yaw);
        List<BlockPos> cells = cells(target, origin);
        for (BlockPos c : cells) {
            if (!level.isLoaded(c)) {
                if (tries < 40) enqueue(new Job(level, e, id, bv, tries + 1), 5);
                else PENDING.remove(e.getUUID());
                return;
            }
        }
        PENDING.remove(e.getUUID());
        BlockState there = level.getBlockState(origin);
        if (there.getBlock() == target.getBlock() && (!(there.getBlock() instanceof FurnitureBlock fb) || fb.partIndex(there) == 0)) {
            e.discard(); // converted before (e.g. the block chunk was saved but this entity was not)
            return;
        }
        if (stacks(id) && !free(level, cells)) {
            // Feather put a fountain's first tier (or spout) into the basin's own center: stack it on top instead
            for (int up = 1; up <= 6; up++) {
                List<BlockPos> moved = cells(target, origin.above(up));
                if (free(level, moved)) {
                    origin = origin.above(up);
                    cells = moved;
                    break;
                }
            }
        }
        // clear Oraxen's barrier / LIGHT blocks in the footprint, remembering water
        List<BlockPos> wet = new ArrayList<>();
        for (BlockPos c : cells) {
            BlockState s = level.getBlockState(c);
            boolean water = s.getFluidState().is(Fluids.WATER) && s.getFluidState().isSource();
            if (water) wet.add(c);
            if (s.is(Blocks.BARRIER) || s.is(Blocks.LIGHT)) {
                level.setBlock(c, water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        FurnitureSpec spec = ArcanaContent.spec(id);
        if (spec != null && lit(spec)) clearLightsAround(level, cells);
        // placeState only checks the other parts' cells; never put a piece over a block someone built into its cell
        boolean ok = level.getBlockState(origin).canBeReplaced() && ArcanaContent.placeState(level, origin, target);
        if (!ok) {
            ItemStack drop = ArcanaContent.stack(id, 1);
            if (!drop.isEmpty()) {
                level.addFreshEntity(new ItemEntity(level, origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5, drop));
            }
            AftrHrs.LOG.warn("Oraxen furniture {} at {} {}: cells are blocked; dropped it as an item", id,
                    level.dimension().identifier(), origin.toShortString());
            Legacy.furnitureConverted(level, origin, id, bv, yaw); // listeners find no block and drop their contents
            e.discard();
            return;
        }
        for (BlockPos c : wet) {
            BlockState s = level.getBlockState(c);
            if (s.hasProperty(BlockStateProperties.WATERLOGGED) && !s.getValue(BlockStateProperties.WATERLOGGED)) {
                level.setBlock(c, s.setValue(BlockStateProperties.WATERLOGGED, true), 2);
            }
        }
        converted++;
        Legacy.furnitureConverted(level, origin, id, bv, yaw);
        e.discard();
    }

    /** The piece's state turned the way the Oraxen entity's yaw had it. */
    public static BlockState orient(BlockState s, float yaw) {
        int r16 = Math.floorMod(Math.round(yaw / 22.5f), 16);
        Direction h = switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.WEST;
            default -> Direction.NORTH;
        };
        if (s.hasProperty(FurnitureBlock.FACING)) s = s.setValue(FurnitureBlock.FACING, h);
        if (s.hasProperty(FurnitureBlock.FACING_ALL)) s = s.setValue(FurnitureBlock.FACING_ALL, h);
        if (s.hasProperty(FurnitureBlock.ROTATION)) {
            int step = 1;
            if (s.getBlock() instanceof FurnitureBlock fb) step = Math.max(1, fb.spec.rotStep);
            s = s.setValue(FurnitureBlock.ROTATION, Math.floorMod(Math.round(r16 / (float) step) * step, 16));
        }
        return s;
    }

    private static List<BlockPos> cells(BlockState s, BlockPos origin) {
        if (s.getBlock() instanceof FurnitureBlock fb) return fb.partPositions(s, origin);
        return List.of(origin);
    }

    private static boolean lit(FurnitureSpec spec) {
        for (int l : spec.light) if (l > 0) return true;
        return false;
    }

    /** Oraxen lights could sit next to the piece (the native piece lights its own parts). */
    private static void clearLightsAround(ServerLevel level, List<BlockPos> cells) {
        Set<BlockPos> seen = new HashSet<>(cells);
        for (BlockPos c : cells) {
            for (BlockPos n : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) {
                BlockPos p = n.immutable();
                if (!seen.add(p) || !level.isLoaded(p)) continue;
                BlockState s = level.getBlockState(p);
                if (!s.is(Blocks.LIGHT)) continue;
                boolean water = s.getValue(BlockStateProperties.WATERLOGGED);
                level.setBlock(p, water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
    }

    private static String strip(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }
}
