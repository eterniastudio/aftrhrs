package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaSpawner;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.axther.aftrhrs.entity.Species;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Creatures 2, ocean: every ocean biome has at least three Arcana species, the additive spawner puts the Floepup,
 * Kelpback and Inkcurl into ocean water (and never onto dry ground), the aquatic wildlife swims and breathes, the
 * Floepup tames, sits and saves its owner from drowning, and the Kelpback / Inkcurl harvests work.
 */
public class OceanTests {
    /** The vanilla ocean biomes + Terralith's deep warm ocean (indexed even though Terralith isn't loaded here). */
    static final List<String> OCEANS = List.of("minecraft:ocean", "minecraft:deep_ocean", "minecraft:warm_ocean",
            "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean", "minecraft:cold_ocean", "minecraft:deep_cold_ocean",
            "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "terralith:deep_warm_ocean");

    private static Species species(String id) {
        return ArcanaEntities.species(ArcanaEntities.type(id));
    }

    private static Predicate<Species> only(String id) {
        return s -> s.id.equals(id);
    }

    private static void paint(GameTestHelper h, BlockPos relFrom, BlockPos relTo, String biome) {
        ServerLevel level = h.getLevel();
        var holder = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome)));
        FillBiomeCommand.fill(level, h.absolutePos(relFrom), h.absolutePos(relTo), holder);
    }

    /** A glass-walled pool: water at relative x 20..34, y 1..4, z 0..8 over the arena's stone floor. */
    private static void pool(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 19; x <= 35; x++) {
            for (int z = -1; z <= 9; z++) {
                for (int y = 1; y <= 5; y++) {
                    boolean wall = x == 19 || x == 35 || z == -1 || z == 9;
                    var st = wall ? Blocks.GLASS.defaultBlockState() : y <= 4 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(h.absolutePos(new BlockPos(x, y, z)), st, 2);
                }
            }
        }
    }

    private static void click(ServerPlayer p, Entity e, ItemStack held) {
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        UseEntityCallback.EVENT.invoker().interact(p, p.level(), InteractionHand.MAIN_HAND, e, null);
    }

    private static int count(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (s.is(item)) n += s.getCount();
        return n;
    }

    /** Every ocean biome is covered by at least three Arcana species' water rules; the three new ones are indexed. */
    @GameTest(maxTicks = 20)
    public void everyOceanHasThreeSpecies(GameTestHelper h) {
        List<String> problems = new ArrayList<>();
        for (String biome : OCEANS) {
            Set<String> ids = new LinkedHashSet<>();
            for (var r : ArcanaSpawner.rulesAt("minecraft:overworld", biome)) if (r.spawn.water()) ids.add(r.species.id);
            if (ids.size() < 3) problems.add(biome + ": only " + ids);
        }
        for (String id : List.of("floepup", "kelpback", "inkcurl")) {
            Species s = species(id);
            if (s.aquatic() == null) problems.add(id + " is not aquatic");
            if (s.spawns.isEmpty() || !s.spawns.stream().allMatch(Species.Spawn::water)) problems.add(id + " has no / non-water rules");
        }
        CT.report(h, "ocean coverage", problems);
        h.succeed();
    }

    /**
     * Removes every wild creature around the test: a probe's spawn can bring company (a kelpback comes with a second
     * one), and leftovers would count toward the spawner's local cap and the rule caps of the next probes.
     */
    private static void clearWild(GameTestHelper h) {
        for (Mob m : h.getLevel().getEntitiesOfClass(Mob.class, h.getBounds().inflate(48), ArcanaSpawner::isWild)) m.discard();
    }

    /**
     * The additive spawner puts each new species into ocean water of each of its biomes; never onto a dry floor. In a
     * batch of its own: kelpback's rule cap (3 within 48 blocks) also counted the kelpbacks other creature tests spawned.
     */
    @GameTest(maxTicks = 60, padding = 32, skyAccess = true, environment = "aftrhrs_gametest:creatures_isolated")
    public void spawnerFillsOceanWater(GameTestHelper h) {
        CT.arena(h, 36, 8);
        pool(h);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 4));
        List<String> problems = new ArrayList<>();
        Map<String, List<String>> want = Map.of(
                "floepup", List.of("minecraft:ocean", "minecraft:deep_ocean", "minecraft:cold_ocean", "minecraft:deep_cold_ocean",
                        "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean"),
                "kelpback", List.of("minecraft:ocean", "minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean"),
                "inkcurl", OCEANS.subList(0, 9));
        try {
            for (String biome : OCEANS.subList(0, 9)) {
                paint(h, new BlockPos(-2, 0, -2), new BlockPos(38, 8, 10), biome);
                for (var e : want.entrySet()) {
                    String id = e.getKey();
                    BlockPos spot = h.absolutePos(new BlockPos(30, 2, 4));
                    Mob m = ArcanaSpawner.probe(p, spot, only(id));
                    boolean should = e.getValue().contains(biome);
                    if (m != null) {
                        if (!should) problems.add(id + " spawned in " + biome);
                        else if (!h.getLevel().getFluidState(m.blockPosition()).is(FluidTags.WATER)) problems.add(id + " not in water in " + biome);
                        else if (!ArcanaSpawner.isWild(m)) problems.add(id + " spawned not wild in " + biome);
                        m.discard();
                    } else if (should) {
                        problems.add("no " + id + " in " + biome + " water");
                    }
                    // a dry spot (the stone floor outside the pool) of the same biome never gets a sea creature
                    Mob dry = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(8, 1, 4)), only(id));
                    if (dry != null) {
                        problems.add(id + " spawned on dry ground in " + biome);
                        dry.discard();
                    }
                    clearWild(h);
                }
            }
            // the older sea creatures spawn in water too (Mob.checkSpawnObstruction refused any liquid before)
            for (String[] old : new String[][] {{"bubblefin", "minecraft:warm_ocean"}, {"ripplekit", "minecraft:cold_ocean"},
                    {"tidemane", "minecraft:ocean"}}) {
                paint(h, new BlockPos(-2, 0, -2), new BlockPos(38, 8, 10), old[1]);
                Mob m = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(27, 2, 4)), only(old[0]));
                if (m == null) problems.add("no " + old[0] + " in " + old[1] + " water");
                else m.discard();
                clearWild(h);
            }
        } finally {
            CT.disconnect(p);
        }
        CT.report(h, "ocean water spawns", problems);
        h.succeed();
    }

    /** Aquatic wildlife swims (water-bound navigation, no drowning) and dries out slowly on land. */
    @GameTest(maxTicks = 120, padding = 32)
    public void aquaticWildlifeSwimsAndBreathes(GameTestHelper h) {
        CT.arena(h, 36, 8);
        pool(h);
        ArcanaWildlife cow = CT.spawn(h, species("kelpback"), new Vec3(27.5, 2, 4.5));
        ArcanaWildlife ink = CT.spawn(h, species("inkcurl"), new Vec3(31.5, 1, 4.5));
        ArcanaWildlife beached = CT.spawn(h, species("inkcurl"), new Vec3(6.5, 1, 4.5));
        for (ArcanaWildlife w : List.of(cow, ink, beached)) w.setKept(true);
        h.assertTrue(cow.getNavigation() instanceof WaterBoundPathNavigation, "Kelpback navigation " + cow.getNavigation().getClass().getSimpleName());
        h.assertTrue(cow.canBreatheUnderwater() && !cow.isPushedByFluid(), "Kelpback should breathe under water and not drift");
        h.runAfterDelay(100, () -> {
            for (ArcanaWildlife w : List.of(cow, ink)) {
                h.assertTrue(w.isAlive(), w.species().id + " died in water");
                h.assertTrue(h.getLevel().getFluidState(w.blockPosition()).is(FluidTags.WATER), w.species().id + " left the water: " + w.blockPosition());
                h.assertValueEqual(w.getAirSupply(), w.getMaxAirSupply(), w.species().id + " air in water");
            }
            h.assertTrue(beached.getAirSupply() < beached.getMaxAirSupply() - 50, "a beached Inkcurl does not dry out: " + beached.getAirSupply());
            for (Entity e : List.of(cow, ink, beached)) e.discard();
            h.succeed();
        });
    }

    /** Floepup: tamed with cod, sits on Shift-stay, and its Lifeguard gives a drowning owner air and Regeneration. */
    @GameTest(maxTicks = 1200, padding = 32)
    public void floepupTamesSitsAndSavesItsOwner(GameTestHelper h) {
        CT.arena(h, 36, 8);
        pool(h);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 4));
        ArcanaCreature pup = CT.spawn(h, species("floepup"), new Vec3(5.5, 1, 4.5));
        pup.setPersistenceRequired();
        // taming: one cod per second (the creature takes one try per second), 1 in 3 per cod
        feed(h, owner, pup, 0, () -> afterTame(h, owner, pup));
    }

    private static void feed(GameTestHelper h, ServerPlayer owner, ArcanaCreature pup, int tries, Runnable then) {
        if (pup.isTame() || tries >= 40) {
            then.run();
            return;
        }
        PetsTests.withCap(1000, () -> {
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COD, 8));
            pup.interact(owner, InteractionHand.MAIN_HAND, pup.position());
        });
        h.runAfterDelay(22, () -> feed(h, owner, pup, tries + 1, then));
    }

    private static void afterTame(GameTestHelper h, ServerPlayer owner, ArcanaCreature pup) {
        h.assertTrue(pup.isTame() && pup.getOwner() == owner, "the Floepup was not tamed with 40 cod");
        h.assertTrue(owner.getMainHandItem().getCount() < 8 || owner.getMainHandItem().isEmpty(), "taming did not use the cod");
        pup.setStaying(true, owner);
        h.assertTrue(pup.isOrderedToSit() && pup.staying(), "the Floepup does not sit when told to stay");
        // lifeguard: owner in the pool, almost out of air
        pup.setStaying(false, owner);
        pup.snapTo(h.absoluteVec(new Vec3(25.5, 2, 4.5)));
        Vec3 at = h.absoluteVec(new Vec3(27.5, 2, 4.5));
        owner.teleportTo(h.getLevel(), at.x, at.y, at.z, Set.of(), 0, 0, false);
        owner.setAirSupply(40);
        h.assertTrue(Abilities.runSkill((ArcanaMob) pup, "ArcanaFloepup_Lifeguard", owner), "Lifeguard skill not registered");
        h.runAfterDelay(20, () -> {
            h.assertValueEqual(owner.getAirSupply(), owner.getMaxAirSupply(), "owner air after the Lifeguard");
            h.assertTrue(owner.hasEffect(MobEffects.REGENERATION), "no Regeneration from the Lifeguard");
            // resting: a second rescue right away does nothing
            owner.setAirSupply(40);
            Abilities.runSkill((ArcanaMob) pup, "ArcanaFloepup_Lifeguard", owner);
            h.runAfterDelay(15, () -> {
                h.assertValueEqual(owner.getAirSupply(), 40, "owner air during the Lifeguard's rest");
                pup.discard();
                CT.disconnect(owner);
                h.succeed();
            });
        });
    }

    /** Kelpback: shear the garden (kelp, then it is shorn, garden hidden), brush for a sea pickle; Inkcurl: brush for ink. */
    @GameTest(maxTicks = 60, padding = 16)
    public void oceanHarvests(GameTestHelper h) {
        CT.arena(h, 16, 16);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(8, 1, 1));
        ArcanaWildlife cow = CT.spawn(h, species("kelpback"), new Vec3(4, 1, 6));
        ArcanaWildlife ink = CT.spawn(h, species("inkcurl"), new Vec3(10, 1, 6));
        cow.setKept(true);
        ink.setKept(true);
        click(p, cow, new ItemStack(Items.SHEARS));
        int kelp = count(p, Items.KELP);
        h.assertTrue(kelp >= 2, "shearing a Kelpback gave " + kelp + " kelp");
        h.assertTrue(cow.hiddenBones().contains("garden"), "the garden is still shown after shearing: '" + cow.hiddenBones() + "'");
        click(p, ink, new ItemStack(Items.BRUSH));
        h.assertTrue(count(p, Items.INK_SAC) >= 1, "brushing an Inkcurl gave no ink sac");
        // clicks on the same creature in one tick count once (off hand): the next ones a few ticks later
        h.runAfterDelay(5, () -> {
            click(p, cow, new ItemStack(Items.SHEARS));
            h.assertValueEqual(count(p, Items.KELP), kelp, "kelp after shearing a shorn Kelpback");
            h.runAfterDelay(5, () -> {
                click(p, cow, new ItemStack(Items.BRUSH));
                h.assertTrue(count(p, Items.SEA_PICKLE) >= 1, "brushing a Kelpback gave no sea pickle");
                // hurt: the Inkcurl inks and is spooked (it flees)
                ink.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(p), 1);
                h.assertTrue(ink.spooked(), "a hurt Inkcurl is not spooked");
                cow.discard();
                ink.discard();
                CT.disconnect(p);
                h.succeed();
            });
        });
    }
}
