package net.axther.aftrhrs.feature.storage;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Locale;

/**
 * Paper storage networks → real blocks. Paper kept each placed storage block as a record in its chunk's
 * PersistentDataContainer ({@code feather:storage_blocks} = {@code x,y,z,kind,FACING,CARRIER;...}, now in the
 * chunk's {@link Pdc}) over a carrier block: the fallback block (chiseled polished blackstone / tuff bricks / tuff)
 * or a barrier under a non-persistent ItemDisplay skin. When such a chunk loads, every record whose carrier is still
 * there becomes the native block (the screen facing the way it faced the placer) and the record is dropped; a record
 * whose block was replaced in the meantime is forgotten, like Feather did. Leftover skin displays
 * ({@code feather:storage_display}) are discarded by {@link StorageContent}'s legacy converter.
 */
final class StorageMigration {
    static final String KEY = "feather:storage_blocks";
    static final String DISPLAY_KEY = "feather:storage_display";

    private StorageMigration() {
    }

    static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        if (generated || !Pdc.has(chunk, KEY)) return;
        ChunkPos cp = chunk.getPos();
        // No block changes inside a chunk load.
        Tasks.later(1, () -> convert(level, cp));
    }

    private static void convert(ServerLevel level, ChunkPos cp) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x(), cp.z());
        if (chunk == null) return; // unloaded again: the record waits for the next load
        String data = Pdc.getString(chunk, KEY);
        if (data == null) return;
        int converted = 0;
        for (String entry : data.split(";")) {
            if (entry.isBlank()) continue;
            String[] f = entry.split(",");
            try {
                int x = Integer.parseInt(f[0].trim()), y = Integer.parseInt(f[1].trim()), z = Integer.parseInt(f[2].trim());
                Kind kind = Kind.parse(f[3]);
                if (kind == null || (x >> 4) != cp.x() || (z >> 4) != cp.z()) throw new IllegalArgumentException("bad entry");
                Direction placerFacing = f.length > 4 ? Direction.byName(f[4].trim().toLowerCase(Locale.ROOT)) : null;
                Block carrier = f.length > 5 ? BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(f[5].trim().toLowerCase(Locale.ROOT))) : null;
                BlockPos pos = new BlockPos(x, y, z);
                BlockState now = level.getBlockState(pos);
                boolean there = now.is(Blocks.BARRIER) || (carrier != null && carrier != Blocks.AIR && now.is(carrier));
                if (!there) {
                    AftrHrs.LOG.info("Storage {} at {} {} {} {} is no longer there; forgetting it.", kind.id, level.dimension().identifier(), x, y, z);
                    continue;
                }
                StorageBlock block = StorageContent.BLOCKS.get(kind);
                BlockState state = block.defaultBlockState();
                if (block instanceof StorageBlock.Terminal && placerFacing != null && placerFacing.getAxis().isHorizontal()) {
                    state = state.setValue(StorageBlock.Terminal.FACING, placerFacing.getOpposite());
                }
                level.setBlock(pos, state, Block.UPDATE_ALL);
                converted++;
            } catch (RuntimeException e) {
                AftrHrs.LOG.warn("Skipping a broken storage block entry in chunk {},{}: {}", cp.x(), cp.z(), entry);
            }
        }
        Pdc.remove(chunk, KEY);
        chunk.markUnsaved();
        if (converted > 0) AftrHrs.LOG.info("Storage: {} Paper storage block(s) in chunk {},{} of {} are native blocks now.",
                converted, cp.x(), cp.z(), level.dimension().identifier());
    }
}
