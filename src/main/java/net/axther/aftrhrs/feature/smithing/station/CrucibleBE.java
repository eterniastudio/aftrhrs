package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.Heat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Crucible: holds 4 ingots. It takes the heat of a lit forge hearth under it; lava under it gives 1200°, a magma
 * block 700°, a lit campfire 500° (enough for fish oil, copper with a forge).
 */
public final class CrucibleBE extends MeltBE {
    public static final int CAPACITY = 4 * 90;

    public CrucibleBE(BlockPos pos, BlockState state) {
        super(Station.CRUCIBLE, pos, state);
    }

    @Override
    public int capacity() {
        return CAPACITY;
    }

    @Override
    protected boolean great() {
        return false;
    }

    @Override
    public float heat() {
        if (level == null) return Heat.AIR;
        BlockPos below = worldPosition.below();
        if (level.getBlockEntity(below) instanceof ForgeBE forge) return forge.heat();
        BlockState s = level.getBlockState(below);
        if (s.is(Blocks.LAVA) || s.is(Blocks.LAVA_CAULDRON)) return 1200;
        if (s.is(Blocks.MAGMA_BLOCK)) return 700;
        if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) return 500;
        return Heat.AIR;
    }

    @Override
    protected Vec3 mouth() {
        return Vec3.atLowerCornerOf(worldPosition).add(0.5, 0.75, 0.5);
    }
}
