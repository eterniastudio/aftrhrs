package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaSpawner;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.axther.aftrhrs.entity.Species;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.axther.aftrhrs.entity.ability.EndSkills;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Creatures 2, the End: every vanilla and Stellarity End biome has Arcana life, the Chorusling / Shulkling / Voidpuff
 * spawn in the real End through the additive spawner, the Chorusling pulls its owner back out of the void, the
 * Shulkling calms endermen and turns levitation into slow falling, the Voidpuff floats and gives its breath to a bottle,
 * and both pets tame and sit.
 */
public class EndTests {
    static final List<String> VANILLA_END = List.of("minecraft:the_end", "minecraft:end_highlands", "minecraft:end_midlands",
            "minecraft:end_barrens", "minecraft:small_end_islands");
    static final List<String> STELLARITY = List.of("amethyst_forest", "ashfall_deltas", "blazing_dunes", "crystal_crags",
            "dragons_den", "ender_wastes", "endless_dunes", "end_shrubland", "end_wilds", "fiery_hills", "flesh_tundra",
            "frosted_hills", "frosted_valley", "frozen_marsh", "frozen_shrubland", "frozen_spikes", "hallowed_tundra",
            "prismarine_forest", "prismatic_dunes", "sculk_growth", "the_hallow", "the_nest", "the_void", "warped_marsh");
    static final Set<String> MINE = Set.of("chorusling", "shulkling", "voidpuff");

    private static Species species(String id) {
        return ArcanaEntities.species(ArcanaEntities.type(id));
    }

    private static void click(ServerPlayer p, Entity e, ItemStack held) {
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        UseEntityCallback.EVENT.invoker().interact(p, p.level(), InteractionHand.MAIN_HAND, e, null);
    }

    /** Every End biome (5 vanilla, 24 Stellarity) has at least one of the new End creatures in the spawner's index. */
    @GameTest(maxTicks = 20)
    public void everyEndBiomeHasArcanaLife(GameTestHelper h) {
        List<String> problems = new ArrayList<>();
        List<String> all = new ArrayList<>(VANILLA_END);
        for (String s : STELLARITY) all.add("stellarity:" + s);
        for (String b : all) {
            var rules = ArcanaSpawner.rulesAt("minecraft:the_end", b);
            if (rules.stream().noneMatch(r -> MINE.contains(r.species.id))) problems.add(b + " has no End creature");
        }
        // two tameables and a wildlife with a harvest
        h.assertTrue(species("chorusling").kind == Species.Kind.PET && species("shulkling").kind == Species.Kind.PET, "the End pets are not pets");
        h.assertTrue(species("voidpuff").kind == Species.Kind.WILDLIFE, "the Voidpuff is not wildlife");
        CT.report(h, "End biome coverage", problems);
        h.succeed();
    }

