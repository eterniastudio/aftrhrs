package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaMount;
import net.axther.aftrhrs.entity.Species;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.axther.aftrhrs.entity.ability.Line;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Every species' ability lines and every registered Java skill (PetSkills, CatSkills, WildSkills, MountSkills, boss
 * skills) fire on a real creature, with a target and without one, tamed (owner online) and wild; then their delayed
 * sequences tick out. Nothing may throw.
 */
public class AbilityTests {
    @SuppressWarnings("unchecked")
    static Map<String, Abilities.Skill> skills() {
        return (Map<String, Abilities.Skill>) CT.get(Abilities.class, "SKILLS");
    }

    /** The species a skill id belongs to: the longest MythicMobs id prefix, else the species whose lines call it. */
    static Map<String, List<Species>> owners() {
        Map<String, List<Species>> out = new LinkedHashMap<>();
        List<Species> all = CT.species(x -> true);
        for (String id : new TreeSet<>(skills().keySet())) {
            Species best = null;
            for (Species s : all) {
                if ((id.equals(s.mythicId) || id.startsWith(s.mythicId + "_")) && (best == null || s.mythicId.length() > best.mythicId.length())) best = s;
            }
            List<Species> list = new ArrayList<>();
            if (best != null) list.add(best);
            for (Species s : all) {
                if (list.contains(s)) continue;
                for (var o : s.abilities) {
                    String l = Species.str(o, "line", "");
                    if (l != null && (l.contains("s=" + id + "}") || l.contains("s=" + id + ";") || l.contains("skill=" + id))) {
                        list.add(s);
                        break;
                    }
                }
            }
            out.put(id, list);
        }
        return out;
    }

    @GameTest(maxTicks = 700, padding = 64, skyAccess = true)
    public void everySkillAndLineFires(GameTestHelper h) {
        CT.arena(h, 64, 64);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(30, 2, 30));
        owner.setPermanentlyInvulnerable(true);
        List<String> problems = new ArrayList<>();
        Map<String, List<Species>> owners = owners();
        List<String> orphan = new ArrayList<>();
        Map<Species, List<String>> bySpecies = new LinkedHashMap<>();
        for (var e : owners.entrySet()) {
            if (e.getValue().isEmpty()) orphan.add(e.getKey());
            for (Species s : e.getValue()) bySpecies.computeIfAbsent(s, k -> new ArrayList<>()).add(e.getKey());
        }
        for (Species s : CT.species(x -> true)) bySpecies.computeIfAbsent(s, k -> new ArrayList<>());
        AftrHrs.LOG.info("[bt-creatures] {} skills, {} without an owning species: {}", owners.size(), orphan.size(), orphan);
        List<Entity> made = new ArrayList<>();
        int i = 0;
        for (var en : bySpecies.entrySet()) {
            Species s = en.getKey();
            Vec3 at = Vec3.atBottomCenterOf(CT.grid(i++, 9, 7));
            Entity e;
            Zombie z;
            try {
                e = CT.spawn(h, s, at);
                made.add(e);
                z = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, at.add(2, 0, 0));
                z.setPermanentlyInvulnerable(true);
                made.add(z);
            } catch (Throwable t) {
                problems.add(s.id + " spawn: " + CT.err(t));
                continue;
            }
            ArcanaMob mob = CT.mob(e);
            if (e instanceof ArcanaCreature c && !s.untameable()) {
                c.tamedBy(owner);
                c.setBond(5);
            }
            if (e instanceof net.minecraft.world.entity.Mob m && !(e instanceof ArcanaCreature c && c.isTame())) m.setTarget(z);
            for (String id : en.getValue()) {
                for (Entity target : new Entity[]{z, null, owner}) {
                    try {
                        Abilities.runSkill(mob, id, target);
                    } catch (Throwable t) {
                        problems.add(s.id + " skill " + id + " (target " + (target == null ? "none" : target.getType().toShortString()) + "): " + CT.err(t));
                    }
                }
            }
            for (var o : s.abilities) {
                String text = Species.str(o, "line", null);
                Line l = text == null ? null : Line.parse(text);
                if (l == null) continue;
                for (Entity trigger : new Entity[]{z, owner, null}) {
                    try {
                        Abilities.effect(mob, l, trigger);
                    } catch (Throwable t) {
                        problems.add(s.id + " line '" + text + "': " + CT.err(t));
                    }
                }
            }
            for (String sig : List.of("CANOPIP_REPLANT", "SPORELING_BOOST", "ARCANAWHISTLE", "nope")) {
                try {
                    Abilities.signal(mob, sig, owner);
                } catch (Throwable t) {
                    problems.add(s.id + " signal " + sig + ": " + CT.err(t));
                }
            }
            if (e instanceof ArcanaMount m) {
                try {
                    Abilities.onRide(m);
                    Abilities.onRiderSwing(m, owner);
                    Abilities.onTouchdown(m, "dive");
                } catch (Throwable t) {
                    problems.add(s.id + " mount hooks: " + CT.err(t));
                }
            }
            try {
                Abilities.ownerHurt(owner, z);
            } catch (Throwable t) {
                problems.add(s.id + " ownerHurt: " + CT.err(t));
            }
        }
        h.runAfterDelay(600, () -> {
            for (Entity e : made) if (!e.isRemoved()) e.discard();
            CT.disconnect(owner);
            CT.report(h, "abilities", problems);
            h.succeed();
        });
    }

    /**
     * The precomputed timer plan holds exactly the ~onTimer lines (every > 0) and the extra Java timers of each species,
     * in order, is cached on the species, and a timer registered after init is refused (it would miss a cached plan).
     */
    @GameTest
    public void timerPlanMatchesLines(GameTestHelper h) {
        List<String> problems = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, List<Abilities.Timer>> timers = (Map<String, List<Abilities.Timer>>) CT.get(Abilities.class, "TIMERS");
        int withPlan = 0;
        for (Species s : CT.species(x -> true)) {
            Abilities.TimerPlan plan = (Abilities.TimerPlan) CT.call(Abilities.class, "timerPlan", s);
            @SuppressWarnings("unchecked")
            List<Line> lines = (List<Line>) CT.call(Abilities.class, "lines", s);
            List<Line> want = new ArrayList<>();
            for (Line l : lines) if (l.every() > 0 && "ontimer".equals(l.trigger())) want.add(l);
            List<Abilities.Timer> extra = timers.getOrDefault(s.mythicId, List.of());
            if (!List.of(plan.lines()).equals(want)) problems.add(s.id + ": plan lines " + plan.lines().length + " != " + want.size());
            if (!List.of(plan.extra()).equals(extra)) problems.add(s.id + ": plan timers " + plan.extra().length + " != " + extra.size());
            if (s.timerPlan != plan) problems.add(s.id + ": plan not cached on the species");
            if (CT.call(Abilities.class, "timerPlan", s) != plan) problems.add(s.id + ": plan rebuilt");
            if (plan.lines().length + plan.extra().length > 0) withPlan++;
        }
        if (withPlan == 0) problems.add("no species has timed work");
        try {
            Abilities.timer("ArcanaNope", 20, "ArcanaNope_Skill", false);
            problems.add("Abilities.timer after init did not throw");
        } catch (IllegalStateException expected) {
            // late registration is refused
        }
        if (timers.containsKey("ArcanaNope")) problems.add("late timer was registered");
        AftrHrs.LOG.info("[bt-creatures] timer plans: {} species with timed work", withPlan);
        CT.report(h, "timer plans", problems);
        h.succeed();
    }
}
