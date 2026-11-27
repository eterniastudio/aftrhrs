package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaSpawner;
import net.axther.aftrhrs.entity.SpawnConditions;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Natural spawning: vanilla's biome spawn lists stay vanilla, the additive Arcana spawner (ArcanaSpawner) spawns a
 * species in its biome near a player and keeps to the local cap and the night rule, undead variants replace a share of
 * vanilla's natural zombie spawns one for one, and the spawn-rule conditions parse.
 */
public class SpawnTests {
    private static final MobCategory[] CATEGORIES = {MobCategory.CREATURE, MobCategory.MONSTER, MobCategory.WATER_CREATURE,
            MobCategory.AMBIENT, MobCategory.UNDERGROUND_WATER_CREATURE, MobCategory.WATER_AMBIENT, MobCategory.AXOLOTLS};

    private static boolean arcana(EntityType<?> t) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(t).getNamespace().equals("aftrhrs");
    }

    private static List<String> arcanaEntries(MobSpawnSettings settings, String where) {
        List<String> out = new ArrayList<>();
        for (MobCategory c : CATEGORIES) {
            for (Weighted<MobSpawnSettings.SpawnerData> w : settings.getMobsToSpawn(c).unwrap()) {
                if (arcana(w.value().type())) out.add(where + " " + c.getName() + ": " + w.value().type().toShortString() + " weight " + w.weight());
            }
        }
        return out;
    }

    /** Paints a box (test-relative corners) with a biome, like /fillbiome. */
    private static void paint(GameTestHelper h, BlockPos relFrom, BlockPos relTo, String biome) {
        ServerLevel level = h.getLevel();
        var holder = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome)));
        var r = FillBiomeCommand.fill(level, h.absolutePos(relFrom), h.absolutePos(relTo), holder);
        if (r.right().isPresent() && r.right().get().getType() != FillBiomeCommand.ERROR_NO_BIOMES_SET) throw h.assertionException(net.minecraft.network.chat.Component.literal("fillbiome " + biome + ": " + r.right().get().getMessage()));
    }

    private static Species species(String id) {
        return ArcanaEntities.species(ArcanaEntities.type(id));
    }

    private static Predicate<Species> only(String id) {
        return s -> s.id.equals(id);
    }

    // ------------------------------------------------------------------ vanilla lists

    /** No biome (plains, meadow ... every registered one) has an Arcana creature in its vanilla spawn lists. */
    @GameTest(maxTicks = 20)
    public void biomeSpawnListsStayVanilla(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<String> problems = new ArrayList<>();
        int biomes = 0;
        boolean plains = false, meadow = false;
        for (var b : level.registryAccess().lookupOrThrow(Registries.BIOME).listElements().toList()) {
            String id = b.key().identifier().toString();
            plains |= id.equals("minecraft:plains");
            meadow |= id.equals("minecraft:meadow");
            biomes++;
            problems.addAll(arcanaEntries(b.value().getAttributes().applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY), id));
        }
        // the lists the spawner really reads at a position (dimension attributes included)
        for (String biome : List.of("minecraft:plains", "minecraft:meadow", "minecraft:swamp")) {
            paint(h, new BlockPos(0, 0, 0), new BlockPos(7, 7, 7), biome);
            problems.addAll(arcanaEntries(level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, h.absolutePos(new BlockPos(3, 2, 3))), "at " + biome));
        }
        h.assertTrue(plains && meadow, "plains / meadow not in the biome registry");
        // Terralith isn't loaded in the test server: its biomes are matched by the additive spawner's own index
        h.assertTrue(!ArcanaSpawner.rulesAt("minecraft:overworld", "terralith:highlands").isEmpty(), "no Arcana rules indexed for terralith:highlands");
        h.assertTrue(!ArcanaSpawner.rulesAt("minecraft:overworld", "minecraft:meadow").isEmpty(), "no Arcana rules indexed for meadow");
        for (var r : ArcanaSpawner.rulesAt("minecraft:overworld", "minecraft:swamp")) {
            if (r.species.kind == Species.Kind.UNDEAD) problems.add("undead " + r.species.id + " is in the additive spawner");
        }
        CT.report(h, "biome spawn lists (" + biomes + " biomes)", problems);
        h.succeed();
    }

    // ------------------------------------------------------------------ the additive spawner

    /** A Beagle spawns on open meadow grass near a player (NATURAL, wild); a meadow spot never gives a species of other biomes. */
    @GameTest(maxTicks = 40, padding = 32, skyAccess = true)
    public void spawnerAddsInBiome(GameTestHelper h) {
        CT.arena(h, 36, 8);
        paint(h, new BlockPos(-2, 0, -2), new BlockPos(38, 8, 10), "minecraft:meadow");
        ServerLevel level = h.getLevel();
        long before = level.getDefaultClockTime();
        long day0 = before - Math.floorMod(before, 24000L);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 4));
        List<Entity> made = new ArrayList<>();
        try {
            h.setTime(day0 + 6000);
            BlockPos spot = h.absolutePos(new BlockPos(30, 1, 4));
            Mob beagle = ArcanaSpawner.probe(p, spot, only("beagle"));
            h.assertTrue(beagle != null, "no Beagle spawned on meadow");
            made.add(beagle);
            h.assertTrue(beagle.getType() == ArcanaEntities.type("beagle"), "spawned " + beagle.getType().toShortString());
            h.assertTrue(ArcanaSpawner.isWild(beagle), "the spawned Beagle is not wild");
            h.assertTrue(beagle.blockPosition().equals(spot), "spawned at " + beagle.blockPosition() + ", not " + spot);
            // the snow owlet has no meadow rule
            Mob owlet = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(30, 1, 6)), only("snowpelt_owlet"));
            if (owlet != null) made.add(owlet);
            h.assertTrue(owlet == null, "a Snowpelt Owlet spawned on meadow");
            // any species: something with a meadow rule
            Mob any = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(26, 1, 2)), null);
            h.assertTrue(any != null, "nothing spawned on meadow");
            made.add(any);
            boolean ok = false;
            for (var r : ArcanaSpawner.rules(((ArcanaMob) any).species())) ok |= r.spawn.biomes().contains("minecraft:meadow");
            h.assertTrue(ok, ((ArcanaMob) any).species().id + " has no meadow rule");
        } finally {
            h.setTime(before);
            for (Entity e : made) if (e != null && !e.isRemoved()) e.discard();
            CT.disconnect(p);
        }
        h.succeed();
    }

    /** No spawn while local-cap wild Arcana creatures are near the player; tamed ones don't count. */
    @GameTest(maxTicks = 40, padding = 32, skyAccess = true)
    public void spawnerLocalCap(GameTestHelper h) {
        CT.arena(h, 36, 8);
        paint(h, new BlockPos(-2, 0, -2), new BlockPos(38, 8, 10), "minecraft:meadow");
        ServerLevel level = h.getLevel();
        long before = level.getDefaultClockTime();
        long day0 = before - Math.floorMod(before, 24000L);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 4));
        List<Entity> made = new ArrayList<>();
        int cap = ArcanaSpawner.Settings.read().localCap();
        try {
            h.setTime(day0 + 6000);
            int already = ArcanaSpawner.wildNear(level, p.position(), ArcanaSpawner.Settings.read().localCapRadius());
            for (int i = already; i < cap; i++) made.add(CT.spawn(h, species("tabby_cat"), Vec3.atBottomCenterOf(CT.grid(i, 8, 2))));
            // a tamed pet is no wild creature
            net.axther.aftrhrs.entity.ArcanaCreature pet = CT.spawn(h, species("tabby_cat"), new Vec3(4.5, 1, 7.5));
            pet.tamedBy(p);
            made.add(pet);
            BlockPos spot = h.absolutePos(new BlockPos(30, 1, 4));
            Mob over = ArcanaSpawner.probe(p, spot, only("beagle"));
            if (over != null) made.add(over);
            h.assertTrue(over == null, "spawned past the local cap of " + cap);
            made.getFirst().discard();
            Mob under = ArcanaSpawner.probe(p, spot, only("beagle"));
            if (under != null) made.add(under);
            h.assertTrue(under != null, "no spawn one below the local cap of " + cap);
        } finally {
            h.setTime(before);
            for (Entity e : made) if (e != null && !e.isRemoved()) e.discard();
            CT.disconnect(p);
        }
        h.succeed();
    }

    /** An aggressive kind (Cinder Imp) never spawns by day, and does at night in its biome. */
    @GameTest(maxTicks = 40, padding = 32, skyAccess = true)
    public void spawnerNightOnly(GameTestHelper h) {
        Species imp = species("cinder_imp");
        h.assertTrue(ArcanaEntities.aggressive(imp), "cinder_imp is not aggressive");
        String biome = imp.spawns.stream().filter(r -> r.dimension().equals("minecraft:overworld") && !r.water())
                .flatMap(r -> r.biomes().stream()).filter(b -> b.startsWith("minecraft:")).findFirst().orElseThrow();
        CT.arena(h, 36, 8);
        paint(h, new BlockPos(-2, 0, -2), new BlockPos(38, 8, 10), biome);
        ServerLevel level = h.getLevel();
        long before = level.getDefaultClockTime();
        long day0 = before - Math.floorMod(before, 24000L);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 4));
        List<Entity> made = new ArrayList<>();
        try {
            h.setTime(day0 + 6000);
            for (int i = 0; i < 20; i++) {
                Mob m = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(30, 1, 4)), only("cinder_imp"));
                if (m != null) {
                    made.add(m);
                    throw h.assertionException(net.minecraft.network.chat.Component.literal("a Cinder Imp spawned at noon in " + biome));
                }
            }
            h.setTime(day0 + 18000);
            Mob m = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(30, 1, 4)), only("cinder_imp"));
            if (m != null) made.add(m);
            h.assertTrue(m != null, "no Cinder Imp at midnight in " + biome);
        } finally {
            h.setTime(before);
            for (Entity e : made) if (e != null && !e.isRemoved()) e.discard();
            CT.disconnect(p);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ undead: REPLACE

    /** Mirewalker takes about `share` of the natural zombie spawns in a swamp at night, none by day, none of skeletons or elsewhere. */
    @GameTest(maxTicks = 20)
    public void undeadReplaceShare(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Species mire = species("mirewalker");
        double share = mire.section("replace").get("share").getAsDouble(), moonShare = mire.section("replace").get("moon_share").getAsDouble();
        paint(h, new BlockPos(0, 0, 0), new BlockPos(7, 7, 7), "minecraft:swamp");
        BlockPos pos = h.absolutePos(new BlockPos(3, 1, 3));
        long before = level.getDefaultClockTime();
        long day0 = before - Math.floorMod(before, 24000L);
        int n = 4000, hits = 0, skel = 0, dayHits = 0;
        double want;
        try {
            h.setTime(day0 + 18000);
            want = ArcanaEntities.restlessMoon(level, pos) ? moonShare : share;
            for (int i = 0; i < n; i++) {
                Mob m = ArcanaEntities.naturalReplacement(level, EntityTypes.ZOMBIE, pos);
                if (m != null) {
                    hits++;
                    h.assertTrue(m.getType() == ArcanaEntities.type("mirewalker"), "replaced by " + m.getType().toShortString());
                }
                if (ArcanaEntities.naturalReplacement(level, EntityTypes.SKELETON, pos) != null) skel++;
            }
            h.setTime(day0 + 6000);
            for (int i = 0; i < 500; i++) if (ArcanaEntities.naturalReplacement(level, EntityTypes.ZOMBIE, pos) != null) dayHits++;
        } finally {
            h.setTime(before);
        }
        double got = hits / (double) n;
        h.assertTrue(Math.abs(got - want) < 0.04, String.format("share %.3f, want %.2f", got, want));
        h.assertTrue(skel == 0, skel + " skeletons became Mirewalkers");
        h.assertTrue(dayHits == 0, dayHits + " daytime replacements");
        paint(h, new BlockPos(0, 0, 0), new BlockPos(7, 7, 7), "minecraft:plains");
        h.setTime(day0 + 18000);
        int plains = 0;
        try {
            for (int i = 0; i < 500; i++) if (ArcanaEntities.naturalReplacement(level, EntityTypes.ZOMBIE, pos) != null) plains++;
        } finally {
            h.setTime(before);
        }
        h.assertTrue(plains == 0, plains + " replacements in plains");
        h.succeed();
    }

    /**
     * End to end through vanilla's natural spawner in a dark swamp box at night: zombies still spawn, about `share` of
     * them come out as Mirewalkers, and the zombie family's part of all monsters stays near vanilla's (one for one, no
     * extra weight).
     */
    @GameTest(maxTicks = 200, padding = 48)
    public void undeadReplaceThroughVanilla(GameTestHelper h) {
        CT.arena(h, 64, 24);
        ServerLevel level = h.getLevel();
        var stone = Blocks.STONE.defaultBlockState();
        // a sealed box x 30..62, z -1..25, floor y 0, interior y 1..4, roof y 5
        for (int x = 30; x <= 62; x++) {
            for (int z = -1; z <= 25; z++) {
                boolean wall = x == 30 || x == 62 || z == -1 || z == 25;
                for (int y = 1; y <= 5; y++) {
                    BlockPos at = h.absolutePos(new BlockPos(x, y, z));
                    level.setBlock(at, wall || y == 5 ? stone : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        paint(h, new BlockPos(28, 0, -3), new BlockPos(64, 8, 27), "minecraft:swamp");
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 12));
        h.runAfterDelay(40, () -> {
            Species mire = species("mirewalker");
            EntityType<?> mireType = ArcanaEntities.type("mirewalker");
            BlockPos probe = h.absolutePos(new BlockPos(46, 1, 12));
            long before = level.getDefaultClockTime();
            long day0 = before - Math.floorMod(before, 24000L);
            int zombies = 0, variants = 0, others = 0;
            double expected, want;
            try {
                h.setTime(day0 + 18000);
                level.updateSkyBrightness();
                h.assertTrue(level.getBrightness(LightLayer.SKY, probe) == 0 && level.getBrightness(LightLayer.BLOCK, probe) == 0,
                        "the box is not dark: sky " + level.getBrightness(LightLayer.SKY, probe) + ", block " + level.getBrightness(LightLayer.BLOCK, probe));
                want = ArcanaEntities.restlessMoon(level, probe) ? mire.section("replace").get("moon_share").getAsDouble() : mire.section("replace").get("share").getAsDouble();
                // vanilla's weight share of zombies among the swamp monsters
                int total = 0, zw = 0;
                for (var w : level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, probe).getMobsToSpawn(MobCategory.MONSTER).unwrap()) {
                    total += w.weight();
                    if (w.value().type() == EntityTypes.ZOMBIE) zw += w.weight();
                }
                expected = total == 0 ? 0 : zw / (double) total;
                List<Mob> spawned = new ArrayList<>();
                var random = level.getRandom();
                var chunk = level.getChunk(probe);
                for (int i = 0; i < 400; i++) {
                    BlockPos start = h.absolutePos(new BlockPos(35 + random.nextInt(23), 1, 4 + random.nextInt(17)));
                    NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, level.getChunk(start), start, (t, l, b, c) -> true,
                            (mob, c) -> spawned.add(mob));
                    for (Mob m : spawned) {
                        if (m.getType() == EntityTypes.ZOMBIE) zombies++;
                        else if (m.getType() == mireType) variants++;
                        else others++;
                        m.discard();
                    }
                    spawned.clear();
                }
                h.assertTrue(chunk != null, "no chunk");
            } finally {
                h.setTime(before);
                level.updateSkyBrightness();
                CT.disconnect(p);
            }
            int family = zombies + variants, all = family + others;
            String stats = String.format("zombies %d, mirewalkers %d, other monsters %d; vanilla zombie weight share %.2f, want variant share %.2f",
                    zombies, variants, others, expected, want);
            net.axther.aftrhrs.AftrHrs.LOG.info("[bt-creatures] undead replace through vanilla: {}", stats);
            h.assertTrue(family >= 60, "too few zombie-family spawns: " + stats);
            h.assertTrue(zombies > 0 && variants > 0, "zombies and Mirewalkers should both spawn: " + stats);
            double v = variants / (double) family;
            h.assertTrue(Math.abs(v - want) < 0.15, String.format("Mirewalker share of zombie spawns %.2f: %s", v, stats));
            double fam = family / (double) all;
            h.assertTrue(fam < expected * 2 + 0.05, String.format("zombie family is %.2f of all monsters (vanilla weight share %.2f): %s", fam, expected, stats));
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ claims

    /** The box the early-claim-check test counts in (absolute, inclusive), or null while that test isn't running. */
    private static volatile int[] countBox;
    private static final java.util.concurrent.atomic.AtomicInteger spotsPassed = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger replaceCalls = new java.util.concurrent.atomic.AtomicInteger();
    private static boolean countersRegistered;

    private static boolean inCountBox(BlockPos pos) {
        int[] b = countBox;
        return b != null && pos.getX() >= b[0] && pos.getX() <= b[1] && pos.getZ() >= b[2] && pos.getZ() <= b[3];
    }

    /**
     * Counters registered after the claims listeners: a spot a no-spawn claim refuses never reaches them, and
     * NATURAL_REPLACE fires only once vanilla has picked a mob type (right before it builds the Mob).
     */
    private static void registerCounters() {
        if (countersRegistered) return;
        countersRegistered = true;
        net.axther.aftrhrs.core.event.FeatherEvents.NATURAL_SPAWN_AT.register((level, category, pos) -> {
            if (inCountBox(pos)) spotsPassed.incrementAndGet();
            return true;
        });
        net.axther.aftrhrs.core.event.FeatherEvents.NATURAL_REPLACE.register((level, type, pos) -> {
            if (inCountBox(pos)) replaceCalls.incrementAndGet();
            return null;
        });
    }

    /**
     * A claim with mob spawning off refuses natural monster spots before vanilla picks a type or builds a Mob (no
     * NATURAL_REPLACE call, nothing spawned); with the flag on monsters spawn there again, and animals spawn in the
     * no-spawn claim as before.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @GameTest(maxTicks = 200, padding = 48, environment = "aftrhrs_gametest:econ_claims")
    public void noSpawnClaimsRefuseSpotsEarly(GameTestHelper h) {
        CT.arena(h, 64, 24);
        registerCounters();
        ServerLevel level = h.getLevel();
        var stone = Blocks.STONE.defaultBlockState();
        // a sealed box x 30..62, z -1..25, floor y 0, interior y 1..4, roof y 5
        for (int x = 30; x <= 62; x++) {
            for (int z = -1; z <= 25; z++) {
                boolean wall = x == 30 || x == 62 || z == -1 || z == 25;
                for (int y = 1; y <= 5; y++) {
                    level.setBlock(h.absolutePos(new BlockPos(x, y, z)), wall || y == 5 ? stone : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        paint(h, new BlockPos(28, 0, -3), new BlockPos(64, 8, 27), "minecraft:plains");
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 12));
        h.runAfterDelay(40, () -> {
            Object m = net.axther.aftrhrs.core.Feather.get().module(net.axther.aftrhrs.feature.claims.ClaimsModule.class);
            BlockPos a = h.absolutePos(new BlockPos(29, 0, -2)), b = h.absolutePos(new BlockPos(63, 0, 26));
            Object area = CT.call(CT.cls("net.axther.aftrhrs.feature.claims.Area"), "rect", a.getX(), a.getZ(), b.getX(), b.getZ());
            Object claim = CT.call(m, "createAdmin", net.kyori.adventure.audience.Audience.empty(), level, area, "bt" + Long.toString(System.nanoTime(), 36));
            h.assertTrue(claim != null, "the test claim could not be created");
            Map flags = (Map) CT.get(claim, "flags");
            Object mobSpawning = Enum.valueOf((Class) CT.cls("net.axther.aftrhrs.feature.claims.ClaimFlag"), "MOB_SPAWNING");
            countBox = new int[]{Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getZ(), b.getZ())};
            long before = level.getDefaultClockTime();
            long day0 = before - Math.floorMod(before, 24000L);
            Runnable cleanUp = () -> {
                countBox = null;
                h.setTime(before);
                level.updateSkyBrightness();
                CT.call(CT.get(m, "registry"), "remove", claim);
                CT.call(CT.get(m, "store"), "markDirty");
                CT.disconnect(p);
            };
            String stats;
            try {
                h.setTime(day0 + 18000);
                level.updateSkyBrightness();
                // (a) mob spawning off: nothing reaches a later listener, no type is picked, nothing spawns
                flags.put(mobSpawning, false);
                spotsPassed.set(0);
                replaceCalls.set(0);
                List<String> offMobs = new ArrayList<>();
                int offMonsters = spawnIn(h, MobCategory.MONSTER, 200, offMobs);
                int offSpots = spotsPassed.get(), offReplace = replaceCalls.get();
                // (b) mob spawning on: monsters spawn in the same box
                flags.put(mobSpawning, true);
                spotsPassed.set(0);
                int onMonsters = spawnIn(h, MobCategory.MONSTER, 200, null);
                int onSpots = spotsPassed.get();
                stats = String.format("off: %d monsters %s, %d spots passed, %d types picked; on: %d monsters, %d spots",
                        offMonsters, offMobs, offSpots, offReplace, onMonsters, onSpots);
                h.assertTrue(offMonsters == 0 && offSpots == 0 && offReplace == 0, "a no-spawn claim let the natural spawner get past the spot: " + stats);
                h.assertTrue(onMonsters > 0 && onSpots > 0, "no monsters with mob spawning on: " + stats);
                // (c) animals on lit grass in the no-spawn claim still spawn (once the light engine has caught up)
                flags.put(mobSpawning, false);
                for (int x = 31; x <= 61; x++) {
                    for (int z = 0; z <= 24; z++) {
                        level.setBlock(h.absolutePos(new BlockPos(x, 0, z)), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                        level.setBlock(h.absolutePos(new BlockPos(x, 5, z)), Blocks.GLOWSTONE.defaultBlockState(), 3);
                    }
                }
            } catch (RuntimeException | Error e) {
                cleanUp.run();
                throw e;
            }
            String first = stats;
            h.runAfterDelay(20, () -> {
                try {
                    BlockPos probe = h.absolutePos(new BlockPos(46, 1, 12));
                    List<String> animalsOut = new ArrayList<>();
                    int animals = spawnIn(h, MobCategory.CREATURE, 200, animalsOut);
                    String all = first + "; animals in the no-spawn claim: " + animals + " (light " + level.getMaxLocalRawBrightness(probe) + ")";
                    net.axther.aftrhrs.AftrHrs.LOG.info("[claims] no-spawn claim early check: {}", all);
                    h.assertTrue(animals > 0, "animals stopped spawning in a no-spawn claim: " + all);
                } finally {
                    cleanUp.run();
                }
                h.succeed();
            });
        });
    }

    /** {@code tries} calls of vanilla's natural spawner for {@code category} at random spots in the box: how many mobs came out in the claim (all are removed again). */
    private static int spawnIn(GameTestHelper h, MobCategory category, int tries, List<String> describe) {
        ServerLevel level = h.getLevel();
        List<Mob> spawned = new ArrayList<>();
        var random = level.getRandom();
        int n = 0;
        for (int i = 0; i < tries; i++) {
            BlockPos start = h.absolutePos(new BlockPos(35 + random.nextInt(23), 1, 4 + random.nextInt(17)));
            NaturalSpawner.spawnCategoryForPosition(category, level, level.getChunk(start), start, (t, l, b, c) -> true, (mob, c) -> spawned.add(mob));
            for (Mob mob : spawned) {
                // A group may wander out of the box (and the claim) into the open arena: only mobs in the claim count.
                if (inCountBox(mob.blockPosition())) {
                    n++;
                    if (describe != null && describe.size() < 5) describe.add(mob.getType().toShortString() + " at " + mob.blockPosition().toShortString());
                }
                mob.discard();
            }
            spawned.clear();
        }
        return n;
    }

    // ------------------------------------------------------------------ conditions

    /** Every condition in the species data parses; height, worldtime, the || form, outside / night and negation evaluate right. */
    @GameTest(maxTicks = 20, skyAccess = true)
    public void spawnConditionsParse(GameTestHelper h) {
        List<String> problems = new ArrayList<>();
        int n = 0;
        for (Species s : CT.species(x -> true)) {
            for (Species.Spawn r : s.spawns) {
                for (String c : r.conditions()) {
                    n++;
                    try {
                        SpawnConditions.parse(c);
                    } catch (RuntimeException e) {
                        problems.add(s.id + ": '" + c + "': " + e.getMessage());
                    }
                }
            }
        }
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        var low = SpawnConditions.parse("height{h=<40} true");
        if (!low.test(level, new BlockPos(0, 39, 0)) || low.test(level, new BlockPos(0, 40, 0)) || !low.test(level, new BlockPos(0, -30, 0))) problems.add("height{h=<40}");
        var high = SpawnConditions.parse("height{h=>100}");
        if (high.test(level, new BlockPos(0, 100, 0)) || !high.test(level, new BlockPos(0, 101, 0))) problems.add("height{h=>100}");
        var notLow = SpawnConditions.parse("height{h=<40} false");
        if (notLow.test(level, new BlockPos(0, 10, 0)) || !notLow.test(level, new BlockPos(0, 80, 0))) problems.add("height{h=<40} false");
        var dusk = SpawnConditions.parse("(worldtime{t=11500to14000} || worldtime{t=22000to24000} || worldtime{t=0to1500}) true");
        var notDusk = SpawnConditions.parse("(worldtime{t=11500to14000} || worldtime{t=22000to24000} || worldtime{t=0to1500}) false");
        var night = SpawnConditions.parse("night true");
        var outsideFalse = SpawnConditions.parse("outside false");
        var both = SpawnConditions.parse("(lunarphase{p=4} false || night false) true");
        long before = level.getDefaultClockTime();
        long day0 = before - Math.floorMod(before, 24000L);
        try {
            long[][] cases = {{12000, 1}, {18000, 0}, {23000, 1}, {1000, 1}, {6000, 0}};
            for (long[] c : cases) {
                h.setTime(day0 + c[0]);
                if (dusk.test(level, at) != (c[1] == 1)) problems.add("dusk OR form at " + c[0]);
                if (notDusk.test(level, at) == (c[1] == 1)) problems.add("negated OR form at " + c[0]);
            }
            h.setTime(day0 + 18000);
            if (!night.test(level, at)) problems.add("night at 18000");
            long phaseDay = Math.floorMod(Math.floorDiv(level.getDefaultClockTime(), 24000L), 8L);
            if (both.test(level, at) != (phaseDay != 4)) problems.add("(lunarphase false || night false) at night, phase " + phaseDay);
            h.setTime(day0 + 6000);
            if (night.test(level, at)) problems.add("night at 6000");
            if (!both.test(level, at)) problems.add("(lunarphase false || night false) by day");
        } finally {
            h.setTime(before);
        }
        if (outsideFalse.test(level, at)) problems.add("outside false under open sky");
        for (String bad : List.of("mobsinradius{types=X;amount=0to2;radius=8} true", "height{h=abc}", "(night ||) true", "frobnicate")) {
            try {
                SpawnConditions.parse(bad);
                problems.add("accepted '" + bad + "'");
            } catch (IllegalArgumentException expected) {
                // fine: unknown syntax is an error, not a silent true
            }
        }
        h.assertTrue(n > 40, "only " + n + " conditions in the species data");
        CT.report(h, "spawn conditions", problems);
        h.succeed();
    }
}
