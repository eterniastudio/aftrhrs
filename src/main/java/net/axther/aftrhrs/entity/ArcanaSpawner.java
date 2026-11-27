package net.axther.aftrhrs.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Natural spawning of the wild Arcana creatures (pets, mounts, wildlife, livestock, duskflies, the tameable hostiles):
 * an <b>additive</b> spawner next to vanilla's, like the old MythicMobs random spawns ({@code Action: ADD}). It never
 * replaces, cancels or alters a vanilla spawn, the creatures are not in any biome spawn list, and they neither use nor
 * count toward the vanilla mob cap (see {@code entity.mixin.MobCapMixin}).
 *
 * <p>Every {@code arcana-spawning.interval-ticks} each online player (not spectating) gets {@code attempts} random spots
 * {@code min-distance}..{@code max-distance} blocks away horizontally and within {@code y-range} blocks up or down: the
 * first ground spot (two free blocks over a sturdy top face: surface or cave floor) or water block found scanning down
 * (then up) from a random height. The spot's dimension + biome select the species rules (LAND rules on ground, SEA rules
 * in water); in random order each rule off cooldown rolls its rarity chance ({@code chance.<rarity>}), then has to pass
 * the kind rules (aggressive kinds only at night, bosses / police / undead never, monster kinds need the
 * {@code spawn_monsters} game rule, nothing that Peaceful would remove at once), its conditions, structures and
 * {@code cap} / {@code cap_radius}, the type's {@link SpawnPlacements} position + predicate, room for the hitbox and
 * {@link FeatherEvents#MOB_SPAWN} (claims), exactly as a vanilla natural spawn would. One spawn per spot; the rule then
 * waits {@code cooldown-seconds.<rarity>} for that player. No spawns while {@code local-cap} wild Arcana creatures
 * (untamed, not kept, not leashed) are within {@code local-cap-radius} blocks of the player, or with the
 * {@code spawn_mobs} game rule off. Spawns use {@link EntitySpawnReason#NATURAL}.
 */
public final class ArcanaSpawner {
    private static final String[] RARITIES = {"common", "uncommon", "rare", "very_rare"};
    private static final double[] CHANCE = {0.06, 0.035, 0.012, 0.006};
    private static final int[] COOLDOWN = {60, 150, 600, 1200};
    /** Game tests pause the timer ({@code -Daftrhrs.spawner.paused=true}) so stray creatures stay out of other tests. */
    private static final boolean PAUSED = Boolean.getBoolean("aftrhrs.spawner.paused");

    private static final Map<Species, List<Rule>> RULES = new IdentityHashMap<>();
    /** dimension -> biome -> rules. */
    private static final Map<String, Map<String, List<Rule>>> INDEX = new HashMap<>();
    /** player -> rule -> game time its cooldown ends. */
    private static final Map<UUID, Map<Rule, Long>> COOLDOWNS = new HashMap<>();
    private static boolean done;

    private ArcanaSpawner() {
    }

    /** A compiled spawn rule of one species (identity equality). */
    public static final class Rule {
        public final Species species;
        public final EntityType<? extends Mob> type;
        public final Species.Spawn spawn;
        final Set<String> biomes;
        final SpawnConditions.Cond cond;
        final List<Identifier> structures;
        final Set<String> capTypes;
        final int rarity;

        Rule(Species species, EntityType<? extends Mob> type, Species.Spawn spawn, Set<String> capTypes) {
            this.species = species;
            this.type = type;
            this.spawn = spawn;
            this.biomes = Set.copyOf(spawn.biomes());
            SpawnConditions.Cond c;
            try {
                c = SpawnConditions.compile(spawn.conditions());
            } catch (IllegalArgumentException e) {
                AftrHrs.LOG.warn("Arcana spawns: {} rule {} has a condition the mod can't evaluate ({}); the rule never spawns",
                        species.id, spawn.biomes().isEmpty() ? "?" : spawn.biomes().getFirst(), e.getMessage());
                c = SpawnConditions.Cond.FALSE;
            }
            this.cond = c;
            List<Identifier> st = new ArrayList<>();
            for (String s : spawn.structures()) {
                Identifier id = Identifier.tryParse(s);
                if (id != null) st.add(id);
            }
            this.structures = List.copyOf(st);
            this.capTypes = capTypes;
            this.rarity = rarityIndex(spawn.rarity());
        }

        /** The rule's conditions, structures and cap at this spot (dimension and biome not checked). */
        public boolean allows(ServerLevel level, BlockPos pos) {
            if (!cond.test(level, pos)) return false;
            if (!structures.isEmpty() && !inStructure(level, pos)) return false;
            return spawn.cap() < 0 || !capped(level, pos);
        }

        /** Dimension and biome. */
        public boolean covers(ServerLevel level, BlockPos pos) {
            return level.dimension().identifier().toString().equals(spawn.dimension()) && biomes.contains(biomeId(level, pos));
        }

        private boolean inStructure(ServerLevel level, BlockPos pos) {
            var reg = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            for (Identifier id : structures) {
                Structure s = reg.getValue(id);
                if (s != null && level.structureManager().getStructureAt(pos, s).isValid()) return true;
            }
            return false;
        }

        /** MythicMobs {@code mobsinradius{types=...;amount=0to<cap>;radius=<cap_radius>}}. */
        private boolean capped(ServerLevel level, BlockPos pos) {
            AABB box = new AABB(pos).inflate(spawn.capRadius());
            int n = level.getEntitiesOfClass(Mob.class, box, m -> m instanceof ArcanaMob am && capTypes.contains(am.species().mythicId)).size();
            return n > spawn.cap();
        }
    }

    // ------------------------------------------------------------------ setup

    /** Compiles a species' rules (ArcanaEntities, once per species at init). Undead, bosses and the police get none. */
    static void add(Species s, EntityType<? extends Mob> type) {
        if (s.spawns.isEmpty()) return;
        Set<String> capTypes = new HashSet<>();
        capTypes.add(s.mythicId);
        var arr = s.json.getAsJsonArray("spawns");
        if (arr != null) {
            for (var e : arr) {
                var o = e.getAsJsonObject();
                if (o.has("cap_types")) o.getAsJsonArray("cap_types").forEach(x -> capTypes.add(x.getAsString()));
            }
        }
        List<Rule> rules = new ArrayList<>();
        for (Species.Spawn sp : s.spawns) {
            Rule r = new Rule(s, type, sp, Set.copyOf(capTypes));
            rules.add(r);
            boolean spawner = s.kind != Species.Kind.UNDEAD && s.kind != Species.Kind.BOSS && s.kind != Species.Kind.POLICE;
            if (!spawner) continue;
            Map<String, List<Rule>> byBiome = INDEX.computeIfAbsent(sp.dimension(), k -> new HashMap<>());
            for (String b : r.biomes) byBiome.computeIfAbsent(b, k -> new ArrayList<>()).add(r);
        }
        RULES.put(s, List.copyOf(rules));
    }

    static void init() {
        if (done) return;
        done = true;
        ServerTickEvents.END_SERVER_TICK.register(ArcanaSpawner::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> COOLDOWNS.remove(handler.getPlayer().getUUID()));
        int n = 0;
        for (var byBiome : INDEX.values()) for (var l : byBiome.values()) n += l.size();
        AftrHrs.LOG.info("Arcana spawns: {} species with natural spawn rules ({} biome entries), additive spawner{}", RULES.size(), n,
                PAUSED ? " paused (-Daftrhrs.spawner.paused)" : "");
    }

    /** The species' compiled rules (empty if it has none). */
    public static List<Rule> rules(Species s) {
        return RULES.getOrDefault(s, List.of());
    }

    /** The rules the spawner runs at a biome of a dimension (ids like {@code minecraft:meadow}). */
    public static List<Rule> rulesAt(String dimension, String biome) {
        Map<String, List<Rule>> byBiome = INDEX.get(dimension);
        List<Rule> l = byBiome == null ? null : byBiome.get(biome);
        return l == null ? List.of() : Collections.unmodifiableList(l);
    }

    static String biomeId(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("");
    }

    private static int rarityIndex(String rarity) {
        for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equals(rarity)) return i;
        return 1;
    }

    // ------------------------------------------------------------------ settings (config.yml arcana-spawning)

    /**
     * The spawner's settings, read from {@code config.yml arcana-spawning} (defaults when Feather isn't up). Parsed once
     * per config object: server start and {@code /feather reload} load a new one, which is when they change. Server
     * thread only; the arrays are never written after parsing.
     */
    public record Settings(boolean enabled, int interval, int attempts, int minDistance, int maxDistance, int yRange, int localCap,
                           double localCapRadius, double[] chance, int[] cooldownSeconds) {
        /** The settings and the config object they were parsed from ({@code from} null = no Feather: the defaults). */
        private record Memo(@Nullable Yaml from, Settings settings) {
        }

        private static volatile @Nullable Memo memo;

        public static Settings read() {
            Feather f = Feather.get();
            Yaml c = f == null ? null : f.getConfig();
            Memo m = memo;
            if (m != null && m.from() == c) return m.settings();
            Settings s = parse(c == null ? new Yaml() : c);
            memo = new Memo(c, s);
            return s;
        }

        private static Settings parse(Yaml c) {
            String k = "arcana-spawning.";
            double[] chance = new double[RARITIES.length];
            int[] cooldown = new int[RARITIES.length];
            for (int i = 0; i < RARITIES.length; i++) {
                chance[i] = Mth.clamp(c.getDouble(k + "chance." + RARITIES[i], CHANCE[i]), 0, 1);
                cooldown[i] = Math.max(0, c.getInt(k + "cooldown-seconds." + RARITIES[i], COOLDOWN[i]));
            }
            int min = Math.max(0, c.getInt(k + "min-distance", 12));
            return new Settings(c.getBoolean(k + "enabled", true), Math.max(1, c.getInt(k + "interval-ticks", 20)),
                    Math.max(0, c.getInt(k + "attempts", 2)), min, Math.max(min + 1, c.getInt(k + "max-distance", 64)),
                    Math.max(0, c.getInt(k + "y-range", 16)), Math.max(0, c.getInt(k + "local-cap", 8)),
                    Math.max(1, c.getDouble(k + "local-cap-radius", 64)), chance, cooldown);
        }

        double chance(Rule r) {
            return chance[r.rarity];
        }

        long cooldownTicks(Rule r) {
            return cooldownSeconds[r.rarity] * 20L;
        }
    }

    // ------------------------------------------------------------------ the timer

    private static void tick(MinecraftServer server) {
        if (PAUSED || INDEX.isEmpty()) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        Settings st = Settings.read();
        if (!st.enabled() || st.attempts() == 0) return;
        int tick = server.getTickCount();
        for (ServerPlayer p : List.copyOf(players)) {
            if (Math.floorMod(tick + p.getId(), st.interval()) != 0) continue;
            try {
                runFor(p, st);
            } catch (RuntimeException e) {
                AftrHrs.LOG.warn("Arcana spawns: spawn attempt near {} failed", p.getGameProfile().name(), e);
            }
        }
    }

    /** One timer run for a player: {@code attempts} random spots, at most one spawn. */
    static void runFor(ServerPlayer p, Settings st) {
        if (p.isSpectator() || !p.isAlive() || p.hasDisconnected()) return;
        ServerLevel level = p.level();
        if (!level.getGameRules().get(GameRules.SPAWN_MOBS)) return;
        if (!INDEX.containsKey(level.dimension().identifier().toString())) return;
        if (wildNear(level, p.position(), st.localCapRadius()) >= st.localCap()) return;
        RandomSource random = level.getRandom();
        for (int i = 0; i < st.attempts(); i++) {
            Spot spot = findSpot(level, p, st, random);
            if (spot != null && attempt(level, p, spot.pos(), spot.water(), st, false, null) != null) return;
        }
    }

    record Spot(BlockPos pos, boolean water) {
    }

    /** A random ground / water spot 12..64 blocks from the player (null: none in that column, or not loaded / too close). */
    static @Nullable Spot findSpot(ServerLevel level, ServerPlayer p, Settings st, RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double min = st.minDistance(), max = st.maxDistance();
        double d = Math.sqrt(min * min + random.nextDouble() * (max * max - min * min));   // uniform over the ring's area
        int x = Mth.floor(p.getX() + Math.cos(angle) * d), z = Mth.floor(p.getZ() + Math.sin(angle) * d);
        int py = p.getBlockY();
        BlockPos column = new BlockPos(x, py, z);
        if (!level.hasChunkAt(column) || !level.canSpawnEntitiesInChunk(ChunkPos.containing(column))) return null;
        int lo = Math.max(level.getMinY() + 1, py - st.yRange()), hi = Math.min(level.getMaxY() - 2, py + st.yRange());
        if (hi < lo) return null;
        int y0 = lo + random.nextInt(hi - lo + 1);
        Spot spot = null;
        for (int y = y0; y >= lo && spot == null; y--) spot = spotAt(level, x, y, z);
        for (int y = y0 + 1; y <= hi && spot == null; y++) spot = spotAt(level, x, y, z);
        if (spot == null) return null;
        Vec3 c = Vec3.atBottomCenterOf(spot.pos());
        // vanilla's rules for natural spawns: nobody closer than the minimum, not right at the world spawn
        if (level.getNearestPlayer(c.x, c.y, c.z, min, EntitySelector.NO_SPECTATORS) != null) return null;
        var respawn = level.getRespawnData();
        if (respawn.dimension() == level.dimension() && respawn.pos().closerToCenterThan(c, 24.0)) return null;
        return spot;
    }

    private static @Nullable Spot spotAt(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState at = level.getBlockState(pos);
        FluidState fluid = at.getFluidState();
        if (fluid.is(FluidTags.WATER)) return new Spot(pos, true);
        if (!fluid.isEmpty() || !at.getCollisionShape(level, pos).isEmpty()) return null;
        BlockPos above = pos.above();
        BlockState up = level.getBlockState(above);
        if (!up.getCollisionShape(level, above).isEmpty() || !up.getFluidState().isEmpty()) return null;
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) ? new Spot(pos, false) : null;
    }

    /**
     * Tries the rules of the spot's biome in random order; spawns at most one creature. {@code forced}: ignore rarity
     * chances and cooldowns (the game tests and staff probes); {@code only}: restrict to these species.
     */
    static @Nullable Mob attempt(ServerLevel level, ServerPlayer p, BlockPos pos, boolean water, Settings st, boolean forced,
                                 @Nullable Predicate<Species> only) {
        List<Rule> rules = rulesAt(level.dimension().identifier().toString(), biomeId(level, pos));
        if (rules.isEmpty()) return null;
        RandomSource random = level.getRandom();
        long now = level.getGameTime();
        Map<Rule, Long> cooldowns = COOLDOWNS.computeIfAbsent(p.getUUID(), k -> new IdentityHashMap<>());
        boolean monsters = level.isSpawningMonsters();
        boolean peaceful = level.getDifficulty() == Difficulty.PEACEFUL;
        int n = rules.size(), start = random.nextInt(n);
        for (int k = 0; k < n; k++) {
            Rule r = rules.get((start + k) % n);
            if (r.spawn.water() != water) continue;
            if (only != null && !only.test(r.species)) continue;
            if (!forced) {
                Long until = cooldowns.get(r);
                if (until != null && until > now) continue;
                if (random.nextDouble() >= st.chance(r)) continue;
            }
            if (!kindAllowed(r, level, monsters, peaceful) || !r.allows(level, pos)) continue;
            Mob mob = spawn(level, r, pos, random);
            if (mob == null) continue;
            cooldowns.put(r, now + st.cooldownTicks(r));
            return mob;
        }
        return null;
    }

    /**
     * Staff / game-test probe: one attempt at this spot as the timer would do it for {@code p} (local cap included),
     * with rarity chances and cooldowns ignored. Returns the spawned creature or null.
     */
    public static @Nullable Mob probe(ServerPlayer p, BlockPos pos, @Nullable Predicate<Species> only) {
        Settings st = Settings.read();
        ServerLevel level = p.level();
        if (wildNear(level, p.position(), st.localCapRadius()) >= st.localCap()) return null;
        boolean water = level.getFluidState(pos).is(FluidTags.WATER);
        return attempt(level, p, pos, water, st, true, only);
    }

    /** The kind rules: aggressive only at night; bosses, police and undead (replacement only) never; what Peaceful removes, not. */
    static boolean kindAllowed(Rule r, ServerLevel level, boolean monsters, boolean peaceful) {
        Species s = r.species;
        if (s.kind == Species.Kind.BOSS || s.kind == Species.Kind.POLICE || s.kind == Species.Kind.UNDEAD) return false;
        if (ArcanaEntities.aggressive(s) && !ArcanaEntities.nightForSpawns(level)) return false;
        if (!r.type.getCategory().isFriendly() && !monsters) return false;
        // a wild hostile (ArcanaCreature.checkDespawn) or a not-in-peaceful type would be removed on the next tick
        return !peaceful || r.type.isAllowedInPeaceful() && !s.hostile();
    }

    private static @Nullable Mob spawn(ServerLevel level, Rule r, BlockPos pos, RandomSource random) {
        EntityType<? extends Mob> type = r.type;
        if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)) return null;
        if (!SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, pos, random)) return null;
        double x = pos.getX() + 0.5, z = pos.getZ() + 0.5;
        if (!level.noCollision(type.getSpawnAABB(x, pos.getY(), z))) return null;
        Mob mob = type.create(level, EntitySpawnReason.NATURAL);
        if (mob == null) return null;
        mob.snapTo(x, pos.getY(), z, random.nextFloat() * 360.0F, 0.0F);
        if (!mob.checkSpawnObstruction(level)) return null;
        if (!FeatherEvents.MOB_SPAWN.invoker().allow(level, mob, EntitySpawnReason.NATURAL)) return null;
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.NATURAL, null);
        level.addFreshEntityWithPassengers(mob);
        return mob;
    }

    // ------------------------------------------------------------------ the local cap

    /** Wild Arcana creatures (the spawner's kinds: untamed, not kept, not leashed, not persistent) within r blocks. */
    public static int wildNear(ServerLevel level, Vec3 at, double r) {
        double r2 = r * r;
        return level.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(r), e -> isWild(e) && e.distanceToSqr(at) <= r2).size();
    }

    /** A wild creature of the kinds this spawner makes (what the local cap counts). */
    public static boolean isWild(Entity e) {
        if (!(e instanceof Mob m) || !m.isAlive() || m.isPersistenceRequired() || m.isLeashed()) return false;
        if (e instanceof ArcanaCreature c) return !c.isTame() && !c.species().eggOnly();
        if (e instanceof ArcanaWildlife w) return !w.kept();
        return false;
    }
}
