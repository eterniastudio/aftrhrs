package net.axther.aftrhrs.gametest.econ;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.feature.bank.BankModule;
import net.axther.aftrhrs.feature.trade.TradeModule;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bank, bounty editor, trade and mail: money and items must never appear or vanish. */
public class EconTests {
    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    /** A test player whose client counts as loaded (until then vanilla makes players invulnerable: no deaths). */
    static Bots.Bot join(GameTestHelper h, String base) {
        Bots.Bot b = Bots.join(h, Bots.name(base));
        b.player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        return b;
    }

    static BankModule bank() {
        return Feather.get().module(BankModule.class);
    }

    /** The player-inventory slot of a menu showing inventory slot {@code invSlot}. */
    static int menuSlot(AbstractContainerMenu menu, ServerPlayer p, int invSlot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s.container == p.getInventory() && s.getContainerSlot() == invSlot) return i;
        }
        return -1;
    }

    static int count(ServerPlayer p, Item item) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        if (p.containerMenu != null && p.containerMenu.getCarried().is(item)) n += p.containerMenu.getCarried().getCount();
        return n;
    }

    static int dropped(GameTestHelper h, Entity near, Item item) {
        int n = 0;
        for (ItemEntity e : h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(near.blockPosition()).inflate(12))) {
            if (e.getItem().is(item)) n += e.getItem().getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ quit events

    private static final Map<UUID, String> QUIT_THREADS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> QUIT_COUNTS = new ConcurrentHashMap<>();
    private static volatile boolean quitHooked;

    /**
     * A client closing its socket makes Netty run Connection.channelInactive on its IO thread; Fabric fired the quit
     * event right there, so every Feather quit listener (robbery kill, trade hand-out, saves) ran off the server thread.
     */
    @GameTest
    public void quitEventRunsOnTheServerThread(GameTestHelper h) throws Exception {
        if (!quitHooked) {
            quitHooked = true;
            ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
                UUID id = handler.player.getUUID();
                QUIT_THREADS.put(id, Thread.currentThread().getName());
                QUIT_COUNTS.merge(id, 1, Integer::sum);
            });
        }
        Bots.Bot a = Bots.join(h, Bots.name("quit"));
        io.netty.channel.Channel ch = (io.netty.channel.Channel) R.get(a, "channel");
        Thread netty = new Thread(ch::close, "Netty Test IO");
        netty.start();
        netty.join(5000);
        check(h, !QUIT_COUNTS.containsKey(a.id()), "the quit event ran on " + QUIT_THREADS.get(a.id()) + " (the Netty thread)");
        net.minecraft.network.Connection c = (net.minecraft.network.Connection) R.get(a, "connection");
        c.handleDisconnection();
        check(h, QUIT_COUNTS.getOrDefault(a.id(), 0) == 1, "the quit event ran " + QUIT_COUNTS.getOrDefault(a.id(), 0) + " times");
        check(h, Thread.currentThread().getName().equals(QUIT_THREADS.get(a.id())), "the quit event ran on " + QUIT_THREADS.get(a.id()));
        check(h, a.player.hasDisconnected(), "the player is still connected");
        h.succeed();
    }

    // ------------------------------------------------------------------ bounty editor

    /** Shift-clicking an item that may not be a reward into the editor gives it back (it used to be deleted). */
    @GameTest(maxTicks = 100)
    public void bountyEditorShiftClickGivesRefusedItemsBack(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("bty"));
        Bots.Bot b = Bots.join(h, Bots.name("btyT"));
        ServerPlayer p = a.player;
        p.getInventory().setItem(0, new ItemStack(Items.STICK, 7));
        a.cmd("bounty " + b.name());
        Gui gui = Gui.of(p);
        check(h, gui != null, "/bounty didn't open the reward chest: " + a.chat());
        int slot = menuSlot(p.containerMenu, p, 0);
        p.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, p);
        h.runAfterDelay(3, () -> {
            check(h, count(p, Items.STICK) == 7, "sticks after the refused shift-click: " + count(p, Items.STICK) + " (expected 7)");
            for (int i = 0; i < gui.size(); i++) check(h, !gui.container().getItem(i).is(Items.STICK), "a stick stayed in the reward chest");
            p.closeContainer();
            a.leave();
            b.leave();
            h.succeed();
        });
    }

    /** Dying with the reward chest open: the rewards fall with the death loot (or the grave), never vanish. */
    @GameTest(maxTicks = 100)
    public void bountyEditorDeathKeepsRewards(GameTestHelper h) {
        Bots.Bot a = join(h, "btd");
        Bots.Bot b = Bots.join(h, Bots.name("btdT"));
        ServerPlayer p = a.player;
        a.cmd("bounty " + b.name());
        Gui gui = Gui.of(p);
        check(h, gui != null, "/bounty didn't open the reward chest: " + a.chat());
        gui.container().setItem(0, new ItemStack(Items.DIAMOND, 5));
        p.kill(h.getLevel());
        h.runAfterDelay(2, () -> {
            ServerPlayer now = h.getLevel().getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
            h.runAfterDelay(2, () -> {
                int found = count(now, Items.DIAMOND) + dropped(h, now, Items.DIAMOND) + dropped(h, p, Items.DIAMOND) + Graves.count(p.getUUID(), Items.DIAMOND);
                check(h, found == 5, "diamonds from the open reward chest after death: " + found + " (expected 5)");
                Graves.clear(p.getUUID());
                a.leave();
                b.leave();
                h.succeed();
            });
        });
    }

    // ------------------------------------------------------------------ bank

    static Object account(UUID id) {
        return R.call(R.get(bank(), "store"), "account", id);
    }

    static long pending(UUID id) {
        return (long) R.get(account(id), "pending");
    }

    /** Creative's Inventory.add swallows what doesn't fit: a withdrawal with a full inventory must go to pending. */
    @GameTest(maxTicks = 60)
    public void bankWithdrawFullCreativeInventoryKeepsCoinsPending(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("bkc"));
        ServerPlayer p = a.player;
        p.setGameMode(GameType.CREATIVE);
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        BankModule bank = bank();
        check(h, bank.credit(p.getUUID(), a.name(), 1000, "TEST", "") == 1000, "credit failed");
        bank.openScreen(p);
        long fee = (long) Math.floor(500 * (double) R.get(bank, "withdrawFeePercent") / 100.0);
        check(h, (boolean) R.call(bank, "withdraw", p, "500"), "withdraw refused: " + a.chat());
        check(h, bank.balance(p.getUUID()) == 500, "balance " + bank.balance(p.getUUID()));
        long coins = count(p, bank.coin(1).getItem());
        check(h, coins + pending(p.getUUID()) == 500 - fee, "coins " + coins + " + pending " + pending(p.getUUID()) + " != " + (500 - fee));
        p.closeContainer();
        a.leave();
        h.succeed();
    }

    /** Deposit and withdraw move exactly what they say; fees go to the reserve; nothing more can be taken out. */
    @GameTest(maxTicks = 60)
    public void bankDepositWithdrawConserveCoins(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("bkd"));
        ServerPlayer p = a.player;
        BankModule bank = bank();
        Item coin = bank.coin(1).getItem();
        p.getInventory().setItem(0, bank.coin(64));
        p.getInventory().setItem(1, bank.coin(36));
        bank.openScreen(p);
        double depFee = (double) R.get(bank, "depositFeePercent"), wdFee = (double) R.get(bank, "withdrawFeePercent");
        check(h, (boolean) R.call(bank, "deposit", p, "100"), "deposit refused: " + a.chat());
        long credited = 100 - (long) Math.floor(100 * depFee / 100.0);
        check(h, count(p, coin) == 0 && bank.balance(p.getUUID()) == credited, "after deposit: coins " + count(p, coin) + " balance " + bank.balance(p.getUUID()));
        check(h, !(boolean) R.call(bank, "deposit", p, "1"), "deposited coins it didn't have");
        check(h, !(boolean) R.call(bank, "withdraw", p, String.valueOf(credited + 1)), "withdrew more than the balance");
        check(h, !(boolean) R.call(bank, "withdraw", p, "-5"), "withdrew a negative amount");
        check(h, (boolean) R.call(bank, "withdraw", p, String.valueOf(credited)), "withdraw refused: " + a.chat());
        long out = credited - (long) Math.floor(credited * wdFee / 100.0);
        check(h, bank.balance(p.getUUID()) == 0 && count(p, coin) + pending(p.getUUID()) == out,
                "after withdraw: balance " + bank.balance(p.getUUID()) + " coins " + count(p, coin) + " pending " + pending(p.getUUID()));
        // Selling diamonds costs more than buying pays: no round trip makes coins.
        long buy = (long) R.get(bank, "buyRate"), sell = (long) R.get(bank, "sellRate");
        check(h, sell >= buy, "diamond rates allow arbitrage: buy " + buy + " sell " + sell);
        p.closeContainer();
        a.leave();
        h.succeed();
    }

    /** Pending coins are never handed to a player on the death screen (respawning throws that inventory away). */
    @GameTest(maxTicks = 100)
    public void bankPendingWaitsForTheRespawn(GameTestHelper h) {
        Bots.Bot a = join(h, "bkp");
        ServerPlayer p = a.player;
        BankModule bank = bank();
        Object acc = account(p.getUUID());
        R.set(acc, "pending", 40L);
        p.kill(h.getLevel());
        R.call(bank, "deliverPending", p, true);
        check(h, pending(p.getUUID()) == 40, "pending handed to a dead player: " + pending(p.getUUID()));
        ServerPlayer now = h.getLevel().getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
        h.runAfterDelay(15, () -> {
            check(h, pending(now.getUUID()) == 0 && count(now, bank.coin(1).getItem()) == 40,
                    "after respawn: pending " + pending(now.getUUID()) + " coins " + count(now, bank.coin(1).getItem()));
            Graves.clear(now.getUUID());
            a.leave();
            h.succeed();
        });
    }

    /** /pay: not to yourself, not more than you have, not negative; a real payment moves the exact amount. */
    @GameTest(maxTicks = 60)
    public void payValidatesAndMovesExactly(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("pay"));
        Bots.Bot b = Bots.join(h, Bots.name("payB"));
        Hooks.Bank bank = bank();
        bank.credit(a.id(), a.name(), 300, "TEST", "");
        long b0 = bank.balance(b.id());
        a.cmd("pay " + a.name() + " 10");
        a.cmd("pay " + b.name() + " -10");
        a.cmd("pay " + b.name() + " 999999");
        a.cmd("pay " + b.name() + " 1e3");
        check(h, bank.balance(a.id()) == 300 && bank.balance(b.id()) == b0, "a refused /pay moved money: " + bank.balance(a.id()) + " / " + bank.balance(b.id()));
        R.call(bank, "pay", a.player, b.id(), 120L, true, b.name());
        check(h, bank.balance(a.id()) == 180 && bank.balance(b.id()) == b0 + 120, "after paying 120: " + bank.balance(a.id()) + " / " + bank.balance(b.id()));
        a.leave();
        b.leave();
        h.succeed();
    }

    /** bank.yml as a restart (after a crash) would read it: a second store loaded from the file. Account id -> balance. */
    @SuppressWarnings("unchecked")
    static Map<UUID, Long> bankOnDisk() {
        try {
            var ctor = R.cls("net.axther.aftrhrs.feature.bank.BankStore").getDeclaredConstructor(Feather.class);
            ctor.setAccessible(true);
            Object fresh = ctor.newInstance(Feather.get());
            try {
                R.call(fresh, "load", 0L);
                Map<UUID, Long> out = new java.util.HashMap<>();
                for (Map.Entry<UUID, Object> e : ((Map<UUID, Object>) R.get(fresh, "accounts")).entrySet()) out.put(e.getKey(), (long) R.get(e.getValue(), "balance"));
                return out;
            } finally {
                R.call(fresh, "shutdown");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static void forgetAccounts(List<UUID> ids) {
        Object store = R.get(bank(), "store");
        Map<UUID, Object> accounts = (Map<UUID, Object>) R.get(store, "accounts");
        for (UUID id : ids) accounts.remove(id);
        R.call(store, "save");
    }

    /** A thousand credits in one tick are one bank.yml snapshot (and at most one write), not a thousand. */
    @GameTest(maxTicks = 100)
    public void bankThousandCreditsOneSnapshot(GameTestHelper h) {
        BankModule bank = bank();
        Object store = R.get(bank, "store");
        java.util.concurrent.atomic.AtomicInteger writes = (java.util.concurrent.atomic.AtomicInteger) R.get(store, "writes");
        R.call(store, "flush");   // whatever ran before in this tick
        // Let the bank's IO thread finish what is queued, so a write of an earlier snapshot isn't counted as ours.
        try {
            ((java.util.concurrent.ExecutorService) R.get(store, "io")).submit(() -> {
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            check(h, false, "the bank IO thread did not go idle: " + e);
        }
        int s0 = (int) R.get(store, "snapshots");
        int w0 = writes.get();
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            check(h, bank.credit(id, "bulk" + i, 1 + i, "TEST", "") == 1 + i, "credit " + i + " failed");
        }
        check(h, (int) R.get(store, "snapshots") == s0, "the credits took " + ((int) R.get(store, "snapshots") - s0) + " snapshots during the tick");
        check(h, (boolean) R.get(store, "dirty") && (boolean) R.get(store, "flushScheduled"), "the credits didn't schedule the end-of-tick save");
        R.call(store, "flush");   // what the end of the tick does
        check(h, (int) R.get(store, "snapshots") == s0 + 1, "1000 credits took " + ((int) R.get(store, "snapshots") - s0) + " snapshots");
        check(h, !(boolean) R.get(store, "dirty"), "still dirty after the flush");
        h.runAfterDelay(20, () -> {
            int snaps = (int) R.get(store, "snapshots") - s0, w = writes.get() - w0;
            // Later ticks of other tests may add snapshots; the writer never renders more than were taken.
            check(h, w >= 1 && w <= snaps, writes.get() - w0 + " writes for " + snaps + " snapshots");
            Map<UUID, Long> disk = bankOnDisk();
            for (int i = 0; i < ids.size(); i++) {
                Long b = disk.get(ids.get(i));
                check(h, b != null && b == 1 + i, "bank.yml has " + b + " for bulk account " + i + " (expected " + (1 + i) + ")");
            }
            forgetAccounts(ids);
            h.succeed();
        });
    }

    /** Disabling the bank right after changes (shutdown, /feather reload of the module) writes them: enabling reads them back. */
    @GameTest(maxTicks = 60)
    public void bankDisableEnableKeepsFinalBalances(GameTestHelper h) {
        BankModule bank = bank();
        UUID id = UUID.randomUUID();
        check(h, bank.credit(id, "reload", 777, "TEST", "") == 777, "credit failed");
        check(h, bank.debit(id, "reload", 77, "TEST", "") == 700, "debit failed");
        bank.disable();
        bank.enable();
        check(h, bank.balance(id) == 700, "after disable + enable the account holds " + bank.balance(id) + " (expected 700 from bank.yml)");
        forgetAccounts(List.of(id));
        h.succeed();
    }

    /** A deposit snapshots the bank in the same step as the player's inventory file: a crash can't keep one without the other. */
    @GameTest(maxTicks = 60)
    public void bankDepositSnapshotsWithTheInventorySave(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("bks"));
        ServerPlayer p = a.player;
        BankModule bank = bank();
        Object store = R.get(bank, "store");
        p.getInventory().setItem(0, bank.coin(50));
        bank.openScreen(p);
        R.call(store, "flush");
        int s0 = (int) R.get(store, "snapshots");
        check(h, (boolean) R.call(bank, "deposit", p, "50"), "deposit refused: " + a.chat());
        check(h, (int) R.get(store, "snapshots") == s0 + 1 && !(boolean) R.get(store, "dirty"),
                "the deposit didn't snapshot the bank at once (" + ((int) R.get(store, "snapshots") - s0) + " snapshots)");
        long balance = bank.balance(p.getUUID());
        h.runAfterDelay(20, () -> {
            Long onDisk = bankOnDisk().get(p.getUUID());
            check(h, onDisk != null && onDisk == balance, "bank.yml after the deposit holds " + onDisk + " (expected " + balance + ")");
            p.closeContainer();
            a.leave();
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ trade

    static Object trade(ServerPlayer p) {
        return R.call(Feather.get().module(TradeModule.class), "tradeOf", p);
    }

    static Bots.Bot[] openTrade(GameTestHelper h, String base) {
        Bots.Bot a = Bots.join(h, Bots.name(base));
        Bots.Bot b = Bots.join(h, Bots.name(base + "B"));
        a.cmd("trade " + b.name());
        b.cmd("trade accept " + a.name());
        return new Bots.Bot[]{a, b};
    }

    /** Leaving mid-trade: the leaver's offer waits for them (handed out on the next join), the partner's comes back. */
    @GameTest(maxTicks = 100)
    public void tradeLeavingMidTradeReturnsOffers(GameTestHelper h) {
        Bots.Bot[] ab = openTrade(h, "trl");
        ServerPlayer a = ab[0].player, b = ab[1].player;
        check(h, trade(a) != null, "the trade didn't open: " + ab[0].chat() + " / " + ab[1].chat());
        a.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 16));
        b.getInventory().setItem(0, new ItemStack(Items.EMERALD, 9));
        a.containerMenu.clicked(menuSlot(a.containerMenu, a, 0), 0, ContainerInput.QUICK_MOVE, a);
        b.containerMenu.clicked(menuSlot(b.containerMenu, b, 0), 0, ContainerInput.QUICK_MOVE, b);
        check(h, count(a, Items.DIAMOND) == 0 && count(b, Items.EMERALD) == 0, "shift-click didn't offer the items");
        ab[0].leave();
        h.runAfterDelay(5, () -> {
            check(h, count(b, Items.EMERALD) == 9 && count(b, Items.DIAMOND) == 0, "partner: emeralds " + count(b, Items.EMERALD) + " diamonds " + count(b, Items.DIAMOND));
            check(h, dropped(h, b, Items.DIAMOND) + dropped(h, b, Items.EMERALD) == 0, "items were dropped on the ground");
            Bots.Bot again = Bots.join(h, ab[0].name());
            h.runAfterDelay(60, () -> {
                int d = count(again.player, Items.DIAMOND);
                check(h, d == 16, "the leaver got " + d + " diamonds back on rejoining (expected 16)");
                again.leave();
                ab[1].leave();
                h.succeed();
            });
        });
    }

    /** Both ready: the offers swap exactly once. */
    @GameTest(maxTicks = 300)
    public void tradeCompletesExactlyOnce(GameTestHelper h) {
        Bots.Bot[] ab = openTrade(h, "trc");
        ServerPlayer a = ab[0].player, b = ab[1].player;
        check(h, trade(a) != null, "the trade didn't open");
        a.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        b.getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));
        a.containerMenu.clicked(menuSlot(a.containerMenu, a, 0), 0, ContainerInput.QUICK_MOVE, a);
        b.containerMenu.clicked(menuSlot(b.containerMenu, b, 0), 0, ContainerInput.QUICK_MOVE, b);
        int ready = (int) R.get(R.cls("net.axther.aftrhrs.feature.trade.TradeMenu"), "BUTTON_READY");
        h.runAfterDelay(2, () -> {
            a.containerMenu.clickMenuButton(a, ready);
            b.containerMenu.clickMenuButton(b, ready);
            int wait = (int) R.call(Feather.get().module(TradeModule.class), "countdownSeconds") * 20 + 30;
            h.runAfterDelay(wait, () -> {
                check(h, trade(a) == null, "the trade is still open after the countdown");
                check(h, count(a, Items.EMERALD) == 3 && count(a, Items.DIAMOND) == 0, "a: emeralds " + count(a, Items.EMERALD) + " diamonds " + count(a, Items.DIAMOND));
                check(h, count(b, Items.DIAMOND) == 5 && count(b, Items.EMERALD) == 0, "b: diamonds " + count(b, Items.DIAMOND) + " emeralds " + count(b, Items.EMERALD));
                ab[0].leave();
                ab[1].leave();
                h.succeed();
            });
        });
    }

    /**
     * The escrow file holds the cursor too: picking a stack up onto the cursor and putting it back must refresh the
     * escrow, or a hard crash hands that stack out a second time on restart.
     */
    @GameTest(maxTicks = 200)
    public void tradeEscrowFollowsTheCursor(GameTestHelper h) {
        Bots.Bot[] ab = openTrade(h, "tre");
        ServerPlayer a = ab[0].player;
        Object t = trade(a);
        check(h, t != null, "the trade didn't open");
        Object side = R.call(t, "side", a.getUUID());
        a.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
        a.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 64));
        a.containerMenu.clicked(menuSlot(a.containerMenu, a, 0), 0, ContainerInput.QUICK_MOVE, a);
        int dSlot = menuSlot(a.containerMenu, a, 1);
        a.containerMenu.clicked(dSlot, 0, ContainerInput.PICKUP, a);
        check(h, a.containerMenu.getCarried().is(Items.DIAMOND), "the diamonds aren't on the cursor");
        h.runAfterDelay(30, () -> {
            check(h, persisted(side, Items.DIAMOND) == 64, "the escrow didn't record the cursor: " + persisted(side, Items.DIAMOND));
            a.containerMenu.clicked(dSlot, 0, ContainerInput.PICKUP, a);
            h.runAfterDelay(30, () -> {
                check(h, persisted(side, Items.DIAMOND) == 0, "the escrow still lists " + persisted(side, Items.DIAMOND) + " diamonds that are back in the inventory");
                ab[0].leave();
                ab[1].leave();
                h.succeed();
            });
        });
    }

    @SuppressWarnings("unchecked")
    static int persisted(Object side, Item item) {
        int n = 0;
        for (ItemStack s : (List<ItemStack>) R.get(side, "persisted")) if (s.is(item)) n += s.getCount();
        return n;
    }

    // ------------------------------------------------------------------ shop

    /** A stall keeper that died (or vanished) is spawned again and stays (it used to be discarded as an "orphan"). */
    @GameTest(maxTicks = 100)
    @SuppressWarnings("unchecked")
    public void deadStallKeepersComeBack(GameTestHelper h) throws Exception {
        Object shop = Feather.get().module(R.cls("net.axther.aftrhrs.feature.shop.ShopModule").asSubclass(net.axther.aftrhrs.core.FeatherModule.class));
        check(h, shop != null, "the shop module is off");
        Object store = R.get(shop, "store"), keepers = R.get(shop, "keepers");
        var ctor = R.cls("net.axther.aftrhrs.feature.shop.ShopStore$Stall").getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        String id = "bt" + Long.toString(System.nanoTime(), 36);
        Object stall = ctor.newInstance(id);
        var at = h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 2, 2.5));
        R.set(stall, "name", id);
        R.set(stall, "world", net.axther.aftrhrs.core.Worlds.name(h.getLevel()));
        R.set(stall, "x", at.x);
        R.set(stall, "y", at.y);
        R.set(stall, "z", at.z);
        R.set(stall, "keeperWorld", net.axther.aftrhrs.core.Worlds.name(h.getLevel()));
        R.set(stall, "kx", at.x);
        R.set(stall, "ky", at.y);
        R.set(stall, "kz", at.z);
        R.set(stall, "keeper", UUID.randomUUID());   // the old keeper, dead and gone
        Map<String, Object> stalls = (Map<String, Object>) R.get(store, "stalls");
        stalls.put(id, stall);
        R.call(keepers, "ensure", stall);
        h.runAfterDelay(5, () -> {
            try {
                Object v = R.call(keepers, "entity", stall);
                check(h, v != null, "the keeper wasn't spawned again (or was discarded right away)");
                check(h, ((Entity) v).getUUID().equals(R.get(stall, "keeper")), "the stall records another keeper");
                R.call(keepers, "despawn", id, (UUID) R.get(stall, "keeper"), R.call(stall, "keeperSpot"));
            } finally {
                stalls.remove(id);
                R.call(store, "save");
            }
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ mail

    /** Quitting with an item on the cursor of the gift chest: it goes back into the inventory, not onto the ground. */
    @GameTest(maxTicks = 60)
    public void mailGiftChestQuitKeepsTheCursor(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("mlg"));
        Bots.Bot b = Bots.join(h, Bots.name("mlgB"));
        ServerPlayer p = a.player;
        a.cmd("mail gift " + b.name());
        check(h, Gui.of(p) != null, "/mail gift didn't open the gift chest: " + a.chat());
        p.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 3));
        a.leave();
        h.runAfterDelay(2, () -> {
            check(h, count(p, Items.DIAMOND) == 3, "diamonds back in the inventory: " + count(p, Items.DIAMOND));
            check(h, dropped(h, p, Items.DIAMOND) == 0, "the cursor was dropped where the player logged out");
            b.leave();
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ round 2: mail reads and deletes

    /**
     * Opening /mail with unread letters and deleting one write nothing in that tick (the mail tick saves them in the
     * background); after the data thread wrote them, a store loaded from mail.yml (a restart) has the letters read and
     * the deleted one gone.
     */
    @GameTest(maxTicks = 100)
    public void mailOpenAndDeleteSaveInTheBackground(GameTestHelper h) {
        Object mail = Feather.get().module(net.axther.aftrhrs.feature.mail.MailModule.class);
        check(h, mail != null, "the mail module is off");
        Object store = net.axther.aftrhrs.gametest.creatures.CT.get(mail, "store");
        check(h, !(boolean) net.axther.aftrhrs.gametest.creatures.CT.get(store, "unreadable"), "mail.yml is unreadable");
        Bots.Bot a = join(h, "mlo");
        Hooks.Mail hook = Feather.get().service(Hooks.Mail.class);
        for (int i = 0; i < 3; i++) check(h, hook.systemLetter(a.id(), "Tester", "letter " + i), "a system letter wasn't kept");
        Object box = net.axther.aftrhrs.gametest.creatures.CT.call(store, "find", a.id());
        @SuppressWarnings("unchecked") List<Object> letters = (List<Object>) net.axther.aftrhrs.gametest.creatures.CT.get(box, "letters");
        check(h, letters.size() == 3, "expected 3 letters, found " + letters.size());
        long deleted = (long) net.axther.aftrhrs.gametest.creatures.CT.get(letters.get(1), "id");
        int saves0 = (int) net.axther.aftrhrs.gametest.creatures.CT.get(store, "saves");
        net.axther.aftrhrs.gametest.creatures.CT.call(mail, "open", a.player, 0);
        net.axther.aftrhrs.gametest.creatures.CT.call(mail, "delete", a.player, deleted);
        int saves = (int) net.axther.aftrhrs.gametest.creatures.CT.get(store, "saves") - saves0;
        check(h, saves == 0, "opening /mail and deleting a letter saved mail.yml " + saves + " time(s) in that tick");
        check(h, letters.stream().allMatch(l -> (boolean) net.axther.aftrhrs.gametest.creatures.CT.get(l, "read")), "opening /mail didn't mark the letters read");
        check(h, letters.stream().noneMatch(l -> (long) net.axther.aftrhrs.gametest.creatures.CT.get(l, "id") == deleted), "the deleted letter is still there");
        // The mail tick runs every 40 ticks; then the data thread writes it.
        h.runAfterDelay(45, () -> {
            check(h, (int) net.axther.aftrhrs.gametest.creatures.CT.get(store, "saves") > saves0, "the mail tick never saved the read flags");
            net.axther.aftrhrs.core.SafeYaml.drain(5);
            try {
                var ctor = net.axther.aftrhrs.gametest.creatures.CT.cls("net.axther.aftrhrs.feature.mail.MailStore").getDeclaredConstructor(Feather.class);
                ctor.setAccessible(true);
                Object fresh = ctor.newInstance(Feather.get());
                net.axther.aftrhrs.gametest.creatures.CT.call(fresh, "load");
                Object b = net.axther.aftrhrs.gametest.creatures.CT.call(fresh, "find", a.id());
                check(h, b != null, "mail.yml has no box for the player");
                @SuppressWarnings("unchecked") List<Object> onDisk = (List<Object>) net.axther.aftrhrs.gametest.creatures.CT.get(b, "letters");
                check(h, onDisk.size() == 2, "mail.yml has " + onDisk.size() + " letters (expected 2)");
                check(h, onDisk.stream().allMatch(l -> (boolean) net.axther.aftrhrs.gametest.creatures.CT.get(l, "read")), "mail.yml lost the read flags");
                check(h, onDisk.stream().noneMatch(l -> (long) net.axther.aftrhrs.gametest.creatures.CT.get(l, "id") == deleted), "the deleted letter came back from mail.yml");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            } finally {
                letters.clear();
                net.axther.aftrhrs.gametest.creatures.CT.call(store, "markDirty");
                a.leave();
            }
            h.succeed();
        });
    }
}
