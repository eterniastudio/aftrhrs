package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaSpawner;
import net.axther.aftrhrs.entity.PenguinBoats;
import net.axther.aftrhrs.entity.PenguinCreature;
import net.axther.aftrhrs.entity.Species;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Penguins: colonies spawn in their biomes near water; the boat boost (wild pushers capped, they let go out of their
 * habitat, a pet in the back seat, the thrust the boat really gets), the sled on snow (glides, climbs a 1-block snow
 * step; a boat without a penguin crawls), the fishing buddy (one session per player per cooldown, at most 3 extra fish,
 * real fish items outside the journal count), and raising a chick into an adult pet (the pet record follows).
 */
public class PenguinTests {
    private static Species species(String id) {
        return ArcanaEntities.species(ArcanaEntities.type(id));
    }

    private static void paint(GameTestHelper h, BlockPos relFrom, BlockPos relTo, String biome) {
        ServerLevel level = h.getLevel();
        var holder = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome)));
        FillBiomeCommand.fill(level, h.absolutePos(relFrom), h.absolutePos(relTo), holder);
    }

    /** Fills a box (relative, inclusive) with a block. */
    private static void fill(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) h.getLevel().setBlock(h.absolutePos(new BlockPos(x, y, z)), s, 2);
    }

    /** Open water: x 2..30, z 2..30, y 1..4 (stone floor at 0), air above. */
    private static void sea(GameTestHelper h) {
        CT.arena(h, 32, 32);
        fill(h, 1, 1, 1, 31, 4, 31, Blocks.STONE.defaultBlockState());
        fill(h, 2, 1, 2, 30, 4, 30, Blocks.WATER.defaultBlockState());
        fill(h, 1, 5, 1, 31, 8, 31, Blocks.AIR.defaultBlockState());
    }

    private static AbstractBoat boat(GameTestHelper h, Vec3 rel) {
        AbstractBoat b = (AbstractBoat) EntityTypes.OAK_BOAT.create(h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = h.absoluteVec(rel);
        b.snapTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(b);
        return b;
    }

    private static PenguinCreature penguin(GameTestHelper h, String id, Vec3 rel) {
        PenguinCreature p = CT.spawn(h, species(id), rel);
        p.setPersistenceRequired();
        return p;
    }

    /** The boat's paddling step as the steering client runs it (forward key held): the velocity it adds this tick. */
    private static double paddle(AbstractBoat b) {
        b.setInput(false, false, true, false);
        Vec3 before = b.getDeltaMovement();
        CT.call(b, "controlBoat");
        return b.getDeltaMovement().subtract(before).horizontalDistance();
    }

    // ------------------------------------------------------------------ spawning

    /** Emperors spawn as a colony (adults + chicks) on snowy beaches near water, rockhoppers on stony shores; not inland. */
    @GameTest(maxTicks = 60, padding = 40, skyAccess = true)
    public void penguinColoniesSpawnByTheWater(GameTestHelper h) {
        CT.arena(h, 40, 12);
        fill(h, 0, 1, 0, 6, 2, 12, Blocks.WATER.defaultBlockState());
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(39, 1, 6));
        List<String> problems = new ArrayList<>();
        try {
            for (String[] c : new String[][]{{"emperor_penguin", "minecraft:snowy_beach"}, {"rockhopper_penguin", "minecraft:stony_shore"}}) {
                paint(h, new BlockPos(-2, 0, -2), new BlockPos(42, 8, 14), c[1]);
                Mob m = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(10, 1, 6)), s -> s.id.equals(c[0]));
                if (m == null) {
                    problems.add("no " + c[0] + " on " + c[1] + " by the water");
                } else {
                    List<PenguinCreature> colony = h.getLevel().getEntitiesOfClass(PenguinCreature.class, m.getBoundingBox().inflate(12));
                    long adults = colony.stream().filter(x -> !x.chick()).count();
                    long chicks = colony.stream().filter(PenguinCreature::chick).count();
                    if (adults < 3) problems.add(c[0] + " colony has " + adults + " adults");
                    if (c[0].startsWith("emperor") && chicks < 1) problems.add("emperor colony without chicks");
                    colony.forEach(PenguinCreature::discard);
                }
                // inland (no water or ice within 12 blocks): nothing
                Mob dry = ArcanaSpawner.probe(p, h.absolutePos(new BlockPos(34, 1, 6)), s -> s.id.equals(c[0]));
                if (dry != null) {
                    problems.add(c[0] + " spawned far from water");
                    h.getLevel().getEntitiesOfClass(PenguinCreature.class, dry.getBoundingBox().inflate(12)).forEach(PenguinCreature::discard);
                }
            }
            for (String biome : List.of("minecraft:snowy_beach", "minecraft:frozen_ocean", "minecraft:ice_spikes", "minecraft:snowy_plains", "terralith:ice_marsh")) {
                if (ArcanaSpawner.rulesAt("minecraft:overworld", biome).stream().noneMatch(r -> r.species.id.equals("emperor_penguin"))) problems.add("no emperor rule for " + biome);
            }
            for (String biome : List.of("minecraft:stony_shore", "minecraft:snowy_beach", "terralith:gravel_beach")) {
                if (ArcanaSpawner.rulesAt("minecraft:overworld", biome).stream().noneMatch(r -> r.species.id.equals("rockhopper_penguin"))) problems.add("no rockhopper rule for " + biome);
            }
        } finally {
            CT.disconnect(p);
        }
        CT.report(h, "penguin spawns", problems);
        h.succeed();
    }

    // ------------------------------------------------------------------ boats

    /**
     * Wild penguins near a player's boat in their waters push it: never more than 4 count (6 sent: still +40%), the
     * thrust the boat gets per paddle is vanilla's x (1 + boost), and they all let go when the boat leaves their habitat.
     */
    @GameTest(maxTicks = 900, padding = 40, skyAccess = true)
    public void wildPushersAreCappedAndLeaveOutsideTheirHabitat(GameTestHelper h) {
        sea(h);
        paint(h, new BlockPos(-2, 0, -2), new BlockPos(34, 10, 34), "minecraft:frozen_ocean");
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(16, 5, 16));
        AbstractBoat b = boat(h, new Vec3(16.5, 4.6, 16.5));
        p.startRiding(b, true, false);
        List<PenguinCreature> wild = new ArrayList<>();
        for (int i = 0; i < 6; i++) wild.add(penguin(h, "emperor_penguin", new Vec3(10.5 + i * 2, 2, 12.5)));
        double vanilla = 0.04;
        // they find the boat on their own (35 % a second each): within 15 s some push, never more than 4
        h.runAfterDelay(300, () -> {
            int sent = PenguinCreature.countPushing(h.getLevel(), b);
            h.assertTrue(sent >= 1, "no wild penguin started pushing the boat in 15 s");
            h.assertTrue(sent <= PenguinBoats.WILD_CAP, sent + " penguins push one boat (cap " + PenguinBoats.WILD_CAP + ")");
            // the server sends all six: the boost still counts only four
            long now = h.getLevel().getGameTime();
            for (PenguinCreature w : wild) if (w.pushing() < 0) w.startPushing(b, now);
        });
        h.runAfterDelay(340, () -> {
            int pushers = PenguinBoats.wildPushers(b);
            h.assertTrue(pushers >= 5, "pushers in the water near the boat: " + pushers);
            double boost = PenguinBoats.boost(b);
            h.assertTrue(Math.abs(boost - PenguinBoats.WILD_CAP * PenguinBoats.WILD_EACH) < 1e-9, "6 pushers give boost " + boost + ", expected the cap 0.4");
            double dv = paddle(b);
            h.assertTrue(Math.abs(dv - vanilla * (1 + boost)) < 1e-6, "paddle thrust " + dv + " (vanilla " + vanilla + ", boost " + boost + ")");
            // the boat leaves their waters: they let go
            paint(h, new BlockPos(-2, 0, -2), new BlockPos(34, 10, 34), "minecraft:warm_ocean");
        });
        h.runAfterDelay(380, () -> {
            for (PenguinCreature w : wild) h.assertTrue(w.pushing() < 0, "a penguin still pushes a boat outside its habitat");
            h.assertTrue(PenguinBoats.boost(b) == 0, "boost outside their habitat: " + PenguinBoats.boost(b));
            h.assertTrue(Math.abs(paddle(b) - vanilla) < 1e-6, "paddle thrust without penguins is not vanilla");
            wild.forEach(PenguinCreature::discard);
            p.stopRiding();
            b.discard();
            CT.disconnect(p);
            h.succeed();
        });
    }

    /**
     * A grown pet penguin hops into the back seat of its owner's boat (boat clips held), gives its boost for as long as it
     * rides (+40% at bond 0, +50% at bond 5; with 4 wild pushers the total stays capped at 60%), and hops out when the
     * owner gets out. A penguin chick never boards.
     */
    @GameTest(maxTicks = 600, padding = 40, skyAccess = true)
    public void petPenguinRidesInTheBackSeat(GameTestHelper h) {
        sea(h);
        paint(h, new BlockPos(-2, 0, -2), new BlockPos(34, 10, 34), "minecraft:frozen_ocean");
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(16, 5, 16));
        AbstractBoat b = boat(h, new Vec3(16.5, 4.6, 16.5));
        owner.startRiding(b, true, false);
        PenguinCreature pet = penguin(h, "emperor_penguin", new Vec3(18.5, 2, 16.5));
        PetsTests.withCap(1000, () -> pet.tamedBy(owner));
        // Each step waits for what it needs (fixed delays ran out when the full suite loads the server).
        when(h, () -> pet.getVehicle() == b, 200, () -> "the pet penguin did not hop into its owner's boat (vehicle " + pet.getVehicle() + ")", () -> {
            h.assertTrue(b.getPassengers().indexOf(pet) == 1, "the penguin is not in the back seat");
            h.assertTrue(pet.holdClip().startsWith("boat_"), "boat clip while riding: " + pet.holdClip());
            h.assertTrue(Math.abs(PenguinBoats.boost(b) - 0.40) < 1e-9, "pet boost at bond 0: " + PenguinBoats.boost(b));
            pet.setBond(5);
            h.assertTrue(Math.abs(PenguinBoats.boost(b) - 0.50) < 1e-9, "pet boost at bond 5: " + PenguinBoats.boost(b));
            h.assertTrue(Math.abs(paddle(b) - 0.04 * 1.5) < 1e-6, "paddle thrust with the pet aboard");
            long now = h.getLevel().getGameTime();
            for (int i = 0; i < 4; i++) penguin(h, "emperor_penguin", new Vec3(14.5 + i, 2, 13.5)).startPushing(b, now);
            h.runAfterDelay(10, () -> {
                h.assertTrue(PenguinBoats.boost(b) <= PenguinBoats.TOTAL_CAP + 1e-9, "total boost above the cap: " + PenguinBoats.boost(b));
                owner.stopRiding();
                when(h, () -> pet.getVehicle() == null, 120, () -> "the penguin stayed in the boat after its owner got out", () -> {
                    h.assertTrue(!pet.holdClip().startsWith("boat_"), "boat clip after getting out: " + pet.holdClip());
                    h.getLevel().getEntitiesOfClass(PenguinCreature.class, b.getBoundingBox().inflate(20)).forEach(PenguinCreature::discard);
                    b.discard();
                    CT.disconnect(owner);
                    h.succeed();
                });
            });
        });
    }

    /** Runs {@code then} as soon as {@code cond} holds (checked every tick), failing with {@code fail} after {@code ticks}. */
    private static void when(GameTestHelper h, java.util.function.BooleanSupplier cond, int ticks, java.util.function.Supplier<String> fail, Runnable then) {
        if (cond.getAsBoolean()) {
            then.run();
            return;
        }
        if (ticks <= 0) throw h.assertionException(net.minecraft.network.chat.Component.literal(fail.get()));
        h.runAfterDelay(1, () -> when(h, cond, ticks - 1, fail, then));
    }

    /**
     * On snow a boat with a pet penguin aboard is a sled: driven for 4 s it glides several times as far as a boat
     * without one (which crawls) and climbs a 1-block snow step; with a penguin it is still slower than on water.
     */
    @GameTest(maxTicks = 300, padding = 40, skyAccess = true)
    public void penguinBoatSledsOnSnow(GameTestHelper h) {
        // The arena (and its force-loaded chunks) covers the whole lanes: a sled that runs into a chunk that isn't
        // ticking freezes there, which cut runs short at wherever the chunk border fell (11.6 to 16.4 blocks).
        CT.arena(h, 40, 36);
        // two snow lanes along +z, each with a 1-block snow step at z 12
        fill(h, 0, 1, 0, 12, 3, 34, Blocks.AIR.defaultBlockState());
        fill(h, 0, 0, 0, 12, 0, 34, Blocks.SNOW_BLOCK.defaultBlockState());
        fill(h, 0, 1, 12, 12, 1, 34, Blocks.SNOW_BLOCK.defaultBlockState());
        ServerPlayer withP = CT.player(h, GameType.SURVIVAL, new Vec3(3, 1, 3));
        ServerPlayer without = CT.player(h, GameType.SURVIVAL, new Vec3(9, 1, 3));
        AbstractBoat sled = boat(h, new Vec3(3.5, 1, 3.5));
        AbstractBoat plain = boat(h, new Vec3(9.5, 1, 3.5));
        withP.startRiding(sled, true, false);
        without.startRiding(plain, true, false);
        PenguinCreature pet = penguin(h, "rockhopper_penguin", new Vec3(3.5, 1, 5.5));
        PetsTests.withCap(1000, () -> pet.tamedBy(withP));
        pet.startRiding(sled, true, false);
        Vec3 s0 = sled.position(), p0 = plain.position();
        int[] sledTicks = {0};
        double[] top = {0};
        Vec3[] last = {s0};
        StringBuilder path = new StringBuilder();
        for (int t = 1; t <= 80; t++) {
            h.runAfterDelay(t, () -> {
                paddle(sled);
                paddle(plain);
                if (PenguinBoats.sledding(sled)) sledTicks[0]++;
                top[0] = Math.max(top[0], sled.position().subtract(last[0]).horizontalDistance());
                last[0] = sled.position();
                if (h.getTick() % 8 == 0) path.append(String.format(java.util.Locale.ROOT, " z%.1f/y%.2f/v%.3f", sled.getZ() - s0.z, sled.getY() - s0.y, sled.getDeltaMovement().horizontalDistance()));
            });
        }
        h.runAfterDelay(90, () -> {
            h.assertTrue(PenguinBoats.sledding(sled) || sled.getY() > s0.y + 0.5, "the penguin boat is not a sled on snow");
            double ds = sled.position().subtract(s0).horizontalDistance(), dp = plain.position().subtract(p0).horizontalDistance();
            h.assertTrue(ds > dp * 2.5, "sled went " + ds + " blocks, a plain boat " + dp + " (expected several times as far); sledding "
                    + sledTicks[0] + "/80 ticks, top speed " + top[0] + " blocks/tick");
            h.assertTrue(sled.getY() >= s0.y + 0.9, "the sled did not climb the 1-block snow step (y " + s0.y + " -> " + sled.getY() + "); path" + path);
            h.assertTrue(plain.getY() < p0.y + 0.5, "a boat without a penguin climbed the snow step");
            // slower than on water: 80 ticks of paddling on water reach ~0.36 blocks/tick, the sled about 0.23
            h.assertTrue(ds / 80 < 0.36, "the sled is as fast as a boat on water: " + ds / 80 + " blocks/tick");
            net.axther.aftrhrs.AftrHrs.LOG.info("penguin sled: {} blocks (plain boat {}), top speed {}, path{}", ds, dp, top[0], path);
            pet.discard();
            withP.stopRiding();
            without.stopRiding();
            sled.discard();
            plain.discard();
            CT.disconnect(withP);
            CT.disconnect(without);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ fishing buddy

    /**
     * A fish fed to a wild adult starts a fishing buddy session; a second penguin won't start another for that player
     * until the cooldown is over (its fish is not eaten). Catches with the buddy near: the journal counts each catch
     * once, extra fish are real fish items of the same species and no heavier, at most 3 a session.
     */
    @GameTest(maxTicks = 200, padding = 40, skyAccess = true)
    public void fishingBuddyDoublesWithCooldown(GameTestHelper h) {
        sea(h);
        PenguinCreature.resetBuddyCooldowns();
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(16, 5, 1));
        PenguinCreature a = penguin(h, "rockhopper_penguin", new Vec3(14.5, 2, 4.5));
        PenguinCreature b2 = penguin(h, "emperor_penguin", new Vec3(18.5, 2, 4.5));
        List<String> problems = new ArrayList<>();
        h.runAfterDelay(2, () -> {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COD, 4));
            a.interact(p, InteractionHand.MAIN_HAND, a.position());
            if (!p.getUUID().equals(a.buddy())) problems.add("feeding a fish did not start a fishing buddy session");
            if (p.getMainHandItem().getCount() != 3) problems.add("the buddy's fish was not eaten: " + p.getMainHandItem().getCount());
            long now = h.getLevel().getGameTime();
            if (PenguinCreature.buddyReadyAt(p.getUUID()) < now + PenguinCreature.BUDDY_PLAYER_COOLDOWN - 5) problems.add("no player cooldown after starting a session");
            b2.interact(p, InteractionHand.MAIN_HAND, b2.position());
            if (b2.buddy() != null) problems.add("a second penguin started a session during the cooldown");
            if (p.getMainHandItem().getCount() != 3) problems.add("a fish was eaten during the cooldown");
        });
        h.runAfterDelay(6, () -> {
            Object m = CT.get(CT.cls("net.axther.aftrhrs.feature.fishing.FishingModule"), "active");
            if (m == null) {
                problems.add("fishing module not active");
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> table = (Map<String, Object>) CT.get(CT.get(m, "table"), "species");
                String sid = null;
                for (Map.Entry<String, Object> e : table.entrySet()) {
                    int rank = (int) CT.call(e.getValue(), "rank");
                    if (rank == 1) {
                        sid = e.getKey();
                        break;
                    }
                }
                @SuppressWarnings("unchecked")
                Map<UUID, String> force = (Map<UUID, String>) CT.get(m, "debugForce");
                FishingHook hook = new FishingHook(p, h.getLevel(), 0, 0);
                hook.snapTo(a.getX(), a.getY() + 1, a.getZ(), 0, 0);
                int catches = 0, extra = 0;
                for (int i = 0; i < 60 && a.buddyDoubles() < PenguinCreature.BUDDY_MAX_DOUBLES; i++) {
                    force.put(p.getUUID(), sid);
                    List<ItemStack> loot = new ArrayList<>(List.of(new ItemStack(Items.COD)));
                    FeatherEvents.FISH_CATCH.invoker().caught(hook, p, new ItemStack(Items.FISHING_ROD), loot);
                    catches++;
                    if (loot.size() == 2) {
                        extra++;
                        String a0 = (String) CT.call(m, "fishId", loot.get(0)), a1 = (String) CT.call(m, "fishId", loot.get(1));
                        if (!a1.endsWith(sid) || !a0.equals(a1)) problems.add("extra fish is " + a1 + ", caught " + a0 + " (species " + sid + ")");
                    } else if (loot.size() != 1) {
                        problems.add("loot size " + loot.size());
                    }
                }
                if (extra != PenguinCreature.BUDDY_MAX_DOUBLES) problems.add("extra fish in " + catches + " catches: " + extra + " (expected the max " + PenguinCreature.BUDDY_MAX_DOUBLES + ")");
                // one more catch after the max: no more doubles
                force.put(p.getUUID(), sid);
                List<ItemStack> loot = new ArrayList<>(List.of(new ItemStack(Items.COD)));
                FeatherEvents.FISH_CATCH.invoker().caught(hook, p, new ItemStack(Items.FISHING_ROD), loot);
                catches++;
                if (loot.size() != 1) problems.add("an extra fish after the session's max");
                Object journal = CT.call(CT.get(m, "data"), "journal", p.getUUID());
                @SuppressWarnings("unchecked")
                Map<String, Object> js = (Map<String, Object>) CT.get(journal, "species");
                int count = js.get(sid) == null ? 0 : (int) CT.get(js.get(sid), "count");
                if (count != catches) problems.add("journal counts " + count + " for " + catches + " catches (" + extra + " extra fish)");
                hook.discard();
            }
            a.discard();
            b2.discard();
            CT.disconnect(p);
            PenguinCreature.resetBuddyCooldowns();
            CT.report(h, "fishing buddy", problems);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ raising a chick

    /** A raw fish tames a wild chick (1 in 3); fed, it grows; grown, it is the adult type, still the owner's pet (same record). */
    @GameTest(maxTicks = 1600, padding = 32)
    public void chickIsRaisedIntoAnAdultPet(GameTestHelper h) {
        CT.arena(h, 10, 10);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(2, 1, 2));
        PenguinCreature chick = penguin(h, "emperor_penguin_chick", new Vec3(5.5, 1, 5.5));
        PenguinCreature adultWild = penguin(h, "emperor_penguin", new Vec3(8.5, 1, 8.5));
        // an adult can't be tamed with fish (it goes fishing instead)
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SALMON, 2));
        adultWild.interact(owner, InteractionHand.MAIN_HAND, adultWild.position());
        h.assertTrue(!adultWild.isTame(), "a wild adult penguin got tamed with a fish");
        adultWild.discard();
        PenguinCreature.resetBuddyCooldowns();
        tame(h, owner, chick, 0, () -> {
            h.assertTrue(chick.isTame(), "the chick was not tamed with 40 fish");
            List<Object> pets = PetsTests.petsOf(owner.getUUID());
            h.assertTrue(pets.size() == 1 && "ArcanaEmperorPenguinChick".equals(CT.get(pets.get(0), "species")), "pet record after taming: " + pets);
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COD, 2));
            chick.scheduler().clearCooldown("grow_feed");
            chick.interact(owner, InteractionHand.MAIN_HAND, chick.position());
            h.assertTrue(chick.var("fed") > 0 && chick.var("grow") >= PenguinCreature.GROW_BONUS, "feeding the tamed chick: fed " + chick.var("fed") + " grow " + chick.var("grow"));
            int before = chick.var("grow");
            h.runAfterDelay(41, () -> {
                h.assertTrue(chick.var("grow") > before, "a fed chick does not grow");
                chick.setVar("grow", PenguinCreature.GROW_TICKS - 20);
                // the record is the truth for bond (the pets module puts it on the grown-up)
                CT.set(PetsTests.petsOf(owner.getUUID()).get(0), "level", 2);
                chick.setBond(2);
            });
            h.runAfterDelay(90, () -> {
                h.assertTrue(chick.isRemoved(), "the chick did not grow up");
                List<PenguinCreature> adults = h.getLevel().getEntitiesOfClass(PenguinCreature.class, owner.getBoundingBox().inflate(16), c -> !c.chick() && c.isTame());
                h.assertTrue(adults.size() == 1, "grown adults: " + adults.size());
                PenguinCreature adult = adults.get(0);
                h.assertTrue(adult.getOwner() == owner, "the grown penguin lost its owner");
                h.assertValueEqual(adult.bond(), 2, "bond after growing up");
                List<Object> after = PetsTests.petsOf(owner.getUUID());
                h.assertTrue(after.size() == 1, "pet records after growing up: " + after.size());
                h.assertValueEqual(CT.get(after.get(0), "species"), "ArcanaEmperorPenguin", "record species after growing up");
                h.assertValueEqual(CT.get(after.get(0), "entity"), adult.getUUID(), "record entity after growing up");
                adult.discard();
                CT.disconnect(owner);
                h.succeed();
            });
        });
    }

    private static void tame(GameTestHelper h, ServerPlayer owner, PenguinCreature c, int tries, Runnable then) {
        if (c.isTame() || tries >= 40) {
            then.run();
            return;
        }
        PetsTests.withCap(1000, () -> {
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COD, 8));
            c.interact(owner, InteractionHand.MAIN_HAND, c.position());
        });
        h.runAfterDelay(22, () -> tame(h, owner, c, tries + 1, then));
    }
}
