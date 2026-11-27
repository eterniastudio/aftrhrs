package net.axther.aftrhrs.gametest.storage;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.feature.storage.StorageVaultBE;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Storage Vaults: upright boxes up to 3x3 across and any height, one set of contents answered by every block, any
 * number of kinds of items (each up to 20 stacks per block), hoppers in and out (also when a take can't be placed),
 * comparators, breaking (only what no longer fits drops), saving counts past a stack, and the storage network.
 */
public class VaultTests {
    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    static Block vault() {
        return BuiltInRegistries.BLOCK.getValue(AftrHrs.id("storage_vault"));
    }

    static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    static void place(GameTestHelper h, int x, int y, int z) {
        h.setBlock(new BlockPos(x, y, z), vault());
    }

    static StorageVaultBE be(GameTestHelper h, BlockPos rel) {
        return h.getBlockEntity(rel, StorageVaultBE.class);
    }

    static StorageVaultBE be(GameTestHelper h, int x, int y, int z) {
        return be(h, new BlockPos(x, y, z));
    }

    static boolean linked(GameTestHelper h, BlockPos rel, Direction d) {
        return h.getBlockState(rel).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d));
    }

    /** What a container holds of an item, the way a hopper sees it (for a vault: up to a stack per kind). */
    static int shown(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    static long held(StorageVaultBE v, Item item) {
        return (long) RemoteTests.call(v, "count", new ItemStack(item));
    }

    static void put(StorageVaultBE v, Item item, int n) {
        // Straight in, past any limit (the test sets up contents; the limit is tested on its own).
        Object lead = RemoteTests.call(v, "controllerBE");
        Object pool = field(lead, "pool", StorageVaultBE.class);
        RemoteTests.call(pool, "add", new ItemStack(item), (long) n);
        v.setChanged();
    }

    static String size(StorageVaultBE v) {
        return v.sizeX() + "x" + v.sizeZ() + "x" + v.height();
    }

    /** A 3x3 footprint five high is one vault, the same from every block, linked all through. */
    @GameTest(maxTicks = 40)
    public void vaultTowerIsOneVault(GameTestHelper h) {
        floor(h);
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) place(h, x, y, z);
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) {
            StorageVaultBE b = be(h, x, y, z);
            check(h, b.sizeX() == 3 && b.sizeZ() == 3 && b.height() == 5 && b.blocks() == 45, "block " + x + "," + y + "," + z + " is in a " + size(b));
        }
        check(h, linked(h, new BlockPos(2, 3, 2), Direction.UP) && linked(h, new BlockPos(2, 3, 2), Direction.DOWN)
                && linked(h, new BlockPos(2, 3, 2), Direction.NORTH), "the middle block isn't linked all round");
        check(h, !linked(h, new BlockPos(2, 5, 2), Direction.UP) && !linked(h, new BlockPos(1, 1, 1), Direction.WEST), "the outside shows as linked");
        // What goes in through one block comes out of another.
        put(be(h, 1, 1, 1), Items.DIAMOND, 300);
        check(h, held(be(h, 3, 5, 3), Items.DIAMOND) == 300, "the top corner doesn't see the bottom corner's diamonds");
        h.succeed();
    }

    /** Built one block at a time, upward and sideways, it keeps joining: a column, then two columns side by side. */
    @GameTest(maxTicks = 40)
    public void vaultJoinsAsYouBuild(GameTestHelper h) {
        floor(h);
        for (int y = 1; y <= 7; y++) {
            place(h, 2, y, 2);
            check(h, be(h, 2, 1, 2).height() == y, "after " + y + " blocks the column is " + size(be(h, 2, 1, 2)));
        }
        put(be(h, 2, 4, 2), Items.IRON_INGOT, 500);
        for (int y = 7; y >= 1; y--) place(h, 3, y, 2); // the second column from the top down
        StorageVaultBE v = be(h, 3, 7, 2);
        check(h, v.blocks() == 14 && v.sizeX() == 2 && v.height() == 7, "two columns side by side are a " + size(v));
        check(h, held(v, Items.IRON_INGOT) == 500, "the iron didn't follow the vault: " + held(v, Items.IRON_INGOT));
        h.succeed();
    }

    /** Footprints stop at 3x3: a 4-long row is a 3 and a 1; a 4x4 floor is a 3x3 and the rest. */
    @GameTest(maxTicks = 40)
    public void vaultFootprintStopsAtThreeByThree(GameTestHelper h) {
        floor(h);
        for (int x = 1; x <= 4; x++) place(h, x, 1, 1);
        check(h, be(h, 1, 1, 1).blocks() == 3 && be(h, 4, 1, 1).blocks() == 1, "a row of four is " + size(be(h, 1, 1, 1)) + " + " + size(be(h, 4, 1, 1)));
        check(h, !linked(h, new BlockPos(3, 1, 1), Direction.EAST), "the fourth block shows as part of the first vault");
        for (int x = 1; x <= 4; x++) for (int z = 3; z <= 6; z++) place(h, x, 2, z);
        check(h, be(h, 1, 2, 3).blocks() == 9, "a 4x4 floor's corner vault is " + size(be(h, 1, 2, 3)));
        h.succeed();
    }

    /** Any number of kinds; each kind up to 20 stacks per block, then no more of that kind (but other kinds still go in). */
    @GameTest(maxTicks = 40)
    public void vaultTakesAnyKindsUpToTheLimit(GameTestHelper h) {
        floor(h);
        place(h, 2, 1, 2);
        StorageVaultBE v = be(h, 2, 1, 2);
        int kinds = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (kinds == 60) break;
            if (item == Items.AIR || new ItemStack(item).getMaxStackSize() != 64) continue;
            check(h, v.canPlaceItem(v.getContainerSize() - 1, new ItemStack(item, 64)), "kind " + kinds + " didn't fit");
            v.setItem(v.getContainerSize() - 1, new ItemStack(item, 64));
            kinds++;
        }
        check(h, v.getContainerSize() == 61, "60 kinds show as " + v.getContainerSize() + " slots");
        // Fill cobblestone to its limit: 20 stacks for a one-block vault.
        long limit = 20L * 64;
        check(h, v.limit(new ItemStack(Items.COBBLESTONE)) == limit, "the cobblestone limit is " + v.limit(new ItemStack(Items.COBBLESTONE)));
        put(v, Items.COBBLESTONE, (int) (limit - held(v, Items.COBBLESTONE)));
        check(h, !v.canPlaceItem(0, new ItemStack(Items.COBBLESTONE)), "a full kind still takes more");
        check(h, v.canPlaceItem(0, new ItemStack(Items.ENDER_PEARL, 16)), "a full kind keeps other kinds out");
        h.succeed();
    }

    /**
     * Hoppers: one above puts in until that kind is full (the comparator then reads 15); one below that has no room
     * for what's in the vault takes nothing and loses nothing; once emptied it takes items out.
     */
    @GameTest(maxTicks = 240)
    public void vaultHoppersAndComparator(GameTestHelper h) {
        floor(h);
        place(h, 2, 2, 2);
        StorageVaultBE v = be(h, 2, 2, 2);
        put(v, Items.COBBLESTONE, 20 * 64 - 5);
        put(v, Items.DIAMOND, 200);
        h.setBlock(new BlockPos(2, 3, 2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        h.getBlockEntity(new BlockPos(2, 3, 2), HopperBlockEntity.class).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        h.setBlock(new BlockPos(2, 1, 3), Blocks.STONE);
        h.setBlock(new BlockPos(2, 2, 3), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH));
        // A hopper below with no room for cobblestone or diamonds (but not full, so it keeps trying): every take
        // fails and is put back, and nothing may go missing.
        h.setBlock(new BlockPos(2, 1, 2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        HopperBlockEntity below = h.getBlockEntity(new BlockPos(2, 1, 2), HopperBlockEntity.class);
        for (int i = 0; i < 5; i++) below.setItem(i, new ItemStack(Items.DIRT, 63));
        h.runAfterDelay(80, () -> {
            check(h, held(v, Items.COBBLESTONE) == 20 * 64, "cobblestone is " + held(v, Items.COBBLESTONE) + " (the hopper should fill it to 1280 exactly)");
            check(h, shown(h.getBlockEntity(new BlockPos(2, 3, 2), HopperBlockEntity.class), Items.COBBLESTONE) == 59, "the hopper above didn't stop at the limit");
            check(h, held(v, Items.DIAMOND) == 200, "diamonds went missing through a full hopper: " + held(v, Items.DIAMOND));
            int signal = comparatorOut(h, h.absolutePos(new BlockPos(2, 2, 3)));
            check(h, signal == 15, "the comparator reads " + signal + " with a full kind");
            for (int i = 0; i < 5; i++) below.setItem(i, ItemStack.EMPTY);
        });
        h.succeedWhen(() -> {
            long d = held(v, Items.DIAMOND), took = shown(below, Items.DIAMOND) + shown(below, Items.COBBLESTONE);
            check(h, took >= 3, "the hopper below took only " + took);
            check(h, d + shown(below, Items.DIAMOND) == 200, "diamonds don't add up: " + d + " + " + shown(below, Items.DIAMOND));
        });
    }

    private static int comparatorOut(GameTestHelper h, BlockPos abs) {
        var be = h.getLevel().getBlockEntity(abs);
        return be instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity c ? c.getOutputSignal() : -1;
    }

    /**
     * Breaking a block of a 1x1x3: what no longer fits in two blocks drops, the rest stays. Breaking the bottom (the
     * controller) of what's left hands everything to the block above.
     */
    @GameTest(maxTicks = 40)
    public void vaultBreakKeepsWhatFits(GameTestHelper h) {
        floor(h);
        for (int y = 1; y <= 3; y++) place(h, 2, y, 2);
        put(be(h, 2, 1, 2), Items.COBBLESTONE, 3000);
        put(be(h, 2, 1, 2), Items.EMERALD, 9);
        h.getLevel().destroyBlock(h.absolutePos(new BlockPos(2, 3, 2)), true);
        long kept = held(be(h, 2, 1, 2), Items.COBBLESTONE);
        check(h, kept == 2 * 20 * 64, "a 1x1x2 kept " + kept + " cobblestone");
        check(h, dropped(h, Items.COBBLESTONE) == 3000 - kept && dropped(h, Items.EMERALD) == 0, "dropped " + dropped(h, Items.COBBLESTONE) + " cobblestone, " + dropped(h, Items.EMERALD) + " emeralds");
        clearDrops(h);
        h.getLevel().destroyBlock(h.absolutePos(new BlockPos(2, 1, 2)), true);
        StorageVaultBE top = be(h, 2, 2, 2);
        check(h, top.blocks() == 1 && held(top, Items.COBBLESTONE) == 20 * 64 && held(top, Items.EMERALD) == 9,
                "the block left holds " + held(top, Items.COBBLESTONE) + " cobblestone, " + held(top, Items.EMERALD) + " emeralds");
        check(h, !linked(h, new BlockPos(2, 2, 2), Direction.DOWN), "the block left still shows as linked down");
        h.succeed();
    }

    static int dropped(GameTestHelper h, Item item) {
        int n = 0;
        for (ItemEntity e : h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(10))) {
            if (e.getItem().is(item)) n += e.getItem().getCount();
        }
        return n;
    }

    static void clearDrops(GameTestHelper h) {
        for (ItemEntity e : h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(10))) e.discard();
    }

    /** Counts past a stack survive saving and loading. */
    @GameTest(maxTicks = 40)
    public void vaultSavesBigCounts(GameTestHelper h) {
        floor(h);
        for (int y = 1; y <= 2; y++) place(h, 2, y, 2);
        StorageVaultBE v = be(h, 2, 1, 2);
        put(v, Items.REDSTONE, 2222);
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Edge"));
        v.setItem(v.getContainerSize() - 1, named);
        CompoundTag tag = v.saveWithFullMetadata(h.getLevel().registryAccess());
        BlockEntity copy = BlockEntity.loadStatic(v.getBlockPos(), v.getBlockState(), tag, h.getLevel().registryAccess());
        check(h, copy instanceof StorageVaultBE, "didn't load back as a vault");
        Object pool = field(copy, "pool", StorageVaultBE.class);
        long redstone = (long) RemoteTests.call(pool, "count", new ItemStack(Items.REDSTONE));
        long swords = (long) RemoteTests.call(pool, "count", named);
        check(h, redstone == 2222 && swords == 1, "loaded back " + redstone + " redstone, " + swords + " named swords");
        h.succeed();
    }

    /**
     * Outside claims, only one owner's blocks join: a stranger's vault block placed on someone's vault stays a vault of
     * its own (a hopper under it can't reach the other vault).
     */
    @GameTest(maxTicks = 40)
    public void vaultStrangersDontJoin(GameTestHelper h) {
        floor(h);
        for (int y = 1; y <= 3; y++) place(h, 2, y, 2);
        StorageVaultBE stranger = be(h, 2, 3, 2);
        try {
            Method setOwner = StorageVaultBE.class.getDeclaredMethod("setOwner", java.util.UUID.class);
            setOwner.setAccessible(true);
            setOwner.invoke(stranger, java.util.UUID.randomUUID());
            Class<?> form = Class.forName("net.axther.aftrhrs.feature.storage.VaultForm");
            Method reform = form.getDeclaredMethod("reform", net.minecraft.server.level.ServerLevel.class, java.util.Collection.class);
            reform.setAccessible(true);
            reform.invoke(null, h.getLevel(), java.util.List.of(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(2, 3, 2))));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        StorageVaultBE first = be(h, 2, 1, 2);
        check(h, first.blocks() == 2, "the owner's vault is " + size(first));
        check(h, stranger.blocks() == 1, "the stranger's block is in a " + size(stranger));
        check(h, !linked(h, new BlockPos(2, 2, 2), Direction.UP) && !linked(h, new BlockPos(2, 3, 2), Direction.DOWN), "the stranger's block shows as joined");
        put(first, Items.DIAMOND, 3);
        check(h, held(stranger, Items.DIAMOND) == 0, "the stranger's block sees the owner's diamonds");
        h.succeed();
    }

    /** A terminal beside a 1x1x3 vault: the network reads the vault once with its real counts, and takes and puts go through it. */
    @GameTest(maxTicks = 40)
    public void vaultInStorageNetwork(GameTestHelper h) {
        floor(h);
        h.setBlock(QuickCraftTests.TERMINAL, QuickCraftTests.terminal()); // 2,1,2
        for (int y = 1; y <= 3; y++) place(h, 3, y, 2);
        StorageVaultBE v = be(h, 3, 2, 2);
        put(v, Items.IRON_INGOT, 2500);
        try {
            Object network = scan(h);
            long n = (long) call(network, "count", new ItemStack(Items.IRON_INGOT));
            check(h, n == 2500, "the network counts " + n + " iron");
            int containers = (int) field(network, "containers");
            check(h, containers == 1, "the vault counts as " + containers + " storage blocks");
            int took = (int) call(network, "extract", new ItemStack(Items.IRON_INGOT), 150);
            check(h, took == 150 && held(v, Items.IRON_INGOT) == 2350, "took " + took + ", left " + held(v, Items.IRON_INGOT));
            Object rest = call(network, "insert", new ItemStack(Items.OAK_LOG, 100));
            check(h, rest == null && held(v, Items.OAK_LOG) == 100, "the network didn't put 100 logs into the vault: " + rest);
            long room = (long) call(network, "capacity", new ItemStack(Items.IRON_INGOT));
            check(h, room == 3L * 20 * 64 - 2350, "the network sees room for " + room + " more iron");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        h.succeed();
    }

    /**
     * A terminal's put into a vault and take out of it are written down right away, under the player's name (not later
     * as "Automation", or not at all): +20, then -10 on top.
     */
    @GameTest(maxTicks = 40)
    public void vaultLogsTerminalPutsAndTakesUnderThePlayer(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "vaLog");
        ServerPlayer p = a.player;
        String name = p.getGameProfile().name();
        h.setBlock(QuickCraftTests.CHEST, vault());
        StorageVaultBE v = be(h, QuickCraftTests.CHEST);
        p.getInventory().setItem(9, new ItemStack(Items.EMERALD, 20));
        h.useBlock(QuickCraftTests.TERMINAL, p);
        check(h, QuickCraftTests.chestWindow(p), "the terminal didn't open");
        int slot = -1;
        for (int i = 54; i < p.containerMenu.slots.size(); i++) {
            if (p.containerMenu.slots.get(i).container == p.getInventory() && p.containerMenu.slots.get(i).getContainerSlot() == 9) slot = i;
        }
        p.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, p);
        ListTag in = RemoteTests.log(v, "minecraft:emerald");
        check(h, held(v, Items.EMERALD) == 20, "the vault holds " + held(v, Items.EMERALD) + " emeralds");
        check(h, in.size() == 1 && in.getCompoundOrEmpty(0).getStringOr("w", "").equals(name) && in.getCompoundOrEmpty(0).getIntOr("d", 0) == 20,
                "the put isn't logged as +20 by " + name + ": " + RemoteTests.describe(in));
        int icon = -1;
        for (int i = 0; i < 45 && icon < 0; i++) if (p.containerMenu.slots.get(i).getItem().is(Items.EMERALD)) icon = i;
        check(h, icon >= 0, "no emerald icon");
        p.containerMenu.clicked(icon, 1, ContainerInput.PICKUP, p); // right: half (10)
        ListTag out = RemoteTests.log(v, "minecraft:emerald");
        check(h, held(v, Items.EMERALD) == 10 && out.size() == 2 && out.getCompoundOrEmpty(0).getStringOr("w", "").equals(name)
                        && out.getCompoundOrEmpty(0).getIntOr("d", 0) == -10 && out.getCompoundOrEmpty(1).getIntOr("d", 0) == 20,
                "the take isn't logged as -10 by " + name + " on top of +20: " + RemoteTests.describe(out));
        p.closeContainer();
        a.leave();
        h.succeed();
    }

    /**
     * A hopper drains one kind out of a watched vault until it runs out (and is forgotten): the log has one line for
     * it ("Hopper", -3 in all) and nothing for the kinds that stayed, also once the kind's slot has gone.
     */
    @GameTest(maxTicks = 200)
    public void vaultLogsAHopperDrainingAKindOnce(GameTestHelper h) {
        floor(h);
        BlockPos at = new BlockPos(3, 1, 2);
        place(h, 3, 1, 2);
        StorageVaultBE v = be(h, at);
        put(v, Items.EMERALD, 3);
        put(v, Items.DIAMOND, 5);
        put(v, Items.IRON_INGOT, 5);
        h.setBlock(QuickCraftTests.TERMINAL, QuickCraftTests.terminal()); // 2,1,2: the network watches the vault
        try {
            scan(h);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        // A hopper under it with room for exactly 3 emeralds and nothing else (it points at stone: it keeps them).
        BlockPos below = new BlockPos(3, 0, 2);
        h.setBlock(below, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        HopperBlockEntity hopper = h.getBlockEntity(below, HopperBlockEntity.class);
        hopper.setItem(0, new ItemStack(Items.EMERALD, 61));
        for (int i = 1; i < 5; i++) hopper.setItem(i, new ItemStack(Items.DIRT, 64));
        h.succeedWhen(() -> {
            check(h, held(v, Items.EMERALD) == 0 && shown(hopper, Items.EMERALD) == 64, "the hopper has " + shown(hopper, Items.EMERALD) + " emeralds");
            check(h, v.getContainerSize() == 3, "the emptied kind still has a slot: " + v.getContainerSize() + " slots");
            v.setChanged(); // a look after the kind went: nothing new to write down
            ListTag em = RemoteTests.log(v, "minecraft:emerald");
            check(h, em.size() == 1 && em.getCompoundOrEmpty(0).getStringOr("w", "").equals("Hopper") && em.getCompoundOrEmpty(0).getIntOr("d", 0) == -3,
                    "the emeralds aren't one line of Hopper -3: " + RemoteTests.describe(em));
            check(h, RemoteTests.log(v, "minecraft:diamond").isEmpty() && RemoteTests.log(v, "minecraft:iron_ingot").isEmpty(),
                    "kinds the hopper never took have lines: diamond " + RemoteTests.describe(RemoteTests.log(v, "minecraft:diamond"))
                            + " iron " + RemoteTests.describe(RemoteTests.log(v, "minecraft:iron_ingot")));
            check(h, held(v, Items.DIAMOND) == 5 && held(v, Items.IRON_INGOT) == 5, "the other kinds changed");
        });
    }

    // ------------------------------------------------------------------ storage_lean (Companion terminal deltas)

    /** The entry for kind {@code k} in a storage_delta's "set", or null. */
    static com.google.gson.JsonObject entry(com.google.gson.JsonObject delta, int k) {
        if (delta == null) return null;
        for (var e : delta.getAsJsonArray("set")) if (e.getAsJsonObject().get("k").getAsInt() == k) return e.getAsJsonObject();
        return null;
    }

    static boolean removed(com.google.gson.JsonObject delta, int k) {
        if (delta == null) return false;
        for (var e : delta.getAsJsonArray("remove")) if (e.getAsInt() == k) return true;
        return false;
    }

    /** Opens the terminal as a Companion player with these features; returns the diamonds' key from the storage message. */
    static int openLinked(GameTestHelper h, ServerPlayer p, String... features) {
        QuickCraftTests.link(p, features);
        QuickCraftTests.sent(p);
        h.useBlock(QuickCraftTests.TERMINAL, p);
        com.google.gson.JsonObject storage = QuickCraftTests.last(QuickCraftTests.sent(p), "storage");
        check(h, storage != null, "a Companion player got no storage message");
        for (var e : storage.getAsJsonArray("items")) {
            com.google.gson.JsonObject o = e.getAsJsonObject();
            check(h, o.has("item") && o.get("item").isJsonObject(), "the opening list has an entry without its item: " + o);
            if (o.getAsJsonObject("item").toString().contains("diamond")) return o.get("k").getAsInt();
        }
        throw h.assertionException(Component.literal("no diamonds in the opening list: " + storage));
    }

    /**
     * storage_lean: a lean session's later delta for a kind it already got leaves the item out; a kind that was removed
     * and comes back carries its item again; a session without the feature always gets the item.
     */
    @GameTest(maxTicks = 100)
    public void storageLeanDeltasSkipKnownItems(GameTestHelper h) {
        Bots.Bot b = QuickCraftTests.rig(h, "stLean", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = b.player;
        Container chest = QuickCraftTests.chest(h);
        try {
            int k = openLinked(h, p, "storage", "storage_lean");
            chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
            RemoteTests.refreshPass();
            com.google.gson.JsonObject more = entry(QuickCraftTests.last(QuickCraftTests.sent(p), "storage_delta"), k);
            check(h, more != null && more.get("total").getAsLong() == 7 && !more.has("item"), "a lean delta for known diamonds: " + more);
            chest.setItem(0, ItemStack.EMPTY);
            RemoteTests.refreshPass();
            check(h, removed(QuickCraftTests.last(QuickCraftTests.sent(p), "storage_delta"), k), "the diamonds weren't removed");
            chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
            RemoteTests.refreshPass();
            com.google.gson.JsonObject back = entry(QuickCraftTests.last(QuickCraftTests.sent(p), "storage_delta"), k);
            check(h, back != null && back.get("total").getAsLong() == 3 && back.has("item"), "diamonds back after a removal came without their item: " + back);
            p.closeContainer();

            // Without storage_lean (a new session): every entry carries its item.
            chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
            int k2 = openLinked(h, p, "storage");
            chest.setItem(0, new ItemStack(Items.DIAMOND, 9));
            RemoteTests.refreshPass();
            com.google.gson.JsonObject full = entry(QuickCraftTests.last(QuickCraftTests.sent(p), "storage_delta"), k2);
            check(h, full != null && full.get("total").getAsLong() == 9 && full.has("item"), "a delta without storage_lean lacks the item: " + full);
        } finally {
            p.closeContainer();
        }
        b.leave();
        h.succeed();
    }

    // ------------------------------------------------------------------ reflection into package-private parts

    static Object scan(GameTestHelper h) throws ReflectiveOperationException {
        Class<?> net = Class.forName("net.axther.aftrhrs.feature.storage.Network");
        Class<?> at = Class.forName("net.axther.aftrhrs.feature.storage.StorageBlock$Terminal$At");
        Class<?> kind = Class.forName("net.axther.aftrhrs.feature.storage.Kind");
        Object terminal = null;
        for (Object k : kind.getEnumConstants()) if (((Enum<?>) k).name().equals("TERMINAL")) terminal = k;
        Constructor<?> c = at.getDeclaredConstructors()[0];
        c.setAccessible(true);
        Object where = c.newInstance(h.getLevel(), h.absolutePos(QuickCraftTests.TERMINAL), terminal);
        Object module = RemoteTests.module();
        Method scan = net.getDeclaredMethod("scan", module.getClass(), net.minecraft.server.level.ServerPlayer.class, at);
        scan.setAccessible(true);
        return scan.invoke(null, module, null, where);
    }

    static Object call(Object target, String name, Object... args) {
        return RemoteTests.call(target, name, args);
    }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static Object field(Object target, String name, Class<?> owner) {
        try {
            var f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
