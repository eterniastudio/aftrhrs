package net.axther.aftrhrs.feature.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A storage network block (on Paper: a Feather record in the chunk plus a carrier block). The Inventory Connector is
 * this class; terminals are {@link Terminal}s with a front that faces the player who placed them. Pistons, explosions
 * and the wither can't move or break them (block properties and tags); everything else is {@link StorageModule}'s.
 */
class StorageBlock extends Block {
    final Kind kind;

    StorageBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        StorageModule m = StorageModule.active();
        if (m != null && by instanceof ServerPlayer p && level instanceof ServerLevel sl) m.placed(p, sl, pos, kind);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        // Kept-loaded networks near it read themselves again on the next pass (it may join them).
        StorageModule m = StorageModule.active();
        if (m != null && level instanceof ServerLevel sl && !old.is(this)) m.remotes().blockChanged(sl, pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        StorageModule m = StorageModule.active();
        if (m != null) {
            m.terminalGone(level, pos);
            m.remotes().blockChanged(level, pos);
        }
    }

    /** A Storage Terminal or Crafting Terminal: right-click opens it. */
    static final class Terminal extends StorageBlock {
        static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

        Terminal(Kind kind, Properties properties) {
            super(kind, properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            // The screen faces the player who placed it.
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
            StorageModule m = StorageModule.active();
            if (m == null) return InteractionResult.PASS;
            m.openTerminal(p, new Terminal.At(sl, pos.immutable(), kind));
            return InteractionResult.SUCCESS_SERVER;
        }

        /** A placed terminal: where it is and what it is. A window only stays open while {@link #present()}. */
        record At(ServerLevel level, BlockPos pos, Kind kind) {
            boolean loaded() {
                return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
            }

            /** Still there: loaded and still this kind of block. */
            boolean present() {
                return loaded() && level.getBlockState(pos).getBlock() instanceof StorageBlock b && b.kind == kind;
            }

            boolean same(At o) {
                return o != null && o.level == level && o.pos.equals(pos);
            }

            double distanceSqr(ServerPlayer p) {
                return p.level() != level ? Double.MAX_VALUE : p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            }
        }
    }
}
