package net.axther.aftrhrs.feature.coast;

import net.axther.aftrhrs.core.Pdc;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * State of a furniture block that Paper kept in its Oraxen base entity's PDC (compost bins, seaweed lines, trophy
 * plaques, aquarium tanks, fountain basins). Furniture is a block now, so the same keys ({@code feather:compost_fill},
 * {@code feather:fish_display} ...) live in a tag per block position inside the chunk's {@link Pdc}
 * ({@code aftrhrs:coast_spots}, keyed {@code "x,y,z"}): it travels with the world like the old entity did.
 */
public final class SpotData {
    public static final String KEY = "aftrhrs:coast_spots";

    private SpotData() {
    }

    private static String key(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** A copy of the spot's tag (empty when none). The chunk must be loaded. */
    public static CompoundTag get(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        CompoundTag all = chunk.getAttached(Pdc.DATA);
        if (all == null) return new CompoundTag();
        return all.getCompound(KEY).flatMap(t -> t.getCompound(key(pos))).map(CompoundTag::copy).orElseGet(CompoundTag::new);
    }

    /** Replaces the spot's tag ({@code null} or empty removes it). */
    public static void put(ServerLevel level, BlockPos pos, @Nullable CompoundTag tag) {
        LevelChunk chunk = level.getChunkAt(pos);
        CompoundTag all = Pdc.of(chunk).copy();
        CompoundTag spots = all.getCompound(KEY).map(CompoundTag::copy).orElseGet(CompoundTag::new);
        if (tag == null || tag.isEmpty()) spots.remove(key(pos));
        else spots.put(key(pos), tag.copy());
        if (spots.isEmpty()) all.remove(KEY);
        else all.put(KEY, spots);
        Pdc.changed(chunk, all);
        chunk.markUnsaved();
    }

    /** Removes the spot's tag and returns what it held. */
    public static CompoundTag take(ServerLevel level, BlockPos pos) {
        CompoundTag t = get(level, pos);
        if (!t.isEmpty()) put(level, pos, null);
        return t;
    }
}
