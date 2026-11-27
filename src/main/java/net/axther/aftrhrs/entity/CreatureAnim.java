package net.axther.aftrhrs.entity;

import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.LoopType;
import com.geckolib.animation.object.PlayState;
import com.geckolib.animation.state.AnimationPoint;
import com.geckolib.animation.state.AnimationTest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.axther.aftrhrs.AftrHrs;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

/**
 * The animation state machine of every Arcana creature (PORTING.md "Animation"). Three GeckoLib controllers:
 * <ul>
 *   <li>{@code main} (base layer): locomotion, postures (sit / lie / sleep / held poses), idle loops and whole-body
 *       one-shots. Driven by {@link Driver} on the client from the server's intent (synced idle / walk / hold clips,
 *       vanilla's sitting pose, triggered one-shots) and the creature's <b>measured</b> movement: speed with hysteresis
 *       picks idle / walk / run; posture clips only play while still and are entered / left through their transition
 *       clips (sit_down, stand_up, lie_down, wake_up); movement cancels a posture or a one-shot that cannot move with a
 *       quick cross-fade. Every change cross-fades.</li>
 *   <li>{@code action} (overlay layer): one-shots tagged {@code overlay} (attacks, hurt, head-only clips) layered on
 *       whatever the body does; they may play while moving.</li>
 *   <li>{@code accent} (additive): leap, land, takeoff accents.</li>
 * </ul>
 * {@link ArcanaMob#play(String)} routes a clip to its layer's controller by its {@link AnimSpec.Tag}.
 */
public final class CreatureAnim {
    public static final String MAIN = "main", ACTION = "action", ACCENT = "accent";
    /** Cross-fades in ticks: between idle loops, locomotion changes, cancelling a pose for movement, into postures. */
    static final int FADE_IDLE = 8, FADE_MOVE = 5, FADE_CANCEL = 3, FADE_POSE = 5, ACTION_FADE = 3;
    /** Client log of the played clip per tick ({@code -Daftrhrs.animTrace=true}); see {@link Driver#trace}. */
    static final boolean TRACE = Boolean.getBoolean("aftrhrs.animTrace");

    private static final Map<String, RawAnimation> PLAY = new ConcurrentHashMap<>();
    private static final Map<String, RawAnimation> SEQ = new ConcurrentHashMap<>();
    /** Keyed by the creature's GeckoLib instance cache (identity; an entity compares by network id, so a re-tracked
     *  creature would find, then lose, its old object's entry). Written only by {@link #register}, read only by
     *  {@link #driverCount}: lookups go through {@link ArcanaMob#animDriver()} (no lock on the render path). */
    private static final Map<Object, Driver> DRIVERS = Collections.synchronizedMap(new WeakHashMap<>());

    private CreatureAnim() {
    }

    static RawAnimation play(String clip) {
        return PLAY.computeIfAbsent(clip, c -> RawAnimation.begin().then(c, LoopType.DEFAULT));
    }

    /** A clip sequence: transition clips played once, then the target clip as its own loop mode says. */
    static RawAnimation sequence(List<String> transitions, String target, AnimSpec.Tag tag) {
        String key = String.join(">", transitions) + "|" + target;
        return SEQ.computeIfAbsent(key, k -> {
            RawAnimation r = RawAnimation.begin();
            for (String t : transitions) r = r.thenPlay(t);
            if (tag.holds()) return r.thenPlayAndHold(target);
            if (tag.loops()) return r.thenLoop(target);
            return r.then(target, LoopType.DEFAULT);
        });
    }

    /** How many creatures have an animation driver right now (diagnostics: DevScript {@code status}). */
    public static int driverCount() {
        return DRIVERS.size();
    }

