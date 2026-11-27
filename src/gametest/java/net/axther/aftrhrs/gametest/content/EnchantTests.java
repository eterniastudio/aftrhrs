package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.enchants.Enchants;
import net.axther.aftrhrs.feature.smithing.Soulbound;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * The Aftr Hrs enchantments (feature.enchants, docs/enchants.md): every definition loads with the right tags, and the
 * block, drop, damage and death effects do what their descriptions say.
 */
public class EnchantTests {
    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    static Holder<Enchantment> ench(GameTestHelper h, ResourceKey<Enchantment> key) {
        return h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    static ItemStack with(GameTestHelper h, ItemStack s, ResourceKey<Enchantment> key, int level) {
        s.enchant(ench(h, key), level);
        return s;
    }

    /** All 32 load; table ones are in the table, treasure ones aren't; the exclusive sets hold both ways. */
    @GameTest
    public void enchantmentsLoad(GameTestHelper h) {
        var reg = h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        long ours = reg.listElementIds().filter(k -> k.identifier().getNamespace().equals("aftrhrs")).count();
        check(h, ours == 32, ours + " aftrhrs enchantments loaded, want 32");
        for (var k : new ResourceKey[]{Enchants.RIMEBITE, Enchants.PROSPECTOR, Enchants.SOULBOUND, Enchants.TWIN_HOOK, Enchants.DRIFTWALKER}) {
            @SuppressWarnings("unchecked") ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) k;
            check(h, reg.get(key).isPresent(), key.identifier() + " missing");
        }
        check(h, ench(h, Enchants.CULLING).is(EnchantmentTags.IN_ENCHANTING_TABLE), "culling isn't in the enchanting table");
        check(h, !ench(h, Enchants.PROSPECTOR).is(EnchantmentTags.IN_ENCHANTING_TABLE), "prospector (treasure) is in the table");
        check(h, ench(h, Enchants.PROSPECTOR).is(EnchantmentTags.TRADEABLE), "prospector can't be traded");
        check(h, !ench(h, Enchants.SOULBOUND).is(EnchantmentTags.TRADEABLE), "soulbound can be traded");
        check(h, ench(h, Enchants.SOULBOUND).is(EnchantmentTags.ON_RANDOM_LOOT), "soulbound isn't found in loot");
        check(h, !Enchantment.areCompatible(ench(h, Enchants.GIANTSLAYER), ench(h, Enchantments.SHARPNESS)), "giantslayer stacks with sharpness");
        check(h, !Enchantment.areCompatible(ench(h, Enchantments.SHARPNESS), ench(h, Enchants.GIANTSLAYER)), "sharpness stacks with giantslayer");
        check(h, !Enchantment.areCompatible(ench(h, Enchants.RIMEBITE), ench(h, Enchantments.FIRE_ASPECT)), "rimebite stacks with fire aspect");
        check(h, !Enchantment.areCompatible(ench(h, Enchants.MOLTEN_TOUCH), ench(h, Enchantments.SILK_TOUCH)), "molten touch stacks with silk touch");
        check(h, ench(h, Enchants.TIMBERFALL).value().canEnchant(new ItemStack(Items.IRON_AXE)), "timberfall doesn't go on an axe");
        check(h, !ench(h, Enchants.TIMBERFALL).value().canEnchant(new ItemStack(Items.IRON_PICKAXE)), "timberfall goes on a pickaxe");
        check(h, ench(h, Enchants.POCKETING).value().canEnchant(new ItemStack(Items.DIAMOND_SWORD)), "pocketing doesn't go on a sword");
        check(h, ench(h, Enchants.STEADY_LINE).value().canEnchant(new ItemStack(Items.FISHING_ROD)), "steady line doesn't go on a rod");
        h.succeed();
    }

