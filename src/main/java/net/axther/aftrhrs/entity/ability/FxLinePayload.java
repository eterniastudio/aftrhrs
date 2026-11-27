package net.axther.aftrhrs.entity.ability;

import io.netty.handler.codec.DecoderException;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Server to client: a straight line of particles from {@code from} to {@code to}, one every {@code step} blocks
 * ({@link Fx#line}). One payload instead of one particle packet per point; the client walks the same points
 * ({@link Fx#forEachLinePoint}) and adds the particles itself, so its camera distance and particle setting apply per
 * point as for vanilla packets. Players without the channel get the old per-point packets.
 */
public record FxLinePayload(ParticleOptions particle, Vec3 from, Vec3 to, float step) implements CustomPacketPayload {
    public static final Type<FxLinePayload> TYPE = new Type<>(AftrHrs.id("fx_line"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FxLinePayload> CODEC = StreamCodec.composite(
            ParticleTypes.STREAM_CODEC, FxLinePayload::particle, Vec3.STREAM_CODEC, FxLinePayload::from, Vec3.STREAM_CODEC, FxLinePayload::to,
            ByteBufCodecs.FLOAT, FxLinePayload::step, FxLinePayload::new);

    private static boolean registered;

    /** Registers this payload type and the nested ones (both sides, at mod init). */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Ring.TYPE, Ring.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Arc.TYPE, Arc.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Points.TYPE, Points.CODEC);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Server to client: a ring of {@code points} particle spots ({@code amount} particles each) of {@code radius} round
     * {@code center}, {@code y} up, turned by {@code phase} radians ({@link BossKit#ring}, {@link Fx#ring}); the client walks
     * the same points ({@link Fx#forEachRingPoint}). The channel is {@code fx_ring2} since the phase was added: an older
     * client doesn't report it and gets the per-point packets instead of a payload it can't decode.
     */
    public record Ring(ParticleOptions particle, Vec3 center, float radius, int points, int amount, float y, float phase)
            implements CustomPacketPayload {
        public static final Type<Ring> TYPE = new Type<>(AftrHrs.id("fx_ring2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ring> CODEC = StreamCodec.composite(
                ParticleTypes.STREAM_CODEC, Ring::particle, Vec3.STREAM_CODEC, Ring::center, ByteBufCodecs.FLOAT, Ring::radius,
                ByteBufCodecs.VAR_INT, Ring::points, ByteBufCodecs.VAR_INT, Ring::amount, ByteBufCodecs.FLOAT, Ring::y,
                ByteBufCodecs.FLOAT, Ring::phase, Ring::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: spots {@code [first, first + count)} of a {@code points}-spot ring (the same angles as {@link Ring},
     * one particle each), {@code y} up ({@link Fx#arc}). Progress rings in two colours are two of these.
     */
    public record Arc(ParticleOptions particle, Vec3 center, float radius, int points, int first, int count, float y)
            implements CustomPacketPayload {
        public static final Type<Arc> TYPE = new Type<>(AftrHrs.id("fx_arc"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Arc> CODEC = StreamCodec.composite(
                ParticleTypes.STREAM_CODEC, Arc::particle, Vec3.STREAM_CODEC, Arc::center, ByteBufCodecs.FLOAT, Arc::radius,
                ByteBufCodecs.VAR_INT, Arc::points, ByteBufCodecs.VAR_INT, Arc::first, ByteBufCodecs.VAR_INT, Arc::count,
                ByteBufCodecs.FLOAT, Arc::y, Arc::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: a batch of particle spots, each played like a vanilla particle packet with the same count, spread
     * ({@code dx, dy, dz}) and speed ({@link Fx.PointBatch}). {@code offsets} holds xyz triples relative to {@code origin}.
     */
    public record Points(ParticleOptions particle, Vec3 origin, float[] offsets, int count, float dx, float dy, float dz, float speed)
            implements CustomPacketPayload {
        public static final Type<Points> TYPE = new Type<>(AftrHrs.id("fx_points"));
        /** At most this many spots in one payload (a bad length fails the decode instead of allocating). */
        public static final int MAX = 4096;
        public static final StreamCodec<RegistryFriendlyByteBuf, Points> CODEC = StreamCodec.of(Points::write, Points::read);

        /** Number of spots. */
        public int size() {
            return offsets.length / 3;
        }

        private static void write(RegistryFriendlyByteBuf buf, Points p) {
            ParticleTypes.STREAM_CODEC.encode(buf, p.particle);
            Vec3.STREAM_CODEC.encode(buf, p.origin);
            int n = p.size();
            buf.writeVarInt(n);
            for (int i = 0; i < n * 3; i++) buf.writeFloat(p.offsets[i]);
            buf.writeVarInt(p.count);
            buf.writeFloat(p.dx);
            buf.writeFloat(p.dy);
            buf.writeFloat(p.dz);
            buf.writeFloat(p.speed);
        }

        private static Points read(RegistryFriendlyByteBuf buf) {
            ParticleOptions particle = ParticleTypes.STREAM_CODEC.decode(buf);
            Vec3 origin = Vec3.STREAM_CODEC.decode(buf);
            int n = buf.readVarInt();
            if (n < 0 || n > MAX) throw new DecoderException("fx_points: " + n + " spots (at most " + MAX + ")");
            float[] offsets = new float[n * 3];
            for (int i = 0; i < offsets.length; i++) offsets[i] = buf.readFloat();
            return new Points(particle, origin, offsets, buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
