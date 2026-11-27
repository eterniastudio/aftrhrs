package net.axther.aftrhrs.feature.bank;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The vault's locks, cracked through dialogs (which never pause the game: the police keep coming). Each game is
 * random every time and keeps its answer on the server; the client only ever sees what the robber needs to play.
 * Every dialog a game opens carries the game's step number, so a click on an old or copied dialog does nothing.
 *
 * <ul>
 *   <li><b>Safe dial</b>: a combination of numbers; each guess tells, per number, higher, lower or right.</li>
 *   <li><b>Wires</b>: a rule card and colored wires; cut the right wires in the right order.</li>
 *   <li><b>Memory</b>: a code of symbols flashes up for a few seconds, then it has to be entered.</li>
 * </ul>
 */
final class Minigames {
    static final Random RANDOM = new SecureRandom();

    /** Settings (robbery.games in bank.yml). */
    int dialNumbers = 3;
    int dialMax = 20;
    int dialGuesses = 6;
    int wiresMin = 4;
    int wiresMax = 6;
    int memoryLength = 5;
    int memorySeconds = 4;

    private final Robberies robberies;

    Minigames(Robberies robberies) {
        this.robberies = robberies;
    }

    void reload(Yaml c) {
        if (c == null) return;
        dialNumbers = clamp(c.getInt("dial.numbers", 3), 1, 5);
        dialMax = clamp(c.getInt("dial.max", 20), 3, 99);
        dialGuesses = clamp(c.getInt("dial.guesses", 6), 1, 30);
        wiresMin = clamp(c.getInt("wires.min", 4), 3, 8);
        wiresMax = clamp(c.getInt("wires.max", 6), wiresMin, 8);
        memoryLength = clamp(c.getInt("memory.length", 5), 2, 10);
        memorySeconds = clamp(c.getInt("memory.show-seconds", 4), 1, 30);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    Game create(String type) {
        return switch (type) {
            case "wires" -> new Wires();
            case "memory" -> new Memory();
            default -> new Dial();
        };
    }

    private Text text() {
        return robberies.bank.text();
    }

    private Dialogs.Menu menu(String titleKey) {
        return robberies.bank.dialogs.menu(text().msg(titleKey, Text.unparsed("stage", robberies.stageNumber()),
                Text.unparsed("stages", robberies.stageCount())));
    }

    /** One lock. {@link #open} shows its current state; clicks run only while it is the robbery's live game. */
    abstract class Game {
        int step;
        boolean done;

        abstract String type();

        abstract void open(ServerPlayer p);

        void cancel() {
            done = true;
        }

        /** Wraps a click: ignored unless this game is live and the dialog is the latest one. */
        Consumer<ServerPlayer> click(Consumer<ServerPlayer> action) {
            int at = step;
            return p -> {
                if (done || step != at || !robberies.isLiveGame(this, p)) return;
                step++;
                action.accept(p);
            };
        }

        void solved(ServerPlayer p) {
            done = true;
            robberies.gameSolved(this, p);
        }

        void failed(ServerPlayer p, String reasonKey) {
            done = true;
            robberies.gameFailed(this, p, reasonKey);
        }
    }

    // ------------------------------------------------------------------ safe dial

    final class Dial extends Game {
        final int[] secret = new int[dialNumbers];
        final int[] last = new int[dialNumbers];
        final List<Component> log = new ArrayList<>();
        int left = dialGuesses;

        Dial() {
            for (int i = 0; i < secret.length; i++) {
                secret[i] = RANDOM.nextInt(dialMax + 1);
                last[i] = dialMax / 2;
            }
        }

        @Override
        String type() {
            return "dial";
        }

        @Override
        void open(ServerPlayer p) {
            Dialogs.Menu m = menu("bank.robbery.dial.title");
            m.text(text().msg("bank.robbery.dial.body", Text.unparsed("numbers", secret.length), Text.unparsed("max", dialMax),
                    Text.unparsed("left", left)), 300);
            if (!log.isEmpty()) {
                Component all = Component.empty();
                for (int i = 0; i < log.size(); i++) all = all.append(i == 0 ? Component.empty() : Component.newline()).append(log.get(i));
                m.text(all, 300);
            }
            for (int i = 0; i < secret.length; i++) {
                m.input(Dialogs.Input.numberRange("n" + i, text().msg("bank.robbery.dial.number", Text.unparsed("index", i + 1)), 0, dialMax)
                        .step(1f).initial((float) last[i]).width(260).build());
            }
            int at = step;
            m.button(text().msg("bank.robbery.dial.try"), null, 150, (pl, view) -> {
                if (done || step != at || !robberies.isLiveGame(this, pl)) return;
                step++;
                int[] guess = new int[secret.length];
                for (int i = 0; i < secret.length; i++) {
                    Float f = view == null ? null : view.getFloat("n" + i);
                    guess[i] = f == null ? -1 : Math.round(f);
                    if (guess[i] < 0 || guess[i] > dialMax) {
                        open(pl);
                        return;
                    }
                }
                guess(pl, guess);
            });
            m.exit(text().msg("bank.robbery.step-away"), null);
            m.open(p);
        }

        void guess(ServerPlayer p, int[] guess) {
            boolean all = true;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < secret.length; i++) {
                last[i] = guess[i];
                if (i > 0) sb.append(" <dim>·</dim> ");
                sb.append("<text>").append(guess[i]).append("</text> ");
                if (guess[i] == secret[i]) sb.append(text().raw("bank.robbery.dial.right"));
                else {
                    all = false;
                    sb.append(text().raw(guess[i] < secret[i] ? "bank.robbery.dial.higher" : "bank.robbery.dial.lower"));
                }
            }
            if (all) {
                robberies.click(p);
                solved(p);
                return;
            }
            left--;
            log.add(text().parse(text().raw("bank.robbery.dial.log").replace("<guess>", sb.toString()),
                    Text.unparsed("n", log.size() + 1)));
            if (left <= 0) {
                failed(p, "bank.robbery.dial.out-of-guesses");
                return;
            }
            robberies.click(p);
            open(p);
        }
    }

