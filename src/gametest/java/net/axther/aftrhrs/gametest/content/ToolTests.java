package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Feather;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.UUID;

/**
 * The tools module (hammers, lumber axes, scythes, builder's wand) for real: a survival player mining / using them,
 * including the per-block claim checks (another player's claimed chunk next to the work area). Isolated batch:
 * chunk claims must not touch the other suites' test areas.
 */
public class ToolTests {
    static final String ENV = "aftrhrs_gametest:content_isolated";

    /** Claims chunk (cx, cz) for the player through the claims module; returns the claim (to abandon) or null. */
    static Object claimChunk(ServerPlayer p, ServerLevel level, int cx, int cz) {
        try {
            Class<?> mc = Class.forName("net.axther.aftrhrs.feature.claims.ClaimsModule");
            Class<?> area = Class.forName("net.axther.aftrhrs.feature.claims.Area");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object m = Feather.get().module((Class) mc);
            if (m == null) return null;
            var of = area.getDeclaredMethod("ofChunk", int.class, int.class);
            of.setAccessible(true);
            var claim = mc.getDeclaredMethod("claim", ServerPlayer.class, ServerLevel.class, area);
            claim.setAccessible(true);
            return claim.invoke(m, p, level, of.invoke(null, cx, cz));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void abandon(ServerPlayer p, Object claim) {
        if (claim == null) return;
        try {
            Class<?> mc = Class.forName("net.axther.aftrhrs.feature.claims.ClaimsModule");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object m = Feather.get().module((Class) mc);
            var ab = mc.getDeclaredMethod("abandon", ServerPlayer.class, Class.forName("net.axther.aftrhrs.feature.claims.Claim"));
            ab.setAccessible(true);
            ab.invoke(m, p, claim);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Looks at the top face center of {@code target} from {@code dz} blocks south, standing on the floor level. */
    static void lookAtTop(ServerPlayer p, BlockPos target, double dz) {
        double ex = target.getX() + 0.5, ez = target.getZ() + 0.5 + dz;
        double feetY = target.getY() + 1;
        double eyeY = feetY + p.getEyeHeight();
        float pitch = (float) Math.toDegrees(Math.atan2(eyeY - (target.getY() + 1), dz));
        CT.pose(p, new Vec3(ex, feetY, ez), 180, pitch);
    }

    /**
     * Hammer on a floor block from a shallow angle: the 3x3 is the floor plane (it used to dig a vertical slice: the
     * hit face came from a ray through the already-broken block). The 3 cells in someone else's claim stay.
     */
    @GameTest(maxTicks = 200, padding = 24, environment = ENV)
    public void hammerFloorPlaneAndClaims(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot miner = CT.join(h, "btHammer", false);
        CT.Bot owner = CT.join(h, "btOwner", true);
        ServerPlayer p = miner.p;
        // the center sits on the east edge of a chunk: its east column is in the next chunk (claimed by the owner)
        BlockPos o = h.absolutePos(new BlockPos(4, 3, 4));
        int x = o.getX() | 15;
        // z mid-chunk: the 3x3 must not reach into the chunk north or south of the claimed one (the test failed
        // whenever the framework placed it on a chunk's z edge)
        BlockPos center = new BlockPos(x, o.getY(), (o.getZ() & ~15) | 8);
        CT.clear(level, center, 4);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(center.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(center.offset(dx, -1, dz), Blocks.DEEPSLATE.defaultBlockState(), 3);
            }
        }
        Object claim = claimChunk(owner.p, level, (x + 1) >> 4, center.getZ() >> 4);
        CT.Fails f = new CT.Fails("hammer");
        f.check(claim != null, "owner could not claim the chunk east of the work area");
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("arcana_iron_hammer", 1));
        lookAtTop(p, center, 3);
        boolean broke = CT.breakBlock(p, center);
        f.check(broke, "hammer could not break the center");
        h.runAfterDelay(4, () -> { // the 8 extra blocks break one tick later
            int floor = 0, below = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean gone = level.getBlockState(center.offset(dx, 0, dz)).isAir();
                    if (dx == 1) f.check(!gone, "broke " + dx + "," + dz + " inside someone else's claim");
                    else {
                        f.check(gone, "floor cell " + dx + "," + dz + " not broken");
                        if (gone) floor++;
                    }
                    if (level.getBlockState(center.offset(dx, -1, dz)).isAir()) below++;
                }
            }
            f.check(below == 0, below + " blocks under the floor broke (vertical slice)");
            f.check(level.getBlockState(center.offset(0, 1, 0)).isAir(), "air above");
            var drops = CT.collectDrops(level, center, 4);
            f.check(drops.getOrDefault("minecraft:cobblestone", 0) == floor, "cobblestone dropped " + drops + " for " + floor + " blocks");
            f.check(p.getMainHandItem().getDamageValue() == floor, "hammer took " + p.getMainHandItem().getDamageValue() + " damage for " + floor + " blocks");
            abandon(owner.p, claim);
            CT.clear(level, center, 4);
            miner.leave();
            owner.leave();
            f.finish(h);
        });
    }

