package net.axther.aftrhrs.feature.bossfx;

import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Server to client: play a boss quake ring or fissure line ({@link BossFx#quake} / {@link BossFx#fissure}) locally. The
 * client walks the same ring or line as the server, finds the ground itself ({@link BossFx#groundAt}) and spawns
 * client-only block displays, so the server makes no display entity and sends no particle packet per column. Hits stay
 * on the server.
 *
 * <p>The shape, the timing and the per-column randomness live here, shared by the client and by the server's fallback
 * for players without the channel, so both draw the same thing. Randoms come from {@link #mix} of the payload's seed and
 * the column (quake) or step (fissure), never from iteration order, so every viewer sees the same tilts.
 *
 * @param kind     {@link #QUAKE} or {@link #FISSURE}
 * @param x        origin (quake center / fissure start); {@code y} is the base height the ground is searched around
 * @param dirX     fissure step direction (normalized, horizontal); 0 for a quake
 * @param size     quake radius / fissure length (blocks)
 * @param speed    blocks per tick
 * @param rise     how high quake blocks pop (blocks); unused for a fissure
 * @param width    quake ring thickness (1-3); unused for a fissure
 * @param lookState fissure spikes of this {@link Block#getId block state id} instead of the ground's block; -1 for none
 */
public record HeavePayload(byte kind, double x, double y, double z, float dirX, float dirZ, double size, double speed, double rise, byte width,
                           long seed, int lookState) implements CustomPacketPayload {
    public static final Type<HeavePayload> TYPE = new Type<>(AftrHrs.id("boss_heave"));
    public static final StreamCodec<FriendlyByteBuf, HeavePayload> CODEC = StreamCodec.of(HeavePayload::write, HeavePayload::read);

    public static final byte QUAKE = 0, FISSURE = 1;

    // quake ring: starts at 0.6 blocks, one column every 0.9 blocks round the ring, sub-rings 0.9 apart rise to 60 %
    public static final double QUAKE_START = 0.6, POINT_SPACING = 0.9, RING_SPACING = 0.9, SUB_RISE = 0.6;
    // heave keyframes (ticks after the block appears): pop at +1 over 3, settle at +5 over 4, gone at +10
    public static final int POP_AT = 1, POP_TICKS = 3, SETTLE_AT = 5, SETTLE_TICKS = 4, HEAVE_GONE = 10;
    // fissure: first spike 1.5 blocks out; rise at +1 over 2, sink at +26 over 10, gone at +38
    public static final double FISSURE_START = 1.5;
    public static final int SPIKE_RISE_AT = 1, SPIKE_RISE_TICKS = 2, SPIKE_SINK_AT = 26, SPIKE_SINK_TICKS = 10, SPIKE_GONE = 38;

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

    private static void write(FriendlyByteBuf buf, HeavePayload p) {
        buf.writeByte(p.kind);
        buf.writeDouble(p.x);
        buf.writeDouble(p.y);
        buf.writeDouble(p.z);
        buf.writeFloat(p.dirX);
        buf.writeFloat(p.dirZ);
        buf.writeDouble(p.size);
        buf.writeDouble(p.speed);
        buf.writeDouble(p.rise);
        buf.writeByte(p.width);
        buf.writeLong(p.seed);
        buf.writeVarInt(p.lookState + 1);
    }

    private static HeavePayload read(FriendlyByteBuf buf) {
        return new HeavePayload(buf.readByte(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readByte(), buf.readLong(), buf.readVarInt() - 1);
    }

    /** The fissure's spike block, or null for the ground's own. */
    public @Nullable BlockState look() {
        return lookState < 0 ? null : Block.stateById(lookState);
    }

    // ------------------------------------------------------------------ shared shape

    /** A ground column of a quake ring: {@code w} is the sub-ring (0 = the front, rising highest). */
    @FunctionalInterface
    public interface Column {
        void at(int bx, int bz, int w);
    }

    /** Walks the ring at radius {@code d}: every column not in {@code done} yet, front ring first. */
    public static void ring(double cx, double cz, double d, int width, LongSet done, Column out) {
        for (int w = 0; w < Math.max(1, width); w++) {
            double r = d - w * RING_SPACING;
            if (r < 0.5) continue;
            int points = Math.max(6, (int) Math.ceil(2 * Math.PI * r / POINT_SPACING));
            for (int i = 0; i < points; i++) {
                double a = 2 * Math.PI * i / points;
                int bx = (int) Math.floor(cx + Math.cos(a) * r), bz = (int) Math.floor(cz + Math.sin(a) * r);
                if (!done.add(((long) bx << 32) ^ (bz & 0xffffffffL))) continue;
                out.at(bx, bz, w);
            }
        }
    }

    /** A heaved block at rest (where it starts and settles back to). */
    public static Transformation heaveRest() {
        return new Transformation(new Vector3f(-0.01f, 0f, -0.01f), new Quaternionf(), new Vector3f(1.02f), new Quaternionf());
    }

    /** A heaved block at the top of its pop: {@code rise} up, tilted up to 14 degrees about a horizontal axis. */
    public static Transformation heavePop(long seed, int bx, int bz, double rise) {
        long h = mix(seed, bx, bz);
        float tilt = (float) Math.toRadians(range(h, 0, -14, 14));
        Vector3f axis = new Vector3f((float) range(h, 1, -1, 1), 0, (float) range(h, 2, -1, 1));
        if (axis.lengthSquared() < 1e-4) axis.set(1, 0, 0);
        axis.normalize();
        return new Transformation(new Vector3f(-0.01f, (float) rise, -0.01f), new Quaternionf(new AxisAngle4f(tilt, axis)),
                new Vector3f(1.02f), new Quaternionf());
    }

    /** One fissure spike's random look: yaw 0-90, lean up to 12 degrees, 0.6-0.9 wide, 1.4-2.3 tall. */
    public record Spike(Quaternionf rot, float w, float height) {
        /** The spike with its base {@code y} relative to the block on the ground. */
        public Transformation at(float y) {
            return new Transformation(new Vector3f(0.5f - w / 2, y, 0.5f - w / 2), rot, new Vector3f(w, height, w), new Quaternionf());
        }

        /** Just poking out (where it starts). */
        public Transformation hidden() {
            return at(-0.9f);
        }

        /** Fully risen. */
        public Transformation risen() {
            return at(0.6f);
        }

        /** Sunk back into the ground. */
        public Transformation sunk() {
            return at(-height);
        }
    }

    public static Spike spike(long seed, int step) {
        long h = mix(seed, step, 0x5F1C);
        float yaw = (float) Math.toRadians(range(h, 0, 0, 90));
        float lean = (float) Math.toRadians(range(h, 1, -12, 12));
        float w = (float) range(h, 2, 0.6, 0.9);
        float height = (float) (1.4 + range(h, 3, 0, 0.9));
        return new Spike(new Quaternionf().rotateY(yaw).rotateX(lean), w, height);
    }

    /** A well-mixed hash of the seed and two coordinates (SplitMix64 finalizer). */
    public static long mix(long seed, long a, long b) {
        return fmix(seed ^ fmix(a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L));
    }

    /** The {@code i}-th uniform random in [lo, hi) drawn from hash {@code h}. */
    private static double range(long h, int i, double lo, double hi) {
        double u = (fmix(h + (i + 1) * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
        return lo + (hi - lo) * u;
    }

    private static long fmix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
