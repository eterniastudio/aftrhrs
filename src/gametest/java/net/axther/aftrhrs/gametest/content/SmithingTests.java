package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.feature.smithing.HandToolItem;
import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.MoldItem;
import net.axther.aftrhrs.feature.smithing.Mods;
import net.axther.aftrhrs.feature.smithing.PartInfo;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.Soulbound;
import net.axther.aftrhrs.feature.smithing.ToolBuild;
import net.axther.aftrhrs.feature.smithing.ToolStats;
import net.axther.aftrhrs.feature.smithing.station.AnvilBE;
import net.axther.aftrhrs.feature.smithing.station.CastingBE;
import net.axther.aftrhrs.feature.smithing.station.CrucibleBE;
import net.axther.aftrhrs.feature.smithing.station.ForgeBE;
import net.axther.aftrhrs.feature.smithing.station.RuneTableBE;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.axther.aftrhrs.feature.smithing.station.TroughBE;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Anvil & Ember (feature.smithing): the stat math against vanilla, quality and slots, persistence, broken-not-destroyed,
 * wear rules, soulbound, and every station through real clicks (forge, crucible + alloy, casting, anvil, quench,
 * carving, assembly, runesmith).
 */
public class SmithingTests {
    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    static ItemStack tool(GameTestHelper h, String id, List<String> mats, int stars) {
        return Smithing.tool(id, mats, List.of(stars), h.getLevel().registryAccess());
    }

    static BlockPos station(ServerLevel level, BlockPos at, Station s) {
        level.setBlock(at, Smithing.block(s).defaultBlockState(), 3);
        return at;
    }

    static void use(ServerPlayer p, BlockPos pos, ItemStack held) {
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        CT.pose(p, Vec3.atBottomCenterOf(pos.north(2)), 0, 30);
        CT.click(p, pos, Direction.UP);
    }

