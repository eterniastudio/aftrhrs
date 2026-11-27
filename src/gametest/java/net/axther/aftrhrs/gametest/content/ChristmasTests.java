package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.feature.christmas.ChristmasModule;
import net.axther.aftrhrs.feature.christmas.PresentBlockEntity;
import net.axther.aftrhrs.feature.christmas.TreeBlockEntity;
import net.axther.aftrhrs.feature.christmas.TreeSlots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Christmas set (feature/christmas): decorating the fir tree, wrapping / opening / carrying presents, the advent
 * wreath's candles and the "until Christmas" date.
 */
public class ChristmasTests {
    static BlockPos floor(GameTestHelper h) {
        return GadgetTests.floor(h.getLevel(), h.absolutePos(new BlockPos(4, 2, 4)));
    }

    static void hold(CT.Bot b, String id, int n) {
        b.p.setItemInHand(InteractionHand.MAIN_HAND, id == null ? ItemStack.EMPTY : ArcanaContent.stack(id, n));
    }

    static InteractionResult clickAt(CT.Bot b, BlockPos pos, Vec3 hit, boolean sneak) {
        b.p.setShiftKeyDown(sneak);
        try {
            return CT.click(b.p, new BlockHitResult(hit, Direction.NORTH, pos, false));
        } finally {
            b.p.setShiftKeyDown(false);
        }
    }

    @GameTest(maxTicks = 100, padding = 12)
    public void firTreeDecorations(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("christmas-tree");
        BlockPos at = floor(h);
        f.check(ArcanaContent.place(level, at, "arcana_fir_tree", Direction.NORTH), "fir tree not placed");
        CT.Bot a = CT.join(h, "xmTree", false);
        CT.pose(a.p, Vec3.atBottomCenterOf(at.offset(0, 0, -2)), 0, 10);
        if (!(level.getBlockEntity(at) instanceof TreeBlockEntity be)) {
            f.add("no tree block entity: " + level.getBlockEntity(at));
            a.leave();
            f.finish(h);
            return;
        }
        f.check(!level.getBlockState(at).getValue(FurnitureBlock.LIT) && level.getBlockState(at).getLightEmission() == 0,
                "a bare fir tree glows");
        Vec3 front = Vec3.atBottomCenterOf(at).add(0, 0.6, -0.7);
        // hang every slot: each click uses one bauble and fills one more spot
        hold(a, "arcana_red_bauble", 64);
        for (int i = 0; i < TreeSlots.SLOTS.length; i++) {
            InteractionResult r = clickAt(a, at, front, false);
            f.check(r.consumesAction(), "hanging bauble " + i + " returned " + r);
        }
        f.check(be.ornamentCount() == TreeSlots.SLOTS.length, "tree holds " + be.ornamentCount() + " of " + TreeSlots.SLOTS.length);
        f.check(a.p.getMainHandItem().getCount() == 64 - TreeSlots.SLOTS.length, "baubles used: " + (64 - a.p.getMainHandItem().getCount()));
        clickAt(a, at, front, false);
        f.check(a.p.getMainHandItem().getCount() == 64 - TreeSlots.SLOTS.length, "a bauble was used up on a full tree");
        // the first click hangs on the spot nearest the click (a front spot, low on the tree)
        // topper and lights
        hold(a, "arcana_tree_star", 2);
        clickAt(a, at, front, false);
        clickAt(a, at, front, false);
        f.check("arcana_tree_star".equals(be.topper()) && a.p.getMainHandItem().getCount() == 1, "star: topper " + be.topper()
                + ", left " + a.p.getMainHandItem().getCount());
        hold(a, "arcana_tree_lights", 1);
        clickAt(a, at, front, false);
        f.check(be.hasLights() && a.p.getMainHandItem().isEmpty(), "tree lights not wound on");
        f.check(level.getBlockState(at).getValue(FurnitureBlock.LIT) && level.getBlockState(at.above(2)).getValue(FurnitureBlock.LIT),
                "lights did not switch the tree on (all parts)");
        f.check(level.getBlockState(at.above()).getLightEmission() > 0, "a lit tree gives no light");
        // empty hand switches the lights
        hold(a, null, 0);
        clickAt(a, at, front, false);
        f.check(!level.getBlockState(at).getValue(FurnitureBlock.LIT), "empty hand did not switch the lights off");
        clickAt(a, at, front, false);
        f.check(level.getBlockState(at).getValue(FurnitureBlock.LIT), "empty hand did not switch the lights back on");
        // sneak + empty hand takes the nearest bauble back; up high it takes the star
        clickAt(a, at, front, true);
        f.check(be.ornamentCount() == TreeSlots.SLOTS.length - 1 && a.p.getInventory().countItem(ArcanaContent.item("arcana_red_bauble")) == 1,
                "sneak-click did not give one bauble back");
        a.p.getInventory().clearContent(); // the bauble landed in the main hand: a sneak-click with it would hang it again
        clickAt(a, at.above(2), Vec3.atBottomCenterOf(at.above(2)).add(0, 0.8, -0.3), true);
        f.check(be.topper().isEmpty() && a.p.getInventory().countItem(ArcanaContent.item("arcana_tree_star")) == 1, "the star was not taken back");
        // breaking the tree drops it plus everything on it
        CT.collectDrops(level, at, 6);
        CT.breakBlock(a.p, at.above());
        Map<String, Integer> drops = CT.collectDrops(level, at, 6);
        f.check(drops.getOrDefault("aftrhrs:arcana_fir_tree", 0) == 1, "tree not dropped: " + drops);
        f.check(drops.getOrDefault("aftrhrs:arcana_red_bauble", 0) == TreeSlots.SLOTS.length - 1, "baubles not dropped: " + drops);
        f.check(drops.getOrDefault("aftrhrs:arcana_tree_lights", 0) == 1, "lights not dropped: " + drops);
        CT.clear(level, at, 5);
        a.leave();
        f.finish(h);
    }

