/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/block/FastenerBlock; the jingle/redstone parts are not ported.)
 */
package net.axther.aftrhrs.feature.fairylights;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The String Hook ({@code aftrhrs:fairy_hook}): a small brass screw-eye strands hang from. Stringing onto a wall,
 * ceiling or floor puts one there by itself ({@code placed=false}: it goes away with its last strand and drops
 * nothing); a hook placed from the item stays and drops itself.
 */
public final class HookBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty PLACED = BooleanProperty.create("placed");
    private static final VoxelShape[] SHAPES = {
            Block.box(6, 10, 5.5, 10, 16, 10.5), // down (hangs from a ceiling)
            Block.box(6, 0, 5.5, 10, 6, 10.5),   // up
            Block.box(6, 5.5, 10, 10, 10.5, 16), // north
            Block.box(6, 5.5, 0, 10, 10.5, 6),   // south
            Block.box(10, 5.5, 6, 16, 10.5, 10), // west
            Block.box(0, 5.5, 6, 6, 10.5, 10),   // east
    };

    public HookBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PLACED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, PLACED);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[state.getValue(FACING).get3DDataValue()];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return net.minecraft.world.phys.shapes.Shapes.empty();
    }

    /** Hangs on a sturdy face, leaves, or the top of a wall (as in Fairy Lights). */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos on = pos.relative(facing.getOpposite());
        BlockState s = level.getBlockState(on);
        return s.is(BlockTags.LEAVES) || s.isFaceSturdy(level, on, facing) || facing == Direction.UP && s.is(BlockTags.WALLS);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState s = defaultBlockState().setValue(PLACED, true).setValue(FACING, ctx.getClickedFace());
        if (s.canSurvive(level, pos)) return s;
        for (Direction d : ctx.getNearestLookingDirections()) {
            s = s.setValue(FACING, d.getOpposite());
            if (s.canSurvive(level, pos)) return s;
        }
        return null;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction dir,
                                     BlockPos neighborPos, BlockState neighbor, RandomSource random) {
        if (dir == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HookBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != FairyLights.HOOK_BE) return null;
        return level.isClientSide()
                ? (BlockEntityTicker<T>) (BlockEntityTicker<HookBlockEntity>) (l, p, s, be) -> be.clientTick()
                : (BlockEntityTicker<T>) (BlockEntityTicker<HookBlockEntity>) (l, p, s, be) -> be.serverTick((ServerLevel) l);
    }

    /** Where strands attach on a hook facing this way: the eye of the screw hook, relative to the block. */
    public static Vec3 offset(Direction facing) {
        return new Vec3(0.5 - facing.getStepX() * 0.22, 0.5 - facing.getStepY() * 0.22, 0.5 - facing.getStepZ() * 0.22);
    }
}
