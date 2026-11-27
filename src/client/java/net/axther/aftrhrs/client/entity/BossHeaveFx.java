package net.axther.aftrhrs.client.entity;

import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.axther.aftrhrs.feature.bossfx.BossFx;
import net.axther.aftrhrs.feature.bossfx.HeavePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Boss quakes and fissures drawn on this client ({@link HeavePayload}): walks the same ring or line as the server, finds
 * the ground in the client level ({@link BossFx#groundAt}) and heaves client-only block displays (never in the server's
 * world, negative ids of their own) with the same keyframes the server displays had. Vanilla's display renderer does
 * the interpolation and the frustum culling, which works only because each display gets a width and height
 * ({@link #display}): a display without them is never culled, so every one in range would be extracted and submitted each
 * frame. Columns past the display view distance are skipped before their ground lookup. Ticked with the level, so it
 * pauses with the game; dropped on disconnect and on a level change.
 */
public final class BossHeaveFx {
    /** Most heave displays alive at once (as many as the server engine ever allowed). */
    private static final int MAX_LIVE = 1500;
    /** Client-only display ids: far below CreatureView's range (which counts down from -1,000,000). */
    private static final int FIRST_ID = -500_000_000;
    private static int nextId = FIRST_ID;

    private static final List<Run> RUNS = new ArrayList<>();
    private static final List<Key> KEYS = new ArrayList<>();
    private static int live;
    private static long now;
    private static @Nullable ClientLevel owner;
    private static final RandomSource RANDOM = RandomSource.create();

    private BossHeaveFx() {
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(HeavePayload.TYPE, (payload, ctx) -> start(ctx.client(), payload));
        ClientTickEvents.END_LEVEL_TICK.register(BossHeaveFx::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    /** One quake ring or fissure line being played. */
    private static final class Run {
        final HeavePayload p;
        final @Nullable BlockState look;
        final LongOpenHashSet done;
        double d;
        int step;

        Run(HeavePayload p) {
            this.p = p;
            this.look = p.kind() == HeavePayload.FISSURE ? p.look() : null;
            this.done = p.kind() == HeavePayload.QUAKE ? new LongOpenHashSet() : null;
            this.d = p.kind() == HeavePayload.QUAKE ? HeavePayload.QUAKE_START : HeavePayload.FISSURE_START;
        }
    }

    /** A scheduled change of one display: a new transformation, or (to == null) its removal. */
    private record Key(long at, Display.BlockDisplay display, @Nullable Transformation to, int ticks) {
    }

    private static void start(Minecraft mc, HeavePayload p) {
        ClientLevel level = mc.level;
        if (level == null) return;
        if (owner != level) clear();
        owner = level;
        RUNS.add(new Run(p));
    }

    private static void clear() {
        // displays of an old level go with it; in the current one they are taken out
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level == owner) {
            for (Key k : KEYS) if (k.to == null && !k.display.isRemoved()) level.removeEntity(k.display.getId(), Entity.RemovalReason.DISCARDED);
        }
        RUNS.clear();
        KEYS.clear();
        live = 0;
        owner = null;
    }

    private static void tick(ClientLevel level) {
        if (owner == null) return;
        if (owner != level) {
            clear();
            return;
        }
        now++;
        Minecraft mc = Minecraft.getInstance();
        boolean particles = mc.options.particles().get() != ParticleStatus.MINIMAL;
        // the displays render out to 64 blocks x the entity distance setting (view range 1): no point heaving columns past that
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double maxSq = Mth.square(64 * Entity.getViewScale() + 4);
        for (int i = RUNS.size() - 1; i >= 0; i--) {
            Run r = RUNS.get(i);
            if (!advance(level, r, particles, cam, maxSq)) RUNS.remove(i);
        }
        // keyframes come due in order of insertion per display; a removal frees its slot
        for (int i = 0; i < KEYS.size(); ) {
            Key k = KEYS.get(i);
            if (k.at > now) {
                i++;
                continue;
            }
            KEYS.set(i, KEYS.get(KEYS.size() - 1));
            KEYS.remove(KEYS.size() - 1);
            Display.BlockDisplay d = k.display;
            if (k.to == null) {
                live--;
                if (!d.isRemoved()) level.removeEntity(d.getId(), Entity.RemovalReason.DISCARDED);
            } else if (!d.isRemoved()) {
                d.setTransformationInterpolationDelay(0);
                d.setTransformationInterpolationDuration(k.ticks);
                d.setTransformation(k.to);
            }
        }
        if (RUNS.isEmpty() && KEYS.isEmpty()) owner = null;
    }

    /** Squared horizontal distance from the camera to the middle of column {@code bx, bz}. */
    private static double dist2(Vec3 cam, int bx, int bz) {
        double dx = bx + 0.5 - cam.x, dz = bz + 0.5 - cam.z;
        return dx * dx + dz * dz;
    }

    /**
     * One tick of a run, the same steps as the server's timer; false once it is over. Columns {@code maxSq} or more from
     * the camera (horizontally) are skipped (a quake column is still marked done, so it is not retried).
     */
    private static boolean advance(ClientLevel level, Run r, boolean particles, Vec3 cam, double maxSq) {
        HeavePayload p = r.p;
        if (r.d > p.size()) return false;
        if (p.kind() == HeavePayload.QUAKE) {
            HeavePayload.ring(p.x(), p.z(), r.d, p.width(), r.done, (bx, bz, w) -> {
                if (live >= MAX_LIVE || dist2(cam, bx, bz) >= maxSq) return;
                BlockPos ground = BossFx.groundAt(level, bx, p.y(), bz);
                if (ground == null) return;
                heave(level, ground, HeavePayload.heavePop(p.seed(), bx, bz, p.rise() * (w == 0 ? 1 : HeavePayload.SUB_RISE)), particles);
            });
        } else {
            int index = r.step++;
            double x = p.x() + p.dirX() * r.d, z = p.z() + p.dirZ() * r.d;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (live < MAX_LIVE && dist2(cam, bx, bz) < maxSq) {
                BlockPos ground = BossFx.groundAt(level, bx, p.y(), bz);
                if (ground != null) spike(level, ground, HeavePayload.spike(p.seed(), index), r.look, particles);
            }
        }
        r.d += p.speed();
        return true;
    }

    private static void heave(ClientLevel level, BlockPos ground, Transformation pop, boolean particles) {
        BlockState data = level.getBlockState(ground);
        Display.BlockDisplay d = display(level, ground, data, HeavePayload.heaveRest());
        BlockPos above = ground.above();
        d.setBrightnessOverride(new Brightness(level.getBrightness(LightLayer.BLOCK, above), Math.max(8, level.getBrightness(LightLayer.SKY, above))));
        add(level, d);
        schedule(d, HeavePayload.POP_AT, pop, HeavePayload.POP_TICKS);
        schedule(d, HeavePayload.SETTLE_AT, HeavePayload.heaveRest(), HeavePayload.SETTLE_TICKS);
        schedule(d, HeavePayload.HEAVE_GONE, null, 0);
        if (particles) burst(level, new BlockParticleOption(ParticleTypes.BLOCK, data), ground.getX() + 0.5, ground.getY() + 1.05, ground.getZ() + 0.5, 5, 0.3, 0.05, 0.1);
    }

    private static void spike(ClientLevel level, BlockPos ground, HeavePayload.Spike spike, @Nullable BlockState look, boolean particles) {
        BlockState data = look != null ? look : level.getBlockState(ground);
        Display.BlockDisplay d = display(level, ground, data, spike.hidden());
        add(level, d);
        schedule(d, HeavePayload.SPIKE_RISE_AT, spike.risen(), HeavePayload.SPIKE_RISE_TICKS);
        schedule(d, HeavePayload.SPIKE_SINK_AT, spike.sunk(), HeavePayload.SPIKE_SINK_TICKS);
        schedule(d, HeavePayload.SPIKE_GONE, null, 0);
        if (particles) {
            double x = ground.getX() + 0.5, y = ground.getY() + 1.1, z = ground.getZ() + 0.5;
            burst(level, new BlockParticleOption(ParticleTypes.BLOCK, data), x, y, z, 14, 0.4, 0.2, 0.15);
            burst(level, ParticleTypes.DUST_PLUME, x, y, z, 4, 0.2, 0.1, 0.02);
        }
    }

    private static Display.BlockDisplay display(ClientLevel level, BlockPos at, BlockState state, Transformation start) {
        Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
        if (nextId <= Integer.MIN_VALUE + 1) nextId = FIRST_ID;   // never wraps round into real ids
        d.setId(nextId--);
        d.setPos(at.getX(), at.getY(), at.getZ());
        // a culling box (centred on the block corner, half-width 1, 3 tall: the block, its pop and tilt, a risen fissure
        // spike): without a width and height a display is never frustum culled
        d.setWidth(2f);
        d.setHeight(3f);
        d.setBlockState(state);
        d.setTransformation(start);
        return d;
    }

    private static void add(ClientLevel level, Display.BlockDisplay d) {
        level.addEntity(d);
        live++;
    }

    private static void schedule(Display.BlockDisplay d, int after, @Nullable Transformation to, int ticks) {
        KEYS.add(new Key(now + after, d, to, ticks));
    }

    /** A vanilla particle burst ({@code ServerLevel.sendParticles} spread: gaussian offsets and speeds). */
    private static void burst(ClientLevel level, ParticleOptions p, double x, double y, double z, int count, double h, double v, double speed) {
        for (int i = 0; i < count; i++) {
            level.addParticle(p, x + RANDOM.nextGaussian() * h, y + RANDOM.nextGaussian() * v, z + RANDOM.nextGaussian() * h,
                    RANDOM.nextGaussian() * speed, RANDOM.nextGaussian() * speed, RANDOM.nextGaussian() * speed);
        }
    }
}
