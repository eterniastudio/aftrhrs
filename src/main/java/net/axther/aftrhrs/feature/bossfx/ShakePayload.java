package net.axther.aftrhrs.feature.bossfx;

import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: shake the camera ({@link BossFx#shake}). {@code strength} is the peak tilt in degrees, {@code ticks}
 * how long it takes to die down. The client scales it by the player's Damage Tilt option (0 turns it off).
 */
public record ShakePayload(float strength, int ticks) implements CustomPacketPayload {
    public static final Type<ShakePayload> TYPE = new Type<>(AftrHrs.id("boss_shake"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShakePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, ShakePayload::strength, ByteBufCodecs.VAR_INT, ShakePayload::ticks, ShakePayload::new);

    private static boolean registered;

    /** Registers the payload type (both sides, at mod init). */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
