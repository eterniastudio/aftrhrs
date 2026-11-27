/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/block/entity/FastenerBlockEntity + server/fastener/BlockFastener.)
 */
package net.axther.aftrhrs.feature.fairylights;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** A hook's strands ({@code Strands}: saved with the block, sent to clients in the block entity update). */
public final class HookBlockEntity extends BlockEntity {
    private final HookFastener fastener = new HookFastener();
    private @Nullable AABB cullBox;
    private long cullStamp = Long.MIN_VALUE;

    public HookBlockEntity(BlockPos pos, BlockState state) {
        super(FairyLights.HOOK_BE, pos, state);
    }

    public Fastener fastener() {
        return fastener;
    }

    public Direction facing() {
        BlockState s = getBlockState();
        return s.hasProperty(HookBlock.FACING) ? s.getValue(HookBlock.FACING) : Direction.UP;
    }

    public boolean placedByItem() {
        BlockState s = getBlockState();
        return s.hasProperty(HookBlock.PLACED) && s.getValue(HookBlock.PLACED);
    }

    /**
     * Client: bounds of the strands that start here, for culling (worked out once a game tick; every frame while an end
     * is a player, as those move between ticks and under /tick freeze).
     */
    public AABB renderBounds() {
        long now = level == null ? 0 : level.getGameTime();
        if (cullBox == null || cullStamp != now) {
            cullBox = fastener.renderBounds();
            boolean moving = false;
            for (Strand s : fastener.outgoing()) {
                if (s.dest.isPlayer()) {
                    moving = true;
                    break;
                }
            }
            cullStamp = moving ? Long.MIN_VALUE : now;
        }
        return cullBox;
    }

    void serverTick(ServerLevel level) {
        fastener.serverTick(level);
        if (fastener.empty() && !placedByItem() && !isRemoved()) {
            // a hook put up by stringing goes away with its last strand
            level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    void clientTick() {
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level.isClientSide()) Fastener.CLIENT_LOADED.add(fastener);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && level.isClientSide()) Fastener.CLIENT_LOADED.add(fastener);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        Fastener.CLIENT_LOADED.remove(fastener);
    }

    /** The hook is going away (broken, exploded, lost its wall): its strands come down and drop. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel sl) fastener.removeAll(sl, Vec3.atCenterOf(pos), true);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        in.read("Strands", CompoundTag.CODEC).ifPresent(fastener::load);
        cullBox = null;   // an update packet can add or take away strands within a tick
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.store("Strands", CompoundTag.CODEC, fastener.save());
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    final class HookFastener extends Fastener {
        @Override
        public @Nullable Level level() {
            return HookBlockEntity.this.level;
        }

        @Override
        public FastenerRef ref() {
            return FastenerRef.hook(worldPosition);
        }

        @Override
        public Vec3 point() {
            return Vec3.atLowerCornerOf(worldPosition).add(HookBlock.offset(facing()));
        }

        @Override
        public BlockPos pos() {
            return worldPosition;
        }

        @Override
        public Direction facing() {
            return HookBlockEntity.this.facing();
        }

        /** Gone once the block entity is (broken, replaced, chunk unloaded). */
        @Override
        public boolean valid() {
            return !HookBlockEntity.this.isRemoved() && HookBlockEntity.this.level != null;
        }

        @Override
        protected void sync() {
            setChanged();
            if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
