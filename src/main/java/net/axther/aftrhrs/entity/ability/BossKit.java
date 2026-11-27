package net.axther.aftrhrs.entity.ability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMonster;
import net.axther.aftrhrs.entity.CreatureUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The MythicMobs mechanics and targeters the boss, Wukong and police skills use, as plain helpers: PlayersInRadius,
 * damage, throw, lunge, leap, pull, lightning, summon, particle rings and lines, messages and titles, the global
 * cooldown ({@code gcd} / {@code offgcd}) and the distance / line-of-sight / altitude target conditions.
 */
final class BossKit {
    private BossKit() {
    }

    // ------------------------------------------------------------------ targeters

    /** {@code @PlayersInRadius{r}}: survival / adventure players within {@code r} blocks of {@code center}. */
    static List<ServerPlayer> players(Entity center, double r) {
        List<ServerPlayer> out = new ArrayList<>();
        if (!(center.level() instanceof ServerLevel level)) return out;
        double r2 = r * r;
        for (ServerPlayer p : level.players()) {
            if (p.isAlive() && !p.isSpectator() && !p.isCreative() && p.distanceToSqr(center) <= r2) out.add(p);
        }
        return out;
    }

    /** {@code @PlayersInRadius{r;limit}}: at most {@code limit} of them, picked at random. */
    static List<ServerPlayer> players(Entity center, double r, int limit) {
        List<ServerPlayer> all = players(center, r);
        Collections.shuffle(all);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /** Every player in range for messages (spectators and creative too: MythicMobs messages everyone). */
    static List<ServerPlayer> audience(Entity center, double r) {
        List<ServerPlayer> out = new ArrayList<>();
        if (!(center.level() instanceof ServerLevel level)) return out;
        for (ServerPlayer p : level.players()) if (p.distanceToSqr(center) <= r * r) out.add(p);
        return out;
    }

    /** {@code @LivingInLine{r}} from the caster's chest to {@code to}, players only (the Wukong line attacks). */
    static List<ServerPlayer> playersInLine(Entity from, Vec3 to, double r) {
        List<ServerPlayer> out = new ArrayList<>();
        Vec3 a = from.position().add(0, from.getBbHeight() * 0.6, 0);
        Vec3 ab = to.subtract(a);
        double len2 = ab.lengthSqr();
        for (ServerPlayer p : players(from, Math.sqrt(len2) + r + 2)) {
            Vec3 c = p.position().add(0, p.getBbHeight() / 2, 0);
            double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, c.subtract(a).dot(ab) / len2));
            if (a.add(ab.scale(t)).distanceTo(c) <= r + p.getBbWidth() / 2) out.add(p);
        }
        return out;
    }

    /** {@code @Forward{f}}: the point {@code f} blocks ahead of the caster at its feet. */
    static Vec3 forward(Entity e, double f) {
        Vec3 look = Vec3.directionFromRotation(0, e.getYRot());
        return e.position().add(look.scale(f));
    }

    // ------------------------------------------------------------------ conditions

    static double distance(Entity a, @Nullable Entity b) {
        return b == null ? Double.MAX_VALUE : a.distanceTo(b);
    }

    static boolean sight(Mob self, @Nullable Entity target) {
        return target != null && self.hasLineOfSight(target);
    }

    /** MythicMobs {@code altitude}: height above the ground below. */
    static double altitude(Entity e) {
        BlockPos.MutableBlockPos p = BlockPos.containing(e.getX(), e.getY() - 0.01, e.getZ()).mutable();
        int floor = e.level().getMinY();
        int n = 0;
        while (p.getY() > floor && n++ < 64 && e.level().getBlockState(p).getCollisionShape(e.level(), p).isEmpty()) p.move(0, -1, 0);
        return e.getY() - (p.getY() + 1);
    }

    /** {@code gcd{ticks}}: sets the global cooldown (always replaces a running one). */
    static void gcd(Ctx c, int ticks) {
        c.sched().clearCooldown("gcd");
        c.sched().ready("gcd", ticks);
    }

    /** {@code offgcd}: no global cooldown running. */
    static boolean offGcd(Ctx c) {
        return !c.sched().onCooldown("gcd");
    }

    // ------------------------------------------------------------------ mechanics

    /** {@code damage{amount}} from the caster (an entity attack, armor applies). */
    static void damage(Ctx c, @Nullable Entity target, double amount) {
        if (target == null || target == c.self || !target.isAlive()) return;
        target.hurtServer(c.level(), c.self.damageSources().mobAttack(c.self), (float) amount);
    }

    static void damage(Ctx c, List<? extends Entity> targets, double amount) {
        for (Entity e : targets) damage(c, e, amount);
    }

    static void setVelocity(Entity e, Vec3 v) {
        e.setDeltaMovement(v);
        e.needsSync = true;
        if (e instanceof LivingEntity le) le.resetFallDistance();
    }

    /** {@code throw{velocity;velocityY}}: away from the caster, velocity / 10 horizontally and velocityY / 10 up. */
    static void throwAway(Entity from, @Nullable Entity target, double velocity, double velocityY) {
        if (target == null) return;
        Vec3 d = new Vec3(target.getX() - from.getX(), 0, target.getZ() - from.getZ());
        d = d.lengthSqr() < 1e-4 ? Vec3.ZERO : d.normalize().scale(velocity / 10.0);
        setVelocity(target, new Vec3(d.x, velocityY / 10.0, d.z));
    }

    static void throwAway(Entity from, List<? extends Entity> targets, double velocity, double velocityY) {
        for (Entity e : targets) throwAway(from, e, velocity, velocityY);
    }

    /** {@code lunge{velocity;velocityY}}: the caster flies at the target. */
    static void lunge(Entity self, @Nullable Entity target, double velocity, double velocityY) {
        if (target == null) return;
        Vec3 d = target.position().subtract(self.position());
        Vec3 h = new Vec3(d.x, 0, d.z);
        h = h.lengthSqr() < 1e-4 ? Vec3.ZERO : h.normalize().scale(velocity);
        setVelocity(self, new Vec3(h.x, velocityY, h.z));
    }

    /** {@code leap{velocity=P}}: a ballistic jump that lands on the target after about {@code ticks} ticks (P % power). */
    static void leap(Entity self, @Nullable Entity target, double percent, int ticks) {
        if (target == null) return;
        double t = Math.max(8, ticks);
        double dx = target.getX() - self.getX(), dz = target.getZ() - self.getZ(), dy = target.getY() - self.getY();
        double g = 0.08;
        double vy = (dy + 0.5 * g * t * t) / t;
        double drag = 1.12;   // horizontal air drag over the flight
        setVelocity(self, new Vec3(dx / t * drag, Math.min(2.4, vy * Math.max(0.6, percent / 170.0)), dz / t * drag));
    }

    /** {@code pull{velocity}}: toward the caster, harder from further away. */
    static void pull(Entity self, @Nullable Entity target, double velocity) {
        if (target == null) return;
        Vec3 d = self.position().subtract(target.position());
        double dist = d.length();
        if (dist < 0.5) return;
        Vec3 v = d.normalize().scale(Math.min(2.5, velocity * 0.1 * Math.sqrt(dist) * 1.6));
        setVelocity(target, new Vec3(v.x, Math.max(v.y, dist > 2 ? 0.15 : 0), v.z));
    }

    /** {@code lightning{damage}}: a visual bolt plus the damage (MythicMobs strikes the effect and hurts the target). */
    static void lightning(Ctx c, @Nullable Entity target, double damage) {
        if (target == null) return;
        LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(c.level(), EntitySpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.setPos(target.getX(), target.getY(), target.getZ());
            bolt.setVisualOnly(true);
            c.level().addFreshEntity(bolt);
        }
        if (damage > 0) target.hurtServer(c.level(), c.self.damageSources().lightningBolt(), (float) damage);
    }

    /** {@code summon{type;amount;radius}}: spawns Arcana creatures by entity id around {@code at}. */
    static List<Mob> summon(ServerLevel level, String id, Vec3 at, int amount, double radius) {
        List<Mob> out = new ArrayList<>();
        EntityType<?> type = ArcanaEntities.type(id);
        if (type == null) return out;
        for (int i = 0; i < amount; i++) {
            double a = level.getRandom().nextDouble() * Math.PI * 2, d = radius <= 0 ? 0 : level.getRandom().nextDouble() * radius;
            Vec3 p = at.add(Math.cos(a) * d, 0, Math.sin(a) * d);
            BlockPos ground = BlockPos.containing(p);
            for (int up = 0; up < 4 && !level.getBlockState(ground).getCollisionShape(level, ground).isEmpty(); up++) ground = ground.above();
            Entity e = type.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (!(e instanceof Mob mob)) continue;
            mob.snapTo(p.x, ground.getY(), p.z, level.getRandom().nextFloat() * 360, 0);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(ground), EntitySpawnReason.MOB_SUMMONED, null);
            if (level.addFreshEntity(mob)) out.add(mob);
        }
        return out;
    }

    /** Arcana mobs of a type (by entity id) within {@code r} (MythicMobs @MobsInRadius{types}). */
    static List<Mob> mobs(Entity center, String id, double r) {
        EntityType<?> type = ArcanaEntities.type(id);
        if (type == null) return List.of();
        return center.level().getEntitiesOfClass(Mob.class, new AABB(center.blockPosition()).inflate(r),
                m -> m.getType() == type && m.isAlive() && m.distanceToSqr(center) <= r * r);
    }

    // ------------------------------------------------------------------ effects

    /** {@code particlering{p;radius;points;amount;y}} around {@code center}. */
    static void ring(ServerLevel level, Vec3 center, ParticleOptions p, double radius, int points, int amount, double y) {
        Fx.ring(level, center, p, radius, points, amount, y);
    }

    /** {@code particleline} from the caster ({@code fromY} up) to the target ({@code toY} up). */
    static void line(ServerLevel level, Entity from, double fromY, Vec3 to, ParticleOptions p, double step) {
        Fx.line(level, from.position().add(0, fromY, 0), to, p, step);
    }

    /** {@code message{m}} to every player within {@code r} (legacy {@code &} colors). */
    static void message(Entity center, double r, String legacy) {
        Component c = CreatureUtil.legacy(legacy);
        for (ServerPlayer p : audience(center, r)) p.sendSystemMessage(c);
    }

    /** {@code sendtitle{title;subtitle;d;fi;fo}} to every player within {@code r}. */
    static void title(Entity center, double r, String title, String subtitle, int stay, int fadeIn, int fadeOut) {
        for (ServerPlayer p : audience(center, r)) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
            p.connection.send(new ClientboundSetSubtitleTextPacket(CreatureUtil.legacy(subtitle)));
            p.connection.send(new ClientboundSetTitleTextPacket(CreatureUtil.legacy(title)));
        }
    }

    /** {@code state{s}}: an attack clip that the hurt flinch won't cut off. */
    static void clip(Ctx c, String clip) {
        if (c.mob instanceof ArcanaMonster m) m.action(clip);
        else c.play(clip);
        net.axther.aftrhrs.entity.CreatureSfx.ability(c.mob, c.skill);   // the boss voices its attacks (5 s cooldown)
    }

    /**
     * The boss is mid-attack (an attack clip or a standing phase / taunt): a timed skill waits instead of cutting it off,
     * retried once when the clip ends. True = the caller returns now.
     */
    static boolean waiting(Ctx c) {
        if (!(c.mob instanceof ArcanaMonster m) || !m.inAction() && !m.busy()) return false;
        if (c.sched().ready("retry_" + c.skill, 60)) {
            Entity target = c.target;
            later(c, (m.inAction() ? m.actionLeft() : 20) + 4, () -> Abilities.runSkill(c.mob, c.skill, target));
        }
        return true;
    }

    /** Clip length in ticks (for holds and busy windows). */
    static int ticks(Ctx c, String clip, int def) {
        return c.species().clipTicks(clip, def);
    }

    /** Runs {@code r} after {@code ticks} unless the caster died or was removed meanwhile. */
    static void later(Ctx c, int ticks, Runnable r) {
        c.sched().after(ticks, () -> {
            if (c.self.isAlive() && !c.self.isRemoved()) r.run();
        });
    }

    /** Runs {@code r} {@code times} times, {@code interval} ticks apart, starting now (MythicMobs repeat / repeatInterval). */
    static void repeat(Ctx c, int times, int interval, Runnable r) {
        for (int i = 0; i < times; i++) later(c, i * interval, r);
    }
}
