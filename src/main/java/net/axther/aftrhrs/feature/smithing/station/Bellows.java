package net.axther.aftrhrs.feature.smithing.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The bellows: its nozzle points at a hearth; a pump (a click, or a fan blowing into it) works the fire. */
public final class Bellows {
    private Bellows() {
    }

    /** A click on the bellows (both sides). True when it pumped. */
    static boolean use(BlockState state, Level level, BlockPos pos, Player p) {
        if (state.getValue(StationBlock.ON)) return false;
        if (!level.isClientSide()) pumpFrom((ServerLevel) level, pos, state);
        return true;
    }

    public static void pumpFrom(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getValue(StationBlock.ON)) return;
        level.setBlock(pos, state.setValue(StationBlock.ON, true), 3);
        level.scheduleTick(pos, state.getBlock(), 8);
        level.playSound(null, pos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8f, 0.6f);
        level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.15f, 0.5f);
        var f = state.getValue(StationBlock.FACING);
        Vec3 n = Vec3.atCenterOf(pos).add(f.getStepX() * 0.5, -0.25, f.getStepZ() * 0.5);
        level.sendParticles(ParticleTypes.POOF, n.x, n.y, n.z, 3, 0.05, 0.05, 0.05, 0.02);
        if (level.getBlockEntity(pos.relative(f)) instanceof ForgeBE forge) forge.pump();
    }
}
