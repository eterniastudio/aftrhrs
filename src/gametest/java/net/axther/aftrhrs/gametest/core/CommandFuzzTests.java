package net.axther.aftrhrs.gametest.core;

import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs every bound Feather command with odd arguments, as a player without op, as an op and from the console, plus
 * every tab completion: nothing may throw (CommandBridge would answer "Something went wrong") or log an error.
 */
public class CommandFuzzTests {
    /** Global switches and things that leave the server unusable for other tests or need the network. */
    static final Set<String> SKIP = Set.of("maintenance", "auth", "mutechat", "rtp", "stop",
            // Saved world state the other suites stand in: spawn (everyone joins there) and claims around it.
            "setspawn", "claim", "unclaim", "trust", "untrust", "claimblocks");

    static List<String> args(String other) {
        return List.of("", "help", "zz_nobody_here", other, other + " 99999999999999999999", other + " -1", other + " 0 0",
                "-1", "0", "99999999999999999999", "1.5e308 NaN Infinity", "list", "set", "add", "remove", "delete", "info", "toggle",
                "on", "off", "all", "*", "@a", "<red>x</red> <click:run_command:'/op x'>y", "名前ü ✦", "a  b", " ",
                "x ".repeat(40).trim(), "set " + other + " 5", "add " + other, "remove " + other, "give " + other + " 64",
                "../../../etc", "home.x", "\u0000\u0007");
    }

    @SuppressWarnings("unchecked")
    static Map<String, FeatherCommand> bound() {
        try {
            Field f = CommandBridge.class.getDeclaredField("BOUND");
            f.setAccessible(true);
            return new TreeMap<>((Map<String, FeatherCommand>) f.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Own batch: /tpall, /sudo, /kick ... would reach the players of tests running beside it. */
    @GameTest(maxTicks = 2400, environment = "aftrhrs_gametest:core_isolated")
    public void everyCommandSurvivesOddArguments(GameTestHelper h) {
        LogWatch log = new LogWatch();
        List<String> failures = new ArrayList<>();
        Bots.Bot a = Bots.join(h, Bots.name("fzA"));
        Bots.Bot[] b = {Bots.join(h, Bots.name("fzB"))};
        Bots.Console console = new Bots.Console();
        Map<String, FeatherCommand> cmds = bound();
        int[] ran = {0}, answered = {0};
        try {
            for (int phase = 0; phase < 3; phase++) {
                a.op(phase == 1);
                for (String name : cmds.keySet()) {
                    if (SKIP.contains(name)) continue;
                    for (String arg : args(b[0].name())) {
                        if (b[0].player.hasDisconnected()) b[0] = Bots.join(h, Bots.name("fzB"));
                        if (a.player.hasDisconnected()) {
                            failures.add("before /" + name + " " + arg + ": the sender was disconnected later");
                            a = Bots.join(h, Bots.name("fzA"));
                            a.op(phase == 1);
                        }
                        String line = (name + " " + arg).trim();
                        log.clear();
                        console.out.clear();
                        try {
                            // Control characters typed in chat kick the client (vanilla): feed those to the dispatcher.
                            if (phase < 2 && line.chars().anyMatch(Character::isISOControl)) a.exec(line);
                            else if (phase < 2) a.cmd(line);
                            else console.cmd(h.getLevel(), line);
                        } catch (Throwable t) {
                            failures.add("/" + line + " threw " + t);
                        }
                        if (a.player.hasDisconnected()) {
                            failures.add("/" + line + " [" + phaseName(phase) + "] disconnected the sender: " + a.chat());
                            a = Bots.join(h, Bots.name("fzA"));
                            a.op(phase == 1);
                            continue;
                        }
                        List<String> said = phase < 2 ? a.chat() : console.out;
                        if (!said.isEmpty() || a.dialogCount() > 0) answered[0]++;
                        ran[0]++;
                        for (String s : said) if (s.contains("Something went wrong")) failures.add("/" + line + " [" + phaseName(phase) + "] failed");
                        for (String e : log.errors()) failures.add("/" + line + " [" + phaseName(phase) + "] logged " + e);
                        for (String p : a.problems()) failures.add("/" + line + " " + p);
                        // A nickname like "all" would make the next "/ban all" ban the sender (correctly, by nickname).
                        if (name.equals("nick")) a.exec("nick off");
                        a.problems().clear();
                    }
                }
            }
            // Tab completion: CommandBridge swallows exceptions here, so call the handlers directly.
            a.op(false);
            for (int phase = 0; phase < 2; phase++) {
                a.op(phase == 1);
                Sender sender = Sender.of(a.player);
                for (Map.Entry<String, FeatherCommand> e : cmds.entrySet()) {
                    for (String arg : args(b[0].name())) {
                        String[] parts = (arg + " ").split(" ", -1);
                        for (int n = 1; n <= Math.min(parts.length, 5); n++) {
                            String[] sub = java.util.Arrays.copyOf(parts, n);
                            try {
                                e.getValue().onTabComplete(sender, e.getKey(), sub);
                            } catch (Throwable t) {
                                failures.add("tab /" + e.getKey() + " " + String.join(" ", sub) + " threw " + t + LogWatchAt.at(t));
                            }
                        }
                    }
                }
            }
        } finally {
            a.op(false);
        }
        Bots.Bot fa = a;
        h.runAfterDelay(60, () -> {
            for (String e : log.errors()) failures.add("later: " + e);
            log.close();
            fa.leave();
            b[0].leave();
            net.axther.aftrhrs.AftrHrs.LOG.info("command fuzz: {} commands, {} runs, {} answered", cmds.size(), ran[0], answered[0]);
            if (cmds.size() < 100) failures.add("only " + cmds.size() + " commands are bound");
            if (answered[0] < ran[0] / 2) failures.add("only " + answered[0] + " of " + ran[0] + " runs answered anything");
            if (!failures.isEmpty()) {
                List<String> dedup = failures.stream().distinct().limit(40).toList();
                throw h.assertionException(CoreTests.lit(failures.size() + " command failures:\n" + String.join("\n", dedup)));
            }
            h.succeed();
        });
    }

    private static String phaseName(int phase) {
        return switch (phase) {
            case 0 -> "player";
            case 1 -> "op";
            default -> "console";
        };
    }

    /** Where in our code a throwable came from. */
    static final class LogWatchAt {
        static String at(Throwable t) {
            for (StackTraceElement e : t.getStackTrace()) if (e.getClassName().startsWith("net.axther")) return " at " + e;
            return "";
        }
    }
}
