package net.axther.aftrhrs.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * The server half of the animation state machine for the tameable creatures (the client half is
 * {@link CreatureAnim.Driver}). It decides the <i>intent</i>; the client plays it against the measured movement.
 * <ul>
 *   <li><b>Mood</b> (every second): gen_mm's mood rules pick the idle loop and the gait ({@link Species#idleMoods},
 *       {@link Species#walkMoods}, later rules win; checked from the last rule down, stopping at the first that holds): sleepy at night, cold, hot, rain, happy, beg, alert, worried, hurt,
 *       scared, swim. Luna's {@code idle_bed} and Kevin's {@code idle_sunbeam} / {@code idle_box} and Kitty's {@code idle_lounge} come from their specs.</li>
 *   <li><b>Posture</b>: vanilla's sitting pose is the truth. Sitting -> the sit clip is held; a staying pet naps at
 *       night (sleep clip) and sits back up in the morning. The client inserts sit_down / lie_down / wake_up /
 *       stand_up between postures.</li>
 *   <li><b>Idle director</b>: every few seconds a still, free creature picks from the weighted idle pool of its
 *       posture ({@link AnimSpec#idle}: loops for a while, one-shots once, cooldowns, {@code when} conditions and
 *       {@code boost} weights by mood / time / weather / owner nearby) plus the mood's flourish pool
 *       ({@link Species#pools}).</li>
 *   <li>Greets the owner coming back, shakes off after a swim.</li>
 * </ul>
 */
final class MoodBrain {
    private final ArcanaCreature c;
    /** The idle mood picked last (it names the flourish pool). */
    String mood = "idle";
    boolean far, wet;
    /** A loop variant from the idle pool and when it ends (game time); "" = the mood loop. */
    private String variant = "";
    private long variantUntil, nextEvent;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private net.minecraft.world.entity.@Nullable LivingEntity lastTarget;
    /** {@code monster_near} is an entity scan: its answer is kept for 2 s (transient, never saved). */
    private boolean monsterNearCached;
    private long monsterRecheckAt = Long.MIN_VALUE;

    MoodBrain(ArcanaCreature c) {
        this.c = c;
    }

    /** An untamed hostile with a wild idle authored (idle_wild): it gets the wild idles, not the pet moods. */
    boolean wild() {
        return !c.isTame() && c.species().hostile() && c.species().anim.state("idle_wild") != null;
    }

    /** The posture the brain wants: standing, sitting (staying) or lying (napping). */
    String posture() {
        if (!c.isInSittingPose()) return AnimSpec.STANDING;
        return c.napping() ? AnimSpec.LYING : AnimSpec.SITTING;
    }

    void think() {
        Species s = c.species();
        if (c instanceof ArcanaMount m && m.isVehicle()) return;   // the motion controller owns ridden clips
        if (c instanceof ArcanaMount m && m.inAir()) return;
        String idle = s.baseIdle(), walk = s.baseWalk();
        boolean inWater = Conditions.inWater(c);
        net.minecraft.world.entity.LivingEntity target = c.getTarget();
        if (target != lastTarget) monsterRecheckAt = Long.MIN_VALUE;   // a fight starts or ends: look again now
        // a flyer standing on the ground idles and walks instead of hovering and flying
        if (s.flyer() && c.onGround() && s.has("idle") && s.has("walk")) {
            idle = "idle";
            walk = "walk";
        }
        // wild hostiles (anim states idle_wild / walk_wild / stalk / threat, all optional): a menacing idle and prowl
        // instead of the pet moods, a stalking gait toward a target still far off (the charge is the run), and a
        // threat display the moment a new target is picked
        boolean wild = wild();
        String wi = wild ? s.anim.state("idle_wild") : null;
        // later rules win: walk the rules from the last one down and stop at the first that holds
        List<Species.Mood> idleMoods = s.idleMoods;
        boolean keepBase = keepsOverWild(idle);
        for (int i = idleMoods.size() - 1; i >= 0; i--) {
            Species.Mood m = idleMoods.get(i);
            // the wild idle replaces whatever wins here unless it is a swim / flop loop: when no such rule (nor the base
            // idle) can still win below, the answer is known without checking the rest (no monster_near scan for them)
            if (wi != null && !keepBase && !keepsOverWild(m.clip()) && !keeperBelow(idleMoods, i)) {
                idle = m.clip();
                break;
            }
            if (holds(m.condition())) {
                idle = m.clip();
                break;
            }
        }
        List<Species.Mood> walkMoods = s.walkMoods;
        for (int i = walkMoods.size() - 1; i >= 0; i--) {
            Species.Mood m = walkMoods.get(i);
            if (holds(m.condition())) {
                walk = m.clip();
                break;
            }
        }
        // a fish out of water flops (the Lurefin's idle_flop)
        if (s.has("idle_flop") && s.aquatic() != null && !inWater) idle = "idle_flop";
        if (wild) {
            String ww = s.anim.state("walk_wild");
            if (wi != null && !keepsOverWild(idle)) idle = wi;
            if (ww != null && !walk.equals("swim") && !walk.equals("run")) walk = ww;
        }
        String stalk = s.anim.state("stalk");
        // a swimming stalk (the Lurefin's) only in water, a walking one only out of it
        if (target != null && stalk != null && inWater == AnimSpec.SWIMMING.equals(s.anim.tag(stalk).posture())
                && c.distanceToSqr(target) > 49) walk = stalk;
        if (target != null && target != lastTarget && !c.isTame() && s.hostile()) {
            String threat = s.anim.state("threat");
            if (threat != null) c.play(threat);
        }
        lastTarget = target;
        // a second body state with its own loops (the Pockie's fishbowl) maps the mood's idle / gait
        idle = c.moodIdle(idle);
        walk = c.moodWalk(walk);
        if (!idle.equals(mood)) variant = "";      // a new mood ends a loop variant
        mood = idle;
        long now = c.level().getGameTime();
        if (!variant.isEmpty() && (now >= variantUntil || !posture().equals(AnimSpec.STANDING))) variant = "";
        String shown = variant.isEmpty() ? idle : variant;
        if (!shown.equals(c.idleClip())) c.setIdleClip(shown);
        if (!walk.equals(c.walkClip())) c.setWalkClip(walk);
        // owner coming back after being away -> notice
        if (s.has("notice")) {
            ServerPlayer owner = c.ownerPlayer();
            if (owner != null && owner.level() == c.level()) {
                double d = owner.distanceToSqr(c);
                if (d > 256) far = true;
                else if (far && d < 36) {
                    far = false;
                    c.play("notice");
                }
            }
        }
        // leaving water -> shake off
        if (!s.flyer() && s.has("shake")) {
            if (inWater) wet = true;
            else if (wet) {
                wet = false;
                c.scheduler().after(10, () -> {
                    c.play("shake");
                    c.particles(ParticleTypes.SPLASH, 20, 0.4, 0.3, 0.5, 0.1);
                });
            }
        }
    }

    /** Idle loops a wild creature keeps over its wild idle (it swims or flops whatever its mood). */
    private static boolean keepsOverWild(String clip) {
        return clip.equals("idle_swim") || clip.equals("idle_flop");
    }

    private static boolean keeperBelow(List<Species.Mood> moods, int i) {
        for (int k = i - 1; k >= 0; k--) if (keepsOverWild(moods.get(k).clip())) return true;
        return false;
    }

    // ------------------------------------------------------------------ idle director

    /** Every second: if the next idle event is due and the creature is still and free, pick one from the pool. */
    void direct() {
        long now = c.level().getGameTime();
        if (now < nextEvent) return;
        nextEvent = now + 60 + c.getRandom().nextInt(110);
        Species s = c.species();
        if (c.busy() || c.getTarget() != null || c.isVehicle() || c.isPassenger()) return;
        if (c.getDeltaMovement().horizontalDistanceSqr() > 0.0004 || !c.getNavigation().isDone()) return;
        String posture = posture();
        if (AnimSpec.STANDING.equals(posture) && !c.holdClip().isEmpty()) return;   // a skill holds a pose
        List<AnimSpec.IdleEntry> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        String moodPool = mood.equals(s.baseIdle()) || mood.equals("idle") ? "idle" : mood;
        if (Conditions.dawn(c.level())) moodPool = "dawn";
        boolean calm = moodPool.equals("idle") || moodPool.equals("idle_happy") || moodPool.equals("idle_sleepy") || moodPool.equals("dawn");
        if (wild() && mood.equals(s.anim.state("idle_wild"))) {
            moodPool = "wild";      // no pet flourishes; the idle pool's own entries (when: !tame) are the wild idles
            calm = true;
        }
        if (AnimSpec.STANDING.equals(posture)) {
            // only a standing creature can dance, and only one with the clip and a dance pool (the bosses have the clip
            // but no pools): the jukebox scan runs for nobody else
            boolean dance = s.has("dance") && s.pools.containsKey("dance") && Conditions.blockNear(c, 8, 4, b -> b.is(Blocks.JUKEBOX));
            if (dance) moodPool = "dance";
            List<String> flourishes = s.pools.get(moodPool);
            if (flourishes != null) {
                for (String clip : flourishes) {
                    AnimSpec.IdleEntry e = new AnimSpec.IdleEntry(clip, dance ? 6 : 1, 0, 0, 0, "", Map.of());
                    total += add(pool, weights, e, now);
                }
            }
        }
        for (AnimSpec.IdleEntry e : s.anim.idle(posture)) {
            // outside the calm moods only the mood loop plays, plus entries written for that mood ("when": "mood:idle_swim")
            if (AnimSpec.STANDING.equals(posture) && !calm && !e.clip().startsWith("@") && !e.when().contains("mood:")) continue;
            total += add(pool, weights, e, now);
        }
        if (pool.isEmpty() || total <= 0) return;
        double r = c.getRandom().nextDouble() * total;
        AnimSpec.IdleEntry pick = pool.get(pool.size() - 1);
        for (int i = 0; i < pool.size(); i++) {
            r -= weights.get(i);
            if (r <= 0) {
                pick = pool.get(i);
                break;
            }
        }
        run(pick, posture, now);
    }

    private double add(List<AnimSpec.IdleEntry> pool, List<Double> weights, AnimSpec.IdleEntry e, long now) {
        String clip = e.clip();
        if (!clip.startsWith("@") && !c.species().has(clip)) return 0;
        Long ready = cooldowns.get(clip);
        if (ready != null && now < ready) return 0;
        if (!e.when().isEmpty()) {
            for (String cond : e.when().split(",")) {
                cond = cond.trim();
                boolean not = cond.startsWith("!");
                if (holds(not ? cond.substring(1) : cond) == not) return 0;
            }
        }
        double w = e.weight();
        for (Map.Entry<String, Double> b : e.boost().entrySet()) if (holds(b.getKey())) w *= b.getValue();
        if (w <= 0) return 0;
        pool.add(e);
        weights.add(w);
        return w;
    }

    private void run(AnimSpec.IdleEntry e, String posture, long now) {
        Species s = c.species();
        String clip = e.clip();
        switch (clip) {
            case "@mood" -> {
                variant = "";
                return;
            }
            case "@sit", "@sleep" -> {
                c.restPose();
                return;
            }
            default -> {
            }
        }
        AnimSpec.Tag t = s.anim.tag(clip);
        double cd = e.cooldown() > 0 ? e.cooldown() : Math.max(6, t.len() * 2.5);
        cooldowns.put(clip, now + Math.round(cd * 20));
        if (t.once()) {
            c.play(clip);
            Species.Sound fs = s.flourishSounds.get(clip);
            if (fs != null) c.sound(fs);
            return;
        }
        // a loop (or held pose) for a while
        int ticks = (int) Math.round((e.min() + c.getRandom().nextDouble() * Math.max(0, e.max() - e.min())) * 20);
        if (AnimSpec.STANDING.equals(posture)) {
            variant = clip;
            variantUntil = now + ticks;
            c.setIdleClip(clip);
        } else {
            c.restPose(clip, ticks);
        }
    }

    // ------------------------------------------------------------------ conditions

    boolean holds(String cond) {
        int colon = cond.indexOf(':');
        String key = colon < 0 ? cond : cond.substring(0, colon);
        String arg = colon < 0 ? "" : cond.substring(colon + 1);
        switch (key) {
            case "night":
                return Conditions.night(c.level());
            case "day":
                return Conditions.day(c.level());
            case "dawn":
                return Conditions.dawn(c.level());
            case "cold_biome", "cold":
                return Conditions.cold(c);
            case "hot_biome", "hot":
                return Conditions.hot(c);
            case "rain_outside", "rain":
                return Conditions.rainingOn(c);
            case "happy":
                return c.happy();
            case "monster_near": {
                long now = c.level().getGameTime();
                if (now >= monsterRecheckAt) {
                    monsterNearCached = Conditions.monsterNear(c, 10);
                    monsterRecheckAt = now + 40;
                }
                return monsterNearCached;
            }
            case "hurt":
                return c.getHealth() < c.getMaxHealth() * 0.35f;
            case "thunder_outside", "thunder":
                return Conditions.thunderOutside(c);
            case "in_water":
                return Conditions.inWater(c);
            case "in_bowl":   // the Pockie rolls about in its fishbowl (FishbowlCreature)
                return c.inBowl();
            case "in_air":   // flyers: off the ground and out of water (hovering); on_ground is the opposite
                return !c.onGround() && !Conditions.inWater(c);
            case "on_ground":
                return c.onGround();
            case "in_combat":
                return c.getTarget() != null;
            case "on_bed":
                return Conditions.onBed(c);
            case "sunbeam":
                return Conditions.sunbeam(c);
            case "in_chest_block":
                return Conditions.inChestBlock(c);
            case "on_container":
                return Conditions.onContainer(c);
            case "on_seat":
                return Conditions.onSeat(c);
            case "on_paperwork":
                return Conditions.onPaperwork(c);
            case "meadow":
                return Conditions.inMeadow(c);
            case "on_carpet":
                return Conditions.onCarpet(c);
            case "staying":
                return c.staying();
            case "tame":
                return c.isTame();
            case "owner_near":
                return c.ownerWithin(arg.isEmpty() ? 8 : Double.parseDouble(arg)) != null;
            case "owner_far": {
                ServerPlayer o = c.ownerPlayer();
                return o == null || o.level() != c.level() || o.distanceToSqr(c) > 24 * 24;
            }
            case "player_near":
                return c.level().getNearestPlayer(c, arg.isEmpty() ? 6 : Double.parseDouble(arg)) != null;
            case "alone":
                return c.level().getNearestPlayer(c, 16) == null;
            case "mood":
                return mood.equals(arg);
            case "owner_holding": {
                ServerPlayer o = c.ownerWithin(6);
                if (o == null) return false;   // the registry lookup only when the owner is close
                Item item = Species.item(arg);
                return item != null && (o.getMainHandItem().is(item) || o.getOffhandItem().is(item));
            }
            case "owner_hurt": {
                ServerPlayer o = c.ownerWithin(16);
                return o != null && o.getHealth() < o.getMaxHealth() * 0.3f;
            }
            case "owner_crouching": {
                ServerPlayer o = c.ownerWithin(12);
                return o != null && o.isShiftKeyDown();
            }
            case "owner_sprinting": {
                ServerPlayer o = c.ownerWithin(24);
                return o != null && o.isSprinting();
            }
            default:
                return false;
        }
    }

    /** The nearest player the creature should look at: its owner within 10 blocks, else anyone within 6. */
    @Nullable Player lookTarget() {
        ServerPlayer o = c.ownerWithin(10);
        if (o != null) return o;
        return c.level().getNearestPlayer(c, 6);
    }
}
