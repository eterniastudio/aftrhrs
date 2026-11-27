package net.axther.aftrhrs.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;

/** Framework smoke tests (also an example for the area suites in this package). */
public class SmokeTests {
    /** Falling blocks landing go through the claims grief mixins (this crashed the server once). */
    @GameTest
    public void fallingSandLands(GameTestHelper h) {
        BlockPos floor = new BlockPos(1, 1, 1);
        h.setBlock(floor, Blocks.STONE);
        var fb = net.minecraft.world.entity.item.FallingBlockEntity.fall(h.getLevel(), h.absolutePos(floor.above(4)), Blocks.SAND.defaultBlockState());
        h.succeedWhen(() -> h.assertBlockPresent(Blocks.SAND, floor.above()));
    }
}
