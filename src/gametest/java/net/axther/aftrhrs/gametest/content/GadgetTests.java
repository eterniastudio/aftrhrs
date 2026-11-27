package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.axther.aftrhrs.feature.furniture.curios.CurioBlockEntity;
import net.axther.aftrhrs.feature.furniture.gadgets.GadgetsModule;
import net.axther.aftrhrs.feature.furniture.power.PowerBlockEntity;
import net.axther.aftrhrs.feature.furniture.power.PowerModule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gadgets (jars, cages + HMAC, ropes), curios (pedestals, doormats) and power (generator, fan) through real clicks. */
public class GadgetTests {
    static BlockPos floor(ServerLevel level, BlockPos center) {
        CT.clear(level, center, 5);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) level.setBlock(center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2 | 16);
        return center;
    }

    /** Places a piece the way a player does (click the floor below). */
    static void place(ServerPlayer p, BlockPos at, String id) {
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack(id, 1));
        CT.pose(p, Vec3.atBottomCenterOf(at.south(3)), 180, 30);
        CT.click(p, at.below(), Direction.UP);
    }

    static CompoundTag data(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? new CompoundTag() : d.copyTag();
    }

    /** A jar takes water from a bucket, breaks into exactly one filled jar, keeps it when placed again, gives it back. */
    @GameTest(maxTicks = 100, padding = 12)
    public void jarHoldsWaterThroughBreakAndPlace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btJar", true);
        ServerPlayer p = bot.p;
        BlockPos at = floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        CT.Fails f = new CT.Fails("jar");
        place(p, at, "arcana_jar");
        f.check(ArcanaContent.isFurniture(level.getBlockState(at)), "jar not placed");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        CT.click(p, at, Direction.UP);
        f.check(p.getMainHandItem().is(Items.BUCKET), "bucket not emptied into the jar: " + p.getMainHandItem());
        p.getInventory().clearContent();
        CT.breakBlock(p, at);
        List<ItemEntity> drops = CT.dropEntities(level, at, 5);
        f.check(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "breaking a filled jar dropped " + drops.size() + " stacks: " + drops);
        ItemStack jar = drops.isEmpty() ? ItemStack.EMPTY : drops.getFirst().getItem().copy();
        f.check("fluid".equals(data(jar).getStringOr("feather:gadget_kind", "")) && data(jar).getIntOr("feather:gadget_units", 0) == 4,
                "dropped jar lost its water: " + data(jar));
        CT.collectDrops(level, at, 5);
        // put it back: the water is still inside, and comes out into an empty bucket
        p.setItemInHand(InteractionHand.MAIN_HAND, jar);
        CT.click(p, at.below(), Direction.UP);
        f.check(p.getMainHandItem().isEmpty(), "filled jar item not used up on placing");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
        CT.click(p, at, Direction.UP);
        f.check(p.getMainHandItem().is(Items.WATER_BUCKET), "placed jar gave back " + p.getMainHandItem());
        // empty again: breaks into a plain jar that stacks with new ones
        p.getInventory().clearContent();
        CT.breakBlock(p, at);
        Map<String, Integer> d = CT.collectDrops(level, at, 5);
        f.check(d.equals(Map.of("aftrhrs:arcana_jar", 1)), "empty jar dropped " + d);
        CT.clear(level, at, 5);
        bot.leave();
        f.finish(h);
    }

    /** A cage catches a chicken (signed), lets it out on a block, refuses tampered data, zombies and players. */
    @GameTest(maxTicks = 100, padding = 12)
    public void cageCatchesSignedAndReleases(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btCage", true);
        CT.Bot other = CT.join(h, "btCage2", true);
        ServerPlayer p = bot.p;
        BlockPos at = floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        CT.Fails f = new CT.Fails("cage");
        Chicken chicken = EntityTypes.CHICKEN.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        chicken.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(chicken);
        CT.pose(p, Vec3.atBottomCenterOf(at.south(2)), 180, 20);
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("arcana_cage", 1));
        p.connection.handleInteract(new ServerboundInteractPacket(chicken.getId(), InteractionHand.MAIN_HAND, chicken.position().subtract(chicken.position()), false));
        ItemStack cage = p.getMainHandItem().copy();
        f.check(chicken.isRemoved(), "chicken not caught");
        f.check("mob".equals(data(cage).getStringOr("feather:gadget_kind", "")) && data(cage).contains("feather:gadget_mob_sig"), "cage holds no signed mob: " + data(cage));
        // tampered bytes: never let out
        ItemStack forged = cage.copy();
        CompoundTag t = data(forged);
        byte[] mob = t.getByteArray("feather:gadget_mob").orElse(new byte[0]).clone();
        if (mob.length > 20) mob[mob.length / 2] ^= 0x5a;
        t.putByteArray("feather:gadget_mob", mob);
        forged.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        p.setItemInHand(InteractionHand.MAIN_HAND, forged);
        int before = level.getEntitiesOfClass(Chicken.class, new net.minecraft.world.phys.AABB(at).inflate(6)).size();
        CT.click(p, at.below(), Direction.UP);
        int after = level.getEntitiesOfClass(Chicken.class, new net.minecraft.world.phys.AABB(at).inflate(6)).size();
        f.check(after == before, "a tampered cage let a mob out");
        // the real one: out it comes, the cage is empty
        p.setItemInHand(InteractionHand.MAIN_HAND, cage.copy());
        CT.click(p, at.below(), Direction.UP);
        after = level.getEntitiesOfClass(Chicken.class, new net.minecraft.world.phys.AABB(at).inflate(6)).size();
        f.check(after == before + 1, "signed cage did not release the chicken");
        f.check(!data(p.getMainHandItem()).contains("feather:gadget_kind"), "cage still full after release");
        // too big / players are refused
        Zombie z = EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        z.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 1.5, 0, 0);
        z.setNoAi(true);
        level.addFreshEntity(z);
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("arcana_cage", 1));
        p.connection.handleInteract(new ServerboundInteractPacket(z.getId(), InteractionHand.MAIN_HAND, z.position().subtract(z.position()), false));
        f.check(!z.isRemoved() && !data(p.getMainHandItem()).contains("feather:gadget_kind"), "a zombie went into a cage");
        CT.pose(other.p, Vec3.atBottomCenterOf(at.south(1)), 0, 0);
        p.connection.handleInteract(new ServerboundInteractPacket(other.p.getId(), InteractionHand.MAIN_HAND, other.p.position().subtract(other.p.position()), false));
        f.check(!other.p.isRemoved() && !other.p.hasDisconnected() && !data(p.getMainHandItem()).contains("feather:gadget_kind"), "a player went into a cage");
        z.discard();
        CT.clear(level, at, 5);
        bot.leave();
        other.leave();
        f.finish(h);
    }

    /** A pedestal shows an item; breaking drops the pedestal and the item exactly once. */
    @GameTest(maxTicks = 100, padding = 12)
    public void pedestalKeepsAndDropsItsItem(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btPed", true);
        ServerPlayer p = bot.p;
        BlockPos at = floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        CT.Fails f = new CT.Fails("pedestal");
        place(p, at, "arcana_stone_pedestal");
        f.check(level.getBlockEntity(at) instanceof CurioBlockEntity, "no curio block entity on the pedestal");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 5));
        CT.click(p, at, Direction.UP);
        f.check(level.getBlockEntity(at) instanceof CurioBlockEntity c && c.item().is(Items.DIAMOND) && c.item().getCount() == 1, "pedestal did not take one diamond");
        f.check(p.getMainHandItem().getCount() == 4, "diamonds left in hand: " + p.getMainHandItem().getCount());
        var tag = level.getBlockEntity(at).saveWithFullMetadata(level.registryAccess());
        var re = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(at, level.getBlockState(at), tag, level.registryAccess());
        f.check(re instanceof CurioBlockEntity c2 && c2.item().is(Items.DIAMOND), "pedestal item lost on reload");
        // sneak + item (vanilla skips the block for this): the hook still gets it
        p.getInventory().clearContent();
        CT.breakBlock(p, at);
        Map<String, Integer> d = CT.collectDrops(level, at, 5);
        f.check(d.getOrDefault("minecraft:diamond", 0) == 1 && d.keySet().stream().filter(k -> k.contains("pedestal")).mapToInt(d::get).sum() == 1,
                "broken pedestal dropped " + d);
        CT.clear(level, at, 5);
        bot.leave();
        f.finish(h);
    }

    /** End crystals on pedestals two blocks from an enchanting table count as 3 bookshelves each (capped at 15). */
    @GameTest(maxTicks = 100, padding = 12)
    public void pedestalCrystalsPowerEnchanting(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btEnch", true);
        ServerPlayer p = bot.p;
        BlockPos table = floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        level.setBlock(table, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("enchanting_power");
        int base = net.axther.aftrhrs.core.event.FeatherEvents.ENCHANTING_POWER.invoker().power(level, table, 0);
        f.check(base == 0, "no pedestals: power " + base);
        BlockPos[] spots = {table.offset(2, 0, 0), table.offset(-2, 0, 0), table.offset(0, 0, 2), table.offset(0, 0, -2), table.offset(2, 0, 1), table.offset(-2, 0, -1)};
        int n = 0;
        for (BlockPos s : spots) {
            place(p, s, "arcana_stone_pedestal");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.END_CRYSTAL));
            CT.click(p, s, Direction.UP);
            n++;
            int power = net.axther.aftrhrs.core.event.FeatherEvents.ENCHANTING_POWER.invoker().power(level, table, 0);
            f.check(power == Math.min(15, 3 * n), n + " crystals: power " + power + " expected " + Math.min(15, 3 * n));
        }
        // a block between the table and a pedestal cuts it off, like a bookshelf
        level.setBlock(table.offset(1, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(table.offset(1, 1, 0), Blocks.STONE.defaultBlockState(), 3);
        int power = net.axther.aftrhrs.core.event.FeatherEvents.ENCHANTING_POWER.invoker().power(level, table, 0);
        // (1,0,0) is the transmitter cell of both (2,0,0) and (2,0,1) (vanilla's offset / 2): 4 crystals are left
        f.check(power == 12, "6 crystals, 2 cut off: power " + power + " expected 12");
        CT.clear(level, table, 5);
        bot.leave();
        f.finish(h);
    }

    /** Sneak + item on a doormat hides the item under it (vanilla never offered sneak + item clicks to the block). */
    @GameTest(maxTicks = 100, padding = 12)
    public void doormatHidesItemOnSneakClick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btMat", true);
        ServerPlayer p = bot.p;
        BlockPos at = floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        CT.Fails f = new CT.Fails("doormat");
        String mat = null;
        for (String id : ArcanaContent.entries().keySet()) if (id.endsWith("doormat") && ArcanaContent.block(id) != null) { mat = id; break; }
        f.check(mat != null, "no doormat");
        if (mat != null) {
            place(p, at, mat);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
            p.setShiftKeyDown(true);
            CT.click(p, at, Direction.UP);
            p.setShiftKeyDown(false);
            f.check(level.getBlockEntity(at) instanceof CurioBlockEntity c && c.hidden().is(Items.DIAMOND), "sneak + diamond did not hide it under the mat");
            f.check(p.getMainHandItem().isEmpty(), "diamond still in hand");
            f.check(!(level.getBlockState(at.above()).getBlock() instanceof FurnitureBlock), "placed something on the mat instead");
            p.getInventory().clearContent();
            CT.breakBlock(p, at);
            Map<String, Integer> d = CT.collectDrops(level, at, 5);
            f.check(d.getOrDefault("minecraft:diamond", 0) == 1 && d.getOrDefault("aftrhrs:" + mat, 0) == 1, "broken mat dropped " + d);
        }
        CT.clear(level, at, 5);
        bot.leave();
        f.finish(h);
    }

    /** Ropes hang under a ceiling, a second rope lengthens the column, breaking the top drops the whole column once. */
    @GameTest(maxTicks = 100, padding = 12)
    public void ropesHangExtendAndCascade(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btRope", true);
        ServerPlayer p = bot.p;
        BlockPos ceil = h.absolutePos(new BlockPos(4, 7, 4));
        CT.clear(level, ceil.below(3), 5);
        level.setBlock(ceil, Blocks.STONE.defaultBlockState(), 3);
        CT.Fails f = new CT.Fails("ropes");
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("arcana_rope", 3));
        CT.pose(p, Vec3.atBottomCenterOf(ceil.below(4).south(2)), 180, -40);
        CT.click(p, ceil, Direction.DOWN);
        f.check("arcana_rope_knot".equals(ArcanaContent.id(level.getBlockState(ceil.below()))), "no knot under the ceiling: " + CT.id(level.getBlockState(ceil.below())));
        CT.click(p, ceil.below(), Direction.NORTH);
        CT.click(p, ceil.below(), Direction.NORTH);
        int len = 0;
        while (len < 5 && ArcanaContent.id(level.getBlockState(ceil.below(len + 1))) != null) len++;
        f.check(len == 3, "column is " + len + " long after 3 ropes");
        f.check(p.getMainHandItem().isEmpty(), "ropes left in hand: " + p.getMainHandItem());
        CT.breakBlock(p, ceil.below());
        f.check(ArcanaContent.id(level.getBlockState(ceil.below(2))) == null && ArcanaContent.id(level.getBlockState(ceil.below(3))) == null, "rope below survived");
        Map<String, Integer> d = CT.collectDrops(level, ceil.below(2), 6);
        f.check(d.equals(Map.of("aftrhrs:arcana_rope", 3)), "broken column dropped " + d);
        // a rope in the off hand never lands as a bare block (vanilla would place it from the off hand)
        CT.clear(level, ceil.below(3), 5);
        level.setBlock(ceil.below(5), Blocks.STONE.defaultBlockState(), 3);
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.OFF_HAND, ArcanaContent.stack("arcana_rope", 2));
        CT.pose(p, Vec3.atBottomCenterOf(ceil.below(4).south(2)), 180, 30);
        p.gameMode.useItemOn(p, level, p.getOffhandItem(), InteractionHand.OFF_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(ceil.below(5)).add(0, 0.5, 0), Direction.UP, ceil.below(5), false));
        f.check(ArcanaContent.id(level.getBlockState(ceil.below(4))) == null, "off-hand rope placed a bare rope block on the floor");
        f.check(p.getOffhandItem().getCount() == 2, "off-hand rope used up: " + p.getOffhandItem());
        CT.clear(level, ceil.below(3), 5);
        bot.leave();
        f.finish(h);
    }

    /** Pulling a rope up lifts a plain block hanging under it, but never half of a door, tall plant or bed. */
    @GameTest
    public void ropesNeverLiftHalfABlock(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        java.lang.reflect.Method liftable = Class.forName("net.axther.aftrhrs.feature.furniture.gadgets.Ropes")
                .getDeclaredMethod("liftable", ServerLevel.class, BlockPos.class);
        liftable.setAccessible(true);
        BlockPos at = h.absolutePos(new BlockPos(2, 3, 2));
        CT.Fails f = new CT.Fails("rope lift");
        net.minecraft.world.level.block.state.BlockState[] halves = {
                Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),
                Blocks.TALL_GRASS.defaultBlockState(),
                Blocks.STRAW_BED.defaultBlockState(),
        };
        for (var s : halves) {
            level.setBlock(at, s, 2 | 16);   // no shape updates: the lone half stays put for the check
            f.check(!(boolean) liftable.invoke(null, level, at), "a rope would lift " + s);
        }
        level.setBlock(at, Blocks.STONE.defaultBlockState(), 2 | 16);
        f.check((boolean) liftable.invoke(null, level, at), "a rope won't lift plain stone");
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
        f.finish(h);
    }

    /**
     * A fan blowing through lava blasts raw iron into iron ingots on an open lane (no wall to hold the items): an item
     * still being processed waits in the stream's last block, then the ingots are blown on.
     */
    @GameTest(maxTicks = 400, padding = 16)
    public void fanBlastsRawIronIntoIngots(GameTestHelper h) {
        fanBlasts(h, "btBlast", Items.RAW_IRON, Items.IRON_INGOT, 8, 320, 3);
    }

    /** A whole dropped stack of 64 raw iron comes out as 64 iron ingots (4 x 150 ticks for 64 items, then blown on). */
    @GameTest(maxTicks = 900, padding = 16)
    public void fanBlastsWholeStack(GameTestHelper h) {
        fanBlasts(h, "btBlast64", Items.RAW_IRON, Items.IRON_INGOT, 64, 800, 3);
    }

    /**
     * A stack dropped straight into the lava in front of the fan doesn't burn up: items in a fan's stream can't burn,
     * so it is blown on and blasted like any other (and blasting cooks food instead of burning it).
     */
    @GameTest(maxTicks = 600, padding = 16)
    public void fanBlastingNeverBurnsItems(GameTestHelper h) {
        fanBlasts(h, "btBlastLava", Items.BEEF, Items.COOKED_BEEF, 32, 480, 1);
    }

    private void fanBlasts(GameTestHelper h, String botName, net.minecraft.world.item.Item input, net.minecraft.world.item.Item output,
                           int count, int wait, int startAt) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, botName, true);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("fan blasting");
        BlockPos gen = floor(level, h.absolutePos(new BlockPos(1, 2, 4)));
        BlockPos fan = h.absolutePos(new BlockPos(3, 2, 4));
        Direction east = Direction.getApproximateNearest(fan.getX() - gen.getX(), 0, fan.getZ() - gen.getZ());
        // An open lane past the end of the 8-block stream (the structure's barrier wall taken out), all in ticking chunks.
        for (int i = 1; i <= 12; i++) {
            level.setBlock(fan.relative(east, i).below(), Blocks.STONE.defaultBlockState(), 2 | 16);
            for (int dy = 0; dy <= 2; dy++) level.setBlock(fan.relative(east, i).above(dy), Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        java.util.List<long[]> forced = new java.util.ArrayList<>();
        for (int cx = (fan.getX() - 16) >> 4; cx <= (fan.getX() + 16) >> 4; cx++)
            for (int cz = (fan.getZ() - 16) >> 4; cz <= (fan.getZ() + 16) >> 4; cz++)
                if (level.setChunkForced(cx, cz, true)) forced.add(new long[]{cx, cz});
        place(p, gen, "feather_coal_generator");
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("feather_encased_fan", 1));
        CT.pose(p, Vec3.atBottomCenterOf(fan.relative(east.getOpposite())), east.toYRot(), 0);
        CT.click(p, fan.below(), Direction.UP);
        f.check("feather_encased_fan".equals(ArcanaContent.id(level.getBlockState(fan))), "fan not placed");
        p.getInventory().clearContent();
        CT.click(p, gen, Direction.UP);
        for (int i = 0; i < p.containerMenu.slots.size(); i++) {
            var sl = p.containerMenu.getSlot(i);
            if (sl.container != p.getInventory() && sl.mayPlace(new ItemStack(Items.COAL))) {
                sl.set(new ItemStack(Items.COAL, 8));
                break;
            }
        }
        p.closeContainer();
        // lava right in front of the fan; the raw iron goes in past where the lava runs
        level.setBlock(fan.relative(east, 1), Blocks.LAVA.defaultBlockState(), 3);
        Vec3 start = Vec3.atBottomCenterOf(fan.relative(east, startAt)).add(0, 0.05, 0);
        // dropped in once the fan is running (3 s in), as a player would
        h.runAfterDelay(60, () -> {
            ItemEntity item = new ItemEntity(level, start.x, start.y, start.z, new ItemStack(input, count), 0, 0, 0);
            item.setPickUpDelay(wait + 80);
            level.addFreshEntity(item);
        });
        AABB lane = new AABB(fan).inflate(14);
        h.runAfterDelay(wait, () -> {
            int raw = 0, ingots = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, lane)) {
                if (e.getItem().is(input)) raw += e.getItem().getCount();
                if (e.getItem().is(output)) ingots += e.getItem().getCount();
            }
            f.check(raw == 0 && ingots == count, "after " + wait / 20 + " s in the blasting stream: " + raw + " " + input + ", " + ingots
                + " " + output + " (want 0 and " + count + ")");
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, lane)) e.discard();
            for (BlockPos b : BlockPos.betweenClosed(fan.offset(-8, -2, -8), fan.offset(8, 3, 8)))
                if (level.getFluidState(b).is(net.minecraft.tags.FluidTags.LAVA)) level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
            for (long[] c : forced) level.setChunkForced((int) c[0], (int) c[1], false);
            CT.clear(level, gen, 5);
            bot.leave();
            f.finish(h);
        });
    }

    /** A coal generator lights up with coal (both parts) and a fan next to it pushes an item along its stream. */
    @GameTest(maxTicks = 200, padding = 16)
    public void generatorRunsFanPushes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btPower", true);
        ServerPlayer p = bot.p;
        // Laid out across the structure (generator at one side, the stream blowing over it toward the other), whatever
        // way the structure is rotated: pushed off the edge, an item can land in a chunk that isn't ticking.
        BlockPos gen = floor(level, h.absolutePos(new BlockPos(1, 2, 4)));
        BlockPos fan = h.absolutePos(new BlockPos(3, 2, 4));
        Direction east = Direction.getApproximateNearest(fan.getX() - gen.getX(), 0, fan.getZ() - gen.getZ());
        for (int i = 1; i <= 6; i++) level.setBlock(fan.relative(east, i).below(), Blocks.STONE.defaultBlockState(), 2 | 16);
        CT.Fails f = new CT.Fails("power");
        place(p, gen, "feather_coal_generator");
        f.check("feather_coal_generator".equals(ArcanaContent.id(level.getBlockState(gen))), "generator not placed");
        // fan two blocks on, blowing away from the generator (the way the placer looks)
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("feather_encased_fan", 1));
        CT.pose(p, Vec3.atBottomCenterOf(fan.relative(east.getOpposite())), east.toYRot(), 0);
        CT.click(p, fan.below(), Direction.UP);
        f.check("feather_encased_fan".equals(ArcanaContent.id(level.getBlockState(fan))), "fan not placed");
        // fuel: open the generator and put coal in its fuel slot
        p.getInventory().clearContent();
        CT.click(p, gen, Direction.UP);
        if (p.containerMenu != p.inventoryMenu) {
            boolean put = false;
            for (int i = 0; i < p.containerMenu.slots.size() && !put; i++) {
                var s = p.containerMenu.getSlot(i);
                if (s.container != p.getInventory() && s.mayPlace(new ItemStack(Items.COAL))) {
                    s.set(new ItemStack(Items.COAL, 8));
                    put = true;
                }
            }
            f.check(put, "generator window has no fuel slot");
            p.closeContainer();
        } else f.add("generator window did not open");
        // Laid on the floor in front of the fan with no throw (a dropped item's random toss could carry it out of the
        // one-block stream before the fan has power).
        Vec3 start = Vec3.atBottomCenterOf(fan.relative(east)).add(0, 0.05, 0);
        ItemEntity item = new ItemEntity(level, start.x, start.y, start.z, new ItemStack(Items.DIRT), 0, 0, 0);
        item.setPickUpDelay(200);
        level.addFreshEntity(item);
        Vec3 dir = Vec3.atLowerCornerOf(east.getUnitVec3i());
        h.runAfterDelay(60, () -> {
            f.check(level.getBlockState(gen).getValue(FurnitureBlock.LIT), "generator not lit with coal");
            f.check(level.getBlockState(fan).getValue(FurnitureBlock.POWERED), "fan not powered next to a burning generator");
            double moved = item.position().subtract(start).dot(dir);
            f.check(moved > 1.0, "fan did not push the item (moved " + moved + " along the stream)");
            item.discard();
            p.getInventory().clearContent();
            CT.breakBlock(p, gen);
            Map<String, Integer> d = CT.collectDrops(level, gen, 6);
            f.check(d.getOrDefault("aftrhrs:feather_coal_generator", 0) == 1 && d.getOrDefault("minecraft:coal", 0) <= 8, "broken generator dropped " + d);
            CT.clear(level, gen, 5);
            bot.leave();
            f.finish(h);
        });
    }

    /**
     * Spikes still hurt after a gadgets.yml reload hands the spike role a new id set (the tick looks the id up in the
     * current set, after its schedule check).
     */
    @GameTest(maxTicks = 120, padding = 12)
    public void spikesFireAfterTheirIdsReload(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btSpikes", true);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("spikes_reload");
        GadgetsModule g = Feather.get().module(GadgetsModule.class);
        if (g == null) {
            f.add("the gadgets module is off");
            bot.leave();
            f.finish(h);
            return;
        }
        BlockPos at = floor(level, h.absolutePos(new BlockPos(4, 2, 4)));
        place(p, at, "arcana_bamboo_spikes");
        f.check("arcana_bamboo_spikes".equals(ArcanaContent.id(level.getBlockState(at))), "spikes not placed: " + CT.id(level.getBlockState(at)));
        p.getInventory().clearContent();
        CT.pose(p, Vec3.atBottomCenterOf(at.south(4)), 180, 30);
        // a reload that changes spikes.ids (a new set, the spikes still in it)
        Object spikes = FurnitureBehaviorTests.field(g, "spikes");
        Yaml y = new Yaml();
        y.set("ids", List.of("arcana_bamboo_spikes", "arcana_test_only_spikes"));
        FurnitureBehaviorTests.call(spikes, "load", y);
        net.minecraft.world.entity.Mob z = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        z.setNoAi(true);
        z.setPersistenceRequired();
        Vec3 c = Vec3.atBottomCenterOf(at);
        z.snapTo(c.x, c.y, c.z, 0, 0);
        level.addFreshEntity(z);
        h.runAfterDelay(50, () -> {
            f.check(z.hasEffect(MobEffects.SLOWNESS), "a pig on the spikes was not slowed after the ids reloaded");
            z.discard();
            g.reload();   // back to gadgets.yml
            CT.clear(level, at, 5);
            bot.leave();
            f.finish(h);
        });
    }

    /**
     * After the power module is turned off and on again, a burning generator still powers its fan. Both block entities
     * also go through vanilla's remove/re-add (setRemoved then clearRemoved) first, which untracks them and drops their
     * tracked-by mark: they must track themselves again on their next tick, not stay out of the module's machines.
     */
    @GameTest(maxTicks = 200, padding = 16)
    public void generatorFeedsFanAfterPowerToggle(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btPowerT", true);
        ServerPlayer p = bot.p;
        CT.Fails f = new CT.Fails("power_toggle");
        PowerModule power = Feather.get().module(PowerModule.class);
        if (power == null) {
            f.add("the power module is off");
            bot.leave();
            f.finish(h);
            return;
        }
        BlockPos gen = floor(level, h.absolutePos(new BlockPos(1, 2, 4)));
        BlockPos fan = h.absolutePos(new BlockPos(3, 2, 4));
        Direction east = Direction.getApproximateNearest(fan.getX() - gen.getX(), 0, fan.getZ() - gen.getZ());
        place(p, gen, "feather_coal_generator");
        f.check("feather_coal_generator".equals(ArcanaContent.id(level.getBlockState(gen))), "generator not placed");
        p.setItemInHand(InteractionHand.MAIN_HAND, ArcanaContent.stack("feather_encased_fan", 1));
        CT.pose(p, Vec3.atBottomCenterOf(fan.relative(east.getOpposite())), east.toYRot(), 0);
        CT.click(p, fan.below(), Direction.UP);
        f.check("feather_encased_fan".equals(ArcanaContent.id(level.getBlockState(fan))), "fan not placed");
        p.getInventory().clearContent();
        CT.click(p, gen, Direction.UP);
        if (p.containerMenu != p.inventoryMenu) {
            boolean put = false;
            for (int i = 0; i < p.containerMenu.slots.size() && !put; i++) {
                var s = p.containerMenu.getSlot(i);
                if (s.container != p.getInventory() && s.mayPlace(new ItemStack(Items.COAL))) {
                    s.set(new ItemStack(Items.COAL, 8));
                    put = true;
                }
            }
            f.check(put, "generator window has no fuel slot");
            p.closeContainer();
        } else f.add("generator window did not open");
        // let both block entities register with the module, re-add them (untracked), then turn the module off and on
        h.runAfterDelay(5, () -> {
            List<PowerBlockEntity> bes = new ArrayList<>();
            for (BlockPos at : List.of(gen, fan)) {
                if (level.getBlockEntity(at) instanceof PowerBlockEntity be) {
                    be.setRemoved();
                    be.clearRemoved();
                    bes.add(be);
                } else f.add("no power block entity at " + at.toShortString());
            }
            f.check(bes.stream().noneMatch(be -> machinesOf(power).contains(be)), "a re-added block entity is still tracked");
            power.disable();
            power.enable();
            h.runAfterDelay(2, () -> f.check(machinesOf(power).containsAll(bes),
                    "the re-added generator and fan did not track again: " + machinesOf(power).size() + " machines"));
            h.runAfterDelay(60, () -> {
                f.check(level.getBlockState(gen).getValue(FurnitureBlock.LIT), "generator not lit after the power toggle");
                f.check(level.getBlockState(fan).getValue(FurnitureBlock.POWERED), "fan not powered after the power toggle");
                p.getInventory().clearContent();
                CT.clear(level, gen, 5);
                CT.clear(level, fan, 5);
                bot.leave();
                f.finish(h);
            });
        });
    }

    /** The power module's live machines (package-private there). */
    @SuppressWarnings("unchecked")
    private static List<PowerBlockEntity> machinesOf(PowerModule power) {
        try {
            return (List<PowerBlockEntity>) FurnitureBehaviorTests.call(power, "machines");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static volatile ServerPlayer portalBot;
    private static final List<TeleportCause> PORTAL_CAUSES = new ArrayList<>();
    private static boolean portalHooked;

    /** Stands the bot in the portal block at {@code at} and runs its portal handling until it tries to teleport (cancelled). */
    private static TeleportCause portalCause(ServerPlayer p, ServerLevel level, BlockPos at) throws Exception {
        BlockState portal = level.getBlockState(at);
        java.lang.reflect.Method handle = Entity.class.getDeclaredMethod("handlePortal");
        handle.setAccessible(true);
        p.portalProcess = null;
        p.setPortalCooldown(0);
        int before = PORTAL_CAUSES.size();
        for (int i = 0; i < 120 && PORTAL_CAUSES.size() == before; i++) {
            p.setAsInsidePortal((Portal) portal.getBlock(), at);
            handle.invoke(p);
        }
        p.portalProcess = null;
        p.setPortalCooldown(0);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
        return PORTAL_CAUSES.size() == before ? null : PORTAL_CAUSES.get(PORTAL_CAUSES.size() - 1);
    }

    /**
     * Portals tag their teleport: a nether portal, an end portal and an end gateway each reach PLAYER_TELEPORT with
     * their cause (the teleport is cancelled there), and nothing is left set afterwards.
     */
    @GameTest(maxTicks = 100, padding = 12)
    public void portalsTagTheirTeleportCause(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btPortal", true);
        ServerPlayer p = bot.p;
        p.setGameMode(GameType.CREATIVE);
        CT.Fails f = new CT.Fails("portal_causes");
        synchronized (GadgetTests.class) {
            if (!portalHooked) {
                portalHooked = true;
                FeatherEvents.PLAYER_TELEPORT.register((who, from, to, cause) -> {
                    if (who != portalBot) return true;
                    PORTAL_CAUSES.add(cause);
                    return false;
                });
            }
        }
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        CT.clear(level, at, 3);
        CT.pose(p, Vec3.atBottomCenterOf(at), 0, 0);
        portalBot = p;
        try {
            level.setBlock(at, Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X), 2 | 16);
            TeleportCause nether = portalCause(p, level, at);
            f.check(nether == TeleportCause.NETHER_PORTAL, "nether portal teleport cause: " + nether);
            f.check(TeleportCause.current() == TeleportCause.UNKNOWN, "cause left set after the nether portal: " + TeleportCause.current());
            level.setBlock(at, Blocks.END_PORTAL.defaultBlockState(), 2 | 16);
            TeleportCause end = portalCause(p, level, at);
            f.check(end == TeleportCause.END_PORTAL, "end portal teleport cause: " + end);
            f.check(TeleportCause.current() == TeleportCause.UNKNOWN, "cause left set after the end portal: " + TeleportCause.current());
            level.setBlock(at, Blocks.END_GATEWAY.defaultBlockState(), 2 | 16);
            if (level.getBlockEntity(at) instanceof TheEndGatewayBlockEntity gw) gw.setExitPosition(at.above(4), true);
            else f.add("no end gateway block entity");
            TeleportCause gateway = portalCause(p, level, at);
            f.check(gateway == TeleportCause.END_GATEWAY, "end gateway teleport cause: " + gateway);
            f.check(TeleportCause.current() == TeleportCause.UNKNOWN, "cause left set after the end gateway: " + TeleportCause.current());
            f.check(p.level() == level, "the bot left the test level");
        } finally {
            portalBot = null;
        }
        CT.clear(level, at, 3);
        bot.leave();
        f.finish(h);
    }
}
