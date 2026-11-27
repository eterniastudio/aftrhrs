package net.axther.aftrhrs.feature.travel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block a player carries in their arms (the carry module): its state and its block entity's saved data (a
 * chest's contents, a furnace's fuel ...). Kept on the player and saved with them, so logging out or a restart never
 * loses it; synced to every client as the state alone (what to draw in their arms), never the contents.
 */
public record CarriedBlock(BlockState state, CompoundTag data) {
    public static final Codec<CarriedBlock> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockState.CODEC.fieldOf("state").forGetter(CarriedBlock::state),
            CompoundTag.CODEC.optionalFieldOf("data", new CompoundTag()).forGetter(CarriedBlock::data)
    ).apply(i, CarriedBlock::new));

    /** Clients only learn which block it is. */
    public static final StreamCodec<RegistryFriendlyByteBuf, CarriedBlock> LOOK = ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)
            .<RegistryFriendlyByteBuf>cast().map(s -> new CarriedBlock(s, new CompoundTag()), CarriedBlock::state);

    public static final AttachmentType<CarriedBlock> CARRIED = AttachmentRegistry.create(AftrHrs.id("carried_block"),
            b -> b.persistent(CODEC).syncWith(LOOK, AttachmentSyncPredicate.all()));

    /** Registers the attachment (mod init, both sides; the class load does it). */
    public static void init() {
    }
}
