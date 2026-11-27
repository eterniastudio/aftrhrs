package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Base of every station block entity: saving, syncing to clients (the renderers draw what's on the station), dropping
 * contents when broken, and the claim check every click goes through ("containers" rules).
 */
public abstract class StationBE extends BlockEntity {
    protected StationBE(Station station, BlockPos pos, BlockState state) {
        super(Smithing.beType(station), pos, state);
    }

    // ------------------------------------------------------------------ to implement

    /** A click; runs on both sides; mutate only on the server. PASS lets the held item do its own thing. */
    public abstract InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit);

    protected abstract void save(ValueOutput out);

    protected abstract void load(ValueInput in);

    /** Everything that drops when the station is broken. */
    protected abstract List<ItemStack> contents();

    public void serverTick(ServerLevel level) {
    }

    public void clientTick() {
    }

    public void animate(RandomSource random) {
    }

    public void neighborChanged(boolean powered) {
    }

    public int comparator() {
        return 0;
    }

    // ------------------------------------------------------------------ helpers

    public Direction facing() {
        BlockState s = getBlockState();
        return s.hasProperty(StationBlock.FACING) ? s.getValue(StationBlock.FACING) : Direction.NORTH;
    }

    protected boolean client() {
        return level == null || level.isClientSide();
    }

    /** May this player work this station (claims: container rules)? Tells them when not. */
    protected boolean allowed(Player p) {
        if (!(p instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return true;
        Feather f = Feather.get();
        if (f == null || f.claimAllows(sp, Loc.of(sl, worldPosition), ClaimAction.CONTAINER)) return true;
        sp.sendOverlayMessage(Component.translatable("aftrhrs.smithing.claimed"));
        return false;
    }

    protected void sync() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    protected void sound(SoundEvent s, float volume, float pitch) {
        if (level != null && !level.isClientSide()) level.playSound(null, worldPosition, s, SoundSource.BLOCKS, volume, pitch);
    }

    protected static void tell(Player p, String key, Object... args) {
        if (p instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.translatable(key, args));
    }

    protected long now() {
        return level == null ? 0 : level.getGameTime();
    }

    @Override
    protected final void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        load(in);
    }

    @Override
    protected final void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        save(out);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !level.isClientSide()) {
            for (ItemStack s : contents()) if (!s.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, s);
        }
    }

    protected static ItemStack readStack(ValueInput in, String key) {
        return in.read(key, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
    }

    protected static void writeStack(ValueOutput out, String key, ItemStack s) {
        if (!s.isEmpty()) out.store(key, ItemStack.OPTIONAL_CODEC, s);
    }
}
