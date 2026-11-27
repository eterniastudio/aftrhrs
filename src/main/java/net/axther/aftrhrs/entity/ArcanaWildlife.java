package net.axther.aftrhrs.entity;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.axther.aftrhrs.entity.ability.Fx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingLookControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Wildlife and livestock (+ their young) from tools/wild_specs (gen_wild.py): idle life (graze, lie down, look about,
 * species antics), gait swaps (run when spooked, charge in combat, swim), herds on natural spawns, the young follow
 * the grown-ups and grow up after 20 loaded minutes. Untameable; Feather does harvests and breeding through
 * {@link #hook(String)} and {@link #setKept(boolean)}. Unkept wild ones despawn like the MythicMobs version.
 */
public class ArcanaWildlife extends Animal implements ArcanaMob {
    private static final EntityDataAccessor<String> DATA_IDLE = SynchedEntityData.defineId(ArcanaWildlife.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_WALK = SynchedEntityData.defineId(ArcanaWildlife.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_HOLD = SynchedEntityData.defineId(ArcanaWildlife.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_HIDDEN = SynchedEntityData.defineId(ArcanaWildlife.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_TINT = SynchedEntityData.defineId(ArcanaWildlife.class, EntityDataSerializers.INT);

    private @Nullable Species species;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Scheduler scheduler = new Scheduler();
    private final CreatureSfx.State sfxState = new CreatureSfx.State();
    /** The client animation driver ({@link CreatureAnim#register}); a plain field: read every frame by the renderer. */
    private CreatureAnim.@Nullable Driver animDriver;
    private final Map<String, Integer> vars = new java.util.HashMap<>();
    private boolean busy, lying, kept, grown;
    private int ageMinutes;
    private long spookedUntil;
    /** The lying clip of the current rest (life lie_clip) and until when it is getting up (stand_up plays). */
    private String lieClip = "lie";
    private long standUntil;
    /** Idle director: idle / lying / notice / walk pools (anim.idle). Null only during super construction. */
    private final WildBrain brain = new WildBrain(this);

    public ArcanaWildlife(EntityType<? extends Animal> type, Level level) {
        super(type, level);
        // a Duskflies swarm hovers where its drift ends instead of sinking to the ground between flights
        // floaters (spec options.NoGravity, the Voidpuff) hover in place between drifts instead of sinking
        if (flies()) this.moveControl = new FlyingMoveControl<>(this, 10, species().kind == Species.Kind.PARTICLES || floats());
        if (floats()) setNoGravity(true);
        if (species().kind == Species.Kind.PARTICLES) setInvisible(true);
        // aquatic wildlife (spec "aquatic": Kelpback, Inkcurl): swims in 3D like an axolotl / dolphin
        if (swims()) {
            float mod = (float) Species.dbl(species().aquatic(), "swim", 0.5);
            this.moveControl = new SmoothSwimmingMoveControl<>(this, 85, 10, mod, 0.1F, false);
            this.lookControl = new SmoothSwimmingLookControl(this, 10);
            setPathfindingMalus(PathType.WATER, 0.0F);
        }
    }

    /** Aquatic wildlife (spec "aquatic"): swims, breathes under water, dries out on land. */
    public boolean swims() {
        return species().aquatic() != null;
    }

    /** Spec options.NoGravity: a floater that never sinks (the Voidpuff over the End's void). */
    public boolean floats() {
        JsonObject o = species().section("options");
        return o != null && o.has("NoGravity") && o.get("NoGravity").getAsBoolean();
    }

    @Override
    public Species species() {
        if (species == null) species = ArcanaEntities.species(getType());
        return species;
    }

    private List<String> goals(String key) {
        JsonObject ai = species().section("ai");
        if (ai == null || !ai.has(key)) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement e : ai.getAsJsonArray(key)) out.add(e.getAsString());
        return out;
    }

    public boolean flies() {
        return goals("goals").contains("randomfly");
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        if (flies()) {
            FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
            nav.setCanFloat(true);
            return nav;
        }
        if (swims()) {
            JsonObject aq = species().aquatic();
            return aq.has("water_only") && aq.get("water_only").getAsBoolean() ? new WaterBoundPathNavigation(this, level)
                    : new AmphibiousPathNavigation(this, level);
        }
        return super.createNavigation(level);
    }

    @Override
    protected void registerGoals() {
        int g = 0;
        for (String goal : goals("goals")) {
            String name = goal.contains("{") ? goal.substring(0, goal.indexOf('{')) : goal;
            Map<String, String> args = args(goal);
            switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "float" -> goalSelector.addGoal(g++, new FloatGoal(this));
                case "panic" -> goalSelector.addGoal(g++, new PanicGoal(this, 1.25));
                case "meleeattack" -> goalSelector.addGoal(g++, new MeleeAttackGoal(this, d(args, "s", 1.0), true));
                // idle wandering waits while a still-only flourish plays (panic, fleeing and parents still move it)
                case "randomstroll" -> goalSelector.addGoal(g++, new WaterAvoidingRandomStrollGoal(this, 1.0) {
                    @Override
                    public boolean canUse() {
                        return !lingering() && super.canUse();
                    }
                });
                case "randomswim" -> goalSelector.addGoal(g++, new RandomSwimmingGoal(this, 1.0, 40) {
                    @Override
                    public boolean canUse() {
                        return !lingering() && super.canUse();
                    }
                });
                case "randomfly" -> goalSelector.addGoal(g++, new WaterAvoidingRandomFlyingGoal(this, 1.0) {
                    @Override
                    public boolean canUse() {
                        return !lingering() && super.canUse();
                    }
                });
                case "lookatplayers" -> goalSelector.addGoal(g++, new LookAtPlayerGoal(this, Player.class, 8.0F));
                case "randomlookaround" -> goalSelector.addGoal(g++, new RandomLookAroundGoal(this));
                case "gotoparent" -> {
                    // only when it can ever match: a young with a parent kind, or a "line" walker (squallgulls have neither)
                    if (species().youngOf() != null || species().flag("line"))
                        goalSelector.addGoal(g++, new Goals.FollowAdultGoal(this, d(args, "s", 1.1), d(args, "mr", 3), d(args, "fr", 6)));
                }
                case "fleeconditional" -> {
                    String cond = goal.toLowerCase(java.util.Locale.ROOT);
                    double dist = d(args, "d", 8);
                    java.util.function.Predicate<net.minecraft.world.entity.LivingEntity> test;
                    if (cond.contains("sprinting true")) test = e -> e.isSprinting();
                    else if (cond.contains("spooked")) test = e -> spooked();
                    else if (cond.contains("crouching false")) test = e -> !e.isShiftKeyDown();
                    else test = e -> true;
                    goalSelector.addGoal(g++, new AvoidEntityGoal<>(this, Player.class, (float) dist, d(args, "ss", 1.2), d(args, "s", 1.5),
                            e -> test.test(e) && !e.isSpectator() && !(e instanceof Player p && p.isCreative())));
                }
                default -> {
                }
            }
        }
        if (goals("goals").isEmpty() && species().kind != Species.Kind.PARTICLES) {
            goalSelector.addGoal(0, new FloatGoal(this));
            goalSelector.addGoal(1, new WaterAvoidingRandomStrollGoal(this, 1.0));
            goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
        }
        if (goals("targets").contains("hurtbytarget")) targetSelector.addGoal(0, new HurtByTargetGoal(this));
    }

    private static Map<String, String> args(String goal) {
        Map<String, String> out = new java.util.HashMap<>();
        int a = goal.indexOf('{'), b = goal.lastIndexOf('}');
        if (a < 0 || b < a) return out;
        for (String p : goal.substring(a + 1, b).split(";")) {
            int eq = p.indexOf('=');
            if (eq > 0 && !p.contains("[")) out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return out;
    }

    private static double d(Map<String, String> args, String k, double def) {
        try {
            return args.containsKey(k) ? Double.parseDouble(args.get(k)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_IDLE, "");
        builder.define(DATA_WALK, "");
        builder.define(DATA_HOLD, "");
        builder.define(DATA_HIDDEN, "");
        builder.define(DATA_TINT, 0xFFFFFF);
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                                  @Nullable SpawnGroupData data) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data);
        resetClips();
        Abilities.onSpawn(this);
        if ((reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.CHUNK_GENERATION) && level instanceof ServerLevel sl) herd(sl);
        return out;
    }

    void resetClips() {
        setIdleClip(species().baseIdle());
        setWalkClip(species().baseWalk());
        hold("");
    }

    /** Natural spawns come as a small group: the first one calls the rest in (spec "herd"). */
    private void herd(ServerLevel level) {
        JsonObject h = species().section("herd");
        if (h == null || grown) return;
        List<String> types = new ArrayList<>();
        for (JsonElement e : h.getAsJsonArray("types")) types.add(e.getAsString());
        boolean others = !Conditions.nearby(this, 8, Mob.class, m -> m instanceof ArcanaMob am && types.contains(am.species().mythicId)).isEmpty();
        if (others) return;
        for (JsonElement e : h.getAsJsonArray("members")) {
            JsonArray m = e.getAsJsonArray();
            if (random.nextDouble() >= m.get(1).getAsDouble()) continue;
            EntityType<?> type = ArcanaEntities.byMythicId(m.get(0).getAsString());
            if (type == null) continue;
            BlockPos at = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    blockPosition().offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3));
            if (swims()) {
                // the heightmap is the water's surface (air above it): herd mates join at the leader's depth, in water
                at = blockPosition().offset(random.nextInt(7) - 3, random.nextInt(3) - 1, random.nextInt(7) - 3);
                if (!level.getFluidState(at).is(FluidTags.WATER) || !level.noCollision(type.getSpawnAABB(at.getX() + 0.5, at.getY(), at.getZ() + 0.5))) continue;
            }
            Mob mob = (Mob) type.create(level, EntitySpawnReason.EVENT);
            if (mob == null) continue;
            mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360, 0);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
            level.addFreshEntity(mob);
        }
    }

    // ------------------------------------------------------------------ API for the wildlife module

    /** Kept by a player (leashed, name-tagged, bred): never despawns (MythicMobs caster.kept). */
    public boolean kept() {
        return kept || isLeashed() || hasCustomName();
    }

    public void setKept(boolean kept) {
        this.kept = kept;
        if (kept) setPersistenceRequired();
    }

    /** Feather hooks (gen_wild.py HOOKS): Harvest, Root, Shorn, Regrow, AntlersOff/On, HoldFish, DropFish, Dive, Lay, Spin, Hide. */
    public boolean hook(String hook) {
        String name = hook.startsWith("ArcanaWild_") ? hook.substring("ArcanaWild_".length()) : hook;
        return Abilities.runSkill(this, species().mythicId + "_" + name, null);
    }

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

    @Override
    public void free() {
        getUp();
    }

    /** The end of a rest: gets up, staying put while the way up (stand_up) plays so it never walks off lying. */
    private void standUp() {
        getUp();
        List<String> way = species().anim.path(AnimSpec.LYING, AnimSpec.STANDING);
        int ticks = 0;
        for (String c : way) ticks += species().anim.tag(c).ticks();
        if (ticks > 0) {
            standUntil = level().getGameTime() + ticks;
            getNavigation().stop();
        }
    }

    /** Back to the base clips, AI on (ArcanaX_GetUp). */
    public void getUp() {
        busy = false;
        lying = false;
        resetClips();
        brainTick();
    }

    public boolean spooked() {
        return level().getGameTime() < spookedUntil;
    }

    public void spook(int ticks) {
        spookedUntil = level().getGameTime() + ticks;
        if (brain != null) brain.interrupt();
    }

    /** True while a still-only idle one-shot plays (the idle wander goals wait). */
    public boolean lingering() {
        return brain != null && brain.lingering();
    }

    /** Lying down for a rest (life lie / lie_long). */
    public boolean lyingDown() {
        return lying;
    }

    /** The clip of the current rest (the lying pool's variants return to it). */
    public String lieClip() {
        return lieClip;
    }

    @Override
    public void onPlayed(String clip) {
        if (brain != null) brain.onPlayed(clip);
    }

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

    /** Hides or shows a bone (and its children) for every watching client (ModelEngine partvis). */
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

    public void setTint(int rgb) {
        entityData.set(DATA_TINT, rgb & 0xFFFFFF);
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
        return busy || level().getGameTime() < standUntil || super.isImmobile();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || !isAlive()) return;
        scheduler.tick();
        int t = tickCount + getId();
        if (t % 20 == 0) {
            brainTick();
            brain.second();
        }
        brain.tick();
        Species ls = species();
        JsonObject life = ls.life;
        if (life != null) {
            int every = ls.lifeEvery;
            if (every > 0 && t % every == 0 && random.nextDouble() < ls.lifeChance(0.4)) life(life);
        }
        if (t % 600 == 0) despawnCheck(level);
        if (species().young() && t % 1200 == 0) age(level);
        CreatureSfx.tick(this);
        Abilities.tick(this);
    }

    /** Every second: gait / idle swaps (swim, run when spooked, charge in combat) unless holding a pose. */
    void brainTick() {
        if (isLeashed() && busy && species().life != null) getUp();
        if (busy) return;
        Species s = species();
        JsonArray gaits = s.json.has("gaits") ? s.json.getAsJsonArray("gaits") : new JsonArray();
        String idle = s.baseIdle(), walk = s.baseWalk();
        boolean panicRun = s.panicRun();
        for (JsonElement e : gaits) {
            JsonObject g = e.getAsJsonObject();
            if (gaitHolds(g)) {
                if (g.has("idle")) idle = g.get("idle").getAsString();
                if (g.has("walk")) walk = g.get("walk").getAsString();
            }
        }
        if (panicRun && spooked() && s.has("run")) walk = "run";
        idle = brain.idle(idle);
        walk = brain.walk(walk);
        if (!idle.equals(idleClip())) setIdleClip(idle);
        if (!walk.equals(walkClip()) && var("gliding") == 0) setWalkClip(walk);
    }

    private boolean gaitHolds(JsonObject g) {
        if (g.has("players")) {
            double r = Species.dbl(g, "r", 16);
            String p = g.get("players").getAsString();
            return !Conditions.nearby(this, r, Player.class, pl -> !pl.isSpectator()
                    && (p.contains("crouching false") ? !pl.isShiftKeyDown() : !p.contains("sprinting true") || pl.isSprinting())).isEmpty();
        }
        String cond = Species.str(g, "cond", "");
        if (cond.contains("incombat")) return getTarget() != null;
        if (cond.contains("inblock{m=WATER}")) return Conditions.inWater(this);
        if (cond.contains("spooked")) return spooked();
        return false;
    }

    /** Idle life (gen_wild.py life_skills): a resting creature may graze, lie down, look about or do its own thing. */
    private void life(JsonObject life) {
        if (busy || getTarget() != null || Conditions.inWater(this) && !swims() || getDeltaMovement().horizontalDistanceSqr() > 1.0E-4
                || !getNavigation().isDone() || isLeashed() || lingering() || level().getGameTime() < standUntil) return;
        JsonObject table = Conditions.night(level()) && life.has("night") ? life.getAsJsonObject("night") : life.getAsJsonObject("day");
        if (table == null || table.isEmpty()) return;
        double total = 0;
        for (Map.Entry<String, JsonElement> e : table.entrySet()) total += e.getValue().getAsDouble();
        double r = random.nextDouble() * total;
        String act = null;
        for (Map.Entry<String, JsonElement> e : table.entrySet()) {
            r -= e.getValue().getAsDouble();
            if (r <= 0) {
                act = e.getKey();
                break;
            }
        }
        if (act != null) act(life, act);
    }

    private void act(JsonObject life, String act) {
        Species s = species();
        boolean aiToggle = !s.json.has("ai_toggle") || s.json.get("ai_toggle").getAsBoolean();
        switch (act) {
            case "graze", "graze_long" -> {
                int n = act.equals("graze") ? 120 : 220;
                String clip = Species.str(life, "graze_clip", "graze");
                if (!s.has(clip)) return;
                setBusy(true);
                setIdleClip(clip);
                Block block = blockOf(Species.str(life, "graze_block", "GRASS_BLOCK"));
                Species.Sound snd = life.has("graze_sound") ? sound3(life.getAsJsonArray("graze_sound")) : null;
                for (int t = 0; t < n; t += 60) {
                    scheduler.after(t, () -> {
                        if (!busy) return;
                        Vec3 f = position().add(getLookAngle().multiply(1, 0, 1).normalize().scale(0.9));
                        if (block != null && level() instanceof ServerLevel sl) {
                            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block.defaultBlockState()), f.x, f.y + 0.15, f.z, 3, 0.25, 0.05, 0.25, 0.02);
                        }
                        if (snd != null) sound(snd);
                    });
                }
                scheduler.after(n, () -> {
                    if (busy) getUp();
                });
            }
            case "lie", "lie_long" -> {
                int n = act.equals("lie") ? (int) Species.dbl(life, "lie_ticks", 500) : (int) Species.dbl(life, "lie_long_ticks", 1100);
                String clip = Species.str(life, "lie_clip", "lie");
                if (!s.has(clip)) return;
                setBusy(true);
                lying = true;
                lieClip = clip;
                hold(clip);
                scheduler.after(n, () -> {
                    if (lying) standUp();
                });
            }
            case "look" -> {
                Player p = level().getNearestPlayer(this, 10);
                if (p != null) getLookControl().setLookAt(p, 12, 12);
                if (random.nextBoolean() && !CreatureSfx.play(this, CreatureSfx.ambientEvent(this))) sound(s.sound("ambient"));
            }
            default -> {
                JsonObject hold = life.has("hold") ? life.getAsJsonObject("hold") : null;
                JsonArray extra = life.has("extra") && life.getAsJsonObject("extra").has(act) ? life.getAsJsonObject("extra").getAsJsonArray(act) : null;
                if (act.startsWith("ds_")) {
                    String clip = act.substring(3);
                    setBusy(true);
                    setIdleClip(clip);
                    scheduler.after(100, () -> {
                        if (busy) getUp();
                    });
                } else if (hold != null && hold.has(act)) {
                    int times = hold.get(act).getAsInt();
                    int len = Math.max(10, s.clipTicks(act, 40));
                    if (aiToggle) setBusy(true);
                    for (int i = 0; i < times; i++) {
                        scheduler.after(i * len, () -> {
                            play(act);
                            if (extra != null) Fx.lines(this, extra);
                        });
                    }
                    scheduler.after(times * len, () -> {
                        if (busy) getUp();
                    });
                } else {
                    if (s.has(act)) play(act);
                    else if (Abilities.runSkill(this, s.mythicId + "_" + capitalize(act), null)) return;
                    if (extra != null) Fx.lines(this, extra);
                }
            }
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static @Nullable Block blockOf(String id) {
        var key = net.minecraft.resources.Identifier.tryParse(id.toLowerCase(java.util.Locale.ROOT));
        return key == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(key);
    }

    private static Species.Sound sound3(JsonArray a) {
        return new Species.Sound(a.get(0).getAsString(), a.get(2).getAsFloat(), a.get(1).getAsFloat());
    }

    private void despawnCheck(ServerLevel level) {
        String despawn = species().despawn;
        if (kept() || "persistent".equals(despawn) && species().kind != Species.Kind.LIVESTOCK) return;
        if (species().kind == Species.Kind.LIVESTOCK || species().kind == Species.Kind.WILDLIFE || species().kind == Species.Kind.PARTICLES) {
            if (level.getNearestPlayer(this, 64) == null) discard();
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return super.shouldBeSaved() && kept();
    }

    // ------------------------------------------------------------------ aquatic (spec "aquatic")

    @Override
    public boolean canBreatheUnderwater() {
        return swims() || super.canBreatheUnderwater();
    }

    @Override
    public boolean isPushedByFluid() {
        return !swims() && super.isPushedByFluid();
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

    @Override
    public int getMaxAirSupply() {
        return swims() ? 2400 : super.getMaxAirSupply();
    }

    /** A swimmer out of water slowly dries out (2 minutes of air), like a dolphin; in water or rain it refills. */
    @Override
    public void baseTick() {
        int air = getAirSupply();
        super.baseTick();
        if (!swims() || !(level() instanceof ServerLevel sl) || !isAlive()) return;
        if (isInWaterOrRain()) {
            setAirSupply(getMaxAirSupply());
        } else {
            setAirSupply(air - 1);
            if (getAirSupply() <= -20) {
                setAirSupply(0);
                hurtServer(sl, damageSources().dryOut(), 2.0F);
            }
        }
    }

    /** Swimmers glide (no water drag on the input, gentle damping); idle ones sink slowly onto the floor to rest. */
    @Override
    protected void travelInWater(Vec3 input, double baseGravity, boolean isFalling, double oldY) {
        if (!swims()) {
            super.travelInWater(input, baseGravity, isFalling, oldY);
            return;
        }
        moveRelative(getSpeed(), input);
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(0.9));
        if (getTarget() == null && getNavigation().isDone()) setDeltaMovement(getDeltaMovement().add(0.0, -0.005, 0.0));
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    /** One minute of age per loaded minute; at 20 the young becomes an adult (kept flag and a given name carry over). */
    private void age(ServerLevel level) {
        ageMinutes++;
        if (ageMinutes < 20 || isLeashed()) return;
        EntityType<?> adult = ArcanaEntities.byMythicId(species().youngOf());
        if (adult == null) return;
        Mob mob = (Mob) adult.create(level, EntitySpawnReason.CONVERSION);
        if (!(mob instanceof ArcanaWildlife w)) return;
        w.snapTo(getX(), getY(), getZ(), getYRot(), getXRot());
        w.grown = true;
        w.finalizeSpawn(level, level.getCurrentDifficultyAt(blockPosition()), EntitySpawnReason.CONVERSION, null);
        w.setKept(kept);
        if (hasCustomName()) {
            w.setCustomName(getCustomName());
            w.setKept(true);
        }
        level.addFreshEntity(w);
        particles(ParticleTypes.HAPPY_VILLAGER, 12, 0.5, 0.5, 0.6, 0.02);
        discard();
    }

    public int ageMinutes() {
        return ageMinutes;
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    public boolean canMate(Animal other) {
        return false;
    }

    // ------------------------------------------------------------------ damage, death, sounds

    @Override
    public boolean isInvulnerable() {
        return species().kind == Species.Kind.PARTICLES || super.isInvulnerable();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (species().kind == Species.Kind.PARTICLES) return false;
        float mult = CreatureUtil.damageMultiplier(species(), source);
        if (mult <= 0) return false;
        boolean hurt = super.hurtServer(level, source, amount * mult);
        if (hurt && isAlive()) {
            play(species().anim("hurt"));
            standUntil = 0;
            brain.interrupt();
            if (busy) getUp();
            if (species().panicRun()) spook(100);
            Abilities.onDamaged(this, source);
        }
        return hurt;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource source) {
        if (flies() || CreatureUtil.damageMultiplier(species(), source) <= 0) return false;
        return super.causeFallDamage(fallDistance, damageModifier, source);
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, net.minecraft.world.entity.Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit) {
            play(species().anim("attack"));
            if (!CreatureSfx.play(this, "attack")) sound(species().sound("attack"));
            Abilities.onAttack(this, target);
        }
        return hit;
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
        out.putBoolean("ArcanaKept", kept);
        out.putInt("ArcanaAge", ageMinutes);
        out.putBoolean("ArcanaGrown", grown);
        out.putString("ArcanaHidden", hiddenBones());
        out.putInt("ArcanaTint", tint());
        if (!vars.isEmpty()) out.store("ArcanaVars", ArcanaCreature.VARS_CODEC, Map.copyOf(vars));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        kept = in.getBooleanOr("ArcanaKept", false);
        ageMinutes = in.getIntOr("ArcanaAge", 0);
        grown = in.getBooleanOr("ArcanaGrown", false);
        entityData.set(DATA_HIDDEN, in.getStringOr("ArcanaHidden", ""));
        setTint(in.getIntOr("ArcanaTint", 0xFFFFFF));
        vars.clear();
        in.read("ArcanaVars", ArcanaCreature.VARS_CODEC).ifPresent(vars::putAll);
        // chunk load / restart: drop any held pose (the delayed get-up was lost)
        busy = false;
        lying = false;
        resetClips();
        Abilities.onLoad(this);
    }
}
