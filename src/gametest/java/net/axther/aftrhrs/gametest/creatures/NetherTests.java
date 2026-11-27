package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Creatures 2 / Nether: the Soulmander (soul sand valley pet), Fumetail (warped forest pet) and Shroomwallow (crimson
 * forest livestock) spawn in their own Nether biome through the additive spawner, the pets' Java abilities work
 * (soul ward, soul stride, fungal musk on hoglins, stink spray), they tame and sit, and the Shroomwallow's brush
 * harvest gives shroomlight once per cooldown.
 */
public class NetherTests {
    private static Species species(String id) {
        return ArcanaEntities.species(ArcanaEntities.type(id));
    }

    // ------------------------------------------------------------------ spawning in the Nether

    /**
     * A stone pad far out in the Nether (force-loaded, painted with the biome), a player in the Nether: one probe at a
     * spot 30 blocks away gives the biome's new species, and never another biome's.
     */
    @GameTest(maxTicks = 60)
    public void spawnsInTheirNetherBiome(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "no nether");
        String[][] cases = {{"soulmander", "minecraft:soul_sand_valley", "fumetail"},
                {"fumetail", "minecraft:warped_forest", "soulmander"},
                {"shroomwallow", "minecraft:crimson_forest", "fumetail"}};
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        p.setPermanentlyInvulnerable(true);
        List<Entity> made = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            int k = 0;
            for (String[] c : cases) {
                BlockPos origin = new BlockPos(24000 + 200 * k++, 100, 24000);
                pad(nether, origin, c[1]);
                BlockPos at = origin.offset(0, 1, 0);
                p.teleport(new TeleportTransition(nether, Vec3.atBottomCenterOf(origin.offset(-30, 1, 0)), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
                String want = c[0];
                h.assertTrue(ArcanaSpawner.rulesAt("minecraft:the_nether", c[1]).stream().anyMatch(r -> r.species.id.equals(want)),
                        want + " has no rule for " + c[1]);
                Mob other = ArcanaSpawner.probe(p, at, s -> s.id.equals(c[2]));
                if (other != null) {
                    made.add(other);
                    problems.add(c[2] + " spawned in " + c[1]);
                }
                Mob m = ArcanaSpawner.probe(p, at, s -> s.id.equals(want));
                if (m == null) {
                    problems.add("no " + want + " in " + c[1]);
                    continue;
                }
                made.add(m);
                if (!ArcanaSpawner.isWild(m)) problems.add(want + " spawned not wild");
                if (m.level() != nether) problems.add(want + " spawned outside the Nether");
            }
        } finally {
            for (Entity e : made) if (e != null && !e.isRemoved()) e.discard();
            CT.disconnect(p);
        }
        CT.report(h, "nether spawns", problems);
        h.succeed();
    }

