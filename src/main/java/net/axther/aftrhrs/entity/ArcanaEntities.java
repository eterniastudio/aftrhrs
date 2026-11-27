package net.axther.aftrhrs.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Registers every Arcana creature from the species table ({@code data/aftrhrs/species}): entity types, default
 * attributes, spawn eggs, natural spawns ({@link ArcanaSpawner}, additive and outside the vanilla mob cap; spawn
 * predicates for the MythicMobs conditions, Terralith biomes included) and the undead's share of vanilla zombie /
 * skeleton / spider / drowned / husk spawns ({@link #naturalReplacement}).
 *
 * <p>Lookup: {@link #type(String)} by entity id ({@code emberkit}), {@link #byMythicId(String)} by MythicMobs id
 * ({@code ArcanaEmberkit}), for migration and configs that still name the MythicMobs types.
 */
public final class ArcanaEntities {
    private static final Map<EntityType<?>, Species> SPECIES = new LinkedHashMap<>();
    private static final Map<String, EntityType<?>> BY_ID = new LinkedHashMap<>();
    private static final Map<String, EntityType<?>> BY_MYTHIC = new LinkedHashMap<>();
    private static final Map<String, Item> EGGS = new LinkedHashMap<>();
    private static boolean done;

    private ArcanaEntities() {
    }

    public static void init() {
        if (done) return;
        done = true;
        AnimDebug.init();
        List<Species> all = Species.loadAll();
        for (Species s : all) register(s);
        CreatureSfx.register(all);
        Abilities.init(all);
        for (Species s : all) spawns(s);
        ArcanaSpawner.init();
        FeatherEvents.NATURAL_REPLACE.register(ArcanaEntities::naturalReplacement);
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS).register(out -> EGGS.values().forEach(out::accept));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer p && source.getEntity() instanceof LivingEntity attacker && !blocked) Abilities.ownerHurt(p, attacker);
        });
        CreatureDebug.init();
        AftrHrs.LOG.info("Arcana creatures: {} entity types registered", SPECIES.size());
    }

    // ------------------------------------------------------------------ lookup

    public static Species species(EntityType<?> type) {
        Species s = SPECIES.get(type);
        if (s == null) throw new IllegalStateException("Not an Arcana creature: " + type);
        return s;
    }

    public static @Nullable EntityType<?> type(String id) {
        return BY_ID.get(id.toLowerCase(Locale.ROOT).replace("aftrhrs:", ""));
    }

    /** The entity type for a MythicMobs id such as {@code ArcanaEmberkit} (null if unknown). */
    public static @Nullable EntityType<?> byMythicId(@Nullable String mythicId) {
        return mythicId == null ? null : BY_MYTHIC.get(mythicId);
    }

    /** MythicMobs id -> entity type, for migration of Paper data. */
    public static Map<String, EntityType<?>> mythicIds() {
        return Collections.unmodifiableMap(BY_MYTHIC);
    }

    public static Map<EntityType<?>, Species> all() {
        return Collections.unmodifiableMap(SPECIES);
    }

    public static @Nullable Item spawnEgg(String id) {
        return EGGS.get(id);
    }

    // ------------------------------------------------------------------ registration

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(Species s) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, s.entityId());
        MobCategory category = category(s);
        EntityType.Builder<? extends Mob> b = switch (s.kind) {
            case MOUNT -> EntityType.Builder.of(ArcanaMount::new, category);
            // the Pockie (spec "fishbowl"): an aquatic pet that rolls about land in its own fishbowl
            case PET, HOSTILE -> s.json.has("fishbowl") ? EntityType.Builder.of(FishbowlCreature::new, category)
                    // the penguins (spec "penguin"): amphibious, colonies, boat pushers, raised from chicks
                    : s.json.has("penguin") ? EntityType.Builder.of(PenguinCreature::new, category)
                    : EntityType.Builder.of(ArcanaCreature::new, category);
            case WILDLIFE, LIVESTOCK, PARTICLES -> EntityType.Builder.of(ArcanaWildlife::new, category);
            case UNDEAD, BOSS, POLICE -> EntityType.Builder.of(ArcanaMonster::new, category);
        };
        b.sized(s.width, s.height).eyeHeight(Math.min(s.eye, s.height)).clientTrackingRange(s.kind == Species.Kind.BOSS ? 16 : 10)
                .nameTagOffset(Math.max(s.nameTagY, s.height + 0.1f)).noLootTable();
        if (s.damageMods.getOrDefault("FIRE", 1f) == 0 && s.damageMods.getOrDefault("LAVA", 1f) == 0) b.fireImmune();
        // tameable hostiles (Cinder Imp, Volt Wisp ...) stay allowed: vanilla deletes a not-in-peaceful type on Peaceful
        // (checkDespawn, and chunk loads skip it), which took tamed pets with it; their wild ones go in ArcanaCreature.checkDespawn
        if (s.kind == Species.Kind.UNDEAD || s.kind == Species.Kind.BOSS || s.hostile() && s.untameable()) b.notInPeaceful();
        EntityType<?> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, ((EntityType.Builder) b).build(key));
        SPECIES.put(type, s);
        BY_ID.put(s.id, type);
        BY_MYTHIC.put(s.mythicId, type);
        FabricDefaultAttributeRegistry.register((EntityType<? extends LivingEntity>) type, attributes(s));

        ResourceKey<Item> eggKey = ResourceKey.create(Registries.ITEM, AftrHrs.id(s.id + "_spawn_egg"));
        Item egg = Registry.register(BuiltInRegistries.ITEM, eggKey, new SpawnEggItem(new Item.Properties().spawnEgg(type).setId(eggKey)));
        EGGS.put(s.id, egg);
    }

    private static MobCategory category(Species s) {
        return switch (s.kind) {
            case UNDEAD, BOSS, HOSTILE -> s.aquatic() != null ? MobCategory.WATER_CREATURE : MobCategory.MONSTER;
            case PARTICLES -> MobCategory.AMBIENT;
            case POLICE -> MobCategory.MISC;
            default -> s.aquatic() != null ? MobCategory.WATER_CREATURE : MobCategory.CREATURE;
        };
    }

    private static AttributeSupplier.Builder attributes(Species s) {
        AttributeSupplier.Builder a = switch (s.kind) {
            case UNDEAD, BOSS, POLICE -> Monster.createMonsterAttributes();
            default -> Animal.createAnimalAttributes().add(Attributes.ATTACK_DAMAGE);
        };
        a.add(Attributes.MAX_HEALTH, s.health())
                .add(Attributes.MOVEMENT_SPEED, s.speed())
                .add(Attributes.FOLLOW_RANGE, Math.max(16, s.followRange()))
                .add(Attributes.ATTACK_DAMAGE, Math.max(1, s.damage()))
                .add(Attributes.FLYING_SPEED, Math.max(0.3, s.speed() * 1.5))
                .add(Attributes.ARMOR, Species.dbl(s.json, "armor", 0))
                .add(Attributes.KNOCKBACK_RESISTANCE, s.mount() ? 1 : Species.dbl(s.json, "knockback_resistance", 0));
        var m = s.mountData();
        if (m != null) {
            a.add(Attributes.JUMP_STRENGTH, Species.dbl(m, "jump_strength", 0.5));
            a.add(Attributes.STEP_HEIGHT, Species.dbl(m, "step", 1.0));
        }
        if (s.kind == Species.Kind.UNDEAD) a.add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0);
        // drowned variants climb out of the water onto banks like the vanilla drowned (step height 1)
        if (s.kind == Species.Kind.UNDEAD && "DROWNED".equalsIgnoreCase(Species.str(s.json, "base", ""))) a.add(Attributes.STEP_HEIGHT, 1.0);
        return a;
    }

    // ------------------------------------------------------------------ natural spawning

    /**
     * Natural spawns: no biome spawn-list entries at all. The non-undead rules run in {@link ArcanaSpawner} (additive,
     * outside the vanilla mob cap); undead variants replace a share of vanilla's natural zombie / skeleton spawns
     * ({@link #naturalReplacement}). The {@link SpawnPlacements} predicate stays, so spawn eggs, commands and both
     * spawners agree on where a type may appear.
     */
    @SuppressWarnings("unchecked")
    private static void spawns(Species s) {
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) BY_ID.get(s.id);
        ArcanaSpawner.add(s, type);
        JsonReplace replace = JsonReplace.of(s);
        if (replace != null) {
            EntityType<?> vanilla = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(replace.vanilla));
            REPLACERS.computeIfAbsent(vanilla, k -> new ArrayList<>()).add(new Replacer(s, type, Set.copyOf(replace.biomes), replace.share, replace.moonShare));
        }
        boolean water = s.aquatic() != null;
        SpawnPlacements.register(type, water ? SpawnPlacementTypes.IN_WATER : s.kind == Species.Kind.PARTICLES ? SpawnPlacementTypes.NO_RESTRICTIONS
                        : SpawnPlacementTypes.ON_GROUND,
                water ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (t, level, reason, pos, random) -> canSpawn(s, level, reason, pos, random));
    }

    /**
     * Aggressive creatures: undead, bosses, the tameable hostiles (wild ones hunt players; incl. the Lurefin) and anything
     * flagged hostile. They spawn naturally only at night.
     */
    public static boolean aggressive(Species s) {
        return s.kind == Species.Kind.UNDEAD || s.kind == Species.Kind.BOSS || s.kind == Species.Kind.HOSTILE || s.hostile();
    }

    /** Night (13000..23000) in a dimension with a day cycle; dimensions without one (the Nether) have no daytime. */
    static boolean nightForSpawns(net.minecraft.world.level.Level level) {
        return level.dimensionType().hasFixedTime() || Conditions.night(level);
    }

    /**
     * The spawn predicate. Spawn eggs, commands, spawners, conversions ... (any reason but NATURAL / CHUNK_GENERATION):
     * always. Natural: bosses and the police never, aggressive kinds only at night; undead need vanilla's darkness
     * (they only come from {@link #naturalReplacement}); everything else needs one of its rules to cover the spot
     * (dimension, biome, conditions, structures, cap). The rarity roll and cooldowns are {@link ArcanaSpawner}'s.
     */
    static boolean canSpawn(Species s, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource random) {
        if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION) return true;
        // bosses (altars / boss spawns) and the police (robberies) are never natural spawns
        if (s.kind == Species.Kind.BOSS || s.kind == Species.Kind.POLICE) return false;
        // aggressive mobs only spawn at night, in every biome and whatever the light level (no ice wraiths at noon)
        if (aggressive(s) && !nightForSpawns(level.getLevel())) return false;
        if (s.kind == Species.Kind.UNDEAD) return Monster.isDarkEnoughToSpawn(level, pos, random);
        ServerLevel sl = level.getLevel();
        for (ArcanaSpawner.Rule rule : ArcanaSpawner.rules(s)) {
            if (rule.covers(sl, pos) && rule.allows(sl, pos)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ undead: REPLACE

    private static final Map<EntityType<?>, List<Replacer>> REPLACERS = new java.util.HashMap<>();

    private record Replacer(Species species, EntityType<? extends Mob> type, Set<String> biomes, double share, double moonShare) {
    }

    /** The Restless Moon: the new moon, at night. */
    public static boolean restlessMoon(ServerLevel level, BlockPos pos) {
        return Conditions.night(level) && level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.MOON_PHASE, pos)
                == net.minecraft.world.level.MoonPhase.NEW_MOON;
    }

    /**
     * The undead variant that takes this natural spawn of a vanilla type (zombie, skeleton, spider, drowned, husk ...:
     * any type a variant's {@code replace.vanilla} names; MythicMobs {@code Action: REPLACE}, Reason
     * NATURAL): in one of the variant's biomes, at night (aggressive kinds), with probability {@code share}
     * ({@code moon_share} on the Restless Moon), if its hitbox fits. Returns the new, unplaced mob (vanilla's spawner
     * positions it and runs the rest of its checks), or null to keep vanilla's. Hooked to vanilla's natural spawner only
     * ({@link FeatherEvents#NATURAL_REPLACE}), so spawners, eggs, reinforcements and commands stay vanilla and the total
     * monster count is unchanged.
     */
    public static @Nullable Mob naturalReplacement(ServerLevel level, EntityType<?> vanilla, BlockPos pos) {
        List<Replacer> list = REPLACERS.get(vanilla);
        if (list == null || !nightForSpawns(level)) return null;
        String biome = ArcanaSpawner.biomeId(level, pos);
        for (Replacer r : list) {
            if (!r.biomes().contains(biome)) continue;
            double share = restlessMoon(level, pos) ? r.moonShare() : r.share();
            if (level.getRandom().nextDouble() >= share) return null;
            if (!level.noCollision(r.type().getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) return null;
            return r.type().create(level, EntitySpawnReason.NATURAL);
        }
        return null;
    }

    /** Vanilla's mob cap counts the undead (they stand in for zombies / skeletons) but no other Arcana creature. */
    public static boolean countsTowardMobCap(net.minecraft.world.entity.Entity e) {
        return !(e instanceof ArcanaMob m) || m.species().kind == Species.Kind.UNDEAD;
    }

    /** Spec "replace" (undead biome variants). */
    private record JsonReplace(String vanilla, double share, double moonShare, List<String> biomes) {
        static @Nullable JsonReplace of(Species s) {
            var r = s.section("replace");
            if (r == null) return null;
            List<String> biomes = new ArrayList<>();
            r.getAsJsonArray("biomes").forEach(e -> biomes.add(e.getAsString()));
            return new JsonReplace(r.get("vanilla").getAsString(), Species.dbl(r, "share", 0.4), Species.dbl(r, "moon_share", 0.8), biomes);
        }
    }
}
