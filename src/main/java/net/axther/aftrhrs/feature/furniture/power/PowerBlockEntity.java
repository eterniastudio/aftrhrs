package net.axther.aftrhrs.feature.furniture.power;

import net.axther.aftrhrs.feature.furniture.FurnitureBE;
import net.axther.aftrhrs.feature.furniture.FurnitureEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A coal generator (on its bottom block) or an encased fan. Saved: who placed it (the fan's stream stops where they
 * couldn't build), the fan's push/pull, the generator's fuel slot and what is left of the current load. The grid
 * state (load, burning, powered) is worked out again every grid tick, like on Paper.
 */
public class PowerBlockEntity extends FurnitureBE {
    @Nullable UUID owner;
    boolean reverse;
    /** Generator: fuel left in the current load, in ticks at full load. */
    double burn;
    ItemStack fuel = ItemStack.EMPTY;

    // grid state (not saved)
    int load;
    boolean active;
    boolean powered;
    int humTick;
    /** Set when the module removes the piece itself: no fuel drop. */
    boolean silent;
    /** The module instance this BE last registered with (re-tracks when the module changes; cleared on removal). */
    private transient @Nullable PowerModule trackedBy;

    // client spin
    public float spin, spinO;

    public PowerBlockEntity(BlockPos pos, BlockState state) {
        super(FurnitureEntities.POWER, pos, state);
    }

    public boolean reverse() {
        return reverse;
    }

    public boolean isFan() {
        String id = furnitureId();
        PowerModule m = PowerModule.active();
        return id != null && (m != null ? id.equals(m.fanItem) : id.contains("fan"));
    }

    @Override
    public void serverTick() {
        PowerModule m = PowerModule.active();
        if (m != null && m != trackedBy) {
            m.track(this);
            trackedBy = m;
        }
    }

    @Override
    public void clientTick() {
        spinO = spin;
        BlockState s = getBlockState();
        boolean on = s.hasProperty(net.axther.aftrhrs.content.FurnitureBlock.POWERED) && s.getValue(net.axther.aftrhrs.content.FurnitureBlock.POWERED);
        if (on) {
            float step = (float) Math.toRadians(PowerModule.clientSpinDegrees());
            spin += reverse ? -step : step;
            if (spin > Math.PI * 2) {
                spin -= (float) (Math.PI * 2);
                spinO -= (float) (Math.PI * 2);
            } else if (spin < -Math.PI * 2) {
                spin += (float) (Math.PI * 2);
                spinO += (float) (Math.PI * 2);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        trackedBy = null;   // a BE vanilla re-adds (clearRemoved) tracks again on its next tick
        PowerModule m = PowerModule.active();
        if (m != null && level != null && !level.isClientSide()) m.untrack(this);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (silent || level == null || level.isClientSide()) return;
        PowerModule m = PowerModule.active();
        if (m != null) m.closeViews(this);
        if (!fuel.isEmpty()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, fuel);
            fuel = ItemStack.EMPTY;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        if (reverse) t.putBoolean("Reverse", true);
        return t;
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        reverse = in.getBooleanOr("Reverse", false);
        if (level != null && level.isClientSide()) return;
        owner = in.read("Owner", UUIDUtil.CODEC).orElse(null);
        burn = in.getDoubleOr("Burn", 0);
        fuel = in.read("Fuel", ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (reverse) out.putBoolean("Reverse", true);
        if (owner != null) out.store("Owner", UUIDUtil.CODEC, owner);
        if (burn > 0) out.putDouble("Burn", burn);
        if (!fuel.isEmpty()) out.store("Fuel", ItemStack.CODEC, fuel);
    }
}
