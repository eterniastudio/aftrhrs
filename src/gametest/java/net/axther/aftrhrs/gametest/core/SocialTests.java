package net.axther.aftrhrs.gametest.core;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.gametest.creatures.CT;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.axther.aftrhrs.gametest.core.CoreTests.check;

/** Two-player paths: teleport requests, homes, messages, ignore, mute, chat, nicknames, vanish, freeze. */
public class SocialTests {
    static void chat(Bots.Bot from, String text) {
        var msg = PlayerChatMessage.unsigned(from.id(), text);
        var params = ChatType.bind(ChatType.CHAT, from.player);
        if (ServerMessageEvents.ALLOW_CHAT_MESSAGE.invoker().allowChatMessage(msg, from.player, params)) {
            from.player.level().getServer().getPlayerList().broadcastChatMessage(msg, from.player, params);
        }
    }

    static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    static Bots.Bot far(GameTestHelper h, String name, double dx) {
        return Bots.join(h.getLevel(), name, h.absoluteVec(new Vec3(1.5 + dx, 2, 1.5)));
    }

    @GameTest(maxTicks = 300)
    public void tpaAcceptMovesTheRequester(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("tpA"));
        Bots.Bot b = far(h, Bots.name("tpB"), 30);
        a.cmd("tpa " + b.name());
        check(h, b.heard(a.name()), "B was not told about the request: " + b.chat());
        b.cmd("tpaccept");
        h.runAfterDelay(100, () -> {
            double d = horizontal(a.player.position(), b.player.position());
            check(h, d < 3, "A did not arrive at B (distance " + d + "); A heard " + a.chat());
            a.leave();
            b.leave();
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void tpaWarmupCancelsOnMoveAndDenyWorks(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("twA"));
        Bots.Bot b = far(h, Bots.name("twB"), 30);
        Vec3 start = a.player.position();
        b.cmd("tpdeny");
        a.cmd("tpa " + b.name());
        b.cmd("tpdeny");
        h.runAfterDelay(80, () -> {
            check(h, a.player.position().distanceTo(start) < 1, "A moved after a denied request");
            // A request to someone who quit is dropped; accepting nothing is harmless.
            a.player.connection.teleport(start.x, start.y, start.z, 0, 0);
            b.cmd("tpahere " + a.name());
            b.leave();
            a.cmd("tpaccept");
            h.runAfterDelay(80, () -> {
                check(h, a.player.position().distanceTo(start) < 1, "A teleported to a player who had quit");
                a.leave();
                h.succeed();
            });
        });
    }

    @GameTest(maxTicks = 200)
    public void tpToggleAndIgnoreBlockRequests(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("ttA"));
        Bots.Bot b = far(h, Bots.name("ttB"), 30);
        b.cmd("tptoggle");
        b.clear();
        a.cmd("tpa " + b.name());
        check(h, !b.heard(a.name() + " "), "B got a request while requests were off: " + b.chat());
        b.cmd("tptoggle");
        b.cmd("ignore " + a.name());
        b.clear();
        a.cmd("tpa " + b.name());
        b.cmd("tpaccept");
        h.runAfterDelay(80, () -> {
            check(h, a.player.position().distanceTo(b.player.position()) > 10, "an ignored player's request went through: A at "
                    + a.player.position() + " B at " + b.player.position() + "; A heard " + a.chat() + "; B heard " + b.chat());
            a.leave();
            b.leave();
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void homesSetGoListDelete(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("hm"));
        Vec3 home = a.player.position();
        a.cmd("sethome a.b");
        a.cmd("sethome ../x");
        a.cmd("sethome " + "x".repeat(40));
        var p = Feather.get().profiles().get(a.player);
        check(h, p.homes.keySet().stream().noneMatch(k -> k.contains(".") || k.length() > 16), "a bad home name was accepted: " + p.homes.keySet());
        for (int i = 4; i >= 1; i--) a.cmd("sethome h" + i);
        // Default players have homes.limits.default (3) homes.
        check(h, p.homes.size() <= 3, "home limit ignored: " + p.homes.keySet());
        check(h, p.homes.containsKey("h1") || p.homes.size() == 3, "no home set");
        if (!p.homes.containsKey("h1")) a.cmd("delhome h4");
        a.cmd("sethome h1");
        a.player.connection.teleport(home.x + 20, home.y, home.z, 0, 0);
        a.cmd("home h1");
        String said = String.join(" | ", a.chat());
        h.runAfterDelay(90, () -> {
            check(h, horizontal(a.player.position(), home) < 1.5, "/home h1 didn't go home: now " + a.player.position() + " home " + home
                    + " homes " + p.homes + " online " + !a.player.hasDisconnected() + "; said: " + said);
            a.cmd("delhome h1");
            check(h, !p.homes.containsKey("h1"), "/delhome h1 kept it");
            a.cmd("home zz_none");
            a.cmd("homes");
            a.leave();
            h.succeed();
        });
    }

    /**
     * Home and warp icons: /homeicon with a named item, the held item and clear; a bad name changes nothing; moving a home
     * keeps its icon; it is saved in the player file; /warpicon works the same for warps.
     */
    @GameTest(maxTicks = 100)
    public void homeAndWarpIcons(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("ico"));
        var p = Feather.get().profiles().get(a.player);
        p.homes.remove("ic1");
        a.cmd("sethome ic1");
        check(h, p.homes.containsKey("ic1"), "no home set: " + a.chat());
        a.cmd("homeicon ic1 diamond");
        check(h, "diamond".equals(p.homes.get("ic1").icon()), "/homeicon ic1 diamond: " + p.homes.get("ic1") + " " + a.chat());
        a.cmd("homeicon ic1 no_such_item_ico");
        check(h, "diamond".equals(p.homes.get("ic1").icon()), "an unknown item changed the icon: " + p.homes.get("ic1"));
        a.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT));
        a.cmd("homeicon ic1");
        check(h, "gold_ingot".equals(p.homes.get("ic1").icon()), "/homeicon with an item in hand: " + p.homes.get("ic1"));
        // moving the home (the same /sethome twice) keeps the icon
        a.cmd("sethome ic1");
        a.cmd("sethome ic1");
        check(h, "gold_ingot".equals(p.homes.get("ic1").icon()), "moving the home lost its icon: " + p.homes.get("ic1"));
        check(h, p.homes.get("ic1").iconStack(net.minecraft.world.item.Items.STONE).is(net.minecraft.world.item.Items.GOLD_INGOT), "the icon isn't a gold ingot");
        java.nio.file.Path file = Feather.get().getDataFolder().resolve("data").resolve("players").resolve(a.player.getUUID() + ".yml");
        h.runAfterDelay(20, () -> {
            try {
                String saved = java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file) : "";
                check(h, saved.contains("icon: gold_ingot"), "the icon isn't in the player file " + file + ":\n" + saved);
                a.cmd("homeicon ic1 clear");
                check(h, p.homes.get("ic1").icon() == null, "/homeicon ic1 clear kept " + p.homes.get("ic1").icon());
                a.cmd("delhome ic1");
                // warps (staff)
                a.op(true);
                a.cmd("setwarp icow");
                a.cmd("warpicon icow emerald");
                Object places = Feather.get().module(net.axther.aftrhrs.feature.travel.PlacesModule.class);
                java.lang.reflect.Field f = places.getClass().getDeclaredField("warps");
                f.setAccessible(true);
                @SuppressWarnings("unchecked") java.util.Map<String, net.axther.aftrhrs.core.Profiles.Home> warps = (java.util.Map<String, net.axther.aftrhrs.core.Profiles.Home>) f.get(places);
                check(h, warps.containsKey("icow") && "emerald".equals(warps.get("icow").icon()), "/warpicon icow emerald: " + warps.get("icow") + " " + a.chat());
                a.cmd("setwarp icow");
                check(h, "emerald".equals(warps.get("icow").icon()), "moving the warp lost its icon: " + warps.get("icow"));
                a.cmd("delwarp icow");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                a.op(false);
                a.leave();
            }
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void messagesIgnoreAndReply(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("msA"));
        Bots.Bot b = Bots.join(h, Bots.name("msB"));
        a.cmd("msg " + b.name() + " hello-one");
        check(h, b.heard("hello-one"), "B did not get the message: " + b.chat());
        b.cmd("r hi-back");
        check(h, a.heard("hi-back"), "A did not get the reply: " + a.chat());
        b.cmd("ignore " + a.name());
        b.clear();
        a.cmd("msg " + b.name() + " secret-two");
        check(h, !b.heard("secret-two"), "an ignored player's message arrived");
        chat(a, "public-three");
        check(h, !b.heard("public-three"), "an ignored player's chat arrived");
        b.cmd("unignore " + a.name());
        b.clear();
        chat(a, "public-four");
        check(h, b.heard("public-four"), "chat did not arrive after unignore: " + b.chat());
        // Format injection: MiniMessage typed by a player must not turn into clicks or colors.
        b.clear();
        chat(a, "<click:run_command:'/op " + a.name() + "'>click</click> <red>red");
        check(h, b.chat().stream().anyMatch(s -> s.contains("<red>red") || s.contains("red")), "chat with tags vanished");
        a.cmd("msg " + b.name() + " " + "<bold>x".repeat(10));
        a.cmd("msg zz_nobody hi");
        a.cmd("msg " + a.name() + " self");
        a.leave();
        // Messaging someone who left.
        b.cmd("r still-there?");
        b.leave();
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void mutedPlayersCannotTalk(GameTestHelper h) {
        Bots.Bot staff = Bots.join(h, Bots.name("muS"));
        Bots.Bot m = Bots.join(h, Bots.name("muM"));
        Bots.Bot other = Bots.join(h, Bots.name("muO"));
        staff.op(true);
        staff.cmd("mute " + m.name() + " 10m spam");
        check(h, Feather.get().profiles().get(m.player).muted(), "/mute did not mute: " + staff.chat());
        other.clear();
        chat(m, "muted-chat");
        m.cmd("msg " + other.name() + " muted-msg");
        m.cmd("r muted-reply");
        m.cmd("me muted-me");
        m.cmd("tell " + other.name() + " muted-tell");
        m.cmd("say muted-say");
        m.cmd("teammsg muted-team");
        for (String s : other.chat()) check(h, !s.contains("muted-"), "a muted player got through: " + s);
        staff.cmd("unmute " + m.name());
        check(h, !Feather.get().profiles().get(m.player).muted(), "/unmute did not unmute");
        staff.op(false);
        staff.leave();
        m.leave();
        other.leave();
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void nicknamesValidated(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("nkA"));
        Bots.Bot b = Bots.join(h, Bots.name("nkB"));
        var pa = Feather.get().profiles().get(a.player);
        a.cmd("nick " + b.name());
        check(h, pa.nickRaw == null || !net.axther.aftrhrs.core.Names.plain(a.player).equalsIgnoreCase(b.name()), "a nickname copied another player's name: " + pa.nickRaw);
        a.cmd("nick <click:run_command:'/op x'>Evil</click>");
        check(h, pa.nickRaw == null || !pa.nickRaw.contains("click"), "a click event got into a nickname: " + pa.nickRaw);
        a.cmd("nick <gradient:#ff0000:#00ff00>Sunny</gradient>");
        a.cmd("nick " + "W".repeat(200));
        check(h, pa.nickRaw == null || net.axther.aftrhrs.core.Names.plain(a.player).length() <= 32, "a huge nickname was accepted");
        a.cmd("nick off");
        a.leave();
        b.leave();
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void vanishHidesFromPlayers(GameTestHelper h) {
        Bots.Bot staff = Bots.join(h, Bots.name("vnS"));
        Bots.Bot p = Bots.join(h, Bots.name("vnP"));
        staff.op(true);
        staff.cmd("vanish");
        check(h, !Feather.get().canSee(p.player, staff.player), "vanish did not hide the staff member");
        p.cmd("list");
        check(h, p.chat().stream().noneMatch(s -> s.contains(staff.name())), "/list showed a vanished player: " + p.chat());
        p.cmd("msg " + staff.name() + " hi");
        p.cmd("tpa " + staff.name());
        check(h, Feather.get().findPlayer(p.player, staff.name()) == null, "lookups find a vanished player");
        check(h, !Feather.get().completePlayers(p.player, "").contains(staff.name()), "tab completion shows a vanished player");
        staff.cmd("vanish");
        check(h, Feather.get().canSee(p.player, staff.player), "unvanish did not show the staff member");
        staff.op(false);
        staff.leave();
        p.leave();
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void frozenPlayersStayPut(GameTestHelper h) {
        Bots.Bot staff = Bots.join(h, Bots.name("frS"));
        Bots.Bot f = Bots.join(h, Bots.name("frF"));
        staff.op(true);
        staff.cmd("sethome x");
        f.cmd("sethome fhome");
        staff.cmd("freeze " + f.name());
        check(h, Feather.get().profiles().get(f.player).frozen, "/freeze did not freeze: " + staff.chat());
        Vec3 at = f.player.position();
        staff.player.connection.teleport(at.x + 15, at.y, at.z, 0, 0);
        f.cmd("spawn");
        f.cmd("home fhome");
        f.cmd("tpa " + staff.name());
        staff.cmd("tpaccept");
        h.runAfterDelay(90, () -> {
            check(h, f.player.position().distanceTo(at) < 1, "a frozen player teleported away");
            staff.cmd("unfreeze " + f.name());
            check(h, !Feather.get().profiles().get(f.player).frozen, "/unfreeze did not unfreeze");
            staff.op(false);
            staff.leave();
            f.leave();
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void bansKickAndBlockOfflineToo(GameTestHelper h) {
        Bots.Bot staff = Bots.join(h, Bots.name("bnS"));
        Bots.Bot t = Bots.join(h, Bots.name("bnT"));
        String tName = t.name();
        staff.op(true);
        staff.cmd("tempban " + tName + " 1h testing");
        check(h, t.player.hasDisconnected(), "a banned player stayed online");
        staff.cmd("history " + tName);
        check(h, Feather.get().profiles().load(t.id()).modLog.stream().anyMatch(e -> e.type().equals("tempban")), "the history has no tempban");
        check(h, staff.lastDialog() != null || !staff.chat().isEmpty(), "/history showed nothing");
        staff.cmd("unban " + tName);
        staff.cmd("warn " + tName + " offline-warning");
        var prof = Feather.get().profiles().load(t.id());
        check(h, prof.warnings.stream().anyMatch(w -> w.reason().contains("offline-warning")), "an offline /warn was lost");
        staff.cmd("tempban " + tName + " 99999999999999999999y overflow");
        staff.cmd("tempban " + tName + " -5m negative");
        staff.cmd("unban " + tName);
        staff.op(false);
        staff.leave();
        h.succeed();
    }

    // ------------------------------------------------------------------ quests and calendar saves

    static Object quests() {
        return Feather.get().module(net.axther.aftrhrs.feature.quests.QuestsModule.class);
    }

    static net.axther.aftrhrs.feature.calendar.CalendarModule calendar() {
        return Feather.get().module(net.axther.aftrhrs.feature.calendar.CalendarModule.class);
    }

    /** quests.yml as a restart would read it: a second store loaded from the file. */
    @SuppressWarnings("unchecked")
    static Map<UUID, Object> questsOnDisk() {
        try {
            var ctor = CT.cls("net.axther.aftrhrs.feature.quests.QuestStore").getDeclaredConstructor(java.nio.file.Path.class, org.slf4j.Logger.class);
            ctor.setAccessible(true);
            Object fresh = ctor.newInstance(Feather.get().dataFile("quests.yml"), Feather.get().getLogger());
            CT.call(fresh, "load");
            return (Map<UUID, Object>) CT.get(fresh, "players");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Object slot(Object quests, boolean weekly, int i) {
        return ((List<Object>) CT.get(quests, weekly ? "weekly" : "daily")).get(i);
    }

    /**
     * The day and week turn for several online players holding finished, unclaimed quests: each is paid once, and the
     * whole turnover is one quests.yml save (it was one or two per player in that tick), queued in that tick (with the
     * bank snapshot) and on disk once the data thread wrote it.
     */
    @GameTest(maxTicks = 100)
    public void questRolloverPaysOnceAndSavesOnce(GameTestHelper h) {
        Object m = quests();
        check(h, m != null, "the quests module is off");
        Hooks.Bank bank = Feather.get().service(Hooks.Bank.class);
        check(h, bank != null && bank.isOpen(), "the bank is off or closed");
        Object store = CT.get(m, "store"), pool = CT.get(m, "pool");
        long today = (long) CT.call(m, "today"), week = (long) CT.call(m, "thisWeek");
        List<Bots.Bot> bots = new ArrayList<>();
        for (int i = 0; i < 3; i++) bots.add(Bots.join(h, Bots.name("qro")));
        Map<UUID, Long> expected = new HashMap<>();
        Map<UUID, Object[]> oldSlots = new HashMap<>();
        for (Bots.Bot b : bots) {
            check(h, !Feather.get().authPending(b.player), "the test player waits for the server code");
            Object q = CT.call(m, "state", b.player);
            long coins = 0;
            Object[] old = new Object[2];
            for (int w = 0; w < 2; w++) {
                Object s = slot(q, w == 1, 0);
                Object def = CT.call(pool, "def", (String) CT.get(s, "id"));
                CT.set(s, "progress", (int) CT.call(def, "amount"));
                CT.set(s, "claimed", false);
                coins += (long) CT.call(m, "boosted", (long) CT.call(CT.call(def, "reward"), "coins"));
                old[w] = s;
            }
            CT.set(q, "day", today - 1);
            CT.set(q, "week", week - 7);
            expected.put(b.id(), bank.balance(b.id()) + coins);
            oldSlots.put(b.id(), old);
        }
        int saves0 = (int) CT.get(store, "saves");
        CT.call(m, "tick");
        int saves = (int) CT.get(store, "saves") - saves0;
        check(h, saves == 1, "the turnover of " + bots.size() + " players made " + saves + " quests.yml saves (expected 1)");
        check(h, !(boolean) CT.get(store, "dirty"), "quests are still dirty after the turnover");
        net.axther.aftrhrs.core.SafeYaml.drain(5);
        Map<UUID, Object> disk = questsOnDisk();
        for (Bots.Bot b : bots) {
            Object q = ((Map<UUID, Object>) CT.get(store, "players")).get(b.id());
            check(h, (long) CT.get(q, "day") == today && (long) CT.get(q, "week") == week, b.name() + " didn't turn over");
            check(h, bank.balance(b.id()) == expected.get(b.id()), b.name() + " holds " + bank.balance(b.id()) + " coins (expected " + expected.get(b.id()) + ")");
            Object[] old = oldSlots.get(b.id());
            check(h, slot(q, false, 0) != old[0] && slot(q, true, 0) != old[1], b.name() + " kept the old quests");
            Object onDisk = disk.get(b.id());
            check(h, onDisk != null && (long) CT.get(onDisk, "day") == today && (long) CT.get(onDisk, "week") == week,
                    "quests.yml doesn't have " + b.name() + "'s new sets");
            check(h, CT.get(slot(onDisk, false, 0), "id").equals(CT.get(slot(q, false, 0), "id"))
                    && CT.get(slot(onDisk, true, 0), "id").equals(CT.get(slot(q, true, 0), "id")), "quests.yml has other quests than " + b.name() + " holds");
        }
        // Paid once: the next run finds nothing to turn over.
        CT.call(m, "tick");
        for (Bots.Bot b : bots) check(h, bank.balance(b.id()) == expected.get(b.id()), b.name() + " was paid again: " + bank.balance(b.id()));
        for (Bots.Bot b : bots) b.leave();
        h.succeed();
    }

    /**
     * A claimed login reward is in calendar.yml before it is paid (a crash in between can't pay it twice): the claim
     * writes on the calendar's io thread without blocking, and the coins arrive with the write's callback. A second
     * claim while the write is on its way is refused (the record already holds the claim).
     */
    @GameTest(maxTicks = 60)
    public void calendarClaimIsOnDiskBeforeItIsPaid(GameTestHelper h) {
        var cal = calendar();
        check(h, cal != null, "the calendar module is off");
        Hooks.Bank bank = Feather.get().service(Hooks.Bank.class);
        check(h, bank != null && bank.isOpen(), "the bank is off or closed");
        Bots.Bot a = Bots.join(h, Bots.name("cal"));
        long before = bank.balance(a.id());
        long today = (long) CT.call(cal, "today");
        java.util.concurrent.CountDownLatch gate = holdCalendarWriter(cal);
        claim(cal, a, "claimDaily");
        long paidEarly = bank.balance(a.id()) - before;
        Map<?, ?> pending = (Map<?, ?>) CT.get(cal, "pendingPay");
        int waiting = pending.size();
        // The second claim inside the window.
        claim(cal, a, "claimDaily");
        boolean refused = a.heard("You've claimed today's reward");
        gate.countDown();
        h.runAfterDelay(10, () -> {
            check(h, paidEarly == 0, "the claim paid " + paidEarly + " coins before calendar.yml was written");
            check(h, waiting >= 1, "the claim wasn't waiting for its write");
            check(h, refused, "a second claim in the pending window wasn't refused: " + a.chat());
            Yaml file = Yaml.load(Feather.get().dataFile("calendar.yml"));
            long day = file.getLong("players." + a.id() + ".last-claim-day", Long.MIN_VALUE);
            check(h, day == today, "calendar.yml after the claim has last-claim-day " + day + " (expected " + today + "); " + a.chat());
            long paid = bank.balance(a.id()) - before;
            check(h, paid == dayOneCoins(cal), "the day-1 claim paid " + paid + " coins (expected " + dayOneCoins(cal) + " once)");
            a.leave();
            h.succeed();
        });
    }

    /** A background save queued before a claim's immediate save never lands over it (calendar seq guard, quests' queue order). */
    @GameTest(maxTicks = 60)
    @SuppressWarnings("unchecked")
    public void queuedSavesNeverOverwriteANewerOne(GameTestHelper h) throws Exception {
        var cal = calendar();
        Object m = quests();
        check(h, cal != null && m != null, "the calendar or quests module is off");
        Bots.Bot a = Bots.join(h, Bots.name("seq"));
        // Calendar: hold its writer, queue an older snapshot, then write a newer one on this thread.
        Object rec = CT.call(cal, "rec", a.player);
        java.util.concurrent.ExecutorService io = (java.util.concurrent.ExecutorService) CT.get(cal, "io");
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        io.execute(() -> {
            try {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        CT.set(rec, "playTotal", 111L);
        CT.call(cal, "saveAsync");
        CT.set(rec, "playTotal", 222_000_000L);
        // A newer snapshot written straight from this thread (as disable() and a refused io queue do).
        Object newer = CT.call(cal, "snapshot");
        check(h, (boolean) CT.call(cal, "write", newer, (long) CT.get(cal, "snapshotSeq")), "the calendar's immediate save failed");
        gate.countDown();
        // Quests: the same through the shared data thread (held for a moment by another thread).
        Object store = CT.get(m, "store");
        Object q = CT.call(m, "state", a.player);
        java.util.concurrent.ExecutorService data = (java.util.concurrent.ExecutorService) CT.get(net.axther.aftrhrs.core.SafeYaml.class, "DATA_IO");
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        data.execute(() -> {
            try {
                hold.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            hold.countDown();
        }, "seq-test-release");
        release.start();
        CT.set(q, "completed", 111);
        CT.set(store, "dirty", true);
        CT.call(store, "saveIfDirtyAsync");
        CT.set(q, "completed", 222);
        check(h, (boolean) CT.call(store, "saveNow"), "the quests' immediate save failed");
        Object onDisk = questsOnDisk().get(a.id());
        check(h, onDisk != null && (int) CT.get(onDisk, "completed") == 222, "quests.yml has completed " + (onDisk == null ? null : CT.get(onDisk, "completed")) + " (expected 222)");
        h.runAfterDelay(20, () -> {
            long total = Yaml.load(Feather.get().dataFile("calendar.yml")).getLong("players." + a.id() + ".play-total", -1);
            check(h, total >= 222_000_000L, "calendar.yml has play-total " + total + ": the older queued save landed over the newer one");
            a.leave();
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ round 2: quests, mail, calendar, digest

    static Object newQuestStore(java.nio.file.Path file) {
        try {
            var ctor = CT.cls("net.axther.aftrhrs.feature.quests.QuestStore").getDeclaredConstructor(java.nio.file.Path.class, org.slf4j.Logger.class);
            ctor.setAccessible(true);
            return ctor.newInstance(file, Feather.get().getLogger());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object questSlot(String id, int progress, boolean claimed, String... keys) {
        try {
            var ctor = CT.cls("net.axther.aftrhrs.feature.quests.QuestStore$Slot").getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Object s = ctor.newInstance(id);
            CT.set(s, "progress", progress);
            CT.set(s, "claimed", claimed);
            @SuppressWarnings("unchecked") java.util.Set<String> k = (java.util.Set<String>) CT.get(s, "keys");
            k.addAll(List.of(keys));
            return s;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** QuestStore.snapshot as it was before round 2 (a Yaml tree set field by field, then deep-copied). */
    @SuppressWarnings("unchecked")
    static Object oldQuestSnapshot(Object store) {
        Yaml yml = new Yaml();
        Class<?> qs = CT.cls("net.axther.aftrhrs.feature.quests.QuestStore");
        for (Object q : ((Map<UUID, Object>) CT.get(store, "players")).values()) {
            String p = "players." + CT.get(q, "id");
            yml.set(p + ".name", CT.get(q, "name"));
            yml.set(p + ".day", CT.get(q, "day"));
            yml.set(p + ".week", CT.get(q, "week"));
            yml.set(p + ".rerolls", CT.get(q, "rerolls"));
            yml.set(p + ".completed", CT.get(q, "completed"));
            yml.set(p + ".daily", CT.call(qs, "writeSlots", CT.get(q, "daily")));
            yml.set(p + ".weekly", CT.call(qs, "writeSlots", CT.get(q, "weekly")));
        }
        return yml.snapshot();
    }

    /** QuestStore.snapshot builds plain maps directly: the same YAML as the old Yaml-tree build, empty or not, and load() reads it back. */
    @GameTest(maxTicks = 40)
    @SuppressWarnings("unchecked")
    public void questSnapshotMatchesTheYamlTreeBuild(GameTestHelper h) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("aftrhrs-qsnap");
        java.nio.file.Path file = dir.resolve("quests.yml");
        Object store = newQuestStore(file);
        // (c) empty
        check(h, CT.call(store, "snapshot") == null, "an empty store's snapshot isn't null");
        check(h, Yaml.dump(CT.call(store, "snapshot")).equals(Yaml.dump(oldQuestSnapshot(store))), "an empty store dumps differently");
        // (a) a named player with keyed and plain slots
        Object a = CT.call(store, "get", UUID.randomUUID());
        CT.set(a, "name", "Snappy");
        CT.set(a, "day", 20_400L);
        CT.set(a, "week", 20_398L);
        CT.set(a, "rerolls", 2);
        CT.set(a, "completed", 17);
        ((List<Object>) CT.get(a, "daily")).add(questSlot("mine_stone", 12, false));
        ((List<Object>) CT.get(a, "daily")).add(questSlot("tame_any", 1, true, "e1", "e2"));
        ((List<Object>) CT.get(a, "weekly")).add(questSlot("visit_friends", 3, false, "f1"));
        // (b) no name, never-set day/week, no weekly slots
        Object b = CT.call(store, "get", UUID.randomUUID());
        ((List<Object>) CT.get(b, "daily")).add(questSlot("fish", 0, false));
        String fresh = Yaml.dump(CT.call(store, "snapshot")), old = Yaml.dump(oldQuestSnapshot(store));
        check(h, fresh.equals(old), "the new snapshot dumps differently:\n--- new\n" + fresh + "--- old\n" + old);
        check(h, !fresh.contains("name: null"), "a missing name was written as null:\n" + fresh);
        // load() round-trips
        check(h, (boolean) CT.call(store, "saveNow"), "the save failed");
        Object back = newQuestStore(file);
        CT.call(back, "load");
        check(h, !(boolean) CT.get(back, "unreadable"), "the saved file can't be read");
        String reread = Yaml.dump(CT.call(back, "snapshot"));
        check(h, reread.equals(fresh), "load() didn't round-trip:\n--- saved\n" + fresh + "--- loaded\n" + reread);
        Object b2 = ((Map<UUID, Object>) CT.get(back, "players")).get(CT.get(b, "id"));
        check(h, b2 != null && CT.get(b2, "name") == null, "the unnamed player came back with a name");
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    static <T> T companionField(String field) {
        return (T) CT.get(net.axther.aftrhrs.net.Companion.class, field);
    }

    /** Links the bot as a Companion client that agreed on these features (what a hello would agree on); its channel is open. */
    static void linkCompanion(Bots.Bot b, String... features) {
        b.openChannels(net.axther.aftrhrs.net.ApiPayload.TYPE.id());
        Map<UUID, net.axther.aftrhrs.net.Companion.Session> sessions = companionField("SESSIONS");
        sessions.put(b.id(), new net.axther.aftrhrs.net.Companion.Session("test", java.util.Set.of(features)));
    }

    /** The Companion messages of this type (null: all) the bot was sent since the last call (parts joined); clears the bot's payloads. */
    static List<com.google.gson.JsonObject> companionSent(Bots.Bot b, String type) {
        b.pump();
        List<com.google.gson.JsonObject> out = new ArrayList<>();
        Map<Integer, byte[][]> parts = new HashMap<>();
        for (var pl : b.payloads) {
            if (!(pl instanceof net.axther.aftrhrs.net.ApiPayload api)) continue;
            com.google.gson.JsonObject o = companionDecode(api.bytes());
            if (o == null) continue;
            if ("part".equals(o.get("t").getAsString())) {
                int id = o.get("id").getAsInt(), i = o.get("i").getAsInt(), n = o.get("n").getAsInt();
                byte[][] pieces = parts.computeIfAbsent(id, k -> new byte[n][]);
                pieces[i] = java.util.Base64.getDecoder().decode(o.get("b").getAsString());
                boolean done = true;
                for (byte[] piece : pieces) done &= piece != null;
                if (!done) continue;
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                for (byte[] piece : pieces) all.writeBytes(piece);
                o = companionDecode(all.toByteArray());
                if (o == null) continue;
            }
            if (type == null || type.equals(o.get("t").getAsString())) out.add(o);
        }
        b.payloads.clear();
        return out;
    }

    static com.google.gson.JsonObject companionDecode(byte[] payload) {
        try {
            byte[] body;
            if (payload[0] == 0) body = java.util.Arrays.copyOfRange(payload, 1, payload.length);
            else try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(payload, 1, payload.length - 1))) {
                body = in.readAllBytes();
            }
            return com.google.gson.JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** Gives the bot a finished, unclaimed daily and weekly quest from the old day and week; the coins they pay. */
    static long finishOldQuests(Object m, Bots.Bot b) {
        Object pool = CT.get(m, "pool");
        long today = (long) CT.call(m, "today"), week = (long) CT.call(m, "thisWeek");
        Object q = CT.call(m, "state", b.player);
        long coins = 0;
        for (int w = 0; w < 2; w++) {
            Object s = slot(q, w == 1, 0);
            Object def = CT.call(pool, "def", (String) CT.get(s, "id"));
            CT.set(s, "progress", (int) CT.call(def, "amount"));
            CT.set(s, "claimed", false);
            coins += (long) CT.call(m, "boosted", (long) CT.call(CT.call(def, "reward"), "coins"));
        }
        CT.set(q, "day", today - 1);
        CT.set(q, "week", week - 7);
        return coins;
    }

    /**
     * Three linked players with finished, unclaimed quests from the old day, turned over by one Companion tracker flush
     * (as when they join together): each is paid once and the flush is one quests.yml save, not one per player; a second
     * flush pays nothing.
     */
    @GameTest(maxTicks = 100)
    @SuppressWarnings("unchecked")
    public void companionFlushBatchesRollovers(GameTestHelper h) {
        Object m = quests();
        check(h, m != null, "the quests module is off");
        Hooks.Bank bank = Feather.get().service(Hooks.Bank.class);
        check(h, bank != null && bank.isOpen(), "the bank is off or closed");
        Object store = CT.get(m, "store"), bridge = CT.get(m, "bridge");
        long today = (long) CT.call(m, "today");
        List<Bots.Bot> bots = new ArrayList<>();
        for (int i = 0; i < 3; i++) bots.add(Bots.join(h, Bots.name("qcf")));
        Map<UUID, Long> expected = new HashMap<>();
        try {
            for (Bots.Bot b : bots) {
                check(h, !Feather.get().authPending(b.player), "the test player waits for the server code");
                linkCompanion(b, "quests");
                expected.put(b.id(), bank.balance(b.id()) + finishOldQuests(m, b));
            }
            int saves0 = (int) CT.get(store, "saves");
            CT.call(bridge, "flush");
            int saves = (int) CT.get(store, "saves") - saves0;
            check(h, saves == 1, "one tracker flush turning " + bots.size() + " players over made " + saves + " quests.yml saves (expected 1)");
            for (Bots.Bot b : bots) {
                Object q = ((Map<UUID, Object>) CT.get(store, "players")).get(b.id());
                check(h, (long) CT.get(q, "day") == today, b.name() + " didn't turn over");
                check(h, bank.balance(b.id()) == expected.get(b.id()), b.name() + " holds " + bank.balance(b.id()) + " coins (expected " + expected.get(b.id()) + ")");
                check(h, !companionSent(b, "quests").isEmpty(), b.name() + " got no quests list");
            }
            for (Bots.Bot b : bots) CT.call(bridge, "changed", b.player);
            CT.call(bridge, "flush");
            for (Bots.Bot b : bots) check(h, bank.balance(b.id()) == expected.get(b.id()), b.name() + " was paid again: " + bank.balance(b.id()));
            net.axther.aftrhrs.core.SafeYaml.drain(5);
            Map<UUID, Object> disk = questsOnDisk();
            for (Bots.Bot b : bots) {
                Object onDisk = disk.get(b.id());
                check(h, onDisk != null && (long) CT.get(onDisk, "day") == today, "quests.yml doesn't have " + b.name() + "'s new day");
            }
        } finally {
            for (Bots.Bot b : bots) b.leave();   // the Companion's quit ends the fake session too
        }
        h.succeed();
    }


    /** Runs {@code then} once {@code ready} holds, checking every 2 ticks up to {@code tries} times; fails with {@code why} otherwise. */
    static void waitThen(GameTestHelper h, int tries, java.util.function.BooleanSupplier ready, String why, Runnable then) {
        if (ready.getAsBoolean()) {
            then.run();
            return;
        }
        check(h, tries > 0, why);
        h.runAfterDelay(2, () -> waitThen(h, tries - 1, ready, why, then));
    }

    /** The server goal's active-player count the old way: online players plus every player file modified since the cutoff. */
    static int activeByDiskScan(Object m) throws java.io.IOException {
        long cutoff = System.currentTimeMillis() - (int) CT.get(m, "goalScaleDays") * 86_400_000L;
        java.util.Set<UUID> active = new java.util.HashSet<>();
        for (var p : Feather.get().onlinePlayers()) active.add(p.getUUID());
        try (var files = java.nio.file.Files.list(Feather.get().profiles().folder())) {
            for (java.nio.file.Path f : files.toList()) {
                String n = f.getFileName().toString();
                if (!n.endsWith(".yml")) continue;
                try {
                    if (java.nio.file.Files.getLastModifiedTime(f).toMillis() >= cutoff) active.add(UUID.fromString(n.substring(0, n.length() - 4)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return active.size();
    }

    /** Waits for every player-file write queued so far (the Profiles writer thread). */
    static void drainProfiles() throws Exception {
        var io = (java.util.concurrent.ExecutorService) CT.get(Feather.get().profiles(), "io");
        io.submit(() -> {
        }).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * The server-goal scale counts active players from Profiles' write index (the startup walk's modified times plus
     * saves since), not a folder scan: player files with old and recent modified times give the same count as the scan,
     * and saving a stale profile makes it count.
     */
    @GameTest(maxTicks = 200)
    @SuppressWarnings("unchecked")
    public void goalScaleCountsFromTheWriteIndex(GameTestHelper h) throws Exception {
        Object m = quests();
        check(h, m != null, "the quests module is off");
        var profiles = Feather.get().profiles();
        java.nio.file.Path folder = profiles.folder();
        java.nio.file.Files.createDirectories(folder);
        int days = (int) CT.get(m, "goalScaleDays");
        long now = System.currentTimeMillis();
        List<UUID> old = new ArrayList<>(), recent = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.randomUUID();
            java.nio.file.Path f = folder.resolve(id + ".yml");
            java.nio.file.Files.writeString(f, "name: gsc" + i + "\nlast-seen: 0\n");
            boolean stale = i < 3;
            java.nio.file.Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(stale ? now - (days + 3) * 86_400_000L : now - 3_600_000L));
            (stale ? old : recent).add(id);
        }
        Map<UUID, Long> written = (Map<UUID, Long>) CT.get(profiles, "written");
        boolean scaling = (boolean) CT.get(m, "goalScaling");
        Runnable cleanup = () -> {
            CT.set(m, "goalScaling", scaling);
            for (UUID id : old) written.remove(id);
            for (UUID id : recent) written.remove(id);
            try {
                drainProfiles();
                for (UUID id : old) java.nio.file.Files.deleteIfExists(folder.resolve(id + ".yml"));
                for (UUID id : recent) java.nio.file.Files.deleteIfExists(folder.resolve(id + ".yml"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        CT.set(m, "goalScaling", true);
        // The files appeared after boot: walk the folder again (what a restart does).
        CT.call(Feather.get().lookup(), "start");
        waitThen(h, 80, () -> old.stream().allMatch(written::containsKey) && recent.stream().allMatch(written::containsKey), "the walk never indexed the test files", () -> {
            try {
                drainProfiles();
                check(h, profiles.writtenSince(0, new java.util.HashSet<>()) >= 0, "the write index isn't ready");
                int disk = activeByDiskScan(m);
                double[] scale = (double[]) CT.call(m, "goalScale");
                java.util.Set<UUID> fromIndex = new java.util.HashSet<>();
                profiles.writtenSince(now - days * 86_400_000L, fromIndex);
                check(h, recent.stream().allMatch(fromIndex::contains) && old.stream().noneMatch(fromIndex::contains),
                        "the index has the wrong test files as active: " + fromIndex.stream().filter(id -> old.contains(id) || recent.contains(id)).toList());
                check(h, (int) scale[1] == disk, "goalScale counts " + (int) scale[1] + " active players, the folder scan " + disk);
                // A stale player is saved (they played): they count from now on.
                profiles.save(profiles.load(old.get(0)));
                double[] after = (double[]) CT.call(m, "goalScale");
                check(h, (int) after[1] == disk + 1, "saving a stale profile left the count at " + (int) after[1] + " (expected " + (disk + 1) + ")");
                drainProfiles();
                check(h, activeByDiskScan(m) == (int) after[1], "after the save the folder scan says " + activeByDiskScan(m) + ", goalScale " + (int) after[1]);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                cleanup.run();
            }
            h.succeed();
        });
    }

    /** Holds the calendar's io thread until the latch opens (at most 5 s), so a claim's write waits. */
    static java.util.concurrent.CountDownLatch holdCalendarWriter(Object cal) {
        java.util.concurrent.ExecutorService io = (java.util.concurrent.ExecutorService) CT.get(cal, "io");
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        io.execute(() -> {
            try {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });
        return gate;
    }

    /** A calendar claim by the bot ({@code claimDaily} or {@code claimPlaytime}), without the per-connection limit (all bots share one address). */
    static void claim(Object cal, Bots.Bot b, String how) {
        int ipLimit = (int) CT.get(cal, "accountsPerIp");
        CT.set(cal, "accountsPerIp", 0);
        try {
            CT.call(cal, how, b.player);
        } finally {
            CT.set(cal, "accountsPerIp", ipLimit);
        }
    }

    static long dayOneCoins(Object cal) {
        return ((Number) CT.call(CT.call(cal, "dayReward", 1), "coins")).longValue();
    }

    /**
     * A claim whose write failed is rolled back and pays nothing. The failure is settled by hand while the real write
     * is held (a really failing write logs an error, which tests running alongside that watch the log would count);
     * the write's own callback then finds the claim settled and does nothing.
     */
    @GameTest(maxTicks = 60)
    public void calendarClaimFailedWriteRollsBack(GameTestHelper h) {
        var cal = calendar();
        check(h, cal != null, "the calendar module is off");
        Hooks.Bank bank = Feather.get().service(Hooks.Bank.class);
        check(h, bank != null && bank.isOpen(), "the bank is off or closed");
        Bots.Bot a = Bots.join(h, Bots.name("calf"));
        long before = bank.balance(a.id());
        Object rec = CT.call(cal, "rec", a.player);
        long dayBefore = (long) CT.get(rec, "lastClaimDay");
        int streakBefore = (int) CT.get(rec, "streak");
        java.util.concurrent.CountDownLatch gate = holdCalendarWriter(cal);
        try {
            claim(cal, a, "claimDaily");
            check(h, (long) CT.get(rec, "lastClaimDay") == (long) CT.call(cal, "today"), "the claim didn't take hold in the record");
            long key = (long) CT.get(cal, "claimSeq");
            check(h, ((Map<?, ?>) CT.get(cal, "pendingPay")).containsKey(key), "the claim isn't waiting for its write");
            CT.call(cal, "settle", key, false);
        } finally {
            gate.countDown();
        }
        h.runAfterDelay(10, () -> {
            check(h, (long) CT.get(rec, "lastClaimDay") == dayBefore && (int) CT.get(rec, "streak") == streakBefore,
                    "the failed claim wasn't rolled back: last-claim-day " + CT.get(rec, "lastClaimDay") + ", streak " + CT.get(rec, "streak"));
            check(h, bank.balance(a.id()) == before, "a claim that couldn't be saved paid " + (bank.balance(a.id()) - before) + " coins");
            check(h, a.heard("can't save"), "the player wasn't told the calendar can't save: " + a.chat());
            a.leave();
            h.succeed();
        });
    }

    /** A player who leaves before the claim's write lands is rolled back and paid nothing (no coins for someone offline). */
    @GameTest(maxTicks = 60)
    public void calendarClaimRolledBackWhenThePlayerLeaves(GameTestHelper h) {
        var cal = calendar();
        check(h, cal != null, "the calendar module is off");
        Hooks.Bank bank = Feather.get().service(Hooks.Bank.class);
        check(h, bank != null && bank.isOpen(), "the bank is off or closed");
        Bots.Bot a = Bots.join(h, Bots.name("call"));
        UUID id = a.id();
        long before = bank.balance(id);
        Object rec = CT.call(cal, "rec", a.player);
        long dayBefore = (long) CT.get(rec, "lastClaimDay");
        java.util.concurrent.CountDownLatch gate = holdCalendarWriter(cal);
        claim(cal, a, "claimDaily");
        a.leave();
        gate.countDown();
        h.runAfterDelay(10, () -> {
            check(h, (long) CT.get(rec, "lastClaimDay") == dayBefore, "the claim of a player who left wasn't rolled back");
            check(h, bank.balance(id) == before, "a player who left was paid " + (bank.balance(id) - before) + " coins");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ join digest

    /** Before a test player joins: interest earned while away in the bank and a stall sale note, under their id. */
    static UUID awayFor(String name) {
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
        Object bank = Feather.get().module(net.axther.aftrhrs.feature.bank.BankModule.class);
        Object account = CT.call(CT.get(bank, "store"), "account", id);
        CT.set(account, "awayInterest", 7L);
        Object shop = Feather.get().module(net.axther.aftrhrs.feature.shop.ShopModule.class);
        if (shop != null) {
            try {
                var ctor = CT.cls("net.axther.aftrhrs.feature.shop.ShopStore$Note").getDeclaredConstructor();
                ctor.setAccessible(true);
                Object note = ctor.newInstance();
                CT.set(note, "sales", 2);
                CT.set(note, "coins", 40L);
                CT.set(note, "since", System.currentTimeMillis());
                @SuppressWarnings("unchecked") Map<UUID, Object> notes = (Map<UUID, Object>) CT.get(CT.get(shop, "store"), "notes");
                notes.put(id, note);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return id;
    }

    static final String DIGEST_HEADER = "While you were away:", BANK_LINE = "your savings earned", STALL_LINE = "your stall sold",
            CALENDAR_LINE = "daily reward is waiting";

    static long count(List<String> chat, String fragment) {
        return chat.stream().filter(s -> s.contains(fragment)).count();
    }

    /** Checks the bot got exactly one digest holding the bank, stall and calendar lines as bullets, and none of them on its own. */
    static void checkDigest(GameTestHelper h, Bots.Bot b, boolean stall) {
        List<String> chat = new ArrayList<>(b.chat());
        List<String> digests = chat.stream().filter(s -> s.contains(DIGEST_HEADER)).toList();
        check(h, digests.size() == 1, b.name() + " got " + digests.size() + " join digests: " + chat);
        String d = digests.get(0);
        String[] lines = d.split("\n");
        check(h, lines[0].contains(DIGEST_HEADER), "the digest doesn't start with its header: " + d);
        long bullets = java.util.Arrays.stream(lines).filter(l -> l.contains("•")).count();
        check(h, bullets == lines.length - 1 && bullets >= (stall ? 3 : 2), "the digest has " + bullets + " bullets in " + lines.length + " lines: " + d);
        for (String f : stall ? List.of(BANK_LINE, STALL_LINE, CALENDAR_LINE) : List.of(BANK_LINE, CALENDAR_LINE)) {
            check(h, java.util.Arrays.stream(lines).anyMatch(l -> l.contains("•") && l.contains(f)), "the digest has no '" + f + "' bullet: " + d);
            check(h, count(chat, f) == 1, "'" + f + "' arrived " + count(chat, f) + " times: " + chat);
        }
    }

    /**
     * A player with interest earned while away, a stall sale note and an unclaimed calendar day gets one "While you were
     * away" message after the MOTD with a bullet for each (not a line per module), and each note is used up once.
     */
    @GameTest(maxTicks = 200)
    public void joinDigestBatchesTheJoinLines(GameTestHelper h) {
        check(h, net.axther.aftrhrs.core.JoinDigest.on(), "chat.join-digest is off");
        String name = Bots.name("jdg");
        UUID id = awayFor(name);
        Object shop = Feather.get().module(net.axther.aftrhrs.feature.shop.ShopModule.class);
        Bots.Bot a = Bots.join(h, name);
        check(h, a.id().equals(id), "the test player's id isn't the offline one");
        h.runAfterDelay(80, () -> {
            List<String> chat = new ArrayList<>(a.chat());
            int motd = -1, digest = -1;
            for (int i = 0; i < chat.size(); i++) {
                if (motd < 0 && chat.get(i).contains("welcome back")) motd = i;
                if (digest < 0 && chat.get(i).contains(DIGEST_HEADER)) digest = i;
            }
            check(h, motd >= 0 && digest > motd, "the digest didn't come after the MOTD: " + chat);
            checkDigest(h, a, shop != null);
            Object bank = Feather.get().module(net.axther.aftrhrs.feature.bank.BankModule.class);
            check(h, (long) CT.get(CT.call(CT.get(bank, "store"), "find", id), "awayInterest") == 0, "the away interest wasn't used up");
            if (shop != null) check(h, !((Map<?, ?>) CT.get(CT.get(shop, "store"), "notes")).containsKey(id), "the stall note wasn't used up");
            Object rec = CT.call(calendar(), "find", id);
            check(h, (long) CT.get(rec, "promptDay") == (long) CT.call(calendar(), "today"), "the calendar didn't note today's prompt");
            // Nothing more later (the calendar sampler, a second run).
            a.clear();
            net.axther.aftrhrs.core.JoinDigest.run(a.player);
            h.runAfterDelay(110, () -> {
                List<String> later = a.chat();
                check(h, count(later, DIGEST_HEADER) == 0 && count(later, CALENDAR_LINE) == 0, "join lines came again: " + later);
                a.leave();
                h.succeed();
            });
        });
    }

    /** A player held by the auth gate gets the digest (with the quests line) once they're in, not before. */
    @GameTest(maxTicks = 300)
    @SuppressWarnings("unchecked")
    public void joinDigestWaitsForTheAuthGate(GameTestHelper h) throws Exception {
        Object auth = Feather.get().module(net.axther.aftrhrs.feature.auth.AuthModule.class);
        check(h, auth != null, "the auth module is off");
        String name = Bots.name("jdA");
        awayFor(name);
        Bots.Bot a = Bots.join(h, name);
        // Held as if they had to type the server code (without turning the gate on for everyone).
        var ctor = CT.cls("net.axther.aftrhrs.feature.auth.AuthModule$Session").getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        Object session = ctor.newInstance("test");
        Map<UUID, Object> sessions = (Map<UUID, Object>) CT.get(auth, "sessions");
        sessions.put(a.id(), session);
        check(h, Feather.get().authPending(a.player), "the session didn't hold the player");
        h.runAfterDelay(90, () -> {
            check(h, count(a.chat(), DIGEST_HEADER) == 0 && count(a.chat(), BANK_LINE) == 0, "join lines reached a player behind the gate: " + a.chat());
            CT.call(auth, "succeed", a.player, session);
            check(h, !Feather.get().authPending(a.player), "succeed() didn't let the player in");
            checkDigest(h, a, false);
            check(h, a.chat().stream().anyMatch(s -> s.contains(DIGEST_HEADER) && s.contains("Quests:")), "the digest after the gate has no quests line: " + a.chat());
            a.leave();
            h.succeed();
        });
    }

    /**
     * Only the server goal's progress moved: a client that agreed on quest_goal gets the small quest_goal message (the
     * new progress) and not its whole quests list again; a client without the feature still gets the full list.
     */
    @GameTest(maxTicks = 60)
    public void goalProgressSendsQuestGoalDelta(GameTestHelper h) {
        Object m = quests();
        check(h, m != null, "the quests module is off");
        check(h, (boolean) CT.get(m, "goalEnabled"), "the server goal is off");
        Object goal = CT.get(m, "goal");
        if (CT.call(goal, "def") == null) {
            @SuppressWarnings("unchecked") Map<String, Object> goals = (Map<String, Object>) CT.get(CT.get(m, "pool"), "goals");
            check(h, !goals.isEmpty(), "quests.yml has no server goals");
            CT.call(goal, "start", goals.keySet().iterator().next(), (long) CT.call(m, "thisWeek"), true);
        }
        check(h, CT.call(goal, "def") != null, "no server goal runs");
        Object bridge = CT.get(m, "bridge");
        Bots.Bot a = Bots.join(h, Bots.name("qgA")), b = Bots.join(h, Bots.name("qgB"));
        long before = (long) CT.get(goal, "progress");
        try {
            linkCompanion(a, "quests", "quest_goal");
            linkCompanion(b, "quests");
            CT.call(bridge, "flush");
            check(h, companionSent(a, "quests").size() == 1 && companionSent(b, "quests").size() == 1, "the linked players got no first list");
            CT.set(goal, "progress", before + 1);
            CT.call(bridge, "flush");
            List<com.google.gson.JsonObject> aGoal = companionSent(a, "quest_goal");
            List<com.google.gson.JsonObject> aFull = companionSent(a, "quests");
            check(h, aGoal.size() == 1 && aFull.isEmpty(), "with quest_goal: " + aGoal.size() + " quest_goal and " + aFull.size() + " quests messages (expected 1 and 0)");
            var g = aGoal.get(0).getAsJsonObject("goal");
            check(h, g != null && g.get("progress").getAsLong() == before + 1 && g.has("name") && g.has("goal") && g.has("done") && g.has("reward"),
                    "the quest_goal message is " + aGoal.get(0));
            List<com.google.gson.JsonObject> bAll = companionSent(b, null);
            check(h, bAll.stream().noneMatch(o -> "quest_goal".equals(o.get("t").getAsString())), "a client without quest_goal got one");
            List<com.google.gson.JsonObject> bFull = bAll.stream().filter(o -> "quests".equals(o.get("t").getAsString())).toList();
            check(h, bFull.size() == 1, "a client without quest_goal got " + bFull.size() + " quests lists when the goal moved (expected 1)");
            check(h, bFull.get(0).has("goal") && bFull.get(0).getAsJsonObject("goal").get("progress").getAsLong() == before + 1,
                    "the full list has an old goal: " + bFull.get(0));
        } finally {
            CT.set(goal, "progress", before);
            a.leave();
            b.leave();
        }
        h.succeed();
    }

    /** The server goal's periodic save goes to the data thread (dirty cleared at the snapshot) and lands in server-goal.yml. */
    @GameTest(maxTicks = 40)
    public void serverGoalSavesInTheBackground(GameTestHelper h) {
        Object m = quests();
        check(h, m != null, "the quests module is off");
        Object goal = CT.get(m, "goal");
        check(h, !(boolean) CT.get(goal, "unreadable"), "server-goal.yml is unreadable");
        int announced = (int) CT.get(goal, "announced");
        CT.call(goal, "markDirty");
        CT.call(goal, "saveIfDirtyAsync");
        check(h, !(boolean) CT.get(goal, "dirty"), "the goal is still dirty after its background save was queued");
        net.axther.aftrhrs.core.SafeYaml.drain(5);
        Yaml file = Yaml.load(Feather.get().dataFile("server-goal.yml"));
        check(h, file.getInt("goal.announced", -1) == announced && file.contains("goal.week"), "server-goal.yml after the background save: " + file.saveToString());
        check(h, (boolean) CT.call(goal, "save"), "the goal's synchronous save failed");
        h.succeed();
    }
}
