package net.axther.aftrhrs.gametest.core;

import net.axther.aftrhrs.core.Feather;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;

import static net.axther.aftrhrs.gametest.core.CoreTests.check;
import static net.axther.aftrhrs.gametest.core.SocialTests.horizontal;

/** Back, death points, warps, sit/lay/crawl, carry, hat, AFK, trails, rtp. */
public class TravelTests {
    /** Every stack of an item the player holds: 36 slots, armor and offhand. */
    static int count(Bots.Bot b, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (b.player.getInventory().getItem(i).is(item)) n += b.player.getInventory().getItem(i).getCount();
        for (EquipmentSlot s : EquipmentSlot.values()) {
            if (s == EquipmentSlot.MAINHAND) continue;
            ItemStack st = b.player.getItemBySlot(s);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    @GameTest(maxTicks = 300)
    public void backAfterTeleportAndDeath(GameTestHelper h) {
        LogWatch log = new LogWatch();
        Bots.Bot a = Bots.join(h, Bots.name("bk"));
        a.op(true); // instant teleports
        Vec3 first = a.player.position();
        a.cmd("sethome bk");
        a.player.connection.teleport(first.x + 12, first.y, first.z, 0, 0);
        Vec3 away = a.player.position();
        a.cmd("home bk");
        check(h, horizontal(a.player.position(), first) < 1.5, "an op's /home was not instant: " + a.chat());
        a.cmd("back");
        check(h, horizontal(a.player.position(), away) < 1.5, "/back did not return to where /home left from: " + a.player.position());
        // Death: the death point is kept, /back goes there after the respawn.
        a.player.connection.teleport(first.x + 6, first.y, first.z, 0, 0);
        Vec3 died = a.player.position();
        a.dieAndRespawn();
        check(h, a.player.isAlive() && !a.player.isRemoved(), "the bot did not respawn");
        var deaths = Feather.get().profiles().get(a.player).deaths;
        check(h, !deaths.isEmpty(), "no death point was saved");
        a.cmd("deathpoint");
        a.cmd("back");
        h.runAfterDelay(5, () -> {
            check(h, horizontal(a.player.position(), died) < 2, "/back after death went to " + a.player.position() + " not " + died);
            a.op(false);
            a.leave();
            log.close();
            CoreTests.noErrors(h, log, "back/death");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void warpsSetUseDelete(GameTestHelper h) {
        Bots.Bot staff = Bots.join(h, Bots.name("wpS"));
        Bots.Bot p = SocialTests.far(h, Bots.name("wpP"), 20);
        String warp = "bt" + Bots.name("w").substring(1).toLowerCase();
        staff.op(true);
        staff.cmd("setwarp " + warp);
        p.cmd("warp " + warp);
        h.runAfterDelay(80, () -> {
            check(h, horizontal(p.player.position(), staff.player.position()) < 1.5, "/warp did not arrive: " + p.chat());
            p.cmd("setwarp evil");
            p.cmd("delwarp " + warp);
            staff.cmd("delwarp " + warp);
            p.cmd("warp " + warp);
            staff.op(false);
            staff.leave();
            p.leave();
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void sitLayCrawlCleanUpOnQuit(GameTestHelper h) {
        LogWatch log = new LogWatch();
        h.setBlock(1, 1, 1, Blocks.STONE);
        Bots.Bot a = Bots.join(h.getLevel(), Bots.name("sit"), h.absoluteVec(new Vec3(1.5, 2, 1.5)));
        a.player.setOnGround(true);
        a.cmd("sit");
        var seat = a.player.getVehicle();
        check(h, seat != null, "/sit did not seat the player: " + a.chat());
        a.cmd("lay");
        a.player.setOnGround(true);
        a.cmd("swim");
        a.cmd("swim");
        a.player.setOnGround(true);
        a.cmd("sit");
        var seat2 = a.player.getVehicle();
        a.leave();
        h.runAfterDelay(5, () -> {
            check(h, seat2 == null || seat2.isRemoved(), "a seat was left behind after quitting: " + seat2);
            check(h, seat.isRemoved(), "the first seat was left behind");
            log.close();
            CoreTests.noErrors(h, log, "sit/lay/crawl");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void carriedPlayerQuittingKeepsTheCarrier(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("caA"));
        Bots.Bot b = Bots.join(h, Bots.name("caB"));
        a.player.setShiftKeyDown(true);
        a.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); // the starter kit fills the hotbar
        var result = UseEntityCallback.EVENT.invoker().interact(a.player, h.getLevel(), InteractionHand.MAIN_HAND, b.player, null);
        check(h, b.player.getVehicle() == a.player, "sneak + click did not pick the player up (" + result + ", sneaking "
                + a.player.isShiftKeyDown() + "): " + a.chat());
        b.leave();
        h.runAfterDelay(2, () -> {
            check(h, !a.player.isRemoved(), "the carrier was removed from the world when the carried player quit");
            check(h, h.getLevel().getEntity(a.id()) == a.player, "the carrier is no longer in the level");
            check(h, a.player.getPassengers().isEmpty(), "the carrier still carries someone");
            a.leave();
            h.succeed();
        });
    }

    @GameTest
    public void hatSwapsWithoutDupes(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("hat"));
        a.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CARVED_PUMPKIN, 3));
        a.player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        a.cmd("hat");
        var head = a.player.getItemBySlot(EquipmentSlot.HEAD);
        int total = count(a, Items.CARVED_PUMPKIN);
        check(h, head.is(Items.CARVED_PUMPKIN) || a.chat().stream().anyMatch(s -> !s.isEmpty()), "/hat did nothing and said nothing");
        if (head.is(Items.CARVED_PUMPKIN)) {
            check(h, head.getCount() == 1, "the hat is a stack of " + head.getCount());
            check(h, total == 3, "pumpkins went from 3 to " + total);
            check(h, count(a, Items.IRON_HELMET) == 1, "the old helmet was lost or duplicated: " + count(a, Items.IRON_HELMET));
        }
        a.leave();
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void afkAndTrails(GameTestHelper h) {
        LogWatch log = new LogWatch();
        Bots.Bot a = Bots.join(h, Bots.name("afk"));
        Bots.Bot b = Bots.join(h, Bots.name("afkB"));
        a.cmd("afk");
        check(h, Feather.get().isAfk(a.player), "/afk did not mark AFK");
        // Activity in the first 2.5 s after /afk doesn't count (the command itself).
        long afkAt = System.currentTimeMillis();
        h.runAfterDelay(60, () -> {
            long wait = 2600 - (System.currentTimeMillis() - afkAt); // the grace is wall time; ticks can run fast
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ignored) {
                }
            }
            SocialTests.chat(a, "back now");
        });
        h.runAfterDelay(65, () -> {
            check(h, !Feather.get().isAfk(a.player), "chatting did not end AFK");
            a.cmd("afk brb <red>coffee");
            a.cmd("trail");
            a.cmd("trail none");
            a.cmd("trail zz_unknown");
            a.cmd("trail list");
            a.leave();
            b.leave();
            log.close();
            CoreTests.noErrors(h, log, "afk/trails");
            h.succeed();
        });
    }

    /** /rtp loads chunks off-thread and finishes on the server thread (a crash was fixed here). */
    @GameTest(maxTicks = 600, environment = "aftrhrs_gametest:core_isolated")
    public void rtpFindsAPlaceOrFails(GameTestHelper h) {
        LogWatch log = new LogWatch();
        Bots.Bot a = Bots.join(h, Bots.name("rtp"));
        Vec3 start = a.player.position();
        a.op(true);
        a.cmd("rtp");
        a.cmd("rtp");
        Bots.Bot b = Bots.join(h, Bots.name("rtpB"));
        b.op(true);
        b.cmd("rtp");
        b.leave(); // leaving mid-search
        h.runAfterDelay(400, () -> {
            boolean moved = a.player.position().distanceTo(start) > 50;
            boolean failed = a.chat().stream().anyMatch(s -> s.toLowerCase().contains("couldn"));
            a.op(false);
            a.leave();
            log.close();
            CoreTests.noErrors(h, log, "/rtp");
            net.axther.aftrhrs.AftrHrs.LOG.info("rtp test: moved {} failed-message {}", moved, failed);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ /tpa when someone leaves

    /** Links a bot as a mod client with the tpa feature (a real hello), so the server sends it request toasts. */
    static void linkCompanion(Bots.Bot b) {
        b.openChannels(net.axther.aftrhrs.net.ApiPayload.TYPE.id());
        com.google.gson.JsonObject hello = new com.google.gson.JsonObject();
        hello.addProperty("t", "hello");
        hello.addProperty("proto", net.axther.aftrhrs.net.Companion.PROTO);
        hello.addProperty("mod", "test");
        com.google.gson.JsonArray f = new com.google.gson.JsonArray();
        f.add("tpa");
        hello.add("features", f);
        try {
            var m = net.axther.aftrhrs.net.Companion.class.getDeclaredMethod("hello", net.minecraft.server.level.ServerPlayer.class, com.google.gson.JsonObject.class);
            m.setAccessible(true);
            m.invoke(null, b.player, hello);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Companion messages of this type the bot was sent (small ones are a flag byte and the JSON). */
    static long companionMessages(Bots.Bot b, String type) {
        b.pump();
        return b.payloads.stream().filter(p -> p instanceof net.axther.aftrhrs.net.ApiPayload)
                .map(p -> new String(((net.axther.aftrhrs.net.ApiPayload) p).bytes(), StandardCharsets.UTF_8))
                .filter(s -> s.contains("\"t\":\"" + type + "\"")).count();
    }

    @GameTest(maxTicks = 60)
    public void tpaSenderLeavingTellsTheTarget(GameTestHelper h) {
        Bots.Bot from = Bots.join(h, Bots.name("tsl"));
        Bots.Bot to = Bots.join(h, Bots.name("tslT"));
        boolean linked = net.axther.aftrhrs.net.Companion.running();
        if (linked) linkCompanion(to);
        from.cmd("tpa " + to.name());
        check(h, to.heard("teleport"), "the target got no request: " + to.chat() + " / sender: " + from.chat());
        boolean modded = net.axther.aftrhrs.net.Companion.has(to.player, "tpa");
        check(h, !linked || modded, "the hello didn't link the target");
        check(h, !modded || companionMessages(to, "tpa_request") > 0, "the linked target got no request toast");
        to.clear();
        to.payloads.clear();
        from.leave();
        check(h, to.heard("their teleport request is gone"), "the target wasn't told the sender left: " + to.chat());
        check(h, !modded || companionMessages(to, "tpa_clear") == 1, "the request toast wasn't cleared: " + companionMessages(to, "tpa_clear"));
        to.cmd("tpaccept");
        check(h, !to.heard("accepted"), "a request from a player who left could still be accepted: " + to.chat());
        to.leave();
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void tpaTargetLeavingTellsTheSender(GameTestHelper h) {
        Bots.Bot from = Bots.join(h, Bots.name("ttl"));
        Bots.Bot to = Bots.join(h, Bots.name("ttlT"));
        from.cmd("tpa " + to.name());
        check(h, to.heard("teleport"), "the target got no request: " + to.chat() + " / sender: " + from.chat());
        from.clear();
        to.leave();
        check(h, from.heard("left, so your teleport request was dropped"), "the sender wasn't told the target left: " + from.chat());
        from.leave();
        h.succeed();
    }

    /** A cancelled /tpa warm-up: one chat line (why, and that the request is used up), not two. */
    @GameTest(maxTicks = 100)
    public void tpaCancelledWarmupSendsOneLine(GameTestHelper h) {
        Bots.Bot from = Bots.join(h, Bots.name("twc"));
        Bots.Bot to = Bots.join(h, Bots.name("twcT"));
        check(h, Feather.get().teleports().warmupFor(from.player) > 0, "no warm-up configured for players");
        from.cmd("tpa " + to.name());
        to.cmd("tpaccept");
        check(h, Feather.get().teleports().isWarmingUp(from.player), "the accepted teleport didn't start a warm-up: " + from.chat() + " / " + to.chat());
        from.clear();
        to.clear();
        Vec3 at = from.player.position();
        from.player.connection.teleport(at.x + 4, at.y, at.z, 0, 0);
        h.runAfterDelay(4, () -> {
            check(h, !Feather.get().teleports().isWarmingUp(from.player), "moving didn't cancel the warm-up");
            // Other tests' broadcasts share the chat: count the lines about this cancel.
            var lines = from.chat().stream().filter(l -> !l.startsWith("[bar] ") && (l.contains("anceled") || l.contains("used up"))).toList();
            check(h, lines.size() == 1, "the traveler got " + lines.size() + " lines about the cancel: " + lines);
            check(h, lines.get(0).contains("canceled") && lines.get(0).contains("used up"), "the line doesn't say why and that the request is used up: " + lines);
            check(h, to.heard("moved or got hurt"), "the host wasn't told: " + to.chat());
            from.leave();
            to.leave();
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ crawl barrier across teleports

    @SuppressWarnings("unchecked")
    private static java.util.Map<java.util.UUID, ?> crawlers() {
        try {
            var m = Feather.get().module(net.axther.aftrhrs.feature.travel.SitModule.class);
            var f = net.axther.aftrhrs.feature.travel.SitModule.class.getDeclaredField("crawlers");
            f.setAccessible(true);
            return (java.util.Map<java.util.UUID, ?>) f.get(m);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Block updates the server sent this bot since the last read (reads the channel itself, then lets pump see the rest). */
    private static java.util.List<net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket> blockUpdates(Bots.Bot b) {
        java.util.List<net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket> out = new java.util.ArrayList<>();
        if (b.channel.isOpen()) {
            b.channel.runPendingTasks();
            b.channel.flushOutbound();
        }
        Object o;
        while ((o = b.channel.readOutbound()) != null) {
            if (o instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
                for (var sub : bundle.subPackets()) if (sub instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket u) out.add(u);
            } else if (o instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket u) out.add(u);
        }
        return out;
    }

    /** A crawler going to the Nether: the fake barrier is dropped, never "restored" from a chunk loaded in the new world. */
    @GameTest(maxTicks = 100)
    public void crawlDimensionChangeLoadsNoChunk(GameTestHelper h) {
        LogWatch log = new LogWatch();
        h.setBlock(1, 1, 1, Blocks.STONE);
        Bots.Bot a = Bots.join(h.getLevel(), Bots.name("crwN"), h.absoluteVec(new Vec3(1.5, 2, 1.5)));
        var p = a.player;
        a.cmd("swim");
        check(h, p.getPose() == net.minecraft.world.entity.Pose.SWIMMING, "/swim did not crawl: " + a.chat());
        Object spot = crawlers().get(p.getUUID());
        check(h, spot != null, "no fake barrier above an open head: " + crawlers());
        net.minecraft.core.BlockPos barrier = p.blockPosition().above();
        var nether = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        int bcx = barrier.getX() >> 4, bcz = barrier.getZ() >> 4;
        boolean wasLoaded = nether.getChunkSource().getChunkNow(bcx, bcz) != null; // another test may stand there
        Vec3 dest = new Vec3(barrier.getX() - 9000.5, 200, barrier.getZ() - 9000.5);
        try {
            net.axther.aftrhrs.core.event.TeleportCause.PLUGIN.run(() -> p.teleport(new net.minecraft.world.level.portal.TeleportTransition(
                    nether, dest, Vec3.ZERO, 0, 0, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING)));
        } catch (RuntimeException e) {
            throw new AssertionError("the Nether teleport of a crawler threw", e);
        }
        check(h, a.player.level() == nether, "the crawler did not reach the Nether");
        check(h, wasLoaded || nether.getChunkSource().getChunkNow(bcx, bcz) == null,
                "stopping the crawl loaded the Nether chunk at the old barrier's coordinates " + bcx + "," + bcz);
        check(h, !crawlers().containsKey(a.id()), "the player still crawls after the dimension change");
        check(h, a.player.getPose() != net.minecraft.world.entity.Pose.SWIMMING, "still in the swimming pose: " + a.player.getPose());
        a.leave();
        log.close();
        CoreTests.noErrors(h, log, "crawl dimension change");
        h.succeed();
    }

    /** A crawler teleported within the same world gets the real block back where the barrier was, from a loaded chunk. */
    @GameTest(maxTicks = 100)
    public void crawlSameWorldTeleportRestoresTheBlock(GameTestHelper h) {
        h.setBlock(1, 1, 1, Blocks.STONE);
        Bots.Bot a = Bots.join(h.getLevel(), Bots.name("crwS"), h.absoluteVec(new Vec3(1.5, 2, 1.5)));
        var p = a.player;
        a.cmd("swim");
        check(h, crawlers().get(p.getUUID()) != null, "no fake barrier above an open head: " + crawlers());
        net.minecraft.core.BlockPos barrier = p.blockPosition().above();
        blockUpdates(a);
        Vec3 to = h.absoluteVec(new Vec3(3.5, 2, 3.5));
        net.axther.aftrhrs.core.event.TeleportCause.PLUGIN.run(() -> p.teleport(new net.minecraft.world.level.portal.TeleportTransition(
                h.getLevel(), to, Vec3.ZERO, 0, 0, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING)));
        var sent = blockUpdates(a);
        check(h, sent.stream().anyMatch(u -> u.getPos().equals(barrier) && !u.getBlockState().is(Blocks.BARRIER)),
                "the real block was not sent back at " + barrier + ": " + sent.stream().map(u -> u.getPos() + "=" + u.getBlockState()).toList());
        check(h, !crawlers().containsKey(a.id()), "the player still crawls after the teleport");
        check(h, a.player.getPose() != net.minecraft.world.entity.Pose.SWIMMING, "still in the swimming pose");
        a.leave();
        h.succeed();
    }

    // ------------------------------------------------------------------ checked teleports into unloaded chunks

    /** A spot on the generated surface at x,z, worked out by the generator without loading the chunk. */
    private static net.axther.aftrhrs.core.Loc surface(net.minecraft.server.level.ServerLevel l, int x, int z) {
        var cs = l.getChunkSource();
        int y = cs.getGenerator().getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, l, cs.randomState());
        return new net.axther.aftrhrs.core.Loc(l, x + 0.5, y, z + 0.5, 0, 0);
    }

    /**
     * A checked teleport (homes, warps, /back) to an unloaded chunk loads it off the server thread's back and arrives
     * later; it never loads the chunk inside the call. A second teleport before the first arrives replaces it.
     */
    @GameTest(maxTicks = 600)
    public void checkedTeleportWaitsForTheChunkAsync(GameTestHelper h) {
        LogWatch log = new LogWatch();
        Bots.Bot a = Bots.join(h, Bots.name("tpU"));
        a.op(true); // no warm-up
        var tp = Feather.get().teleports();
        check(h, tp.warmupFor(a.player) == 0, "an op still has a warm-up");
        var l = h.getLevel();
        net.minecraft.core.BlockPos o = h.absolutePos(net.minecraft.core.BlockPos.ZERO);
        var first = surface(l, o.getX() + 30_016, o.getZ() + 1_008);
        var second = surface(l, o.getX() - 30_016, o.getZ() - 1_008);
        check(h, l.getChunkSource().getChunkNow(first.blockX() >> 4, first.blockZ() >> 4) == null
                && l.getChunkSource().getChunkNow(second.blockX() >> 4, second.blockZ() >> 4) == null, "a destination chunk is already loaded");
        Vec3 start = a.player.position();
        boolean[] arrived = {false, false};
        tp.teleport(a.player, () -> first, net.kyori.adventure.text.Component.text("first"), () -> arrived[0] = true, true, null);
        check(h, l.getChunkSource().getChunkNow(first.blockX() >> 4, first.blockZ() >> 4) == null, "the teleport loaded its chunk on the server thread");
        check(h, a.player.position().distanceTo(start) < 0.01 && !arrived[0], "the player moved before the chunk loaded");
        check(h, tp.isLoadingDestination(a.player), "no pending arrival");
        tp.teleport(a.player, () -> second, net.kyori.adventure.text.Component.text("second"), () -> arrived[1] = true, true, null);
        check(h, l.getChunkSource().getChunkNow(second.blockX() >> 4, second.blockZ() >> 4) == null, "the second teleport loaded its chunk on the server thread");
        h.succeedWhen(() -> {
            check(h, !arrived[0], "the replaced teleport still arrived");
            boolean unsafe = a.heard("No safe spot");
            check(h, arrived[1] || unsafe, "not there yet");
            check(h, !tp.isLoadingDestination(a.player), "still pending after arriving");
            if (arrived[1]) {
                check(h, horizontal(a.player.position(), new Vec3(second.x(), 0, second.z())) < 3,
                        "arrived at " + a.player.position() + ", not near " + second);
            }
            net.axther.aftrhrs.AftrHrs.LOG.info("async checked teleport: {}", arrived[1] ? "arrived" : "no safe spot (" + a.chat() + ")");
            a.op(false);
            a.leave();
            log.close();
            CoreTests.noErrors(h, log, "async checked teleport");
        });
    }

    /**
     * Moved far away by something else (a portal, /tp, a jail) while a checked teleport's chunk loads: the stale
     * arrival is dropped and never pulls the player back once the chunk is there.
     */
    @GameTest(maxTicks = 600)
    public void checkedTeleportDroppedByAnotherMove(GameTestHelper h) {
        LogWatch log = new LogWatch();
        Bots.Bot a = Bots.join(h, Bots.name("tpM"));
        a.op(true); // no warm-up
        var tp = Feather.get().teleports();
        var l = h.getLevel();
        net.minecraft.core.BlockPos o = h.absolutePos(net.minecraft.core.BlockPos.ZERO);
        var dest = surface(l, o.getX() + 30_016, o.getZ() - 9_008);
        int cx = dest.blockX() >> 4, cz = dest.blockZ() >> 4;
        check(h, l.getChunkSource().getChunkNow(cx, cz) == null, "the destination chunk is already loaded");
        boolean[] arrived = {false};
        tp.teleport(a.player, () -> dest, net.kyori.adventure.text.Component.text("dest"), () -> arrived[0] = true, true, null);
        check(h, tp.isLoadingDestination(a.player), "no pending arrival");
        var away = new net.axther.aftrhrs.core.Loc(l, a.player.getX() + 20, a.player.getY(), a.player.getZ(), 0, 0);
        check(h, Feather.get().teleportNow(a.player, away, net.axther.aftrhrs.core.event.TeleportCause.PLUGIN), "the other move failed");
        check(h, !tp.isLoadingDestination(a.player), "a far move by another path kept the pending arrival");
        long[] loadedAt = {-1};
        h.succeedWhen(() -> {
            if (loadedAt[0] < 0 && l.getChunkSource().getChunkNow(cx, cz) != null) loadedAt[0] = l.getGameTime();
            check(h, loadedAt[0] >= 0 && l.getGameTime() >= loadedAt[0] + 5, "the destination chunk is not loaded yet");
            check(h, !arrived[0], "the dropped teleport still arrived");
            check(h, horizontal(a.player.position(), new Vec3(away.x(), 0, away.z())) < 1, "pulled away from the other move's spot to "
                    + a.player.position());
            a.op(false);
            a.leave();
            log.close();
            CoreTests.noErrors(h, log, "dropped checked teleport");
        });
    }
}
