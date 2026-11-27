package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.List;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMonster;
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
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The spider / drowned / husk undead variants (Gloomweaver, Reedwader, Sandshroud): each takes about its share of the
 * vanilla mob's natural spawns in its biomes at night (Restless Moon share on the new moon), none of other mobs, none
 * by day or elsewhere; end to end through vanilla's natural spawner (a drowned in water, a spider in a dark cave box,
 * a husk on sand) the variant comes out at its share and the vanilla mob keeps the rest; and each behaves like the
 * mob it replaces (climbs / poison-proof arthropod, amphibious, hunger touch, sun rules), plus the Gloomweaver's snip.
 */
public class UndeadVariantTests {
    private record Case(String id, EntityType<?> vanilla, String biome, boolean water) {
    }

    private static List<Case> cases() {
        return List.of(new Case("gloomweaver", EntityTypes.SPIDER, "minecraft:dark_forest", false),
                new Case("reedwader", EntityTypes.DROWNED, "minecraft:river", true),
                new Case("sandshroud", EntityTypes.HUSK, "minecraft:desert", false));
    }

    private static void paint(GameTestHelper h, BlockPos relFrom, BlockPos relTo, String biome) {
        ServerLevel level = h.getLevel();
        var holder = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome)));
        var r = FillBiomeCommand.fill(level, h.absolutePos(relFrom), h.absolutePos(relTo), holder);
        if (r.right().isPresent() && r.right().get().getType() != FillBiomeCommand.ERROR_NO_BIOMES_SET) {
            throw h.assertionException(net.minecraft.network.chat.Component.literal("fillbiome " + biome + ": " + r.right().get().getMessage()));
        }
    }

    private static Species species(String id) {
        return ArcanaEntities.species(ArcanaEntities.type(id));
    }

    private static double want(ServerLevel level, BlockPos pos, Species s) {
        var r = s.section("replace");
        return ArcanaEntities.restlessMoon(level, pos) ? r.get("moon_share").getAsDouble() : r.get("share").getAsDouble();
    }

    // ------------------------------------------------------------------ shares (the hook alone)

    /** Each variant takes about its share of its vanilla mob at night in its biome; never another mob, the day, or plains. */
    @GameTest(maxTicks = 20)
    public void variantReplaceShares(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(3, 1, 3));
        long before = level.getDefaultClockTime();
        long day0 = before - Math.floorMod(before, 24000L);
        List<String> problems = new ArrayList<>();
        List<EntityType<?>> others = List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.DROWNED,
                EntityTypes.HUSK, EntityTypes.CAVE_SPIDER, EntityTypes.CREEPER);
        try {
            for (Case c : cases()) {
                paint(h, new BlockPos(0, 0, 0), new BlockPos(7, 7, 7), c.biome());
                EntityType<?> variant = ArcanaEntities.type(c.id());
                h.setTime(day0 + 18000);
                double want = want(level, pos, species(c.id()));
                int n = 4000, hits = 0, wrong = 0;
                for (int i = 0; i < n; i++) {
                    Mob m = ArcanaEntities.naturalReplacement(level, c.vanilla(), pos);
                    if (m == null) continue;
                    if (m.getType() == variant) hits++;
                    else problems.add(c.id() + ": " + c.vanilla().toShortString() + " replaced by " + m.getType().toShortString());
                    for (EntityType<?> o : others) {
                        if (o == c.vanilla()) continue;
                        Mob x = ArcanaEntities.naturalReplacement(level, o, pos);
                        if (x != null && x.getType() == variant) wrong++;
                    }
                }
                double got = hits / (double) n;
                if (Math.abs(got - want) >= 0.04) problems.add(String.format("%s: share %.3f, want %.2f", c.id(), got, want));
                if (wrong > 0) problems.add(c.id() + ": replaced " + wrong + " spawns of other mobs");
                h.setTime(day0 + 6000);
                int day = 0;
                for (int i = 0; i < 500; i++) if (ArcanaEntities.naturalReplacement(level, c.vanilla(), pos) != null) day++;
                if (day > 0) problems.add(c.id() + ": " + day + " daytime replacements");
                paint(h, new BlockPos(0, 0, 0), new BlockPos(7, 7, 7), "minecraft:plains");
                h.setTime(day0 + 18000);
                int plains = 0;
                for (int i = 0; i < 500; i++) {
                    Mob m = ArcanaEntities.naturalReplacement(level, c.vanilla(), pos);
                    if (m != null && m.getType() == variant) plains++;
                }
                if (plains > 0) problems.add(c.id() + ": " + plains + " replacements in plains");
                net.axther.aftrhrs.AftrHrs.LOG.info("[c2-undead] {} replaces {} share {} (want {})", c.id(), c.vanilla().toShortString(), got, want);
            }
        } finally {
            h.setTime(before);
        }
        CT.report(h, "undead variant shares", problems);
        h.succeed();
    }

    /** Every variant lists vanilla and Terralith biomes, and none of them overlaps another undead variant of the same mob. */
    @GameTest(maxTicks = 20)
    public void variantBiomes(GameTestHelper h) {
        List<String> problems = new ArrayList<>();
        for (Case c : cases()) {
            var biomes = species(c.id()).section("replace").getAsJsonArray("biomes");
            boolean terralith = false;
            for (var b : biomes) {
                String id = b.getAsString();
                if (id.startsWith("terralith:")) terralith = true;
                if (id.indexOf(':') != id.lastIndexOf(':')) problems.add(c.id() + ": malformed biome " + id);
            }
            if (!terralith) problems.add(c.id() + ": no Terralith biomes");
            if (!c.vanilla().equals(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(species(c.id()).section("replace").get("vanilla").getAsString())))) {
                problems.add(c.id() + ": replaces the wrong mob");
            }
        }
        CT.report(h, "undead variant biomes", problems);
        h.succeed();
    }

    // ------------------------------------------------------------------ end to end through vanilla's natural spawner

    /**
     * Builds a dark box (x 30..62, z -1..25, interior y 1..4) of air or water over stone or sand. Husks only spawn
     * under the open sky (vanilla checkSurfaceMonstersSpawnRules), so their box has no roof (dark enough at night).
     */
    private static void box(GameTestHelper h, boolean water, boolean sand) {
        boolean roof = !sand;
        ServerLevel level = h.getLevel();
        var stone = Blocks.STONE.defaultBlockState();
        for (int x = 30; x <= 62; x++) {
            for (int z = -1; z <= 25; z++) {
                boolean wall = x == 30 || x == 62 || z == -1 || z == 25;
                for (int y = 0; y <= 5; y++) {
                    BlockPos at = h.absolutePos(new BlockPos(x, y, z));
                    var state = wall && y < 5 || y == 5 && roof ? stone : y == 5 ? Blocks.AIR.defaultBlockState() : y == 0 ? (sand ? Blocks.SAND.defaultBlockState() : stone)
                            : water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(at, state, 2);
                }
            }
        }
    }

    private void throughVanilla(GameTestHelper h, Case c, int tries) {
        CT.arena(h, 64, 24);
        ServerLevel level = h.getLevel();
        box(h, c.water(), c.vanilla() == EntityTypes.HUSK);
        paint(h, new BlockPos(28, 0, -3), new BlockPos(64, 8, 27), c.biome());
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 12));
        h.runAfterDelay(40, () -> {
            EntityType<?> variantType = ArcanaEntities.type(c.id());
            BlockPos probe = h.absolutePos(new BlockPos(46, 2, 12));
            long before = level.getDefaultClockTime();
            long day0 = before - Math.floorMod(before, 24000L);
            int vanilla = 0, variants = 0, others = 0, dry = 0;
            double want, expected;
            try {
                h.setTime(day0 + 18000);
                level.updateSkyBrightness();
                h.assertTrue(c.vanilla() == EntityTypes.HUSK || level.getBrightness(LightLayer.SKY, probe) == 0 && level.getBrightness(LightLayer.BLOCK, probe) == 0,
                        "the box is not dark: sky " + level.getBrightness(LightLayer.SKY, probe) + ", block " + level.getBrightness(LightLayer.BLOCK, probe));
                want = want(level, probe, species(c.id()));
                int total = 0, vw = 0;
                for (var w : level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, probe).getMobsToSpawn(MobCategory.MONSTER).unwrap()) {
                    total += w.weight();
                    if (w.value().type() == c.vanilla()) vw += w.weight();
                }
                expected = total == 0 ? 0 : vw / (double) total;
                List<Mob> spawned = new ArrayList<>();
                var random = level.getRandom();
                for (int i = 0; i < tries; i++) {
                    BlockPos start = h.absolutePos(new BlockPos(35 + random.nextInt(23), c.water() ? 2 : 1, 4 + random.nextInt(17)));
                    NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, level.getChunk(start), start, (t, l, b, ch) -> true,
                            (mob, ch) -> spawned.add(mob));
                    for (Mob m : spawned) {
                        if (m.getType() == c.vanilla()) vanilla++;
                        else if (m.getType() == variantType) {
                            variants++;
                            // isInWater() is only set on the first tick: look at the block it spawned in
                            if (c.water() && !level.getFluidState(m.blockPosition()).is(net.minecraft.tags.FluidTags.WATER)) dry++;
                        } else others++;
                        m.discard();
                    }
                    spawned.clear();
                }
            } finally {
                h.setTime(before);
                level.updateSkyBrightness();
                CT.disconnect(p);
            }
            int family = vanilla + variants, all = family + others;
            String stats = String.format("%s %d, %s %d, other monsters %d; vanilla weight share %.2f, want variant share %.2f",
                    c.vanilla().toShortString(), vanilla, c.id(), variants, others, expected, want);
            net.axther.aftrhrs.AftrHrs.LOG.info("[c2-undead] replace through vanilla: {}", stats);
            h.assertTrue(family >= 40, "too few " + c.vanilla().toShortString() + "-family spawns: " + stats);
            h.assertTrue(vanilla > 0 && variants > 0, "both the vanilla mob and the variant should spawn: " + stats);
            double v = variants / (double) family;
            h.assertTrue(Math.abs(v - want) < 0.15, String.format("%s share of %s spawns %.2f: %s", c.id(), c.vanilla().toShortString(), v, stats));
            h.assertTrue(dry == 0, dry + " drowned variants spawned out of the water: " + stats);
            if (!c.water()) {
                double fam = family / (double) Math.max(1, all);
                h.assertTrue(fam < expected * 2 + 0.1, String.format("%s family is %.2f of all monsters (vanilla weight %.2f): %s", c.vanilla().toShortString(), fam, expected, stats));
            }
            h.succeed();
        });
    }

    /** A river at night under water: drowned spawns still come, about `share` of them as Reedwaders, all in the water. */
    @GameTest(maxTicks = 200, padding = 48)
    public void drownedReplaceThroughVanilla(GameTestHelper h) {
        throughVanilla(h, cases().get(1), 6000);
    }

    /** A dark cave box in a dark forest at night: spiders still spawn, about `share` of them as Gloomweavers. */
    @GameTest(maxTicks = 200, padding = 48)
    public void spiderReplaceThroughVanilla(GameTestHelper h) {
        throughVanilla(h, cases().get(0), 600);
    }

    /** A dark sand box in a desert at night: husks still spawn, about `share` of them as Sandshrouds. */
    @GameTest(maxTicks = 200, padding = 48)
    public void huskReplaceThroughVanilla(GameTestHelper h) {
        throughVanilla(h, cases().get(2), 10000);
    }

    // ------------------------------------------------------------------ behavior parity

    /**
     * Rime Wraiths burn in daylight like zombies while wild (the vanilla burn_in_daylight tag drives the burning);
     * a tamed one never does.
     */
    @GameTest(maxTicks = 40, padding = 8)
    public void rimeWraithsBurnByDayUnlessTamed(GameTestHelper h) throws Exception {
        CT.arena(h, 8, 8);
        net.axther.aftrhrs.entity.ArcanaCreature wild = (net.axther.aftrhrs.entity.ArcanaCreature) CT.spawn(h, species("rime_wraith"), new Vec3(2, 1, 2));
        net.axther.aftrhrs.entity.ArcanaCreature pet = (net.axther.aftrhrs.entity.ArcanaCreature) CT.spawn(h, species("rime_wraith"), new Vec3(5, 1, 5));
        h.assertTrue(wild.is(EntityTypeTags.BURN_IN_DAYLIGHT), "the Rime Wraith isn't in minecraft:burn_in_daylight");
        h.assertFalse(wild.is(EntityTypeTags.UNDEAD), "the Rime Wraith became undead (smite, healing potions)");
        pet.setTame(true, false);
        java.lang.reflect.Method sun = net.minecraft.world.entity.Mob.class.getDeclaredMethod("isSunBurnTick");
        sun.setAccessible(true);
        h.assertFalse((boolean) sun.invoke(pet), "a tamed Rime Wraith would burn in daylight");
        wild.discard();
        pet.discard();
        h.succeed();
    }

    /** Gloomweaver = a spider: wall-climber, poison-proof, an arthropod (not undead), no sunburn, spider stats. */
    @GameTest(maxTicks = 40, padding = 8)
    public void gloomweaverIsASpider(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ArcanaMonster g = CT.spawn(h, species("gloomweaver"), new Vec3(4, 1, 4));
        h.assertTrue(g.spider(), "not a spider variant");
        h.assertTrue(g.getNavigation() instanceof WallClimberNavigation, "no wall-climbing navigation");
        h.assertTrue(g.is(EntityTypeTags.ARTHROPOD), "not an arthropod (bane of arthropods)");
        h.assertFalse(g.is(EntityTypeTags.UNDEAD), "a spider variant must not be undead (smite, healing)");
        h.assertFalse(g.is(EntityTypeTags.BURN_IN_DAYLIGHT), "a spider variant must not burn by day");
        h.assertFalse(g.canBeAffected(new MobEffectInstance(MobEffects.POISON, 100)), "poison affects it");
        h.assertValueEqual(g.getMaxHealth(), 16.0f, "health");
        h.assertTrue(g.getType().getCategory() == MobCategory.MONSTER, "category " + g.getType().getCategory());
        h.assertTrue(Math.abs(g.getBbWidth() - 1.4f) < 0.01f && Math.abs(g.getBbHeight() - 0.9f) < 0.01f,
                "spider hitbox 1.4 x 0.9, got " + g.getBbWidth() + " x " + g.getBbHeight());
        // climbs: pushed against a wall it sets the climbing flag. Pushed every tick (its own wandering steers it away
        // from a single shove), toward wherever the wall is in the world.
        h.setBlock(new BlockPos(5, 1, 4), Blocks.STONE);
        h.setBlock(new BlockPos(5, 2, 4), Blocks.STONE);
        Vec3 toWall = Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(5, 1, 4)).subtract(h.absolutePos(new BlockPos(4, 1, 4)))).scale(0.3);
        h.succeedWhen(() -> {
            if (!g.onClimbable()) g.setDeltaMovement(toWall.x, g.getDeltaMovement().y, toWall.z);
            h.assertTrue(g.onClimbable(), "pushed into a wall, it does not climb");
        });
    }

    /** Shears snip the calm Gloomweaver's silk bundle: silk drops, the bundle hides, a second snip finds nothing. */
    @GameTest(maxTicks = 40, padding = 8)
    public void gloomweaverSnip(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ArcanaMonster g = CT.spawn(h, species("gloomweaver"), new Vec3(4, 1, 4));
        g.setNoAi(true);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 4));
        try {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
            h.assertTrue(g.snippable(), "a calm Gloomweaver is not snippable");
            CT.call(g, "mobInteract", p, InteractionHand.MAIN_HAND);
            AABB around = g.getBoundingBox().inflate(3);
            var silk = net.axther.aftrhrs.entity.CreatureUtil.customItem("arcana_silk");
            h.assertTrue(silk != null, "no arcana_silk item");
            int got = 0;
            for (ItemEntity it : h.getLevel().getEntitiesOfClass(ItemEntity.class, around)) if (it.getItem().is(silk)) got += it.getItem().getCount();
            h.assertTrue(got >= 1, "no silk dropped");
            h.assertTrue(g.hiddenBones().contains("silk"), "the silk bundle still shows");
            h.assertTrue(!g.snippable() && g.var("snipped") > 0, "snippable again right away");
            h.assertTrue(p.getMainHandItem().getDamageValue() == 1, "the shears took no wear");
            for (ItemEntity it : h.getLevel().getEntitiesOfClass(ItemEntity.class, around)) it.discard();
            CT.call(g, "mobInteract", p, InteractionHand.MAIN_HAND);
            int again = h.getLevel().getEntitiesOfClass(ItemEntity.class, around).size();
            h.assertTrue(again == 0, "a snipped Gloomweaver dropped more");
            // a Gloomweaver hunting someone can't be snipped
            g.setVar("snipped", 0);
            g.setTarget(p);
            h.assertFalse(g.snippable(), "snippable while hunting");
        } finally {
            CT.disconnect(p);
        }
        h.succeed();
    }

    /** Reedwader = a drowned: amphibious, monster category (not a water creature), spawns in water, burns by day, undead. */
    @GameTest(maxTicks = 20, padding = 8)
    public void reedwaderIsADrowned(GameTestHelper h) {
        CT.arena(h, 8, 8);
        for (int x = 1; x <= 6; x++) for (int z = 1; z <= 6; z++) for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(x, y, z), Blocks.WATER);
        ArcanaMonster r = CT.spawn(h, species("reedwader"), new Vec3(3.5, 1, 3.5));
        h.assertTrue(r.drowned(), "not a drowned variant");
        h.assertTrue(r.getNavigation() instanceof AmphibiousPathNavigation, "no amphibious navigation");
        h.assertTrue(r.getType().getCategory() == MobCategory.MONSTER, "category " + r.getType().getCategory());
        h.assertTrue(r.is(EntityTypeTags.UNDEAD), "not undead");
        h.assertTrue(r.is(EntityTypeTags.BURN_IN_DAYLIGHT), "a drowned variant burns by day");
        h.assertTrue(h.getLevel().getFluidState(r.blockPosition()).is(net.minecraft.tags.FluidTags.WATER), "not in the water");
        h.assertTrue(r.checkSpawnObstruction(h.getLevel()), "vanilla's spawn check turns it away in water");
        h.assertValueEqual(r.getMaxHealth(), 20.0f, "health");
        h.assertTrue(Math.abs(r.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) - 3.0) < 1e-6, "zombie damage 3");
        h.succeed();
    }

    /** Sandshroud = a husk: no sunburn, undead, and its bare-handed hit brings on Hunger (difficulty-scaled). */
    @GameTest(maxTicks = 20, padding = 8)
    public void sandshroudIsAHusk(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        ArcanaMonster s = CT.spawn(h, species("sandshroud"), new Vec3(4, 1, 4));
        h.assertTrue(s.husk(), "not a husk variant");
        h.assertTrue(s.is(EntityTypeTags.UNDEAD), "not undead");
        h.assertFalse(s.is(EntityTypeTags.BURN_IN_DAYLIGHT), "a husk variant must not burn by day");
        // any living target gets the Hunger (a mock player stays damage-immune until its client "loads")
        var target = h.spawn(EntityTypes.VILLAGER, new BlockPos(4, 1, 5));
        Difficulty was = level.getDifficulty();
        try {
            level.getServer().setDifficulty(Difficulty.HARD, true);
            boolean hit = s.doHurtTarget(level, target);
            h.assertTrue(hit, "the hit did not land");
            h.assertTrue(target.hasEffect(MobEffects.HUNGER), "no Hunger after a Sandshroud's hit");
        } finally {
            level.getServer().setDifficulty(was, true);
        }
        h.succeed();
    }
}
