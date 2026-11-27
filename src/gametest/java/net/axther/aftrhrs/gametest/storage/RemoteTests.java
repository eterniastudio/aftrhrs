package net.axther.aftrhrs.gametest.storage;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.feature.storage.StorageLog;
import net.axther.aftrhrs.feature.storage.StorageTime;
import net.axther.aftrhrs.gametest.core.Bots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.kyori.adventure.audience.Audience;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/**
 * Crafting Terminal Remotes (link, permission re-checks, tier I / tier II reach, chunk tickets while linked and
 * released after, a broken terminal) and the storage "who changed what" log (terminal takes and puts, a hopper, kept
 * through save and load), plus the time text the tooltips use.
 */
public class RemoteTests {
    static final BlockPos TERMINAL = QuickCraftTests.TERMINAL, CHEST = QuickCraftTests.CHEST;

    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(Component.literal(what));
    }

    static ItemStack remote(int tier) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(AftrHrs.id(tier == 2 ? "feather_terminal_remote_ender" : "feather_terminal_remote")));
    }

    // ------------------------------------------------------------------ reflection into the storage module

    static Object module() {
        try {
            Class<?> mc = Class.forName("net.axther.aftrhrs.feature.storage.StorageModule");
            Method active = mc.getDeclaredMethod("active");
            active.setAccessible(true);
            return active.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object remotes() {
        return call(module(), "remotes");
    }

    static Object call(Object target, String name, Object... args) {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    if (e.getCause() instanceof RuntimeException re) throw re;
                    throw new IllegalStateException(e.getCause());
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    // another overload: try on
                }
            }
        }
        throw new IllegalStateException("no " + name + "/" + args.length);
    }

    @SuppressWarnings("unchecked")
    static Set<Long> ticketed(ServerLevel level, BlockPos abs) {
        return (Set<Long>) call(remotes(), "ticketed", level, abs);
    }

    /** Sneak-right-click on the terminal with the stack in hand, through the real game path. */
    static void link(GameTestHelper h, ServerPlayer p, ItemStack stack) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.setShiftKeyDown(true);
        BlockPos abs = h.absolutePos(TERMINAL);
        p.gameMode.useItemOn(p, h.getLevel(), stack, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
        p.setShiftKeyDown(false);
    }

    /** Right-click in the air with the stack, through the real game path. */
    static void use(ServerPlayer p, ItemStack stack) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.gameMode.useItem(p, p.level(), stack, InteractionHand.MAIN_HAND);
    }

    static boolean chestWindow(ServerPlayer p) {
        return p.containerMenu != p.inventoryMenu && p.containerMenu.slots.size() >= 54;
    }

    // ------------------------------------------------------------------ remotes

    /**
     * Linking writes the link onto the item and tickets the network's chunks; relinking the same remote elsewhere
     * releases the old network; breaking the terminal kills the link (tickets go, the remote says why).
     */
    @GameTest(maxTicks = 100)
    public void remoteLinksTicketsAndReleases(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmLink", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = a.player;
        ServerLevel level = h.getLevel();
        ItemStack stack = remote(1);
        link(h, p, stack);
        ItemStack held = p.getMainHandItem();
        String lore = held.getOrDefault(net.minecraft.core.component.DataComponents.LORE, net.minecraft.world.item.component.ItemLore.EMPTY).lines().toString();
        BlockPos abs = h.absolutePos(TERMINAL);
        check(h, lore.contains("Linked to") && lore.contains(String.valueOf(abs.getX())), "the remote's tooltip doesn't say where it is linked: " + lore);
        check(h, a.heard("Linked to the terminal"), "no link message: " + a.chat());
        Set<Long> t = ticketed(level, abs);
        check(h, t.contains(ChunkPos.pack(abs)) && t.contains(ChunkPos.pack(h.absolutePos(CHEST))), "the network's chunks aren't ticketed: " + t);

        // Open it from afar (Overworld to Overworld: tier I is fine) as the chest window.
        a.clear();
        use(p, held);
        check(h, chestWindow(p), "tier I in the Overworld didn't open the storage: " + a.chat());
        p.closeContainer();

        // A second terminal: relinking the same remote lets the first network go.
        BlockPos second = new BlockPos(2, 1, 5);
        h.setBlock(second, QuickCraftTests.terminal());
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        p.setShiftKeyDown(true);
        BlockPos abs2 = h.absolutePos(second);
        p.gameMode.useItemOn(p, level, held, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs2), Direction.UP, abs2, false));
        p.setShiftKeyDown(false);
        check(h, ticketed(level, abs).isEmpty(), "relinking kept the old network's tickets: " + ticketed(level, abs));
        check(h, !ticketed(level, abs2).isEmpty(), "the new network isn't ticketed");

        // Breaking the linked terminal: tickets released, the remote refuses with a reason.
        h.setBlock(second, Blocks.AIR);
        check(h, ticketed(level, abs2).isEmpty(), "breaking the terminal kept its tickets: " + ticketed(level, abs2));
        a.clear();
        use(p, p.getMainHandItem());
        check(h, !chestWindow(p) && a.heard("was broken"), "a broken terminal's remote: " + a.chat());
        a.leave();
        h.succeed();
    }

    /** Tier I refuses from the Nether (and says why); tier II opens the same Overworld network from the Nether. */
    @GameTest(maxTicks = 100)
    public void remoteTierOneStaysInTheOverworldTierTwoCrosses(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmTierA", new ItemStack(Items.DIAMOND, 5));
        ItemStack one = remote(1);
        link(h, a.player, one);
        ItemStack linked = a.player.getMainHandItem().copy();
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        check(h, nether != null, "no Nether on the test server");
        Bots.Bot b = Bots.join(nether, Bots.name("rmTierB"), new Vec3(0.5, 120, 0.5));
        ServerPlayer p = b.player;
        p.getInventory().clearContent();
        b.clear();
        use(p, linked.copy());
        check(h, !chestWindow(p) && b.heard("only works in the Overworld"), "tier I from the Nether: " + b.chat());
        // Tier II: the same link on an Ender remote (what the transmute recipe makes).
        ItemStack two = remote(2);
        two.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, linked.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA));
        b.clear();
        use(p, two);
        check(h, chestWindow(p), "tier II from the Nether didn't open the Overworld storage: " + b.chat());
        // Putting the remote away closes it on the next check.
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        // The check runs every 10 ticks (the first one 40 ticks after the server started): wait for it.
        h.succeedWhen(() -> {
            check(h, !chestWindow(p), "the storage stayed open after the remote was put away");
            b.leave();
            a.leave();
        });
    }

    /** Linking needs container trust at the terminal, and every use checks it again (a claim made after linking). */
    @GameTest(maxTicks = 100, environment = "aftrhrs_gametest:econ_claims")
    public void remoteNeedsTrustToLinkAndOnEveryUse(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmTrust", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = a.player;
        link(h, p, remote(1));
        ItemStack linked = p.getMainHandItem();
        check(h, linked.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA), "the first link (no claim) failed: " + a.chat());
        Object claims = Feather.get().module(net.axther.aftrhrs.feature.claims.ClaimsModule.class);
        BlockPos c1 = h.absolutePos(new BlockPos(0, 0, 0)), c2 = h.absolutePos(new BlockPos(7, 0, 7));
        Object area;
        try {
            Class<?> ac = Class.forName("net.axther.aftrhrs.feature.claims.Area");
            Method rect = ac.getDeclaredMethod("rect", int.class, int.class, int.class, int.class);
            rect.setAccessible(true);
            area = rect.invoke(null, c1.getX(), c1.getZ(), c2.getX(), c2.getZ());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        Object claim = call(claims, "createAdmin", Audience.empty(), h.getLevel(), area, "rm" + Long.toString(System.nanoTime(), 36));
        check(h, claim != null, "the test claim could not be created");
        try {
            a.clear();
            use(p, linked);
            check(h, !chestWindow(p) && a.heard("can't use storage here"), "a remote opened storage in a claim without trust: " + a.chat());
            a.clear();
            link(h, p, remote(1));
            check(h, !p.getMainHandItem().has(net.minecraft.core.component.DataComponents.CUSTOM_DATA), "a remote linked inside a claim without trust");
        } finally {
            try {
                Field reg = claims.getClass().getDeclaredField("registry");
                reg.setAccessible(true);
                call(reg.get(claims), "remove", claim);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        a.leave();
        h.succeed();
    }

    static int intField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void intField(Object target, String name, int value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A remote whose link data was made up (or edited): id, dimension and position as given. */
    static ItemStack forged(int tier, String id, String dim, int x, int y, int z) {
        ItemStack stack = remote(tier);
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putString("dim", dim);
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        CompoundTag data = new CompoundTag();
        data.put("feather:remote", t);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
        return stack;
    }

    /**
     * Item data never drives chunk loading: a made-up link id is refused (and not registered), a real id with an
     * edited position opens the real terminal, and neither loads (or generates) the far chunk it points at.
     */
    @GameTest(maxTicks = 100)
    public void remoteForgedItemsAreRefusedAndLoadNothing(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmForge", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = a.player;
        ServerLevel level = h.getLevel();
        int fx = 2_900_000, fz = -2_900_000;
        check(h, !level.hasChunk(fx >> 4, fz >> 4), "the far chunk was loaded before the test");
        int linksBefore = (int) call(remotes(), "linkCount");
        a.clear();
        use(p, forged(2, java.util.UUID.randomUUID().toString(), "minecraft:overworld", fx, 64, fz));
        check(h, !chestWindow(p) && a.heard("isn't known here"), "a made-up link wasn't refused: " + a.chat());
        check(h, !level.hasChunk(fx >> 4, fz >> 4), "a made-up link loaded the chunk it points at");
        ServerLevel end = level.getServer().getLevel(Level.END);
        int ex = 1_234_560, ez = 987_650;
        boolean endBefore = end != null && end.hasChunk(ex >> 4, ez >> 4);
        use(p, forged(2, java.util.UUID.randomUUID().toString(), "minecraft:the_end", ex, 64, ez));
        check(h, end == null || end.hasChunk(ex >> 4, ez >> 4) == endBefore, "a made-up link loaded an End chunk");
        check(h, (int) call(remotes(), "linkCount") == linksBefore, "a made-up link id was registered");
        a.clear();
        use(p, forged(2, java.util.UUID.randomUUID().toString(), "minecraft:overworld", fx, 64, fz));
        check(h, a.heard("isn't known here"), "the made-up id was known the second time: " + a.chat());

        // A real link with the position edited: the server's own record wins.
        link(h, p, remote(2));
        ItemStack real = p.getMainHandItem();
        CompoundTag data = real.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        CompoundTag link = data.getCompoundOrEmpty("feather:remote");
        ItemStack edited = forged(2, link.getStringOr("id", ""), "minecraft:overworld", fx, 64, fz);
        a.clear();
        use(p, edited);
        check(h, chestWindow(p), "a real link with an edited position didn't open its real terminal: " + a.chat());
        check(h, !level.hasChunk(fx >> 4, fz >> 4), "an edited position loaded the chunk it points at");
        p.closeContainer();
        a.leave();
        h.succeed();
    }

    /**
     * The per-player chunk cap holds: with 1 chunk allowed, a second network of the same owner gets no ticket, not on
     * linking (refresh) and not on the next refresh pass. (Own batch: the cap is a server-wide setting.)
     */
    @GameTest(maxTicks = 100, environment = "aftrhrs_gametest:storage_isolated")
    public void remoteOwnerChunkCapHolds(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmCap", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = a.player;
        ServerLevel level = h.getLevel();
        Object r = remotes();
        int was = intField(r, "maxChunksPerPlayer");
        BlockPos far = h.absolutePos(TERMINAL).offset(48, 0, 0);
        intField(r, "maxChunksPerPlayer", 1);
        try {
            link(h, p, remote(1));
            Set<Long> first = ticketed(level, h.absolutePos(TERMINAL));
            check(h, first.size() == 1, "the first network didn't get its one chunk: " + first);
            level.getChunk(far.getX() >> 4, far.getZ() >> 4);
            level.setBlock(far, QuickCraftTests.terminal().defaultBlockState(), 3);
            ItemStack second = remote(1);
            p.setItemInHand(InteractionHand.MAIN_HAND, second);
            p.setShiftKeyDown(true);
            p.gameMode.useItemOn(p, level, second, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(far), Direction.UP, far, false));
            p.setShiftKeyDown(false);
            check(h, p.getMainHandItem().has(net.minecraft.core.component.DataComponents.CUSTOM_DATA), "the second link failed: " + a.chat());
            check(h, ticketed(level, far).isEmpty(), "an owner past the chunk cap got a ticket on linking: " + ticketed(level, far));
            call(r, "tick");
            check(h, ticketed(level, far).isEmpty(), "an owner past the chunk cap got a ticket on the refresh pass: " + ticketed(level, far));
            check(h, ticketed(level, h.absolutePos(TERMINAL)).size() == 1, "the first network lost or grew its chunk: " + ticketed(level, h.absolutePos(TERMINAL)));
        } finally {
            intField(r, "maxChunksPerPlayer", was);
            level.setBlock(far, Blocks.AIR.defaultBlockState(), 3);
        }
        a.leave();
        h.succeed();
    }

    static int scans(ServerLevel level, BlockPos abs) {
        return (int) call(remotes(), "scansOf", level, abs);
    }

    /**
     * A kept-loaded network is read again only while it may still change: once two passes found the same chunks, a
     * pass with nothing changed reads nothing; using the remote reads it again.
     */
    @GameTest(maxTicks = 100)
    public void remoteSettledNetworkIsNotReadEveryPass(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmScan", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = a.player;
        ServerLevel level = h.getLevel();
        BlockPos abs = h.absolutePos(TERMINAL);
        link(h, p, remote(1));
        Object r = remotes();
        check(h, scans(level, abs) >= 1, "linking didn't read the network");
        call(r, "tick"); // the same chunks again: settled
        int settled = scans(level, abs);
        call(r, "tick");
        check(h, scans(level, abs) == settled, "a pass with nothing changed read the network again: " + settled + " -> " + scans(level, abs));
        check(h, ticketed(level, abs).contains(ChunkPos.pack(h.absolutePos(CHEST))), "the cached network lost its tickets: " + ticketed(level, abs));
        // Using the remote reads it again (what the player just built is held at once).
        use(p, p.getMainHandItem());
        check(h, scans(level, abs) == settled + 1, "using the remote didn't read the network again: " + scans(level, abs));
        p.closeContainer();
        a.leave();
        h.succeed();
    }

    /**
     * A barrel a player puts against a kept-loaded network, across a chunk border, gets that new chunk ticketed on the
     * next pass, though the network had settled (high above the test, so the border is the test's own).
     */
    @GameTest(maxTicks = 100)
    public void remoteTicketsStoragePlacedIntoANewChunk(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmGrow");
        ServerPlayer p = a.player;
        ServerLevel level = h.getLevel();
        BlockPos base = h.absolutePos(TERMINAL).above(24);
        int edge = ((base.getX() >> 4) + 1) << 4; // the first block of the next chunk east
        BlockPos term = new BlockPos(edge - 2, base.getY(), base.getZ()), barrel = term.east(), placed = barrel.east();
        long fresh = ChunkPos.pack(placed);
        level.getChunk(placed.getX() >> 4, placed.getZ() >> 4);
        level.setBlock(term, QuickCraftTests.terminal().defaultBlockState(), 3);
        level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
        Object r = remotes();
        try {
            ItemStack stack = remote(1);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            p.setShiftKeyDown(true);
            p.gameMode.useItemOn(p, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(term), Direction.UP, term, false));
            check(h, p.getMainHandItem().has(net.minecraft.core.component.DataComponents.CUSTOM_DATA), "the link failed: " + a.chat());
            call(r, "tick");
            call(r, "tick");
            check(h, !ticketed(level, term).contains(fresh), "the chunk past the border is ticketed before anything is there: " + ticketed(level, term));
            int settled = scans(level, term);
            ItemStack more = new ItemStack(Items.BARREL);
            p.setItemInHand(InteractionHand.MAIN_HAND, more);
            p.gameMode.useItemOn(p, level, more, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(barrel), Direction.EAST, barrel, false));
            p.setShiftKeyDown(false);
            check(h, level.getBlockState(placed).is(Blocks.BARREL), "the barrel wasn't placed: " + level.getBlockState(placed));
            call(r, "tick");
            check(h, scans(level, term) > settled, "placing storage by the network didn't read it again");
            check(h, ticketed(level, term).contains(fresh), "the new chunk isn't ticketed on the next pass: " + ticketed(level, term));
        } finally {
            p.setShiftKeyDown(false);
            level.setBlock(placed, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(barrel, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(term, Blocks.AIR.defaultBlockState(), 3); // the link dies, its tickets go
        }
        check(h, ticketed(level, term).isEmpty(), "breaking the terminal kept its tickets");
        a.leave();
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    static Object lastSeen(ServerPlayer p) {
        try {
            Object module = module();
            Field open = module.getClass().getDeclaredField("open");
            open.setAccessible(true);
            Object view = ((Map<java.util.UUID, Object>) open.get(module)).get(p.getUUID());
            if (view == null) throw new IllegalStateException("no storage window for " + p.getGameProfile().name());
            Field seen = Class.forName("net.axther.aftrhrs.feature.storage.View").getDeclaredField("lastSeen");
            seen.setAccessible(true);
            return seen.get(view);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean shows(ServerPlayer p, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 45; i++) if (p.containerMenu.slots.get(i).getItem().is(item)) return true;
        return false;
    }

    /**
     * A put through terminal A redraws the window on terminal B, which reaches the same chest through a connector,
     * right after (not on the refresh timer); the window on terminal C, with a chest of its own, is left alone.
     */
    @GameTest(maxTicks = 100)
    public void storageClickRedrawsOnlyWindowsOnTheSameStorage(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmDrawA");
        BlockPos conn = new BlockPos(3, 1, 3), termB = new BlockPos(3, 1, 4), termC = new BlockPos(0, 1, 5), chestC = new BlockPos(1, 1, 5);
        h.setBlock(conn, BuiltInRegistries.BLOCK.getValue(AftrHrs.id("feather_inventory_connector")));
        h.setBlock(termB, QuickCraftTests.terminal());
        h.setBlock(termC, QuickCraftTests.terminal());
        h.setBlock(chestC, Blocks.CHEST);
        Bots.Bot b = Bots.join(h, Bots.name("rmDrawB")), c = Bots.join(h, Bots.name("rmDrawC"));
        b.player.getInventory().clearContent();
        c.player.getInventory().clearContent();
        ServerPlayer p = a.player;
        p.getInventory().setItem(9, new ItemStack(Items.EMERALD, 20));
        h.useBlock(TERMINAL, p);
        h.useBlock(termB, b.player);
        h.useBlock(termC, c.player);
        check(h, chestWindow(p) && chestWindow(b.player) && chestWindow(c.player), "a terminal didn't open");
        try {
            // Something no window hears about: C's chest gets a diamond (C's window shows it only once it redraws).
            h.getBlockEntity(chestC, net.minecraft.world.level.block.entity.ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND));
            Object seenB = lastSeen(b.player), seenC = lastSeen(c.player);
            int slot = -1;
            for (int i = 54; i < p.containerMenu.slots.size(); i++) {
                if (p.containerMenu.slots.get(i).container == p.getInventory() && p.containerMenu.slots.get(i).getContainerSlot() == 9) slot = i;
            }
            p.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, p);
            check(h, shows(p, Items.EMERALD), "A's own window doesn't show the emeralds it put in");
            Object seenA = lastSeen(p);
            check(h, !shows(b.player, Items.EMERALD), "B redrew inside A's click");
            call(module(), "flushRefresh"); // what the next tick does
            check(h, lastSeen(b.player) != seenB && shows(b.player, Items.EMERALD), "B (same chest through a connector) wasn't redrawn");
            check(h, lastSeen(c.player) == seenC && !shows(c.player, Items.DIAMOND), "C (a chest of its own) was redrawn");
            check(h, lastSeen(p) == seenA, "A was redrawn again after drawing itself");
        } finally {
            p.closeContainer();
            b.player.closeContainer();
            c.player.closeContainer();
            b.leave();
            c.leave();
        }
        a.leave();
        h.succeed();
    }

    /** At most max-links-per-player links: one more is refused (the remote stays unlinked), relinking is fine. */
    @GameTest(maxTicks = 100, environment = "aftrhrs_gametest:storage_isolated")
    public void remoteLinksPerOwnerCap(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmMany", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = a.player;
        Object r = remotes();
        int was = intField(r, "maxLinksPerPlayer");
        intField(r, "maxLinksPerPlayer", 2);
        try {
            link(h, p, remote(1));
            ItemStack one = p.getMainHandItem();
            link(h, p, remote(1));
            a.clear();
            link(h, p, remote(1));
            check(h, !p.getMainHandItem().has(net.minecraft.core.component.DataComponents.CUSTOM_DATA) && a.heard("already have"),
                    "a third link past the cap of 2: " + a.chat());
            a.clear();
            link(h, p, one);
            check(h, a.heard("Linked to the terminal"), "relinking a remote at the cap was refused: " + a.chat());
        } finally {
            intField(r, "maxLinksPerPlayer", was);
        }
        a.leave();
        h.succeed();
    }

    // ------------------------------------------------------------------ who changed what

    static ListTag log(BlockEntity be, String key) {
        CompoundTag t = be.getAttached(StorageLog.LOG);
        return t == null ? new ListTag() : t.getListOrEmpty(key);
    }

    static String describe(ListTag list) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompoundOrEmpty(i);
            b.append(c.getStringOr("w", "?")).append(' ').append(c.getIntOr("d", 0)).append("; ");
        }
        return b.toString();
    }

    /**
     * Terminal puts and takes are written down with the player's name and signed amounts; a hopper feeding the chest
     * is "Hopper"; the log is saved with the chest and read back the same.
     */
    @GameTest(maxTicks = 200)
    public void storageLogRecordsTerminalHopperAndSurvivesSave(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmLog");
        ServerPlayer p = a.player;
        String name = p.getGameProfile().name();
        p.getInventory().setItem(9, new ItemStack(Items.EMERALD, 20));
        h.useBlock(TERMINAL, p);
        check(h, chestWindow(p), "the terminal didn't open");
        // Shift-click the emeralds from the inventory into the network (slot 9 is the first menu slot after the 54).
        int slot = -1;
        for (int i = 54; i < p.containerMenu.slots.size(); i++) {
            if (p.containerMenu.slots.get(i).container == p.getInventory() && p.containerMenu.slots.get(i).getContainerSlot() == 9) slot = i;
        }
        p.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, p);
        BlockEntity chest = h.getBlockEntity(CHEST, BlockEntity.class);
        ListTag in = log(chest, "minecraft:emerald");
        check(h, in.size() >= 1 && in.getCompoundOrEmpty(0).getStringOr("w", "").equals(name) && in.getCompoundOrEmpty(0).getIntOr("d", 0) == 20,
                "the put isn't logged as +20 by " + name + ": " + describe(in));
        // Take a stack back out (left-click on the emerald icon: onto the cursor).
        int icon = QuickCraftTests.icon(p, Items.EMERALD);
        check(h, icon >= 0, "no emerald icon");
        p.containerMenu.clicked(icon, 1, ContainerInput.PICKUP, p); // right: half (10)
        ListTag out = log(chest, "minecraft:emerald");
        check(h, out.getCompoundOrEmpty(0).getIntOr("d", 0) == -10 && out.getCompoundOrEmpty(0).getStringOr("w", "").equals(name),
                "the take isn't logged as -10 by " + name + ": " + describe(out));
        p.closeContainer();

        // A hopper above the (watched) chest pushes iron in.
        BlockPos hopper = CHEST.above();
        h.setBlock(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        HopperBlockEntity hb = h.getBlockEntity(hopper, HopperBlockEntity.class);
        hb.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        h.runAfterDelay(40, () -> {
            ListTag iron = log(chest, "minecraft:iron_ingot");
            check(h, iron.size() >= 1 && iron.getCompoundOrEmpty(0).getStringOr("w", "").equals("Hopper") && iron.getCompoundOrEmpty(0).getIntOr("d", 0) > 0,
                    "the hopper's iron isn't logged as Hopper: " + describe(iron));
            // Save the chest and load it into a fresh block entity: the log comes back.
            var reg = h.getLevel().registryAccess();
            CompoundTag saved = chest.saveWithFullMetadata(reg);
            BlockEntity loaded = BlockEntity.loadStatic(chest.getBlockPos(), chest.getBlockState(), saved, reg);
            check(h, loaded != null, "the saved chest didn't load");
            ListTag back = log(loaded, "minecraft:emerald");
            check(h, describe(back).equals(describe(log(chest, "minecraft:emerald"))) && back.size() >= 2,
                    "the log changed through save and load: " + describe(back) + " vs " + describe(log(chest, "minecraft:emerald")));
            a.leave();
            h.succeed();
        });
    }

    /** The time text: the viewer's zone with its name, "ago" steps, signed amounts. */
    @GameTest
    public void storageTimeText(GameTestHelper h) {
        long t = java.time.Instant.parse("2026-11-21T08:04:00Z").toEpochMilli();
        String ny = StorageTime.when(t, ZoneId.of("America/New_York"));
        check(h, ny.equals("Nov 21, 3:04 AM EST"), "New York: " + ny);
        String tokyo = StorageTime.when(t, ZoneId.of("Asia/Tokyo"));
        check(h, tokyo.equals("Nov 21, 5:04 PM JST"), "Tokyo: " + tokyo);
        check(h, StorageTime.ago(t, t + 30_000).equals("just now") && StorageTime.ago(t, t + 5 * 60_000).equals("5m ago")
                && StorageTime.ago(t, t + 2 * 3_600_000).equals("2h ago") && StorageTime.ago(t, t + 3 * 86_400_000L).equals("3d ago"), "ago steps");
        check(h, StorageTime.signed(64).equals("+64") && StorageTime.signed(-3).equals("−3"), "signed amounts");
        h.succeed();
    }

    // ------------------------------------------------------------------ idle windows (refresh pass)

    @SuppressWarnings("unchecked")
    static Object view(ServerPlayer p) {
        try {
            Object module = module();
            Field open = module.getClass().getDeclaredField("open");
            open.setAccessible(true);
            Object view = ((Map<java.util.UUID, Object>) open.get(module)).get(p.getUUID());
            if (view == null) throw new IllegalStateException("no storage window for " + p.getGameProfile().name());
            return view;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** How many times this player's storage window was drawn. */
    static int renders(ServerPlayer p) {
        try {
            Field f = Class.forName("net.axther.aftrhrs.feature.storage.View").getDeclaredField("renders");
            f.setAccessible(true);
            return f.getInt(view(p));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One refresh pass of the storage windows (what the refresh-ticks timer runs). */
    static void refreshPass() {
        call(module(), "renderAll");
    }

    /**
     * The refresh pass leaves idle windows alone: an open window whose storage didn't change is not drawn again (its
     * network not read); a chest of its network changed by setItem (no window involved) shows on the next pass; a
     * change in another, watched network redraws only the window on that network.
     */
    @GameTest(maxTicks = 100)
    public void storageIdleWindowIsNotRedrawn(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmIdleA", new ItemStack(Items.DIAMOND, 5));
        BlockPos termC = new BlockPos(0, 1, 5), chestC = new BlockPos(1, 1, 5);
        h.setBlock(termC, QuickCraftTests.terminal());
        h.setBlock(chestC, Blocks.CHEST);
        Bots.Bot c = Bots.join(h, Bots.name("rmIdleC"));
        c.player.getInventory().clearContent();
        ServerPlayer p = a.player;
        h.useBlock(TERMINAL, p);
        h.useBlock(termC, c.player);
        check(h, chestWindow(p) && chestWindow(c.player), "a terminal didn't open");
        try {
            refreshPass(); // whatever the setup changed
            int drawn = renders(p), drawnC = renders(c.player);
            Object seen = lastSeen(p);
            refreshPass();
            refreshPass();
            check(h, renders(p) == drawn && lastSeen(p) == seen, "an idle window was drawn again: " + drawn + " -> " + renders(p));
            check(h, renders(c.player) == drawnC, "the other idle window was drawn again: " + drawnC + " -> " + renders(c.player));

            // C's chest changes by hand (no window): only C's window redraws, and shows it.
            h.getBlockEntity(chestC, net.minecraft.world.level.block.entity.ChestBlockEntity.class).setItem(0, new ItemStack(Items.EMERALD, 3));
            refreshPass();
            check(h, renders(c.player) == drawnC + 1 && shows(c.player, Items.EMERALD), "C's window didn't show its chest's change on the next pass");
            check(h, renders(p) == drawn, "A's window was redrawn for a change in another network");

            // A's chest changes the same way: A's window shows it on the next pass.
            h.getBlockEntity(CHEST, net.minecraft.world.level.block.entity.ChestBlockEntity.class).setItem(5, new ItemStack(Items.GOLD_INGOT, 2));
            refreshPass();
            check(h, renders(p) == drawn + 1 && shows(p, Items.GOLD_INGOT), "A's window didn't show its chest's change on the next pass");
        } finally {
            p.closeContainer();
            c.player.closeContainer();
            c.leave();
        }
        a.leave();
        h.succeed();
    }

    // ------------------------------------------------------------------ keep-ticking / offline release

    /** How many tickets of this type (by registry path) sit on this chunk. */
    static int ticketsAt(ServerLevel level, long chunk, String type) {
        try {
            Field f = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("ticketStorage");
            f.setAccessible(true);
            Object ts = f.get(level.getChunkSource());
            Field tf = net.minecraft.world.level.TicketStorage.class.getDeclaredField("tickets");
            tf.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.List<net.minecraft.server.level.Ticket>>) tf.get(ts);
            int n = 0;
            for (var t : map.getOrDefault(chunk, java.util.List.of())) {
                var key = BuiltInRegistries.TICKET_TYPE.getKey(t.getType());
                if (key != null && key.getPath().equals(type)) n++;
            }
            return n;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void boolField(Object target, String name, boolean value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object ticketTypeName(ServerLevel level, BlockPos abs) {
        Object t = call(remotes(), "ticketTypeOf", level, abs);
        return t == null ? null : BuiltInRegistries.TICKET_TYPE.getKey((net.minecraft.server.level.TicketType) t).getPath();
    }

    /**
     * keep-ticking false: a linked network's chunks get the loading-only ticket; switching back to true moves them to
     * the ticking ticket on the next pass and leaves no loading-only ticket behind. (Own batch: a server-wide setting.)
     */
    @GameTest(maxTicks = 100, environment = "aftrhrs_gametest:storage_isolated")
    public void remoteKeepTickingOffUsesTheLoadingTicket(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmTick", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = a.player;
        ServerLevel level = h.getLevel();
        BlockPos abs = h.absolutePos(TERMINAL);
        long home = ChunkPos.pack(abs);
        Object r = remotes();
        try {
            boolField(r, "keepTicking", false);
            link(h, p, remote(1));
            check(h, ticketed(level, abs).contains(home), "the network isn't ticketed: " + ticketed(level, abs));
            check(h, "storage_remote_loading".equals(ticketTypeName(level, abs)), "keep-ticking false used " + ticketTypeName(level, abs));
            check(h, ticketsAt(level, home, "storage_remote_loading") == 1 && ticketsAt(level, home, "storage_remote") == 0,
                    "the chunk's tickets with keep-ticking false: loading " + ticketsAt(level, home, "storage_remote_loading") + ", ticking "
                            + ticketsAt(level, home, "storage_remote"));
            boolField(r, "keepTicking", true);
            call(r, "tick");
            check(h, "storage_remote".equals(ticketTypeName(level, abs)), "back to keep-ticking true, the network holds " + ticketTypeName(level, abs));
            check(h, ticketsAt(level, home, "storage_remote_loading") == 0 && ticketsAt(level, home, "storage_remote") == 1,
                    "after switching back: loading " + ticketsAt(level, home, "storage_remote_loading") + ", ticking " + ticketsAt(level, home, "storage_remote"));
        } finally {
            boolField(r, "keepTicking", true);
        }
        a.leave();
        h.succeed();
    }

    /**
     * offline-release-hours: a network whose only remote owner is online keeps its tickets however long ago the remote
     * was used; once that owner is offline (and the remote unused for longer than the setting) the next pass lets it go.
     */
    @GameTest(maxTicks = 100, environment = "aftrhrs_gametest:storage_isolated")
    public void remoteOfflineOwnerNetworkIsReleased(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmAway", new ItemStack(Items.DIAMOND, 5));
        ServerPlayer p = a.player;
        ServerLevel level = h.getLevel();
        BlockPos abs = h.absolutePos(TERMINAL);
        Object r = remotes();
        link(h, p, remote(1));
        check(h, !ticketed(level, abs).isEmpty(), "the network isn't ticketed");
        var data = p.getMainHandItem().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        String id = data == null ? "" : data.copyTag().getCompoundOrEmpty("feather:remote").getStringOr("id", "");
        check(h, !id.isEmpty(), "the remote has no link id");
        try {
            Field hours = r.getClass().getDeclaredField("offlineReleaseHours");
            hours.setAccessible(true);
            hours.setDouble(r, 1.0);
            Field linksF = r.getClass().getDeclaredField("links");
            linksF.setAccessible(true);
            Object d = ((Map<?, ?>) linksF.get(r)).get(id);
            Field seen = d.getClass().getDeclaredField("seen");
            seen.setAccessible(true);
            seen.setLong(d, System.currentTimeMillis() - 2 * 3_600_000L);
            call(r, "tick");
            check(h, !ticketed(level, abs).isEmpty(), "an online owner's network was let go");
            a.leave();
            seen.setLong(d, System.currentTimeMillis() - 2 * 3_600_000L);
            call(r, "tick");
            check(h, ticketed(level, abs).isEmpty(), "an offline owner's long unused network kept its tickets: " + ticketed(level, abs));
            hours.setDouble(r, 0);
            call(r, "tick");
            check(h, !ticketed(level, abs).isEmpty(), "with offline-release-hours 0 the network wasn't ticketed again");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                Field hours = r.getClass().getDeclaredField("offlineReleaseHours");
                hours.setAccessible(true);
                hours.setDouble(r, 0);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        h.succeed();
    }

    /** Quick Craft from a terminal (chest window): a change in the player's own inventory shows on the next refresh pass. */
    @GameTest(maxTicks = 100)
    public void storageCraftViewFollowsTheInventory(GameTestHelper h) {
        Bots.Bot a = QuickCraftTests.rig(h, "rmIdleQ");
        ServerPlayer p = a.player;
        h.useBlock(TERMINAL, p);
        check(h, chestWindow(p), "the terminal didn't open");
        p.containerMenu.clicked(QuickCraftTests.CRAFT_BUTTON, 0, ContainerInput.PICKUP, p);
        h.runAfterDelay(3, () -> {
            check(h, chestWindow(p) && QuickCraftTests.icon(p, Items.STICK) < 0, "Quick Craft didn't open, or lists sticks from nothing");
            refreshPass();
            int drawn = renders(p);
            refreshPass();
            check(h, renders(p) == drawn, "an idle Quick Craft window was drawn again");
            p.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 4));
            refreshPass();
            check(h, renders(p) == drawn + 1 && QuickCraftTests.iconAnyPage(p, Items.STICK) >= 0, "planks in the inventory didn't list sticks on the next pass");
            p.closeContainer();
            a.leave();
            h.succeed();
        });
    }
}
