package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureSpec;
import net.axther.aftrhrs.content.FurnitureStorageBlockEntity;
import net.axther.aftrhrs.content.GramophoneBlockEntity;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.feature.fishing.FishingModule;
import net.axther.aftrhrs.feature.tools.ToolsModule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Furniture behavior over every piece that has it: toggles, hearths, seats, storage, the gramophone, planters. */
public class FurnitureBehaviorTests {
    static List<String> ids(Predicate<FurnitureSpec> p) {
        List<String> out = new ArrayList<>();
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (c.kind().equals("furniture") && c.block() instanceof FurnitureBlock fb && p.test(fb.spec)) out.add(c.id());
        }
        return out;
    }

    /** Places a piece on stone at center (facing north, like a player south of it), with every part. */
    static BlockState put(ServerLevel level, BlockPos center, String id) {
        CT.clear(level, center, 5);
        FurnitureBlock fb = (FurnitureBlock) ArcanaContent.block(id);
        BlockPos support = fb.spec.floor ? center.below() : fb.spec.roof ? center.above() : center.north();
        level.setBlock(support, Blocks.STONE.defaultBlockState(), 2 | 16);
        if (!ArcanaContent.place(level, center, id, Direction.SOUTH)) return null;
        return level.getBlockState(center);
    }

    static void reset(ServerPlayer p, BlockPos center) {
        if (p.isPassenger()) p.stopRiding();
        p.closeContainer();
        p.getInventory().clearContent();
        p.setShiftKeyDown(false);
        CT.pose(p, Vec3.atBottomCenterOf(center).add(0, -4, 0), 180, 30);
    }

    // ------------------------------------------------------------------------------------------------ toggles
    @GameTest(maxTicks = 4000, padding = 16)
    public void togglesFlipEveryPart(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btToggle", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("furniture_toggles");
        List<String> all = ids(s -> s.toggle != null && s.toggle.player() && s.storage == null && s.seats.isEmpty());
        FurnitureSweepTests.stepped(h, all, 30, id -> {
            reset(p, center);
            BlockState s = put(level, center, id);
            if (s == null) {
                f.add(id + ": could not place");
                return;
            }
            FurnitureBlock fb = (FurnitureBlock) s.getBlock();
            boolean before = s.getValue(fb.toggle);
            f.check(before == fb.spec.toggle.defaultValue(), id + ": default " + before);
            List<BlockPos> parts = fb.partPositions(s, center);
            for (int round = 0; round < 2; round++) {
                // click the last part (any part toggles the whole piece)
                BlockPos at = parts.get(parts.size() - 1);
                CT.click(p, at, Direction.UP);
                boolean want = round == 0 ? !before : before;
                for (int i = 0; i < parts.size(); i++) {
                    BlockState ps = level.getBlockState(parts.get(i));
                    f.check(ps.is(fb) && ps.getValue(fb.toggle) == want, id + ": part " + i + " not toggled to " + want + " on click " + round);
                }
                if (fb.toggle == FurnitureBlock.LIT) {
                    int light = 0;
                    for (BlockPos q : parts) light = Math.max(light, level.getBlockState(q).getLightEmission());
                    int spec = 0;
                    for (int l : fb.spec.light) spec = Math.max(spec, l);
                    f.check(want ? light == spec : light == 0, id + ": light " + light + " when lit=" + want + " (spec " + spec + ")");
                }
            }
            f.check(CT.collectDrops(level, center, 6).isEmpty(), id + ": toggling dropped items");
            bot.drain();
        }, () -> {
            f.check(all.size() > 90, "only " + all.size() + " toggle pieces");
            CT.clear(level, center, 5);
            bot.leave();
            f.finish(h);
        });
    }

    @GameTest(maxTicks = 400, padding = 16)
    public void hearthsLightAndDouse(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btHearth", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("hearths");
        List<String> all = ids(s -> s.hearthLitItem != null);
        f.check(all.size() == 12, "hearths: " + all.size());
        for (String id : all) {
            reset(p, center);
            BlockState s = put(level, center, id);
            if (s == null) {
                f.add(id + ": could not place");
                continue;
            }
            f.check(!s.getValue(FurnitureBlock.LIT), id + ": placed lit");
            CT.click(p, center, Direction.UP); // empty hand does nothing
            f.check(!level.getBlockState(center).getValue(FurnitureBlock.LIT), id + ": lit by an empty hand");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
            CT.click(p, center, Direction.UP);
            f.check(level.getBlockState(center).getValue(FurnitureBlock.LIT), id + ": flint and steel did not light it");
            f.check(p.getMainHandItem().getDamageValue() == 1, id + ": flint and steel not damaged");
            f.check(level.getBlockState(center).getLightEmission() > 0, id + ": lit hearth gives no light");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
            CT.click(p, center, Direction.UP);
            f.check(!level.getBlockState(center).getValue(FurnitureBlock.LIT), id + ": shovel did not douse");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FIRE_CHARGE, 2));
            CT.click(p, center, Direction.UP);
            f.check(level.getBlockState(center).getValue(FurnitureBlock.LIT), id + ": fire charge did not light");
            f.check(p.getMainHandItem().getCount() == 1, id + ": fire charge not used up");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
            CT.click(p, center, Direction.UP);
            f.check(!level.getBlockState(center).getValue(FurnitureBlock.LIT), id + ": water did not douse");
            f.check(p.getMainHandItem().is(Items.BUCKET), id + ": water bucket not emptied");
            // a lit hearth drops the lit item, an unlit one the plain hearth
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
            CT.click(p, center, Direction.UP);
            p.getInventory().clearContent();
            CT.breakBlock(p, center);
            Map<String, Integer> d = CT.collectDrops(level, center, 6);
            f.check(d.equals(Map.of("aftrhrs:" + id + "_lit", 1)), id + ": lit hearth dropped " + d);
            bot.drain();
        }
        CT.clear(level, center, 5);
        bot.leave();
        f.finish(h);
    }

    // ------------------------------------------------------------------------------------------------ seats
    @GameTest(maxTicks = 4000, padding = 16)
    public void seatsSeatAndRelease(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btSeat", true);
        CT.Bot other = CT.join(h, "btSeat2", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("seats");
        List<String> all = ids(s -> !s.seats.isEmpty());
        FurnitureSweepTests.stepped(h, all, 20, id -> {
            reset(p, center);
            reset(other.p, center);
            BlockState s = put(level, center, id);
            if (s == null) {
                f.add(id + ": could not place");
                return;
            }
            FurnitureBlock fb = (FurnitureBlock) s.getBlock();
            int n = fb.spec.seats.size();
            CT.click(p, center, Direction.UP);
            Entity seat = p.getVehicle();
            f.check(seat != null && seat.entityTags().contains("aftrhrs_seat"), id + ": not seated (vehicle " + seat + ")");
            if (seat != null) {
                f.check(Math.abs(seat.getY() - center.getY() - fb.spec.seats.getFirst().y()) < 1.01, id + ": seat height " + (seat.getY() - center.getY()));
            }
            // a one-seat piece takes nobody else; a bench takes a second player
            CT.click(other.p, center, Direction.UP);
            if (n == 1) f.check(!other.p.isPassenger(), id + ": second player sat on a taken one-seat piece");
            else f.check(other.p.isPassenger() && other.p.getVehicle() != seat, id + ": second player found no free seat of " + n);
            // breaking the piece releases both and removes the seats
            CT.breakBlock(p, center);
            f.check(!p.isPassenger() && !other.p.isPassenger(), id + ": still riding after the piece broke");
            f.check(seat == null || seat.isRemoved(), id + ": seat entity left after breaking");
            List<Entity> left = level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(center).inflate(5), e -> e.entityTags().contains("aftrhrs_seat") && !e.isRemoved());
            f.check(left.isEmpty(), id + ": seats left " + left.size());
            Map<String, Integer> d = CT.collectDrops(level, center, 6);
            f.check(d.equals(Map.of("aftrhrs:" + id, 1)), id + ": dropped " + d);
            bot.drain();
            other.drain();
        }, () -> {
            f.check(all.size() > 100, "only " + all.size() + " seat pieces");
            CT.clear(level, center, 5);
            bot.leave();
            other.leave();
            f.finish(h);
        });
    }

    /** Getting off a seat removes it and puts the player on top of / in front of the piece, not inside it. */
    @GameTest(maxTicks = 200, padding = 16)
    public void standingUpLeavesNoSeat(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btStand", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 2, 4));
        reset(p, center);
        put(level, center, "arcana_oak_chair");
        CT.click(p, center, Direction.UP);
        Entity seat = p.getVehicle();
        if (seat == null) throw h.assertionException(net.minecraft.network.chat.Component.literal("not seated"));
        p.stopRiding();
        h.runAfterDelay(12, () -> {
            CT.Fails f = new CT.Fails("stand_up");
            f.check(seat.isRemoved(), "seat entity still there after standing up");
            f.check(level.noCollision(p, p.getBoundingBox()), "player stuck inside the chair at " + p.position());
            CT.clear(level, center, 5);
            bot.leave();
            f.finish(h);
        });
    }

    // ------------------------------------------------------------------------------------------------ storage
    @GameTest(maxTicks = 4000, padding = 16)
    public void storageOpensKeepsAndDrops(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btStore", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("storage");
        List<String> all = ids(s -> s.storage != null);
        FurnitureSweepTests.stepped(h, all, 20, id -> {
            reset(p, center);
            BlockState s = put(level, center, id);
            if (s == null) {
                f.add(id + ": could not place");
                return;
            }
            FurnitureBlock fb = (FurnitureBlock) s.getBlock();
            FurnitureSpec.Storage st = fb.spec.storage;
            int size = st.rows() * 9;
            List<BlockPos> parts = fb.partPositions(s, center);
            CT.click(p, parts.get(parts.size() - 1), Direction.UP);
            if (!(p.containerMenu instanceof ChestMenu menu)) {
                f.add(id + " (" + st.type() + "): no chest menu opened (" + p.containerMenu + ")");
                return;
            }
            Container c = menu.getContainer();
            f.check(c.getContainerSize() == size, id + ": " + c.getContainerSize() + " slots, spec rows " + st.rows());
            BlockEntity be = level.getBlockEntity(center);
            switch (st.type()) {
                case STORAGE, SHULKER -> {
                    f.check(be instanceof FurnitureStorageBlockEntity && c == be, id + ": menu is not the piece's inventory");
                    f.check(level.getBlockState(center).getValue(FurnitureBlock.OPEN), id + ": not open while viewed");
                    menu.getSlot(size - 1).set(new ItemStack(Items.DIAMOND, 3));
                    p.closeContainer();
                    f.check(!level.getBlockState(center).getValue(FurnitureBlock.OPEN), id + ": still open after closing");
                    // save / load (chunk reload)
                    var tag = be.saveWithFullMetadata(level.registryAccess());
                    BlockEntity re = BlockEntity.loadStatic(center, level.getBlockState(center), tag, level.registryAccess());
                    f.check(re instanceof Container rc && rc.getItem(size - 1).is(Items.DIAMOND) && rc.getItem(size - 1).getCount() == 3, id + ": contents lost on reload");
                    f.check(net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromBlockEntity(be) > 0, id + ": no comparator signal");
                    CT.breakBlock(p, parts.getFirst());
                    List<ItemEntity> drops = CT.dropEntities(level, center, 6);
                    int diamonds = 0, pieces = 0;
                    ItemStack piece = ItemStack.EMPTY;
                    for (ItemEntity e : drops) {
                        if (e.getItem().is(Items.DIAMOND)) diamonds += e.getItem().getCount();
                        if (e.getItem().is(fb.asItem())) {
                            pieces += e.getItem().getCount();
                            piece = e.getItem();
                        }
                    }
                    if (st.type() == FurnitureSpec.StorageType.STORAGE) {
                        f.check(diamonds == 3 && pieces == 1, id + ": broke with 3 diamonds inside, dropped " + diamonds + " diamonds, " + pieces + " pieces");
                    } else {
                        ItemContainerContents cc = piece.get(DataComponents.CONTAINER);
                        int inside = 0;
                        if (cc != null) for (ItemStack x : (Iterable<ItemStack>) cc.nonEmptyItemCopyStream()::iterator) if (x.is(Items.DIAMOND)) inside += x.getCount();
                        f.check(diamonds == 0 && pieces == 1 && inside == 3, id + ": sack dropped " + diamonds + " loose diamonds, " + pieces + " sacks holding " + inside);
                        // the filled sack can't go into another sack or a shulker box
                        f.check(!piece.getItem().canFitInsideContainerItems(), id + ": filled sack fits inside container items");
                        // placing it back restores the contents
                        CT.collectDrops(level, center, 6);
                        CT.clear(level, center, 5);
                        level.setBlock(center.below(), Blocks.STONE.defaultBlockState(), 2 | 16);
                        p.setItemInHand(InteractionHand.MAIN_HAND, piece.copy());
                        CT.click(p, center.below(), Direction.UP);
                        f.check(level.getBlockEntity(center) instanceof Container pc && pc.getItem(size - 1).is(Items.DIAMOND), id + ": placed sack lost its contents");
                        // and a sack inside a sack is refused by the slot
                        CT.click(p, center, Direction.UP);
                        if (p.containerMenu instanceof ChestMenu m2) {
                            f.check(!m2.getSlot(0).mayPlace(new ItemStack(fb.asItem())), id + ": a sack slot takes another sack");
                            f.check(!m2.getSlot(0).mayPlace(new ItemStack(Items.SHULKER_BOX)), id + ": a sack slot takes a shulker box");
                            f.check(m2.getSlot(0).mayPlace(new ItemStack(Items.DIRT)), id + ": a sack slot refuses dirt");
                        } else f.add(id + ": placed sack did not open");
                        p.closeContainer();
                        p.getInventory().clearContent();
                        CT.breakBlock(p, center);
                    }
                }
                case PERSONAL -> {
                    menu.getSlot(0).set(new ItemStack(Items.EMERALD, 5));
                    p.closeContainer();
                    CT.click(p, center, Direction.UP);
                    f.check(p.containerMenu instanceof ChestMenu m2 && m2.getContainer().getItem(0).is(Items.EMERALD), id + ": personal storage forgot its items");
                    menu = (ChestMenu) p.containerMenu;
                    menu.getSlot(0).set(ItemStack.EMPTY);
                    p.closeContainer();
                    CT.breakBlock(p, center);
                    Map<String, Integer> d = CT.collectDrops(level, center, 6);
                    f.check(d.equals(Map.of("aftrhrs:" + id, 1)), id + ": dropped " + d);
                }
                case DISPOSAL -> {
                    menu.getSlot(0).set(new ItemStack(Items.DIRT, 5));
                    p.closeContainer();
                    CT.click(p, center, Direction.UP);
                    f.check(p.containerMenu instanceof ChestMenu m2 && m2.getContainer().isEmpty(), id + ": trash kept items");
                    p.closeContainer();
                    f.check(p.getInventory().countItem(Items.DIRT) == 0, id + ": trash gave items back");
                    CT.breakBlock(p, center);
                    CT.collectDrops(level, center, 6);
                }
                case ENDERCHEST -> {
                    f.check(c == p.getEnderChestInventory(), id + ": not the ender chest");
                    p.closeContainer();
                    CT.breakBlock(p, center);
                    CT.collectDrops(level, center, 6);
                }
            }
            p.closeContainer();
            bot.drain();
        }, () -> {
            f.check(all.size() > 80, "only " + all.size() + " storage pieces");
            CT.clear(level, center, 5);
            bot.leave();
            f.finish(h);
        });
    }

    // ------------------------------------------------------------------------------------------------ gramophone
    @GameTest(maxTicks = 400, padding = 16)
    public void gramophonePlaysAndEjects(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btGramo", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("gramophone");
        List<String> all = ids(s -> s.jukebox);
        f.check(!all.isEmpty(), "no gramophone");
        for (String id : all) {
            reset(p, center);
            BlockState s = put(level, center, id);
            if (s == null) {
                f.add(id + ": could not place");
                continue;
            }
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MUSIC_DISC_CAT));
            var res = CT.click(p, center, Direction.UP);
            f.check(p.getMainHandItem().isEmpty(), id + ": disc not taken (" + res + ", be " + level.getBlockEntity(center) + ", state " + level.getBlockState(center) + ")");
            f.check(level.getBlockState(center).getValue(FurnitureBlock.HAS_RECORD), id + ": has_record not set");
            f.check(level.getBlockEntity(center) instanceof GramophoneBlockEntity g && g.getTheItem().is(Items.MUSIC_DISC_CAT), id + ": disc not inside");
            // another disc is refused while one plays
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MUSIC_DISC_13));
            CT.click(p, center, Direction.UP);
            f.check(p.getMainHandItem().is(Items.MUSIC_DISC_13), id + ": second disc swallowed");
            p.getInventory().clearContent();
            CT.click(p, center, Direction.UP);
            f.check(!level.getBlockState(center).getValue(FurnitureBlock.HAS_RECORD), id + ": has_record after eject");
            Map<String, Integer> d = CT.collectDrops(level, center, 6);
            f.check(d.equals(Map.of("minecraft:music_disc_cat", 1)), id + ": eject gave " + d);
            // breaking with a disc inside drops both, once
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MUSIC_DISC_CAT));
            CT.click(p, center, Direction.UP);
            var tag = level.getBlockEntity(center).saveWithFullMetadata(level.registryAccess());
            BlockEntity re = BlockEntity.loadStatic(center, level.getBlockState(center), tag, level.registryAccess());
            f.check(re instanceof GramophoneBlockEntity g2 && g2.getTheItem().is(Items.MUSIC_DISC_CAT), id + ": disc lost on reload");
            CT.breakBlock(p, center);
            d = CT.collectDrops(level, center, 6);
            f.check(d.equals(Map.of("minecraft:music_disc_cat", 1, "aftrhrs:" + id, 1)), id + ": broke with a disc, dropped " + d);
        }
        CT.clear(level, center, 5);
        bot.leave();
        f.finish(h);
    }

    // ------------------------------------------------------------------------------------------------ planters
    @GameTest(maxTicks = 400, padding = 16)
    public void plantersGrowAndDropPerStage(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btPlant", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("planters");
        // planters grow by themselves; staged pieces a module drives (the advent wreath) have no growth chance
        List<String> all = ids(s -> s.stages != null && s.stages.count() > 1 && s.stages.chance() > 0);
        f.check(all.size() == 4, "planters: " + all.size());
        for (String id : all) {
            reset(p, center);
            BlockState s = put(level, center, id);
            if (s == null) {
                f.add(id + ": could not place");
                continue;
            }
            FurnitureBlock fb = (FurnitureBlock) s.getBlock();
            int max = fb.spec.stages.count() - 1;
            f.check(s.getValue(fb.age) == fb.spec.stages.initial(), id + ": initial age " + s.getValue(fb.age));
            // random ticks eventually grow it to the last stage, never further
            for (int i = 0; i < 4000 && level.getBlockState(center).getValue(fb.age) < max; i++) {
                level.getBlockState(center).randomTick(level, center, level.getRandom());
            }
            f.check(level.getBlockState(center).getValue(fb.age) == max, id + ": did not grow to " + max + " (age " + level.getBlockState(center).getValue(fb.age) + ")");
            for (int a = 0; a <= max; a++) {
                level.setBlock(center, level.getBlockState(center).setValue(fb.age, a), 2);
                CT.breakBlock(p, center);
                Map<String, Integer> d = CT.collectDrops(level, center, 6);
                f.check(d.equals(Map.of("aftrhrs:" + id, 1)), id + ": age " + a + " dropped " + d);
                put(level, center, id);
            }
            // bone meal works on a young planter
            level.setBlock(center, level.getBlockState(center).setValue(fb.age, 0), 2);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE_MEAL, 64));
            for (int i = 0; i < 64 && level.getBlockState(center).getValue(fb.age) == 0; i++) CT.click(p, center, Direction.UP);
            f.check(fb.spec.stages.bonemeal() <= 0 || level.getBlockState(center).getValue(fb.age) > 0, id + ": bone meal never grew it");
        }
        CT.clear(level, center, 5);
        bot.leave();
        f.finish(h);
    }

    static String key(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    // ------------------------------------------------------------------------------------------------ internals

    /** A field of a module object (package-private module state), searched up the class chain. */
    static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // up the chain
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Calls a (package-private or private) method by name and argument count. */
    static Object call(Object o, String name, Object... args) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                m.setAccessible(true);
                return m.invoke(o, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * Furniture aging runs off an index of agable origins. A placed copper lamp is indexed, ages two stages in two
     * rounds at chance 1 (its entry survives each swap) and stays indexed; a lamp put in without its placed hook
     * (/setblock, placeState) is indexed once its chunk loads again.
     */
    @GameTest(maxTicks = 100, padding = 16, environment = ToolTests.ENV)
    public void copperAgingFollowsItsIndex(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btAging", true);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("aging_index");
        ToolsModule tools = Feather.get().module(ToolsModule.class);
        if (tools == null) {
            f.add("the tools module is off");
            bot.leave();
            f.finish(h);
            return;
        }
        Object hooks = field(tools, "furniture");
        BlockPos at = GadgetTests.floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        GadgetTests.place(p, at, "arcana_copper_street_lamp");
        f.check("arcana_copper_street_lamp".equals(ArcanaContent.id(level.getBlockState(at))), "lamp not placed: " + CT.id(level.getBlockState(at)));
        f.check((boolean) call(hooks, "agingIndexed", level, at), "a placed lamp is not in the aging index");
        double chance = (double) field(hooks, "agingChance");
        try {
            call(hooks, "agingChance", 1.0);
            call(hooks, "ageTick");
            f.check("arcana_exposed_copper_street_lamp".equals(ArcanaContent.id(level.getBlockState(at))), "one round at chance 1: " + CT.id(level.getBlockState(at)));
            call(hooks, "ageTick");
        } finally {
            call(hooks, "agingChance", chance);
        }
        f.check("arcana_weathered_copper_street_lamp".equals(ArcanaContent.id(level.getBlockState(at))), "two rounds at chance 1: " + CT.id(level.getBlockState(at)));
        f.check((boolean) call(hooks, "agingIndexed", level, at), "the weathered lamp left the aging index");
        // a lamp put in without the placed hook: not indexed until its chunk loads again
        BlockPos other = at.east(2);
        f.check(ArcanaContent.place(level, other, "arcana_copper_street_lamp", Direction.SOUTH), "setblock lamp not placed");
        f.check(!(boolean) call(hooks, "agingIndexed", level, other), "a setblock lamp was indexed without a chunk load");
        call(hooks, "chunkLoaded", level, level.getChunkAt(other));
        f.check((boolean) call(hooks, "agingIndexed", level, other), "a setblock lamp is not indexed after its chunk loaded");
        CT.clear(level, at, 5);
        bot.leave();
        f.finish(h);
    }

    /**
     * /feather reload rescans the loaded chunks for copper aging and fountains only when their own config changed:
     * the same tools.yml / civic.yml sections leave the indexes alone, a changed one rescans (and the original is put
     * back the same way).
     */
    @GameTest
    public void reloadRescansOnlyChangedSections(GameTestHelper h) throws Exception {
        CT.Fails f = new CT.Fails("reload_sections");
        ToolsModule tools = Feather.get().module(ToolsModule.class);
        net.axther.aftrhrs.feature.civic.CivicModule civic = Feather.get().module(net.axther.aftrhrs.feature.civic.CivicModule.class);
        if (tools == null || civic == null) {
            f.add("tools or civic module is off");
            f.finish(h);
            return;
        }
        // copper aging (tools.yml furniture-aging)
        Object hooks = field(tools, "furniture");
        net.axther.aftrhrs.core.Yaml cfg = (net.axther.aftrhrs.core.Yaml) field(tools, "config");
        int n0 = (int) field(hooks, "agingRescans");
        call(hooks, "load", cfg);
        f.check((int) field(hooks, "agingRescans") == n0, "an unchanged tools.yml rescanned for aging");
        net.axther.aftrhrs.core.Yaml off = net.axther.aftrhrs.core.Yaml.fromString(cfg.saveToString());
        off.setDefaults(cfg.getDefaults());
        off.set("furniture-aging.enabled", false);
        try {
            call(hooks, "load", off);
            f.check((int) field(hooks, "agingRescans") == n0 + 1, "turning aging off didn't rescan");
            call(hooks, "load", off);
            f.check((int) field(hooks, "agingRescans") == n0 + 1, "the same changed tools.yml rescanned twice");
        } finally {
            call(hooks, "load", cfg);
        }
        f.check((int) field(hooks, "agingRescans") == n0 + 2, "putting the original tools.yml back didn't rescan");
        // a non-index setting (the round interval) changes nothing that needs a rescan
        net.axther.aftrhrs.core.Yaml slower = net.axther.aftrhrs.core.Yaml.fromString(cfg.saveToString());
        slower.setDefaults(cfg.getDefaults());
        slower.set("furniture-aging.check-interval-seconds", 90);
        try {
            call(hooks, "load", slower);
            f.check((int) field(hooks, "agingRescans") == n0 + 2, "a new aging interval rescanned");
        } finally {
            call(hooks, "load", cfg);
        }
        // fountains (civic.yml fountains globs)
        Object fountains = field(civic, "fountains");
        net.axther.aftrhrs.core.Yaml fc = (net.axther.aftrhrs.core.Yaml) call(civic, "section", "fountains");
        net.axther.aftrhrs.core.Yaml toss = (net.axther.aftrhrs.core.Yaml) call(civic, "section", "coin-toss");
        call(fountains, "load", fc, toss);   // the baseline key is whatever was loaded last: settle on this one
        int r0 = (int) field(fountains, "reindexes");
        call(fountains, "load", fc, toss);
        f.check((int) field(fountains, "reindexes") == r0, "an unchanged fountains section reindexed");
        net.axther.aftrhrs.core.Yaml more = net.axther.aftrhrs.core.Yaml.fromString(fc.saveToString());
        List<String> drinking = new ArrayList<>(fc.getStringList("drinking"));
        drinking.add("arcana_no_such_fountain_piece");
        more.set("drinking", drinking);
        try {
            call(fountains, "load", more, toss);
            f.check((int) field(fountains, "reindexes") == r0 + 1, "new fountain globs didn't reindex");
        } finally {
            call(fountains, "load", fc, toss);
        }
        f.check((int) field(fountains, "reindexes") == r0 + 2, "putting the fountain globs back didn't reindex");
        f.finish(h);
    }

    /**
     * String lights save off the server thread: a change only queues the write (with the data thread held up, the
     * file is untouched until it drains), while the shutdown save is on disk when it returns.
     */
    @GameTest
    public void stringLightsSaveOnTheDataThread(GameTestHelper h) throws Exception {
        CT.Fails f = new CT.Fails("string_lights_async");
        net.axther.aftrhrs.feature.civic.CivicModule civic = Feather.get().module(net.axther.aftrhrs.feature.civic.CivicModule.class);
        if (civic == null) {
            f.add("the civic module is off");
            f.finish(h);
            return;
        }
        Object lights = field(civic, "lights");
        if ((boolean) field(lights, "unreadable")) {
            f.add("civic-strings.yml is unreadable on the test server");
            f.finish(h);
            return;
        }
        java.nio.file.Path file = (java.nio.file.Path) field(lights, "file");
        java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L);
        if (java.nio.file.Files.exists(file)) java.nio.file.Files.setLastModifiedTime(file, old);
        // hold the data thread: a write whose snapshot can't be read until the latch opens
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.Map<String, Object> blocker = new java.util.LinkedHashMap<>() {
            @Override
            public java.util.Set<java.util.Map.Entry<String, Object>> entrySet() {
                try {
                    gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.entrySet();
            }
        };
        blocker.put("held", 1);
        java.nio.file.Path scratch = java.nio.file.Files.createTempDirectory("hold").resolve("hold.yml");
        try {
            net.axther.aftrhrs.core.SafeYaml.writeAsync(scratch, null, blocker, net.axther.aftrhrs.AftrHrs.LOG);
            java.lang.reflect.Field dirty = lights.getClass().getDeclaredField("dirty");
            dirty.setAccessible(true);
            dirty.setBoolean(lights, true);
            call(lights, "saveIfDirtyAsync");
            f.check(!dirty.getBoolean(lights), "the async save didn't take the changes");
            f.check(!java.nio.file.Files.exists(file) || java.nio.file.Files.getLastModifiedTime(file).equals(old),
                    "the change save wrote on the server thread");
        } finally {
            gate.countDown();
        }
        net.axther.aftrhrs.core.SafeYaml.drain(10);
        f.check(java.nio.file.Files.exists(file) && !java.nio.file.Files.getLastModifiedTime(file).equals(old), "the queued save never reached the file");
        // the shutdown save: written when it returns
        java.nio.file.Files.setLastModifiedTime(file, old);
        java.lang.reflect.Field dirty = lights.getClass().getDeclaredField("dirty");
        dirty.setAccessible(true);
        dirty.setBoolean(lights, true);
        f.check((boolean) call(lights, "saveNow"), "the shutdown save failed");
        f.check(!java.nio.file.Files.getLastModifiedTime(file).equals(old), "the shutdown save wasn't on disk when it returned");
        f.finish(h);
    }

    /**
     * A tank put in with /setblock is tracked once clicked (the click attaches it and indexes it) and dropped when its
     * chunk unloads: tracked pieces are always in the index the unload clears.
     */
    @GameTest(maxTicks = 100, padding = 12)
    public void setblockTankUntrackedOnChunkUnload(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btTankIdx", true);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("tank_index");
        FishingModule fishing = Feather.get().module(FishingModule.class);
        if (fishing == null) {
            f.add("the fishing module is off");
            bot.leave();
            f.finish(h);
            return;
        }
        Object ff = field(fishing, "furniture");
        BlockPos at = GadgetTests.floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        f.check(ArcanaContent.place(level, at, "arcana_aquarium_tank", Direction.SOUTH), "setblock tank not placed");
        f.check(!(boolean) call(ff, "isTracked", level, at), "a setblock tank was tracked before anything touched it");
        p.getInventory().clearContent();
        CT.pose(p, Vec3.atBottomCenterOf(at.south(2)), 180, 30);
        CT.click(p, at, Direction.UP);
        f.check((boolean) call(ff, "isTracked", level, at), "clicking a setblock tank did not attach it");
        var chunk = level.getChunkAt(at);
        call(ff, "chunkUnloaded", level, chunk);
        f.check(!(boolean) call(ff, "isTracked", level, at), "the tank is still tracked after its chunk unloaded");
        // put the chunk's pieces back (the chunk never really went)
        call(ff, "chunkLoaded", level, chunk);
        h.runAfterDelay(4, () -> {
            try {
                f.check((boolean) call(ff, "isTracked", level, at), "the tank was not attached again when its chunk loaded");
            } catch (ReflectiveOperationException e) {
                f.add("reflection: " + e);
            }
            CT.clear(level, at, 5);
            bot.leave();
            f.finish(h);
        });
    }
}