    // ------------------------------------------------------------------ wires

    enum Color {
        RED("#FF5555", "Red"), BLUE("#5599FF", "Blue"), YELLOW("#FFE14D", "Yellow"), GREEN("#55DD66", "Green"),
        WHITE("#F4F4F4", "White"), BLACK("#555566", "Black");

        final String hex;
        final String label;

        Color(String hex, String label) {
            this.hex = hex;
            this.label = label;
        }

        String tag() {
            return "<" + hex + ">" + label + "</" + hex + ">";
        }
    }

    final class Wires extends Game {
        final List<Color> wires = new ArrayList<>();
        /** Indices to cut, in order. Never sent to the client. */
        final List<Integer> order = new ArrayList<>();
        final boolean[] cut;
        String rule;
        int progress;

        Wires() {
            int n = wiresMin + RANDOM.nextInt(wiresMax - wiresMin + 1);
            Color[] all = Color.values();
            for (int i = 0; i < n; i++) wires.add(all[RANDOM.nextInt(all.length)]);
            cut = new boolean[n];
            makeRule();
        }

        private List<Integer> where(Color c) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < wires.size(); i++) if (wires.get(i) == c) out.add(i);
            return out;
        }

        private Color present() {
            return wires.get(RANDOM.nextInt(wires.size()));
        }

        private void makeRule() {
            int n = wires.size();
            switch (RANDOM.nextInt(4)) {
                case 0 -> {
                    // "Cut the X wires first, left to right, then the rest right to left."
                    Color x = present();
                    List<Integer> xs = where(x);
                    order.addAll(xs);
                    for (int i = n - 1; i >= 0; i--) if (!xs.contains(i)) order.add(i);
                    rule = text().raw("bank.robbery.wires.rule-first").replace("<color>", x.tag());
                }
                case 1 -> {
                    // "If there are two or more X wires, cut right to left; otherwise left to right."
                    Color x = Color.values()[RANDOM.nextInt(Color.values().length)];
                    boolean many = where(x).size() >= 2;
                    for (int i = 0; i < n; i++) order.add(many ? n - 1 - i : i);
                    rule = text().raw("bank.robbery.wires.rule-count").replace("<color>", x.tag());
                }
                case 2 -> {
                    // "Leave the X wires alone; cut every other wire left to right."
                    Color x = present();
                    for (int i = 0; i < n; i++) if (wires.get(i) != x) order.add(i);
                    if (order.isEmpty()) {
                        // All one color: the rule would say "cut nothing". Use the plain rule instead.
                        for (int i = 0; i < n; i++) order.add(i);
                        rule = text().raw("bank.robbery.wires.rule-plain");
                    } else {
                        rule = text().raw("bank.robbery.wires.rule-skip").replace("<color>", x.tag());
                    }
                }
                default -> {
                    // A color order printed on the card (random each time), left to right within a color.
                    List<Color> colors = new ArrayList<>(List.of(Color.values()));
                    Collections.shuffle(colors, RANDOM);
                    StringBuilder sb = new StringBuilder();
                    for (Color c : colors) {
                        if (!sb.isEmpty()) sb.append(" <dim>›</dim> ");
                        sb.append(c.tag());
                        order.addAll(where(c));
                    }
                    rule = text().raw("bank.robbery.wires.rule-order").replace("<colors>", sb.toString());
                }
            }
        }

        @Override
        String type() {
            return "wires";
        }

        @Override
        void open(ServerPlayer p) {
            Dialogs.Menu m = menu("bank.robbery.wires.title");
            m.text(text().msg("bank.robbery.wires.body", Text.unparsed("count", wires.size())), 300);
            m.text(text().parse(text().raw("bank.robbery.wires.card").replace("<rule>", rule)), 300);
            for (int i = 0; i < wires.size(); i++) {
                Color c = wires.get(i);
                int idx = i;
                String key = cut[i] ? "bank.robbery.wires.cut-wire" : "bank.robbery.wires.wire";
                Component label = text().parse(text().raw(key).replace("<color>", c.tag()).replace("</hex>", "</" + c.hex + ">").replace("<hex>", "<" + c.hex + ">"),
                        Text.unparsed("index", i + 1));
                if (cut[i]) m.button(label, null, 300, pl -> {
                });
                else m.button(label, null, 300, click(pl -> cutWire(pl, idx)));
            }
            m.columns(1);
            m.exit(text().msg("bank.robbery.step-away"), null);
            m.open(p);
        }

        void cutWire(ServerPlayer p, int idx) {
            if (cut[idx]) {
                open(p);
                return;
            }
            if (order.get(progress) != idx) {
                failed(p, "bank.robbery.wires.wrong");
                return;
            }
            cut[idx] = true;
            progress++;
            robberies.click(p);
            if (progress >= order.size()) solved(p);
            else open(p);
        }
    }

    // ------------------------------------------------------------------ memory

    static final String[] SYMBOLS = {"★", "♦", "♣", "♥", "☀", "☂", "♫", "⚑"};
    static final String[] SYMBOL_COLORS = {"#FFD24A", "#7FE3FF", "#7CE38B", "#FF7C9C", "#FFB347", "#B39CFF", "#FF9AE0", "#F4F4F4"};

    final class Memory extends Game {
        final int[] code = new int[memoryLength];
        boolean showing = true;
        long showUntil;
        int progress;
        Tasks.Task reveal;

        Memory() {
            for (int i = 0; i < code.length; i++) code[i] = RANDOM.nextInt(SYMBOLS.length);
        }

        @Override
        String type() {
            return "memory";
        }

        private String sym(int i) {
            return "<" + SYMBOL_COLORS[i] + "><b>" + SYMBOLS[i] + "</b></" + SYMBOL_COLORS[i] + ">";
        }

        @Override
        void open(ServerPlayer p) {
            if (showing) {
                if (showUntil == 0) {
                    showUntil = System.currentTimeMillis() + memorySeconds * 1000L;
                    UUID id = p.getUUID();
                    reveal = Tasks.later(memorySeconds * 20L, () -> {
                        showing = false;
                        ServerPlayer pl = robberies.bank.plugin.player(id);
                        if (pl != null && !done && robberies.isLiveGame(this, pl)) open(pl);
                    });
                }
                StringBuilder sb = new StringBuilder();
                for (int c : code) sb.append(sym(c)).append("  ");
                Dialogs.Menu m = menu("bank.robbery.memory.title");
                m.text(text().msg("bank.robbery.memory.memorize", Text.unparsed("seconds", Math.max(1, (showUntil - System.currentTimeMillis() + 999) / 1000))), 300);
                m.text(text().parse("<font:minecraft:uniform>" + sb.toString().trim()), 300);
                // "Ready": straight to entering the code (it also comes up by itself when the time is over).
                m.exit(text().msg("bank.robbery.memory.ready"), click(pl -> {
                    showing = false;
                    if (reveal != null) reveal.cancel();
                    open(pl);
                }));
                m.open(p);
                return;
            }
            StringBuilder entered = new StringBuilder();
            for (int i = 0; i < code.length; i++) entered.append(i < progress ? sym(code[i]) : "<dim>_</dim>").append(' ');
            Dialogs.Menu m = menu("bank.robbery.memory.title");
            m.text(text().msg("bank.robbery.memory.enter", Text.unparsed("length", code.length)), 300);
            m.text(text().parse(entered.toString().trim()), 300);
            for (int i = 0; i < SYMBOLS.length; i++) {
                int s = i;
                m.button(text().parse(sym(i)), null, 70, click(pl -> press(pl, s)));
            }
            m.columns(4);
            m.exit(text().msg("bank.robbery.step-away"), null);
            m.open(p);
        }

        void press(ServerPlayer p, int s) {
            if (showing) return;
            if (code[progress] != s) {
                failed(p, "bank.robbery.memory.wrong");
                return;
            }
            progress++;
            robberies.click(p);
            if (progress >= code.length) solved(p);
            else open(p);
        }

        @Override
        void cancel() {
            super.cancel();
            if (reveal != null) reveal.cancel();
        }
    }
}
