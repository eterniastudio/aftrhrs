package net.axther.aftrhrs.feature.coast;

import net.axther.aftrhrs.content.ArcanaContent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where certain furniture pieces stand in loaded chunks (the origin block of each), so timers and effects can visit
 * them without scanning: fountains, tanks, scales, seaweed lines. A chunk that loads is scanned only in sections whose
 * palette may hold one of the blocks; placing and removing keep it current. Server thread only. The owner feeds it
 * chunk load/unload and placed/removed calls. Owners that track live state per piece keep that state a subset of the
 * index (they {@link #add} whatever they track), so an unload can drop exactly the set {@link #chunkUnloaded} returns.
 */
public final class BlockIndex {
    private final Predicate<Block> wanted;
    private final Map<ResourceKey<Level>, Map<Long, Set<BlockPos>>> levels = new HashMap<>();

    public BlockIndex(Predicate<Block> wanted) {
        this.wanted = wanted;
    }

    public void clear() {
        levels.clear();
    }

    /** Scans a loaded chunk; true when it holds at least one indexed origin (see {@link #inChunk}). */
    public boolean chunkLoaded(ServerLevel level, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos cp = chunk.getPos();
        Set<BlockPos> found = null;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> wanted.test(st.getBlock()))) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = s.getBlockState(x, y, z);
                        if (!wanted.test(st.getBlock())) continue;
                        BlockPos pos = new BlockPos(cp.getMinBlockX() + x, baseY + y, cp.getMinBlockZ() + z);
                        if (!ArcanaContent.origin(st, pos).equals(pos)) continue;
                        if (found == null) found = new LinkedHashSet<>();
                        found.add(pos);
                    }
                }
            }
        }
        if (found == null) {
            Map<Long, Set<BlockPos>> map = levels.get(level.dimension());
            if (map != null) map.remove(cp.pack());
            return false;
        }
        levels.computeIfAbsent(level.dimension(), k -> new HashMap<>()).put(cp.pack(), found);
        return true;
    }

    /** Forgets a chunk; returns the origins it held (empty if none). The caller may keep the returned set. */
    public Set<BlockPos> chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        Map<Long, Set<BlockPos>> map = levels.get(level.dimension());
        Set<BlockPos> s = map == null ? null : map.remove(chunk.getPos().pack());
        return s == null ? Set.of() : s;
    }

    /** The indexed origins in one chunk (a copy, safe to iterate while the index changes; empty if none). */
    public List<BlockPos> inChunk(ServerLevel level, int cx, int cz) {
        Map<Long, Set<BlockPos>> map = levels.get(level.dimension());
        Set<BlockPos> s = map == null ? null : map.get(ChunkPos.pack(cx, cz));
        return s == null ? List.of() : List.copyOf(s);
    }

    /** Whether a position is indexed (tests and debug). */
    public boolean contains(ServerLevel level, BlockPos pos) {
        Map<Long, Set<BlockPos>> map = levels.get(level.dimension());
        Set<BlockPos> s = map == null ? null : map.get(ChunkPos.pack(pos));
        return s != null && s.contains(pos);
    }

    public void add(ServerLevel level, BlockPos pos) {
        levels.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(ChunkPos.pack(pos), k -> new LinkedHashSet<>()).add(pos.immutable());
    }

    public void remove(ServerLevel level, BlockPos pos) {
        Map<Long, Set<BlockPos>> map = levels.get(level.dimension());
        if (map == null) return;
        Set<BlockPos> set = map.get(ChunkPos.pack(pos));
        if (set != null && set.remove(pos) && set.isEmpty()) map.remove(ChunkPos.pack(pos));
    }

    /**
     * Re-decides every indexed origin of a level against the live predicate (after a reload changed it): keeps those
     * still standing as a wanted origin, drops the rest. Use it before rescanning the ready-to-send chunks on a reload
     * instead of {@link #clear}: those are only the block-ticking chunks, and a loaded border chunk fires no load event
     * when it starts ticking later, so clearing would lose its pieces until it reloads.
     */
    public void retainValid(ServerLevel level) {
        Map<Long, Set<BlockPos>> map = levels.get(level.dimension());
        if (map == null) return;
        var chunks = level.getChunkSource();
        map.entrySet().removeIf(e -> {
            long key = e.getKey();
            LevelChunk chunk = chunks.getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) return true;
            e.getValue().removeIf(pos -> {
                BlockState st = chunk.getBlockState(pos);
                return !wanted.test(st.getBlock()) || !ArcanaContent.origin(st, pos).equals(pos);
            });
            return e.getValue().isEmpty();
        });
    }

    /** Every indexed origin in a level (a copy). */
    public List<BlockPos> all(ServerLevel level) {
        List<BlockPos> out = new ArrayList<>();
        Map<Long, Set<BlockPos>> map = levels.get(level.dimension());
        if (map != null) for (Set<BlockPos> s : map.values()) out.addAll(s);
        return out;
    }

    public int size() {
        int n = 0;
        for (Map<Long, Set<BlockPos>> m : levels.values()) for (Set<BlockPos> s : m.values()) n += s.size();
        return n;
    }
}
