package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Breeding wild livestock, after the pets' breeding but with no owner and no bond: feed two adults of a species their
 * breed food within {@code breeding.partner-radius} blocks of each other, hearts play, and after
 * {@code breeding.delay-seconds} the young type is spawned between them. Both parents then rest for
 * {@code breeding.cooldown-minutes} (a Pdc timestamp). Refused before any food is used while {@code breeding.cap} of the
 * species (adults and young) already live in the 3x3 chunks around.
 */
final class WildBreeding {
    enum Result { LOVE, RESTING, CROWDED, OFF }

    static final String READY = "wild_breed_ready";

    private final WildlifeModule m;
    /** Mob -> until when it is in the mood (not saved; a restart just ends the mood). */
    private final Map<UUID, Long> love = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> feeder = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    private boolean enabled = true;
    private double radius = 8;
    private long loveMs = 30_000, cooldownMs = 300_000;
    private int delayTicks = 60;
    int cap = 16;

    WildBreeding(WildlifeModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        enabled = cfg.getBoolean("breeding.enabled", true);
        radius = Math.max(1, Math.min(32, cfg.getDouble("breeding.partner-radius", 8)));
        loveMs = (long) (Math.max(3, cfg.getDouble("breeding.love-seconds", 30)) * 1000);
        delayTicks = (int) Math.max(1, Math.round(cfg.getDouble("breeding.delay-seconds", 3) * 20));
        cooldownMs = (long) (Math.max(0, cfg.getDouble("breeding.cooldown-minutes", 5)) * 60_000);
        cap = Math.max(1, cfg.getInt("breeding.cap", 16));
    }

    /** A player fed breed food to an adult. LOVE: the food is used (the caller takes it). */
    Result feed(ServerPlayer p, Entity e, WildSpecies s) {
        if (!enabled) return Result.OFF;
        long now = System.currentTimeMillis();
        long rest = m.getLong(e, READY) - now;
        if (rest > 0) {
            m.bar(p, "breeding.resting", WildlifeModule.tag("name", s.name), WildlifeModule.tag("time", WildlifeModule.time(rest)));
            m.sound(p, "error");
            return Result.RESTING;
        }
        if (pending.contains(e.getUUID())) return Result.RESTING;
        Long until = love.get(e.getUUID());
        if (until != null && until > now) {
            // already in the mood: just look again for a partner, keep the food
            Entity partner = partner(e, s, now);
            if (partner != null) start(p, e, partner, s);
            else m.bar(p, "breeding.in-love", WildlifeModule.tag("name", s.name));
            return Result.RESTING;
        }
        int count = count(s, (ServerLevel) e.level(), e.position());
        if (count >= cap) {
            m.bar(p, "breeding.crowded", WildlifeModule.tag("name", s.name), WildlifeModule.tag("cap", cap));
            m.sound(p, "error");
            return Result.CROWDED;
        }
        love.put(e.getUUID(), now + loveMs);
        feeder.put(e.getUUID(), p.getUUID());
        hearts(e, 4);
        Entity partner = partner(e, s, now);
        if (partner != null) start(p, e, partner, s);
        else m.bar(p, "breeding.fed", WildlifeModule.tag("name", s.name));
        return Result.LOVE;
    }

