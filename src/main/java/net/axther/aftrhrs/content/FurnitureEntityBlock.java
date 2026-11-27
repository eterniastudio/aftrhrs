package net.axther.aftrhrs.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Furniture with a block entity on its origin: real container storage, or the gramophone's disc player. */
public class FurnitureEntityBlock extends FurnitureBlock implements EntityBlock {
    /** The sides that tick this piece's module block entity (ArcanaContent.moduleEntityTicks), -1 until first asked. */
    private byte tickSides = -1;

    protected FurnitureEntityBlock(FurnitureSpec spec, Properties props) {
        super(spec, props);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (partIndex(state) != 0) return null;
        if (spec.jukebox) return new GramophoneBlockEntity(pos, state);
        if (spec.hasBlockEntity()) return new FurnitureStorageBlockEntity(pos, state);
        var module = ArcanaContent.moduleEntity(spec.id);
        return module == null ? null : module.create(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (spec.jukebox && type == ArcanaContent.GRAMOPHONE_BE && partIndex(state) == 0) {
            return (BlockEntityTicker<T>) (BlockEntityTicker<GramophoneBlockEntity>) GramophoneBlockEntity::tick;
        }
        if (!spec.hasBlockEntity() && !spec.jukebox && partIndex(state) == 0) {
            // the module entities are all registered before init (frozen after it): resolve once, not on every call
            if (tickSides < 0) tickSides = ArcanaContent.moduleEntityTicks(spec.id);
            if ((tickSides & (level.isClientSide() ? 2 : 1)) == 0) return null;
            return level.isClientSide()
                    ? (l, p, s, be) -> { if (be instanceof FurnitureTicker t) t.clientTick(); }
                    : (l, p, s, be) -> { if (be instanceof FurnitureTicker t) t.serverTick(); };
        }
        return null;
    }

    @Override
    protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        if (level.getBlockEntity(pos) instanceof FurnitureStorageBlockEntity be) be.recheckOpen();
    }
}
