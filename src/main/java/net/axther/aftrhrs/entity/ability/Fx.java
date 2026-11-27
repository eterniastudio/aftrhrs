package net.axther.aftrhrs.entity.ability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Particles, sounds and effects the way MythicMobs' particles / sound / potion mechanics play them. */
public final class Fx {
    private Fx() {
    }

    /** A particle by MythicMobs (Bukkit) name; {@code block} needs {@code m}/{@code material}, {@code dust} {@code color}/{@code size}. */
    public static @Nullable ParticleOptions particle(String name, Map<String, String> args) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("block") || n.equals("block_crack") || n.equals("falling_dust")) {
            String m = args.getOrDefault("m", args.getOrDefault("material", "DIRT"));
            Block b = block(m);
            return new BlockParticleOption(n.equals("falling_dust") ? ParticleTypes.FALLING_DUST : ParticleTypes.BLOCK, (b == null ? Blocks.DIRT : b).defaultBlockState());
        }
        if (n.equals("dust") || n.equals("redstone")) {
            String c = args.getOrDefault("color", "#FFFFFF").replace("#", "");
            int rgb;
            try {
                rgb = Integer.parseInt(c, 16);
            } catch (NumberFormatException e) {
                rgb = 0xFFFFFF;
            }
            return new DustParticleOptions(rgb, (float) parse(args.getOrDefault("size", "1"), 1));
        }
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.withDefaultNamespace(alias(n)));
        return type instanceof SimpleParticleType s ? s : null;
    }

    private static String alias(String n) {
        return switch (n) {
            case "villager_happy" -> "happy_villager";
            case "villager_angry" -> "angry_villager";
            case "spell_witch" -> "witch";
            case "water_splash" -> "splash";
            case "smoke_normal" -> "smoke";
            case "explosion_normal" -> "poof";
            case "fireworks_spark" -> "firework";
            case "drip_water" -> "dripping_water";
            default -> n;
        };
    }

    public static @Nullable Block block(String id) {
        String k = id.toLowerCase(Locale.ROOT);
        if (k.contains("[")) k = k.substring(0, k.indexOf('['));
        Identifier key = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
        if (key == null) return null;
        Block b = BuiltInRegistries.BLOCK.getValue(key);
        return b == Blocks.AIR && !k.endsWith("air") ? null : b;
    }

    public static void particles(Entity at, ParticleOptions p, int amount, double hs, double vs, double y, double speed) {
        if (at.level() instanceof ServerLevel level) level.sendParticles(p, at.getX(), at.getY() + y, at.getZ(), amount, hs, vs, hs, speed);
    }

    public static void particles(ServerLevel level, Vec3 pos, ParticleOptions p, int amount, double hs, double vs, double speed) {
        level.sendParticles(p, pos.x, pos.y, pos.z, amount, hs, vs, hs, speed);
    }

    /** Where a particle goes ({@link #forEachLinePoint} / {@link #forEachRingPoint}). */
    @FunctionalInterface
    public interface Point {
        void at(double x, double y, double z);
    }

    /** Vanilla's particle range: packets go to players within 32 blocks. */
    private static final double VIEW = 32;

    /**
     * The points of {@link #line}, every {@code step} (at least 0.1) blocks from {@code a} up to {@code b}; a single point
     * at {@code a} when they coincide. Shared by the server and the client ({@link FxLinePayload}) so the spacing matches.
     */
    public static void forEachLinePoint(Vec3 a, Vec3 b, double step, Point out) {
        double len = a.distanceTo(b);
        if (len < 1e-6) {
            out.at(a.x, a.y, a.z);
            return;
        }
        double dx = (b.x - a.x) / len, dy = (b.y - a.y) / len, dz = (b.z - a.z) / len;
        double s = Math.max(0.1, step);
        for (double t = 0; t <= len; t += s) out.at(a.x + dx * t, a.y + dy * t, a.z + dz * t);
    }

    /** The points of {@link #ring}: {@code points} spots of {@code radius} round {@code center}, {@code y} up. */
    public static void forEachRingPoint(Vec3 center, double radius, int points, double y, Point out) {
        forEachRingPoint(center, radius, points, y, 0, out);
    }

    /** {@link #forEachRingPoint(Vec3, double, int, double, Point)} turned by {@code phase} radians. */
    public static void forEachRingPoint(Vec3 center, double radius, int points, double y, double phase, Point out) {
        for (int i = 0; i < points; i++) ringPoint(center, radius, points, i, y, phase, out);
    }

    /**
     * Spots {@code [first, first + count)} of the {@code points}-spot ring ({@link #arc}), clamped to the ring: the same
     * angles as the whole ring, so two arcs that split it line up.
     */
    public static void forEachRingPoint(Vec3 center, double radius, int points, int first, int count, double y, Point out) {
        int from = Math.max(0, first), to = (int) Math.min(points, (long) first + count);
        for (int i = from; i < to; i++) ringPoint(center, radius, points, i, y, 0, out);
    }

    private static void ringPoint(Vec3 center, double radius, int points, int i, double y, double phase, Point out) {
        double a = 2 * Math.PI * i / points + phase;
        out.at(center.x + Math.cos(a) * radius, center.y + y, center.z + Math.sin(a) * radius);
    }

    /**
     * A straight line of particles from {@code a} to {@code b} (MythicMobs particleline), every {@code step} blocks. Players
     * within 32 blocks of the line get one {@link FxLinePayload}; those without the channel get a packet per point.
     */
    public static void line(ServerLevel level, Vec3 a, Vec3 b, ParticleOptions p, double step) {
        FxLinePayload payload = null;
        for (ServerPlayer sp : level.players()) {
            if (distanceSqrToSegment(sp.position(), a, b) >= VIEW * VIEW) continue;
            if (ServerPlayNetworking.canSend(sp, FxLinePayload.TYPE)) {
                if (payload == null) payload = new FxLinePayload(p, a, b, (float) step);
                ServerPlayNetworking.send(sp, payload);
            } else {
                forEachLinePoint(a, b, step, (x, y, z) -> level.sendParticles(sp, p, false, false, x, y, z, 1, 0, 0, 0, 0));
            }
        }
    }

    /**
     * {@code particlering{p;radius;points;amount;y}} round {@code center}: one {@link FxLinePayload.Ring} to each player in
     * range with the channel, a packet per point to the rest.
     */
    public static void ring(ServerLevel level, Vec3 center, ParticleOptions p, double radius, int points, int amount, double y) {
        ring(level, center, p, radius, points, amount, y, 0);
    }

    /** {@link #ring(ServerLevel, Vec3, ParticleOptions, double, int, int, double)} turned by {@code phase} radians. */
    public static void ring(ServerLevel level, Vec3 center, ParticleOptions p, double radius, int points, int amount, double y, double phase) {
        if (points <= 0) return;
        FxLinePayload.Ring payload = null;
        double reach = VIEW + Math.abs(radius);
        for (ServerPlayer sp : level.players()) {
            if (sp.position().distanceToSqr(center.x, center.y + y, center.z) >= reach * reach) continue;
            if (ServerPlayNetworking.canSend(sp, FxLinePayload.Ring.TYPE)) {
                if (payload == null) payload = new FxLinePayload.Ring(p, center, (float) radius, points, amount, (float) y, (float) phase);
                ServerPlayNetworking.send(sp, payload);
            } else {
                forEachRingPoint(center, radius, points, y, phase,
                        (px, py, pz) -> level.sendParticles(sp, p, false, false, px, py, pz, amount, 0, 0, 0, 0));
            }
        }
    }

    /**
     * Spots {@code [first, first + count)} of a {@code points}-spot ring (one particle each, no spread): one
     * {@link FxLinePayload.Arc} to each player in range with the channel, a packet per spot to the rest.
     */
    public static void arc(ServerLevel level, Vec3 center, ParticleOptions p, double radius, int points, int first, int count, double y) {
        if (count <= 0 || points <= 0) return;
        FxLinePayload.Arc payload = null;
        double reach = VIEW + Math.abs(radius);
        for (ServerPlayer sp : level.players()) {
            if (sp.position().distanceToSqr(center.x, center.y + y, center.z) >= reach * reach) continue;
            if (ServerPlayNetworking.canSend(sp, FxLinePayload.Arc.TYPE)) {
                if (payload == null) payload = new FxLinePayload.Arc(p, center, (float) radius, points, first, count, (float) y);
                ServerPlayNetworking.send(sp, payload);
            } else {
                forEachRingPoint(center, radius, points, first, count, y,
                        (px, py, pz) -> level.sendParticles(sp, p, false, false, px, py, pz, 1, 0, 0, 0, 0));
            }
        }
    }

    /**
     * Particle spots that would each have been a {@code sendParticles(p, x, y, z, count, dx, dy, dz, speed)} packet, sent
     * as one {@link FxLinePayload.Points} to each player in range with the channel; the rest get exactly those packets.
     * The server still picks every spot (same random draws, same ground checks).
     */
    public static final class PointBatch {
        private final ParticleOptions p;
        private final Vec3 origin;
        private final int count;
        private final double dx, dy, dz, speed;
        private float[] off = new float[48];
        private int n;

        public PointBatch(ParticleOptions p, Vec3 origin, int count, double dx, double dy, double dz, double speed) {
            this.p = p;
            this.origin = origin;
            this.count = count;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.speed = speed;
        }

        public void add(double x, double y, double z) {
            if (n >= FxLinePayload.Points.MAX) return;
            if (n * 3 + 3 > off.length) off = java.util.Arrays.copyOf(off, off.length * 2);
            off[n * 3] = (float) (x - origin.x);
            off[n * 3 + 1] = (float) (y - origin.y);
            off[n * 3 + 2] = (float) (z - origin.z);
            n++;
        }

        public int size() {
            return n;
        }

        /** To every player within vanilla's 32 blocks (plus {@code radius}, the spread of the spots round the origin). */
        public void send(ServerLevel level, double radius) {
            if (n == 0) return;
            double reach = VIEW + radius + Math.max(dx, Math.max(dy, dz));
            FxLinePayload.Points payload = null;
            for (ServerPlayer sp : level.players()) {
                if (sp.position().distanceToSqr(origin) >= reach * reach) continue;
                if (ServerPlayNetworking.canSend(sp, FxLinePayload.Points.TYPE)) {
                    if (payload == null) {
                        payload = new FxLinePayload.Points(p, origin, java.util.Arrays.copyOf(off, n * 3), count, (float) dx, (float) dy, (float) dz,
                                (float) speed);
                    }
                    ServerPlayNetworking.send(sp, payload);
                } else {
                    for (int i = 0; i < n; i++) {
                        level.sendParticles(sp, p, false, false, origin.x + off[i * 3], origin.y + off[i * 3 + 1], origin.z + off[i * 3 + 2], count,
                                dx, dy, dz, speed);
                    }
                }
            }
        }
    }

    private static double distanceSqrToSegment(Vec3 q, Vec3 a, Vec3 b) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 < 1e-12 ? 0 : ((q.x - a.x) * abx + (q.y - a.y) * aby + (q.z - a.z) * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        double dx = a.x + abx * t - q.x, dy = a.y + aby * t - q.y, dz = a.z + abz * t - q.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Sound ids already reported as unknown (each is logged once). */
    private static final java.util.Set<String> UNKNOWN_SOUNDS = ConcurrentHashMap.newKeySet();

    /** Plays a sound id (vanilla or namespaced); an id no sound event has is skipped and logged once. */
    public static void sound(Entity at, String id, float volume, float pitch) {
        SoundEvent e = new Species.Sound(id, volume, pitch).event();
        if (e != null) at.level().playSound(null, at.getX(), at.getY(), at.getZ(), e, SoundSource.NEUTRAL, volume, pitch);
        else if (UNKNOWN_SOUNDS.add(id)) AftrHrs.LOG.warn("Unknown sound id '{}' (skipped)", id);
    }

    /** Effects by raw type name (MOB_EFFECT is a frozen built-in registry, so this never goes stale); empty = unknown. */
    private static final Map<String, Optional<Holder<MobEffect>>> EFFECTS = new ConcurrentHashMap<>();

    public static @Nullable Holder<MobEffect> effect(String type) {
        Optional<Holder<MobEffect>> e = EFFECTS.get(type);
        if (e == null) {
            e = lookupEffect(type);   // may throw on a malformed name, as before (then nothing is cached)
            EFFECTS.put(type, e);
        }
        return e.orElse(null);
    }

    private static Optional<Holder<MobEffect>> lookupEffect(String type) {
        String k = type.toLowerCase(Locale.ROOT);
        k = switch (k) {
            case "increase_damage" -> "strength";
            case "damage_resistance" -> "resistance";
            case "slow" -> "slowness";
            case "fast_digging" -> "haste";
            case "jump" -> "jump_boost";
            default -> k;
        };
        return BuiltInRegistries.MOB_EFFECT.get(Identifier.withDefaultNamespace(k)).map(r -> (Holder<MobEffect>) r);
    }

    /** potion{type;duration;level;hasParticles;hasIcon}. */
    public static void potion(LivingEntity e, String type, int duration, int level, boolean particles, boolean icon) {
        Holder<MobEffect> eff = effect(type);
        if (eff != null) e.addEffect(new MobEffectInstance(eff, duration, Math.max(0, level), false, particles, icon));
    }

    /**
     * Keeps a sustained buff up without re-sending it: re-applies only when it would run out within {@code minLeft}
     * ticks (or is weaker than {@code level}). {@code minLeft} must stay above the calling skill's timer interval (+2),
     * or the buff can lapse between refreshes.
     */
    public static void sustain(LivingEntity e, String type, int ticks, int level, boolean particles, boolean icon, int minLeft) {
        Holder<MobEffect> eff = effect(type);
        if (eff == null) return;
        MobEffectInstance cur = e.getEffect(eff);
        if (cur != null && cur.getAmplifier() >= Math.max(0, level) && (cur.isInfiniteDuration() || cur.getDuration() > minLeft)) return;
        e.addEffect(new MobEffectInstance(eff, ticks, Math.max(0, level), false, particles, icon));
    }

    static double parse(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Runs simple effect lines in order ({@code delay N} waits): particles, sound, potion, state ... on the mob. */
    public static void lines(ArcanaMob mob, JsonArray lines) {
        int at = 0;
        for (JsonElement e : lines) {
            String s = e.getAsString().trim();
            if (s.startsWith("- ")) s = s.substring(2).trim();
            if (s.startsWith("delay ")) {
                at += (int) parse(s.substring(6).trim(), 0);
                continue;
            }
            Line l = Line.parse(s);
            if (l == null) continue;
            if (at == 0) Abilities.effect(mob, l, null);
            else mob.scheduler().after(at, () -> Abilities.effect(mob, l, null));
        }
    }
}
