package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.gametest.core.LogWatch;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every furniture piece gets clicked (plain and sneaking, on every part) with a set of everyday items by a survival
 * player, then broken. Module hooks catch their own exceptions and only log them, so the log is watched: any error
 * or stack trace fails the test. Breaking afterwards must remove the piece and never drop more than one of it.
 * Own batch: errors from other suites must not count here.
 */
public class InteractionFuzzTests {
    static final List<Supplier<ItemStack>> HELD = List.of(
            () -> ItemStack.EMPTY,
            () -> new ItemStack(Items.DIAMOND, 3),
            () -> new ItemStack(Items.WATER_BUCKET),
            () -> new ItemStack(Items.BUCKET),
            () -> new ItemStack(Items.FLINT_AND_STEEL),
            () -> new ItemStack(Items.BONE_MEAL, 4),
            () -> new ItemStack(Items.COOKIE, 4),
            () -> new ItemStack(Items.MUSIC_DISC_CAT),
            () -> new ItemStack(Items.OAK_SAPLING, 2),
            () -> new ItemStack(Items.COD),
            () -> new ItemStack(Items.IRON_HELMET),
            () -> new ItemStack(Items.NAME_TAG),
            () -> new ItemStack(Items.IRON_AXE),
            () -> new ItemStack(Items.HONEYCOMB));

    @GameTest(maxTicks = 20000, padding = 16, environment = "aftrhrs_gametest:content_fuzz")
    public void everyPieceSurvivesEverydayClicks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btFuzz", false);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("interaction_fuzz");
        LogWatch log = new LogWatch();
        List<String> ids = new ArrayList<>();
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.kind().equals("furniture") && c.block() instanceof FurnitureBlock) ids.add(c.id());
        }
        FurnitureSweepTests.stepped(h, ids, 6, id -> {
            try {
                FurnitureBehaviorTests.reset(p, center);
                BlockState s = FurnitureBehaviorTests.put(level, center, id);
                if (s == null) {
                    f.add(id + ": could not place");
                    return;
                }
                FurnitureBlock fb = (FurnitureBlock) s.getBlock();
                List<BlockPos> parts = fb.partPositions(s, center);
                int k = 0;
                for (Supplier<ItemStack> held : HELD) {
                    for (boolean sneak : new boolean[]{false, true}) {
                        BlockPos at = parts.get(k++ % parts.size());
                        if (!(level.getBlockState(at).getBlock() instanceof FurnitureBlock)) continue;
                        p.getInventory().clearContent();
                        p.setItemInHand(InteractionHand.MAIN_HAND, held.get());
                        p.setShiftKeyDown(sneak);
                        CT.click(p, at, Direction.UP);
                        p.setShiftKeyDown(false);
                        p.closeContainer();
                        if (p.isPassenger()) p.stopRiding();
                    }
                }
                p.getInventory().clearContent();
                CT.collectDrops(level, center, 6);
                BlockState now = level.getBlockState(center);
                String self = "aftrhrs:" + id;
                if (now.getBlock() instanceof FurnitureBlock nfb) {
                    CT.breakBlock(p, center);
                    for (BlockPos q : nfb.partPositions(now, center)) {
                        f.check(!(level.getBlockState(q).getBlock() instanceof FurnitureBlock), id + ": a part survived the break at " + q.subtract(center).toShortString());
                    }
                    Map<String, Integer> d = CT.collectDrops(level, center, 6);
                    int pieces = 0;
                    for (var e : d.entrySet()) if (BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(e.getKey())) instanceof net.minecraft.world.item.BlockItem bi
                            && bi.getBlock() instanceof FurnitureBlock) pieces += e.getValue();
                    f.check(pieces <= 1, id + ": breaking dropped " + pieces + " furniture items: " + d);
                }
                for (String e : log.errors()) f.add(id + ": logged " + e);
                log.clear();
            } catch (RuntimeException ex) {
                f.add(id + ": exception " + ex);
                net.axther.aftrhrs.AftrHrs.LOG.error("[bt-content] fuzz {} crashed", id, ex);
            }
            bot.drain();
        }, () -> {
            log.close();
            CT.clear(level, center, 5);
            bot.leave();
            f.check(ids.size() > 1000, "only " + ids.size() + " pieces");
            f.finish(h);
        });
    }
}
