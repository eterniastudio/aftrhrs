package net.axther.aftrhrs.gametest.core;

import io.netty.buffer.Unpooled;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.chat.ChatModule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;

import static net.axther.aftrhrs.gametest.core.CoreTests.check;

/**
 * Server-wide switches, in their own batch: the auth gate, maintenance, signed chat, the server-list entry.
 * Each test puts the switch back.
 */
public class GateTests {
    static final String ENV = "aftrhrs_gametest:core_isolated";

    /** Each switch gets its own batch: tests in one batch run side by side and would join behind the gate. */
    @GameTest(maxTicks = 500, environment = "aftrhrs_gametest:core_auth")
    public void authGateHoldsNewPlayers(GameTestHelper h) {
        Bots.Console console = new Bots.Console();
        console.cmd(h.getLevel(), "auth setcode testcode1234");
        Bots.Bot other = Bots.join(h, Bots.name("auO"));
        Bots.Bot a = Bots.join(h, Bots.name("auN"));
        check(h, Feather.get().authPending(a.player), "a new player was not held by the auth gate: " + console.out);
        other.clear();
        SocialTests.chat(a, "hello-from-pending");
        a.cmd("home");
        a.cmd("tpa " + other.name());
        Feather.get().openHub(a.player);
        check(h, a.dialogCount() == 0, "a pending player got a menu");
        check(h, other.chat().stream().noneMatch(s -> s.contains("hello-from-pending")), "a pending player's chat reached others");
        check(h, other.chat().stream().noneMatch(s -> s.contains(a.name())), "a pending player's /tpa reached others: " + other.chat());
        SocialTests.chat(a, "0000000");
        h.runAfterDelay(40, () -> {
            check(h, Feather.get().authPending(a.player), "a wrong code let the player in (online " + !a.player.hasDisconnected() + "): " + a.chat());
            SocialTests.chat(a, "testcode1234");
            check(h, other.chat().stream().noneMatch(s -> s.contains("testcode1234")), "the server code was broadcast");
            waitUntil(h, 30, () -> !Feather.get().authPending(a.player), ok -> {
                console.cmd(h.getLevel(), "auth clearcode");
                a.leave();
                other.leave();
                check(h, ok, "the right code did not let the player in: " + a.chat());
                h.succeed();
            });
        });
    }

    /** Polls every 10 ticks (the code check runs PBKDF2 off-thread), up to {@code tries} times. */
    static void waitUntil(GameTestHelper h, int tries, java.util.function.BooleanSupplier cond, java.util.function.Consumer<Boolean> then) {
        h.runAfterDelay(10, () -> {
            boolean ok = cond.getAsBoolean();
            if (ok || tries <= 1) then.accept(ok);
            else waitUntil(h, tries - 1, cond, then);
        });
    }

    @GameTest(maxTicks = 100, environment = "aftrhrs_gametest:core_maintenance")
    public void maintenanceKeepsPlayersOut(GameTestHelper h) {
        Bots.Console console = new Bots.Console();
        Bots.Bot staff = Bots.join(h, Bots.name("mtS"));
        staff.op(true);
        Bots.Bot p = Bots.join(h, Bots.name("mtP"));
        console.cmd(h.getLevel(), "maintenance on");
        h.runAfterDelay(5, () -> {
            boolean kicked = p.player.hasDisconnected();
            boolean staffStays = !staff.player.hasDisconnected();
            var status = FeatherEvents.SERVER_STATUS.invoker().modify(h.getLevel().getServer().getStatus());
            console.cmd(h.getLevel(), "maintenance off");
            staff.op(false);
            staff.leave();
            if (!p.player.hasDisconnected()) p.leave();
            check(h, kicked, "maintenance did not move a player out");
            check(h, staffStays, "maintenance kicked staff");
            check(h, status != null, "no server-list entry");
            encodeStatus(h, status);
            h.succeed();
        });
    }

    static void encodeStatus(GameTestHelper h, net.minecraft.network.protocol.status.ServerStatus status) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundStatusResponsePacket.STREAM_CODEC.encode(buf, new ClientboundStatusResponsePacket(status));
        } finally {
            buf.release();
        }
    }

    @GameTest(maxTicks = 40, environment = ENV)
    public void serverListEntryEncodes(GameTestHelper h) {
        Bots.Bot a = Bots.join(h, Bots.name("motd"));
        for (int i = 0; i < 6; i++) {
            var status = FeatherEvents.SERVER_STATUS.invoker().modify(h.getLevel().getServer().getStatus());
            encodeStatus(h, status);
        }
        a.leave();
        h.succeed();
    }

    /** chat.unsigned: false keeps chat signed (the formatted line as unsigned content); both modes deliver and encode. */
    @GameTest(maxTicks = 60, environment = ENV)
    public void chatBothModes(GameTestHelper h) {
        LogWatch log = new LogWatch();
        Bots.Bot a = Bots.join(h, Bots.name("chA"));
        Bots.Bot b = Bots.join(h, Bots.name("chB"));
        ChatModule chat = ChatModule.active();
        check(h, chat != null, "the chat module is off");
        var cfg = Feather.get().getConfig();
        boolean before = cfg.getBoolean("chat.unsigned", true);
        try {
            for (boolean unsigned : new boolean[]{true, false}) {
                cfg.set("chat.unsigned", unsigned);
                chat.reload();
                b.clear();
                String word = "mode-" + unsigned + "-line";
                SocialTests.chat(a, word + " @" + b.name() + " https://example.com <bold>x");
                check(h, b.chat().stream().anyMatch(s -> s.contains(word)), (unsigned ? "unsigned" : "signed") + " chat did not arrive: " + b.chat());
            }
        } finally {
            cfg.set("chat.unsigned", before);
            chat.reload();
        }
        a.leave();
        b.leave();
        log.close();
        CoreTests.noErrors(h, log, "chat modes");
        h.succeed();
    }

    @GameTest(maxTicks = 60, environment = "aftrhrs_gametest:core_mutechat")
    public void muteChatSilencesPlayersNotStaff(GameTestHelper h) {
        Bots.Bot staff = Bots.join(h, Bots.name("mcS"));
        Bots.Bot p = Bots.join(h, Bots.name("mcP"));
        Bots.Bot listener = Bots.join(h, Bots.name("mcL"));
        staff.op(true);
        staff.cmd("mutechat");
        listener.clear();
        SocialTests.chat(p, "while-muted-line");
        SocialTests.chat(staff, "staff-while-muted");
        boolean leaked = listener.heard("while-muted-line");
        boolean staffHeard = listener.heard("staff-while-muted");
        staff.cmd("mutechat");
        listener.clear();
        SocialTests.chat(p, "after-unmute-line");
        boolean back = listener.heard("after-unmute-line");
        staff.op(false);
        staff.leave();
        p.leave();
        listener.leave();
        check(h, !leaked, "chat got through /mutechat");
        check(h, staffHeard, "staff were silenced by /mutechat too");
        check(h, back, "chat did not come back after /mutechat off");
        h.succeed();
    }
}
