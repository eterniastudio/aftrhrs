package net.axther.aftrhrs.gametest.core;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Gui;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.kyori.adventure.text.Component;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Feather core: menus (dialogs and chests), profiles, permissions, the command bridge and reload. */
public class CoreTests {
    static net.minecraft.network.chat.Component lit(String s) {
        // The XML report can't hold control characters (the fuzz inputs have some).
        return net.minecraft.network.chat.Component.literal(s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "?"));
    }

    static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) throw h.assertionException(lit(what));
    }

    static void noErrors(GameTestHelper h, LogWatch log, String what) {
        List<String> e = log.errors();
        if (!e.isEmpty()) throw h.assertionException(lit(what + " logged " + e.size() + " errors:\n" + String.join("\n", e.stream().distinct().limit(25).toList())));
    }

    // ------------------------------------------------------------------ dialogs

    /** Every button of the hub and everything reachable from it (4 levels), as a player and as an op. */
    /** Own batch: the crawl claims land, sets homes, opens trades... */
    @GameTest(maxTicks = 1200, environment = "aftrhrs_gametest:core_isolated")
    public void hubEveryButtonWorks(GameTestHelper h) {
        LogWatch log = new LogWatch();
        List<String> failures = new ArrayList<>();
        for (boolean op : new boolean[]{false, true}) {
            Bots.Bot a = Bots.join(h, Bots.name(op ? "hubOp" : "hub"));
            Bots.Bot other = Bots.join(h, Bots.name("hubB"));
            a.op(op);
            a.player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            MenuCrawler c = new MenuCrawler(a, b -> Feather.get().openHub(b.player), 4, 700);
            c.crawl();
            if (c.menus.size() < 3) failures.add("the hub crawl reached only " + c.menus);
            for (String p : a.problems()) failures.add(p);
            for (String e : log.errors()) failures.add((op ? "op " : "") + e);
            log.clear();
            net.axther.aftrhrs.AftrHrs.LOG.info("hub crawl ({}): {} menus, {} clicks", op ? "op" : "player", c.menus.size(), c.clicked.size());
            a.abandonClaims();
            a.op(false);
            a.leave();
            other.leave();
        }
        h.runAfterDelay(40, () -> {
            for (String e : log.errors()) failures.add("later: " + e);
            log.close();
            if (!failures.isEmpty()) throw h.assertionException(lit(String.join("\n", failures.stream().distinct().limit(30).toList())));
            h.succeed();
        });
    }

    /** A dialog button works once; forged, stale and out-of-range clicks do nothing. Text input is sanitized. */
    @GameTest
    public void dialogClicksAreValidated(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("dlg"));
        AtomicInteger clicks = new AtomicInteger();
        String[] got = new String[1];
        Feather.get().dialogs().menu(Component.text("t"))
                .input(Dialogs.Input.text("name", Component.text("Name")).maxLength(8))
                .button(Component.text("go"), null, 100, (p, r) -> {
                    clicks.incrementAndGet();
                    got[0] = r.getText("name");
                })
                .open(a.player);
        var d = a.lastDialog();
        check(h, d != null, "no dialog was sent");
        var b = (net.minecraft.server.dialog.action.CustomAll) MenuCrawler.buttons(d).get(0).action().orElseThrow();
        CompoundTag tag = b.additions().orElseThrow().copy();
        CompoundTag forged = tag.copy();
        forged.putInt("aftrhrs:menu", tag.getIntOr("aftrhrs:menu", 0) + 1000);
        Feather.get().dialogs().handleClick(a.player, Dialogs.CLICK_ID, Optional.of(forged));
        check(h, clicks.get() == 0, "a forged click ran the button");
        tag.putString("name", "ab\u0007cdefghijklmnop");
        Feather.get().dialogs().handleClick(a.player, Dialogs.CLICK_ID, Optional.of(tag));
        Feather.get().dialogs().handleClick(a.player, Dialogs.CLICK_ID, Optional.of(tag));
        CompoundTag badIndex = tag.copy();
        badIndex.putInt("aftrhrs:button", 99);
        Feather.get().dialogs().handleClick(a.player, Dialogs.CLICK_ID, Optional.of(badIndex));
        check(h, clicks.get() == 1, "the button ran " + clicks.get() + " times (expected once)");
        check(h, "abcdefgh".equals(got[0]), "text input not sanitized: " + got[0]);
        // Another player can't click someone else's menu.
        Bots.Bot b2 = Bots.join(h, Bots.name("dlgB"));
        Feather.get().dialogs().menu(Component.text("t")).button(Component.text("go"), null, 100, p -> clicks.incrementAndGet()).open(a.player);
        var tag2 = ((net.minecraft.server.dialog.action.CustomAll) MenuCrawler.buttons(a.lastDialog()).get(0).action().orElseThrow()).additions().orElseThrow();
        Feather.get().dialogs().handleClick(b2.player, Dialogs.CLICK_ID, Optional.of(tag2));
        check(h, clicks.get() == 1, "another player clicked someone else's menu");
        a.leave();
        b2.leave();
        h.succeed();
    }

    // ------------------------------------------------------------------ chest menus

    @GameTest
    public void guiLockedSlotsNeverMove(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("gui"));
        var p = a.player;
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        Gui gui = Gui.create(3, Component.text("test"));
        gui.set(0, new ItemStack(Items.DIAMOND, 5), c -> handled.incrementAndGet());
        gui.set(2, new ItemStack(Items.EMERALD, 3));
        gui.unlock(1, 3);
        gui.onClose(x -> closed.incrementAndGet());
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
        p.getInventory().setItem(1, new ItemStack(Items.STONE, 10));
        gui.open(p);
        var menu = p.containerMenu;
        check(h, Gui.of(p) == gui, "the gui did not open");
        int size = gui.size();
        int invHotbar0 = size + 27; // first hotbar slot in the menu
        for (int slot : new int[]{0, 2}) {
            for (ContainerInput in : ContainerInput.values()) {
                for (int button : new int[]{0, 1, 2, 40}) {
                    if (in == ContainerInput.QUICK_CRAFT) continue;
                    menu.clicked(slot, button, in, p);
                }
            }
        }
        // Drag over a locked and an unlocked slot with a carried stack.
        menu.setCarried(new ItemStack(Items.GOLD_INGOT, 4));
        menu.clicked(-999, 0, ContainerInput.QUICK_CRAFT, p);
        menu.clicked(0, 1, ContainerInput.QUICK_CRAFT, p);
        menu.clicked(1, 1, ContainerInput.QUICK_CRAFT, p);
        menu.clicked(-999, 2, ContainerInput.QUICK_CRAFT, p);
        // Double-click gathering diamonds must not pull them from the locked slot.
        menu.setCarried(new ItemStack(Items.DIAMOND, 1));
        menu.clicked(invHotbar0, 0, ContainerInput.PICKUP_ALL, p);
        ItemStack carried = menu.getCarried();
        menu.setCarried(ItemStack.EMPTY);
        // Shift-click stone from the hotbar: only an unlocked slot may take it.
        menu.clicked(invHotbar0 + 1, 0, ContainerInput.QUICK_MOVE, p);
        check(h, gui.get(0).is(Items.DIAMOND) && gui.get(0).getCount() == 5, "locked slot 0 changed: " + gui.get(0));
        check(h, gui.get(2).is(Items.EMERALD) && gui.get(2).getCount() == 3, "locked slot 2 changed: " + gui.get(2));
        check(h, carried.getCount() <= 8, "double-click gathered from a locked slot: " + carried);
        int diamonds = p.getInventory().countItem(Items.DIAMOND);
        int emeralds = p.getInventory().countItem(Items.EMERALD);
        check(h, emeralds == 0, "an emerald icon reached the inventory");
        check(h, gui.get(1).is(Items.STONE) || gui.get(3).is(Items.STONE) || gui.get(1).is(Items.GOLD_INGOT),
                "nothing reached the unlocked slots: " + gui.get(1) + " / " + gui.get(3));
        int finalDiamonds = diamonds;
        h.runAfterDelay(2, () -> {
            check(h, handled.get() > 0, "the locked slot's handler never ran");
            a.leave();
            check(h, closed.get() == 1, "onClose ran " + closed.get() + " times on quit");
            check(h, finalDiamonds <= 8, "diamond icons were duplicated into the inventory: " + finalDiamonds);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ profiles

    /** Changes saved to an offline player's file right before it's read again are not lost (two quick staff edits). */
    @GameTest
    public void offlineProfileEditsAreNotLost(GameTestHelper h) {
        Profiles profiles = Feather.get().profiles();
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            Profiles.Profile p = profiles.load(id);
            check(h, p.warnings.size() == i, "edit " + i + " saw " + p.warnings.size() + " warnings (an earlier save was lost)");
            p.warnings.add(new Profiles.Warning(System.currentTimeMillis(), "Console", "w" + i));
            p.lastName = "offline" + i;
            profiles.save(p);
        }
        h.succeed();
    }

    /** Homes, nickname and toggles survive quitting and coming back (read back from disk). */
    @GameTest(maxTicks = 200)
    public void profileSurvivesRelog(GameTestHelper h) {
        String name = Bots.name("rel");
        Bots.Bot a = Bots.join(h, name);
        UUID id = a.id();
        a.cmd("sethome base");
        var p = Feather.get().profiles().get(a.player);
        check(h, p.homes.containsKey("base"), "/sethome base did not set a home: " + a.chat());
        a.cmd("tptoggle");
        boolean off = p.tpToggledOff;
        a.leave();
        h.runAfterDelay(40, () -> {
            check(h, Feather.get().player(id) == null, "still online");
            Bots.Bot back = Bots.join(h, name);
            var q = Feather.get().profiles().get(back.player);
            check(h, q.homes.containsKey("base"), "the home was lost on relog");
            check(h, q.tpToggledOff == off, "the teleport toggle was lost on relog");
            check(h, !q.firstJoin, "a returning player counted as a first join");
            back.leave();
            h.succeed();
        });
    }

    /** Quitting and rejoining at once keeps the changes made in the first session. */
    @GameTest(maxTicks = 100)
    public void quickRejoinKeepsData(GameTestHelper h) {
        String name = Bots.name("qr");
        Bots.Bot a = Bots.join(h, name);
        a.cmd("sethome quick");
        check(h, Feather.get().profiles().get(a.player).homes.containsKey("quick"), "/sethome quick failed: " + a.chat());
        a.leave();
        Bots.Bot b = Bots.join(h, name);
        check(h, Feather.get().profiles().get(b.player).homes.containsKey("quick"), "a home set just before a quick rejoin was lost");
        b.leave();
        h.succeed();
    }

    // ------------------------------------------------------------------ permissions

    @GameTest
    public void permissionDefaults(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("perm"));
        var p = a.player;
        check(h, Perms.has(p, "feather.tpa"), "players lack feather.tpa");
        check(h, Perms.has(p, "feather.player"), "players lack feather.player");
        check(h, !Perms.has(p, "feather.vanish"), "players have feather.vanish");
        check(h, !Perms.has(p, "feather.admin"), "players have feather.admin");
        check(h, !Perms.has(p, "feather.ban"), "players have feather.ban");
        check(h, !Perms.has(p, "feather.some.unknown.node"), "players have an unknown node");
        a.op(true);
        check(h, Perms.has(p, "feather.vanish") && Perms.has(p, "feather.admin") && Perms.has(p, "feather.vanish.see"), "ops lack staff nodes");
        a.op(false);
        check(h, !Perms.has(p, "feather.admin"), "deop kept feather.admin");
        a.leave();
        // With a permissions mod: group nodes grant their children, and a negated group takes them away (Bukkit).
        try {
            var m = Perms.class.getDeclaredMethod("resolve", String.class, java.util.function.Function.class, boolean.class, int.class);
            m.setAccessible(true);
            java.util.function.Function<String, net.fabricmc.fabric.api.util.TriState> staff =
                    n -> n.equals("feather.staff") ? net.fabricmc.fabric.api.util.TriState.TRUE : net.fabricmc.fabric.api.util.TriState.DEFAULT;
            java.util.function.Function<String, net.fabricmc.fabric.api.util.TriState> noPlayer =
                    n -> n.equals("feather.player") ? net.fabricmc.fabric.api.util.TriState.FALSE : net.fabricmc.fabric.api.util.TriState.DEFAULT;
            check(h, (boolean) m.invoke(null, "feather.vanish.see", staff, false, 0), "feather.staff did not grant feather.vanish.see");
            check(h, (boolean) m.invoke(null, "feather.tpa", staff, false, 0), "feather.staff did not grant feather.tpa (through feather.player)");
            check(h, !(boolean) m.invoke(null, "feather.tpa", noPlayer, false, 0), "a negated feather.player still granted feather.tpa");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ commands

    /** Feather's /msg, /list, /kick ... replaced vanilla's, and a command whose module is off falls back to vanilla. */
    @GameTest
    @SuppressWarnings("unchecked")
    public void commandBridgeTakesOverAndFallsBack(GameTestHelper h) throws Exception {
        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        for (String label : List.of("msg", "tell", "w", "list", "kick", "ban", "r", "feather", "menu")) {
            var node = dispatcher.getRoot().getChild(label);
            check(h, node != null, "/" + label + " is missing");
            check(h, CommandBridge.isBound(CommandBridge.nameOf(label)), "/" + label + " is not bound");
            check(h, node.getCommand() != null && node.getCommand().getClass().getName().startsWith("net.axther"), "/" + label + " is still vanilla's");
        }
        // Module off: a fresh tree keeps vanilla's /list.
        Field f = CommandBridge.class.getDeclaredField("BOUND");
        f.setAccessible(true);
        Map<String, FeatherCommand> bound = (Map<String, FeatherCommand>) f.get(null);
        FeatherCommand list = bound.remove("list");
        try {
            var fresh = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
            fresh.register(net.minecraft.commands.Commands.literal("list").executes(c -> 7));
            CommandBridge.register(fresh);
            int r = fresh.getRoot().getChild("list").getCommand().run(null);
            check(h, r == 7, "an unbound /list replaced vanilla's");
            check(h, fresh.getRoot().getChild("homes") != null, "Feather commands missing from a fresh tree");
        } finally {
            bound.put("list", list);
        }
        h.succeed();
    }

    /** /feather reload (op and console) reloads config, messages and every module without errors. */
    @GameTest(maxTicks = 100)
    public void featherReload(GameTestHelper h) {
        LogWatch log = new LogWatch();
        Bots.Bot a = Bots.join(h, Bots.name("rl"));
        a.cmd("feather reload");
        check(h, !a.chat().isEmpty() && !Perms.has(a.player, "feather.admin"), "no answer to /feather reload");
        a.op(true);
        a.cmd("feather reload");
        String said = String.join(" ", a.chat());
        Bots.Console console = new Bots.Console();
        console.cmd(h.getLevel(), "feather reload");
        a.op(false);
        a.leave();
        h.runAfterDelay(5, () -> {
            log.close();
            noErrors(h, log, "/feather reload");
            check(h, said.toLowerCase().contains("reload"), "op /feather reload said: " + said);
            check(h, !console.out.isEmpty(), "console /feather reload said nothing");
            h.succeed();
        });
    }

    /**
     * A reload reads the same values (config files are parsed once per reload and shared within it, the bundled
     * defaults once per JVM); outside a reload every read goes to the disk again.
     */
    @GameTest(maxTicks = 40)
    public void featherReloadReadsTheSameConfig(GameTestHelper h) {
        Feather f = Feather.get();
        Yaml before = f.getConfig();
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String k : before.getKeys(true)) if (!before.isConfigurationSection(k)) values.put(k, before.get(k));
        Yaml tools = f.loadResourceConfig("tools.yml");
        Map<String, Object> toolValues = new java.util.LinkedHashMap<>();
        for (String k : tools.getKeys(true)) if (!tools.isConfigurationSection(k)) toolValues.put(k, tools.get(k));
        f.reload();
        Yaml after = f.getConfig();
        check(h, after != before, "the reload kept the old config object");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            check(h, java.util.Objects.equals(after.get(e.getKey()), e.getValue()), "config.yml " + e.getKey() + " was " + e.getValue() + ", after the reload " + after.get(e.getKey()));
        }
        check(h, after.getKeys(true).size() == before.getKeys(true).size(), "the reload changed the config's keys");
        check(h, net.axther.aftrhrs.gametest.creatures.CT.get(f, "pass") == null, "the reload left its config cache behind");
        Yaml again = f.loadResourceConfig("tools.yml");
        check(h, again != f.loadResourceConfig("tools.yml"), "config reads outside a reload were cached");
        for (Map.Entry<String, Object> e : toolValues.entrySet()) {
            check(h, java.util.Objects.equals(again.get(e.getKey()), e.getValue()), "tools.yml " + e.getKey() + " changed across the reload");
        }
        check(h, Feather.bundled("tools.yml") == Feather.bundled("tools.yml") && again.getDefaults() == Feather.bundled("tools.yml"),
                "the bundled tools.yml was parsed again");
        check(h, !Feather.bundled("tools.yml").isSet("no.such.key") && Feather.bundled("tools.yml").getDefaults() == null, "isSet changed a shared document");
        h.succeed();
    }

    // ------------------------------------------------------------------ inventory snapshots

    static Object inspector() {
        var mod = Feather.get().module(net.axther.aftrhrs.feature.moderation.ModerationModule.class);
        return mod == null ? null : mod.inspector();
    }

    /** Moving an item to another slot changes the snapshot (the skip-unchanged hash is per slot), an unchanged one is kept. */
    @GameTest(maxTicks = 60)
    public void inspectorSnapshotFollowsSlotMoves(GameTestHelper h) {
        Object insp = inspector();
        check(h, insp != null, "moderation is off");
        check(h, (long) net.axther.aftrhrs.gametest.creatures.CT.get(insp, "periodTicks") > 0, "moderation.snapshot-minutes is off");
        Bots.Bot a = Bots.join(h, Bots.name("snap"));
        var p = a.player;
        var server = h.getLevel().getServer();
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        Profiles.Profile pr = Feather.get().profiles().get(p);
        net.axther.aftrhrs.gametest.creatures.CT.call(insp, "snapshot", p, pr, false);
        ItemStack[] first = net.axther.aftrhrs.feature.moderation.Inspector.decode(pr.snapshotInventory, server);
        check(h, first.length > 5 && first[0].is(Items.DIAMOND), "the first snapshot lacks the diamonds");
        String text = pr.snapshotInventory;
        net.axther.aftrhrs.gametest.creatures.CT.call(insp, "snapshot", p, pr, false);
        check(h, pr.snapshotInventory == text, "an unchanged inventory was encoded again");
        // The same stack in another slot: a hash of what is held (not where) would miss this.
        p.getInventory().setItem(0, ItemStack.EMPTY);
        p.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 3));
        pr.snapshotTime = 1;   // due for the autosave
        int steps = 0;
        while (pr.snapshotTime == 1 && steps++ <= Feather.get().onlinePlayers().size()) net.axther.aftrhrs.gametest.creatures.CT.call(insp, "autosaveStep");
        check(h, pr.snapshotTime != 1, "the autosave never reached the player");
        ItemStack[] after = net.axther.aftrhrs.feature.moderation.Inspector.decode(pr.snapshotInventory, server);
        check(h, after[5].is(Items.DIAMOND) && after[0].isEmpty(), "the snapshot after the move has " + after[0] + " in slot 0 and " + after[5] + " in slot 5");
        a.leave();
        h.succeed();
    }

    /** Quitting writes the profile once (it was twice), with a snapshot taken at the quit. */
    @GameTest(maxTicks = 60)
    @SuppressWarnings("unchecked")
    public void quitWritesTheProfileOnce(GameTestHelper h) throws Exception {
        check(h, inspector() != null, "moderation is off");
        Profiles profiles = Feather.get().profiles();
        Bots.Bot a = Bots.join(h, Bots.name("pq"));
        UUID id = a.id();
        a.player.getInventory().setItem(3, new ItemStack(Items.EMERALD, 7));
        AtomicInteger saves = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        Field hook = Profiles.class.getDeclaredField("onSave");
        hook.setAccessible(true);
        Object prev = hook.get(profiles);
        hook.set(profiles, (java.util.function.Consumer<Profiles.Profile>) pr -> {
            if (pr.id.equals(id)) saves.incrementAndGet();
        });
        try {
            a.leave();
        } finally {
            hook.set(profiles, prev);
        }
        check(h, saves.get() == 1, "quitting saved the profile " + saves.get() + " times");
        Profiles.Profile pr = profiles.load(id);
        check(h, pr.snapshotTime >= t0, "the quit took no snapshot");
        ItemStack[] inv = net.axther.aftrhrs.feature.moderation.Inspector.decode(pr.snapshotInventory, h.getLevel().getServer());
        check(h, inv.length > 3 && inv[3].is(Items.EMERALD) && inv[3].getCount() == 7, "the quit snapshot lacks the emeralds");
        h.runAfterDelay(10, () -> {
            Yaml file = Yaml.load(profiles.folder().resolve(id + ".yml"));
            check(h, file.getLong("snapshot.time", 0) >= t0, "the player file has no quit snapshot");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ chunk tickets (ServerChunkCacheTicketMixin)

    private static long unknownTickets(net.minecraft.world.level.TicketStorage tickets, long chunk) {
        return tickets.getTickets(chunk).stream().filter(t -> t.getType() == net.minecraft.server.level.TicketType.UNKNOWN).count();
    }

    /**
     * A main-thread getChunk cache miss on a chunk already loaded at that status adds no 1-tick UNKNOWN ticket (purged
     * again next tick); a miss on an unloaded chunk still gets one and still loads.
     */
    @GameTest(maxTicks = 200)
    public void loadedChunkCacheMissAddsNoUnknownTicket(GameTestHelper h) {
        var level = h.getLevel();
        var tickets = level.getDataStorage().computeIfAbsent(net.minecraft.world.level.TicketStorage.TYPE);
        net.minecraft.core.BlockPos o = h.absolutePos(net.minecraft.core.BlockPos.ZERO);
        int ox = o.getX() >> 4, oz = o.getZ() >> 4;
        // More loaded chunks than getChunk's 4-entry cache, so every call below is a cache miss.
        List<net.minecraft.world.level.ChunkPos> loaded = new ArrayList<>();
        for (int r = 0; r <= 4 && loaded.size() < 6; r++) {
            for (int dx = -r; dx <= r && loaded.size() < 6; dx++) {
                for (int dz = -r; dz <= r && loaded.size() < 6; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (level.getChunkSource().getChunkNow(ox + dx, oz + dz) != null) loaded.add(new net.minecraft.world.level.ChunkPos(ox + dx, oz + dz));
                }
            }
        }
        check(h, loaded.size() >= 5, "fewer than 5 loaded chunks around the test: " + loaded);
        long[] before = new long[loaded.size()];
        for (int i = 0; i < loaded.size(); i++) before[i] = unknownTickets(tickets, loaded.get(i).pack());
        for (int n = 0; n < 10; n++) {
            for (var c : loaded) {
                var chunk = level.getChunk(c.x(), c.z(), net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_REFERENCES);
                check(h, chunk != null, "a loaded chunk came back null: " + c);
            }
        }
        for (int i = 0; i < loaded.size(); i++) {
            long after = unknownTickets(tickets, loaded.get(i).pack());
            check(h, after <= before[i], "getChunk on loaded chunk " + loaded.get(i) + " added an UNKNOWN ticket ("
                    + before[i] + " -> " + after + "): " + tickets.getTickets(loaded.get(i).pack()));
        }
        // Not loaded: the vanilla ticket is kept and the chunk loads (generates) as before.
        int fx = ox + 2000, fz = oz + 2000;
        check(h, level.getChunkSource().getChunkNow(fx, fz) == null, "the far chunk was already loaded");
        var far = level.getChunk(fx, fz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        check(h, far != null && far.getPos().x() == fx && far.getPos().z() == fz, "getChunk(create) on an unloaded chunk returned " + far);
        check(h, unknownTickets(tickets, net.minecraft.world.level.ChunkPos.pack(fx, fz)) == 1, "the unloaded chunk got no UNKNOWN ticket: "
                + tickets.getTickets(net.minecraft.world.level.ChunkPos.pack(fx, fz)));
        h.succeed();
    }

    // ------------------------------------------------------------------ scheduled ticks (LevelTicksRecheckMixin)

    /**
     * An overdue block tick in a loaded chunk that is not ticking (loaded by a loading-only ticket, like the lazy ring
     * outside simulation distance) is not re-tested every tick: its container's next check moves 10-17 ticks ahead. Once
     * the chunk simulates, the tick runs within that back-off.
     */
    @GameTest(maxTicks = 400)
    public void frozenTickContainerBacksOff(GameTestHelper h) {
        var level = h.getLevel();
        net.minecraft.core.BlockPos o = h.absolutePos(net.minecraft.core.BlockPos.ZERO);
        var cp = new net.minecraft.world.level.ChunkPos((o.getX() >> 4) - 2500, (o.getZ() >> 4) + 7);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, cp, 0); // loads, never simulates
        // Generate the neighbours now (the walk-in part below needs them for the chunk to tick; ticks can run far
        // faster than world generation in a game test).
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(cp.x() + dx, cp.z() + dz);
        check(h, level.getChunk(cp.x(), cp.z()) != null, "the far chunk did not load");
        check(h, !level.isPositionTickingWithEntitiesLoaded(cp.pack()), "the loading-only chunk ticks");
        net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(cp.getMinBlockX() + 3, 70, cp.getMinBlockZ() + 3);
        long now = level.getGameTime();
        level.scheduleTick(at, net.minecraft.world.level.block.Blocks.STONE, 1);
        h.runAfterDelay(4, () -> {
            long next;
            try {
                Field f = net.minecraft.world.ticks.LevelTicks.class.getDeclaredField("nextTickForContainer");
                f.setAccessible(true);
                next = ((it.unimi.dsi.fastutil.longs.Long2LongMap) f.get(level.getBlockTicks())).get(cp.pack());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            check(h, next >= now + 1 + 10, "the overdue container is re-checked every tick (next check " + next + ", scheduled for " + (now + 1)
                    + ", now " + level.getGameTime() + ")");
            check(h, next <= level.getGameTime() + 17, "the back-off is longer than 17 ticks: " + next + " at " + level.getGameTime());
            check(h, level.getBlockTicks().hasScheduledTick(at, net.minecraft.world.level.block.Blocks.STONE), "the frozen tick was lost");
            // Walking in: once the chunk simulates (an entity-ticking simulation ticket, the level /forceload gives)
            // the overdue tick runs within the back-off (~0.85 s), not "eventually". The ticket is a timed one
            // (ender_pearl, 40 ticks) renewed on every check, so a failed or timed-out test doesn't leave the chunk
            // ticking for the rest of the suite the way a forced chunk would.
            var sim = net.minecraft.server.level.TicketType.ENDER_PEARL;
            level.getChunkSource().addTicketWithRadius(sim, cp, 2);
            long[] tickingAt = {-1};
            h.succeedWhen(() -> {
                level.getChunkSource().addTicketWithRadius(sim, cp, 2); // renews the timeout
                if (tickingAt[0] < 0 && level.isPositionTickingWithEntitiesLoaded(cp.pack())) tickingAt[0] = level.getGameTime();
                check(h, tickingAt[0] >= 0, "the chunk does not simulate yet (ticking " + level.getChunkSource().isPositionTicking(cp.pack())
                        + ", entities " + level.areEntitiesLoaded(cp.pack()) + ", block-ticking range " + level.shouldTickBlocksAt(cp.pack()) + ")");
                check(h, !level.getBlockTicks().hasScheduledTick(at, net.minecraft.world.level.block.Blocks.STONE), "the overdue tick hasn't run yet");
                long late = level.getGameTime() - tickingAt[0];
                check(h, late <= 18, "the overdue tick ran " + late + " ticks after the chunk started ticking");
                level.getChunkSource().removeTicketWithRadius(sim, cp, 2);
                level.getChunkSource().removeTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, cp, 0);
            });
        });
    }
}
