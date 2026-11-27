package net.axther.aftrhrs.entity;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.axther.aftrhrs.entity.ability.Fx;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Hostile Arcana mobs that are never tamed: the biome undead (zombie / skeleton bases with their idle antics), the
 * bosses (boss bar, spawn clip, attack clips; their attack skills are a later port) and the police officer (chases only
 * whoever Feather points it at: {@link #setTarget}).
 */
public class ArcanaMonster extends Monster implements ArcanaMob, RangedAttackMob {
    private static final EntityDataAccessor<String> DATA_IDLE = SynchedEntityData.defineId(ArcanaMonster.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_WALK = SynchedEntityData.defineId(ArcanaMonster.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_HOLD = SynchedEntityData.defineId(ArcanaMonster.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_HIDDEN = SynchedEntityData.defineId(ArcanaMonster.class, EntityDataSerializers.STRING);
    /** ModelEngine tint (0xRRGGBB): the species' spawn tint (Wukong's hair clones), Wukong's stone grey when he falls. */
    private static final EntityDataAccessor<Integer> DATA_TINT = SynchedEntityData.defineId(ArcanaMonster.class, EntityDataSerializers.INT);
    /** Spider variants: climbing a wall (vanilla spider flag; synced so clients predict the climb). */
    private static final EntityDataAccessor<Boolean> DATA_CLIMBING = SynchedEntityData.defineId(ArcanaMonster.class, EntityDataSerializers.BOOLEAN);

    private @Nullable Species species;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Scheduler scheduler = new Scheduler();
    private final CreatureSfx.State sfxState = new CreatureSfx.State();
    /** The client animation driver ({@link CreatureAnim#register}); a plain field: read every frame by the renderer. */
    private CreatureAnim.@Nullable Driver animDriver;
    /** {@link BossBrain}'s state, made on first use (only bosses and the police ever ask). */
    private BossBrain.@Nullable State bossState;
    private final Map<String, Integer> vars = new java.util.HashMap<>();
    private @Nullable ServerBossEvent bossBar;
    private boolean busy;
    /** Until this tick an attack clip is playing: the hurt clip doesn't cut it off. */
    private int actionUntil;
    /** The undead's idle director (idle / notice / walk pools); null for bosses and the police. */
    private @Nullable WildBrain wild;
    /** The attack clip started by {@link #action} and a counter of started attacks (a delayed melee hit checks it). */
    private String actionClip = "";
    private int actionToken;
    /** Drowned variants: swimming up to find land (vanilla Drowned.searchingForLand). */
    private boolean searchingForLand;

    public ArcanaMonster(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        if (species().flyer()) this.moveControl = new FlyingMoveControl<>(this, 20, true);
        if (drowned()) {
            this.moveControl = new VariantGoals.SwimMoveControl(this);
            setPathfindingMalus(PathType.WATER, 0.0F);
        }
        entityData.set(DATA_TINT, (int) Species.dbl(species().json, "tint", 0xFFFFFF));
        JsonObject bar = species().section("boss_bar");
        if (bar != null) {
            bossBar = new ServerBossEvent(getUUID(), Component.literal(Species.str(bar, "title", species().name)),
                    color(Species.str(bar, "color", "PURPLE")), overlay(Species.str(bar, "style", "SOLID")));
        }
    }

    @Override
    public Species species() {
        if (species == null) species = ArcanaEntities.species(getType());
        return species;
    }

    /**
     * The vanilla mob this undead variant stands in for ({@code base} in the spec: ZOMBIE, SKELETON, SPIDER, DROWNED,
     * HUSK); spiders, drowned and husks get that mob's behavior (climbing and neutral in the light; swimming; hunger).
     */
    public String base() {
        return species().base;
    }

    BossBrain.State bossState() {
        BossBrain.State st = bossState;
        if (st == null) bossState = st = new BossBrain.State();
        return st;
    }

    public boolean spider() {
        return species().kind == Species.Kind.UNDEAD && base().equals("SPIDER");
    }

    public boolean drowned() {
        return species().kind == Species.Kind.UNDEAD && base().equals("DROWNED");
    }

    public boolean husk() {
        return species().kind == Species.Kind.UNDEAD && base().equals("HUSK");
    }

    public boolean bow() {
        JsonElement eq = species().json.get("equipment");
        return eq != null && eq.toString().contains("BOW");
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        if (species().flyer()) {
            FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
            nav.setCanFloat(true);
            return nav;
        }
        if (spider()) return new WallClimberNavigation(this, level);
        if (drowned()) return new AmphibiousPathNavigation(this, level);
        return super.createNavigation(level);
    }

    @Override
    protected void registerGoals() {
        Species s = species();
        List<String> goals = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        JsonObject ai = s.section("ai");
        if (ai != null) {
            ai.getAsJsonArray("goals").forEach(e -> goals.add(e.getAsString().toLowerCase(Locale.ROOT)));
            ai.getAsJsonArray("targets").forEach(e -> targets.add(e.getAsString().toLowerCase(Locale.ROOT)));
        }
        if (s.kind == Species.Kind.UNDEAD && ai == null && (spider() || drowned())) {
            variantGoals();
            return;
        }
        goalSelector.addGoal(0, new FloatGoal(this));
        if (bow()) {
            goalSelector.addGoal(2, new RangedBowAttackGoal<>(this, 1.0, 20, 15.0F));
        } else {
            double speed = 1.0;
            for (String g : goals) if (g.startsWith("meleeattack{s=")) speed = Double.parseDouble(g.replaceAll("[^0-9.]", ""));
            goalSelector.addGoal(2, new MeleeAttackGoal(this, speed, s.kind != Species.Kind.UNDEAD));
        }
        if (s.kind != Species.Kind.POLICE) {
            goalSelector.addGoal(5, s.flyer() ? new WaterAvoidingRandomFlyingGoal(this, 1.0) : new WaterAvoidingRandomStrollGoal(this, 1.0) {
                @Override
                public boolean canUse() {
                    return (wild() == null || !wild().lingering()) && super.canUse();   // waits for a still-only undead idle
                }
            });
        }
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        if (s.kind == Species.Kind.POLICE) return;   // never picks a target by itself (Feather points it at robbers)
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        if (s.kind == Species.Kind.UNDEAD && ai == null) targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    /** Undead variant goals: the vanilla spider's (Spider.registerGoals) or the drowned's (Drowned.addBehaviourGoals). */
    private void variantGoals() {
        if (spider()) {
            goalSelector.addGoal(1, new FloatGoal(this));
            goalSelector.addGoal(2, new AvoidEntityGoal<>(this, Armadillo.class, 6.0F, 1.0, 1.2, e -> !((Armadillo) e).isScared()));
            goalSelector.addGoal(3, new LeapAtTargetGoal(this, 0.4F));
            goalSelector.addGoal(4, new VariantGoals.SpiderAttack(this));
            goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8) {
                @Override
                public boolean canUse() {
                    return (wild() == null || !wild().lingering()) && super.canUse();
                }
            });
            goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
            goalSelector.addGoal(6, new RandomLookAroundGoal(this));
            targetSelector.addGoal(1, new HurtByTargetGoal(this));
            targetSelector.addGoal(2, new VariantGoals.SpiderTarget<>(this, Player.class));
            targetSelector.addGoal(3, new VariantGoals.SpiderTarget<>(this, IronGolem.class));
            return;
        }
        goalSelector.addGoal(1, new VariantGoals.GoToWater(this));
        goalSelector.addGoal(2, new VariantGoals.DrownedAttack(this));
        goalSelector.addGoal(5, new VariantGoals.GoToBeach(this));
        goalSelector.addGoal(6, new VariantGoals.SwimUp(this));
        goalSelector.addGoal(7, new RandomStrollGoal(this, 1.0) {
            @Override
            public boolean canUse() {
                return (wild() == null || !wild().lingering()) && super.canUse();
            }
        });
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, (t, l) -> okDrownedTarget(t)));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_IDLE, "");
        builder.define(DATA_WALK, "");
        builder.define(DATA_HOLD, "");
        builder.define(DATA_HIDDEN, "");
        builder.define(DATA_TINT, 0xFFFFFF);
        builder.define(DATA_CLIMBING, false);
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                                  @Nullable SpawnGroupData data) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data);
        setIdleClip(species().baseIdle());
        setWalkClip(species().baseWalk());
        if (bow()) setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        // vanilla spider: on Hard, a 10 % x regional difficulty chance of a permanent speed / strength / regen / invisibility
        if (spider() && level.getDifficulty() == net.minecraft.world.Difficulty.HARD && random.nextFloat() < 0.1F * difficulty.getSpecialMultiplier()) {
            int pick = random.nextInt(5);
            var effect = pick <= 1 ? MobEffects.SPEED : pick == 2 ? MobEffects.STRENGTH : pick == 3 ? MobEffects.REGENERATION : MobEffects.INVISIBILITY;
            addEffect(new MobEffectInstance(effect, -1));
        }
        String spawn = species().anim("spawn");
        if (spawn != null) play(spawn);
        Abilities.onSpawn(this);
        return out;
    }

    @Override
    protected void populateDefaultEquipmentSlots(net.minecraft.util.RandomSource random, DifficultyInstance difficulty) {
        // PreventRandomEquipment
    }

    // ------------------------------------------------------------------ API

    public boolean busy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        if (busy) getNavigation().stop();
    }

    public void free() {
        busy = false;
        setIdleClip(species().baseIdle());
        setWalkClip(species().baseWalk());
        hold("");
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

    public int var(String key) {
        return vars.getOrDefault(key, 0);
    }

    public void setVar(String key, int value) {
        if (value == 0) vars.remove(key);
        else vars.put(key, value);
    }

    public @Nullable ServerBossEvent bossBar() {
        return bossBar;
    }

    public void setBoneVisible(String bone, boolean visible) {
        List<String> hidden = new ArrayList<>(List.of(hiddenBones().isEmpty() ? new String[0] : hiddenBones().split(",")));
        hidden.remove(bone);
        if (!visible) hidden.add(bone);
        entityData.set(DATA_HIDDEN, String.join(",", hidden));
    }

    @Override
    public String hiddenBones() {
        return entityData.get(DATA_HIDDEN);
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
    public int tint() {
        return entityData.get(DATA_TINT);
    }

    /** ModelEngine {@code tint{c=RRGGBB}}: multiplies the texture (0xFFFFFF = normal). Saved. */
    public void setTint(int rgb) {
        entityData.set(DATA_TINT, rgb & 0xFFFFFF);
    }

    /** The undead's idle director (lazily: registerGoals runs before the fields are set). */
    private @Nullable WildBrain wild() {
        if (wild == null && species().kind == Species.Kind.UNDEAD) wild = new WildBrain(this);
        return wild;
    }

    @Override
    public void onPlayed(String clip) {
        WildBrain w = wild();
        if (w != null) w.onPlayed(clip);
    }

    /** Plays an attack clip and keeps the hurt clip from cutting it off while it runs. */
    public void action(@Nullable String clip) {
        if (clip == null || !species().has(clip)) return;
        play(clip);
        actionClip = clip;
        actionToken++;
        actionUntil = tickCount + species().clipTicks(clip, 20);
    }

    /** True while an attack clip started with {@link #action} is still playing. */
    public boolean inAction() {
        return tickCount < actionUntil;
    }

    /** The attack clip playing now ("" if none). */
    public String actionClip() {
        return inAction() ? actionClip : "";
    }

    /** Ticks left of the attack clip playing now. */
    public int actionLeft() {
        return Math.max(0, actionUntil - tickCount);
    }

    /** A hit landed: the hurt flinch, never over an attack clip (bosses and police: {@link BossBrain#flinch}). */
    public void flinch(float amount) {
        if (inAction()) return;
        if (BossBrain.drives(this)) BossBrain.flinch(this, amount);
        else play(species().anim("hurt"));
    }

    /** Mobs of the same faction (Wukong and his clones, police) never target each other (modules.boss-fx). */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && net.axther.aftrhrs.feature.bossfx.Allies.allied(this, target)) return;
        super.setTarget(target);
    }

    @Override
    public boolean shiny() {
        return false;
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
        return busy || super.isImmobile();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || !isAlive()) return;
        scheduler.tick();
        int t = tickCount + getId();
        Species s = species();
        JsonObject life = s.life;
        if (life != null && !busy && getTarget() == null) {
            int every = s.lifeEvery;
            if (every > 0 && t % every == 0 && random.nextDouble() < s.lifeChance(0.3)) life(life);
        }
        CreatureSfx.tick(this);   // ambient / step sounds: vanilla's timers through the overrides below
        if (spider()) entityData.set(DATA_CLIMBING, horizontalCollision);
        if (t % 20 == 0) snipRegrow();
        WildBrain w = wild();
        if (t % 20 == 0 && !busy && !BossBrain.drives(this)) {
            boolean rage = s.has("idle_rage") && getHealth() < getMaxHealth() * 0.5f;
            String idle = rage ? "idle_rage" : s.baseIdle();
            String walk = rage && s.has("walk_rage") ? "walk_rage" : getTarget() != null && s.has("run") ? "run" : s.baseWalk();
            if (drowned() && isUnderWater()) {
                // under water a drowned variant treads (still) and swims (moving) instead of its land idle / walk
                setIdleClip(s.has("tread") ? "tread" : idle);
                setWalkClip(s.has("swim") ? "swim" : walk);
            } else {
                setIdleClip(w != null ? w.idle(idle) : idle);
                setWalkClip(w != null ? w.walk(walk) : walk);
            }
        }
        if (w != null) {
            if (t % 20 == 0) w.second();
            w.tick();
        }
        if (bossBar != null) {
            bossBar.setProgress(getHealth() / getMaxHealth());
            if (t % 20 == 0) bossBarPlayers(level);
        }
        if (BossBrain.drives(this)) BossBrain.tick(this);   // bosses / police: idle pools, stances, phases
        Abilities.tick(this);
    }

    /** Undead idle antics (rattle, drip, pray, swing_pick ...): spec life table with hold counts and extra effects. */
    private void life(JsonObject life) {
        JsonObject table = life.getAsJsonObject("day");
        if (table == null || table.isEmpty()) return;
        double total = 0;
        for (var e : table.entrySet()) total += e.getValue().getAsDouble();
        double r = random.nextDouble() * total;
        String act = null;
        for (var e : table.entrySet()) {
            r -= e.getValue().getAsDouble();
            if (r <= 0) {
                act = e.getKey();
                break;
            }
        }
        if (act == null) return;
        final String clip = act;
        var extra = life.has("extra") && life.getAsJsonObject("extra").has(act) ? life.getAsJsonObject("extra").getAsJsonArray(act) : null;
        int times = life.has("hold") && life.getAsJsonObject("hold").has(act) ? life.getAsJsonObject("hold").get(act).getAsInt() : 1;
        boolean aiToggle = !species().json.has("ai_toggle") || species().json.get("ai_toggle").getAsBoolean();
        if (act.equals("look")) {
            Player p = level().getNearestPlayer(this, 10);
            if (p != null) getLookControl().setLookAt(p, 12, 12);
            return;
        }
        int len = Math.max(10, species().clipTicks(act, 40));
        if (times > 1 && aiToggle) setBusy(true);
        for (int i = 0; i < times; i++) {
            scheduler.after(i * len, () -> {
                play(clip);
                if (extra != null) Fx.lines(this, extra);
            });
        }
        if (times > 1 && aiToggle) scheduler.after(times * len, () -> busy = false);
    }

    private void bossBarPlayers(ServerLevel level) {
        double range = Species.dbl(species().section("boss_bar"), "range", 64);
        // players who left this level (portal, death screen, logout) drop off the bar; level.players() never lists them
        for (ServerPlayer p : java.util.List.copyOf(bossBar.getPlayers())) {
            if (p.level() != level || p.isRemoved() || p.hasDisconnected()) bossBar.removePlayer(p);
        }
        for (ServerPlayer p : level.players()) {
            boolean in = p.distanceToSqr(this) < range * range;
            if (in && !bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
            else if (!in && bossBar.getPlayers().contains(p)) bossBar.removePlayer(p);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        if (bossBar != null) bossBar.removePlayer(player);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (bossBar != null) bossBar.removeAllPlayers();
        if (level() instanceof ServerLevel && reason.shouldDestroy()) Abilities.onRemove(this);
        super.remove(reason);
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        if (bossBar != null && name != null) bossBar.setName(name);
    }

    /** A timed summon (a line {@code remove{delay=..} @self ~onSpawn}: Wukong's hair clones) lives only as long as that timer. */
    private @Nullable Boolean summoned;

    @Override
    public boolean shouldBeSaved() {
        if (summoned == null) {
            summoned = false;
            for (var o : species().abilities) {
                String l = Species.str(o, "line", "");
                if (l != null && l.contains("remove{") && l.contains("@self") && l.contains("~onSpawn")) summoned = true;
            }
        }
        // the scheduler (and with it the removal timer) is never saved: a saved clone would outlive its boss forever
        return !summoned && super.shouldBeSaved();
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        String despawn = species().despawn;
        return species().kind == Species.Kind.UNDEAD && "true".equals(despawn) || species().kind == Species.Kind.POLICE;
    }

    // ------------------------------------------------------------------ spider / drowned variants

    @Override
    public boolean onClimbable() {
        return spider() ? entityData.get(DATA_CLIMBING) : super.onClimbable();
    }

    @Override
    public void makeStuckInBlock(net.minecraft.world.level.block.state.BlockState state, Vec3 speed) {
        if (spider() && state.is(Blocks.COBWEB)) return;   // spiders walk through cobwebs
        super.makeStuckInBlock(state, speed);
    }

    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return !(spider() && effect.is(MobEffects.POISON)) && super.canBeAffected(effect);
    }

    /** Drowned variants spawn in water: vanilla's liquid check would turn every one of them away. */
    @Override
    public boolean checkSpawnObstruction(net.minecraft.world.level.LevelReader level) {
        return drowned() ? level.isUnobstructed(this) : super.checkSpawnObstruction(level);
    }

    /** Drowned: attacks only while it is dark outside or the target is in the water (Drowned.okTarget). */
    public boolean okDrownedTarget(@Nullable LivingEntity target) {
        return target != null && (!level().isBrightOutside() || target.isInWater());
    }

    public boolean searchingForLand() {
        return searchingForLand;
    }

    public void setSearchingForLand(boolean searching) {
        this.searchingForLand = searching;
    }

    /** Drowned: swims (3D steering) while searching for land or chasing a target that is in the water. */
    public boolean wantsToSwim() {
        if (searchingForLand) return true;
        LivingEntity target = getTarget();
        return target != null && target.isInWater();
    }

    boolean closeToNextPos() {
        var path = getNavigation().getPath();
        BlockPos at = path == null ? null : path.getTarget();
        return at != null && distanceToSqr(at.getX(), at.getY(), at.getZ()) < 4.0;
    }

    @Override
    public boolean isPushedByFluid() {
        return drowned() ? !isSwimming() : super.isPushedByFluid();
    }

    @Override
    protected void travelInWater(Vec3 input, double baseGravity, boolean isFalling, double oldY) {
        if (drowned() && isUnderWater() && wantsToSwim()) {
            moveRelative(0.01F, input);
            move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
            setDeltaMovement(getDeltaMovement().scale(0.9));
        } else {
            super.travelInWater(input, baseGravity, isFalling, oldY);
        }
    }

    @Override
    public void updateSwimming() {
        if (!drowned()) {
            super.updateSwimming();
            return;
        }
        if (!level().isClientSide()) setSwimming(isEffectiveAi() && isUnderWater() && wantsToSwim());
    }

    // ------------------------------------------------------------------ snip (a non-lethal harvest)

    /**
     * Spec {@code snip}: {@code {item, min, max, extra, bone, regrow_minutes}}: shears snip the item off a calm variant
     * (not hunting anyone: the Gloomweaver's silk bundle, by day); the bone hides until it regrows. It is not angered.
     */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        JsonObject snip = species().section("snip");
        ItemStack held = player.getItemInHand(hand);
        if (snip == null || !held.is(Items.SHEARS)) return super.mobInteract(player, hand);
        if (!(level() instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) return net.minecraft.world.InteractionResult.SUCCESS;
        if (!snippable()) return net.minecraft.world.InteractionResult.PASS;
        int lo = (int) Species.dbl(snip, "min", 1), hi = Math.max(lo, (int) Species.dbl(snip, "max", 1));
        net.minecraft.world.item.Item item = CreatureUtil.customItem(Species.str(snip, "item", ""));
        if (item != null) spawnAtLocation(level, new ItemStack(item, lo + random.nextInt(hi - lo + 1)), 1.0F);
        String extra = Species.str(snip, "extra", null);
        if (extra != null) {
            var vanilla = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(extra.toLowerCase(Locale.ROOT)));
            spawnAtLocation(level, new ItemStack(vanilla, 1 + random.nextInt(2)), 1.0F);
        }
        held.hurtAndBreak(1, player, hand == net.minecraft.world.InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        String bone = Species.str(snip, "bone", null);
        if (bone != null) setBoneVisible(bone, false);
        setVar("snipped", (int) (Species.dbl(snip, "regrow_minutes", 10) * 60));
        if (species().has("snipped")) play("snipped");
        if (!CreatureSfx.play(this, "ability")) playSound(net.minecraft.sounds.SoundEvents.SHEEP_SHEAR, 1.0F, 1.0F);
        CreatureEvents.HARVESTED.invoker().onHarvested(this, sp, "snip");
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    /** True while the snip harvest is ready: alive, calm (no target) and grown back. */
    public boolean snippable() {
        return species().section("snip") != null && isAlive() && getTarget() == null && var("snipped") <= 0;
    }

    /** Once a second: the snipped part grows back when its timer runs out (saved with the vars). */
    private void snipRegrow() {
        int left = var("snipped");
        if (left <= 0) return;
        setVar("snipped", left - 1);
        if (left - 1 > 0) return;
        JsonObject snip = species().section("snip");
        String bone = snip == null ? null : Species.str(snip, "bone", null);
        if (bone != null) setBoneVisible(bone, true);
        if (species().has("spin")) play("spin");
    }

    // ------------------------------------------------------------------ combat

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        ItemStack bow = getItemInHand(ProjectileUtil.getWeaponHoldingHand(this, Items.BOW));
        ItemStack projectile = getProjectile(bow);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, projectile, power, bow);
        double xd = target.getX() - getX(), yd = target.getY(0.3333333333333333) - arrow.getY(), zd = target.getZ() - getZ();
        double dist = Math.sqrt(xd * xd + zd * zd);
        if (level() instanceof ServerLevel sl) {
            Projectile.spawnProjectileUsingShoot(arrow, sl, projectile, xd, yd + dist * 0.2F, zd, 1.6F, 14 - sl.getDifficulty().getId() * 4);
        }
        play(species().anim("attack"));
        if (!CreatureSfx.play(this, "attack")) sound(species().sound("attack"));
    }

    @Override
    public boolean canUseNonMeleeWeapon(ItemStack item) {
        return item.getItem() == Items.BOW;
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        if (Abilities.replacesMelee(species())) {
            // MythicMobs cancelevent: the ~onAttack skill (Wukong's staff, a clone's strike) deals the hit instead
            Abilities.onAttack(this, target);
            return true;
        }
        int hitAt = BossBrain.meleeHit(this);
        if (hitAt > 0) {
            // the attack clip's wind-up plays first; the damage lands on its hit frame if the target is still in reach
            if (inAction() || busy) return false;
            action(species().anim("attack"));
            int token = actionToken;
            scheduler.after(hitAt, () -> {
                if (!isAlive() || actionToken != token || !target.isAlive() || target.level() != level()) return;
                if (!getAttackBoundingBox(2.0).intersects(target.getBoundingBox())) return;   // dodged
                if (super.doHurtTarget(level, target)) {
                    if (!CreatureSfx.play(this, "attack")) sound(species().sound("attack"));
                    Abilities.onAttack(this, target);
                }
            });
            return true;
        }
        boolean hit = super.doHurtTarget(level, target);
        if (hit && husk() && getMainHandItem().isEmpty() && target instanceof LivingEntity living) {
            // vanilla husk: Hunger for 7 s x the regional difficulty
            float difficulty = level.getCurrentDifficultyAt(blockPosition()).getEffectiveDifficulty();
            living.addEffect(new MobEffectInstance(MobEffects.HUNGER, 140 * (int) difficulty), this);
        }
        if (hit) {
            play(species().anim("attack"));
            if (!bow() && !CreatureSfx.play(this, "attack")) sound(species().sound("attack"));
            Abilities.onAttack(this, target);
        }
        return hit;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        float mult = CreatureUtil.damageMultiplier(species(), source);
        if (mult <= 0) {
            if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) clearFire();
            return false;
        }
        // boss set pieces: "untouchable" shrugs every hit off (except /kill), "vulnerable" = extra damage taken in percent
        if (var("untouchable") != 0 && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            Abilities.onDamaged(this, source);
            return false;
        }
        if (var("vulnerable") != 0) mult *= 1 + var("vulnerable") / 100f;
        float dealt = amount * mult;
        // "floor" (per mille of max health): a boss that must reach a scripted finale can't be killed before it
        if (var("floor") > 0 && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            float min = getMaxHealth() * var("floor") / 1000f;
            if (getHealth() - dealt < min) dealt = Math.max(0, getHealth() - min);
        }
        boolean hurt = super.hurtServer(level, source, dealt);
        if (hurt && isAlive()) {
            if (wild() != null) wild().interrupt();
            flinch(amount * mult);
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
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel) {
            if (bossBar != null) bossBar.setProgress(0);   // tick() stops updating it while the death clip plays
            CreatureSfx.layer(this, "death");   // vanilla plays the main death sound (getDeathSound)
            Abilities.onDeath(this);
            CreatureEvents.DIED.invoker().onDied(this, source);
        }
        super.die(source);
    }

    /** Bosses and the police stay for their whole death clip ({@link BossBrain#deathTicks}); vanilla removes at 20. */
    @Override
    protected void tickDeath() {
        int hold = BossBrain.deathTicks(this);
        if (hold <= 20) {
            super.tickDeath();
            return;
        }
        deathTime++;
        BossBrain.dying(this, deathTime);
        if (deathTime >= hold && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
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

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putString("ArcanaHidden", hiddenBones());
        out.putInt("ArcanaTint", tint());
        if (!vars.isEmpty()) out.store("ArcanaVars", ArcanaCreature.VARS_CODEC, Map.copyOf(vars));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        entityData.set(DATA_HIDDEN, in.getStringOr("ArcanaHidden", ""));
        entityData.set(DATA_TINT, in.getIntOr("ArcanaTint", (int) Species.dbl(species().json, "tint", 0xFFFFFF)));
        vars.clear();
        in.read("ArcanaVars", ArcanaCreature.VARS_CODEC).ifPresent(vars::putAll);
        if (bossBar != null && hasCustomName()) bossBar.setName(getCustomName());
        setIdleClip(species().baseIdle());
        setWalkClip(species().baseWalk());
        Abilities.onLoad(this);
    }

    private static BossEvent.BossBarColor color(String c) {
        try {
            return BossEvent.BossBarColor.valueOf(c.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossEvent.BossBarColor.PURPLE;
        }
    }

    private static BossEvent.BossBarOverlay overlay(String s) {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "SEGMENTED_6" -> BossEvent.BossBarOverlay.NOTCHED_6;
            case "SEGMENTED_10" -> BossEvent.BossBarOverlay.NOTCHED_10;
            case "SEGMENTED_12" -> BossEvent.BossBarOverlay.NOTCHED_12;
            case "SEGMENTED_20" -> BossEvent.BossBarOverlay.NOTCHED_20;
            default -> BossEvent.BossBarOverlay.PROGRESS;
        };
    }
}
