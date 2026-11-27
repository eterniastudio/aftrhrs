package net.axther.aftrhrs.entity;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The tameable Arcana creatures: pets, dogs, cats, tameable hostiles, Luna and Kevin, the aquatic pets, the flyers (and,
 * via {@link ArcanaMount}, the mounts). Also the untameable Lurefin, which shares the aquatic base. Behaves like the
 * MythicMobs version (skills/taming.yml + gen_mm.py interaction skills):
 * <ul>
 *   <li>Wild: right-click with its tame item (chance per item, hearts / smoke; pet cap via {@link CreatureEvents#CAN_TAME}).</li>
 *   <li>Owner: its food heals it, empty hand pets it, Shift + right-click stays / follows (sits; naps at night), a renamed
 *       Name Tag names it, it teleports to the owner past 20 blocks.</li>
 *   <li>Wild ones despawn with no player within 64 blocks and are not saved with their chunk.</li>
 * </ul>
 * Bond 0..5 ({@link #bond()}) scales the abilities (set by the pets module).
 */
public class ArcanaCreature extends TamableAnimal implements ArcanaMob {
    private static final EntityDataAccessor<String> DATA_IDLE = SynchedEntityData.defineId(ArcanaCreature.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_WALK = SynchedEntityData.defineId(ArcanaCreature.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_HOLD = SynchedEntityData.defineId(ArcanaCreature.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_TINT = SynchedEntityData.defineId(ArcanaCreature.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_SHINY = SynchedEntityData.defineId(ArcanaCreature.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_BOND = SynchedEntityData.defineId(ArcanaCreature.class, EntityDataSerializers.INT);

    /** Ticks the "happy" aura lasts after petting / feeding / taming (MythicMobs ArcanaHappy, 200 ticks). */
    public static final int HAPPY_TICKS = 200;
    static final com.mojang.serialization.Codec<Map<String, Integer>> VARS_CODEC =
            com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING, com.mojang.serialization.Codec.INT);

    private @Nullable Species species;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Scheduler scheduler = new Scheduler();
    private final CreatureSfx.State sfxState = new CreatureSfx.State();
    /** The client animation driver ({@link CreatureAnim#register}); a plain field: read every frame by the renderer. */
    private CreatureAnim.@Nullable Driver animDriver;
    /** Saved per-creature integers for abilities (MythicMobs saved caster variables such as gift_armed). */
    private final Map<String, Integer> vars = new HashMap<>();
    private @Nullable String variant;
    private long happyUntil;
    private boolean busy, napping, jumpAir, wasGrounded = true;
    /** The posture hold the brain set last (a skill's own hold is never overridden). */
    private String brainHold = "";
    /** A sitting / lying loop variant from the idle pool and its end (game time). */
    private String restVariant = "";
    private long restVariantUntil;
    /** Game time until which the creature stays put (getting up) / does not wander (a still-only flourish plays). */
    private long stillUntil, lingerUntil;
    final MoodBrain brain = new MoodBrain(this);

    public ArcanaCreature(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
        Species s = species();
        if (s.flyer()) {
            this.moveControl = new FlyingMoveControl<>(this, 10, false);
            setPathfindingMalus(PathType.FIRE_IN_NEIGHBOR, -1.0F);
            setPathfindingMalus(PathType.FIRE, -1.0F);
        }
        if (s.aquatic() != null) {
            setPathfindingMalus(PathType.WATER, 0.0F);
        }
    }

    @Override
    public Species species() {
        if (species == null) species = ArcanaEntities.species(getType());
        return species;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected PathNavigation createNavigation(Level level) {
        Species s = species();
        if (s.flyer()) {
            FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
            nav.setCanOpenDoors(false);
            nav.setCanFloat(true);
            return nav;
        }
        // aquatic creatures path on land like the rest (FollowOwnerGoal needs ground navigation); in water they swim
        // after their owner by velocity (swim()), as the MythicMobs version did with propel
        return super.createNavigation(level);
    }

    @Override
    protected void registerGoals() {
        Species s = species();
        boolean tameable = !s.untameable();
        int g = 1;
        if (s.aquatic() == null) goalSelector.addGoal(g++, new FloatGoal(this));
        if (tameable) goalSelector.addGoal(g++, new SitWhenOrderedToGoal(this));
        if (s.fights()) goalSelector.addGoal(g++, new MeleeAttackGoal(this, 1.2, true));
        if (tameable) {
            boolean mount = s.mount();
            goalSelector.addGoal(g++, new FollowOwnerGoal(this, mount ? 1.1 : 1.0, mount ? 10 : 6, mount ? 4 : 3));
        }
        // idle wandering waits while a still-only flourish plays (following the owner or a fight still interrupt it)
        goalSelector.addGoal(g++, s.flyer() ? new WaterAvoidingRandomFlyingGoal(this, 1.0) {
            @Override
            public boolean canUse() {
                return !lingering() && super.canUse();
            }
        } : new WaterAvoidingRandomStrollGoal(this, 0.8) {
            @Override
            public boolean canUse() {
                return !lingering() && super.canUse();
            }
        });
        if (tameable) goalSelector.addGoal(g++, new Goals.LookAtOwnerGoal(this));
        goalSelector.addGoal(g++, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(g, new RandomLookAroundGoal(this));
        int t = 1;
        if (s.fights()) {
            targetSelector.addGoal(t++, new HurtByTargetGoal(this));
            if (tameable) {
                targetSelector.addGoal(t++, new OwnerHurtByTargetGoal(this));
                targetSelector.addGoal(t++, new OwnerHurtTargetGoal(this));
            }
        }
        if (s.hostile()) {
            // wild: hunts players; once tamed it stops (ArcanaX_Domesticate) and defends its owner instead
            targetSelector.addGoal(t, new NearestAttackableTargetGoal<>(this, Player.class, true, (target, level) -> !isTame()));
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_IDLE, "");
        builder.define(DATA_WALK, "");
        builder.define(DATA_HOLD, "");
        builder.define(DATA_TINT, 0xFFFFFF);
        builder.define(DATA_SHINY, false);
        builder.define(DATA_BOND, 0);
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                                  @Nullable SpawnGroupData data) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data);
        Species s = species();
        setIdleClip(s.baseIdle());
        setWalkClip(s.baseWalk());
        String preset = Species.str(s.json, "preset_name", null);
        if (preset != null && !hasCustomName()) {
            setCustomName(net.minecraft.network.chat.Component.literal(preset));
            setCustomNameVisible(true);
        }
        play(s.anim("spawn"));
        Abilities.onSpawn(this);
        return out;
    }

    // ------------------------------------------------------------------ API for the pets module

    /** Bond level 0..5 (Feather pets; abilities scale with it). */
    public int bond() {
        return entityData.get(DATA_BOND);
    }

    public void setBond(int level) {
        entityData.set(DATA_BOND, Math.max(0, Math.min(5, level)));
    }

    /** {@code %bond(a,b)%} = a + b * bond. */
    public double bond(double base, double step) {
        return base + step * bond();
    }

    /** {@code %bondlvl(a,every)%} = a + floor(bond / every). */
    public int bondLvl(int base, int every) {
        return base + bond() / Math.max(1, every);
    }

    /** True while told to stay (sitting / guarding / napping). */
    public boolean staying() {
        return isOrderedToSit();
    }

    /** Stay or follow, as Shift + right-click does it (sit / stand animation; message to {@code owner} if given). */
    public void setStaying(boolean stay, @Nullable ServerPlayer owner) {
        if (stay == staying()) return;
        setOrderedToSit(stay);
        setInSittingPose(stay);
        getNavigation().stop();
        setTarget(null);
        if (stay) {
            // the sit pose is held from vanilla's sitting pose (postureTick); the client plays sit_down into it
            setXRot(0);
            postureTick();
            CreatureSfx.play(this, "sit");
            if (owner != null) CreatureUtil.say(owner, true, "stay", this, "&7<pet> &7will stay here.");
        } else {
            String from = species().anim.posture(holdClip());
            napping = false;
            restVariant = "";
            if (holdClip().equals(brainHold)) hold("");
            brainHold = "";
            setDeltaMovement(Vec3.ZERO);
            // the client plays the way back up (wake_up / stand_up / unhide); it stays put until it is on its feet
            java.util.List<String> up = species().anim.path(from, AnimSpec.STANDING);
            int ticks = 0;
            for (String clip : up) ticks += species().anim.tag(clip).ticks();
            if (ticks > 0) stillUntil = level().getGameTime() + Math.min(60, ticks);
            else playRole("happy");
            CreatureSfx.play(this, "stand");
            if (owner != null) CreatureUtil.say(owner, true, "follow", this, "&7<pet> &7is following you.");
        }
        CreatureEvents.STAY.invoker().onStay(this, stay);
    }

    public @Nullable String variant() {
        return variant;
    }

    public void setVariant(@Nullable String variant) {
        this.variant = variant;
    }

    @Override
    public int tint() {
        return entityData.get(DATA_TINT);
    }

    /** Color tint (0xRRGGBB) multiplied over the texture; 0xFFFFFF is the normal look. */
    public void setTint(int rgb) {
        entityData.set(DATA_TINT, rgb & 0xFFFFFF);
    }

    @Override
    public boolean shiny() {
        return entityData.get(DATA_SHINY);
    }

    public void setShiny(boolean shiny) {
        entityData.set(DATA_SHINY, shiny);
    }

    /** Saved ability variables (MythicMobs caster variables with save=true). */
    public int var(String key) {
        return vars.getOrDefault(key, 0);
    }

    public void setVar(String key, int value) {
        if (value == 0) vars.remove(key);
        else vars.put(key, value);
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public CreatureAnim.@Nullable Driver animDriver() {
        return animDriver;
    }

    @Override
    public void setAnimDriver(CreatureAnim.Driver driver) {
        this.animDriver = driver;
    }

    @Override
    public CreatureSfx.State sfxState() {
        return sfxState;
    }

    /** Busy with a long antic (MythicMobs caster.busy + setai false): no goals, stands still. */
    public boolean busy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        if (busy) {
            getNavigation().stop();
            setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
        }
    }

    /** Ends a long antic: AI back on, or back to the sit pose if it was told to stay meanwhile. */
    public void free() {
        busy = false;
        if (staying()) {
            hold("");
            brainHold = "";
            postureTick();
        } else {
            setDeltaMovement(Vec3.ZERO);
        }
    }

    /** True while a still-only flourish plays: no idle wandering (the owner or a fight still move it). */
    public boolean lingering() {
        return level().getGameTime() < lingerUntil;
    }

    private String lastPlayed = "";
    private long lastPlayedAt;

    /** The last one-shot fired with {@link #play} and when (debug trace). */
    public String lastPlayed() {
        return lastPlayed;
    }

    public long lastPlayedAt() {
        return lastPlayedAt;
    }

    /** Naps now (true) or wakes (false) while staying; the nap timer takes over again at dawn / dusk. */
    public void setNapping(boolean nap) {
        boolean was = napping;
        napping = nap && staying();
        restVariant = "";
        if (napping != was) CreatureSfx.play(this, napping ? "sleep" : "wake");
    }

    @Override
    public void onPlayed(String clip) {
        lastPlayed = clip;
        lastPlayedAt = level().getGameTime();
        AnimSpec.Tag t = species().anim.tag(clip);
        if (t.base() && !t.move() && t.once()) lingerUntil = Math.max(lingerUntil, level().getGameTime() + t.ticks());
    }

    /** Back to the plain sit / sleep pose (idle director). */
    void restPose() {
        restVariant = "";
    }

    /** A sitting / lying loop variant for a while (idle director); the plain pose comes back after {@code ticks}. */
    void restPose(String clip, int ticks) {
        restVariant = clip;
        restVariantUntil = level().getGameTime() + ticks;
    }

    /**
     * Vanilla's sitting pose is the source of truth: sitting holds the sit clip (or a sitting idle variant), napping
     * the sleep clip; standing clears the hold. A hold set by a skill (knead, stand_guard ...) is left alone.
     */
    void postureTick() {
        Species s = species();
        String want = "";
        if (isInSittingPose() && !busy) {
            String sit = postureClip(s.anim("sit")), sleep = postureClip(s.anim("sleep"));
            want = napping && sleep != null ? sleep : sit != null ? sit : "";
            if (!restVariant.isEmpty()) {
                if (level().getGameTime() < restVariantUntil && s.anim.posture(restVariant).equals(s.anim.posture(want))) want = restVariant;
                else restVariant = "";
            }
        }
        String cur = holdClip();
        if (cur.isEmpty() || cur.equals(brainHold)) {
            if (!cur.equals(want)) hold(want);
            brainHold = want;
        }
    }

    /** The sit / sleep clip postureTick holds (a second body state maps it: the Pockie's fishbowl). */
    protected @Nullable String postureClip(@Nullable String clip) {
        return clip;
    }

    /** The mood brain's idle loop / gait for this creature now (a second body state maps them). */
    protected String moodIdle(String idle) {
        return idle;
    }

    protected String moodWalk(String walk) {
        return walk;
    }

    /** True while the creature is in its fishbowl (only {@link FishbowlCreature}). */
    public boolean inBowl() {
        return false;
    }

    /** The posture the creature is in for its hitbox: from the held clip, else vanilla's sitting pose. */
    public String bodyPosture() {
        String hold = holdClip();
        if (!hold.isEmpty()) return species().anim.posture(hold);
        return isInSittingPose() ? AnimSpec.SITTING : AnimSpec.STANDING;
    }

    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions base = super.getDefaultDimensions(pose);
        float[] f = switch (bodyPosture()) {
            case AnimSpec.SITTING -> species().anim.box(AnimSpec.SITTING);
            case AnimSpec.LYING, AnimSpec.BELLY -> species().anim.box(AnimSpec.LYING);
            default -> null;
        };
        return f == null ? base : base.scale(f[0], f[1]);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (accessor == DATA_HOLD || accessor == DATA_FLAGS_ID) refreshDimensions();
    }

    public boolean happy() {
        return level().getGameTime() < happyUntil;
    }

    public void makeHappy() {
        happyUntil = level().getGameTime() + HAPPY_TICKS;
    }

    public boolean napping() {
        return napping;
    }

    public String displayName() {
        return getName().getString();
    }

    public @Nullable ServerPlayer ownerPlayer() {
        return getOwner() instanceof ServerPlayer p ? p : null;
    }

    /** The owner if online, in this level and within {@code r} blocks. */
    public @Nullable ServerPlayer ownerWithin(double r) {
        ServerPlayer p = ownerPlayer();
        return p != null && p.level() == level() && !p.isSpectator() && p.distanceToSqr(this) < r * r ? p : null;
    }

    // ------------------------------------------------------------------ ArcanaMob

    @Override
    public String idleClip() {
        return entityData.get(DATA_IDLE);
    }

    @Override
    public String walkClip() {
        return entityData.get(DATA_WALK);
    }

    @Override
    public String holdClip() {
        return entityData.get(DATA_HOLD);
    }

    @Override
    public void setIdleClip(String clip) {
        entityData.set(DATA_IDLE, clip == null ? "" : clip);
    }

    @Override
    public void setWalkClip(String clip) {
        entityData.set(DATA_WALK, clip == null ? "" : clip);
    }

    @Override
    public void setHoldClip(String clip) {
        entityData.set(DATA_HOLD, clip == null ? "" : clip);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        CreatureAnim.register(this, controllers);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    protected boolean isImmobile() {
        return busy || level().getGameTime() < stillUntil || super.isImmobile();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || !isAlive()) return;
        scheduler.tick();
        int t = tickCount + getId();
        if (t % 20 == 0) {
            brain.think();
            brain.direct();
        }
        if (t % 100 == 0) nap();
        postureTick();
        if (t % 40 == 0) leash();
        if (t % 600 == 0) wildDespawn(level);
        if (species().aquatic() != null && t % 2 == 0) swim();
        CreatureSfx.tick(this);
        airStates();
        Abilities.tick(this);
    }

    /**
     * Extra states ModelEngine could not show (its jump flag lasted one tick): every real jump of a creature with
     * jump_start / jump / jump_end clips (Luna, Kevin) is animated, and flyers play takeoff / land when they leave or
     * touch the ground.
     */
    private void airStates() {
        Species s = species();
        if (this instanceof ArcanaMount) return;
        boolean grounded = onGround() || isInWater() || isPassenger();
        if (s.flyer()) {
            if (wasGrounded && !grounded && s.has("takeoff")) play("takeoff");
            else if (!wasGrounded && grounded && s.has("land")) play("land");
        } else if (s.has("jump_start") && s.has("jump") && s.has("jump_end")) {
            double vy = getY() - yo;
            if (!jumpAir && !grounded && vy > 0.08 && holdClip().isEmpty()) {
                jumpAir = true;
                play("jump_start");
                hold("jump");
            } else if (jumpAir && grounded) {
                jumpAir = false;
                if ("jump".equals(holdClip())) hold("");
                play("jump_end");
            }
        }
        wasGrounded = grounded;
    }

    /**
     * Staying pets doze off at night and sit back up in the morning (MythicMobs &lt;Id&gt;_Nap): the sleep clip is held,
     * the client plays lie_down into it and wake_up out of it (the sleep / wake sounds start with that transition).
     */
    private void nap() {
        Species s = species();
        if (!staying() || busy || s.noNap() || s.anim("sleep") == null || s.anim("sit") == null) return;
        if (Conditions.night(level()) && !napping) {
            napping = true;
            restVariant = "";
            CreatureSfx.play(this, "sleep");
        } else if (Conditions.day(level()) && napping) {
            napping = false;
            restVariant = "";
            CreatureSfx.play(this, "wake");
        }
    }

    /** ArcanaPet_Leash: a following pet more than 20 blocks away teleports to its owner and greets. */
    private void leash() {
        if (!isTame() || staying() || isPassenger() || isVehicle() || isLeashed()) return;
        ServerPlayer owner = ownerPlayer();
        if (owner == null || owner.level() != level() || owner.isSpectator()) return;
        if (distanceToSqr(owner) > 400) {
            tryToTeleportToOwner();
            if (distanceToSqr(owner) <= 400) {   // it arrived (the chunks were ready and a spot was free)
                CreatureSfx.arrive(this, false);
                brain.far = false;
                play(species().anim("greet"));
            }
        }
    }

    /**
     * Never teleports into chunks that are not loaded yet: vanilla's canTeleportTo reads blocks with the loading
     * getBlockState, which parks the server thread while the owner's chunks generate (after /rtp, /home, pearls, fast
     * elytra). FollowOwnerGoal retries every 10 ticks, so the pet snaps over once the chunks are in.
     */
    @Override
    public void tryToTeleportToOwner() {
        LivingEntity owner = getOwner();
        if (owner == null || owner.level() != level()) return;
        BlockPos p = owner.blockPosition();
        // the candidate spots are the owner +-3 on x / z, and the path type check also reads their +-1 neighbours.
        // getChunkNow, not hasChunksAt: on the server hasChunk is already true while a ticketed chunk still generates
        for (int cx = (p.getX() - 4) >> 4; cx <= (p.getX() + 4) >> 4; cx++) {
            for (int cz = (p.getZ() - 4) >> 4; cz <= (p.getZ() + 4) >> 4; cz++) {
                if (level().getChunkSource().getChunkNow(cx, cz) == null) return;
            }
        }
        super.tryToTeleportToOwner();
    }

    @Override
    public boolean shouldTryTeleportToOwner() {
        LivingEntity owner = getOwner();
        return owner != null && distanceToSqr(owner) >= 400.0;
    }

    @Override
    protected boolean canFlyToOwner() {
        return species().flyer();
    }

    private void wildDespawn(ServerLevel level) {
        if (isTame() || species().eggOnly() || isPersistenceRequired() || isLeashed()) return;
        if (level.getNearestPlayer(this, 64) == null) discard();
    }

    @Override
    public boolean shouldBeSaved() {
        // ArcanaPet_WildUnload: wild ones are gone when their chunk reloads (egg-only creatures stay)
        return super.shouldBeSaved() && (isTame() || species().eggOnly() || isPersistenceRequired() || isLeashed());
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    public void checkDespawn() {
        // a wild hostile goes on Peaceful like a vanilla monster; a tamed one is a pet and stays (its type is allowed in
        // Peaceful so vanilla neither discards it here nor skips it when its chunk loads)
        if (level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL && !isTame() && species().hostile()) {
            discard();
            return;
        }
        super.checkDespawn();
    }

    /**
     * A name tag on a tamed creature goes through {@link #mobInteract} (the pets module renames through the record and
     * its rules; strangers can't rename it). Vanilla's {@code Mob.interact} applies name tags before mobInteract.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (isTame() && isAlive() && player.getItemInHand(hand).is(Items.NAME_TAG)) {
            InteractionResult r = mobInteract(player, hand);
            if (r.consumesAction()) gameEvent(net.minecraft.world.level.gameevent.GameEvent.ENTITY_INTERACT, player);
            return r;
        }
        return super.interact(player, hand, location);
    }

    /** Aquatic creatures (gen_mm.py "Aquatic creatures"): swim straight after the owner, hold depth otherwise. */
    private void swim() {
        Species s = species();
        JsonObject cfg = s.aquatic();
        if (cfg == null || !Conditions.inWater(this) || isVehicle() && this instanceof ArcanaMount) return;
        boolean followed = false;
        if (s.aquaticFollow && isTame() && !staying() && !busy) {
            ServerPlayer owner = ownerPlayer();
            if (owner != null && owner.level() == level()) {
                double d = distanceTo(owner);
                if (d > 2.5 && d < 24) {
                    double speed = valueOf(cfg.get("speed"));
                    Vec3 dir = owner.position().add(0, 0.2, 0).subtract(position()).normalize();
                    setDeltaMovement(dir.scale(speed));
                    getLookControl().setLookAt(owner);
                    followed = true;
                }
            }
        }
        if (!followed && s.aquaticHover && getNavigation().isDone()) {
            Vec3 v = getDeltaMovement();
            setDeltaMovement(v.x * 0.85, 0.005, v.z * 0.85);
        }
    }

    /** A spec number: plain or a bond macro object ({@code {base, step}} / {@code {base, every}}). */
    public double valueOf(com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) return 0;
        if (e.isJsonPrimitive()) return e.getAsDouble();
        JsonObject o = e.getAsJsonObject();
        if (o.has("step")) return bond(o.get("base").getAsDouble(), o.get("step").getAsDouble());
        if (o.has("every")) return bondLvl(o.get("base").getAsInt(), o.get("every").getAsInt());
        return 0;
    }

    @Override
    public boolean canBreatheUnderwater() {
        return species().aquatic() != null || CreatureUtil.damageMultiplier(species(), damageSources().drown()) == 0;
    }

    @Override
    public boolean isPushedByFluid() {
        return species().aquatic() == null;
    }

    /**
     * Vanilla's {@code Mob.checkSpawnObstruction} refuses any liquid in the box, so an aquatic species could never spawn
     * in water (ArcanaSpawner's natural spawns of every sea creature failed there). Water mobs only need room, like
     * vanilla's WaterAnimal.
     */
    @Override
    public boolean checkSpawnObstruction(net.minecraft.world.level.LevelReader level) {
        return species().aquatic() != null ? level.isUnobstructed(this) : super.checkSpawnObstruction(level);
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Species s = species();
        if (level().isClientSide()) {
            if (isTame() && isOwnedBy(player)) return InteractionResult.SUCCESS;
            if (!isTame() && isTameItem(stack)) return InteractionResult.SUCCESS;
            if (s.mount() && isVehicle()) return InteractionResult.SUCCESS;
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        InteractionResult hook = CreatureEvents.INTERACT.invoker().onInteract(this, sp, hand);
        if (hook != InteractionResult.PASS) return hook;
        if (isTame() && isOwnedBy(player)) return ownerInteract(sp, hand, stack);
        InteractionResult other = otherInteract(sp, hand, stack);
        if (other != InteractionResult.PASS) return other;
        if (!isTame() && !s.untameable() && isTameItem(stack)) return tryTame(sp, stack);
        if (isTame() && stack.is(Items.NAME_TAG)) return InteractionResult.FAIL;   // only the owner names it
        return InteractionResult.PASS;
    }

    /** Hook for a non-owner's click on a tamed creature (mount passengers). */
    protected InteractionResult otherInteract(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        return InteractionResult.PASS;
    }

    protected boolean isTameItem(ItemStack stack) {
        Species s = species();
        String custom = s.tameCustom();
        if (custom != null) return CreatureUtil.isCustom(stack, custom);
        return s.tameItem() != null && stack.is(s.tameItem());
    }

    private InteractionResult tryTame(ServerPlayer player, ItemStack stack) {
        if (!scheduler.ready("tame", 20)) return InteractionResult.SUCCESS;
        // a refusing listener (the pets module's cap) tells the player why; the item is kept
        if (!CreatureEvents.CAN_TAME.invoker().canTame(this, player)) return InteractionResult.SUCCESS;
        stack.consume(1, player);
        eatSound();
        Species s = species();
        if (random.nextDouble() < s.tameChance()) {
            tamedBy(player);
        } else {
            level().broadcastEntityEvent(this, (byte) 6);
            particles(ParticleTypes.SMOKE, 8, 0.4, 0.3, 0.8, 0.02);
            if (!CreatureSfx.play(this, "tame_fail")) sound("entity.generic.eat", 0.6f, 0.9f);
        }
        if (s.tameReturn() != null) {
            ItemStack back = new ItemStack(s.tameReturn());
            if (!player.getInventory().add(back)) player.getInventory().placeItemBackInInventory(back, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        return InteractionResult.SUCCESS;
    }

    /** ArcanaPet_TameSuccess + &lt;Id&gt;_Tamed. Also usable by the pets module (revive, admin tame). */
    public void tamedBy(ServerPlayer player) {
        tame(player);
        setOrderedToSit(false);
        setInSittingPose(false);
        setTarget(null);
        setDeltaMovement(Vec3.ZERO);
        busy = false;
        setHealth(getMaxHealth());
        level().broadcastEntityEvent(this, (byte) 7);
        particles(ParticleTypes.HEART, 8, 0.5, 0.4, 0.8, 0.02);
        if (!CreatureSfx.tame(this)) sound("entity.player.levelup", 0.7f, 1.6f);
        makeHappy();
        String clip = species().anim("tame");
        play(clip != null ? clip : species().anim("happy"));
        setPersistenceRequired();
        boolean told = CreatureEvents.TAMED.invoker().onTamed(this, player);
        // the fallback line only when no listener announced it (no pets module, so no /pets hint either)
        if (!told) CreatureUtil.say(player, false, "tamed", this, "&a<pet> &abonds with you. &7Shift + right-click to make it stay or follow.");
    }

    protected InteractionResult ownerInteract(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        Species s = species();
        if (player.isShiftKeyDown()) {
            if (scheduler.ready("stay", 20)) setStaying(!staying(), player);
            return InteractionResult.SUCCESS;
        }
        if (s.healItem() != null && stack.is(s.healItem())) {
            if (scheduler.ready("feed", 20)) feed(player, stack);
            return InteractionResult.SUCCESS;
        }
        if (stack.is(Items.NAME_TAG)) {
            var name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
            if (name == null) return InteractionResult.PASS;
            if (scheduler.ready("rename", 20)) {
                setCustomName(name);
                setCustomNameVisible(true);
                stack.consume(1, player);
                particles(ParticleTypes.HAPPY_VILLAGER, 6, 0.4, 0.4, 0.8, 0.02);
                CreatureUtil.say(player, true, "named", "&7Named it &f<name>&7.", net.axther.aftrhrs.core.Text.unparsed("name", name.getString()));
                if (!staying()) playRole("happy");
            }
            return InteractionResult.SUCCESS;
        }
        InteractionResult special = ownerUse(player, hand, stack);
        if (special != InteractionResult.PASS) return special;
        if (stack.isEmpty()) {
            if (scheduler.ready("pet", 40)) petBy(player);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** Hook for the owner's click with anything else (mounts ride here). */
    protected InteractionResult ownerUse(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        return InteractionResult.PASS;
    }

    private void feed(ServerPlayer player, ItemStack stack) {
        ItemStack fed = stack.copyWithCount(1);
        stack.consume(1, player);
        heal(species().healAmount());
        makeHappy();
        particles(ParticleTypes.HEART, 3, 0.4, 0.3, 0.8, 0.02);
        eatSound();
        scheduler.after(16, () -> CreatureSfx.play(this, "heal"));
        if (!staying()) {
            String eat = species().anim("eat");
            play(eat != null ? eat : species().anim("happy"));
        }
        CreatureEvents.FED.invoker().onFed(this, player, fed);
    }

    /** An empty-handed right-click from the owner: a heart and a happy hop (Luna / Kevin add their reactions). */
    public void petBy(ServerPlayer player) {
        particles(ParticleTypes.HEART, 1, 0.2, 0.2, 0.9, 0.02);
        CreatureSfx.pet(this);
        makeHappy();
        String happy = species().anim("happy");
        // a species' own petting clips when it has them: "pet" standing; while staying a sitting loop over the held sit
        // pose ("sit_pet" held as a rest pose, or "pet_sit" as an overlay; both names are in use), else the happy hop
        String sitLoop = species().has("sit_pet") ? "sit_pet" : species().has("pet_sit") ? "pet_sit" : null;
        if (staying()) {
            if (sitLoop != null && isInSittingPose() && !napping) {
                if (sitLoop.equals("sit_pet")) restPose("sit_pet", Math.max(40, species().clipTicks("sit_pet", 40)));
                else play("pet_sit");
            }
        } else if (species().has("pet")) {
            play("pet");
        } else if (happy != null) {
            play(happy);
        } else {
            particles(ParticleTypes.HAPPY_VILLAGER, 3, 0.3, 0.3, 0.6, 0.02);
        }
        Abilities.onInteract(this, player);
    }

    private void eatSound() {
        if (CreatureSfx.play(this, "eat")) return;
        Species.Sound eat = species().sound("eat");
        if (eat != null) sound(eat);
        else sound("entity.generic.eat", 0.8f, 1.2f);
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return false;   // no vanilla breeding: Feather breeds pets
    }

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    public boolean canMate(net.minecraft.world.entity.animal.Animal other) {
        return false;
    }

    // ------------------------------------------------------------------ combat, damage, death

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        float mult = CreatureUtil.damageMultiplier(species(), source);
        if (mult <= 0) {
            if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) clearFire();
            return false;
        }
        boolean hurt = super.hurtServer(level, source, amount * mult);
        if (hurt && isAlive()) {
            play(species().anim("hurt"));
            if (species().has("startle") && random.nextFloat() < 0.3f) play("startle");
            if (busy) free();
            Abilities.onDamaged(this, source);
        }
        return hurt;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource source) {
        if (species().flyer() || CreatureUtil.damageMultiplier(species(), source) <= 0) return false;
        return super.causeFallDamage(fallDistance, damageModifier, source);
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit) {
            play(species().anim("attack"));
            if (!CreatureSfx.play(this, "attack")) sound(species().sound("attack"));
            Abilities.onAttack(this, target);
            if (!target.isAlive() && species().has("celebrate") && scheduler.ready("celebrate", 60)) {
                scheduler.after(6, () -> play("celebrate"));
            }
        }
        return hit;
    }

    @Override
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
        if (target instanceof TamableAnimal t && t.isTame() && t.getOwner() == owner) return false;
        return !(target instanceof Player p && owner instanceof Player o && !o.canHarmPlayer(p));
    }

    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel) {
            CreatureSfx.layer(this, "death");   // vanilla plays the main death sound (getDeathSound)
            Abilities.onDeath(this);
            CreatureEvents.DIED.invoker().onDied(this, source);
        }
        super.die(source);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        CreatureUtil.dropLoot(this, species(), level);
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        return CreatureUtil.dropExp(this, species());
    }

    @Override
    public void remove(RemovalReason reason) {
        if (level() instanceof ServerLevel && (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION)) Abilities.onRemove(this);   // cleanup (light blocks) in the level it leaves
        super.remove(reason);
    }

    // sounds come from the species' sound profile (CreatureSfx): vanilla's ambient timer, hurt, death and step hooks
    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return CreatureSfx.event(this, CreatureSfx.ambientEvent(this));
    }

    @Override
    public void playAmbientSound() {
        CreatureSfx.ambient(this);
    }

    @Override
    public int getAmbientSoundInterval() {
        return CreatureSfx.ambientInterval(this);
    }

    @Override
    protected @Nullable SoundEvent getHurtSound(DamageSource source) {
        return CreatureSfx.event(this, "hurt");
    }

    @Override
    protected void playHurtSound(DamageSource source) {
        super.playHurtSound(source);
        CreatureSfx.layer(this, "hurt");
    }

    @Override
    protected @Nullable SoundEvent getDeathSound() {
        return CreatureSfx.event(this, "death");
    }

    @Override
    public float getVoicePitch() {
        return CreatureSfx.voicePitch(this);
    }

    @Override
    protected void playStepSound(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (!CreatureSfx.step(this)) super.playStepSound(pos, state);
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("ArcanaBond", bond());
        if (variant != null) out.putString("ArcanaVariant", variant);
        out.putBoolean("ArcanaShiny", shiny());
        out.putInt("ArcanaTint", tint());
        if (!vars.isEmpty()) out.store("ArcanaVars", VARS_CODEC, Map.copyOf(vars));
        Abilities.save(this, out);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        setBond(in.getIntOr("ArcanaBond", 0));
        variant = in.getString("ArcanaVariant").orElse(null);
        setShiny(in.getBooleanOr("ArcanaShiny", false));
        setTint(in.getIntOr("ArcanaTint", 0xFFFFFF));
        vars.clear();
        in.read("ArcanaVars", VARS_CODEC).ifPresent(vars::putAll);
        Species s = species();
        setIdleClip(s.baseIdle());
        setWalkClip(s.baseWalk());
        // StayRestore: a staying pet comes back seated (postureTick); the nap timer lies it down again at night
        brainHold = "";
        Abilities.load(this, in);
    }
}
