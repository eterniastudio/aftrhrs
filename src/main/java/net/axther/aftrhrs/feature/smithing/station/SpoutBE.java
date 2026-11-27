package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.Molten;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * The Spout: fitted to a crucible (or the Great Crucible's drain) behind it, it pours into the casting bench under its
 * nozzle when clicked or given a redstone pulse, until the mold is full or the pot runs dry. Hoppers can then take the
 * cast ingots: a fully automatic line.
 */
public final class SpoutBE extends StationBE {
    public static final int RATE = 10;
    private boolean pouring, powered;
    private String fluid = "";

    public SpoutBE(BlockPos pos, BlockState state) {
        super(Station.SPOUT, pos, state);
    }

    public boolean pouring() {
        return pouring;
    }

    public String fluid() {
        return fluid;
    }

    private MeltBE source() {
        return level != null && level.getBlockEntity(worldPosition.relative(facing().getOpposite())) instanceof MeltBE m ? m : null;
    }

    private CastingBE target() {
        if (level == null) return null;
        for (int dy = 1; dy <= 2; dy++) if (level.getBlockEntity(worldPosition.below(dy)) instanceof CastingBE c) return c;
        return null;
    }

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        if (!p.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
        if (client()) return InteractionResult.SUCCESS;
        if (!allowed(p)) return InteractionResult.FAIL;
        start();
        return InteractionResult.SUCCESS;
    }

    private void start() {
        MeltBE src = source();
        CastingBE dst = target();
        if (src == null || dst == null || src.total() == 0) return;
        pouring = true;
        sound(SoundEvents.BUCKET_EMPTY_LAVA, 0.6f, 1.2f);
        sync();
    }

    @Override
    public void neighborChanged(boolean on) {
        if (on && !powered) start();
        powered = on;
    }

    @Override
    public void serverTick(ServerLevel level) {
        if (!pouring) return;
        MeltBE src = source();
        CastingBE dst = target();
        if (src == null || dst == null) {
            stop();
            return;
        }
        String f = src.topFluid();
        if (f == null) {
            stop();
            return;
        }
        Molten took = src.drain(f, RATE);
        if (took == null) {
            stop();
            return;
        }
        int used = dst.accept(took);
        if (used < took.units()) {
            // give back what the mold didn't take
            src.fluids().merge(took.fluid(), took.units() - used, Integer::sum);
            stop();
            return;
        }
        if (!fluid.equals(f)) {
            fluid = f;
            sync();
        }
        if (dst.full()) stop();
    }

    private void stop() {
        if (!pouring) return;
        pouring = false;
        fluid = "";
        sync();
    }

    @Override
    protected void save(ValueOutput out) {
        out.putBoolean("Pouring", pouring);
        out.putBoolean("Powered", powered);
        out.putString("Fluid", fluid);
    }

    @Override
    protected void load(ValueInput in) {
        pouring = in.getBooleanOr("Pouring", false);
        powered = in.getBooleanOr("Powered", false);
        fluid = in.getStringOr("Fluid", "");
    }

    @Override
    protected List<ItemStack> contents() {
        return List.of();
    }
}
