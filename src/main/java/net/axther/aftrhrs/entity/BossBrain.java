package net.axther.aftrhrs.entity;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * The bosses' and the police officer's living layer (PORTING.md "Animation", bosses): which idle loop and walk play,
 * phase stances, taunts, hit reactions and the death sequence. Server side; the clips are synced like any intent.
 *
 * <ul>
 *   <li><b>Pools</b> ({@code anim.idle.boss_calm} without a target, {@code anim.idle.boss_combat} with one): weighted
 *       entries like the pets' idle pools. A loop runs for min..max seconds, a one-shot plays once (in combat the boss
 *       stands still for it: taunts). {@code when}: comma = and, {@code !} = not, tokens {@code p1 p2 p3} (phase),
 *       {@code p4}, {@code p2+}, {@code p3+}, {@code far} / {@code near} (target beyond / within 7 blocks), {@code air} / {@code ground},
 *       {@code night} / {@code day}. Never the same loop twice in a row.</li>
 *   <li><b>Phase</b>: Wukong's {@code phase} variable, the Phoenix's rebirth, else below half health = 2. Entering a
 *       phase plays the state {@code phase<N>} clip if the model has one (standing still for it); walk clips per phase
 *       are the states {@code walk<N>}, {@code chase} (with a target) else the base walk.</li>
 *   <li><b>Hit reactions</b>: never over an attack clip, at most every 0.7 s (0.3 s for the police); a hit worth 3 % of
 *       the max health plays {@code hurt_heavy}.</li>
 *   <li><b>Death</b>: the body stays for its whole death clip (+ 0.5 s) instead of vanilla's 1 s, flyers fall, and each
 *       boss gets its finale burst ({@link #dying}).</li>
 *   <li><b>Melee hit frame</b>: a boss attack clip tagged {@code hit} (seconds) lands its melee damage on that frame
 *       ({@link ArcanaMonster#doHurtTarget}), not at the start of the wind-up.</li>
 * </ul>
 */
public final class BossBrain {
    private static final Map<Species, Integer> HIT = new HashMap<>();

    /** A boss's brain state, held by the boss itself ({@link ArcanaMonster#bossState}); transient, never saved. */
    static final class State {
        int phase;
        String loop = "";
        long loopUntil;
        long flinchAt;
        long stillUntil;
        int still;
        final Map<String, Long> cd = new HashMap<>();
    }

    private BossBrain() {
    }

    /** True for the species this brain drives (bosses and the police). */
    public static boolean drives(ArcanaMob mob) {
        Species.Kind k = mob.species().kind;
        return k == Species.Kind.BOSS || k == Species.Kind.POLICE;
    }

    private static State state(ArcanaMonster m) {
        return m.bossState();
    }

    /** 1 .. 3: Wukong's phase variable, the Phoenix's rebirth, else below half health = 2. */
    public static int phase(ArcanaMonster m) {
        int v = m.var("phase");
        if (v > 0) return v;
        if (m.var("reborn") != 0) return 2;
        return m.getHealth() < m.getMaxHealth() * 0.5f ? 2 : 1;
    }

    // ------------------------------------------------------------------ every tick

    static void tick(ArcanaMonster m) {
        if (!(m.level() instanceof ServerLevel level) || !m.isAlive()) return;
        State st = state(m);
        long now = level.getGameTime();
        int phase = phase(m);
        if (st.phase == 0) st.phase = phase;
        else if (phase > st.phase) {
            st.phase = phase;
            phaseChange(m, st, phase);
        }
        if ((m.tickCount + m.getId()) % 10 != 0 || m.busy() || m.inAction() || now < st.stillUntil) return;
        Species s = m.species();
        LivingEntity target = m.getTarget();
        boolean combat = target != null && target.isAlive();
        m.setWalkClip(walk(m, phase, combat));
        List<AnimSpec.IdleEntry> pool = s.anim.idle(combat ? "boss_combat" : "boss_calm");
        if (pool.isEmpty()) {
            legacyIdle(m, combat);
            return;
        }
        boolean keep = now < st.loopUntil && !st.loop.isEmpty() && st.still == (combat ? 1 : 0) && fits(m, st.loop, pool, phase);
        if (keep) {
            m.setIdleClip(st.loop);
            return;
        }
        AnimSpec.IdleEntry e = pick(m, st, pool, phase, now);
        if (e == null) {
            m.setIdleClip(stance(m, phase));
            return;
        }
        if (e.cooldown() > 0) st.cd.put(e.clip(), now + Math.round(e.cooldown() * 20));
        AnimSpec.Tag tag = s.anim.tag(e.clip());
        if (tag.loops() || tag.holds()) {
            st.loop = e.clip();
            st.still = combat ? 1 : 0;
            double secs = e.min() + m.getRandom().nextDouble() * Math.max(0, e.max() - e.min());
            st.loopUntil = now + Math.round(secs * 20);
            m.setIdleClip(e.clip());
            return;
        }
        // a one-shot flourish over the current loop; a taunt in combat is performed standing (not while it chases)
        if (!combat && m.getDeltaMovement().horizontalDistanceSqr() > 0.004) return;
        int len = s.clipTicks(e.clip(), 30);
        m.play(e.clip());
        if (combat) {
            m.getNavigation().stop();
            st.stillUntil = now + len;
            m.setBusy(true);
            m.scheduler().after(len, () -> m.setBusy(false));
        } else {
            m.getNavigation().stop();
            st.stillUntil = now + Math.min(len, 40);
        }
    }

    /** The old behavior for species without boss pools: rage idle below half health, run while chasing. */
    private static void legacyIdle(ArcanaMonster m, boolean combat) {
        Species s = m.species();
        boolean rage = s.has("idle_rage") && m.getHealth() < m.getMaxHealth() * 0.5f;
        m.setIdleClip(rage ? "idle_rage" : s.baseIdle());
    }

    private static String walk(ArcanaMonster m, int phase, boolean combat) {
        Species s = m.species();
        AnimSpec a = s.anim;
        for (int p = phase; p >= 2; p--) {
            String w = a.state("walk" + p);
            if (w != null && s.has(w)) return w;
        }
        if (s.has("walk_rage") && s.has("idle_rage") && m.getHealth() < m.getMaxHealth() * 0.5f && a.state("walk2") == null) return "walk_rage";
        String chase = a.state("chase");
        if (combat && chase != null && s.has(chase)) return chase;
        if (combat && s.has("run") && !s.flyer()) return "run";
        return s.baseWalk();
    }

    /** The phase's battle stance (state {@code stance<N>}, {@code stance}), else the base idle. */
    private static String stance(ArcanaMonster m, int phase) {
        Species s = m.species();
        for (int p = phase; p >= 1; p--) {
            String c = s.anim.state("stance" + p);
            if (c != null && s.has(c)) return c;
        }
        String c = s.anim.state("stance");
        return c != null && s.has(c) ? c : s.baseIdle();
    }

    private static boolean fits(ArcanaMonster m, String clip, List<AnimSpec.IdleEntry> pool, int phase) {
        for (AnimSpec.IdleEntry e : pool) if (e.clip().equals(clip) && holds(m, e.when(), phase)) return true;
        return false;
    }

    private static AnimSpec.@Nullable IdleEntry pick(ArcanaMonster m, State st, List<AnimSpec.IdleEntry> pool, int phase, long now) {
        List<AnimSpec.IdleEntry> ok = new ArrayList<>();
        double total = 0;
        for (AnimSpec.IdleEntry e : pool) {
            if (!m.species().has(e.clip()) || !holds(m, e.when(), phase)) continue;
            Long until = st.cd.get(e.clip());
            if (until != null && until > now) continue;
            ok.add(e);
            total += e.weight();
        }
        // never the same loop twice in a row when there is a choice
        if (ok.size() > 1 && ok.removeIf(e -> e.clip().equals(st.loop))) {
            total = 0;
            for (AnimSpec.IdleEntry e : ok) total += e.weight();
        }
        if (ok.isEmpty() || total <= 0) return null;
        double r = m.getRandom().nextDouble() * total;
        for (AnimSpec.IdleEntry e : ok) {
            r -= e.weight();
            if (r <= 0) return e;
        }
        return ok.get(ok.size() - 1);
    }

    private static boolean holds(ArcanaMonster m, String when, int phase) {
        if (when == null || when.isBlank()) return true;
        for (String raw : when.split(",")) {
            String c = raw.trim();
            if (c.isEmpty()) continue;
            boolean neg = c.startsWith("!");
            if (neg) c = c.substring(1);
            boolean v = switch (c) {
                case "p1" -> phase == 1;
                case "p2" -> phase == 2;
                case "p3" -> phase == 3;
                case "p4" -> phase == 4;
                case "p2+" -> phase >= 2;
                case "p3+" -> phase >= 3;
                case "far" -> m.getTarget() != null && m.distanceTo(m.getTarget()) > 7;
                case "near" -> m.getTarget() != null && m.distanceTo(m.getTarget()) <= 7;
                case "air" -> !m.onGround();
                case "ground" -> m.onGround();
                case "night" -> Conditions.night(m.level());
                case "day" -> Conditions.day(m.level());
                default -> true;
            };
            if (v == neg) return false;
        }
        return true;
    }

    private static void phaseChange(ArcanaMonster m, State st, int phase) {
        String clip = m.species().anim.state("phase" + phase);
        st.loop = "";
        st.loopUntil = 0;
        if (clip == null || !m.species().has(clip)) return;
        int len = m.species().clipTicks(clip, 40);
        m.action(clip);
        m.setBusy(true);
        m.scheduler().after(len, () -> m.setBusy(false));
        CreatureSfx.play(m, "ability_phase");
    }

    // ------------------------------------------------------------------ hit reactions

    /** A hit landed: flinch (unless an attack clip plays), the heavy flinch for big hits, never more than ~1.5 / s. */
    static void flinch(ArcanaMonster m, float amount) {
        if (m.inAction() || !(m.level() instanceof ServerLevel level)) return;
        State st = state(m);
        long now = level.getGameTime();
        if (now < st.flinchAt) return;
        Species s = m.species();
        String heavy = s.anim.state("hurt_heavy");
        String clip = heavy != null && s.has(heavy) && amount >= m.getMaxHealth() * 0.03f ? heavy : s.anim("hurt");
        if (clip == null) return;
        m.play(clip);
        st.flinchAt = now + (s.kind == Species.Kind.POLICE ? 6 : 14);
    }

    // ------------------------------------------------------------------ melee hit frame

    /** Ticks from the start of the melee attack clip to its hit frame (tag {@code hit}); 0 = the hit lands at once. */
    static int meleeHit(ArcanaMonster m) {
        Species s = m.species();
        return HIT.computeIfAbsent(s, sp -> {
            String clip = sp.anim("attack");
            JsonObject anim = sp.section("anim");
            if (clip == null || anim == null || !anim.has("clips")) return 0;
            JsonObject t = anim.getAsJsonObject("clips").getAsJsonObject(clip);
            return t == null || !t.has("hit") ? 0 : Math.round(t.get("hit").getAsFloat() * 20);
        });
    }

    // ------------------------------------------------------------------ death sequence

    /** How long the body stays after death: its death clip + 0.5 s (bosses / police), vanilla's 20 ticks otherwise. */
    static int deathTicks(ArcanaMonster m) {
        // bosses with a boss bar and the police; Wukong's hair clones just poof
        if (!drives(m) || m.species().kind == Species.Kind.BOSS && m.species().section("boss_bar") == null) return 20;
        String death = m.species().anim.state("death");
        return death == null ? 20 : Math.max(20, Math.min(110, m.species().clipTicks(death, 20) + 10));
    }

    /** Server side, every tick of the death sequence: flyers drop, each boss bursts at the end of its clip. */
    static void dying(ArcanaMonster m, int t) {
        if (!(m.level() instanceof ServerLevel level)) return;
        if (t == 1) {
            m.setNoGravity(false);
            m.setBusy(true);
        }
        int end = deathTicks(m) - 8;
        String id = m.species().id;
        double cy = m.getY() + m.getBbHeight() * 0.4;
        switch (id) {
            case "runebound_colossus" -> {
                if (t % 6 == 0 && t < end) burst(level, m, block(Blocks.DEEPSLATE), 6, 0.6, 0.02);
                if (t == end) {
                    burst(level, m, block(Blocks.DEEPSLATE), 60, 1.2, 0.05);
                    burst(level, m, ParticleTypes.ENCHANT, 60, 1.5, 0.6);
                    level.playSound(null, m.getX(), cy, m.getZ(), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 2.5f, 0.5f);
                }
            }
            case "glacial_wyrm" -> {
                if (t % 5 == 0 && t < end) burst(level, m, ParticleTypes.SNOWFLAKE, 8, 1.0, 0.03);
                if (t == end) {
                    burst(level, m, block(Blocks.PACKED_ICE), 80, 1.4, 0.08);
                    burst(level, m, ParticleTypes.SNOWFLAKE, 60, 1.6, 0.1);
                    level.playSound(null, m.getX(), cy, m.getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 3f, 0.5f);
                }
            }
            case "cinderwing_phoenix_boss" -> {
                if (t % 3 == 0 && t < end) burst(level, m, ParticleTypes.FLAME, 6, 0.6, 0.02);
                if (t == end) {
                    burst(level, m, ParticleTypes.LARGE_SMOKE, 40, 1.0, 0.05);
                    burst(level, m, ParticleTypes.LAVA, 20, 0.8, 0.0);
                    burst(level, m, ParticleTypes.FLAME, 60, 1.2, 0.12);
                    level.playSound(null, m.getX(), cy, m.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 3f, 0.5f);
                }
            }
            case "tempest_roc_boss" -> {
                if (t % 4 == 0 && t < end) burst(level, m, ParticleTypes.ELECTRIC_SPARK, 10, 0.8, 0.2);
                if (t == end) {
                    burst(level, m, ParticleTypes.CLOUD, 50, 1.2, 0.08);
                    burst(level, m, ParticleTypes.ELECTRIC_SPARK, 60, 1.4, 0.5);
                    level.playSound(null, m.getX(), cy, m.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 2f, 1.4f);
                }
            }
            case "wukong" -> {
                if (t % 8 == 0 && t < end) burst(level, m, block(Blocks.STONE), 6, 0.6, 0.02);
                if (t == end) {
                    burst(level, m, block(Blocks.STONE), 70, 1.0, 0.06);
                    burst(level, m, ParticleTypes.WAX_OFF, 50, 1.4, 0.6);
                    level.playSound(null, m.getX(), cy, m.getZ(), SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 2.5f, 0.6f);
                }
            }
            default -> {
            }
        }
    }

    private static ParticleOptions block(net.minecraft.world.level.block.Block b) {
        return new BlockParticleOption(ParticleTypes.BLOCK, b.defaultBlockState());
    }

    private static void burst(ServerLevel level, ArcanaMonster m, ParticleOptions p, int n, double spread, double speed) {
        double w = Math.max(0.4, m.getBbWidth() * 0.5) * spread;
        level.sendParticles(p, m.getX(), m.getY() + m.getBbHeight() * 0.45, m.getZ(), n, w, m.getBbHeight() * 0.35 * spread, w, speed);
    }
}
