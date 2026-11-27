package net.axther.aftrhrs.entity.ability;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaMount;
import net.axther.aftrhrs.entity.Conditions;
import net.axther.aftrhrs.entity.Species;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The ability engine. Each species' spec lines ({@code ability} for tameables, {@code lines} for wildlife / undead,
 * {@code Skills} for bosses) run on their MythicMobs triggers ({@code ~onTimer:N}, {@code ~onAttack}, {@code ~onDamaged},
 * {@code ~onDeath}, {@code ~onSpawn}, {@code ~onLoad}, {@code ~onInteract}, {@code ~onSignal:X}). Simple mechanics
 * (potion, ignite, particles, sound, freeze, damage, state, setvariable) run as is; {@code skill{s=Id}} calls the Java
 * {@link Skill} registered under that MythicMobs skill id (see {@link PetSkills}, {@link CatSkills}, {@link MountSkills},
 * {@link WildSkills}).
 */
public final class Abilities {
    /** One MythicMobs metaskill, ported. */
    @FunctionalInterface
    public interface Skill {
        void run(Ctx ctx);
    }

    private static final Map<String, Skill> SKILLS = new HashMap<>();
    private static final Map<Species, List<Line>> LINES = new ConcurrentHashMap<>();
    private static final Set<String> MISSING = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static boolean booted;
    /** Set once {@link #init} has run every register(): the extra {@link #TIMERS} are final, timer plans may be cached. */
    private static volatile boolean timersSealed;
    /** Skills whose ~onAttack line replaces the base melee hit (MythicMobs {@code cancelevent}: Wukong, his clones). */
    private static final Set<String> MELEE_REPLACING = new java.util.HashSet<>();
    private static final Map<Species, Boolean> REPLACES_MELEE = new ConcurrentHashMap<>();
    /** Extra timed skills a species runs beyond its spec lines (boss attacks for clips no MythicMobs skill used). */
    private static final Map<String, List<Timer>> TIMERS = new HashMap<>();
    /** The damage source of the ~onDamaged trigger being run (MythicMobs damagecause conditions). */
    private static @Nullable DamageSource lastDamage;

    /** An extra timed skill: every {@code every} ticks, at the current target ({@code atTarget}) or always. */
    public record Timer(int every, String skill, boolean atTarget) {
    }

    /** A species' timed work: its ~onTimer lines (every > 0) and its extra Java timers, in tick order. */
    public record TimerPlan(Line[] lines, Timer[] extra) {
        static final TimerPlan NONE = new TimerPlan(new Line[0], new Timer[0]);

        boolean empty() {
            return lines.length == 0 && extra.length == 0;
        }
    }

    private Abilities() {
    }

    public static void register(String id, Skill skill) {
        SKILLS.put(id, skill);
    }

    public static boolean has(String id) {
        return SKILLS.containsKey(id);
    }

    /** Registers a skill whose ~onAttack call replaces the base class's melee hit (MythicMobs cancelevent). */
    public static void registerMelee(String id, Skill skill) {
        register(id, skill);
        MELEE_REPLACING.add(id);
    }

    /** True if one of the species' ~onAttack lines calls a melee-replacing skill. */
    public static boolean replacesMelee(Species s) {
        return REPLACES_MELEE.computeIfAbsent(s, sp -> {
            for (Line l : lines(sp)) {
                if ("onattack".equals(l.trigger()) && l.mechanic().equals("skill") && MELEE_REPLACING.contains(skillId(sp, l.arg("s", "skill")))) return true;
            }
            return false;
        });
    }

    /** Adds an extra timed skill to a species (by MythicMobs id), run like an ~onTimer line. Only while {@link #init}
     *  registers the skills: a later timer would miss the species' cached {@link TimerPlan}. */
    public static void timer(String mythicId, int every, String skill, boolean atTarget) {
        if (timersSealed) throw new IllegalStateException("Abilities.timer(" + mythicId + ", " + skill + ") after init");
        TIMERS.computeIfAbsent(mythicId, k -> new ArrayList<>()).add(new Timer(every, skill, atTarget));
    }

    /** The damage source while an ~onDamaged trigger runs (null otherwise). */
    public static @Nullable DamageSource lastDamage() {
        return lastDamage;
    }

