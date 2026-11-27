package net.axther.aftrhrs.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Pockie (pocket shark, spec {@code "fishbowl"}): an aquatic pet that puts itself into a glass fishbowl when it ends
 * up out of water and rolls after its owner on land, then leaps out of the bowl when the bowl reaches water.
 *
 * <p>The bowl is part of the model (bones {@code bowl} > {@code bowl_roll} (the glass, turned like a wheel by
 * {@code bowl_roll}) and {@code water}); its state lives here, on the server, and is synced
 * ({@link #DATA_BOWL}) so the clips and the state machine agree:
 * <pre>
 *   SWIM --(out of water 4 ticks)--> ENTERING (bowl_in, still) --> IN_BOWL --(deep water 4 ticks)--> EXITING (bowl_out,
 *   still) --> SWIM
 * </pre>
 * <ul>
 *   <li>In water (SWIM): the aquatic pet base (swims after its owner, hovers), the 0.45 hitbox, water clips; the bowl
 *       bone is hidden. In the bowl: the bowl hitbox (spec {@code fishbowl.box}), ground navigation at a pet's pace,
 *       the mood brain's idle / gait become {@code bowl_idle} / {@code bowl_roll}, every played clip maps to its
 *       {@code bowl_} twin ({@link #clipFor}) and sit / sleep hold {@code bowl_sit} / {@code sleep_bowl}. No in-bowl clip
 *       ever plays in water and no water clip in the bowl (a base clip without a twin is skipped; overlays play).</li>
 *   <li>The transitions keep it still ({@link #busy()}) for their clip's length, so they never play while it moves.</li>
 *   <li>The bowl only looks like water: it never dries out or suffocates in it, nothing else can use it.</li>
 * </ul>
 */
public class FishbowlCreature extends ArcanaCreature {
    static final EntityDataAccessor<Byte> DATA_BOWL = SynchedEntityData.defineId(FishbowlCreature.class, EntityDataSerializers.BYTE);
    public static final byte SWIM = 0, ENTERING = 1, IN_BOWL = 2, EXITING = 3;
    /** Water at least this deep (blocks, at the creature) takes it out of the bowl; below DRY it gets into the bowl. */
    static final double DEEP = 0.4, DRY = 0.12;
    /** Ticks a water / dry reading must hold before the state changes (no flicker on a shore or in a wave). */
    static final int SETTLE = 4;
    /** The bowl bone disappears this many ticks before bowl_out ends (its clip has shrunk it to nothing by then). */
    static final int EXIT_HIDE_EARLY = 6;

    private int dryTicks, wetTicks, stateTicks;
    private final float boxW, boxH, boxEye;
    private final int enterTicks, exitTicks;

    public FishbowlCreature(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
        JsonObject fb = species().section("fishbowl");
        float[] box = {0.75f, 0.75f, 0.5f};
        if (fb != null && fb.has("box")) {
            var a = fb.getAsJsonArray("box");
            for (int i = 0; i < Math.min(3, a.size()); i++) box[i] = a.get(i).getAsFloat();
        }
        boxW = box[0];
        boxH = box[1];
        boxEye = box[2];
        enterTicks = fb != null ? (int) Species.dbl(fb, "enter_ticks", 34) : 34;
        exitTicks = fb != null ? (int) Species.dbl(fb, "exit_ticks", 30) : 30;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BOWL, SWIM);
    }

    // ------------------------------------------------------------------ state

    public byte bowlState() {
        return entityData.get(DATA_BOWL);
    }

    /** In the bowl, getting in or getting out: the bowl is shown and the bowl hitbox applies. */
    @Override
    public boolean inBowl() {
        return bowlState() != SWIM;
    }

    /** The bowl clips apply (getting in or rolling about); getting out already uses the water clips' layer. */
    private boolean bowlClips() {
        byte s = bowlState();
        return s == IN_BOWL || s == ENTERING;
    }

    private void setState(byte s) {
        if (s == bowlState()) return;
        entityData.set(DATA_BOWL, s);
        stateTicks = 0;
        refreshDimensions();
    }

    /**
     * Floating at (or bobbing just above) the surface of water deep enough to swim in: not stranded, even when only a
     * sliver of its hitbox is under water (it would otherwise get back into its bowl in the middle of a pond).
     */
    boolean swimmableWaterHere() {
        var at = blockPosition();
        for (BlockPos p : new BlockPos[] {at, at.below()}) {
            var fluid = level().getFluidState(p);
            if (fluid.is(FluidTags.WATER) && fluid.getAmount() >= 7) return true;
        }
        return false;
    }

    /** Water height at the creature (blocks), 0 out of water. */
    double waterDepth() {
        return isInWater() ? getFluidHeight(FluidTags.WATER) : 0;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel) || !isAlive()) return;
        bowlTick();
    }

    private void bowlTick() {
        double depth = waterDepth();
        stateTicks++;
        switch (bowlState()) {
            case SWIM -> {
                dryTicks = depth < DRY && !swimmableWaterHere() && !isPassenger() ? dryTicks + 1 : 0;
                if (dryTicks >= SETTLE) enterBowl();
                else if (depth >= DRY) leapAshore();
            }
            case IN_BOWL -> {
                wetTicks = depth >= DEEP ? wetTicks + 1 : 0;
                if (wetTicks >= SETTLE) exitBowl();
                else rollToWater();
                setAirSupply(getMaxAirSupply());
            }
            case ENTERING -> {
                setAirSupply(getMaxAirSupply());
                if (stateTicks >= enterTicks) {
                    setState(IN_BOWL);
                    if (busy()) free();
                }
            }
            case EXITING -> {
                // it hangs in the water while it leaps out of the bowl (no sinking, no drift: the clip plays in place)
                setDeltaMovement(Vec3.ZERO);
                if (stateTicks >= exitTicks - EXIT_HIDE_EARLY) {
                    setState(SWIM);
                    scheduler().after(EXIT_HIDE_EARLY, () -> {
                        if (busy() && bowlState() == SWIM) free();
                    });
                }
            }
            default -> {
            }
        }
    }

    /** Out of water: the bowl pops up and it hops in (bowl_in), staying put for the clip. */
    public void enterBowl() {
        if (bowlState() != SWIM) return;
        dryTicks = 0;
        setState(ENTERING);
        setBusy(true);
        setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
        setIdleClip(moodIdle(species().baseIdle()));
        setWalkClip(moodWalk(species().baseWalk()));
        stopTriggeredAnim(CreatureAnim.ACTION, null); // a flinch still running would carry on over the hop
        playRaw("bowl_in");
        // the splash as it drops into the bowl (bowl_in: lands at 0.95 s); the glass pop is the clip's own keyframe
        scheduler().after(19, () -> CreatureSfx.play(this, "splash"));
    }

    /** The bowl reached water: it leaps out with a splash and the bowl is gone (bowl_out). */
    public void exitBowl() {
        if (bowlState() != IN_BOWL) return;
        wetTicks = 0;
        setState(EXITING);
        setBusy(true);
        setDeltaMovement(Vec3.ZERO);
        setIdleClip(species().has("idle_swim") ? "idle_swim" : species().baseIdle());
        setWalkClip(species().has("swim") ? "swim" : species().baseWalk());
        stopTriggeredAnim(CreatureAnim.ACTION, null); // a flinch still running would carry on over the hop
        playRaw("bowl_out");
        // the splash as it dives back into the water (bowl_out: 0.95 s)
        scheduler().after(19, () -> CreatureSfx.play(this, "splash"));
    }

    /** Puts it straight into a state without the clip (loading a saved creature, tests). */
    public void forceState(boolean bowl) {
        setState(bowl ? IN_BOWL : SWIM);
        if (busy()) free();
    }

    /**
     * Following its owner out of the water: pressed against a bank with its owner ashore nearby, it jumps the edge
     * (then gets into its bowl on land), like a fish leaping out.
     */
    private void leapAshore() {
        if (!isTame() || staying() || busy() || !horizontalCollision || !scheduler().ready("leap", 30)) return;
        ServerPlayer o = ownerWithin(16);
        if (o == null || o.isInWater() || o.getY() < getY() - 0.5) return;
        Vec3 to = o.position().subtract(position()).multiply(1, 0, 1);
        if (to.lengthSqr() < 1.0E-4) return;
        Vec3 h = to.normalize().scale(0.28);
        setDeltaMovement(h.x, 0.5, h.z);
        needsSync = true;
    }

    /**
     * Following its owner who swims off: ground navigation stops at the bank, so the bowl rolls straight at its owner
     * and tips into the water (then it gets out of the bowl).
     */
    private void rollToWater() {
        if (!isTame() || staying() || busy() || isPassenger() || (tickCount + getId()) % 10 != 0) return;
        ServerPlayer o = ownerWithin(16);
        if (o == null || !o.isInWater() || distanceToSqr(o) < 4.0) return;
        if (!getNavigation().isDone() && !getNavigation().isStuck()) return;
        getNavigation().stop();
        getMoveControl().setWantedPosition(o.getX(), o.getY(), o.getZ(), 1.0);
    }

    // ------------------------------------------------------------------ hitbox, breath

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        if (inBowl()) return EntityDimensions.scalable(boxW, boxH).withEyeHeight(boxEye);
        return super.getDefaultDimensions(pose);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_BOWL.equals(accessor)) refreshDimensions();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        // in its bowl it never dries out (it has its water with it)
        if (inBowl() && (source.is(DamageTypes.DRY_OUT) || source.is(DamageTypes.DROWN))) return false;
        return super.hurtServer(level, source, amount);
    }

    // ------------------------------------------------------------------ clips

    /** Shows the bowl bones only while the bowl exists (the water clips also key the bowl to nothing). */
    @Override
    public String hiddenBones() {
        return inBowl() ? "" : "bowl";
    }

    /** {@code bowl_<clip>} names, made once (clipFor runs every animation frame on the client). Clip names are bounded. */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> TWINS = new java.util.concurrent.ConcurrentHashMap<>();

    private static String twin(String clip) {
        return TWINS.computeIfAbsent(clip, c -> "bowl_" + c);
    }

    @Override
    public @Nullable String clipFor(@Nullable String clip) {
        if (clip == null || clip.isEmpty()) return clip;
        boolean bowlClip = clip.startsWith("bowl_") || clip.startsWith("sleep_bowl");
        if (!bowlClips()) return bowlClip ? null : clip;
        if (bowlClip) return clip;
        if (clip.equals("sleep") && species().has("sleep_bowl")) return "sleep_bowl";
        String twin = twin(clip);
        if (species().has(twin)) return twin;
        // a water clip without a bowl twin: overlays (head / tail / fins only) still fit, whole-body clips do not
        return species().has(clip) && species().anim.tag(clip).overlay() ? clip : null;
    }

    /**
     * Nothing else plays while it gets in or out: the transition carries the creature through the open top, and any
     * other clip cross-faded in mid-way would take a straight path through the glass (bowl_check.py).
     */
    @Override
    public void play(@Nullable String clip) {
        byte s = bowlState();
        if (s == ENTERING || s == EXITING) return;
        playRaw(clipFor(clip));
    }

    /** Killed half way in or out of the bowl: the bowl goes (a death pose fading in from mid-hop would cross the glass). */
    @Override
    public void die(DamageSource source) {
        byte s = bowlState();
        if (s == ENTERING || s == EXITING) setState(SWIM);
        super.die(source);
    }

    /** {@link ArcanaMob#play} without the bowl mapping (the transitions themselves). */
    private void playRaw(@Nullable String clip) {
        if (clip == null || !species().has(clip)) return;
        triggerAnim(CreatureAnim.controllerFor(species(), clip), clip);
        onPlayed(clip);
    }

    @Override
    protected @Nullable String postureClip(@Nullable String clip) {
        return clipFor(clip);
    }

    @Override
    protected String moodIdle(String idle) {
        if (!bowlClips()) return idle;
        String twin = twin(idle);
        return species().has(twin) ? twin : "bowl_idle";
    }

    @Override
    protected String moodWalk(String walk) {
        return bowlClips() ? "bowl_roll" : walk;
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putBoolean("FishbowlIn", bowlState() == IN_BOWL || bowlState() == ENTERING);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        // a transition cut short by the save ends where it was going; the tick corrects it if the water changed
        entityData.set(DATA_BOWL, in.getBooleanOr("FishbowlIn", false) ? IN_BOWL : SWIM);
    }
}
