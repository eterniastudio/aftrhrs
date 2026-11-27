package net.axther.aftrhrs.feature.furniture.gadgets;

import com.mojang.serialization.Codec;
import net.axther.aftrhrs.feature.furniture.FurnitureBE;
import net.axther.aftrhrs.feature.furniture.FurnitureEntities;
import net.axther.aftrhrs.migrate.PaperItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * A gadget's state (what Paper kept in the Oraxen base entity's PDC under {@code feather:gadget_*} / {@code spike_*}):
 * a goblet's or jar's drink or cookies, the mob in a cage / jar (Paper's entity bytes + HMAC, format kept), coated
 * spikes. Clients get only what they draw: the overlay item and the caged mob's (verified) NBT.
 */
public class GadgetBlockEntity extends FurnitureBE {
    // the same fields and meaning as the Paper PDC keys
    @Nullable String kind;      // fluid | cookies | mob
    @Nullable String fluid;
    int units;
    ItemStack proto = ItemStack.EMPTY;
    byte @Nullable [] mob;
    byte @Nullable [] mobSig;
    @Nullable String mobType;
    @Nullable String spikeEffects;
    int spikeColor;
    @Nullable Integer spikeCharges;

    // synced for the renderer
    @Nullable String overlay;
    @Nullable CompoundTag mobPreview;
    /** Set while the module removes the piece itself (break with state): no extra drop. */
    boolean silent;

    // client
    public net.minecraft.world.entity.@Nullable Entity clientMob;
    private @Nullable CompoundTag clientMobFor;

    public GadgetBlockEntity(BlockPos pos, BlockState state) {
        super(FurnitureEntities.GADGET, pos, state);
    }

    public @Nullable String overlay() {
        return overlay;
    }

    public @Nullable CompoundTag mobPreview() {
        return mobPreview;
    }

    boolean hasState() {
        return kind != null;
    }

    void clearState() {
        kind = null;
        fluid = null;
        units = 0;
        proto = ItemStack.EMPTY;
        mob = null;
        mobSig = null;
        mobType = null;
    }

    /** Paper's base-entity PDC (BukkitValues) → this piece (migration). */
    public void loadPaper(CompoundTag bv) {
        kind = bv.getString("feather:gadget_kind").orElse(null);
        fluid = bv.getString("feather:gadget_fluid").orElse(null);
        units = bv.getIntOr("feather:gadget_units", 0);
        byte[] item = bv.getByteArray("feather:gadget_item").orElse(null);
        proto = item == null || level == null ? ItemStack.EMPTY : orEmpty(PaperItems.fromBytes(level.getServer(), item));
        mob = bv.getByteArray("feather:gadget_mob").orElse(null);
        mobSig = bv.getByteArray("feather:gadget_mob_sig").orElse(null);
        mobType = bv.getString("feather:gadget_mob_type").orElse(null);
        spikeEffects = bv.getString("feather:spike_effects").orElse(null);
        spikeColor = bv.getIntOr("feather:spike_color", 0);
        spikeCharges = bv.getInt("feather:spike_charges").orElse(null);
        GadgetsModule m = GadgetsModule.active();
        if (m != null) m.refresh(this);
        else sync();
    }

    private static ItemStack orEmpty(@Nullable ItemStack s) {
        return s == null ? ItemStack.EMPTY : s;
    }

    @Override
    public void serverTick() {
        GadgetsModule m = GadgetsModule.active();
        if (m != null) m.tick(this);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (silent || level == null || level.isClientSide() || !hasState()) return;
        GadgetsModule m = GadgetsModule.active();
        if (m != null) m.dropWithState(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        if (overlay != null) t.putString("Overlay", overlay);
        if (mobPreview != null) t.put("MobPreview", mobPreview.copy());
        return t;
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        overlay = in.getString("Overlay").orElse(null);
        mobPreview = in.read("MobPreview", CompoundTag.CODEC).orElse(null);
        if (level != null && level.isClientSide()) return; // the update tag carries only the two above
        kind = in.getString("Kind").orElse(null);
        fluid = in.getString("Fluid").orElse(null);
        units = in.getIntOr("Units", 0);
        proto = in.read("Item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        mob = in.read("Mob", Codec.BYTE_BUFFER).map(GadgetBlockEntity::bytes).orElse(null);
        mobSig = in.read("MobSig", Codec.BYTE_BUFFER).map(GadgetBlockEntity::bytes).orElse(null);
        mobType = in.getString("MobType").orElse(null);
        spikeEffects = in.getString("SpikeEffects").orElse(null);
        spikeColor = in.getIntOr("SpikeColor", 0);
        spikeCharges = in.getInt("SpikeCharges").orElse(null);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (overlay != null) out.putString("Overlay", overlay);
        if (mobPreview != null) out.store("MobPreview", CompoundTag.CODEC, mobPreview);
        if (kind != null) out.putString("Kind", kind);
        if (fluid != null) out.putString("Fluid", fluid);
        if (units != 0) out.putInt("Units", units);
        if (!proto.isEmpty()) out.store("Item", ItemStack.CODEC, proto);
        if (mob != null) out.store("Mob", Codec.BYTE_BUFFER, ByteBuffer.wrap(mob));
        if (mobSig != null) out.store("MobSig", Codec.BYTE_BUFFER, ByteBuffer.wrap(mobSig));
        if (mobType != null) out.putString("MobType", mobType);
        if (spikeEffects != null) out.putString("SpikeEffects", spikeEffects);
        if (spikeColor != 0) out.putInt("SpikeColor", spikeColor);
        if (spikeCharges != null) out.putInt("SpikeCharges", spikeCharges);
    }

    private static byte[] bytes(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    /** The client copy of the caged mob, made from the synced NBT (rebuilt when it changes). */
    public net.minecraft.world.entity.@Nullable Entity clientMob() {
        if (level == null || mobPreview == null) {
            clientMob = null;
            clientMobFor = null;
            return null;
        }
        if (clientMob == null || clientMobFor != mobPreview) {
            clientMobFor = mobPreview;
            CompoundTag t = mobPreview.copy();
            t.remove("Passengers");
            clientMob = net.minecraft.world.entity.EntityType.loadEntityRecursive(t, level,
                    new net.minecraft.world.entity.EntitySpawnRequest(net.minecraft.world.entity.EntitySpawnReason.LOAD, true), e -> e);
        }
        return clientMob;
    }
}
