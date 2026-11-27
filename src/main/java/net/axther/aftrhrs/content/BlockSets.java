package net.axther.aftrhrs.content;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The snow, ice and calcite building-block sets ({@code mod/tools/blocksets/build.py}; registered from the content
 * table like every other content block). Holds the bits of behavior that are not plain data.
 */
public final class BlockSets {
    private BlockSets() {
    }

    /** The igloo trapdoor hole (no basement) is filled with polished snow instead of a vanilla snow block. */
    public static BlockState iglooFloorFill(BlockState state) {
        if (!state.is(Blocks.SNOW_BLOCK)) return state;
        Block polished = ArcanaContent.block("arcana_polished_snow");
        return polished != null ? polished.defaultBlockState() : state;
    }
}