    /** Lumber axe fells a natural tree (log by log over a few ticks); a log stack without leaves is not a tree. */
    @GameTest(maxTicks = 200, padding = 16, environment = ENV)
    public void lumberAxeFellsTrees(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btLumber", false);
        ServerPlayer p = bot.p;
        BlockPos base = h.absolutePos(new BlockPos(3, 2, 3));
        CT.clear(level, base.above(3), 5);
        level.setBlock(base.below(), Blocks.DIRT.defaultBlockState(), 3);
        for (int y = 0; y < 5; y++) level.setBlock(base.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = 3; y <= 5; y++) {
                    BlockPos q = base.offset(dx, y, dz);
                    if (level.getBlockState(q).isAir()) {
                        level.setBlock(q, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1), 2 | 16);
                    }
                }
            }
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("arcana_iron_lumber_axe", 1));
        CT.pose(p, Vec3.atBottomCenterOf(base.south(2)), 180, 0);
        CT.breakBlock(p, base);
        h.runAfterDelay(20, () -> {
            CT.Fails f = new CT.Fails("lumber_axe");
            int left = 0;
            for (int y = 0; y < 5; y++) if (level.getBlockState(base.above(y)).is(Blocks.OAK_LOG)) left++;
            f.check(left == 0, left + " logs left standing");
            var d = CT.collectDrops(level, base, 8);
            f.check(d.getOrDefault("minecraft:oak_log", 0) == 5, "logs dropped: " + d);
            CT.clear(level, base.above(3), 5);
            bot.leave();
            f.finish(h);
        });
    }

    /** Scythe on a ripe crop harvests the ripe 3x3 and replants each from its own drops. */
    @GameTest(maxTicks = 200, padding = 16, environment = ENV)
    public void scytheHarvestsAndReplants(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btScythe", false);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 2, 4));
        CT.clear(level, center, 3);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(center.offset(dx, -1, dz), Blocks.FARMLAND.defaultBlockState(), 3);
                level.setBlock(center.offset(dx, 0, dz), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, dx == 1 && dz == 1 ? 3 : 7), 3);
            }
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("arcana_iron_scythe", 1));
        CT.pose(p, Vec3.atBottomCenterOf(center.south(2)), 180, 30);
        CT.breakBlock(p, center);
        h.runAfterDelay(5, () -> {
            CT.Fails f = new CT.Fails("scythe");
            int replanted = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    var s = level.getBlockState(center.offset(dx, 0, dz));
                    if (dx == 1 && dz == 1) f.check(s.is(Blocks.WHEAT) && s.getValue(CropBlock.AGE) == 3, "unripe crop touched: " + s);
                    else if (s.is(Blocks.WHEAT) && s.getValue(CropBlock.AGE) == 0) replanted++;
                }
            }
            f.check(replanted == 8, replanted + " of 8 ripe crops replanted");
            var d = CT.collectDrops(level, center, 4);
            f.check(d.getOrDefault("minecraft:wheat", 0) == 8, "wheat dropped: " + d);
            CT.clear(level, center, 3);
            bot.leave();
            f.finish(h);
        });
    }

    /** Builder's wand extends a same-block face by a layer, paying with blocks from the inventory; not into claims. */
    @GameTest(maxTicks = 200, padding = 24, environment = ENV)
    public void wandBuildsLayerAndRespectsClaims(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btWand", false);
        CT.Bot owner = CT.join(h, "btWandOwner", true);
        ServerPlayer p = bot.p;
        BlockPos o = h.absolutePos(new BlockPos(4, 3, 4));
        BlockPos center = new BlockPos(o.getX() | 15, o.getY(), (o.getZ() & ~15) | 8);   // z mid-chunk (see the hammer test)
        CT.clear(level, center, 4);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlock(center.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
        Object claim = claimChunk(owner.p, level, (center.getX() + 1) >> 4, center.getZ() >> 4);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("arcana_builders_wand", 1));
        p.getInventory().add(new ItemStack(Items.STONE, 64));
        CT.pose(p, Vec3.atBottomCenterOf(center.south(3)), 180, 30);
        CT.click(p, center, Direction.UP);
        CT.Fails f = new CT.Fails("wand");
        f.check(claim != null, "owner could not claim");
        int placed = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean stone = level.getBlockState(center.offset(dx, 1, dz)).is(Blocks.STONE);
                if (dx == 1) f.check(!stone, "wand built at " + dx + "," + dz + " inside someone else's claim");
                else if (stone) placed++;
            }
        }
        f.check(placed == 6, placed + " of 6 cells built");
        f.check(p.getInventory().countItem(Items.STONE) == 64 - placed, "stone left " + p.getInventory().countItem(Items.STONE) + " after building " + placed);
        abandon(owner.p, claim);
        CT.clear(level, center, 4);
        bot.leave();
        owner.leave();
        f.finish(h);
    }

    static UUID id(ServerPlayer p) {
        return p.getUUID();
    }

    static ArrayList<String> list() {
        return new ArrayList<>();
    }

    static Component c(String s) {
        return Component.literal(s);
    }
}