    /** The additive spawner puts each species on an End island in its biome (a player standing on it in the End). */
    @GameTest(maxTicks = 60, padding = 8)
    public void spawnsInTheEnd(GameTestHelper h) {
        ServerLevel end = h.getLevel().getServer().getLevel(Level.END);
        h.assertTrue(end != null, "no End level");
        BlockPos c = new BlockPos(-240, 90, 260);
        for (int cx = (c.getX() - 24) >> 4; cx <= (c.getX() + 24) >> 4; cx++)
            for (int cz = (c.getZ() - 24) >> 4; cz <= (c.getZ() + 24) >> 4; cz++) end.setChunkForced(cx, cz, true);
        for (int x = -20; x <= 20; x++)
            for (int z = -20; z <= 20; z++) {
                end.setBlockAndUpdate(c.offset(x, -1, z), Blocks.END_STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) end.setBlockAndUpdate(c.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(2, 2, 2));
        p.setPermanentlyInvulnerable(true);
        p.teleport(new TeleportTransition(end, Vec3.atBottomCenterOf(c), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        List<Entity> made = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String[][] cases = {{"chorusling", "minecraft:end_highlands"}, {"chorusling", "minecraft:the_end"},
                {"shulkling", "minecraft:end_midlands"}, {"shulkling", "minecraft:end_barrens"},
                {"voidpuff", "minecraft:small_end_islands"}, {"voidpuff", "minecraft:end_barrens"}};
        h.runAfterDelay(5, () -> {
            try {
                int i = 0;
                for (String[] k : cases) {
                    var holder = end.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(k[1])));
                    FillBiomeCommand.fill(end, c.offset(-20, -2, -20), c.offset(20, 6, 20), holder);
                    BlockPos spot = c.offset(-14 + (i++ % 3) * 14, 0, 12);
                    Mob m = ArcanaSpawner.probe(p, spot, s -> s.id.equals(k[0]));
                    if (m == null) problems.add(k[0] + " did not spawn in " + k[1]);
                    else {
                        made.add(m);
                        if (!ArcanaSpawner.isWild(m)) problems.add(k[0] + " spawned not wild");
                        m.discard();
                    }
                }
                // and never in the Overworld test arena (no overworld rules)
                for (String id : MINE) {
                    for (var r : ArcanaSpawner.rules(species(id))) {
                        if (!r.spawn.dimension().equals("minecraft:the_end")) problems.add(id + " has a rule outside the End: " + r.spawn.dimension());
                    }
                }
            } finally {
                for (Entity e : made) if (!e.isRemoved()) e.discard();
                CT.disconnect(p);
            }
            CT.report(h, "End spawns", problems);
            h.succeed();
        });
    }

    /** Chorusling: remembers where its owner stood, pulls them back up from below the world, then waits its cooldown. */
    @GameTest(maxTicks = 60, padding = 8)
    public void choruslingVoidRescue(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerLevel level = h.getLevel();
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 4));
        ArcanaCreature pet = CT.spawn(h, species("chorusling"), new Vec3(2, 1, 2));
        pet.tamedBy(owner);
        h.runAfterDelay(3, () -> {
            owner.setOnGround(true);
            Abilities.runSkill(pet, "ArcanaChorusling_Anchor", owner);
            Vec3 safe = EndSkills.safeSpot(owner);
            h.assertTrue(safe != null, "the Chorusling remembered no safe spot");
            // fall below the bottom of the world
            owner.teleportTo(owner.getX(), level.getMinY() - 6, owner.getZ());
            owner.setDeltaMovement(0, -3, 0);
            Abilities.runSkill(pet, "ArcanaChorusling_VoidRescue", owner);
            h.assertTrue(owner.getY() > level.getMinY() && Math.abs(owner.getY() - safe.y) < 0.01, "not rescued: y " + owner.getY());
            h.assertTrue(owner.hasEffect(MobEffects.SLOW_FALLING), "no slow falling after the rescue");
            h.assertTrue(owner.fallDistance == 0, "fall distance kept: " + owner.fallDistance);
            // on cooldown: a second fall is not caught
            owner.teleportTo(owner.getX(), level.getMinY() - 6, owner.getZ());
            Abilities.runSkill(pet, "ArcanaChorusling_VoidRescue", owner);
            h.assertTrue(owner.getY() < level.getMinY(), "rescued twice without a cooldown");
            // a stranger's fall is not its business
            owner.teleportTo(safe.x, safe.y, safe.z);
            pet.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** Shulkling: levitation becomes slow falling (sneaking keeps it); endermen at its owner calm down unless provoked. */
    @GameTest(maxTicks = 40, padding = 8)
    public void shulklingWard(GameTestHelper h) {
        CT.arena(h, 10, 10);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 4));
        ArcanaCreature pet = CT.spawn(h, species("shulkling"), new Vec3(2, 1, 2));
        pet.tamedBy(owner);
        Enderman e = h.spawn(EntityTypes.ENDERMAN, new BlockPos(7, 1, 7));
        h.runAfterDelay(3, () -> {
            owner.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 200, 0));
            Abilities.runSkill(pet, "ArcanaShulkling_LevitationWard", owner);
            h.assertFalse(owner.hasEffect(MobEffects.LEVITATION), "levitation not removed");
            h.assertTrue(owner.hasEffect(MobEffects.SLOW_FALLING), "no slow falling");
            owner.removeEffect(MobEffects.SLOW_FALLING);
            owner.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 200, 0));
            owner.setShiftKeyDown(true);
            Abilities.runSkill(pet, "ArcanaShulkling_LevitationWard", owner);
            h.assertTrue(owner.hasEffect(MobEffects.LEVITATION), "sneaking did not keep the levitation");
            owner.setShiftKeyDown(false);
            // stared at: calmed
            e.setTarget(owner);
            h.assertTrue(e.getTarget() == owner, "the enderman would not target the owner (test setup)");
            Abilities.runSkill(pet, "ArcanaShulkling_EnderCalm", owner);
            h.assertTrue(e.getTarget() == null, "the enderman still targets the owner");
            // hit by the owner: the fight stays
            e.setLastHurtByMob(owner);
            e.setTarget(owner);
            h.assertTrue(e.getLastHurtByMob() == owner, "lastHurtByMob not kept (test setup)");
            Abilities.runSkill(pet, "ArcanaShulkling_EnderCalm", owner);
            h.assertTrue(e.getTarget() == owner, "an enderman the owner hit was calmed");
            e.discard();
            pet.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** Voidpuff: floats (no gravity), a glass bottle gets Dragon's Breath once, then it needs time; it drops membrane. */
    @GameTest(maxTicks = 40, padding = 8)
    public void voidpuffBreath(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 1));
        ArcanaWildlife puff = CT.spawn(h, species("voidpuff"), new Vec3(4, 2, 4));
        puff.setKept(true);
        h.assertTrue(puff.isNoGravity() && puff.floats(), "the Voidpuff does not float");
        click(p, puff, new ItemStack(Items.GLASS_BOTTLE));
        boolean got = p.getMainHandItem().is(Items.DRAGON_BREATH)
                || p.getInventory().getNonEquipmentItems().stream().anyMatch(s -> s.is(Items.DRAGON_BREATH));
        h.assertTrue(got, "no Dragon's Breath from a Voidpuff");
        click(p, puff, new ItemStack(Items.GLASS_BOTTLE));
        h.assertTrue(p.getMainHandItem().is(Items.GLASS_BOTTLE), "bottled twice in a row");
        boolean membrane = species("voidpuff").json.toString().contains("phantom_membrane");
        h.assertTrue(membrane, "no phantom membrane kill drop");
        puff.discard();
        CT.disconnect(p);
        h.succeed();
    }

    /** Both End pets tame with their item, sit when told to stay and stand when told to follow. */
    @GameTest(maxTicks = 600, padding = 8)
    public void endPetsTameAndSit(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(4, 1, 1));
        p.setPermanentlyInvulnerable(true);
        List<ArcanaCreature> pets = new ArrayList<>();
        for (String id : List.of("chorusling", "shulkling")) {
            ArcanaCreature c = CT.spawn(h, species(id), new Vec3(id.equals("chorusling") ? 2 : 6, 1, 4));
            c.setPersistenceRequired();
            pets.add(c);
            h.assertTrue(species(id).tameItem() != null, id + " has no tame item");
        }
        for (int k = 0; k < 24; k++) {
            h.runAfterDelay(1 + k * 21L, () -> {
                for (ArcanaCreature c : pets) {
                    if (c.isTame()) continue;
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(c.species().tameItem(), 4));
                    c.snapTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
                    c.interact(p, InteractionHand.MAIN_HAND, c.position());
                }
            });
        }
        h.runAfterDelay(510, () -> {
            for (ArcanaCreature c : pets) {
                h.assertTrue(c.isTame(), c.species().id + " not tamed after 24 tries");
                c.setStaying(true, p);
            }
        });
        h.runAfterDelay(525, () -> {
            for (ArcanaCreature c : pets) h.assertTrue(c.isInSittingPose(), c.species().id + " does not sit when staying");
            for (ArcanaCreature c : pets) c.setStaying(false, p);
        });
        h.runAfterDelay(545, () -> {
            for (ArcanaCreature c : pets) {
                h.assertFalse(c.isInSittingPose(), c.species().id + " still sits when following");
                c.discard();
            }
            CT.disconnect(p);
            h.succeed();
        });
    }
}