    public static void init(List<Species> all) {
        if (booted) return;
        booted = true;
        PetSkills.register();
        CatSkills.register();
        MountSkills.register();
        WildSkills.register(all);
        BossSkills.register();
        WukongSkills.register();
        PoliceSkills.register();
        EndSkills.register();
        NetherSkills.register();
        OceanSkills.register();
        timersSealed = true;
        Set<String> missing = new TreeSet<>();
        for (Species s : all) {
            for (Line l : lines(s)) {
                if (l.mechanic().equals("skill")) {
                    String id = skillId(s, l.arg("s", "skill"));
                    if (id != null && !SKILLS.containsKey(id)) missing.add(id);
                }
            }
        }
        if (!missing.isEmpty()) AftrHrs.LOG.info("Arcana creatures: {} skill(s) not ported yet: {}", missing.size(), missing);
    }

    /** Skill ids in wild specs use %ID%; the converter already expanded them, but be lenient. */
    private static @Nullable String skillId(Species s, @Nullable String id) {
        if (id == null) return null;
        return id.replace("%ID%", s.mythicId);
    }

    static List<Line> lines(Species s) {
        return LINES.computeIfAbsent(s, sp -> {
            List<Line> out = new ArrayList<>();
            for (JsonObject o : sp.abilities) {
                String text = Species.str(o, "line", null);
                if (text == null) continue;
                Line l = Line.parse(text);
                if (l != null && !l.mechanic().equals("model")) out.add(l);
            }
            return List.copyOf(out);
        });
    }

    // ------------------------------------------------------------------ triggers

    /** The species' {@link TimerPlan}, built once (never cached before {@link #init} has registered the TIMERS). */
    static TimerPlan timerPlan(Species s) {
        TimerPlan p = s.timerPlan;
        if (p == null) {
            List<Line> tl = new ArrayList<>();
            for (Line l : lines(s)) if (l.every() > 0 && "ontimer".equals(l.trigger())) tl.add(l);
            List<Timer> ex = TIMERS.getOrDefault(s.mythicId, List.of());
            p = tl.isEmpty() && ex.isEmpty() ? TimerPlan.NONE : new TimerPlan(tl.toArray(Line[]::new), ex.toArray(Timer[]::new));
            if (timersSealed) s.timerPlan = p;
        }
        return p;
    }

    public static void tick(ArcanaMob mob) {
        TimerPlan plan = timerPlan(mob.species());
        if (plan.empty()) return;
        int t = mob.self().tickCount + mob.self().getId();
        for (Line l : plan.lines()) if (t % l.every() == 0) fire(mob, l, null);
        for (Timer x : plan.extra()) {
            if (t % x.every() != 0) continue;
            Entity target = x.atTarget() ? mob.self().getTarget() : null;
            if (x.atTarget() && target == null) continue;
            runSkill(mob, x.skill(), target);
        }
    }

    public static void onAttack(ArcanaMob mob, Entity victim) {
        on(mob, "onattack", victim);
    }

    public static void onDamaged(ArcanaMob mob, DamageSource source) {
        DamageSource prev = lastDamage;
        lastDamage = source;
        try {
            on(mob, "ondamaged", source.getEntity());
        } finally {
            lastDamage = prev;
        }
    }

    public static void onDeath(ArcanaMob mob) {
        // MythicMobs' @trigger on ~onDeath is the killer (the bird bosses' Fall skills run @trigger); a /kill has none,
        // so the dying mob stands in and the death skills still run
        Entity killer = mob.self().getKillCredit();
        on(mob, "ondeath", killer != null ? killer : mob.self());
    }

    public static void onSpawn(ArcanaMob mob) {
        on(mob, "onspawn", null);
        on(mob, "onspawnorload", null);
    }

    public static void onLoad(ArcanaMob mob) {
        // runs while reading the save; the creature may not be in a level yet, so defer one tick
        mob.scheduler().after(1, () -> {
            on(mob, "onload", null);
            on(mob, "onspawnorload", null);
        });
    }

    public static void load(ArcanaCreature c, ValueInput in) {
        onLoad(c);
    }

    public static void save(ArcanaCreature c, ValueOutput out) {
    }

    public static void onInteract(ArcanaCreature c, Player player) {
        on(c, "oninteract", player);
    }

    /** MythicMobs {@code signal{s=X}}: delivers a named signal to the creature's ~onSignal:X lines. */
    public static void signal(ArcanaMob mob, String signal, @Nullable Entity from) {
        for (Line l : lines(mob.species())) {
            if ("onsignal".equals(l.trigger()) && signal.equalsIgnoreCase(l.triggerArg())) fire(mob, l, from);
        }
    }

    /** Removed (despawn, unload, discard): ~onDespawn lines and the species' Cleanup skill (light blocks ...). */
    public static void onRemove(ArcanaMob mob) {
        on(mob, "ondespawn", null);
        runSkill(mob, mob.species().mythicId + "_Cleanup", null);
    }

