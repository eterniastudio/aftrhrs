package net.axther.aftrhrs.feature.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * How Storage Vault blocks join up. Vault blocks touching each other and in the same "zone" form a group (a zone is
 * one claim, or, outside claims, one owner's blocks: a vault never reaches across a claim's edge or into a stranger's
 * blocks, where a hopper or terminal on the far side could empty it). The group is split into upright boxes, from the
 * bottom: at its lowest block, of every footprint up to {@link #MAX_WIDTH}x{@link #MAX_WIDTH} that fits there, the one
 * that makes the biggest box going up (as high as it stays whole, up to the build limit) wins. Each block learns its
 * vault's controller (the box's lowest corner) and the controller the box's size; each block's state says which
 * neighbors are in the same vault (for the connected textures). The contents gather at each new controller.
 */
final class VaultForm {
    static final int MAX_WIDTH = 3;
    /** A group bigger than this (in loaded blocks) is formed only this far around the change. 3x3 up a whole world fits. */
    static final int MAX_GROUP = 9 * 4096;

    private VaultForm() {
    }

    /** Re-forms the vault groups that hold these positions (a block placed, or the neighbors of one broken). */
    static void reform(ServerLevel level, Collection<BlockPos> seeds) {
        Set<BlockPos> done = new HashSet<>();
        List<StorageVaultBE> touched = new ArrayList<>();
        for (BlockPos seed : seeds) {
            if (done.contains(seed)) continue;
            BlockState s = level.getBlockState(seed);
            if (!(s.getBlock() instanceof StorageVaultBlock)) continue;
            form(level, group(level, seed, done), touched);
        }
        StorageVaultBE.shapes++;
        // Contents of blocks that no longer lead their vault move to the one that does.
        for (StorageVaultBE be : touched) be.controllerBE();
        // Only now every block knows its vault: the "who changed what" copies start again from the new shapes.
        StorageLog.reshaped(touched);
    }

    /**
     * Whether two touching vault blocks may be one vault: both in the same claim, or both outside claims and placed by
     * the same player (or both by commands). Claims are the claims module's (none when it is off).
     */
    static boolean sameZone(ServerLevel level, BlockPos a, BlockPos b) {
        net.axther.aftrhrs.core.Feather plugin = net.axther.aftrhrs.core.Feather.get();
        if (plugin != null) {
            net.axther.aftrhrs.core.Loc la = net.axther.aftrhrs.core.Loc.of(level, a), lb = net.axther.aftrhrs.core.Loc.of(level, b);
            boolean ca = plugin.claimed(la), cb = plugin.claimed(lb);
            if (ca || cb) return ca && cb && plugin.sameClaim(la, lb);
        }
        UUID oa = level.getBlockEntity(a) instanceof StorageVaultBE va ? va.owner() : null;
        UUID ob = level.getBlockEntity(b) instanceof StorageVaultBE vb ? vb.owner() : null;
        return java.util.Objects.equals(oa, ob);
    }

    /** The vault blocks connected to {@code seed} and in its zone (loaded chunks only). */
    private static List<BlockPos> group(ServerLevel level, BlockPos seed, Set<BlockPos> done) {
        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        done.add(seed.immutable());
        while (!queue.isEmpty() && out.size() < MAX_GROUP) {
            BlockPos p = queue.poll();
            out.add(p);
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (done.contains(n) || !level.isLoaded(n)) continue;
                if (!(level.getBlockState(n).getBlock() instanceof StorageVaultBlock)) continue;
                if (!sameZone(level, seed, n)) continue;
                done.add(n);
                queue.add(n);
            }
        }
        return out;
    }

    private record Box(BlockPos corner, int sx, int sz, int h) {
        long volume() {
            return (long) sx * sz * h;
        }
    }

    private static void form(ServerLevel level, List<BlockPos> group, List<StorageVaultBE> touched) {
        Set<BlockPos> left = new HashSet<>(group);
        List<BlockPos> order = new ArrayList<>(group);
        // Lowest first, then by X, then by Z: each start is the bottom of what's left.
        order.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
        for (BlockPos start : order) {
            if (!left.contains(start)) continue;
            Box best = new Box(start, 1, 1, height(start, 1, 1, left));
            // Every footprint up to 3x3 that holds the start block (it may sit anywhere in it): the biggest box wins,
            // then the wider footprint (a whole 3x3 tower beats a taller thin one beside it only when it is bigger).
            for (int sx = 1; sx <= MAX_WIDTH; sx++) {
                for (int sz = 1; sz <= MAX_WIDTH; sz++) {
                    for (int ox = 0; ox < sx; ox++) {
                        for (int oz = 0; oz < sz; oz++) {
                            BlockPos corner = start.offset(-ox, 0, -oz);
                            int h = height(corner, sx, sz, left);
                            if (h == 0) continue;
                            Box b = new Box(corner, sx, sz, h);
                            if (b.volume() > best.volume() || b.volume() == best.volume() && sx * sz > best.sx * best.sz) best = b;
                        }
                    }
                }
            }
            Set<BlockPos> vault = new HashSet<>();
            for (int y = 0; y < best.h; y++) {
                for (int x = 0; x < best.sx; x++) for (int z = 0; z < best.sz; z++) vault.add(best.corner.offset(x, y, z));
            }
            left.removeAll(vault);
            apply(level, best, vault, touched);
        }
    }

    /** How many whole sx x sz layers there are going up from {@code corner} among the free blocks (0: not even one). */
    private static int height(BlockPos corner, int sx, int sz, Set<BlockPos> left) {
        int h = 0;
        while (true) {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) if (!left.contains(corner.offset(x, h, z))) return h;
            }
            h++;
        }
    }

    private static void apply(ServerLevel level, Box box, Set<BlockPos> vault, List<StorageVaultBE> touched) {
        for (BlockPos p : vault) {
            BlockState s = level.getBlockState(p);
            BlockState linked = s;
            for (Direction d : Direction.values()) linked = linked.setValue(StorageVaultBlock.LINK.get(d), vault.contains(p.relative(d)));
            if (linked != s) level.setBlock(p, linked, Block.UPDATE_CLIENTS);
            if (level.getBlockEntity(p) instanceof StorageVaultBE be) {
                be.setShape(box.corner, box.sx, box.h, box.sz);
                touched.add(be);
            }
        }
    }
}
