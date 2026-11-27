package net.axther.aftrhrs.net;

import net.axther.aftrhrs.AftrHrs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One {@code aftrhrs:api} message, both directions: raw bytes (flag byte + body), see docs/companion-protocol.md and
 * {@link Wire}. Registered on both sides by {@link Companion#init}.
 */
public record ApiPayload(byte[] bytes) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ApiPayload> TYPE = new CustomPacketPayload.Type<>(AftrHrs.id("api"));
    public static final StreamCodec<FriendlyByteBuf, ApiPayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeBytes(p.bytes),
            buf -> {
                byte[] b = new byte[buf.readableBytes()];
                buf.readBytes(b);
                return new ApiPayload(b);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
