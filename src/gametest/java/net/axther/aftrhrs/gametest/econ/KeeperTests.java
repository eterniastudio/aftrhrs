package net.axther.aftrhrs.gametest.econ;

import net.axther.aftrhrs.feature.bank.BankModule;
import net.axther.aftrhrs.feature.shop.CounterKeeper;
import net.axther.aftrhrs.feature.shop.KeeperContent;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/** Counter keepers and the mining store: placing, selling through a keeper, staying unhurt, picking up, fair prices. */
public class KeeperTests {
    static void check(GameTestHelper h, boolean ok, String what) {
        EconTests.check(h, ok, what);
    }

    static Object shop() {
        return R.get(R.cls("net.axther.aftrhrs.feature.shop.ShopModule"), "active");
    }

    static Object counter(String name) {
        for (Object c : R.cls("net.axther.aftrhrs.feature.shop.SellBack$Counter").getEnumConstants()) {
            if (((Enum<?>) c).name().equals(name)) return c;
        }
        throw new IllegalStateException(name);
    }

    static CounterKeeper place(GameTestHelper h, ServerPlayer p, KeeperContent.Trade trade) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        BlockPos floor = h.absolutePos(new BlockPos(2, 0, 2));
        p.snapTo(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() - 1.5, 0, 0);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(KeeperContent.ITEMS.get(trade)));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        p.getMainHandItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
        List<CounterKeeper> ks = h.getLevel().getEntitiesOfClass(CounterKeeper.class, new AABB(floor).inflate(2));
        check(h, ks.size() == 1, "keepers placed: " + ks.size());
        return ks.get(0);
    }

    /** A placed miner keeper buys raw iron like the counter: items out, coins in, nothing else touched. */
    @GameTest(maxTicks = 60)
    public void minerKeeperBuysOres(GameTestHelper h) {
        Bots.Bot a = EconTests.join(h, "kmi");
        ServerPlayer p = a.player;
        CounterKeeper k = place(h, p, KeeperContent.Trade.MINER);
        check(h, p.getMainHandItem().isEmpty(), "the keeper item wasn't used up");
        check(h, k.owner() != null && k.owner().equals(p.getUUID()), "owner " + k.owner());
        p.getInventory().setItem(0, new ItemStack(Items.RAW_IRON, 10));
        p.getInventory().setItem(1, new ItemStack(Items.COD, 3));
        BankModule bank = EconTests.bank();
        long before = bank.balance(p.getUUID());
        Object sell = R.get(shop(), "sell");
        Object booth = R.call(R.cls("net.axther.aftrhrs.feature.shop.SellBack$Booth"), "of", k);
        Predicate<Object> all = s -> true;
        check(h, (boolean) R.call(sell, "sell", p, booth, counter("MINER"), all), "sale refused: " + a.chat());
        check(h, EconTests.count(p, Items.RAW_IRON) == 0, "raw iron left: " + EconTests.count(p, Items.RAW_IRON));
        check(h, EconTests.count(p, Items.COD) == 3, "the miner bought fish");
        check(h, bank.balance(p.getUUID()) > before, "balance " + before + " -> " + bank.balance(p.getUUID()));
        // too far from the keeper: refused
        p.getInventory().setItem(0, new ItemStack(Items.RAW_IRON, 10));
        p.snapTo(k.getX() + 40, k.getY(), k.getZ(), 0, 0);
        check(h, !(boolean) R.call(sell, "sell", p, booth, counter("MINER"), all), "sold from 40 blocks away");
        k.discard();
        a.leave();
        h.succeed();
    }

    /** Keepers can't be hurt or pushed; only the owner's sneak-click picks one up, as its item. */
    @GameTest(maxTicks = 60)
    public void keeperStaysAndOwnerPicksUp(GameTestHelper h) {
        Bots.Bot a = EconTests.join(h, "kpk");
        Bots.Bot b = EconTests.join(h, "kpo");
        ServerPlayer p = a.player;
        CounterKeeper k = place(h, p, KeeperContent.Trade.GROCER);
        float hp = k.getHealth();
        k.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(b.player), 50);
        check(h, k.isAlive() && k.getHealth() == hp, "keeper hurt: " + k.getHealth());
        check(h, !k.isPushable(), "keeper pushable");
        b.player.setShiftKeyDown(true);
        R.call(shop(), "keeperClick", b.player, k);
        check(h, k.isAlive(), "someone else picked the keeper up");
        p.setShiftKeyDown(true);
        p.getInventory().clearContent();
        R.call(shop(), "keeperClick", p, k);
        check(h, k.isRemoved(), "the owner couldn't pick it up");
        check(h, EconTests.count(p, KeeperContent.ITEMS.get(KeeperContent.Trade.GROCER)) == 1, "no keeper item back");
        a.leave();
        b.leave();
        h.succeed();
    }

    /** The mining store pays less for a diamond than the bank does: buying diamonds at the bank never makes coins. */
    @GameTest(maxTicks = 20)
    public void miningPricesLeaveNoArbitrage(GameTestHelper h) {
        Object sell = R.get(shop(), "sell");
        Object s = R.call(sell, "classify", new ItemStack(Items.DIAMOND));
        check(h, s != null, "the mining store doesn't buy diamonds");
        long cents = (long) R.call(s, "baseCents");
        long bankBuy = (long) R.get(EconTests.bank(), "buyRate");
        check(h, cents <= bankBuy * 100, "a diamond sells for " + cents / 100.0 + " here, the bank pays " + bankBuy);
        check(h, R.call(sell, "classify", new ItemStack(Items.COBBLESTONE)) == null, "cobblestone is bought");
        check(h, R.call(sell, "classify", new ItemStack(Items.IRON_PICKAXE)) == null, "tools are bought");
        h.succeed();
    }
}