    /** Every tick while ridden: the species' RideTick skill once a second (rider perks such as the Tidemane's). */
    public static void onRide(ArcanaMount mount) {
        if ((mount.tickCount + mount.getId()) % 20 == 0) runSkill(mount, mount.species().mythicId + "_RideTick", null);
    }

    /** The rider of a mount swung their arm (left-click in the air): the Cinderwing Phoenix kindles. */
    public static void onRiderSwing(ArcanaMount mount, Player rider) {
        runSkill(mount, mount.species().mythicId + "_Kindle", rider);
    }

    /** A player was hurt by a living attacker: the Barkling's ward roots monsters that hit its owner; the Fumetail sprays them. */
    public static void ownerHurt(net.minecraft.server.level.ServerPlayer owner, LivingEntity attacker) {
        PetSkills.ownerHurt(owner, attacker);
        NetherSkills.ownerHurt(owner, attacker);
    }

    /** A ridden flyer touched down after flying in state {@code from} (Tempest Roc's dive gust). */
    public static void onTouchdown(ArcanaMount mount, String from) {
        runSkill(mount, mount.species().mythicId + "_Touchdown", null);
    }

    private static void on(ArcanaMob mob, String trigger, @Nullable Entity triggerEntity) {
        if (!(mob.self().level() instanceof ServerLevel)) return;
        for (Line l : lines(mob.species())) if (trigger.equals(l.trigger())) fire(mob, l, triggerEntity);
    }

    /** Runs a registered skill by its MythicMobs id. False if it isn't ported. */
    public static boolean runSkill(ArcanaMob mob, String id, @Nullable Entity target) {
        Skill s = SKILLS.get(id);
        if (s == null) return false;
        if (!(mob.self().level() instanceof ServerLevel)) return false;
        s.run(new Ctx(mob, id, target, target));
        return true;
    }

    // ------------------------------------------------------------------ line execution

    private static void fire(ArcanaMob mob, Line l, @Nullable Entity trigger) {
        if (l.chance() < 1 && mob.self().getRandom().nextDouble() >= l.chance()) return;
        if (!conditions(mob, l)) return;
        effect(mob, l, trigger);
    }

    static boolean conditions(ArcanaMob mob, Line l) {
        for (String c : l.conditions()) {
            boolean neg = c.startsWith("!");
            String cond = neg ? c.substring(1) : c;
            if (check(mob, cond) == neg) return false;
        }
        return true;
    }

