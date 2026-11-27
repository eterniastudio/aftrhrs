package net.axther.aftrhrs.feature.furniture;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** A module block entity on a furniture piece's origin: synced to clients (for its renderer) whenever it changes. */
public abstract class FurnitureBE extends BlockEntity implements FurnitureTicker {
    /** {@link #furnitureId} for {@link #idBlock}: keyed on the block, so a swap that keeps this BE re-reads it. */
    private transient @Nullable Block idBlock;
    private transient @Nullable String idCache;

    protected FurnitureBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Saves and sends the new state to the players who see it. */
    public void sync() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    /** The piece's Oraxen-style id ({@code arcana_stone_pedestal}). */
    public @Nullable String furnitureId() {
        BlockState s = getBlockState();
        Block b = s.getBlock();
        if (b != idBlock) {
            idCache = ArcanaContent.id(s);
            idBlock = b;
        }
        return idCache;
    }

    /** The piece's yaw in degrees clockwise from north (Oraxen's furniture yaw). */
    public float yaw() {
        return FurnitureBlock.yawDegrees(getBlockState());
    }
}
