package net.axther.aftrhrs.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The penguins (spec {@code "penguin"}): Emperor and Rockhopper, each with a chick form.
 * <ul>
 *   <li><b>Body</b>: amphibious. Waddles (the rockhopper hops) on land, belly-slides on snow and ice when it hurries
 *       (gait {@code slide}, faster), swims fast under water (its own move control and water travel, never drowns),
 *       floats at the surface when idle. Wild adults now and then go for a swim and come back out.</li>
 *   <li><b>Colonies</b>: a natural spawn brings its group (spec {@code penguin.group}: adults and chicks) and only
 *       happens near water or ice; wild chicks keep close to the grown-ups.</li>
 *   <li><b>Raised, not tamed</b>: adults are never tamed by hand (spec {@code raised}); a wild chick takes a raw fish
 *       (1 in 3). The owner's fish keep a tamed chick growing for 5 minutes each (and add a minute); after 20 fed minutes
 *       it grows up into its adult type ({@link #growUp}), keeping owner, bond, name and look
 *       ({@link CreatureEvents#GREW_UP} moves the pet record).</li>
 *   <li><b>Boats</b> ({@link PenguinBoats}): wild adults swimming near a player's boat in their habitat push it for 30 -
 *       60 s, at most {@link PenguinBoats#WILD_CAP} per boat, then rest; they let go at once when the boat leaves their
 *       habitat or outruns them. A grown pet hops into the back seat of its owner's boat (boat_sit / boat_push) and out
 *       when the owner gets out.</li>
 *   <li><b>Fishing buddy</b>: a raw fish fed to an adult (wild, or your own) makes it fish beside you for a while: each
 *       catch then has its species' chance (+ bond for your pet) to come with a second fish ({@link #buddyDouble},
 *       called by the fishing module), at most {@link #BUDDY_MAX_DOUBLES} per session. One session per player every
 *       {@link #BUDDY_PLAYER_COOLDOWN} ticks, and the penguin rests {@link #BUDDY_REST} after it.</li>
 * </ul>
 * Everything is decided here on the server; clients only see synced state (pushing, passengers, clips, held fish).
 */
public class PenguinCreature extends ArcanaCreature {
    private static final EntityDataAccessor<String> DATA_HIDDEN = SynchedEntityData.defineId(PenguinCreature.class, EntityDataSerializers.STRING);
    /** Entity id of the boat this wild penguin pushes, -1 for none. */
    private static final EntityDataAccessor<Integer> DATA_PUSH = SynchedEntityData.defineId(PenguinCreature.class, EntityDataSerializers.INT);
    private static final Identifier SLIDE_SPEED = AftrHrs.id("penguin_slide");
    private static final String FISH_BONE = "held_fish";

    /** A chick grows up after this many fed ticks (20 minutes); each fish feeds it 5 minutes (stock up to 10) and adds 1. */
    public static final int GROW_TICKS = 24000;
    public static final int FED_PER_FISH = 6000;
    public static final int FED_MAX = 12000;
    public static final int GROW_BONUS = 1200;
    /** Ticks between two fish for a growing chick. */
    public static final int FEED_COOLDOWN = 400;

    /** Fishing buddy: session length (wild; pets longer with bond), cooldowns, doubles per session, range. */
    public static final int BUDDY_WILD_TICKS = 1200;
    public static final int BUDDY_PET_TICKS = 1800;
    public static final int BUDDY_PET_BOND_TICKS = 200;
    public static final int BUDDY_PLAYER_COOLDOWN = 6000;
    public static final int BUDDY_REST = 6000;
    public static final int BUDDY_MAX_DOUBLES = 3;
    public static final double BUDDY_RANGE = 24;

    /** Wild boat pushing: how long a push lasts, the rest after it, how far a penguin notices a boat, join chance. */
    public static final int PUSH_MIN = 600, PUSH_MAX = 1200, PUSH_REST_MIN = 2400, PUSH_REST_MAX = 4800;
    public static final double PUSH_NOTICE = 14;
    public static final double PUSH_JOIN_CHANCE = 0.35;

    /** Player -> game time a new fishing buddy session may start (shared by all penguins). */
    private static final Map<UUID, Long> BUDDY_READY = new HashMap<>();

    private long pushUntil, pushRestUntil, boardReadyAt;
    private double boatLastX, boatLastZ, boatSpeed;
    /** A pusher's slot around the stern (index into {@link #SLOTS}); -1 = find it again. Transient, server side. */
    private int pushSlot = -1;
    /** Pusher slots, side and forward offsets from the boat: behind, the rear corners, further behind. */
    private static final double[][] SLOTS = {{0, -1.9}, {-1.2, -1.3}, {1.2, -1.3}, {0, -3.0}};
    private @Nullable UUID buddy;
    private long buddyUntil, buddyRestUntil;
    private int buddyDoubles;
    private long fishHideAt;
    private int offBoatTicks, waterTicks;
    private @Nullable Set<String> habitat;

    public PenguinCreature(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
        this.moveControl = new PenguinMoveControl(this);
        setPathfindingMalus(PathType.WATER, 0.0F);
        setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
        if (!chick()) setBoneVisible(FISH_BONE, false);
    }

    // ------------------------------------------------------------------ species data

    JsonObject cfg() {
        JsonObject c = species().section("penguin");
        return c == null ? new JsonObject() : c;
    }

    double cfgD(String key, double def) {
        return Species.dbl(cfg(), key, def);
    }

    /** A chick (spec penguin.adult names its grown-up form). */
    public boolean chick() {
        return cfg().has("adult");
    }

    public String family() {
        return Species.str(cfg(), "family", "");
    }

    /** Biomes where wild ones push boats (they let go when the boat leaves them). */
    public Set<String> habitat() {
        if (habitat == null) {
            Set<String> h = new HashSet<>();
            JsonArray a = cfg().has("habitat") ? cfg().getAsJsonArray("habitat") : new JsonArray();
            for (JsonElement e : a) {
                String b = e.getAsString().toLowerCase(java.util.Locale.ROOT);
                h.add(b.contains(":") ? b : "minecraft:" + b);
            }
            habitat = h;
        }
        return habitat;
    }

    /** Raw fish a penguin eats: cod, salmon, tropical fish and the fishing module's fish. */
    public static boolean isRawFish(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(Items.COD) || stack.is(Items.SALMON) || stack.is(Items.TROPICAL_FISH)) return true;
        String id = ArcanaContent.id(stack);
        return id != null && id.startsWith("arcana_fish_");
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new PenguinNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new SitWhenOrderedToGoal(this));
        goalSelector.addGoal(2, new PanicGoal(this, 1.4) {
            @Override
            public boolean canUse() {
                return !isTame() && super.canUse();
            }
        });
        goalSelector.addGoal(3, new FollowColonyGoal(this));
        goalSelector.addGoal(4, new FollowOwnerGoal(this, 1.0, 6, 3));
        goalSelector.addGoal(5, new RandomSwimmingGoal(this, 1.0, 30) {
            @Override
            public boolean canUse() {
                return isInWater() && !errand() && super.canUse();
            }
        });
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8) {
            @Override
            public boolean canUse() {
                return !isInWater() && !lingering() && !errand() && super.canUse();
            }
        });
        goalSelector.addGoal(7, new Goals.LookAtOwnerGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_HIDDEN, "");
        builder.define(DATA_PUSH, -1);
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                                  @Nullable SpawnGroupData data) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data);
        if (!chick()) setBoneVisible(FISH_BONE, false);
        if ((reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.CHUNK_GENERATION) && !chick()) colony(level.getLevel());
        return out;
    }

    /** A natural spawn brings its colony: spec penguin.group {adults: [min, max], chicks: [min, max]}, on dry ground nearby. */
    private void colony(ServerLevel level) {
        JsonObject g = cfg().has("group") ? cfg().getAsJsonObject("group") : null;
        if (g == null) return;
        boolean others = !Conditions.nearby(this, 16, PenguinCreature.class, p -> p != this && p.family().equals(family())).isEmpty();
        if (others) return;
        EntityType<?> chickType = ArcanaEntities.byMythicId(Species.str(cfg(), "chick", null));
        spawnMates(level, getType(), range(g, "adults"));
        if (chickType != null) spawnMates(level, chickType, range(g, "chicks"));
    }

    private int range(JsonObject g, String key) {
        if (!g.has(key)) return 0;
        JsonArray a = g.getAsJsonArray(key);
        int lo = a.get(0).getAsInt(), hi = a.get(1).getAsInt();
        return lo + random.nextInt(Math.max(1, hi - lo + 1));
    }

    private void spawnMates(ServerLevel level, EntityType<?> type, int n) {
        for (int i = 0, tries = 0; i < n && tries < n * 6; tries++) {
            BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockPosition().offset(random.nextInt(9) - 4, 0, random.nextInt(9) - 4));
            if (!level.getFluidState(at).isEmpty() || !level.getFluidState(at.below()).isEmpty()) continue;
            if (Math.abs(at.getY() - getBlockY()) > 3) continue;
            if (!level.noCollision(type.getSpawnAABB(at.getX() + 0.5, at.getY(), at.getZ() + 0.5))) continue;
            Mob mob = (Mob) type.create(level, EntitySpawnReason.EVENT);
            if (mob == null) return;
            mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360, 0);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
            level.addFreshEntity(mob);
            i++;
        }
    }

    /** Natural spawns only near open water or ice (the spawner's last check): no colonies in the middle of the plains. */
    @Override
    public boolean checkSpawnObstruction(LevelReader level) {
        return super.checkSpawnObstruction(level) && nearWater(level, blockPosition(), 12);
    }

    static boolean nearWater(LevelReader level, BlockPos at, int r) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                for (int dy = -3; dy <= 1; dy++) {
                    p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (level.getFluidState(p).is(FluidTags.WATER) || level.getBlockState(p).is(BlockTags.ICE)) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ synced state

    /** The boat this wild penguin pushes (entity id), or -1. */
    public int pushing() {
        return entityData.get(DATA_PUSH);
    }

    @Override
    public String hiddenBones() {
        return entityData.get(DATA_HIDDEN);
    }

    @Override
    public void setBoneVisible(String bone, boolean visible) {
        List<String> hidden = new ArrayList<>(List.of(hiddenBones().isEmpty() ? new String[0] : hiddenBones().split(",")));
        hidden.remove(bone);
        if (!visible) hidden.add(bone);
        entityData.set(DATA_HIDDEN, String.join(",", hidden));
    }

    /** The fish in its bill shows while it eats / catches (the clips scale it in and out), else it is hidden. */
    @Override
    public void onPlayed(String clip) {
        super.onPlayed(clip);
        if (!chick() && (clip.equals("eat") || clip.equals("catch") || clip.equals("ability"))) {
            setBoneVisible(FISH_BONE, true);
            fishHideAt = level().getGameTime() + species().clipTicks(clip, 40);
        }
    }

    /** Pushing a boat, fishing with someone, or aboard a boat: the idle wandering waits. */
    boolean errand() {
        return pushing() >= 0 || buddy != null || getVehicle() instanceof AbstractBoat;
    }

    // ------------------------------------------------------------------ gait

    /** Belly-sliding: an adult hurrying (following, running) over snow or ice. */
    public boolean sliding() {
        return !chick() && !isInWater() && onSnowOrIce() && getNavigation().isInProgress() && getMoveControl().getSpeedModifier() >= 0.95;
    }

    boolean onSnowOrIce() {
        BlockState under = level().getBlockState(getOnPos());
        BlockState at = level().getBlockState(blockPosition());
        return under.is(BlockTags.ICE) || PenguinBoats.snowy(under) || PenguinBoats.snowy(at);
    }

    @Override
    protected String moodWalk(String walk) {
        if (Conditions.inWater(this)) return walk;
        return sliding() && species().has("slide") ? "slide" : walk;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || !isAlive()) return;
        long now = level.getGameTime();
        int t = tickCount + getId();
        if (fishHideAt > 0 && now >= fishHideAt) {
            fishHideAt = 0;
            setBoneVisible(FISH_BONE, false);
        }
        if (chick()) {
            if (isTame() && t % 20 == 0) grow(level);
        } else {
            if (isTame()) boatSeat(now);
            else pushTick(level, now, t);
            if (buddy != null && t % 10 == 0) buddyTick(level, now);
        }
        if (t % 10 == 0) slideSpeed();
        if (!isTame() && !chick() && t % 40 == 0) wanderWater(level);
    }

    private void slideSpeed() {
        AttributeInstance a = getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        boolean want = "slide".equals(walkClip()) && sliding();
        boolean has = a.hasModifier(SLIDE_SPEED);
        if (want && !has) a.addTransientModifier(new AttributeModifier(SLIDE_SPEED, 0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        else if (!want && has) a.removeModifier(SLIDE_SPEED);
    }

    /** Wild adults go for a swim now and then and come back out after a while; a player's boat draws them in. */
    private void wanderWater(ServerLevel level) {
        if (errand() || isLeashed() || getTarget() != null) return;
        if (isInWater()) {
            waterTicks += 40;
            if (waterTicks > 1200 + random.nextInt(1200) && getNavigation().isDone()) {
                Vec3 land = LandRandomPos.getPos(this, 14, 6);
                if (land != null) getNavigation().moveTo(land.x, land.y, land.z, 1.0);
            }
            return;
        }
        waterTicks = 0;
        AbstractBoat boat = playerBoatNear(level, PUSH_NOTICE + 2);
        if (boat != null && level.getGameTime() >= pushRestUntil && random.nextFloat() < 0.35f) {
            getNavigation().moveTo(boat, 1.1);
            return;
        }
        if (random.nextFloat() < 0.03f && getNavigation().isDone()) {
            for (int i = 0; i < 8; i++) {
                BlockPos p = blockPosition().offset(random.nextInt(21) - 10, random.nextInt(5) - 3, random.nextInt(21) - 10);
                if (level.getFluidState(p).is(FluidTags.WATER) && level.getBlockState(p.above()).isAir()) {
                    getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ chicks: growing up

    private void grow(ServerLevel level) {
        int fed = var("fed");
        if (fed <= 0) return;
        setVar("fed", Math.max(0, fed - 20));
        int g = var("grow") + 20;
        setVar("grow", g);
        if (g >= GROW_TICKS) growUp(level);
    }

    /** 0..100: how far a tamed chick has grown. */
    public int growthPercent() {
        return Math.min(100, var("grow") * 100 / GROW_TICKS);
    }

    private InteractionResult feedChick(ServerPlayer owner, ItemStack stack) {
        if (!scheduler().ready("grow_feed", FEED_COOLDOWN)) {
            CreatureUtil.say(owner, true, "penguin.swallowing", this, "&7<pet> &7is still swallowing the last one.");
            return InteractionResult.SUCCESS;
        }
        eatFish(owner, stack);
        setVar("fed", Math.min(FED_MAX, var("fed") + FED_PER_FISH));
        setVar("grow", var("grow") + GROW_BONUS);
        CreatureUtil.say(owner, true, "penguin.growing", this, "&b<pet> &7is growing: &f<percent>%&7. Each fish keeps it growing 5 minutes.",
                net.axther.aftrhrs.core.Text.unparsed("percent", growthPercent()));
        if (var("grow") >= GROW_TICKS && level() instanceof ServerLevel level) scheduler().after(30, () -> {
            if (isAlive() && !isRemoved()) growUp(level);
        });
        return InteractionResult.SUCCESS;
    }

    /** The chick becomes its adult type: same owner, bond, name, look and stay state; the pet record follows. */
    public @Nullable PenguinCreature growUp(ServerLevel level) {
        EntityType<?> type = ArcanaEntities.byMythicId(Species.str(cfg(), "adult", null));
        if (type == null || !isAlive() || isRemoved()) return null;
        if (!(type.create(level, EntitySpawnReason.CONVERSION) instanceof PenguinCreature adult)) return null;
        adult.snapTo(getX(), getY(), getZ(), getYRot(), getXRot());
        adult.finalizeSpawn(level, level.getCurrentDifficultyAt(blockPosition()), EntitySpawnReason.CONVERSION, null);
        EntityReference<LivingEntity> owner = getOwnerReference();
        if (isTame() && owner != null) {
            adult.setTame(true, true);
            adult.setOwnerReference(owner);
            adult.setPersistenceRequired();
        }
        adult.setBond(bond());
        adult.setTint(tint());
        adult.setShiny(shiny());
        adult.setVariant(variant());
        if (hasCustomName()) {
            adult.setCustomName(getCustomName());
            adult.setCustomNameVisible(isCustomNameVisible());
        }
        if (staying()) {
            adult.setOrderedToSit(true);
            adult.setInSittingPose(true);
        }
        adult.setHealth(adult.getMaxHealth() * Math.max(0.25f, getHealth() / getMaxHealth()));
        CreatureEvents.GREW_UP.invoker().onGrewUp(this, adult);
        level.addFreshEntity(adult);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 0.5, getZ(), 14, 0.4, 0.4, 0.4, 0.02);
        level.sendParticles(ParticleTypes.SNOWFLAKE, getX(), getY() + 0.5, getZ(), 18, 0.4, 0.4, 0.4, 0.03);
        CreatureSfx.play(adult, "happy");
        ServerPlayer o = ownerPlayer();
        if (o != null) CreatureUtil.say(o, false, "penguin.grew-up", this, "&b<pet> &7grew up into <adult>&7! Take it boating or fishing.",
                net.axther.aftrhrs.core.Text.unparsed("adult", adult.species().name));
        discard();
        return adult;
    }

    @Override
    protected boolean isTameItem(ItemStack stack) {
        return chick() && isRawFish(stack);
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!chick() && isRawFish(stack) && !player.isShiftKeyDown() && (!isTame() || isOwnedBy(player))) {
            if (level().isClientSide()) return InteractionResult.SUCCESS;
            if (player instanceof ServerPlayer sp) return feedAdult(sp, stack);
        }
        return super.mobInteract(player, hand);
    }

    @Override
    protected InteractionResult ownerInteract(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        if (chick() && isRawFish(stack) && !player.isShiftKeyDown()) return feedChick(player, stack);
        return super.ownerInteract(player, hand, stack);
    }

    /** Eats one fish: heals, eat clip + sound, bond XP for its owner (CreatureEvents.FED). */
    private void eatFish(ServerPlayer player, ItemStack stack) {
        ItemStack fed = stack.copyWithCount(1);
        stack.consume(1, player);
        heal(species().healAmount());
        makeHappy();
        particles(ParticleTypes.HEART, 2, 0.3, 0.3, 0.8, 0.02);
        CreatureSfx.play(this, "eat");
        if (!staying()) playRole("eat");
        if (isTame() && isOwnedBy(player)) CreatureEvents.FED.invoker().onFed(this, player, fed);
    }

    /** A fish for an adult: it fishes beside you for a while (fishing buddy), or just eats (a hurt pet) when it can't yet. */
    private InteractionResult feedAdult(ServerPlayer p, ItemStack stack) {
        if (!scheduler().ready("fish_click", 10)) return InteractionResult.SUCCESS;
        long now = level().getGameTime();
        if (buddy != null && buddy.equals(p.getUUID())) {
            CreatureUtil.say(p, true, "penguin.already-fishing", this, "&7<pet> &7is already fishing with you.");
            return InteractionResult.SUCCESS;
        }
        long ready = buddyReadyAt(p.getUUID());
        boolean rested = now >= buddyRestUntil && buddy == null;
        if (now < ready || !rested) {
            if (isTame() && getHealth() < getMaxHealth()) {
                eatFish(p, stack);
                return InteractionResult.SUCCESS;
            }
            long wait = Math.max(ready - now, rested ? 0 : buddyRestUntil - now);
            CreatureUtil.say(p, true, "penguin.not-hungry", this, "&7<pet> &7isn't hungry yet. &8(fishing again in <time>)", net.axther.aftrhrs.core.Text.unparsed("time", time(wait)));
            return InteractionResult.SUCCESS;
        }
        eatFish(p, stack);
        startBuddy(p, now);
        return InteractionResult.SUCCESS;
    }

    private void startBuddy(ServerPlayer p, long now) {
        int len = isTame() ? BUDDY_PET_TICKS + BUDDY_PET_BOND_TICKS * bond() : BUDDY_WILD_TICKS;
        buddy = p.getUUID();
        buddyUntil = now + len;
        buddyDoubles = 0;
        BUDDY_READY.put(p.getUUID(), now + BUDDY_PLAYER_COOLDOWN);
        CreatureSfx.play(this, "ability");
        CreatureUtil.say(p, true, "penguin.fishing-start", this, "&b<pet> &7dives in to fish with you &8(<time>)&7: a chance of a second fish on every catch.",
                net.axther.aftrhrs.core.Text.unparsed("time", time(len)));
    }

    private void endBuddy(boolean tell) {
        UUID who = buddy;
        buddy = null;
        buddyRestUntil = level().getGameTime() + BUDDY_REST;
        if (tell && who != null && level() instanceof ServerLevel l && l.getServer().getPlayerList().getPlayer(who) instanceof ServerPlayer p) {
            if (buddyDoubles > 0) CreatureUtil.say(p, true, "penguin.fishing-done-extra", this, "&7<pet> &7is done fishing &8(<extra> extra fish)&7.",
                    net.axther.aftrhrs.core.Text.unparsed("extra", buddyDoubles));
            else CreatureUtil.say(p, true, "penguin.fishing-done", this, "&7<pet> &7is done fishing&7.");
        }
    }

    /** Swims to the player's bobber (or keeps near the player); ends when time is up or the player is gone. */
    private void buddyTick(ServerLevel level, long now) {
        ServerPlayer p = buddy == null ? null : level.getServer().getPlayerList().getPlayer(buddy);
        if (p == null || p.level() != level || !p.isAlive() || now >= buddyUntil || distanceToSqr(p) > BUDDY_RANGE * BUDDY_RANGE * 1.5) {
            endBuddy(p != null);
            return;
        }
        if (getVehicle() != null || staying()) return;
        FishingHook hook = p.fishing;
        boolean atHook = hook != null && hook.isAlive() && hook.isInWater();
        Vec3 goal = atHook ? hook.position() : p.position();
        if (goal.distanceToSqr(position()) > 6.25) {
            getNavigation().moveTo(goal.x + Mth.sin(tickCount * 0.05f) * 1.5, goal.y - (atHook ? 0.8 : 0), goal.z + Mth.cos(tickCount * 0.05f) * 1.5, 1.2);
        }
    }

    /** The fishing buddy diving for {@code p} right now, if any (the nearest one). */
    public static @Nullable PenguinCreature buddyOf(ServerPlayer p) {
        long now = p.level().getGameTime();
        List<PenguinCreature> near = p.level().getEntitiesOfClass(PenguinCreature.class, p.getBoundingBox().inflate(BUDDY_RANGE),
                c -> c.isAlive() && p.getUUID().equals(c.buddy) && now < c.buddyUntil);
        return near.stream().min(Comparator.comparingDouble(c -> c.distanceToSqr(p))).orElse(null);
    }

    /** The buddy's chance to double one catch: its species' value, plus bond for your own pet. */
    public double buddyChance(ServerPlayer p) {
        double c = cfgD("buddy", 0.25);
        if (isTame() && isOwnedBy(p)) c += cfgD("buddy_bond", 0.03) * bond();
        return Mth.clamp(c, 0, 0.6);
    }

    /**
     * A catch was landed by {@code p}: true when a buddy penguin brings a second fish (rolled with its chance, at most
     * {@link #BUDDY_MAX_DOUBLES} a session). Plays its catch clip; the fishing module makes the fish item.
     */
    public static boolean buddyDouble(ServerPlayer p, Vec3 hookPos) {
        PenguinCreature c = buddyOf(p);
        if (c == null || c.buddyDoubles >= BUDDY_MAX_DOUBLES) return false;
        if (c.random.nextDouble() >= c.buddyChance(p)) return false;
        c.buddyDoubles++;
        c.play(c.isInWater() ? "ability" : "catch");
        CreatureSfx.play(c, "ability");
        if (c.level() instanceof ServerLevel l) l.sendParticles(ParticleTypes.SPLASH, hookPos.x, hookPos.y + 0.2, hookPos.z, 16, 0.4, 0.1, 0.4, 0.1);
        CreatureUtil.say(p, true, "penguin.caught-too", c, "&b<pet> &7caught one too!");
        if (c.buddyDoubles >= BUDDY_MAX_DOUBLES) c.scheduler().after(40, () -> c.endBuddy(true));
        return true;
    }

    /** Game tests: forget the per-player buddy cooldowns. */
    public static void resetBuddyCooldowns() {
        BUDDY_READY.clear();
    }

    /** Game time from which the player may start a new fishing buddy session. */
    public static long buddyReadyAt(UUID player) {
        return BUDDY_READY.getOrDefault(player, 0L);
    }

    public @Nullable UUID buddy() {
        return buddy;
    }

    public int buddyDoubles() {
        return buddyDoubles;
    }

    private static String time(long ticks) {
        long s = Math.max(1, ticks / 20);
        return s >= 60 ? s / 60 + "m " + s % 60 + "s" : s + "s";
    }

    // ------------------------------------------------------------------ boats: the back seat (pets)

    /** A grown pet hops into its owner's boat when it is close, and out when the owner gets out. */
    private void boatSeat(long now) {
        if (getVehicle() instanceof AbstractBoat boat) {
            ServerPlayer owner = ownerPlayer();
            if (owner == null || owner.getVehicle() != boat || staying()) {
                if (++offBoatTicks >= 10) {
                    offBoatTicks = 0;
                    stopRiding();
                    playRole("happy");
                }
                return;
            }
            offBoatTicks = 0;
            if (tickCount % 5 == 0) {
                double dx = boat.getX() - boatLastX, dz = boat.getZ() - boatLastZ;
                boatSpeed = boatSpeed * 0.5 + Math.sqrt(dx * dx + dz * dz) / 5 * 0.5;
                boatLastX = boat.getX();
                boatLastZ = boat.getZ();
                String want = boatSpeed > 0.06 ? "boat_push" : "boat_sit";
                if (!want.equals(holdClip())) hold(want);
            }
            return;
        }
        if (holdClip().startsWith("boat_")) hold("");
        if (tickCount % 10 != 0 || staying() || isLeashed() || now < boardReadyAt) return;
        ServerPlayer owner = ownerWithin(16);
        if (owner == null || !(owner.getVehicle() instanceof AbstractBoat boat) || boat.getControllingPassenger() != owner) return;
        if (boat.getPassengers().size() != 1) return;
        if (distanceToSqr(boat) < 9) {
            if (startRiding(boat)) {
                boatLastX = boat.getX();
                boatLastZ = boat.getZ();
                boatSpeed = 0;
                hold("boat_sit");
                CreatureSfx.play(this, "happy");
            } else {
                boardReadyAt = now + 100;   // a chest boat, or no room
            }
        } else if (getNavigation().isDone()) {
            getNavigation().moveTo(boat, 1.2);
        }
    }

    @Override
    public void stopRiding() {
        boolean boat = getVehicle() instanceof AbstractBoat;
        super.stopRiding();
        if (boat && !level().isClientSide()) {
            if (holdClip().startsWith("boat_")) hold("");
            boardReadyAt = level().getGameTime() + 60;
        }
    }

    // ------------------------------------------------------------------ boats: wild pushers

    private @Nullable AbstractBoat playerBoatNear(ServerLevel level, double r) {
        List<AbstractBoat> boats = level.getEntitiesOfClass(AbstractBoat.class, getBoundingBox().inflate(r),
                b -> b.isAlive() && b.getControllingPassenger() instanceof Player && b.isInWater() && habitat().contains(ArcanaSpawner.biomeId(level, b.blockPosition())));
        return boats.stream().min(Comparator.comparingDouble(b -> b.distanceToSqr(this))).orElse(null);
    }

    private void pushTick(ServerLevel level, long now, int t) {
        int id = pushing();
        if (id >= 0) {
            AbstractBoat boat = level.getEntity(id) instanceof AbstractBoat b ? b : null;
            boolean ok = boat != null && boat.isAlive() && boat.getControllingPassenger() instanceof Player && boat.isInWater()
                    && now < pushUntil && distanceToSqr(boat) < 20 * 20 && !isLeashed() && buddy == null;
            if (ok && t % 10 == 0) ok = habitat().contains(ArcanaSpawner.biomeId(level, boat.blockPosition())) && (isInWater() || distanceToSqr(boat) < 36);
            if (!ok) {
                stopPushing(now);
                return;
            }
            steer(boat, t);
            return;
        }
        if (t % 20 != 0 || now < pushRestUntil || !isInWater() || buddy != null || isLeashed()) return;
        AbstractBoat boat = playerBoatNear(level, PUSH_NOTICE);
        if (boat == null || countPushing(level, boat) >= PenguinBoats.WILD_CAP || random.nextDouble() >= PUSH_JOIN_CHANCE) return;
        startPushing(boat, now);
    }

    /** Penguins sent to push this boat (wherever they are), so the cap holds even while they swim over. */
    public static int countPushing(ServerLevel level, AbstractBoat boat) {
        return level.getEntitiesOfClass(PenguinCreature.class, boat.getBoundingBox().inflate(24), p -> p.pushing() == boat.getId()).size();
    }

    /** Starts pushing this boat (the server's decision; game tests call it directly). */
    public void startPushing(AbstractBoat boat, long now) {
        entityData.set(DATA_PUSH, boat.getId());
        pushSlot = -1;
        pushUntil = now + PUSH_MIN + random.nextInt(PUSH_MAX - PUSH_MIN + 1);
        boatLastX = boat.getX();
        boatLastZ = boat.getZ();
        getNavigation().stop();
        CreatureSfx.play(this, "splash");
    }

    private void stopPushing(long now) {
        entityData.set(DATA_PUSH, -1);
        pushSlot = -1;
        pushRestUntil = now + PUSH_REST_MIN + random.nextInt(PUSH_REST_MAX - PUSH_REST_MIN + 1);
        getNavigation().stop();
    }

    /**
     * Swims in its slot around the boat's stern (behind, then the rear corners), matching the boat's speed. The slot
     * (rank by entity id among the boat's pushers) is looked up again every 10 ticks, not every tick; t staggers it
     * across the crew.
     */
    private void steer(AbstractBoat boat, int t) {
        getNavigation().stop();
        if (pushSlot < 0 || t % 10 == 0) {
            // 21: pushTick already stops any pusher 20 blocks or more from the boat
            List<PenguinCreature> crew = level().getEntitiesOfClass(PenguinCreature.class, boat.getBoundingBox().inflate(21), p -> p.pushing() == boat.getId());
            crew.sort(Comparator.comparingInt(PenguinCreature::getId));
            pushSlot = Math.max(0, crew.indexOf(this));
        }
        double[] s = SLOTS[pushSlot % SLOTS.length];
        float yaw = boat.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);       // forward
        double rx = -fz, rz = fx;                            // side
        Vec3 target = new Vec3(boat.getX() + fx * s[1] + rx * s[0], boat.getY() - 0.45, boat.getZ() + fz * s[1] + rz * s[0]);
        Vec3 boatVel = new Vec3(boat.getX() - boatLastX, 0, boat.getZ() - boatLastZ);
        boatLastX = boat.getX();
        boatLastZ = boat.getZ();
        Vec3 v = boatVel.add(target.subtract(position()).scale(0.25));
        double max = 1.1;
        if (v.lengthSqr() > max * max) v = v.normalize().scale(max);
        setDeltaMovement(v);
        float face = (float) (Mth.atan2(fz, fx) * Mth.RAD_TO_DEG) - 90;
        setYRot(face);
        yBodyRot = face;
        yHeadRot = face;
    }

    // ------------------------------------------------------------------ water

    @Override
    protected void travelInWater(Vec3 input, double baseGravity, boolean isFalling, double oldY) {
        moveRelative(getSpeed(), input);
        move(MoverType.SELF, getDeltaMovement());
        Vec3 v = getDeltaMovement().scale(0.9);
        if (getNavigation().isDone() && pushing() < 0 && !isPassenger()) {
            // idle: bob up to float at the surface
            v = isUnderWater() ? v.add(0, 0.012, 0) : new Vec3(v.x, v.y * 0.5, v.z);
        }
        setDeltaMovement(v);
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public int getMaxAirSupply() {
        return 4800;
    }

    // ------------------------------------------------------------------ movement helpers

    /** Swims like a fish in water (pitching up and down after its path, quick), walks like everyone on land. */
    static final class PenguinMoveControl extends MoveControl {
        private final PenguinCreature p;

        PenguinMoveControl(PenguinCreature p) {
            super(p);
            this.p = p;
        }

        @Override
        public void tick() {
            if (!p.isInWater()) {
                if (p.getXRot() != 0) p.setXRot(rotateTowards(p.getXRot(), 0, 10));
                super.tick();
                return;
            }
            if (operation == Operation.MOVE_TO && !p.getNavigation().isDone()) {
                double xd = wantedX - p.getX(), yd = wantedY - p.getY(), zd = wantedZ - p.getZ();
                double dd = xd * xd + yd * yd + zd * zd;
                if (dd < 2.5E-7) {
                    p.setZza(0);
                    return;
                }
                float yRotD = (float) (Mth.atan2(zd, xd) * Mth.RAD_TO_DEG) - 90.0F;
                p.setYRot(rotlerp(p.getYRot(), yRotD, 20));
                p.yBodyRot = p.getYRot();
                p.yHeadRot = p.getYRot();
                float speed = (float) (speedModifier * p.getAttributeValue(Attributes.MOVEMENT_SPEED) * p.cfgD("swim", 2.0) * 0.075);
                p.setSpeed(speed);
                double horiz = Math.sqrt(xd * xd + zd * zd);
                float xRotD = Mth.clamp(-(float) (Mth.atan2(yd, horiz) * Mth.RAD_TO_DEG), -60, 60);
                p.setXRot(rotateTowards(p.getXRot(), xRotD, 8));
                float cos = Mth.cos(p.getXRot() * Mth.DEG_TO_RAD), sin = Mth.sin(p.getXRot() * Mth.DEG_TO_RAD);
                p.setZza(cos);
                p.setYya(-sin);
            } else {
                p.setSpeed(0);
                p.setXxa(0);
                p.setYya(0);
                p.setZza(0);
            }
        }
    }

    /**
     * Ground navigation (FollowOwnerGoal needs a ground or flying navigation) over the amphibious path finder: paths
     * run over land and through water alike.
     */
    static final class PenguinNavigation extends GroundPathNavigation {
        PenguinNavigation(Mob mob, Level level) {
            super(mob, level);
        }

        @Override
        protected PathFinder createPathFinder(int maxVisitedNodes) {
            this.nodeEvaluator = new AmphibiousNodeEvaluator(false);
            this.nodeEvaluator.setCanPassDoors(true);
            return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
        }

        @Override
        protected boolean canUpdatePath() {
            return true;
        }

        @Override
        protected Vec3 getTempMobPos() {
            return new Vec3(mob.getX(), mob.getY(0.5), mob.getZ());
        }

        @Override
        protected double getGroundY(Vec3 target) {
            return target.y;
        }

        @Override
        protected boolean canMoveDirectly(Vec3 startPos, Vec3 stopPos) {
            return mob.isInLiquid() && isClearForMovementBetween(mob, startPos, stopPos, false);
        }

        @Override
        public boolean isStableDestination(BlockPos pos) {
            return !level.getBlockState(pos.below()).isAir();
        }
    }

    /** Wild chicks keep within a few blocks of the nearest grown-up of their family. */
    static final class FollowColonyGoal extends Goal {
        private final PenguinCreature chick;
        private @Nullable PenguinCreature adult;
        private int recalc;

        FollowColonyGoal(PenguinCreature chick) {
            this.chick = chick;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (!chick.chick() || chick.isTame() || chick.isLeashed() || chick.getRandom().nextInt(20) != 0) return false;
            adult = Conditions.nearby(chick, 16, PenguinCreature.class, p -> !p.chick() && p.family().equals(chick.family()) && p.isAlive())
                    .stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(chick))).orElse(null);
            return adult != null && chick.distanceToSqr(adult) > 25;
        }

        @Override
        public boolean canContinueToUse() {
            return adult != null && adult.isAlive() && chick.distanceToSqr(adult) > 6 && chick.distanceToSqr(adult) < 400;
        }

        @Override
        public void start() {
            recalc = 0;
        }

        @Override
        public void tick() {
            if (adult != null && --recalc <= 0) {
                recalc = adjustedTickDelay(10);
                chick.getNavigation().moveTo(adult, 1.1);
            }
        }

        @Override
        public void stop() {
            adult = null;
            chick.getNavigation().stop();
        }
    }
}