    /** An iron pickaxe made of iron, leather and wood is about a vanilla iron pickaxe; quality scales it. */
    @GameTest
    public void ironPickaxeMatchesVanilla(GameTestHelper h) {
        ItemStack pick = tool(h, "pickaxe", List.of("iron", "leather", "wood"), 3);
        check(h, !pick.isEmpty(), "no pickaxe made");
        check(h, pick.getMaxDamage() >= 240 && pick.getMaxDamage() <= 320, "iron pickaxe durability " + pick.getMaxDamage());
        float speed = pick.getDestroySpeed(Blocks.STONE.defaultBlockState());
        check(h, speed > 5.5f && speed < 6.6f, "iron pickaxe speed on stone " + speed);
        check(h, pick.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState()), "iron can't mine diamond ore");
        check(h, !pick.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState()), "iron mines obsidian");
        ItemStack poor = tool(h, "pickaxe", List.of("iron", "leather", "wood"), 1), fine = tool(h, "pickaxe", List.of("iron", "leather", "wood"), 5);
        check(h, poor.getMaxDamage() < pick.getMaxDamage() && pick.getMaxDamage() < fine.getMaxDamage(), "stars don't scale durability");
        ToolBuild b = fine.get(Smithing.BUILD);
        check(h, b != null && b.masterwork() && Mods.capacity(b)[Mods.U] == 4, "a masterwork doesn't get its extra upgrade slot");
        ItemStack sword = tool(h, "sword", List.of("iron", "iron", "wood"), 3);
        double dmg = sword.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers().stream()
                .filter(e -> e.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)).mapToDouble(e -> e.modifier().amount()).sum();
        check(h, dmg > 4.5 && dmg < 6.5, "iron sword attack bonus " + dmg + " (vanilla 5)");
        check(h, tool(h, "pickaxe", List.of("silk", "leather", "wood"), 3).isEmpty(), "a silk pick head was allowed");
        h.succeed();
    }

    /** Builds save and load (codec round trip through the item stack), keeping parts, modifiers and progress. */
    @GameTest
    public void buildRoundTrips(GameTestHelper h) {
        ItemStack s = tool(h, "sledge", List.of("steel", "obsidian", "obsidian", "bone"), 4);
        ToolBuild b = s.get(Smithing.BUILD).withMod("keen_edge", 2).withProgress("mod:honed", 21).withSmith("Tester");
        s.set(Smithing.BUILD, b);
        RegistryOps<net.minecraft.nbt.Tag> ops = h.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var tag = ItemStack.CODEC.encodeStart(ops, s).getOrThrow();
        ItemStack back = ItemStack.CODEC.parse(ops, tag).getOrThrow();
        check(h, b.equals(back.get(Smithing.BUILD)), "build changed in a round trip: " + back.get(Smithing.BUILD));
        check(h, back.get(Smithing.BUILD).level("keen_edge") == 2 && back.get(Smithing.BUILD).progress("mod:honed") == 21, "modifiers lost");
        h.succeed();
    }

    /** At 0 durability a smithed tool breaks (stops working) instead of vanishing, and a repair brings it back. */
    @GameTest
    public void toolsBreakButNeverVanish(GameTestHelper h) {
        CT.Bot bot = CT.join(h, "smBrk", false);
        try {
            ServerPlayer p = bot.p;
            ItemStack pick = tool(h, "pickaxe", List.of("iron", "leather", "wood"), 3);
            p.setItemInHand(InteractionHand.MAIN_HAND, pick);
            pick.hurtAndBreak(pick.getMaxDamage() + 50, p, EquipmentSlot.MAINHAND);
            ItemStack held = p.getMainHandItem();
            check(h, !held.isEmpty() && held.has(Smithing.BUILD), "the broken pickaxe vanished");
            check(h, held.has(Smithing.BROKEN), "the pickaxe isn't marked broken");
            check(h, !held.has(DataComponents.TOOL), "a broken pickaxe still mines");
            held.setDamageValue(0);
            Smithing.repaired(held, h.getLevel());
            check(h, !held.has(Smithing.BROKEN) && held.has(DataComponents.TOOL), "repair didn't bring it back");
            // Unyielding (obsidian) never loses more than 1 at a time
            ItemStack obs = tool(h, "pickaxe", List.of("obsidian", "leather", "wood"), 3);
            int before = obs.getDamageValue();
            obs.hurtAndBreak(10, p, EquipmentSlot.MAINHAND);
            check(h, obs.getDamageValue() - before <= 1, "unyielding lost " + (obs.getDamageValue() - before));
        } finally {
            bot.leave();
        }
        h.succeed();
    }

    /** Modifiers take slots, Gentle Touch works through silk touch, Keen Edge adds damage, a full tool refuses more. */
    @GameTest
    public void modifiersUseSlotsAndWork(GameTestHelper h) {
        ItemStack pick = tool(h, "pickaxe", List.of("iron", "leather", "wood"), 3);
        ToolBuild b = pick.get(Smithing.BUILD).withMod("gentle_touch", 1);
        pick.set(Smithing.BUILD, b);
        ToolStats.apply(pick, h.getLevel().registryAccess());
        var ench = pick.get(DataComponents.ENCHANTMENTS);
        check(h, ench != null && !ench.isEmpty(), "gentle touch didn't add silk touch");
        check(h, Mods.refuse(b, SmithingDefs.modifier("smeltforged")) != null, "a second ability fits in one ability slot");
        ToolBuild full = b.withMod("honed", 3);
        check(h, Mods.refuse(full, SmithingDefs.modifier("keen_edge")) != null, "a fourth upgrade fits in three slots");
        ItemStack sword = tool(h, "sword", List.of("iron", "iron", "wood"), 3);
        float base = ToolStats.compute(sword.get(Smithing.BUILD)).attackDamage();
        float keen = ToolStats.compute(sword.get(Smithing.BUILD).withMod("keen_edge", 2)).attackDamage();
        check(h, keen - base > 1.9f, "keen edge II adds " + (keen - base));
        h.succeed();
    }

    /** Soulbound pieces are held aside when the inventory drops and handed to the respawned player. */
    @GameTest
    public void soulboundStays(GameTestHelper h) {
        CT.Bot a = CT.join(h, "smSb", false), b = CT.join(h, "smSb2", false);
        try {
            ItemStack keep = tool(h, "pickaxe", List.of("iron", "leather", "wood"), 3);
            keep.set(Smithing.BUILD, keep.get(Smithing.BUILD).withMod("soulbound", 1));
            a.p.getInventory().setItem(3, keep);
            a.p.getInventory().setItem(4, new ItemStack(Items.DIRT, 5));
            Soulbound.stash(a.p.getInventory());
            check(h, a.p.getInventory().getItem(3).isEmpty(), "the soulbound pick stayed in the dropping inventory");
            check(h, a.p.getInventory().getItem(4).is(Items.DIRT), "dirt was taken too");
            net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(a.p, b.p, false);
            boolean back = false;
            for (int i = 0; i < b.p.getInventory().getContainerSize(); i++) back |= Soulbound.is(b.p.getInventory().getItem(i));
            check(h, back, "the respawned player didn't get the soulbound pick back");
        } finally {
            a.leave();
            b.leave();
        }
        h.succeed();
    }

    /** Coal in the hearth, flint and steel, bellows: the heat climbs and a piece on the coals warms up. */
    @GameTest(maxTicks = 400, padding = 8)
    public void forgeHeatsUp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smFrg", false);
        ServerPlayer p = bot.p;
        BlockPos forge = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.FORGE);
        level.setBlock(forge.below(), Blocks.STONE.defaultBlockState(), 3);
        use(p, forge, new ItemStack(Items.COAL, 4));
        use(p, forge, new ItemStack(Items.FLINT_AND_STEEL));
        use(p, forge, Smithing.part("pick_head", "iron", 3));
        ForgeBE be = (ForgeBE) level.getBlockEntity(forge);
        check(h, be.lit(), "the hearth didn't light");
        h.runAfterDelay(300, () -> {
            try {
                check(h, be.heat() > 600, "the hearth only reached " + be.heat());
                boolean warm = false;
                for (int i = 0; i < ForgeBE.SLOTS; i++) warm |= Heat.of(be.work(i), level.getGameTime()) > 300;
                check(h, warm, "the piece on the coals stayed cold");
            } finally {
                bot.leave();
            }
            h.succeed();
        });
    }

    /** Copper and gold melt over lava and three stirs make rose gold; a ladle takes it out. */
    @GameTest(maxTicks = 600, padding = 8)
    public void crucibleMakesRoseGold(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smCru", false);
        ServerPlayer p = bot.p;
        BlockPos pot = h.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(pot.below(), Blocks.LAVA.defaultBlockState(), 3);
        station(level, pot, Station.CRUCIBLE);
        use(p, pot, new ItemStack(Items.COPPER_INGOT));
        use(p, pot, new ItemStack(Items.GOLD_INGOT));
        CrucibleBE be = (CrucibleBE) level.getBlockEntity(pot);
        h.succeedWhen(() -> {
            check(h, be.fluids().getOrDefault("copper", 0) == 90 && be.fluids().getOrDefault("gold", 0) == 90, "not melted yet: " + be.fluids());
            for (int i = 0; i < 3; i++) use(p, pot, new ItemStack(Smithing.STIRRING_ROD));
            check(h, be.fluids().getOrDefault("rose_gold", 0) == 180, "no rose gold after three stirs: " + be.fluids());
            ItemStack ladle = new ItemStack(Smithing.LADLE_ITEM);
            use(p, pot, ladle);
            var load = HandToolItem.load(p.getMainHandItem());
            check(h, load != null && load.fluid().equals("rose_gold") && load.units() == 180, "the ladle took " + load);
            bot.leave();
        });
    }

    /** A sand mold, a ladle of iron, cooling, a hammer: a rough iron pick head. */
    @GameTest(maxTicks = 300, padding = 8)
    public void castingMakesARoughPart(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smCas", false);
        ServerPlayer p = bot.p;
        BlockPos bench = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.CASTING_BENCH);
        use(p, bench, new ItemStack(Smithing.MOLDING_BOX));
        for (int i = 0; i < 4; i++) use(p, bench, new ItemStack(Items.SAND));
        use(p, bench, Smithing.part("pick_head", "stone", 3));
        CastingBE be = (CastingBE) level.getBlockEntity(bench);
        check(h, be.mold().getItem() instanceof MoldItem && "pick_head".equals(MoldItem.shape(be.mold())), "no pick head sand mold: " + be.mold());
        ItemStack ladle = new ItemStack(Smithing.LADLE_ITEM);
        HandToolItem.setLoad(ladle, "iron", 360);
        use(p, bench, ladle);
        check(h, be.full() && HandToolItem.load(p.getMainHandItem()).units() == 180, "pour didn't take 180 units");
        h.runAfterDelay(CastingBE.COOL_TICKS + 5, () -> {
            try {
                p.getInventory().clearContent();
                use(p, bench, tool(h, "smithing_hammer", List.of("iron", "iron", "wood"), 3));
                ItemStack got = p.getOffhandItem();
                check(h, got.getItem() instanceof PartItem pi && pi.part.id().equals("pick_head") && "iron".equals(got.get(Smithing.MATERIAL)),
                        "knocked out " + got);
                check(h, got.getOrDefault(Smithing.PART, PartInfo.DEFAULT).rough(), "the casting isn't rough");
                check(h, be.mold().isEmpty(), "the sand mold wasn't used up");
            } finally {
                bot.leave();
            }
            h.succeed();
        });
    }

    /** A hot rough part on the anvil: strikes (even poor ones) finish the plan; it comes off finished and soft. */
    @GameTest(maxTicks = 400, padding = 8)
    public void anvilFinishesAPart(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smAnv", false);
        ServerPlayer p = bot.p;
        BlockPos anvil = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.ANVIL);
        ItemStack rough = Smithing.part("shovel_blade", "iron", 1);
        rough.set(Smithing.PART, new PartInfo(1, "", "", true));
        Heat.set(rough, 1100, level.getGameTime());
        use(p, anvil, rough);
        AnvilBE be = (AnvilBE) level.getBlockEntity(anvil);
        check(h, !be.piece().isEmpty() && be.plan().size() == 3, "the piece isn't on the anvil");
        ItemStack hammer = tool(h, "smithing_hammer", List.of("iron", "iron", "wood"), 3);
        int[] strikes = {0};
        h.onEachTick(() -> {
            if (level.getGameTime() % 7 != 0 || strikes[0] > 40) return;
            if (be.piece().getOrDefault(Smithing.PART, PartInfo.DEFAULT).rough()) {
                Heat.set(be.piece(), 1100, level.getGameTime());
                use(p, anvil, hammer);
                strikes[0]++;
            }
        });
        h.succeedWhen(() -> {
            ItemStack piece = be.piece();
            PartInfo info = piece.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
            check(h, !info.rough(), "still rough after " + strikes[0] + " strikes");
            check(h, info.quench().isEmpty(), "a fresh forging is already quenched");
            bot.leave();
        });
    }

    /** A hot finished part dunked in water is water-quenched and cools at once; a cold one does nothing. */
    @GameTest
    public void troughQuenches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smQ", false);
        try {
            ServerPlayer p = bot.p;
            BlockPos t = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.TROUGH);
            use(p, t, new ItemStack(Items.WATER_BUCKET));
            TroughBE be = (TroughBE) level.getBlockEntity(t);
            check(h, be.fill() == TroughBE.MAX && be.liquid().equals("water"), "the trough didn't fill");
            ItemStack part = Smithing.part("pick_head", "iron", 4);
            part.set(Smithing.PART, new PartInfo(4, "", "", false));
            Heat.set(part, 1000, level.getGameTime());
            use(p, t, part);
            ItemStack out = p.getMainHandItem();
            check(h, "water".equals(out.getOrDefault(Smithing.PART, PartInfo.DEFAULT).quench()), "not quenched: " + out.get(Smithing.PART));
            check(h, !out.has(Smithing.HEAT), "still hot after the quench");
        } finally {
            bot.leave();
        }
        h.succeed();
    }

    /** Two flint on the carving bench, the chisel: a flint pick head. */
    @GameTest(maxTicks = 300, padding = 8)
    public void carvingMakesAFlintHead(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smCrv", false);
        ServerPlayer p = bot.p;
        BlockPos bench = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.CARVING_BENCH);
        use(p, bench, new ItemStack(Items.FLINT));
        use(p, bench, new ItemStack(Items.FLINT));
        int[] cuts = {0};
        h.onEachTick(() -> {
            if (level.getGameTime() % 5 != 0 || cuts[0] > 12) return;
            use(p, bench, new ItemStack(Smithing.CHISEL));
            cuts[0]++;
        });
        h.succeedWhen(() -> {
            ItemStack got = p.getOffhandItem();
            check(h, got.getItem() instanceof PartItem pi && pi.part.id().equals("pick_head") && "flint".equals(got.get(Smithing.MATERIAL)),
                    "carved " + got + " after " + cuts[0] + " cuts");
            bot.leave();
        });
    }

    /** Head, binding, handle laid in order and riveted make a pickaxe of those parts, signed by its smith. */
    @GameTest
    public void assemblyRivetsATool(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smAsm", false);
        try {
            ServerPlayer p = bot.p;
            BlockPos bench = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.ASSEMBLY_BENCH);
            use(p, bench, Smithing.part("handle", "wood", 3));
            use(p, bench, Smithing.part("pick_head", "iron", 5));
            use(p, bench, Smithing.part("binding", "leather", 4));
            use(p, bench, Smithing.part("handle", "bone", 3));
            p.getInventory().clearContent();
            use(p, bench, tool(h, "smithing_hammer", List.of("iron", "iron", "wood"), 3));
            ItemStack made = p.getOffhandItem();
            ToolBuild b = made.get(Smithing.BUILD);
            check(h, b != null && b.tool().equals("pickaxe"), "riveted " + made);
            check(h, b.parts().get(0).material().equals("iron") && b.parts().get(2).material().equals("bone"), "wrong parts: " + b.parts());
            check(h, b.parts().get(0).stars() == 5, "stars lost");
            check(h, b.smith().equals(p.getGameProfile().name()), "not signed");
        } finally {
            bot.leave();
        }
        h.succeed();
    }

    /** 36 quartz around a sword and four chisel strikes etch Keen Edge I; 20 more is progress toward II. */
    @GameTest(maxTicks = 200, padding = 8)
    public void runesmithEtchesKeenEdge(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smRun", false);
        ServerPlayer p = bot.p;
        BlockPos table = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.RUNE_TABLE);
        use(p, table, tool(h, "sword", List.of("iron", "iron", "wood"), 3));
        p.setShiftKeyDown(true);
        use(p, table, new ItemStack(Items.QUARTZ, 56));
        p.setShiftKeyDown(false);
        RuneTableBE be = (RuneTableBE) level.getBlockEntity(table);
        int[] strikes = {0};
        h.onEachTick(() -> {
            if (level.getGameTime() % 8 != 0 || strikes[0] >= 8) return;
            use(p, table, new ItemStack(Smithing.CHISEL));
            strikes[0]++;
        });
        h.succeedWhen(() -> {
            ToolBuild b = be.tool().get(Smithing.BUILD);
            check(h, b != null && b.level("keen_edge") == 1, "keen edge not etched: " + b);
            check(h, b.progress("mod:keen_edge") == 20, "progress " + b.progress("mod:keen_edge"));
            bot.leave();
        });
    }

    /** A sledge breaks the 3x3 around the block it mined. */
    @GameTest(maxTicks = 60, padding = 8)
    public void sledgeMines3x3(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smSlg", false);
        ServerPlayer p = bot.p;
        BlockPos c = h.absolutePos(new BlockPos(4, 3, 4));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlock(c.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
        p.setItemInHand(InteractionHand.MAIN_HAND, tool(h, "sledge", List.of("iron", "iron", "iron", "wood"), 3));
        CT.pose(p, Vec3.atCenterOf(c.above(2)), 0, 90);
        CT.breakBlock(p, c);
        h.succeedWhen(() -> {
            int left = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!level.getBlockState(c.offset(dx, 0, dz)).isAir()) left++;
            check(h, left == 0, left + " blocks left of the 3x3");
            bot.leave();
        });
    }

    /** A dull sword held against the whetstone wheel gets its Edge back. */
    @GameTest
    public void wheelSharpens(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smWhl", false);
        try {
            ServerPlayer p = bot.p;
            BlockPos w = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.WHEEL);
            ItemStack sword = tool(h, "sword", List.of("iron", "iron", "wood"), 3);
            Smithing.setEdge(sword, 50);
            p.setItemInHand(InteractionHand.MAIN_HAND, sword);
            for (int i = 0; i < 3; i++) {
                CT.pose(p, Vec3.atBottomCenterOf(w.north(2)), 0, 30);
                CT.click(p, w, Direction.UP);
            }
            check(h, Smithing.edge(p.getMainHandItem()) == 65, "edge " + Smithing.edge(p.getMainHandItem()) + " after three turns");
        } finally {
            bot.leave();
        }
        h.succeed();
    }

    /** The Great Crucible forms from fire bricks round a hollow with the drain in its wall, and lava under it heats it. */
    @GameTest(maxTicks = 100, padding = 8)
    public void greatCrucibleForms(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos drain = h.absolutePos(new BlockPos(4, 3, 3));
        BlockPos hollow = drain.south();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(hollow.offset(dx, -1, dz), Smithing.FIRE_BRICKS.defaultBlockState(), 3);
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy <= 1; dy++) level.setBlock(hollow.offset(dx, dy, dz), Smithing.FIRE_BRICKS.defaultBlockState(), 3);
            }
        }
        level.setBlock(hollow, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(hollow.above(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(hollow.below(2), Blocks.LAVA.defaultBlockState(), 3);
        station(level, drain, Station.GREAT_DRAIN);
        var be = (net.axther.aftrhrs.feature.smithing.station.GreatCrucibleBE) level.getBlockEntity(drain);
        h.succeedWhen(() -> {
            check(h, be.formed(), "the Great Crucible didn't form");
            check(h, be.heat() >= 1600, "heat " + be.heat());
            check(h, be.capacity() == 36 * 90, "capacity " + be.capacity());
        });
    }

    /** A spout fitted to a crucible pours into the ingot mold on the casting bench under it. */
    @GameTest(maxTicks = 600, padding = 8)
    public void spoutPoursIntoTheMold(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smSpt", false);
        ServerPlayer p = bot.p;
        BlockPos pot = h.absolutePos(new BlockPos(3, 3, 4));
        level.setBlock(pot.below(), Blocks.LAVA.defaultBlockState(), 3);
        station(level, pot, Station.CRUCIBLE);
        BlockPos spout = pot.north();
        level.setBlock(spout, Smithing.block(Station.SPOUT).defaultBlockState(), 3);
        BlockPos bench = station(level, spout.below(), Station.CASTING_BENCH);
        ((CastingBE) level.getBlockEntity(bench)).accept(null);
        use(p, bench, MoldItem.of(MoldItem.Kind.CLAY, "ingot"));
        use(p, pot, new ItemStack(Items.IRON_INGOT));
        use(p, pot, new ItemStack(Items.IRON_INGOT));
        CrucibleBE crucible = (CrucibleBE) level.getBlockEntity(pot);
        CastingBE cast = (CastingBE) level.getBlockEntity(bench);
        boolean[] poured = {false};
        h.succeedWhen(() -> {
            if (!poured[0] && crucible.fluids().getOrDefault("iron", 0) >= 180) {
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                CT.pose(p, Vec3.atBottomCenterOf(spout.north(2)), 0, 20);
                CT.click(p, spout, Direction.NORTH);
                poured[0] = true;
            }
            check(h, poured[0] && cast.full() && "iron".equals(cast.fluid()), "mold not filled: " + cast.units() + " " + crucible.fluids());
            check(h, crucible.fluids().getOrDefault("iron", 0) == 90, "the spout took " + (180 - crucible.fluids().getOrDefault("iron", 0)));
            bot.leave();
        });
    }

    /** The creative tab lists no stack twice (vanilla refuses to build a tab with duplicates). */
    @GameTest
    public void creativeTabHasNoDuplicates(GameTestHelper h) {
        var tab = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getValue(Smithing.TAB);
        var params = new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(h.getLevel().enabledFeatures(), true,
                h.getLevel().registryAccess());
        tab.buildContents(params);
        check(h, tab.getDisplayItems().size() > 300, "the tab has " + tab.getDisplayItems().size() + " items");
        h.succeed();
    }

    /** /smith heat warms the held piece, and the piece keeps its heat when laid on the anvil. */
    @GameTest
    public void heatSurvivesTheAnvil(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "smHt", true);
        try {
            ServerPlayer p = bot.p;
            BlockPos anvil = station(level, h.absolutePos(new BlockPos(3, 2, 3)), Station.ANVIL);
            p.setItemInHand(InteractionHand.MAIN_HAND, Smithing.part("shovel_blade", "iron", 2));
            level.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "smith heat 1150");
            check(h, Heat.of(p.getMainHandItem(), level.getGameTime()) > 1100, "/smith heat didn't heat the held piece: " + p.getMainHandItem().get(Smithing.HEAT));
            use(p, anvil, p.getMainHandItem());
            AnvilBE be = (AnvilBE) level.getBlockEntity(anvil);
            check(h, !be.piece().isEmpty(), "the piece isn't on the anvil");
            check(h, Heat.of(be.piece(), level.getGameTime()) > 1100, "the piece cooled on the anvil: " + be.piece().get(Smithing.HEAT));
        } finally {
            bot.leave();
        }
        h.succeed();
    }

    /** Mods.traits is memoized per build instance: equal builds give equal maps, a repeat call the same map, read-only. */
    @GameTest
    public void traitsAreCachedAndReadOnly(GameTestHelper h) {
        SmithingDefs.load();
        var type = SmithingDefs.tool("pickaxe");
        check(h, type != null, "no pickaxe tool type");
        ToolBuild a = ToolBuild.of(type, List.of("stone", "wood", "wood"), List.of(3));
        ToolBuild b = ToolBuild.of(type, List.of("stone", "wood", "wood"), List.of(3));
        check(h, a != b && a.equals(b), "the two builds should be equal but distinct");
        var ta = Mods.traits(a);
        check(h, !ta.isEmpty(), "stone and wood bring no traits: " + ta);
        check(h, ta.equals(Mods.traits(b)), "equal builds, different traits: " + ta + " / " + Mods.traits(b));
        check(h, Mods.traits(a) == ta, "a repeat call built a new map");
        for (var e : ta.entrySet()) check(h, e.getValue() == Mods.trait(a, e.getKey()), "traits() and trait() disagree on " + e.getKey());
        boolean threw = false;
        try {
            ta.put("seasoned", 3);
        } catch (UnsupportedOperationException e) {
            threw = true;
        }
        check(h, threw, "the cached traits map can be changed");
        ToolBuild c = ToolBuild.of(type, List.of("flint", "wood", "wood"), List.of(3));
        check(h, !Mods.traits(c).equals(ta), "a different build got the same traits: " + Mods.traits(c));
        h.succeed();
    }
}
