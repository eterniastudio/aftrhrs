package net.axther.aftrhrs.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The idle director of the untameable creatures (wildlife, livestock and their young, the undead); the tameables have
 * {@link MoodBrain}. Data: the species' idle pools ({@code anim.idle}, authored as {@code IDLE} in
 * {@code tools/clips/<model>.py}, PORTING.md "Animation"). Every few seconds a still creature picks from
 * <ul>
 *   <li>the pool named after what it is doing (its idle clip: {@code graze}, {@code dormant}, {@code swim}, a perched
 *       gull's {@code idle}), else {@code standing} when it is free, {@code lying} while it lies down (wildlife);</li>
 *   <li>{@code notice} once when a player walks up (10 blocks; a sneaking player gets to 4 first);</li>
 *   <li>{@code walk}: walk-cycle variants, each kept for min..max seconds (the base walk only).</li>
 * </ul>
 * Loops run min..max seconds (an idle variant of the activity, or a held lying variant), one-shots play once (the
 * creature does not wander off during a still-only one), {@code @mood} / {@code @sleep} go back to the activity clip.
 * Never the same pick twice in a row. While moving only overlay one-shots that may move are picked.
 * Conditions ({@code when} / {@code boost}): night, day, dawn, dusk, rain, thunder, cold, hot, in_water, hurt, young,
 * spooked, player_near[:r], alone, herd_near[:r], adult_near[:r], young_near[:r], rub_near, var:&lt;name&gt;. A pick
 * whose condition found a herd mate, a player or a rubbing post turns the head to it while it plays.
 */
final class WildBrain {
    private final Mob m;
    private final ArcanaMob a;
    private String variant = "", variantOf = "", last = "", walkVariant = "";
    private long variantUntil, lieUntil, nextEvent, lingerUntil, walkUntil, noticeReady, lookUntil;
    private boolean playerNear;
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** What the last condition check found (look target). */
    private @Nullable Entity foundEntity, lookEntity;
    private @Nullable BlockPos foundBlock, lookBlock;

    WildBrain(Mob m) {
        this.m = m;
        this.a = (ArcanaMob) m;
    }

    private long now() {
        return m.level().getGameTime();
    }

    /** True while a still-only one-shot plays: the idle wander goals wait. */
    boolean lingering() {
        return now() < lingerUntil;
    }

    /** The idle clip to show for the activity {@code base}: a running variant of it, else base. */
    String idle(String base) {
        return !variant.isEmpty() && variantOf.equals(base) && now() < variantUntil ? variant : base;
    }

    /** The walk clip for {@code base}: the current walk variant while the base walk is the gait. */
    String walk(String base) {
        return !walkVariant.isEmpty() && base.equals(a.species().baseWalk()) && a.species().has(walkVariant) ? walkVariant : base;
    }

    /** After {@link ArcanaMob#play}: a still-only one-shot keeps the creature in place for its length. */
    void onPlayed(String clip) {
        AnimSpec.Tag t = a.species().anim.tag(clip);
        if (t.base() && !t.move() && t.once()) lingerUntil = Math.max(lingerUntil, now() + t.ticks());
    }

    /** Hurt / spooked: drop everything (it runs). */
    void interrupt() {
        lingerUntil = 0;
        lookUntil = 0;
        if (!variant.isEmpty() && a.idleClip().equals(variant)) a.setIdleClip(variantOf);
        variant = "";
    }

    private boolean lyingDown() {
        return m instanceof ArcanaWildlife w && w.lyingDown();
    }

    private boolean moving() {
        return m.getDeltaMovement().horizontalDistanceSqr() > 4.0E-4 || !m.getNavigation().isDone();
    }

    /** Every tick: keeps the head on what the current pick looks at. */
    void tick() {
        if (now() >= lookUntil) return;
        if (lookEntity != null && lookEntity.isAlive()) m.getLookControl().setLookAt(lookEntity, 30, 30);
        else if (lookBlock != null) m.getLookControl().setLookAt(lookBlock.getX() + 0.5, lookBlock.getY() + 0.5, lookBlock.getZ() + 0.5, 30, 30);
    }

    /** Every second (server). */
    void second() {
        Species s = a.species();
        long now = now();
        String shown = a.idleClip();
        if (!variant.isEmpty()) {
            if (!shown.equals(variant)) variant = "";                       // someone else set the idle clip
            else if (now >= variantUntil || moving()) {
                a.setIdleClip(variantOf);
                variant = "";
            }
        }
        String activity = variant.isEmpty() ? a.idleClip() : variantOf;
        boolean lying = lyingDown();
        if (lying && now >= lieUntil && m instanceof ArcanaWildlife w && !a.holdClip().equals(w.lieClip())) a.hold(w.lieClip());

        // walk-cycle variants
        List<AnimSpec.IdleEntry> walks = s.anim.idle("walk");
        if (!walks.isEmpty() && now >= walkUntil) {
            AnimSpec.IdleEntry e = pick(walks, false, now, true);
            walkVariant = e == null || e.clip().startsWith("@") ? "" : e.clip();
            walkUntil = now + (e == null ? 200 : duration(e));
        }

        // a player walks up: look up from what it is doing
        Player p = nearestPlayer();
        boolean near = p != null;
        if (near && !playerNear && now >= noticeReady && m.getTarget() == null && !lying && a.holdClip().isEmpty() && !lingering()) {
            AnimSpec.IdleEntry e = pick(s.anim.idle("notice"), moving(), now, false);
            if (e != null) {
                noticeReady = now + 240;
                run(e, "notice", activity, now);
                lookEntity = p;
                lookBlock = null;
                lookUntil = now + Math.max(30, s.anim.tag(e.clip()).ticks());
            }
        }
        playerNear = near;

        // the idle director
        if (now < nextEvent) return;
        nextEvent = now + 50 + m.getRandom().nextInt(90);
        if (m.getTarget() != null || m.isPassenger() || lingering() || !m.isAlive()) return;
        String key;
        if (lying) key = "lying";
        else if (!a.holdClip().isEmpty()) return;
        else if (!s.anim.idle(activity).isEmpty()) key = activity;
        else if (!a.busy() && (activity.equals(s.baseIdle()) || activity.equals("idle"))) key = AnimSpec.STANDING;
        else return;
        AnimSpec.IdleEntry e = pick(s.anim.idle(key), !lying && moving(), now, false);
        if (e != null) run(e, key, activity, now);
    }

    private int duration(AnimSpec.IdleEntry e) {
        return (int) Math.round((e.min() + m.getRandom().nextDouble() * Math.max(0, e.max() - e.min())) * 20);
    }

    private @Nullable Player nearestPlayer() {
        boolean undead = a.species().kind == Species.Kind.UNDEAD;
        double r = undead ? 12 : 10;
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : m.level().players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = p.distanceToSqr(m);
            double lim = p.isShiftKeyDown() ? 4 : r;
            if (d < lim * lim && d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private AnimSpec.@Nullable IdleEntry pick(List<AnimSpec.IdleEntry> pool, boolean moving, long now, boolean walking) {
        Species s = a.species();
        List<AnimSpec.IdleEntry> cands = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (AnimSpec.IdleEntry e : pool) {
            String clip = e.clip();
            boolean meta = clip.startsWith("@");
            if (!meta && !s.has(clip)) continue;
            if (!walking && moving && (meta || !(s.anim.tag(clip).move() && s.anim.tag(clip).overlay()))) continue;
            Long ready = cooldowns.get(clip);
            if (ready != null && now < ready) continue;
            if (!walking && clip.equals(last) && pool.size() > 1) continue;
            if (!conditions(e)) continue;
            double w = e.weight();
            for (Map.Entry<String, Double> b : e.boost().entrySet()) if (holds(b.getKey())) w *= b.getValue();
            if (w <= 0) continue;
            cands.add(e);
            weights.add(w);
            total += w;
        }
        if (cands.isEmpty()) return null;
        double r = m.getRandom().nextDouble() * total;
        for (int i = 0; i < cands.size(); i++) {
            r -= weights.get(i);
            if (r <= 0) return cands.get(i);
        }
        return cands.get(cands.size() - 1);
    }

    private boolean conditions(AnimSpec.IdleEntry e) {
        if (e.when().isEmpty()) return true;
        for (String cond : e.when().split(",")) {
            cond = cond.trim();
            boolean not = cond.startsWith("!");
            if (holds(not ? cond.substring(1) : cond) == not) return false;
        }
        return true;
    }

    private void run(AnimSpec.IdleEntry e, String key, String activity, long now) {
        Species s = a.species();
        String clip = e.clip();
        last = clip;
        if (clip.startsWith("@")) {
            if (AnimSpec.LYING.equals(key) && m instanceof ArcanaWildlife w) {
                a.hold(w.lieClip());
                lieUntil = now + duration(e);
            } else if (!variant.isEmpty()) {
                a.setIdleClip(variantOf);
                variant = "";
            }
            return;
        }
        // look at whatever the pick's condition found (a herd mate, the player, the post it rubs on)
        foundEntity = null;
        foundBlock = null;
        conditions(e);
        if (foundEntity != null || foundBlock != null) {
            lookEntity = foundEntity;
            lookBlock = foundBlock;
            lookUntil = now + Math.max(40, s.anim.tag(clip).ticks());
        }
        AnimSpec.Tag t = s.anim.tag(clip);
        double cd = e.cooldown() > 0 ? e.cooldown() : Math.max(6, t.len() * 2.5);
        cooldowns.put(clip, now + Math.round(cd * 20));
        if (t.once()) {
            a.play(clip);
            return;
        }
        int ticks = duration(e);
        if (AnimSpec.LYING.equals(key)) {
            a.hold(clip);
            lieUntil = now + ticks;
        } else {
            variant = clip;
            variantOf = activity;
            variantUntil = now + ticks;
            a.setIdleClip(clip);
        }
    }

    // ------------------------------------------------------------------ conditions

    private static String base(String id) {
        return id.endsWith("_young") ? id.substring(0, id.length() - 6) : id;
    }

    private @Nullable Mob kin(double r, int young) {
        String me = base(a.species().id);
        Mob best = null;
        double bd = Double.MAX_VALUE;
        for (Mob o : Conditions.nearby(m, r, Mob.class, x -> x != m && x.isAlive() && x instanceof ArcanaMob am && base(am.species().id).equals(me))) {
            boolean y = ((ArcanaMob) o).species().young();
            if (young == 1 && !y || young == 0 && y) continue;
            double d = o.distanceToSqr(m);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    private static boolean rubbable(BlockState b) {
        return b.is(BlockTags.LOGS) || b.is(BlockTags.FENCES) || b.is(BlockTags.WALLS) || b.is(BlockTags.FENCE_GATES);
    }

    boolean holds(String cond) {
        int colon = cond.indexOf(':');
        String key = colon < 0 ? cond : cond.substring(0, colon);
        String arg = colon < 0 ? "" : cond.substring(colon + 1);
        double r = 0;
        if (!arg.isEmpty() && !key.equals("var")) {
            try {
                r = Double.parseDouble(arg);
            } catch (NumberFormatException ignored) {
            }
        }
        switch (key) {
            case "night":
                return Conditions.night(m.level());
            case "day":
                return Conditions.day(m.level());
            case "dawn":
                return Conditions.dawn(m.level());
            case "dusk":
                return !m.level().dimensionType().hasFixedTime() && Conditions.timeIn(m.level(), 11800, 13500);
            case "rain":
                return Conditions.rainingOn(m);
            case "thunder":
                return Conditions.thunderOutside(m);
            case "cold":
                return Conditions.cold(m);
            case "hot":
                return Conditions.hot(m);
            case "in_water":
                return Conditions.inWater(m);
            case "hurt":
                return m.getHealth() < m.getMaxHealth() * 0.35f;
            case "young":
                return a.species().young();
            case "spooked":
                return m instanceof ArcanaWildlife w && w.spooked();
            case "player_near": {
                Player p = m.level().getNearestPlayer(m, r > 0 ? r : 6);
                if (p != null && !p.isSpectator()) foundEntity = p;
                return p != null && !p.isSpectator();
            }
            case "alone":
                return m.level().getNearestPlayer(m, 16) == null;
            case "herd_near", "adult_near", "young_near": {
                Mob k = kin(r > 0 ? r : 8, key.equals("adult_near") ? 0 : key.equals("young_near") ? 1 : -1);
                if (k != null) foundEntity = k;
                return k != null;
            }
            case "rub_near": {
                BlockPos c = m.blockPosition().above();
                int reach = Math.max(1, (int) Math.ceil(m.getBbWidth() / 2 + 0.6));
                for (BlockPos q : BlockPos.betweenClosed(c.offset(-reach, -1, -reach), c.offset(reach, 0, reach))) {
                    if (rubbable(m.level().getBlockState(q))) {
                        foundBlock = q.immutable();
                        return true;
                    }
                }
                return false;
            }
            case "var":
                return a.var(arg) != 0;
            default:
                return false;
        }
    }
}