    private Entity partner(Entity e, WildSpecies s, long now) {
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<UUID, Long> en : love.entrySet()) {
            if (en.getKey().equals(e.getUUID()) || en.getValue() <= now || pending.contains(en.getKey())) continue;
            if (!s.id.equals(m.tracked.get(en.getKey()))) continue;
            Entity o = m.entity(en.getKey());
            if (o == null || o.level() != e.level()) continue;
            double d = o.distanceToSqr(e);
            if (d <= radius * radius && d < bestD) {
                best = o;
                bestD = d;
            }
        }
        return best;
    }

    /** Adults and young of the species loaded in the 3x3 chunks around {@code at}. */
    int count(WildSpecies s, ServerLevel level, Vec3 at) {
        int cx = BlockPos.containing(at).getX() >> 4, cz = BlockPos.containing(at).getZ() >> 4, n = 0;
        for (Entity o : m.trackedEntities()) {
            String t = m.tracked.get(o.getUUID());
            if (t == null || !t.equals(s.id) && !t.equals(s.young)) continue;
            if (o.level() != level) continue;
            if (Math.abs((o.getBlockX() >> 4) - cx) <= 1 && Math.abs((o.getBlockZ() >> 4) - cz) <= 1) n++;
        }
        return n;
    }

    private void start(ServerPlayer p, Entity a, Entity b, WildSpecies s) {
        pending.add(a.getUUID());
        pending.add(b.getUUID());
        love.remove(a.getUUID());
        love.remove(b.getUUID());
        UUID by = p.getUUID();
        m.bar(p, "breeding.starting", WildlifeModule.tag("name", s.name));
        int steps = Math.max(1, delayTicks / 10);
        for (int i = 0; i < steps; i++) {
            Tasks.later(Math.max(1, i * 10L), () -> {
                hearts(a, 2);
                hearts(b, 2);
            });
        }
        Tasks.later(delayTicks, () -> {
            pending.remove(a.getUUID());
            pending.remove(b.getUUID());
            birth(by, a, b, s);
        });
    }

    private void birth(UUID by, Entity a, Entity b, WildSpecies s) {
        ServerPlayer p = Feather.get().player(by);
        if (a.isRemoved() || b.isRemoved() || a.level() != b.level() || a.distanceToSqr(b) > radius * radius * 4) {
            if (p != null) m.bar(p, "breeding.failed", WildlifeModule.tag("name", s.name));
            return;
        }
        ServerLevel level = (ServerLevel) a.level();
        Vec3 at = a.position().add(b.position()).scale(0.5);
        BlockPos bp = BlockPos.containing(at);
        if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) at = a.position();
        if (count(s, level, at) >= cap) {
            if (p != null) m.bar(p, "breeding.crowded", WildlifeModule.tag("name", s.name), WildlifeModule.tag("cap", cap));
            return;
        }
        Entity baby = spawn(level, s.young, at);
        if (baby == null) {
            if (p != null) m.bar(p, "breeding.spawn-failed", WildlifeModule.tag("name", s.name));
            return;
        }
        long until = System.currentTimeMillis() + cooldownMs;
        m.setLong(a, READY, until);
        m.setLong(b, READY, until);
        if (m.keptOnBreed) {
            m.keep(a);
            m.keep(b);
            m.keep(baby);
        }
        level.sendParticles(ParticleTypes.HEART, baby.getX(), baby.getY() + 0.6, baby.getZ(), 8, 0.4, 0.3, 0.4, 0);
        if (p != null) {
            m.bar(p, "breeding.born", WildlifeModule.tag("name", s.name));
            m.sound(p, "born");
            m.feather().stat(by, "wild-bred", 1);
        }
    }

    /** Spawns a creature by its MythicMobs id; null (logged once) when the type doesn't exist. */
    Entity spawn(ServerLevel level, String mythicId, Vec3 at) {
        EntityType<?> type = ArcanaEntities.byMythicId(mythicId);
        if (type == null) {
            m.warnOnce("mob:" + mythicId, "Wildlife: no entity type for " + mythicId + "; nothing spawned.");
            return null;
        }
        Entity e = type.create(level, EntitySpawnReason.BREEDING);
        if (e == null) return null;
        e.snapTo(at.x, at.y, at.z, level.getRandom().nextFloat() * 360, 0);
        if (e instanceof Mob mob) mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), EntitySpawnReason.BREEDING, null);
        level.addFreshEntity(e);
        return e;
    }

    private static void hearts(Entity e, int n) {
        if (!e.isRemoved() && e.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART, e.getX(), e.getY() + Math.max(0.5, e.getBbHeight() * 0.9), e.getZ(), n, 0.3, 0.25, 0.3, 0);
        }
    }

    void forget(UUID id) {
        love.remove(id);
        feeder.remove(id);
    }

    void resetLove() {
        love.clear();
        feeder.clear();
    }
}
