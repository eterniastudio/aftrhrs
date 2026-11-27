package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.feature.fairylights.BulbItem;
import net.axther.aftrhrs.feature.fairylights.BulbStyle;
import net.axther.aftrhrs.feature.fairylights.FairyCommands;
import net.axther.aftrhrs.feature.fairylights.FairyLights;
import net.axther.aftrhrs.feature.fairylights.FairyLightsConfig;
import net.axther.aftrhrs.feature.fairylights.FairyNet;
import net.axther.aftrhrs.feature.fairylights.Fastener;
import net.axther.aftrhrs.feature.fairylights.FastenerRef;
import net.axther.aftrhrs.feature.fairylights.HookBlockEntity;
import net.axther.aftrhrs.feature.fairylights.KnotEntity;
import net.axther.aftrhrs.feature.fairylights.PlayerFastener;
import net.axther.aftrhrs.feature.fairylights.Strand;
import net.axther.aftrhrs.feature.fairylights.StrandSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fairy lights: stringing between two fences by hand, save/load of hooks and knots, breaking an end drops the strand,
 * the length / per-fastener limits, real light, the pattern recipe and clicks on hung strands.
 */
public class FairyLightsTests {
    private static void floor(ServerLevel level, BlockPos center, int r) {
        CT.clear(level, center, r);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) level.setBlock(center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
        }
    }

    private static Strand only(Fastener f) {
        return f.outgoing().isEmpty() ? null : f.outgoing().iterator().next();
    }

    /** Right-click fence A then fence B holding a String of Lights: one strand A to B, the item used, real light once it hangs. */
    @GameTest(maxTicks = 160, padding = 16, environment = ToolTests.ENV)
    public void stringBetweenFences(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btFairy", false);
        ServerPlayer p = bot.p;
        BlockPos a = h.absolutePos(new BlockPos(2, 2, 4)), b = a.east(7);
        floor(level, a.east(3), 6);
        level.setBlock(a, Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(b, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("fairy_string");
        p.setItemInHand(InteractionHand.MAIN_HAND, FairyLights.preset("holiday"));
        CT.pose(p, Vec3.atBottomCenterOf(a.east(3).south(2)), 0, 20);
        CT.click(p, a, Direction.UP);
        KnotEntity ka = KnotEntity.find(level, a);
        f.check(ka != null, "no knot on fence A");
        PlayerFastener pf = PlayerFastener.existing(p);
        f.check(pf != null && pf.pending() != null, "player is not holding a loose end after clicking A");
        f.check(p.getMainHandItem().getCount() == 1, "item used before the strand was finished");
        CT.click(p, b, Direction.UP);
        KnotEntity kb = KnotEntity.find(level, b);
        f.check(kb != null, "no knot on wall B");
        Strand s = ka == null ? null : only(ka.fastener());
        f.check(s != null && s.dest.equals(FastenerRef.knot(b)), "strand does not end at B: " + (s == null ? null : s.dest));
        f.check(s != null && s.drop, "finished strand would not drop its item");
        f.check(kb != null && kb.fastener().incoming().size() == 1, "B does not know the strand");
        f.check(p.getMainHandItem().isEmpty(), "String of Lights not used up: " + p.getMainHandItem());
        f.check(pf == null || pf.pending() == null, "still holding a loose end");
        f.check(s != null && s.spec.bulbs().size() == 5, "strand lost its bulbs: " + (s == null ? null : s.spec));
        h.runAfterDelay(30, () -> {
            f.check(s != null && s.lit, "strand not lit");
            int lights = 0;
            if (s != null) for (BlockPos q : s.litBlocks) if (level.getBlockState(q).is(Blocks.LIGHT)) lights++;
            f.check(lights > 0 && lights <= FairyLightsConfig.maxLightBlocksPerStrand, "light blocks: " + lights);
            List<BlockPos> lit = s == null ? List.of() : new ArrayList<>(s.litBlocks);
            // the fence goes: its knot and the strand come down, the item drops, the light goes
            CT.breakBlock(p, a);
            h.runAfterDelay(12, () -> {
                Map<String, Integer> drops = CT.collectDrops(level, a, 3);
                f.check(drops.getOrDefault("aftrhrs:fairy_lights", 0) == 1, "breaking the fence did not drop the strand: " + drops);
                f.check(KnotEntity.find(level, a) == null, "knot left on the broken fence");
                for (BlockPos q : lit) f.check(!level.getBlockState(q).is(Blocks.LIGHT), "light block left at " + q.toShortString());
                CT.clear(level, a.east(3), 6);
                bot.leave();
                f.finish(h);
            });
        });
    }

    /** A hook's strands and a knot's strands come back the same from saved data. */
    @GameTest(maxTicks = 60, padding = 16, environment = ToolTests.ENV)
    public void strandsSaveAndLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(2, 2, 4)), b = a.east(6), c = a.south(5);
        floor(level, a.east(3), 6);
        level.setBlock(c, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("fairy_save");
        Fastener fa = FairyCommands.fastenerAt(level, a), fb = FairyCommands.fastenerAt(level, b), fc = FairyCommands.fastenerAt(level, c);
        f.check(fa != null && fb != null && fc != null, "could not set up hooks / knot");
        if (fa == null || fb == null || fc == null) {
            f.finish(h);
            return;
        }
        Strand s1 = FairyCommands.string(fa, fb, FairyLights.preset("bunting_noel"), 1.4f);
        Strand s2 = FairyCommands.string(fc, fa, FairyLights.preset("tinsel_gold"), 1);
        f.check(s1 != null && s2 != null, "strands not made");
        h.runAfterDelay(10, () -> {
            HookBlockEntity be = (HookBlockEntity) level.getBlockEntity(a);
            CompoundTag saved = be.saveWithFullMetadata(level.registryAccess());
            BlockEntity copy = BlockEntity.loadStatic(a, level.getBlockState(a), saved, level.registryAccess());
            Strand back = copy instanceof HookBlockEntity hc ? hc.fastener().strand(s1.id) : null;
            f.check(back != null, "hook strand missing after load: " + saved);
            if (back != null) {
                f.check(back.spec.equals(s1.spec), "spec changed: " + back.spec + " vs " + s1.spec);
                f.check("NOEL".equals(back.spec.text()), "bunting letters lost: " + back.spec.text());
                f.check(Math.abs(back.slack - 1.4f) < 1e-4, "slack lost: " + back.slack);
                f.check(back.dest.equals(FastenerRef.hook(b)), "dest changed: " + back.dest);
                f.check(((HookBlockEntity) copy).fastener().incoming().containsKey(s2.id), "incoming strand from the knot lost");
            }
            KnotEntity knot = KnotEntity.find(level, c);
            CompoundTag kt = knot == null ? null : knot.getAttached(KnotEntity.DATA);
            f.check(kt != null, "knot has no saved data attachment");
            if (kt != null) {
                KnotEntity fresh = new KnotEntity(level, c);
                fresh.setAttached(KnotEntity.DATA, kt.copy());
                Strand k = fresh.fastener().strand(s2.id);
                f.check(k != null && k.spec.color() == FairyLights.GOLD, "knot strand not restored: " + (k == null ? null : k.spec));
            }
            CT.clear(level, a.east(3), 7);
            f.finish(h);
        });
    }

    /** Breaking a hook (survival) drops its strand item and the hook; the other end forgets the strand. */
    @GameTest(maxTicks = 100, padding = 16, environment = ToolTests.ENV)
    public void breakingHookDropsStrand(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btFairyBrk", false);
        BlockPos a = h.absolutePos(new BlockPos(2, 5, 4)), b = a.east(6);
        CT.clear(level, a.east(3), 6);
        // two posts with hooks on top: the strand hangs free (bulbs in the air get real light)
        level.setBlock(a.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(b.below(), Blocks.STONE.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("fairy_break");
        Fastener fa = FairyCommands.fastenerAt(level, a), fb = FairyCommands.fastenerAt(level, b);
        Strand s = fa == null || fb == null ? null : FairyCommands.string(fa, fb, FairyLights.preset("candy_cane"), 1);
        f.check(s != null, "no strand");
        h.runAfterDelay(25, () -> {
            List<BlockPos> lit = s == null ? List.of() : new ArrayList<>(s.litBlocks);
            f.check(!lit.isEmpty(), "candy cane strand gave no light");
            // the loot table gives the hook back only for hooks placed from the item
            var placed = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(a), level, a, level.getBlockEntity(a));
            f.check(placed.stream().anyMatch(st -> st.is(FairyLights.HOOK_ITEM)), "placed hook's loot has no hook: " + placed);
            var auto = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(a).setValue(net.axther.aftrhrs.feature.fairylights.HookBlock.PLACED, false),
                    level, a, null);
            f.check(auto.isEmpty(), "a hook put up by stringing would drop: " + auto);
            bot.p.getInventory().clearContent();
            CT.breakBlock(bot.p, a);
            h.runAfterDelay(25, () -> {
                // the posts stand in the air: the drops fall, so look all the way down
                Map<String, Integer> drops = CT.collectDrops(level, a.below(5), 7);
                f.check(drops.getOrDefault("aftrhrs:fairy_lights", 0) == 1, "strand item not dropped: " + drops);
                int hooks = drops.getOrDefault("aftrhrs:fairy_hook", 0) + bot.p.getInventory().countItem(FairyLights.HOOK_ITEM);
                f.check(hooks == 1, "placed hook did not drop itself: " + drops + ", in inventory "
                        + bot.p.getInventory().countItem(FairyLights.HOOK_ITEM));
                f.check(!(level.getBlockState(a).getBlock() instanceof net.axther.aftrhrs.feature.fairylights.HookBlock), "hook not broken");
                Fastener other = level.getBlockEntity(b) instanceof HookBlockEntity hb ? hb.fastener() : null;
                f.check(other != null && other.incoming().isEmpty(), "other end still lists the strand");
                for (BlockPos q : lit) f.check(!level.getBlockState(q).is(Blocks.LIGHT), "light block left at " + q.toShortString());
                CT.clear(level, a.east(3), 7);
                bot.leave();
                f.finish(h);
            });
        });
    }

    /** A strand longer than max-length can't be finished; a full hook takes no more strands; a strung hook goes when empty. */
    @GameTest(maxTicks = 80, padding = 16, environment = ToolTests.ENV)
    public void strandLimits(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btFairyLim", false);
        CT.Bot bot2 = CT.join(h, "btFairyLm2", false);
        ServerPlayer p = bot.p;
        float oldLen = FairyLightsConfig.maxLength;
        int oldPer = FairyLightsConfig.maxPerFastener;
        BlockPos a = h.absolutePos(new BlockPos(1, 2, 2)), b = a.east(9);
        floor(level, a.east(4), 7);
        CT.Fails f = new CT.Fails("fairy_limits");
        try {
            FairyLightsConfig.maxLength = 6;
            level.setBlock(a, Blocks.OAK_FENCE.defaultBlockState(), 3);
            level.setBlock(b, Blocks.OAK_FENCE.defaultBlockState(), 3);
            p.setItemInHand(InteractionHand.MAIN_HAND, FairyLights.preset("warm"));
            CT.pose(p, Vec3.atBottomCenterOf(a.east(4).south(2)), 0, 20);
            CT.click(p, a, Direction.UP);
            CT.click(p, b, Direction.UP);
            KnotEntity ka = KnotEntity.find(level, a);
            Strand s = ka == null ? null : only(ka.fastener());
            f.check(s != null && s.dest.isPlayer(), "a 9 block strand was finished with max-length 6: " + (s == null ? null : s.dest));
            f.check(p.getMainHandItem().getCount() == 1, "item used by a refused strand");
            // a hook that is full
            FairyLightsConfig.maxPerFastener = 2;
            BlockPos c = a.south(4), d = c.east(3), e = c.east(5);
            Fastener fc = FairyCommands.fastenerAt(level, c), fd = FairyCommands.fastenerAt(level, d), fe = FairyCommands.fastenerAt(level, e);
            FairyCommands.string(fc, fd, FairyLights.preset("warm"), 1);
            FairyCommands.string(fc, fe, FairyLights.preset("warm"), 1);
            bot2.p.setItemInHand(InteractionHand.MAIN_HAND, FairyLights.preset("warm"));
            CT.pose(bot2.p, Vec3.atBottomCenterOf(c.south(2)), 180, 30);
            CT.click(bot2.p, c, Direction.UP);
            f.check(fc.outgoing().size() == 2, "a full hook took another strand: " + fc.outgoing().size());
            PlayerFastener pf2 = PlayerFastener.existing(bot2.p);
            f.check(pf2 == null || pf2.pending() == null, "player holds a loose end from a full hook");
        } finally {
            FairyLightsConfig.maxLength = oldLen;
            FairyLightsConfig.maxPerFastener = oldPer;
        }
        // letting go of the item drops the loose end (nothing drops: it was never finished)
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        h.runAfterDelay(10, () -> {
            KnotEntity ka = KnotEntity.find(level, a);
            f.check(ka == null || ka.fastener().outgoing().isEmpty(), "loose end kept after the item was put away");
            f.check(CT.collectDrops(level, a, 12).getOrDefault("aftrhrs:fairy_lights", 0) == 0, "an unfinished strand dropped an item");
            CT.clear(level, a.east(4), 8);
            bot.leave();
            bot2.leave();
            f.finish(h);
        });
    }

    /** Lights in the grid with a strand set its pattern in order; dyeing and glowstone work on the light items. */
    @GameTest(maxTicks = 20, environment = ToolTests.ENV)
    public void patternAndLightRecipes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("fairy_recipes");
        ItemStack red = new ItemStack(FairyLights.bulbItem(BulbStyle.HOLIDAY));
        red.set(DataComponents.DYED_COLOR, new DyedItemColor(0xB02E26));
        ItemStack star = new ItemStack(FairyLights.bulbItem(BulbStyle.STAR));
        ItemStack strand = new ItemStack(FairyLights.STRANDS.get(net.axther.aftrhrs.feature.fairylights.StrandKind.LIGHTS));
        CraftingInput in = CraftingInput.of(3, 3, List.of(strand, red, star, new ItemStack(Items.DYE.pick(net.minecraft.world.item.DyeColor.WHITE)), ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
        var match = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, level);
        f.check(match.isPresent(), "no recipe for strand + lights");
        if (match.isPresent()) {
            ItemStack out = match.get().value().assemble(in);
            StrandSpec spec = FairyLights.spec(out);
            f.check(spec.bulbs().size() == 2 && spec.bulbs().get(0).style() == BulbStyle.HOLIDAY && spec.bulbs().get(0).color() == 0xB02E26
                    && spec.bulbs().get(1).style() == BulbStyle.STAR, "pattern wrong: " + spec);
            f.check(spec.wire() == StrandSpec.Wire.WHITE, "white dye did not make a white wire: " + spec.wire());
            f.check(out.has(DataComponents.CUSTOM_MODEL_DATA), "strand icon colors missing");
        }
        CraftingInput tw = CraftingInput.of(2, 1, List.of(new ItemStack(FairyLights.bulbItem(BulbStyle.FAIRY)), new ItemStack(Items.GLOWSTONE_DUST)));
        var twm = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, tw, level);
        f.check(twm.isPresent() && Boolean.TRUE.equals(twm.get().value().assemble(tw).get(FairyLights.TWINKLE)), "glowstone did not make a twinkling bulb");
        CraftingInput dy = CraftingInput.of(2, 1, List.of(new ItemStack(FairyLights.bulbItem(BulbStyle.FAIRY)), new ItemStack(Items.DYE.pick(net.minecraft.world.item.DyeColor.BLUE))));
        var dym = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, dy, level);
        f.check(dym.isPresent() && BulbItem.bulb(dym.get().value().assemble(dy)).color() >= 0, "light could not be dyed");
        f.finish(h);
    }

    /** Clicks on a hung strand: a light swaps one bulb (old one back), an empty hand cycles the lights, punching takes it down. */
    @GameTest(maxTicks = 60, padding = 16, environment = ToolTests.ENV)
    public void clickingHungStrands(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btFairyClk", false);
        ServerPlayer p = bot.p;
        BlockPos a = h.absolutePos(new BlockPos(2, 3, 4)), b = a.east(6);
        floor(level, a.east(3), 6);
        level.setBlock(a.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(b.below(), Blocks.STONE.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("fairy_clicks");
        Fastener fa = FairyCommands.fastenerAt(level, a), fb = FairyCommands.fastenerAt(level, b);
        Strand s = FairyCommands.string(fa, fb, FairyLights.preset("warm"), 1);
        s.curve(fa.point(), fb.point());
        Strand.Feature mid = s.features().get(s.features().size() / 2);
        Vec3 hit = fa.point().add(mid.x(), mid.y() - 0.1, mid.z());
        CT.pose(p, hit.add(0, -1.2, 2.5), 180, 0);
        ItemStack green = new ItemStack(FairyLights.bulbItem(BulbStyle.HOLIDAY), 2);
        green.set(DataComponents.DYED_COLOR, new DyedItemColor(0x2F9E4A));
        p.setItemInHand(InteractionHand.MAIN_HAND, green);
        FairyNet.act(p, new FairyNet.Action(fa.ref(), s.id, false, mid.index(), hit));
        f.check(s.spec.bulbs().size() == s.features().size(), "pattern not spread over the strand: " + s.spec.bulbs().size());
        f.check(s.bulb(mid.index()).style() == BulbStyle.HOLIDAY, "clicked bulb not swapped");
        f.check(p.getMainHandItem().getCount() == 1, "light not used");
        f.check(p.getInventory().countItem(FairyLights.bulbItem(BulbStyle.FAIRY)) == 1, "old bulb not given back");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        FairyNet.act(p, new FairyNet.Action(fa.ref(), s.id, false, mid.index(), hit));
        f.check(s.mode == Strand.Mode.NIGHT, "empty hand did not switch to dusk-to-dawn: " + s.mode);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STRING));
        float before = s.slack;
        FairyNet.act(p, new FairyNet.Action(fa.ref(), s.id, false, -1, hit));
        f.check(s.slack > before, "string did not loosen the strand");
        // too far away: ignored
        FairyNet.act(p, new FairyNet.Action(fa.ref(), s.id, true, -1, hit.add(0, 30, 0)));
        f.check(fa.strand(s.id) != null, "a punch far from the strand took it down");
        FairyNet.act(p, new FairyNet.Action(fa.ref(), s.id, true, -1, hit));
        f.check(fa.strand(s.id) == null, "punching did not take the strand down");
        h.runAfterDelay(3, () -> {
            f.check(CT.collectDrops(level, BlockPos.containing(hit), 3).getOrDefault("aftrhrs:fairy_lights", 0) == 1, "punched strand did not drop");
            CT.clear(level, a.east(3), 7);
            bot.leave();
            f.finish(h);
        });
    }

    /**
     * The far end vanishing without taking its strands down (a hook removed with no break side effects, a knot discarded
     * without dropping): the owner's cached far end ({@link Strand#resolveDest}) notices on its next tick and the strand
     * drops, the same as with no cache.
     */
    @GameTest(maxTicks = 60, padding = 16, environment = ToolTests.ENV)
    public void strandDropsWhenFarEndVanishes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(2, 2, 4)), b = a.east(6), c = a.south(5), d = c.east(6);
        floor(level, a.east(3), 7);
        level.setBlock(d, Blocks.OAK_FENCE.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("fairy_far_end");
        Fastener fa = FairyCommands.fastenerAt(level, a), fb = FairyCommands.fastenerAt(level, b), fc = FairyCommands.fastenerAt(level, c),
                fd = FairyCommands.fastenerAt(level, d);
        f.check(fa != null && fb != null && fc != null && fd != null, "could not set up hooks / knot");
        if (fa == null || fb == null || fc == null || fd == null) {
            f.finish(h);
            return;
        }
        Strand toHook = FairyCommands.string(fa, fb, FairyLights.preset("warm"), 1);
        Strand toKnot = FairyCommands.string(fc, fd, FairyLights.preset("warm"), 1);
        f.check(toHook != null && toKnot != null, "strands not made");
        // a few ticks hanging: both owners have their far end cached
        h.runAfterDelay(5, () -> {
            f.check(fa.strand(toHook.id) != null && fc.strand(toKnot.id) != null, "strands did not stay up");
            level.setBlock(b, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                    | net.minecraft.world.level.block.Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
            KnotEntity kd = KnotEntity.find(level, d);
            f.check(kd != null, "no knot on the far fence");
            if (kd != null) kd.discard();
            h.runAfterDelay(2, () -> {
                f.check(fa.strand(toHook.id) == null, "strand to a vanished hook still up (stale far end)");
                f.check(fc.strand(toKnot.id) == null, "strand to a discarded knot still up (stale far end)");
                f.check(CT.collectDrops(level, a.east(3).south(2), 8).getOrDefault("aftrhrs:fairy_lights", 0) == 2, "the two strands did not drop");
                CT.clear(level, a.east(3), 8);
                f.finish(h);
            });
        });
    }

    /**
     * The knot renderer's per-tick cache on {@link KnotEntity} (rCord, rBow, rWall, rStamp, rFor) is client scratch: it
     * never reaches the saved entity, and a knot loaded back starts with it empty while its strands come back.
     */
    @GameTest(maxTicks = 40, padding = 16, environment = ToolTests.ENV)
    public void knotRenderCacheNeverSaved(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(2, 2, 4)), b = a.east(5);
        floor(level, a.east(2), 6);
        level.setBlock(a, Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(b, Blocks.OAK_FENCE.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("fairy_knot_cache");
        Fastener fa = FairyCommands.fastenerAt(level, a), fb = FairyCommands.fastenerAt(level, b);
        Strand s = fa == null || fb == null ? null : FairyCommands.string(fa, fb, FairyLights.preset("garland_lit"), 1);
        KnotEntity k = KnotEntity.find(level, a);
        f.check(s != null && k != null, "could not string the two fences");
        if (s == null || k == null) {
            f.finish(h);
            return;
        }
        k.rCord = 0x123456;
        k.rBow = true;
        k.rWall = true;
        k.rStamp = 77;
        k.rFor = new Object();
        CompoundTag tag = net.axther.aftrhrs.gametest.creatures.CT.save(k);
        String text = tag.toString();
        for (String key : new String[]{"rCord", "rBow", "rWall", "rStamp", "rFor"}) {
            f.check(!text.contains(key), "knot saved its render cache field " + key + ": " + text);
        }
        net.minecraft.world.entity.Entity e = net.axther.aftrhrs.gametest.creatures.CT.load(level, tag);
        f.check(e instanceof KnotEntity, "saved knot did not load back: " + e);
        if (e instanceof KnotEntity copy) {
            f.check(copy.rStamp == Integer.MIN_VALUE && copy.rFor == null && copy.rCord == 0 && !copy.rBow && !copy.rWall,
                    "loaded knot came back with a render cache");
            f.check(copy.fastener().strand(s.id) != null, "loaded knot lost its strand");
        }
        CT.clear(level, a.east(2), 6);
        f.finish(h);
    }
}
