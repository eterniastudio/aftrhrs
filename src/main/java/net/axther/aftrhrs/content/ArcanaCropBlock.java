package net.axther.aftrhrs.content;

import com.google.gson.JsonArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * An Arcana crop (ex-Oraxen tripwire stage blocks {@code arcana_crop_<crop>_stage<n>}): growth stage = {@code age}.
 * Growth, watering, quality and harvest belong to the farming port, so this block never grows by itself; it sits
 * on farmland, has no collision and, when broken without the farming module claiming the break, drops its seeds
 * (plus one produce when ripe).
 */
public class ArcanaCropBlock extends Block {
    static final ThreadLocal<Integer> BUILDING = new ThreadLocal<>();
    public final IntegerProperty age;
    public final int stages;
    private final VoxelShape[] shapes;
    final @Nullable String seeds;

    static ArcanaCropBlock create(int stages, JsonArray shapes, @Nullable String seeds, Properties p) {
        BUILDING.set(stages);
        try {
            return new ArcanaCropBlock(stages, shapes, seeds, p);
        } finally {
            BUILDING.remove();
        }
    }

    private ArcanaCropBlock(int stages, JsonArray boxes, @Nullable String seeds, Properties p) {
        super(p);
        this.stages = stages;
        this.age = FurnitureBlock.intProp("age", stages - 1);
        this.seeds = seeds;
        this.shapes = new VoxelShape[stages];
        for (int i = 0; i < stages; i++) {
            JsonArray b = boxes != null && i < boxes.size() ? boxes.get(i).getAsJsonArray() : null;
            shapes[i] = b == null ? Block.box(0, 0, 0, 16, 4, 16)
                    : Block.box(b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble(),
                    b.get(3).getAsDouble(), b.get(4).getAsDouble(), b.get(5).getAsDouble());
        }
        registerDefaultState(stateDefinition.any().setValue(age, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FurnitureBlock.intProp("age", BUILDING.get() - 1));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapes[Math.min(state.getValue(age), shapes.length - 1)];
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(Blocks.FARMLAND);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction dir, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        if (dir == Direction.DOWN && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, level, ticks, pos, dir, neighbourPos, neighbourState, random);
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return seeds != null ? ArcanaContent.stack(seeds, 1) : ItemStack.EMPTY;
    }

    public boolean isRipe(BlockState state) {
        return state.getValue(age) >= stages - 1;
    }
}
