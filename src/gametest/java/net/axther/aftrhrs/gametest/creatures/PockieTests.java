package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaSpawner;
import net.axther.aftrhrs.entity.FishbowlCreature;
import net.axther.aftrhrs.entity.Species;
import net.axther.aftrhrs.entity.ability.Abilities;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Pockie (pocket shark, the fishbowl pet): spawns in warm / lukewarm ocean water through the additive spawner (never on
 * dry ground, never in cold oceans), tames with a sea pickle and sits, gets into its fishbowl out of water (state flag,
 * bowl hitbox, bowl clips) and takes no damage there for two minutes, leaps out of the bowl in water, and its glow
 * pocket marks monsters and fish around its owner and gives an owner under water Night Vision.
 */
public class PockieTests {
    /** Every damage a Pockie took during the tests (entity uuid -> "type amount @tick"), for the bowl test's report. */
    private static final java.util.Map<java.util.UUID, List<String>> HURT = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof FishbowlCreature f) {
                HURT.computeIfAbsent(f.getUUID(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(source.getMsgId() + " " + taken + " @" + f.tickCount + " state " + f.bowlState() + " at " + f.blockPosition());
            }
        });
    }
    private static Species species() {
        return ArcanaEntities.species(ArcanaEntities.type("pockie"));
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

    private static FishbowlCreature spawn(GameTestHelper h, Vec3 rel) {
        FishbowlCreature f = CT.spawn(h, species(), rel);
        f.setPersistenceRequired();
        return f;
    }

    /** The additive spawner puts the Pockie into warm / lukewarm ocean water; not on dry ground, not in cold oceans. */
    @GameTest(maxTicks = 60, padding = 32, skyAccess = true)
    public void pockieSpawnsInWarmOceanWater(GameTestHelper h) {
        CT.arena(h, 36, 8);
        pool(h);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 4));
        List<String> problems = new ArrayList<>();
        try {
            Set<String> rules = new java.util.HashSet<>();
            for (String biome : List.of("minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean", "terralith:deep_warm_ocean")) {
                boolean has = ArcanaSpawner.rulesAt("minecraft:overworld", biome).stream().anyMatch(r -> r.species.id.equals("pockie") && r.spawn.water());
                if (!has) problems.add("no Pockie water rule for " + biome);
                rules.add(biome);
            }
            for (String biome : List.of("minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean", "minecraft:cold_ocean")) {
                paint(h, new BlockPos(-2, 0, -2), new BlockPos(38, 8, 10), biome);
                boolean should = !biome.equals("minecraft:cold_ocean");
                Mob m = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(30, 2, 4)), s -> s.id.equals("pockie"));
                if (m != null) {
                    if (!should) problems.add("Pockie spawned in " + biome);
                    else if (!h.getLevel().getFluidState(m.blockPosition()).is(FluidTags.WATER)) problems.add("Pockie not in water in " + biome);
                    else if (!(m instanceof FishbowlCreature f) || f.inBowl()) problems.add("a fresh Pockie in water is in its bowl in " + biome);
                    m.discard();
                } else if (should) {
                    problems.add("no Pockie in " + biome + " water");
                }
                Mob dry = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(8, 1, 4)), s -> s.id.equals("pockie"));
                if (dry != null) {
                    problems.add("Pockie spawned on dry ground in " + biome);
                    dry.discard();
                }
            }
        } finally {
            CT.disconnect(p);
        }
        CT.report(h, "Pockie spawns", problems);
        h.succeed();
    }

    /** Tamed with sea pickles (1 in 3 each), sits on Shift-stay (the water sit clip is held). */
    @GameTest(maxTicks = 1200, padding = 32)
    public void pockieTamesAndSits(GameTestHelper h) {
        CT.arena(h, 36, 8);
        pool(h);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(24, 5, 4));
        FishbowlCreature f = spawn(h, new Vec3(25.5, 2, 4.5));
        tame(h, owner, f, 0, () -> {
            h.assertTrue(f.isTame() && f.getOwner() == owner, "the Pockie was not tamed with 40 sea pickles");
            f.setStaying(true, owner);
            h.assertTrue(f.isOrderedToSit() && f.staying(), "the Pockie does not sit when told to stay");
            h.runAfterDelay(3, () -> {
                // a staying pet may start a random nap at any moment, so "sleep" is a sitting posture too
                String clip = f.holdClip();
                h.assertTrue("sit".equals(clip) || "sleep".equals(clip), "held clip while sitting in water: " + clip);
                h.assertTrue(!f.inBowl(), "a sitting Pockie in water got into its bowl");
                f.discard();
                CT.disconnect(owner);
                h.succeed();
            });
        });
    }

    private static void tame(GameTestHelper h, ServerPlayer owner, FishbowlCreature f, int tries, Runnable then) {
        if (f.isTame() || tries >= 40) {
            then.run();
            return;
        }
        PetsTests.withCap(1000, () -> {
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SEA_PICKLE, 8));
            f.interact(owner, InteractionHand.MAIN_HAND, f.position());
        });
        h.runAfterDelay(22, () -> tame(h, owner, f, tries + 1, then));
    }

    /**
     * Out of water it gets into its bowl (ENTERING with the bowl_in clip, then IN_BOWL: bowl hitbox, bowl idle / gait,
     * the bowl bones shown); told to stay it parks the bowl (bowl_sit); two minutes on land cost it no health or air.
     */
    @GameTest(maxTicks = 2700, padding = 32)
    public void pockieEntersBowlOnLandAndKeepsWell(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(1.5, 1, 1.5));
        FishbowlCreature f = spawn(h, new Vec3(4.5, 1, 4.5));
        f.tamedBy(owner);
        h.runAfterDelay(8, () -> {
            h.assertTrue(f.bowlState() == FishbowlCreature.ENTERING, "out of water the Pockie is not getting into its bowl: " + f.bowlState());
            h.assertTrue(f.busy(), "the Pockie moves while it gets into its bowl");
            h.assertValueEqual(f.hiddenBones(), "", "bowl bones while getting in");
        });
        h.runAfterDelay(70, () -> {
            h.assertTrue(f.bowlState() == FishbowlCreature.IN_BOWL, "not in its bowl after getting in: " + f.bowlState());
            h.assertTrue(!f.busy(), "still held after getting into the bowl");
            h.assertTrue(Math.abs(f.getBbWidth() - 0.75f) < 1.0E-3 && Math.abs(f.getBbHeight() - 0.75f) < 1.0E-3,
                    "bowl hitbox " + f.getBbWidth() + " x " + f.getBbHeight());
            h.assertValueEqual(f.idleClip(), "bowl_idle", "idle clip in the bowl");
            h.assertValueEqual(f.walkClip(), "bowl_roll", "gait in the bowl");
            h.assertValueEqual(f.clipFor("happy"), "bowl_happy", "happy maps to its bowl twin");
            h.assertTrue(f.clipFor("dart") == null, "a whole-body water flourish would play in the bowl");
            h.assertTrue(f.clipFor("tail_wiggle") != null, "a tail-only overlay is skipped in the bowl");
            // dry-out / drown damage never reaches it in its bowl
            h.assertTrue(!f.hurtServer(h.getLevel(), h.getLevel().damageSources().dryOut(), 2f), "the bowl does not keep it from drying out");
            f.setStaying(true, owner);
        });
        // parked: the bowl twin of sit (or of sleep, when a nap has started by then), never the water clip
        h.runAfterDelay(75, () -> h.assertTrue(f.holdClip().equals("bowl_sit") || f.holdClip().equals("sleep_bowl"),
                "held clip while parked in the bowl: " + f.holdClip()));
        h.runAfterDelay(80 + 2400, () -> {
            h.assertTrue(f.isAlive(), "the Pockie died in its bowl");
            h.assertTrue(f.getHealth() >= f.getMaxHealth(), "health after two minutes in the bowl: " + f.getHealth() + ", hurt by "
                    + HURT.getOrDefault(f.getUUID(), List.of()));
            h.assertValueEqual(f.getAirSupply(), f.getMaxAirSupply(), "air after two minutes in the bowl");
            h.assertTrue(f.bowlState() == FishbowlCreature.IN_BOWL, "left the bowl on land: " + f.bowlState());
            f.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** In deep water the bowl goes: EXITING (bowl_out), then SWIM with the small hitbox and the bowl bones hidden. */
    @GameTest(maxTicks = 200, padding = 32)
    public void pockieLeavesBowlInWater(GameTestHelper h) {
        CT.arena(h, 36, 8);
        pool(h);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(10, 1, 4));
        FishbowlCreature f = spawn(h, new Vec3(10.5, 1, 4.5));
        f.tamedBy(owner);
        f.forceState(true);
        h.assertTrue(f.inBowl(), "forceState(true) did not put it in its bowl");
        Vec3 at = h.absoluteVec(new Vec3(27.5, 1.5, 4.5));
        f.teleportTo(at.x, at.y, at.z);
        h.runAfterDelay(12, () -> h.assertTrue(f.bowlState() == FishbowlCreature.EXITING, "in deep water it does not leave its bowl: " + f.bowlState()));
        h.runAfterDelay(60, () -> {
            h.assertTrue(f.bowlState() == FishbowlCreature.SWIM, "still in its bowl in water: " + f.bowlState());
            h.assertTrue(!f.busy(), "still held after leaving the bowl");
            h.assertTrue(Math.abs(f.getBbWidth() - 0.45f) < 1.0E-3, "water hitbox " + f.getBbWidth());
            h.assertValueEqual(f.hiddenBones(), "bowl", "the bowl shows in water");
            h.assertTrue(f.clipFor("bowl_idle") == null, "a bowl clip would play in water");
            h.assertValueEqual(f.clipFor("happy"), "happy", "happy in water");
            h.assertTrue(h.getLevel().getFluidState(f.blockPosition()).is(FluidTags.WATER), "left the water");
            // a save / load keeps the state
            FishbowlCreature back = (FishbowlCreature) CT.load(h.getLevel(), CT.save(f));
            h.assertTrue(back != null && !back.inBowl(), "a saved swimming Pockie loads in its bowl");
            f.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /**
     * Following its owner who swims off into a pond (no walls, level with the bank): the bowl rolls in after them, gets
     * out of the bowl in the water and swims.
     */
    @GameTest(maxTicks = 500, padding = 32)
    public void pockieRollsIntoTheWaterAfterItsOwner(GameTestHelper h) {
        CT.arena(h, 36, 8);
        ServerLevel level = h.getLevel();
        for (int x = 20; x <= 32; x++) {
            for (int z = 0; z <= 8; z++) {
                level.setBlock(h.absolutePos(new BlockPos(x, -1, z)), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(h.absolutePos(new BlockPos(x, 0, z)), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(26.5, 0.1, 4.5));
        FishbowlCreature f = spawn(h, new Vec3(13.5, 1, 4.5));
        f.tamedBy(owner);
        h.runAfterDelay(60, () -> h.assertTrue(f.bowlState() == FishbowlCreature.IN_BOWL, "not in its bowl on the bank: " + f.bowlState()));
        int[] seen = {0};
        h.onEachTick(() -> {
            if (f.bowlState() == FishbowlCreature.EXITING) seen[0] = 1;
        });
        h.runAfterDelay(460, () -> {
            h.assertTrue(seen[0] == 1, "it never got out of its bowl (state " + f.bowlState() + " at " + h.relativePos(f.blockPosition()) + ")");
            h.assertTrue(f.bowlState() == FishbowlCreature.SWIM, "not swimming after reaching its owner: " + f.bowlState());
            h.assertTrue(f.isInWater(), "not in the water: " + h.relativePos(f.blockPosition()));
            f.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /**
     * Floating at the surface of a pond one block deep (only a sliver of it under water, e.g. right after it left its
     * bowl): it is swimming, not stranded, and never gets back into a bowl there.
     */
    @GameTest(maxTicks = 140, padding = 32)
    public void pockieFloatingAtThePondSurfaceStaysOut(GameTestHelper h) {
        CT.arena(h, 36, 8);
        ServerLevel level = h.getLevel();
        for (int x = 20; x <= 32; x++) {
            for (int z = 0; z <= 8; z++) {
                level.setBlock(h.absolutePos(new BlockPos(x, -1, z)), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(h.absolutePos(new BlockPos(x, 0, z)), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        FishbowlCreature f = spawn(h, new Vec3(26.5, 0.93, 4.5));
        f.forceState(false);
        int[] worst = {FishbowlCreature.SWIM};
        h.onEachTick(() -> {
            if (f.bowlState() != FishbowlCreature.SWIM) worst[0] = f.bowlState();
            // held at the surface the whole time (a fish bobbing up), its hitbox mostly above the water
            Vec3 at = h.absoluteVec(new Vec3(26.5, 0.93, 4.5));
            f.setPos(at.x, at.y, at.z);
            f.setDeltaMovement(Vec3.ZERO);
        });
        h.runAfterDelay(120, () -> {
            h.assertTrue(worst[0] == FishbowlCreature.SWIM, "it got into its bowl floating in a pond: state " + worst[0]);
            f.discard();
            h.succeed();
        });
    }

    /**
     * Glow pocket: its owner under water within 10 blocks -> a monster and a fish near the owner glow, the owner gets
     * Night Vision; then it rests (a second squirt right away does nothing).
     */
    @GameTest(maxTicks = 120, padding = 32)
    public void pockieGlowPocketMarks(GameTestHelper h) {
        CT.arena(h, 36, 8);
        pool(h);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(24, 1, 4));
        Vec3 at = h.absoluteVec(new Vec3(24.5, 1.2, 4.5));
        owner.teleportTo(h.getLevel(), at.x, at.y, at.z, Set.of(), 0, 0, false);
        FishbowlCreature f = spawn(h, new Vec3(27.5, 2, 4.5));
        f.tamedBy(owner);
        Zombie z = EntityTypes.ZOMBIE.create(h.getLevel(), EntitySpawnReason.SPAWN_ITEM_USE);
        Vec3 zp = h.absoluteVec(new Vec3(16.5, 1, 4.5));
        z.snapTo(zp.x, zp.y, zp.z, 0, 0);
        z.setNoAi(true);
        h.getLevel().addFreshEntity(z);
        Mob cod = EntityTypes.COD.create(h.getLevel(), EntitySpawnReason.SPAWN_ITEM_USE);
        Vec3 cp = h.absoluteVec(new Vec3(30.5, 2, 6.5));
        cod.snapTo(cp.x, cp.y, cp.z, 0, 0);
        cod.setNoAi(true);
        h.getLevel().addFreshEntity(cod);
        h.assertTrue(Abilities.runSkill((ArcanaMob) f, "ArcanaPockie_GlowPocket", owner), "Glow pocket skill not registered");
        h.runAfterDelay(16, () -> {
            h.assertTrue(z.hasEffect(MobEffects.GLOWING), "the zombie near its owner is not marked");
            h.assertTrue(cod.hasEffect(MobEffects.GLOWING), "the cod near its owner in water is not marked");
            h.assertTrue(owner.hasEffect(MobEffects.NIGHT_VISION), "no Night Vision for its owner under water");
            z.removeEffect(MobEffects.GLOWING);
            Abilities.runSkill((ArcanaMob) f, "ArcanaPockie_GlowPocket", owner);
            h.runAfterDelay(16, () -> {
                h.assertTrue(!z.hasEffect(MobEffects.GLOWING), "the glow pocket did not rest after squirting");
                for (var e : List.of(z, cod, f)) e.discard();
                CT.disconnect(owner);
                h.succeed();
            });
        });
    }
}
