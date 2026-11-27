package net.axther.aftrhrs.feature.smithing.station;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.BiFunction;

/**
 * The smithing stations (SMITHING.md 3): block id, collision boxes in pixels for a north-facing block (the model
 * contract in {@code tools/smithing_blocks.py}), whether it faces, ticks and has a block entity.
 */
public enum Station {
    FORGE("forge_hearth", true, true, ForgeBE::new, new double[][]{{0, 0, 0, 16, 16, 16}}),
    BELLOWS("bellows", true, false, null, new double[][]{{2, 0, 3, 14, 9, 16}, {7, 3, 0, 9, 5, 3}}),
    CRUCIBLE("crucible", true, true, CrucibleBE::new, new double[][]{{2, 0, 2, 14, 12, 14}}),
    CASTING_BENCH("casting_bench", true, true, CastingBE::new, new double[][]{{0, 6, 0, 16, 8, 16},
            {1, 0, 1, 4, 6, 4}, {12, 0, 1, 15, 6, 4}, {1, 0, 12, 4, 6, 15}, {12, 0, 12, 15, 6, 15}}),
    ANVIL("smithing_anvil", true, true, AnvilBE::new,
            new double[][]{{2, 0, 3, 14, 3, 13}, {5, 3, 5, 11, 8, 11}, {2, 8, 4, 14, 12, 12}, {0, 9, 6, 2, 11, 10}}),
    TROUGH("quench_trough", true, true, TroughBE::new, new double[][]{{1, 1, 3, 15, 8, 13},
            {1, 0, 3, 3, 1, 5}, {13, 0, 3, 15, 1, 5}, {1, 0, 11, 3, 1, 13}, {13, 0, 11, 15, 1, 13}}),
    CARVING_BENCH("carving_bench", true, true, CarvingBE::new, new double[][]{{3, 0, 4, 13, 10, 12}, {1, 10, 2, 15, 12, 14}}),
    // Shapes follow the models: a bench on legs must not cover the whole ground under it (renderers and culling
    // mods that trust the shape would hide the face below and show the caves through it).
    ASSEMBLY_BENCH("assembly_bench", true, true, AssemblyBE::new, new double[][]{{0, 12, 0, 16, 15, 16}, {2, 3, 2, 14, 4, 14},
            {1, 0, 1, 4, 12, 4}, {12, 0, 1, 15, 12, 4}, {1, 0, 12, 4, 12, 15}, {12, 0, 12, 15, 12, 15}}),
    WHEEL("whetstone_wheel", true, true, WheelBE::new, new double[][]{{1, 0, 4, 15, 14, 12}}),
    RUNE_TABLE("runesmith_table", true, true, RuneTableBE::new, new double[][]{{0, 11, 0, 16, 14, 16}, {3, 0, 3, 13, 11, 13}}),
    SPOUT("smithing_spout", true, true, SpoutBE::new, new double[][]{{5, 4, 4, 11, 10, 16}}),
    FIRE_BRICKS("fire_bricks", false, false, null, new double[][]{{0, 0, 0, 16, 16, 16}}),
    GREAT_DRAIN("great_crucible_drain", true, true, GreatCrucibleBE::new, new double[][]{{0, 0, 0, 16, 16, 16}});

    public final String id;
    public final boolean facing, ticks;
    private final @Nullable BiFunction<BlockPos, BlockState, BlockEntity> factory;
    final double[][] boxes;

    Station(String id, boolean facing, boolean ticks, @Nullable BiFunction<BlockPos, BlockState, BlockEntity> factory, double[][] boxes) {
        this.id = id;
        this.facing = facing;
        this.ticks = ticks;
        this.factory = factory;
        this.boxes = boxes;
    }

    public boolean hasBlockEntity() {
        return factory != null;
    }

    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return factory == null ? null : factory.apply(pos, state);
    }

    public String lang() {
        return name().toLowerCase(Locale.ROOT);
    }
}