    private static boolean check(ArcanaMob mob, String cond) {
        var self = mob.self();
        String name = cond.contains("{") ? cond.substring(0, cond.indexOf('{')) : cond;
        java.util.Map<String, String> args = cond.contains("{") ? Line.args(cond.substring(cond.indexOf('{') + 1, cond.lastIndexOf('}'))) : Map.of();
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "hasowner" -> mob instanceof ArcanaCreature c && c.isTame();
            case "night" -> Conditions.night(self.level());
            case "day" -> Conditions.day(self.level());
            case "moving" -> self.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4;
            case "onground" -> self.onGround();
            case "outside" -> Conditions.outside(self);
            case "inblock" -> Conditions.inWater(self);
            case "playerwithin" -> self.level().getNearestPlayer(self, Fx.parse(args.getOrDefault("d", "8"), 8)) != null;
            case "variableequals" -> {
                String var = args.getOrDefault("var", "").replace("caster.", "");
                yield String.valueOf(mob.var(var)).equals(args.getOrDefault("value", "0"));
            }
            case "variableisset" -> mob.var(args.getOrDefault("var", "").replace("caster.", "")) != 0;
            default -> true;
        };
    }

    /** Runs one mechanic line (no trigger / condition checks). */
    public static void effect(ArcanaMob mob, Line l, @Nullable Entity trigger) {
        if (!(mob.self().level() instanceof ServerLevel level)) return;
        Ctx ctx = new Ctx(mob, l.mechanic().equals("skill") ? String.valueOf(l.arg("s", "skill")) : l.mechanic(), null, trigger);
        Entity target = resolve(ctx, l.target(), trigger);
        if (target == null && !l.target().equals("forward") && !l.target().equals("selflocation")) return;
        ctx.target = target;
        Map<String, String> a = l.args();
        switch (l.mechanic()) {
            case "skill" -> {
                String id = skillId(mob.species(), l.arg("s", "skill"));
                Skill s = id == null ? null : SKILLS.get(id);
                if (s == null) {
                    if (id != null && MISSING.add(id)) AftrHrs.LOG.debug("Arcana skill {} not ported", id);
                    return;
                }
                Ctx c = new Ctx(mob, id, target, trigger);
                s.run(c);
            }
            case "ignite" -> {
                if (target != null) target.igniteForTicks((int) ctx.num(a.get("ticks"), 60));
            }
            case "potion" -> {
                if (target instanceof LivingEntity le) {
                    Fx.potion(le, a.getOrDefault("type", "SPEED"), (int) ctx.num(a.getOrDefault("duration", a.get("d")), 60),
                            (int) ctx.num(a.getOrDefault("level", a.get("l")), 0), !"false".equals(a.get("hasParticles")), !"false".equals(a.get("hasIcon")));
                }
            }
            case "freeze" -> {
                if (target != null) target.setTicksFrozen((int) ctx.num(a.get("ticks"), 0));
            }
            case "damage" -> {
                if (target != null && target != mob.self()) {
                    target.hurtServer(level, mob.self().damageSources().mobAttack(mob.self()), (float) ctx.num(a.getOrDefault("amount", a.get("a")), 1));
                }
            }
            case "particles", "effect:particles", "e:p" -> {
                ParticleOptions p = Fx.particle(a.getOrDefault("p", a.getOrDefault("particle", "cloud")), a);
                if (p == null) return;
                int amount = (int) ctx.num(a.getOrDefault("amount", a.get("a")), 1);
                double hs = ctx.num(a.getOrDefault("hS", a.get("hs")), 0), vs = ctx.num(a.getOrDefault("vS", a.get("vs")), 0);
                double y = ctx.num(a.get("y"), 0), speed = ctx.num(a.getOrDefault("speed", a.get("s")), 0);
                net.minecraft.world.phys.Vec3 at = l.target().equals("forward")
                        ? ctx.forward(Fx.parse(l.targetArgs().getOrDefault("f", "1"), 1)) : target.position();
                int delay = (int) ctx.num(a.get("delay"), 0);
                Runnable r = () -> level.sendParticles(p, at.x, at.y + y, at.z, amount, hs, vs, hs, speed);
                if (delay > 0) mob.scheduler().after(delay, r);
                else r.run();
            }
            case "sound" -> {
                Entity at = target == null ? mob.self() : target;
                int delay = (int) ctx.num(a.get("delay"), 0);
                Runnable r = () -> Fx.sound(at, a.getOrDefault("s", "entity.generic.eat"), (float) ctx.num(a.get("v"), 1), (float) ctx.num(a.get("p"), 1));
                if (delay > 0) mob.scheduler().after(delay, r);
                else r.run();
            }
            case "state" -> {
                if ("true".equals(a.get("r"))) mob.stopPlay(a.get("s"));
                else if (mob instanceof net.axther.aftrhrs.entity.ArcanaMonster m && String.valueOf(a.get("s")).equals(mob.species().anim("hurt"))) {
                    m.flinch(0);   // a hurt flinch never cuts off a boss's attack clip (and is rate-limited)
                } else if (mob instanceof net.axther.aftrhrs.entity.ArcanaMonster m && String.valueOf(a.get("s")).equals(m.actionClip())) {
                    // already playing as the attack clip (a melee waiting for its hit frame): no restart
                } else mob.play(a.get("s"));
            }
            case "partvis" -> mob.setBoneVisible(a.getOrDefault("p", ""), !"false".equals(a.get("v")));
            case "tint" -> {
                if (mob instanceof net.axther.aftrhrs.entity.ArcanaMonster m) {
                    try {
                        m.setTint(Integer.parseInt(a.getOrDefault("c", "FFFFFF").replace("#", ""), 16));
                    } catch (NumberFormatException ignored) {
                        // bad color: keep the look
                    }
                }
            }
            case "remove" -> {
                int delay = (int) ctx.num(a.get("delay"), 0);
                Entity e = target == null ? mob.self() : target;
                if (delay > 0) mob.scheduler().after(delay, () -> {
                    if (!e.isRemoved()) e.discard();
                });
                else e.discard();
            }
            case "setvariable" -> {
                String var = a.getOrDefault("var", "").replace("caster.", "");
                mob.setVar(var, (int) ctx.num(a.get("value"), 0));
            }
            case "extinguish" -> {
                if (target != null) target.clearFire();
            }
            default -> {
            }
        }
    }

    private static @Nullable Entity resolve(Ctx ctx, String target, @Nullable Entity trigger) {
        return switch (target) {
            case "self", "selflocation", "forward" -> ctx.self;
            case "target" -> trigger != null ? trigger : ctx.self.getTarget();
            case "trigger" -> trigger;
            case "owner", "ownerlocation" -> ctx.owner();
            default -> ctx.self;
        };
    }
}