    /** The client driver of a creature (procedural layer and debug), or null before its first frame. */
    public static @Nullable Driver driver(Object mob) {
        return mob instanceof ArcanaMob a ? a.animDriver() : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register(ArcanaMob mob, AnimatableManager.ControllerRegistrar controllers) {
        Species s = mob.species();
        if (s.clips.isEmpty()) return;
        Driver d = new Driver(mob);
        DRIVERS.put(mob.getAnimatableInstanceCache(), d);
        mob.setAnimDriver(d);
        AnimationController main = new AnimationController<>(MAIN, FADE_MOVE, test -> d.main((AnimationTest) test)).receiveTriggeredAnimations();
        AnimationController action = new AnimationController<>(ACTION, ACTION_FADE, test -> PlayState.STOP);
        AnimationController accent = new AnimationController<>(ACCENT, 1, test -> PlayState.STOP).additiveAnimations();
        for (AnimationController ctl : new AnimationController[]{main, action, accent}) ctl.setSoundKeyframeHandler(e -> keyframeSound(mob, e));
        for (AnimationController ctl : new AnimationController[]{main, action, accent}) ctl.setParticleKeyframeHandler(e -> keyframeParticle(mob, e));
        for (Species.Clip c : s.clips.values()) {
            RawAnimation r = play(c.name());
            main.triggerableAnim(c.name(), r);
            action.triggerableAnim(c.name(), r);
            accent.triggerableAnim(c.name(), r);
        }
        controllers.add(main, action, accent);
    }

    /**
     * A clip's sound keyframe ({@code animkit.Clip.sound}: {@code "event|volume|pitch"}), client side: a bare event is
     * the species' own sfx event ({@code splash} -> {@code aftrhrs:<species>.splash}), else a full sound id. Played
     * locally at the creature (each client animates it itself) with a small pitch jitter; silent mobs stay silent.
     */
    @SuppressWarnings("rawtypes")
    static void keyframeSound(ArcanaMob mob, com.geckolib.animation.state.KeyFrameEvent e) {
        Mob m = mob.self();
        if (m.isSilent() || m.level() == null || !m.level().isClientSide()) return;
        if (!(e.keyframeData() instanceof com.geckolib.cache.animation.keyframeevent.SoundKeyframeData d)) return;
        String[] p = d.getSound().split("\\|");
        String name = p[0].trim();
        net.minecraft.resources.Identifier id = name.contains(":") ? net.minecraft.resources.Identifier.tryParse(name)
                : AftrHrs.id(mob.species().id + "." + name);
        if (id == null) return;
        net.minecraft.sounds.SoundEvent ev = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (ev == null) return;
        float vol = 1f, pitch = 1f;
        try {
            if (p.length > 1) vol = Float.parseFloat(p[1]);
            if (p.length > 2) pitch = Float.parseFloat(p[2]);
        } catch (NumberFormatException ignored) {
        }
        pitch *= 0.95f + m.getRandom().nextFloat() * 0.1f;
        m.level().playLocalSound(m.getX(), m.getY(), m.getZ(), ev, m.getSoundSource(), vol, pitch, false);
    }

    /**
     * A clip's particle keyframe ({@code animkit.Clip.particle}: {@code "particle|count|spread|speed|height"}), client
     * side: a vanilla simple particle type ({@code minecraft:end_rod}) puffed around the creature at {@code height} x
     * its bounding-box height, {@code spread} blocks wide. Invisible mobs show none.
     */
    @SuppressWarnings("rawtypes")
    static void keyframeParticle(ArcanaMob mob, com.geckolib.animation.state.KeyFrameEvent e) {
        Mob m = mob.self();
        if (m.isInvisible() || m.level() == null || !m.level().isClientSide()) return;
        if (!(e.keyframeData() instanceof com.geckolib.cache.animation.keyframeevent.ParticleKeyframeData d)) return;
        String[] p = d.getEffect().split("\\|");
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(p[0].trim());
        if (id == null) return;
        if (!(net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getValue(id)
                instanceof net.minecraft.core.particles.SimpleParticleType type)) return;
        int count = 4;
        double spread = 0.25, speed = 0.02, height = 0.5;
        try {
            if (p.length > 1) count = Math.max(1, Math.min(32, Integer.parseInt(p[1].trim())));
            if (p.length > 2) spread = Double.parseDouble(p[2]);
            if (p.length > 3) speed = Double.parseDouble(p[3]);
            if (p.length > 4) height = Double.parseDouble(p[4]);
        } catch (NumberFormatException ignored) {
        }
        var r = m.getRandom();
        double y = m.getY() + m.getBbHeight() * height;
        for (int i = 0; i < count; i++) {
            m.level().addParticle(type, m.getX() + (r.nextDouble() - 0.5) * 2 * spread, y + (r.nextDouble() - 0.5) * spread,
                    m.getZ() + (r.nextDouble() - 0.5) * 2 * spread, (r.nextDouble() - 0.5) * speed, r.nextDouble() * speed,
                    (r.nextDouble() - 0.5) * speed);
        }
    }

    /** The controller a clip plays on (by its tag's layer). */
    static String controllerFor(Species s, String clip) {
        AnimSpec.Tag t = s.anim.tag(clip);
        if (AnimSpec.ADDITIVE.equals(t.layer())) return ACCENT;
        if (t.overlay()) return ACTION;
        return MAIN;
    }

    /**
     * Client-side state machine for the base layer (one per creature instance). Reads the synced intent and the
     * measured motion every frame, decides the clip (or transition sequence) and sets it on the controller.
     */
    public static final class Driver {
        /** Weak: the driver is the WeakHashMap's value (and the creature's own field), a strong reference would keep its
         *  own key (the entity) alive forever. */
        private final java.lang.ref.WeakReference<ArcanaMob> ref;
        private int lastTick = Integer.MIN_VALUE;
        private double lx, ly, lz;
        private boolean havePos;
        /** Smoothed horizontal / vertical speed in blocks per tick; smoothed turn rate in degrees per tick. */
        double hs, vs, yawRate;
        private float lyaw;
        boolean moving, running;
        int movingTicks, stillTicks;
        /** What the base layer shows: its posture, the key of the animation set, the clip playing now. */
        String posture = AnimSpec.STANDING;
        private @Nullable String key;
        /** {@link #key}'s target clip and whether it was set with a transition path (a sequence that ends in it). */
        private @Nullable String keyTarget;
        private boolean keyPathed;
        String clip = "";
        /** {@link #clip}'s tag in {@link #clipSpec} (the procedural layer reads it every frame). */
        private AnimSpec.Tag clipTag = AnimSpec.NONE;
        private @Nullable AnimSpec clipSpec;
        boolean oneShot, heldPose;
        int violations;
        /**
         * Per-tick memo of the intent branch of {@link #main}: the tick it was decided in, the raw synced intent it was
         * decided from (reference compares: a synced value that changes is a new instance), the controller speed and
         * the result. {@link Integer#MIN_VALUE} = nothing memoised (one-shots, death and STOP never are).
         */
        private int memoTick = Integer.MIN_VALUE;
        private @Nullable String mHold, mIdle, mWalk;
        private boolean mSit;
        private float mSpeed = 1;
        private PlayState mResult = PlayState.CONTINUE;

        Driver(ArcanaMob mob) {
            this.ref = new java.lang.ref.WeakReference<>(mob);
        }

        private ArcanaMob mob() {
            return ref.get();
        }

        public String clip() {
            return clip;
        }

        /** The tag of {@link #clip()} (cached when the clip changes). */
        public AnimSpec.Tag clipTag() {
            return clipTag;
        }

        /** Every assignment to {@link #clip} goes through here (identity compares: clip names are stable instances). */
        private void setClip(String name, AnimSpec a) {
            if (name != clip || a != clipSpec) {
                clip = name;
                clipSpec = a;
                clipTag = a.tag(name);
            }
        }

        public boolean moving() {
            return moving;
        }

        public String posture() {
            return posture;
        }

        public double speed() {
            return hs;
        }

        /** Smoothed turn rate (degrees of yaw per tick, + = turning right): the procedural bank. */
        public double yawRate() {
            return yawRate;
        }

        private void motion(Mob m, AnimSpec a) {
            int tick = m.tickCount;
            if (tick == lastTick) return;
            boolean step = lastTick != Integer.MIN_VALUE && tick == lastTick + 1;
            lastTick = tick;
            double x = m.getX(), y = m.getY(), z = m.getZ();
            if (havePos && step) {
                double dh = Math.sqrt((x - lx) * (x - lx) + (z - lz) * (z - lz)), dv = Math.abs(y - ly);
                if (dh > 2.5 || dv > 2.5) dh = dv = 0;   // teleported
                hs = hs * 0.55 + dh * 0.45;
                vs = vs * 0.55 + dv * 0.45;
            } else if (!step) {
                hs *= 0.5;
                vs *= 0.5;
            }
            float yaw = m.getYRot();
            yawRate = havePos && step ? yawRate * 0.75 + Mth.wrapDegrees(yaw - lyaw) * 0.25 : yawRate * 0.5;
            lyaw = yaw;
            lx = x;
            ly = y;
            lz = z;
            havePos = true;
            Species s = mob().species();
            boolean air = s.flyer() && !m.onGround() || m.isInWater();
            double sp = air ? Math.sqrt(hs * hs + vs * vs) : hs;
            if (moving) {
                if (sp < a.walkOff) stillTicks++;
                else stillTicks = 0;
                if (stillTicks >= 3) {
                    moving = false;
                    movingTicks = 0;
                }
            } else if (sp > a.walkOn) {
                moving = true;
                stillTicks = 0;
            }
            if (moving) movingTicks++;
            if (running) running = hs > a.runOff;
            else running = hs > a.runOn;
            if (TRACE) trace(m, a);
        }

        /** {@code [animtrace]} one line per tick; a VIOLATION is a still-only clip showing while the creature moves. */
        private void trace(Mob m, AnimSpec a) {
            if (m.level() == null || !m.level().isClientSide()) return;
            AnimSpec.Tag t = a.tag(clip);
            boolean bad = moving && movingTicks > FADE_MOVE + 2 && !clip.isEmpty() && !t.move() && t.base();
            if (bad) violations++;
            AftrHrs.LOG.info("[animtrace] t={} id={} {} clip={} p={} oneShot={} moving={} hs={} vs={} hold={} idle={} walk={}{}",
                    m.tickCount, m.getId(), mob().species().id, clip, t.posture(), oneShot, moving, String.format("%.3f", hs),
                    String.format("%.3f", vs), mob().holdClip(), mob().idleClip(), mob().walkClip(), bad ? " VIOLATION" : "");
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        PlayState main(AnimationTest<?> test) {
            Mob m = mob().self();
            Species s = mob().species();
            AnimSpec a = s.anim;
            AnimationController ctl = test.controller();
            motion(m, a);
            if (m.isDeadOrDying()) {
                memoTick = Integer.MIN_VALUE;
                String death = mob().clipFor(a.state("death"));
                if (death == null) return PlayState.STOP;
                return set(test, ctl, "death", List.of(), death, a.tag(death), FADE_CANCEL, 1);
            }
            int fade = FADE_MOVE;
            // a one-shot triggered on this layer: keep it unless the creature moves (and the clip cannot), blend out early
            if (ctl.isPlayingTriggeredAnimation()) {
                memoTick = Integer.MIN_VALUE;
                AnimationPoint p = ctl.getCurrentAnimationPoint();
                String name = p != null ? p.animation().name() : "";
                AnimSpec.Tag t = a.tag(name);
                boolean cancel = moving && !t.move();
                double left = p != null ? p.animation().length() - p.animTime() : 0;
                boolean ending = !t.loops() && !t.holds() && left <= FADE_POSE / 20.0;
                if (!cancel && !ending) {
                    oneShot = true;
                    setClip(name, a);
                    key = null;
                    keyTarget = null;
                    test.setControllerSpeed(1);
                    return PlayState.CONTINUE;
                }
                fade = cancel ? FADE_CANCEL : FADE_POSE;
                // a one-shot ends in its own posture (transitions) or back on its feet
                posture = t.transition() ? t.posture() : t.grounded() && !t.once() ? t.posture() : AnimSpec.STANDING;
                key = null;
                keyTarget = null;
            }
            oneShot = false;
            // intent: decided once per tick from the synced intent and the measured motion (motion changes per tick; a
            // packet mid-tick is picked up next tick, well inside every cross-fade). One-shots and death stay per frame.
            String rawHold = mob().holdClip(), rawIdle = mob().idleClip(), rawWalk = mob().walkClip();
            boolean sitting = m instanceof ArcanaCreature sc && sc.isInSittingPose();
            if (key != null && memoTick == m.tickCount && rawHold == mHold && rawIdle == mIdle && rawWalk == mWalk && sitting == mSit) {
                test.setControllerSpeed(mSpeed);
                AnimationPoint p = ctl.getCurrentAnimationPoint();
                if (p != null) setClip(p.animation().name(), a);
                return mResult;
            }
            memoTick = Integer.MIN_VALUE;
            String hold = rawHold;
            // a hold set in the other body state (the Pockie's water sit while it gets into its bowl) maps to this one's
            if (!hold.isEmpty()) hold = java.util.Objects.requireNonNullElse(mob().clipFor(hold), "");
            if (hold.isEmpty() && sitting) {
                String sit = mob().clipFor(a.state("sit"));
                if (sit != null) hold = sit;
            }
            if (!hold.isEmpty() && !s.has(hold)) hold = "";
            String target;
            if (moving) {
                if (!hold.isEmpty() && a.tag(hold).move()) target = hold;
                else target = gait(m, s, a);
                // leaving a sit / lie / belly pose because it moves: a quick blend, never a walk in that pose
                if (grounded(posture)) fade = Math.min(fade, FADE_CANCEL + 1);
                AnimSpec.Tag tt = a.tag(target);
                if (target.isEmpty()) return PlayState.STOP;
                String gaitKind = tt.gait();
                double nominal = "run".equals(gaitKind) ? s.speed() * 0.9 : s.speed() * 0.62;
                double speed = gaitKind != null && ("walk".equals(gaitKind) || "run".equals(gaitKind)) && nominal > 0
                        ? Mth.clamp(hs / nominal, 0.75, 1.4) : 1;
                heldPose = false;
                return memo(m, rawHold, rawIdle, rawWalk, sitting, speed, set(test, ctl, target, List.of(), target, tt, fade, speed));
            }
            target = !hold.isEmpty() ? hold : mob().idleClip();
            if (!s.has(target)) target = a.state("idle") != null ? a.state("idle") : s.baseIdle();
            target = fits(s, target, a.state("idle") != null ? a.state("idle") : s.baseIdle());
            if (!s.has(target)) return PlayState.STOP;
            AnimSpec.Tag tt = a.tag(target);
            String want = tt.posture();
            // posture changes the server holds (sit, nap, stay over) go through their transition clips; idle mood loops
            // were authored to blend straight from standing, so they cross-fade
            boolean fromHold = !hold.isEmpty();
            boolean usePath = !tt.transition() && (fromHold && grounded(want) || heldPose && grounded(posture));
            List<String> path = usePath ? a.path(posture, want) : List.of();
            if (path.isEmpty() && !posture.equals(want)) fade = Math.max(fade, FADE_POSE + 3);
            else if (posture.equals(want) && fade == FADE_MOVE) fade = FADE_IDLE;
            String before = key;
            PlayState st = set(test, ctl, target, path, target, tt, fade, 1);
            if (!java.util.Objects.equals(before, key)) heldPose = fromHold;
            return memo(m, rawHold, rawIdle, rawWalk, sitting, 1, st);
        }

        /** Records this tick's intent decision (see {@link #memoTick}) and returns it. */
        private PlayState memo(Mob m, String hold, String idle, String walk, boolean sitting, double speed, PlayState result) {
            memoTick = m.tickCount;
            mHold = hold;
            mIdle = idle;
            mWalk = walk;
            mSit = sitting;
            mSpeed = (float) speed;
            mResult = result;
            return result;
        }

        /** The clip that plays for {@code clip} in the creature's current body state ({@link ArcanaMob#clipFor}), else
         *  the one for {@code fallback}, else "" (stop). */
        private String fits(Species s, String clip, @Nullable String fallback) {
            String c = s.has(clip) ? mob().clipFor(clip) : null;
            if (c == null || !s.has(c)) c = fallback != null && s.has(fallback) ? mob().clipFor(fallback) : null;
            return c != null && s.has(c) ? c : "";
        }

        static boolean grounded(String posture) {
            return AnimSpec.SITTING.equals(posture) || AnimSpec.LYING.equals(posture) || AnimSpec.BELLY.equals(posture);
        }

        private String gait(Mob m, Species s, AnimSpec a) {
            String walk = mob().walkClip();
            if (!s.has(walk)) walk = a.state("walk") != null ? a.state("walk") : s.baseWalk();
            if (s.flyer() && !m.onGround() && !m.isInWater()) {
                String fly = a.state("fly");
                if (fly != null && !a.tag(walk).posture().equals(AnimSpec.AIRBORNE)) walk = fly;
            }
            String base = a.state("walk"), run = a.state("run");
            if (running && run != null && walk.equals(base) && !s.flyer() && !m.isInWater()) walk = run;
            return fits(s, walk, base != null ? base : s.baseWalk());
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private PlayState set(AnimationTest<?> test, AnimationController ctl, String target, List<String> path, String clipName,
                              AnimSpec.Tag tag, int fade, double speed) {
            String k = path.isEmpty() ? target : String.join(">", path) + ">" + target;
            test.setControllerSpeed((float) speed);
            // a running transition sequence already ends in this clip: let it finish
            if (path.isEmpty() && keyPathed && target.equals(keyTarget)) k = key;
            if (!k.equals(key)) {
                if (TRACE) AftrHrs.LOG.info("[animtrace] id={} set {} (was {}, posture {} -> {}, fade {})", mob().self().getId(), k, key, posture,
                        tag.posture(), fade);
                ctl.setTransitionTicks(fade);
                RawAnimation raw = path.isEmpty() ? (tag.holds() ? sequence(List.of(), target, tag) : play(target)) : sequence(path, target, tag);
                test.setAnimation(raw);
                key = k;
                keyTarget = target;
                keyPathed = !path.isEmpty();
                posture = tag.posture();
            }
            AnimationPoint p = ctl.getCurrentAnimationPoint();
            setClip(p != null ? p.animation().name() : clipName, mob().species().anim);
            return PlayState.CONTINUE;
        }
    }
}