    @GameTest(maxTicks = 100, padding = 12)
    public void presentsWrapCarryAndOpen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("christmas-present");
        ChristmasModule m = ChristmasModule.active();
        BlockPos at = floor(h);
        CT.Bot giver = CT.join(h, "xmGiver", false), kid = CT.join(h, "xmKid", false), other = CT.join(h, "xmOther", false);
        if (m == null) {
            f.add("christmas module not running");
            giver.leave();
            kid.leave();
            other.leave();
            f.finish(h);
            return;
        }
        ArcanaContent.place(level, at, "arcana_red_present", Direction.NORTH);
        for (CT.Bot b : List.of(giver, kid, other)) CT.pose(b.p, Vec3.atBottomCenterOf(at.offset(0, 0, -2)), 0, 30);
        PresentBlockEntity be = level.getBlockEntity(at) instanceof PresentBlockEntity p ? p : null;
        f.check(be != null, "no present block entity");
        if (be == null) {
            f.finish(h);
            return;
        }
        // nothing to wrap yet; nesting refused
        f.check("empty".equals(m.wrap(giver.p, level, at, kid.p.getGameProfile().name(), "", false)), "an empty present was wrapped");
        f.check(!PresentBlockEntity.allowed(ArcanaContent.stack("arcana_green_present", 1)) && !PresentBlockEntity.allowed(new ItemStack(Items.SHULKER_BOX)),
                "a present or shulker box may go inside a present");
        be.setItem(0, new ItemStack(Items.DIAMOND, 3));
        be.setItem(4, ArcanaContent.stack("arcana_candy_cane", 5));
        f.check("unknown".equals(m.wrap(giver.p, level, at, "NoSuchPlayerXyz", "", false)), "wrapped for a player who never played here");
        f.check(m.wrap(giver.p, level, at, kid.p.getGameProfile().name(), "Merry Christmas!", false) == null, "wrapping failed");
        f.check(be.wrapped() && be.gift() != null && kid.p.getUUID().equals(be.gift().to()), "present not wrapped for the kid");
        f.check("gone".equals(m.wrap(giver.p, level, at, "", "", false)), "an already wrapped present was wrapped again");
        f.check("gone".equals(m.wrap(giver.p, level, at.above(3), "", "", false)), "wrapped a present that isn't there");
        // someone else can't open it
        hold(other, null, 0);
        CT.click(other.p, at, Direction.UP);
        f.check(be.wrapped() && !be.isEmpty() && other.p.getInventory().countItem(Items.DIAMOND) == 0, "someone else opened the present");
        // breaking it carries everything in the item; placing it back restores it
        CT.collectDrops(level, at, 5);
        giver.p.setGameMode(GameType.SURVIVAL);
        CT.breakBlock(giver.p, at);
        List<ItemEntity> drops = CT.dropEntities(level, at, 5);
        f.check(drops.size() == 1, "broken present dropped " + drops.size() + " stacks");
        ItemStack carried = drops.isEmpty() ? ItemStack.EMPTY : drops.getFirst().getItem().copy();
        drops.forEach(net.minecraft.world.entity.Entity::discard);
        f.check(carried.has(DataComponents.CONTAINER) && carried.has(DataComponents.CUSTOM_DATA) && carried.has(DataComponents.BLOCK_STATE),
                "the dropped present lost its contents / tag / wrapping: " + carried.getComponents());
        hold(giver, null, 0);
        giver.p.setItemInHand(InteractionHand.MAIN_HAND, carried);
        CT.click(giver.p, at.below(), Direction.UP);
        BlockState placed = level.getBlockState(at);
        PresentBlockEntity be2 = level.getBlockEntity(at) instanceof PresentBlockEntity p2 ? p2 : null;
        f.check(be2 != null && be2.wrapped() && be2.gift() != null && kid.p.getUUID().equals(be2.gift().to())
                && be2.getItem(0).is(Items.DIAMOND) && be2.getItem(0).getCount() == 3, "re-placed present is not the same gift: " + placed);
        // the kid opens it and gets the gifts; the box stays, open and empty
        if (be2 != null) {
            hold(kid, null, 0);
            CT.click(kid.p, at, Direction.UP);
            f.check(kid.p.getInventory().countItem(Items.DIAMOND) == 3 && kid.p.getInventory().countItem(ArcanaContent.item("arcana_candy_cane")) == 5,
                    "the kid did not get the gifts");
            f.check(!be2.wrapped() && be2.isEmpty() && be2.gift() == null, "the opened box is not open and empty");
        }
        CT.clear(level, at, 4);
        giver.leave();
        kid.leave();
        other.leave();
        f.finish(h);
    }

    @GameTest(maxTicks = 100, padding = 12)
    public void presentWaitsForChristmas(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("christmas-wait");
        f.check(ChristmasModule.nextChristmas(LocalDate.of(2026, 11, 21)) == LocalDate.of(2026, 12, 25).toEpochDay(), "November");
        f.check(ChristmasModule.nextChristmas(LocalDate.of(2026, 12, 25)) == LocalDate.of(2026, 12, 25).toEpochDay(), "Christmas Day");
        f.check(ChristmasModule.nextChristmas(LocalDate.of(2026, 12, 26)) == LocalDate.of(2027, 12, 25).toEpochDay(), "Boxing Day");
        ChristmasModule m = ChristmasModule.active();
        BlockPos at = floor(h);
        CT.Bot a = CT.join(h, "xmWait", false);
        CT.pose(a.p, Vec3.atBottomCenterOf(at.offset(0, 0, -2)), 0, 30);
        ArcanaContent.place(level, at, "arcana_gold_present", Direction.NORTH);
        if (m != null && level.getBlockEntity(at) instanceof PresentBlockEntity be) {
            be.setItem(0, new ItemStack(Items.EMERALD));
            f.check(m.wrap(a.p, level, at, "", "", true) == null && be.gift() != null && be.gift().forAnyone(), "wrap for anyone failed");
            boolean early = be.gift().daysLeft() > 0;
            hold(a, null, 0);
            CT.click(a.p, at, Direction.UP);
            if (early) f.check(be.wrapped() && a.p.getInventory().countItem(Items.EMERALD) == 0, "opened before Christmas");
            else f.check(!be.wrapped() && a.p.getInventory().countItem(Items.EMERALD) == 1, "could not open on/after Christmas");
        } else {
            f.add("no module / present");
        }
        CT.clear(level, at, 4);
        a.leave();
        f.finish(h);
    }

    @GameTest(maxTicks = 100, padding = 12)
    public void adventWreathCandles(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("christmas-advent");
        BlockPos at = floor(h);
        ArcanaContent.place(level, at, "arcana_advent_wreath", Direction.NORTH);
        CT.Bot a = CT.join(h, "xmAdvent", false);
        CT.pose(a.p, Vec3.atBottomCenterOf(at.offset(0, 0, -2)), 0, 30);
        FurnitureBlock fb = (FurnitureBlock) level.getBlockState(at).getBlock();
        f.check(fb.age != null && level.getBlockState(at).getValue(fb.age) == 0 && level.getBlockState(at).getLightEmission() == 0,
                "a new advent wreath is lit");
        a.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
        int last = 0;
        for (int i = 1; i <= 5; i++) {
            CT.click(a.p, at, Direction.UP);
            int lit = level.getBlockState(at).getValue(fb.age);
            f.check(lit == Math.min(4, i), "after " + i + " lightings " + lit + " candles burn");
            f.check(level.getBlockState(at).getLightEmission() >= last, "light went down while lighting candles");
            last = level.getBlockState(at).getLightEmission();
        }
        f.check(last > 0, "four candles give no light");
        a.p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        CT.click(a.p, at, Direction.UP);
        f.check(level.getBlockState(at).getValue(fb.age) == 0 && level.getBlockState(at).getLightEmission() == 0, "empty hand did not snuff the candles");
        // the snow globe takes a shake
        BlockPos g = at.east(2);
        ArcanaContent.place(level, g, "arcana_snow_globe", Direction.NORTH);
        f.check(CT.click(a.p, g, Direction.UP).consumesAction(), "the snow globe did not shake");
        f.check(level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(4)).isEmpty(), "clicks dropped items");
        CT.clear(level, at, 4);
        a.leave();
        f.finish(h);
    }
}
