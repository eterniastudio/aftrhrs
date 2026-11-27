package net.axther.aftrhrs.feature.christmas;

import net.axther.aftrhrs.feature.furniture.FurnitureBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Mistletoe: when two or more players stand beneath it, hearts float up between them now and then. */
public class MistletoeBlockEntity extends FurnitureBE {
    static final int PERIOD = 30;
    private int pairTicks;

    public MistletoeBlockEntity(BlockPos pos, BlockState state) {
        super(ChristmasContent.MISTLETOE_BE, pos, state);
    }

    /** Players standing in the 3x3 column up to four blocks under the mistletoe. */
    public List<ServerPlayer> under(ServerLevel level) {
        BlockPos p = worldPosition;
        AABB box = new AABB(p.getX() - 1, p.getY() - 4, p.getZ() - 1, p.getX() + 2, p.getY() + 1, p.getZ() + 2);
        return level.getEntitiesOfClass(ServerPlayer.class, box, pl -> pl.isAlive() && !pl.isSpectator());
    }

    @Override
    public void serverTick() {
        if (!(level instanceof ServerLevel sl)) return;
        if ((sl.getGameTime() + worldPosition.hashCode()) % PERIOD != 0) return;
        List<ServerPlayer> ps = under(sl);
        if (ps.size() < 2) {
            pairTicks = 0;
            return;
        }
        ServerPlayer a = ps.get(0), b = ps.get(1);
        Vec3 mid = a.getEyePosition().add(b.getEyePosition()).scale(0.5).add(0, 0.3, 0);
        sl.sendParticles(ParticleTypes.HEART, mid.x, mid.y, mid.z, 3, 0.35, 0.2, 0.35, 0.02);
        if (pairTicks++ % 4 == 0) {
            sl.playSound(null, mid.x, mid.y, mid.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.6f, 1.6f);
        }
    }
}