    private static void pad(ServerLevel level, BlockPos origin, String biome) {
        for (int cx = (origin.getX() - 40) >> 4; cx <= (origin.getX() + 8) >> 4; cx++) {
            for (int cz = (origin.getZ() - 8) >> 4; cz <= (origin.getZ() + 8) >> 4; cz++) level.setChunkForced(cx, cz, true);
        }
        for (int x = -40; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 1; y <= 4; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        var holder = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome)));
        FillBiomeCommand.fill(level, origin.offset(-8, -2, -8), origin.offset(8, 6, 8), holder);
    }

    // ------------------------------------------------------------------ abilities

    /** Soul ward burns the Wither off the owner; soul stride gives Speed on soul sand. */
    @GameTest(maxTicks = 40, padding = 8)
    public void soulmanderWardAndStride(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 4));
        owner.setPermanentlyInvulnerable(true);
        ArcanaCreature c = PetsTests.tamed(h, species("soulmander"), owner, new Vec3(5, 1, 4));
        h.assertTrue(species("soulmander").tameItem() == Items.GHAST_TEAR, "Soulmander tame item is not a ghast tear");
        owner.addEffect(new MobEffectInstance(MobEffects.WITHER, 200, 1));
        h.assertTrue(Abilities.runSkill(c, "ArcanaSoulmander_SoulWard", owner), "soul ward is not registered");
        h.assertFalse(owner.hasEffect(MobEffects.WITHER), "the Soulmander did not burn the Wither off its owner");
        h.setBlock(new BlockPos(4, 0, 4), Blocks.SOUL_SAND);
        owner.snapTo(h.absoluteVec(new Vec3(4.5, 1, 4.5)));
        owner.removeEffect(MobEffects.SPEED);
        Abilities.runSkill(c, "ArcanaSoulmander_SoulStride", owner);
        h.assertTrue(owner.hasEffect(MobEffects.SPEED), "no Speed on soul sand");
        // a staying Soulmander does nothing
        c.setStaying(true, owner);
        owner.addEffect(new MobEffectInstance(MobEffects.WITHER, 200, 1));
        Abilities.runSkill(c, "ArcanaSoulmander_SoulWard", owner);
        h.assertTrue(owner.hasEffect(MobEffects.WITHER), "a staying Soulmander still warded");
        c.discard();
        CT.disconnect(owner);
        h.succeed();
    }

    /** Fungal musk: a hoglin attacking the Fumetail's owner drops its target and is pacified (warped fungus rules). */
    @GameTest(maxTicks = 40, padding = 8)
    public void fumetailMuskPacifiesHoglins(GameTestHelper h) {
        CT.arena(h, 12, 12);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 4));
        owner.setPermanentlyInvulnerable(true);
        ArcanaCreature c = PetsTests.tamed(h, species("fumetail"), owner, new Vec3(5, 1, 4));
        Hoglin hog = h.spawn(EntityTypes.HOGLIN, new BlockPos(9, 1, 8));
        hog.setImmuneToZombification(true);
        hog.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, owner);
        h.assertTrue(Abilities.runSkill(c, "ArcanaFumetail_Musk", owner), "fungal musk is not registered");
        h.assertFalse(hog.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET), "the hoglin still targets the owner");
        h.assertTrue(hog.getBrain().hasMemoryValue(MemoryModuleType.PACIFIED), "the hoglin is not pacified");
        // a hoglin far away is left alone
        Hoglin far = h.spawn(EntityTypes.HOGLIN, new BlockPos(4, 1, 4).offset(0, 0, 0));
        far.snapTo(owner.getX() + 40, owner.getY(), owner.getZ());
        far.setImmuneToZombification(true);
        Abilities.runSkill(c, "ArcanaFumetail_Musk", owner);
        h.assertFalse(far.getBrain().hasMemoryValue(MemoryModuleType.PACIFIED), "a hoglin 40 blocks away was pacified");
        // stink spray: a monster that hits the owner gets Weakness
        Zombie z = h.spawn(EntityTypes.ZOMBIE, new BlockPos(6, 1, 6));
        Abilities.ownerHurt(owner, z);
        h.assertTrue(z.hasEffect(MobEffects.WEAKNESS), "the Fumetail did not spray the zombie");
        for (Entity e : List.of(c, hog, far, z)) if (!e.isRemoved()) e.discard();
        CT.disconnect(owner);
        h.succeed();
    }

    /** Both pets tame with their item (real clicks, one try per second as in play) and sit when told to stay. */
    @GameTest(maxTicks = 500, padding = 8)
    public void petsTameAndSit(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 4));
        p.setPermanentlyInvulnerable(true);
        List<ArcanaCreature> pets = new ArrayList<>();
        int i = 0;
        for (String id : List.of("soulmander", "fumetail")) {
            ArcanaCreature c = CT.spawn(h, species(id), new Vec3(2 + 3 * i++, 1, 6));
            c.setPersistenceRequired();
            c.setPermanentlyInvulnerable(true);
            pets.add(c);
        }
        Object m = PetsTests.module();
        int was = (int) CT.get(m, "defaultCap");
        CT.set(m, "defaultCap", 1000);
        for (int k = 0; k < 20; k++) {
            h.runAfterDelay(1 + k * 22L, () -> {
                for (ArcanaCreature c : pets) {
                    if (c.isTame()) continue;
                    ItemStack stack = PetsTests.tameItem(c.species());
                    stack.setCount(5);
                    p.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    PetsTests.use(p, c);
                }
            });
        }
        h.runAfterDelay(20 * 22L + 5, () -> {
            CT.set(m, "defaultCap", was);
            List<String> problems = new ArrayList<>();
            for (ArcanaCreature c : pets) {
                String id = c.species().id;
                if (!c.isTame()) {
                    problems.add(id + " not tamed after 20 tries");
                } else {
                    c.setStaying(true, p);
                    if (!c.staying() || !c.isOrderedToSit()) problems.add(id + " does not sit when told to stay");
                }
                c.discard();
            }
            CT.disconnect(p);
            CT.report(h, "nether pets", problems);
            h.succeed();
        });
    }

    /** Brushing a Shroomwallow gives shroomlight; a second brush right after finds it on cooldown. */
    @GameTest(maxTicks = 40, padding = 8)
    public void shroomwallowBrush(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 1));
        ArcanaWildlife w = CT.spawn(h, species("shroomwallow"), new Vec3(4, 1, 5));
        w.setKept(true);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BRUSH));
        UseEntityCallback.EVENT.invoker().interact(p, p.level(), InteractionHand.MAIN_HAND, w, null);
        int n = count(p);
        h.assertTrue(n == 1, "brushing a Shroomwallow gave " + n + " shroomlight");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BRUSH));
        UseEntityCallback.EVENT.invoker().interact(p, p.level(), InteractionHand.MAIN_HAND, w, null);
        h.assertValueEqual(count(p), 1, "shroomlight after brushing twice in a row");
        h.assertTrue(((ArcanaMob) w).species().young() == false && species("shroomwallow_young").young(), "young form missing");
        w.discard();
        CT.disconnect(p);
        h.succeed();
    }

    private static int count(ServerPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (s.is(Items.SHROOMLIGHT)) n += s.getCount();
        return n;
    }
}
