package net.axther.aftrhrs.feature.bossfx;

import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.entity.ability.Fx;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Display-entity boss effects (port of Feather's bossfx; the MythicMobs mechanics featherquake / featherfissure /
 * featherpillar). Boss skills call the static methods; they do nothing while {@code modules.boss-fx} is off (as the
 * mechanics did not exist without Feather).
 * <ul>
 *   <li>{@link #quake}: an expanding ring that heaves up copies of the real ground blocks. Hits players standing on the
 *       ground as the wave passes them, so jumping over it dodges it.</li>
 *   <li>{@link #fissure}: a line of earth spikes erupting from the caster toward a target.</li>
 *   <li>{@link #pillar}: a giant item (Wukong's staff) telegraphed on the ground, then slammed down from the sky.</li>
 * </ul>
 * Nothing changes real blocks. Displays are never saved ({@link Entity#shouldBeSaved()} is false), carry the
 * {@link #TAG} tag, and are removed when the module stops; a tagged one found on load is removed too.
 */
public final class BossFx {
    /** Entity tag on every display this engine spawns. */
    public static final String TAG = "aftrhrs_bossfx";
    /** Hard cap so a misconfigured skill can never flood a chunk with displays. */
    private static final int MAX_LIVE = 1500;
    /**
     * Most server displays one quake makes for players without the heave channel; the fallback also stops while
     * {@code MAX_LIVE - FALLBACK_CAP} are live, so quakes never push {@link SetPieces#full()} over.
     */
    static final int FALLBACK_CAP = 300;
    private static final Set<Entity> LIVE = new HashSet<>();
    static volatile boolean enabled;

    private BossFx() {
    }

    /** True while modules.boss-fx runs. */
    public static boolean enabled() {
        return enabled;
    }

    static void shutdown() {
        for (Entity e : LIVE) if (!e.isRemoved()) e.discard();
        LIVE.clear();
    }

    static int liveCount() {
        for (Iterator<Entity> it = LIVE.iterator(); it.hasNext(); ) if (it.next().isRemoved()) it.remove();
        return LIVE.size();
    }

    /** A hit: damage, upward and outward knockback, and whether only players on the ground are hit. */
    public record Hit(double damage, double knockUp, double knockOut, boolean groundOnly) {
    }

    // ------------------------------------------------------------------ quake

    /**
     * @param radius how far the ring travels (blocks)
     * @param speed  blocks per tick the ring advances
     * @param rise   how high the heaved blocks pop (blocks)
     * @param width  ring thickness in blocks (1-3)
     */
    public static void quake(ServerLevel level, Vec3 origin, @Nullable Entity source, double radius, double speed, double rise, int width,
                             @Nullable Hit hit) {
        if (!enabled) return;
        final double cx = origin.x, cz = origin.z, baseY = origin.y;
        final long seed = ThreadLocalRandom.current().nextLong();
        // the heaving ground is drawn by each client (HeavePayload); only players without the channel get server displays
        final List<ServerPlayer> legacy = sendHeave(level, origin, radius, new HeavePayload(HeavePayload.QUAKE, cx, baseY, cz, 0f, 0f, radius, speed, rise,
                (byte) width, seed, -1));
        final LongSet done = legacy.isEmpty() ? null : new LongOpenHashSet();
        final int[] fallback = {0};
        final Set<UUID> struck = new HashSet<>();
        final double[] d = {HeavePayload.QUAKE_START};
        final int[] tick = {0};
        final Tasks.Task[] task = new Tasks.Task[1];
        task[0] = Tasks.timer(1, 1, () -> {
            if (d[0] > radius || !enabled) {
                task[0].cancel();
                return;
            }
            if (done != null && fallback[0] < FALLBACK_CAP) {
                HeavePayload.ring(cx, cz, d[0], width, done, (bx, bz, w) -> {
                    if (fallback[0] >= FALLBACK_CAP || LIVE.size() >= MAX_LIVE - FALLBACK_CAP) return;
                    BlockPos ground = groundAt(level, bx, baseY, bz);
                    if (ground == null) return;
                    fallback[0]++;
                    heave(level, legacy, ground, HeavePayload.heavePop(seed, bx, bz, rise * (w == 0 ? 1 : HeavePayload.SUB_RISE)));
                });
            }
            if ((tick[0]++ & 1) == 0) {
                BlockPos g = groundAt(level, (int) Math.floor(cx + d[0]), baseY, (int) Math.floor(cz));
                SoundEvent s = g == null ? SoundEvents.ROOTED_DIRT_BREAK : level.getBlockState(g).getSoundType().getBreakSound();
                level.playSound(null, cx, baseY, cz, s, SoundSource.HOSTILE, 1.4f, 0.6f);
            }
            if (hit != null && hit.damage() > 0) {
                for (ServerPlayer p : level.players()) {
                    if (!targetable(p) || struck.contains(p.getUUID())) continue;
                    double dx = p.getX() - cx, dz = p.getZ() - cz;
                    double hd = Math.sqrt(dx * dx + dz * dz);
                    if (Math.abs(hd - d[0]) > 0.9 + width * 0.45 || Math.abs(p.getY() - baseY) > 3) continue;
                    if (hit.groundOnly() && !grounded(p)) continue;
                    struck.add(p.getUUID());
                    strike(level, p, source, hit, new Vec3(dx, 0, dz));
                }
            }
            d[0] += speed;
        });
    }

    /**
     * Fallback for players without the {@link HeavePayload} channel: a copy of the block pops up, tilts, and settles back,
     * as a server display shown only to them (players with the channel draw their own).
     */
    private static void heave(ServerLevel level, List<ServerPlayer> legacy, BlockPos ground, Transformation pop) {
        if (LIVE.size() >= MAX_LIVE) return;
        BlockState data = level.getBlockState(ground);
        Display.BlockDisplay d = fallbackDisplay(level, ground, data);
        d.setTransformation(HeavePayload.heaveRest());
        BlockPos above = ground.above();
        d.setBrightnessOverride(new Brightness(level.getBrightness(LightLayer.BLOCK, above), Math.max(8, level.getBrightness(LightLayer.SKY, above))));
        add(level, d);
        BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, data);
        for (ServerPlayer p : legacy) {
            level.sendParticles(p, dust, false, false, ground.getX() + 0.5, ground.getY() + 1.05, ground.getZ() + 0.5, 5, 0.3, 0.05, 0.3, 0.1);
        }
        keyframe(d, HeavePayload.POP_AT, HeavePayload.POP_TICKS, pop);
        keyframe(d, HeavePayload.SETTLE_AT, HeavePayload.SETTLE_TICKS, HeavePayload.heaveRest());
        Tasks.later(HeavePayload.HEAVE_GONE, () -> remove(d));
    }

    private static void keyframe(Display d, int at, int ticks, Transformation to) {
        Tasks.later(at, () -> {
            if (d.isRemoved()) return;
            d.setTransformationInterpolationDelay(0);
            d.setTransformationInterpolationDuration(ticks);
            d.setTransformation(to);
        });
    }

    /**
     * Sends a heave to every player in range that has the channel ({@code max(64, size + 48)} blocks of the origin) and
     * returns the players in range that don't (they get the server fallback).
     */
    private static List<ServerPlayer> sendHeave(ServerLevel level, Vec3 origin, double size, HeavePayload payload) {
        double r = Math.max(64, size + 48);
        List<ServerPlayer> legacy = List.of();
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(origin) > r * r) continue;
            if (ServerPlayNetworking.canSend(p, HeavePayload.TYPE)) {
                ServerPlayNetworking.send(p, payload);
            } else {
                if (legacy.isEmpty()) legacy = new ArrayList<>(2);
                legacy.add(p);
            }
        }
        return legacy;
    }

    // ------------------------------------------------------------------ fissure

    /** Earth spikes erupt one after another from {@code from} toward {@code toward}. */
    public static void fissure(ServerLevel level, Vec3 from, Vec3 toward, @Nullable Entity source, double length, double speed, @Nullable Hit hit) {
        fissure(level, from, toward, source, length, speed, hit, null);
    }

    /** {@link #fissure} with spikes of {@code look} instead of the ground's own block (the Glacial Wyrm's ice). */
    public static void fissure(ServerLevel level, Vec3 from, Vec3 toward, @Nullable Entity source, double length, double speed, @Nullable Hit hit,
                               @Nullable BlockState look) {
        if (!enabled) return;
        Vec3 dir = new Vec3(toward.x - from.x, 0, toward.z - from.z);
        if (dir.lengthSqr() < 1e-4 && source != null) dir = source.getLookAngle().multiply(1, 0, 1);
        if (dir.lengthSqr() < 1e-4) return;
        final Vec3 step = dir.normalize();
        final Set<UUID> struck = new HashSet<>();
        final double baseY = from.y;
        final long seed = ThreadLocalRandom.current().nextLong();
        // the spikes are drawn by each client (HeavePayload); only players without the channel get server displays
        final List<ServerPlayer> legacy = sendHeave(level, from, length, new HeavePayload(HeavePayload.FISSURE, from.x, baseY, from.z, (float) step.x,
                (float) step.z, length, speed, 0, (byte) 0, seed, look == null ? -1 : Block.getId(look)));
        final double[] d = {HeavePayload.FISSURE_START};
        final int[] k = {0};
        final Tasks.Task[] task = new Tasks.Task[1];
        task[0] = Tasks.timer(1, 1, () -> {
            if (d[0] > length || !enabled) {
                task[0].cancel();
                return;
            }
            int index = k[0]++;
            Vec3 p = from.add(step.scale(d[0]));
            // still worked out here: it gates the hit test
            BlockPos ground = groundAt(level, (int) Math.floor(p.x), baseY, (int) Math.floor(p.z));
            if (ground != null) {
                if (!legacy.isEmpty()) spike(level, legacy, ground, HeavePayload.spike(seed, index), look);
                level.playSound(null, p.x, p.y, p.z, SoundEvents.POINTED_DRIPSTONE_LAND, SoundSource.HOSTILE, 1.2f, 0.6f);
                if (hit != null && hit.damage() > 0) {
                    for (ServerPlayer pl : level.players()) {
                        if (!targetable(pl) || struck.contains(pl.getUUID())) continue;
                        double dx = pl.getX() - p.x, dz = pl.getZ() - p.z;
                        if (dx * dx + dz * dz > 1.7 * 1.7 || Math.abs(pl.getY() - ground.getY() - 1) > 2.5) continue;
                        struck.add(pl.getUUID());
                        strike(level, pl, source, hit, new Vec3(dx, 0, dz));
                    }
                }
            }
            d[0] += speed;
        });
    }

    /** Fallback for players without the {@link HeavePayload} channel: one earth spike as a server display shown only to them. */
    private static void spike(ServerLevel level, List<ServerPlayer> legacy, BlockPos ground, HeavePayload.Spike spike, @Nullable BlockState look) {
        if (LIVE.size() >= MAX_LIVE - FALLBACK_CAP) return;
        BlockState data = look != null ? look : level.getBlockState(ground);
        Display.BlockDisplay d = fallbackDisplay(level, ground, data);
        d.setTransformation(spike.hidden());
        add(level, d);
        double x = ground.getX() + 0.5, y = ground.getY() + 1.1, z = ground.getZ() + 0.5;
        BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, data);
        for (ServerPlayer p : legacy) {
            level.sendParticles(p, dust, false, false, x, y, z, 14, 0.4, 0.2, 0.4, 0.15);
            level.sendParticles(p, ParticleTypes.DUST_PLUME, false, false, x, y, z, 4, 0.2, 0.1, 0.2, 0.02);
        }
        keyframe(d, HeavePayload.SPIKE_RISE_AT, HeavePayload.SPIKE_RISE_TICKS, spike.risen());
        keyframe(d, HeavePayload.SPIKE_SINK_AT, HeavePayload.SPIKE_SINK_TICKS, spike.sunk());
        Tasks.later(HeavePayload.SPIKE_GONE, () -> remove(d));
    }

    // ------------------------------------------------------------------ pillar

    /**
     * A giant copy of {@code item} hangs over {@code target}, a ring warns where it will land for {@code warn} ticks,
     * then it slams down, hits everything within {@code radius}, sends a quake out and sinks away.
     */
    public static void pillar(ServerLevel level, Vec3 target, @Nullable Entity source, @Nullable ItemStack item, float scale, int warn, int fall,
                              int linger, double radius, @Nullable Hit hit, double quakeRadius) {
        if (!enabled || LIVE.size() >= MAX_LIVE) return;
        BlockPos ground = groundAt(level, (int) Math.floor(target.x), target.y, (int) Math.floor(target.z));
        double gy = ground == null ? target.y : ground.getY() + 1;
        Vec3 impact = new Vec3(Math.floor(target.x) + 0.5, gy, Math.floor(target.z) + 0.5);
        double half = 1.5 * scale;   // the staff model is 3 blocks long at scale 1
        Vec3 sky = impact.add(0, half + 26, 0);
        Vec3 landed = impact.add(0, half - 1.2, 0);
        ItemStack shown = item == null || item.isEmpty() ? new ItemStack(Items.END_ROD) : item;
        Display.ItemDisplay staff = new Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }
        };
        staff.setPos(sky.x, sky.y, sky.z);
        staff.setItemStack(shown);
        staff.setItemTransform(ItemDisplayContext.NONE);
        staff.setBrightnessOverride(new Brightness(15, 15));
        staff.setViewRange(4f);
        staff.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        staff.setGlowingTag(true);
        staff.setGlowColorOverride(0xF6C64A);
        add(level, staff);
        level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2f, 0.5f);
        // telegraph: a shrinking golden ring and a beam where it will land
        final int[] t = {0};
        final Tasks.Task[] ring = new Tasks.Task[1];
        DustParticleOptions gold = new DustParticleOptions(0xF2B33D, 1.6f);
        ring[0] = Tasks.timer(1, 2, () -> {
            if (t[0] >= warn || staff.isRemoved()) {
                ring[0].cancel();
                return;
            }
            double r = radius * (1.0 - 0.5 * t[0] / Math.max(1, warn));
            // one ring payload per viewer (per-point packets for clients without the channel), drifting round as it shrinks
            Fx.ring(level, impact, gold, r, 28, 1, 0.15, t[0] * 0.15);
            level.sendParticles(ParticleTypes.END_ROD, impact.x, impact.y + 0.2 + (t[0] % 10) * 1.5, impact.z, 2, 0.1, 0.4, 0.1, 0.01);
            t[0] += 2;
        });
        Tasks.later(warn, () -> {
            if (staff.isRemoved()) return;
            staff.setPosRotInterpolationDuration(Math.max(1, Math.min(59, fall)));
            staff.teleportTo(landed.x, landed.y, landed.z);
            level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.HOSTILE, 2f, 0.5f);
        });
        Tasks.later(warn + fall, () -> {
            level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.5f, 0.6f);
            level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 2f, 0.5f);
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y, impact.z, 1, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.FLAME, impact.x, impact.y, impact.z, 60, radius / 2, 0.4, radius / 2, 0.05);
            if (ground != null) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(ground)), impact.x, impact.y, impact.z, 80,
                        radius / 2, 0.3, radius / 2, 0.2);
            }
            if (hit != null && hit.damage() > 0) {
                for (ServerPlayer p : level.players()) {
                    if (!targetable(p)) continue;
                    double dx = p.getX() - impact.x, dz = p.getZ() - impact.z;
                    if (dx * dx + dz * dz > radius * radius || Math.abs(p.getY() - gy) > 4) continue;
                    strike(level, p, source, hit, new Vec3(dx, 0, dz));
                }
            }
            if (quakeRadius > 0) {
                quake(level, impact, source, quakeRadius, 0.55, 0.55, 2, hit == null ? null : new Hit(hit.damage() * 0.5, 0.5, 0.6, true));
            }
        });
        Tasks.later(warn + fall + linger, () -> {
            if (staff.isRemoved()) return;
            staff.setPosRotInterpolationDuration(30);
            staff.teleportTo(landed.x, landed.y - 2 * half, landed.z);
        });
        Tasks.later(warn + fall + linger + 32, () -> remove(staff));
    }

    // ------------------------------------------------------------------ camera shake

    /**
     * Shakes the camera of every player within {@code radius} of {@code at}: {@code strength} degrees at the center,
     * fading linearly to a third at the edge, dying down over {@code ticks}. Clients without the mod channel skip it.
     */
    public static void shake(ServerLevel level, Vec3 at, double radius, float strength, int ticks) {
        if (!enabled) return;
        for (ServerPlayer p : level.players()) {
            double d = p.position().distanceTo(at);
            if (d > radius || !net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, ShakePayload.TYPE)) continue;
            float s = (float) (strength * (1 - 0.66 * d / radius));
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new ShakePayload(s, ticks));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Display.BlockDisplay blockDisplay(ServerLevel level, BlockPos at, BlockState state) {
        Display.BlockDisplay d = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }
        };
        d.setPos(at.getX(), at.getY(), at.getZ());
        d.setBlockState(state);
        return d;
    }

    /** A quake / fissure fallback display: tracked only by players without the heave channel (the rest draw their own). */
    private static Display.BlockDisplay fallbackDisplay(ServerLevel level, BlockPos at, BlockState state) {
        Display.BlockDisplay d = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }

            @Override
            public boolean broadcastToPlayer(ServerPlayer p) {
                return !ServerPlayNetworking.canSend(p, HeavePayload.TYPE);
            }
        };
        d.setPos(at.getX(), at.getY(), at.getZ());
        d.setBlockState(state);
        return d;
    }

    static void add(ServerLevel level, Entity e) {
        e.addTag(TAG);
        LIVE.add(e);   // before adding: the entity-load listener removes tagged displays it doesn't know
        if (!level.addFreshEntity(e)) LIVE.remove(e);
    }

    static boolean isLive(Entity e) {
        return LIVE.contains(e);
    }

    /** Topmost solid full block with a passable block above it, searched from 3 above to 5 below {@code y}. */
    public static @Nullable BlockPos groundAt(BlockGetter level, int x, double y, int z) {
        int top = (int) Math.floor(y) + 3, bottom = (int) Math.floor(y) - 5;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int by = top; by >= bottom; by--) {
            p.set(x, by, z);
            BlockState s = level.getBlockState(p);
            if (s.isSolidRender() && s.isCollisionShapeFullBlock(level, p)) {
                BlockPos up = p.above();
                if (level.getBlockState(up).getCollisionShape(level, up).isEmpty()) return p.immutable();
            }
        }
        return null;
    }

    /** A player in survival or adventure, alive (MythicMobs' PlayersInRadius skip creative / spectators for damage anyway). */
    public static boolean targetable(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative();
    }

    static boolean grounded(ServerPlayer p) {
        if (p.onGround()) return true;
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.3, p.getZ());
        return !p.level().getBlockState(below).getCollisionShape(p.level(), below).isEmpty();
    }

    static void strike(ServerLevel level, LivingEntity p, @Nullable Entity source, Hit hit, Vec3 away) {
        p.hurtServer(level, source instanceof LivingEntity le ? level.damageSources().mobAttack(le) : level.damageSources().generic(), (float) hit.damage());
        Vec3 v = away.lengthSqr() < 1e-4 ? Vec3.ZERO : away.normalize().scale(hit.knockOut());
        p.setDeltaMovement(p.getDeltaMovement().add(v.x, hit.knockUp(), v.z));
        p.needsSync = true;
    }

    static void remove(Entity e) {
        LIVE.remove(e);
        if (!e.isRemoved()) e.discard();
    }
}
