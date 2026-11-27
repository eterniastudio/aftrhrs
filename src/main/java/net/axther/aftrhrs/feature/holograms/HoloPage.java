package net.axther.aftrhrs.feature.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One page of a hologram: its lines (top to bottom) and the click actions that run while a player is on it. */
public final class HoloPage {
    final List<HoloLine> lines = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();

    public List<HoloLine> lines() {
        return lines;
    }

    public List<Action> actions() {
        return actions;
    }

    HoloPage copy() {
        HoloPage p = new HoloPage();
        for (HoloLine l : lines) p.lines.add(l.copy());
        p.actions.addAll(actions);
        return p;
    }

    /**
     * A click action: which click ({@code left}, {@code right} or {@code any}), what it does ({@code message},
     * {@code command}, {@code console}, {@code menu}, {@code sound}, {@code page}) and its value. Written as
     * {@code "<click> <type>: <value>"}.
     */
    public record Action(String click, String type, String value) {
        static final List<String> CLICKS = List.of("left", "right", "any");
        static final List<String> TYPES = List.of("message", "command", "console", "menu", "sound", "page");

        /** Parses {@code "<click> <type>: <value>"} (or {@code "<type>: <value>"} for any click); null if malformed. */
        static Action parse(String s) {
            if (s == null) return null;
            String t = s.strip();
            String click = "any";
            int sp = t.indexOf(' ');
            if (sp > 0 && CLICKS.contains(t.substring(0, sp).toLowerCase(Locale.ROOT))) {
                click = t.substring(0, sp).toLowerCase(Locale.ROOT);
                t = t.substring(sp + 1).strip();
            }
            int colon = t.indexOf(':');
            if (colon <= 0) return null;
            String type = t.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = t.substring(colon + 1).strip();
            if (!TYPES.contains(type) || value.isEmpty()) return null;
            return new Action(click, type, value);
        }

        boolean matches(boolean left) {
            return click.equals("any") || click.equals(left ? "left" : "right");
        }

        @Override
        public String toString() {
            return click + " " + type + ": " + value;
        }
    }
}