    /** Molten Touch: iron ore drops an ingot. */
    @GameTest(maxTicks = 40)
    public void moltenTouchSmelts(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "enMolt", false);
        try {
            BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
            level.setBlock(at, Blocks.IRON_ORE.defaultBlockState(), 3);
            bot.p.setItemInHand(InteractionHand.MAIN_HAND, with(h, new ItemStack(Items.IRON_PICKAXE), Enchants.MOLTEN_TOUCH, 1));
            CT.breakBlock(bot.p, at);
            Map<String, Integer> drops = CT.collectDrops(level, at, 2);
            check(h, drops.containsKey("minecraft:iron_ingot") && !drops.containsKey("minecraft:raw_iron"), "drops " + drops);
        } finally {
            bot.leave();
        }
        h.succeed();
    }

    /** Pocketing: the cobblestone lands in the inventory, nothing on the ground. */
    @GameTest(maxTicks = 40)
    public void pocketingPockets(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "enPock", false);
        try {
            BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
            level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
            bot.p.setItemInHand(InteractionHand.MAIN_HAND, with(h, new ItemStack(Items.IRON_PICKAXE), Enchants.POCKETING, 1));
            CT.breakBlock(bot.p, at);
            check(h, CT.collectDrops(level, at, 2).isEmpty(), "pocketing left drops on the ground");
            check(h, bot.p.getInventory().countItem(Items.COBBLESTONE) == 1, "no cobblestone in the inventory");
        } finally {
            bot.leave();
        }
        h.succeed();
    }

    /** Prospector I: mining one coal ore mines the connected vein (5 ores, within the 8 allowed). */
    @GameTest(maxTicks = 40)
    public void prospectorMinesTheVein(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "enPros", false);
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
        for (int i = 0; i < 5; i++) level.setBlock(at.east(i), Blocks.COAL_ORE.defaultBlockState(), 3);
        bot.p.setItemInHand(InteractionHand.MAIN_HAND, with(h, new ItemStack(Items.IRON_PICKAXE), Enchants.PROSPECTOR, 1));
        CT.breakBlock(bot.p, at);
        h.succeedWhen(() -> {
            int left = 0;
            for (int i = 0; i < 5; i++) if (level.getBlockState(at.east(i)).is(Blocks.COAL_ORE)) left++;
            check(h, left == 0, left + " coal ores left of the vein");
            CT.collectDrops(level, at, 6);
            bot.leave();
        });
    }

    /** Timberfall: chopping the bottom log of a natural tree fells the trunk. */
    @GameTest(maxTicks = 80, padding = 8)
    public void timberfallFellsTheTree(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "enTimb", false);
        BlockPos base = h.absolutePos(new BlockPos(4, 2, 4));
        for (int y = 0; y < 5; y++) level.setBlock(base.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
        var leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 3; y <= 5; y++) {
                    BlockPos q = base.offset(dx, y, dz);
                    if (level.getBlockState(q).isAir()) level.setBlock(q, leaf, 2);
                }
            }
        }
        bot.p.setItemInHand(InteractionHand.MAIN_HAND, with(h, new ItemStack(Items.IRON_AXE), Enchants.TIMBERFALL, 1));
        CT.pose(bot.p, Vec3.atCenterOf(base.north(2)), 0, 10);
        CT.breakBlock(bot.p, base);
        h.succeedWhen(() -> {
            int left = 0;
            for (int y = 0; y < 5; y++) if (level.getBlockState(base.above(y)).is(Blocks.OAK_LOG)) left++;
            check(h, left == 0, left + " logs left standing");
            CT.collectDrops(level, base, 8);
            bot.leave();
        });
    }

    /** Replanting: breaking ripe wheat leaves a fresh wheat plant. */
    @GameTest(maxTicks = 40)
    public void replantingReplants(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "enRepl", false);
        try {
            BlockPos soil = h.absolutePos(new BlockPos(2, 1, 2));
            level.setBlock(soil, Blocks.FARMLAND.defaultBlockState(), 3);
            CropBlock wheat = (CropBlock) Blocks.WHEAT;
            level.setBlock(soil.above(), wheat.getStateForAge(wheat.getMaxAge()), 3);
            bot.p.setItemInHand(InteractionHand.MAIN_HAND, with(h, new ItemStack(Items.IRON_HOE), Enchants.REPLANTING, 1));
            CT.breakBlock(bot.p, soil.above());
            var now = level.getBlockState(soil.above());
            check(h, now.is(Blocks.WHEAT) && wheat.getAge(now) == 0, "not replanted: " + now);
            check(h, CT.collectDrops(level, soil, 2).containsKey("minecraft:wheat"), "no wheat dropped");
        } finally {
            bot.leave();
        }
        h.succeed();
    }

    /** Soulbound (enchantment) on a vanilla sword stays out of the death drops. */
    @GameTest
    public void soulboundEnchantStays(GameTestHelper h) {
        CT.Bot a = CT.join(h, "enSoul", false);
        try {
            ItemStack sword = with(h, new ItemStack(Items.DIAMOND_SWORD), Enchants.SOULBOUND, 1);
            check(h, Soulbound.is(sword), "the enchantment isn't soulbound");
            a.p.getInventory().setItem(2, sword);
            a.p.getInventory().setItem(5, new ItemStack(Items.DIRT, 3));
            Soulbound.stash(a.p.getInventory());
            check(h, a.p.getInventory().getItem(2).isEmpty(), "the soulbound sword stayed in the dropping inventory");
            check(h, a.p.getInventory().getItem(5).is(Items.DIRT), "the dirt was kept too");
        } finally {
            a.leave();
        }
        h.succeed();
    }

    /** Giantslayer scales hits on a 100-health golem, not on a zombie; Culling adds on a target below 35% health. */
    @GameTest
    public void damageEnchantsScale(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "enDmg", false);
        try {
            ServerPlayer p = bot.p;
            Zombie z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            IronGolem g = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
            check(h, z != null && g != null, "couldn't make the mobs");
            var src = p.damageSources().playerAttack(p);
            p.setItemInHand(InteractionHand.MAIN_HAND, with(h, new ItemStack(Items.IRON_SWORD), Enchants.GIANTSLAYER, 4));
            float golem = FeatherEvents.DAMAGE_AMOUNT.invoker().modify(g, src, 10f);
            float zombie = FeatherEvents.DAMAGE_AMOUNT.invoker().modify(z, src, 10f);
            check(h, Math.abs(golem - 14.8f) < 0.01f, "giantslayer IV on a golem: " + golem + " (want 14.8)");
            check(h, Math.abs(zombie - 10f) < 0.01f, "giantslayer touched a zombie: " + zombie);
            p.setItemInHand(InteractionHand.MAIN_HAND, with(h, new ItemStack(Items.IRON_SWORD), Enchants.CULLING, 3));
            float healthy = FeatherEvents.DAMAGE_AMOUNT.invoker().modify(z, src, 10f);
            z.setHealth(5);
            float weak = FeatherEvents.DAMAGE_AMOUNT.invoker().modify(z, src, 10f);
            check(h, Math.abs(healthy - 10f) < 0.01f && Math.abs(weak - 13f) < 0.01f, "culling III: healthy " + healthy + ", weak " + weak);
            z.discard();
            g.discard();
        } finally {
            bot.leave();
        }
        h.succeed();
    }
}
