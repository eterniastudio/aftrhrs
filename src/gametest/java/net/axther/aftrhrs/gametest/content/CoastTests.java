package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.ArcanaCropBlock;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.feature.holograms.HologramsModule;
import net.axther.aftrhrs.feature.wildlife.WildlifeModule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Coast & Harvest: Arcana crops planted by hand grow on the farming timers and harvest; fish tanks keep their fish. */
public class CoastTests {
    static void console(MinecraftServer server, String cmd) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
    }

    /** Seeds on farmland plant an Arcana crop; the farming clock ripens it; breaking a ripe crop gives produce + seeds. */
    @GameTest(maxTicks = 200, padding = 12, environment = ToolTests.ENV)
    public void arcanaCropPlantsGrowsAndHarvests(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btFarm", false);
        ServerPlayer p = bot.p;
        BlockPos crop = h.absolutePos(new BlockPos(4, 2, 4));
        CT.clear(level, crop, 3);
        level.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7), 3);
        level.setBlock(crop.below().east(), Blocks.WATER.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("farming");
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("arcana_seeds_tomato", 3));
        CT.pose(p, Vec3.atBottomCenterOf(crop.south(2)), 180, 40);
        CT.click(p, crop.below(), Direction.UP);
        BlockState s = level.getBlockState(crop);
        f.check(s.getBlock() instanceof ArcanaCropBlock, "seeds did not plant a crop: " + CT.id(s));
        f.check(p.getMainHandItem().getCount() == 2, "seed not used: " + p.getMainHandItem());
        if (s.getBlock() instanceof ArcanaCropBlock cb) {
            // three stages on the farming clock (stage-minutes 9-11, plus the retry time): skip two hours ahead
            for (int i = 0; i < 6; i++) {
                console(level.getServer(), "farming debug clock 40");
                console(level.getServer(), "farming debug tick");
            }
            BlockState ripe = level.getBlockState(crop);
            f.check(ripe.is(cb) && cb.isRipe(ripe), "crop did not ripen on the farming clock: " + ripe);
            console(level.getServer(), "farming debug clock -240");
            p.getInventory().clearContent();
            CT.breakBlock(p, crop);
            h.runAfterDelay(3, () -> {
                Map<String, Integer> d = CT.collectDrops(level, crop, 4);
                int inv = p.getInventory().countItem(ArcanaContent.item("arcana_crop_tomato")) + p.getInventory().countItem(ArcanaContent.item("arcana_seeds_tomato"));
                f.check(d.getOrDefault("aftrhrs:arcana_crop_tomato", 0) + inv >= 1, "ripe tomato gave no produce: " + d);
                f.check(!(level.getBlockState(crop).getBlock() instanceof ArcanaCropBlock) || !cb.isRipe(level.getBlockState(crop)), "ripe crop still there after the harvest");
                CT.clear(level, crop, 3);
                bot.leave();
                f.finish(h);
            });
            return;
        }
        CT.clear(level, crop, 3);
        bot.leave();
        f.finish(h);
    }

    /**
     * An aquarium tank takes a cod; breaking drops tank + cod once. A tank removed without updates (/setblock) gives
     * its fish back and the spot forgets it (a new tank there used to get the old fish again).
     */
    @GameTest(maxTicks = 200, padding = 12)
    public void tankKeepsFishAndForgetsOnSetblock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btTank", true);
        ServerPlayer p = bot.p;
        BlockPos at = GadgetTests.floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        CT.Fails f = new CT.Fails("tank");
        GadgetTests.place(p, at, "arcana_aquarium_tank");
        f.check("arcana_aquarium_tank".equals(ArcanaContent.id(level.getBlockState(at))), "tank not placed");
        h.runAfterDelay(25, () -> { // the fishing furniture index picks up new pieces on its 1 s tick
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COD, 2));
            CT.click(p, at, Direction.UP);
            f.check(p.getMainHandItem().getCount() == 1, "tank did not take a cod: " + p.getMainHandItem());
            p.getInventory().clearContent();
            CT.breakBlock(p, at);
            Map<String, Integer> d = CT.collectDrops(level, at, 5);
            f.check(d.equals(Map.of("aftrhrs:arcana_aquarium_tank", 1, "minecraft:cod", 1)), "broken tank dropped " + d);
            // again, then /setblock it away
            GadgetTests.place(p, at, "arcana_aquarium_tank");
            h.runAfterDelay(25, () -> {
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COD, 1));
                CT.click(p, at, Direction.UP);
                console(level.getServer(), "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " air replace");
                h.runAfterDelay(30, () -> {
                    Map<String, Integer> d2 = CT.collectDrops(level, at, 5);
                    f.check(d2.getOrDefault("minecraft:cod", 0) == 1, "/setblock tank: fish not given back: " + d2);
                    GadgetTests.place(p, at, "arcana_aquarium_tank");
                    h.runAfterDelay(25, () -> {
                        p.getInventory().clearContent();
                        p.setShiftKeyDown(true);
                        CT.click(p, at, Direction.UP);
                        p.setShiftKeyDown(false);
                        f.check(p.getInventory().countItem(Items.COD) == 0, "a new tank in the same spot had the old fish");
                        CT.clear(level, at, 5);
                        bot.leave();
                        f.finish(h);
                    });
                });
            });
        });
    }

    /** What the server sent a bot since the last call (bundles opened up). */
    static List<Object> sent(CT.Bot bot) {
        List<Object> out = new ArrayList<>();
        bot.channel.runPendingTasks();
        bot.channel.flushOutbound();
        Object o;
        while ((o = bot.channel.readOutbound()) != null) {
            if (o instanceof BundlePacket<?> b) for (Object sub : b.subPackets()) out.add(sub);
            else out.add(o);
        }
        return out;
    }

    /**
     * The bestiary counts a wild drop once, when it really goes into the inventory: a full inventory touching it for
     * 40 ticks counts nothing, the pickup after a slot frees counts one.
     */
    @GameTest(maxTicks = 120, padding = 12)
    public void bestiaryCountsRealPickupsOnly(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btPickup", false);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("bestiary_pickup");
        WildlifeModule wild = Feather.get().module(WildlifeModule.class);
        Hooks.Bestiary book = Feather.get().service(Hooks.Bestiary.class);
        Collection<?> all = wild == null ? List.of() : (Collection<?>) FurnitureBehaviorTests.field(FurnitureBehaviorTests.field(wild, "bestiary"), "all");
        if (book == null || all.isEmpty()) {
            f.add("no bestiary (wildlife module off or no pages)");
            bot.leave();
            f.finish(h);
            return;
        }
        String mob = String.valueOf(all.iterator().next());
        int start = book.bestiaryMob(p.getUUID(), mob).harvested();
        for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.STONE, 64));   // the main slots
        Vec3 at = p.position();
        ItemEntity drop = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.LEATHER), 0, 0, 0);
        drop.setNoPickUpDelay();
        Pdc.putString(drop, "feather:wild_from", mob);
        level.addFreshEntity(drop);
        // a real client touches the drop every tick it stands on it
        for (int t = 1; t <= 40; t++) h.runAfterDelay(t, () -> {
            if (!drop.isRemoved()) drop.playerTouch(p);
        });
        h.runAfterDelay(42, () -> {
            f.check(!drop.isRemoved(), "a full inventory picked the drop up");
            int full = book.bestiaryMob(p.getUUID(), mob).harvested();
            f.check(full == start, "40 ticks against a full inventory counted " + (full - start) + " harvests");
            p.getInventory().setItem(0, ItemStack.EMPTY);
            drop.playerTouch(p);
            f.check(drop.isRemoved() || drop.getItem().isEmpty(), "the drop was not picked up into the free slot");
            int after = book.bestiaryMob(p.getUUID(), mob).harvested();
            f.check(after == start + 1, "a real pickup counted " + (after - start) + " harvests (want 1)");
            p.getInventory().clearContent();
            if (!drop.isRemoved()) drop.discard();
            bot.leave();
            f.finish(h);
        });
    }

    /**
     * Damage numbers are packet-only: a hit next to a player sends them a text display that never joins the level,
     * the module counts it while it shows, and its viewer gets the removal when it expires.
     */
    @GameTest(maxTicks = 120, padding = 12)
    public void damageNumbersArePacketOnly(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btDmgNum", false);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("damage_numbers");
        HologramsModule holo = Feather.get().module(HologramsModule.class);
        if (holo == null) {
            f.add("the holograms module is off");
            bot.leave();
            f.finish(h);
            return;
        }
        Object numbers = FurnitureBehaviorTests.field(holo, "damage");
        Mob pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        pig.setNoAi(true);
        Vec3 at = p.position().add(2, 0, 0);
        pig.snapTo(at.x, at.y, at.z, 0, 0);
        level.addFreshEntity(pig);
        sent(bot);
        int before = (int) FurnitureBehaviorTests.call(numbers, "activeCount");
        pig.hurtServer(level, level.damageSources().generic(), 3f);
        int during = (int) FurnitureBehaviorTests.call(numbers, "activeCount");
        f.check(during > before, "no damage number for a hit next to a player (active " + before + " -> " + during + ")");
        int id = -1;
        for (Object o : sent(bot)) {
            if (o instanceof ClientboundAddEntityPacket a && a.getType() == EntityTypes.TEXT_DISPLAY) id = a.getId();
        }
        f.check(id != -1, "the player was not sent a text display");
        final int shown = id;
        f.check(shown == -1 || level.getEntity(shown) == null, "the damage number joined the level");
        f.check(level.getEntitiesOfClass(net.minecraft.world.entity.Display.TextDisplay.class, pig.getBoundingBox().inflate(4)).isEmpty(),
                "a text display entity stands at the hit");
        h.runAfterDelay(30, () -> {
            boolean removed = false;
            for (Object o : sent(bot)) {
                if (o instanceof ClientboundRemoveEntitiesPacket r && r.entityIds().contains(shown)) removed = true;
            }
            f.check(shown == -1 || removed, "the expired damage number was not removed from its viewer");
            pig.discard();
            bot.leave();
            f.finish(h);
        });
    }
}
