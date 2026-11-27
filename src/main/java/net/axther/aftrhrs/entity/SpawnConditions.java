package net.axther.aftrhrs.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The MythicMobs conditions of the species spawn rules ({@code spawns[].conditions}), compiled once.
 *
 * <p>A line is {@code <expr> [true|false]}; an expression is an atom, {@code (<line>)}, or lines joined with
 * {@code ||} / {@code &&} (each operand may carry its own {@code true|false}), e.g.
 * {@code (worldtime{t=11500to14000} || worldtime{t=0to1500}) true} or {@code (lunarphase{p=4} false || night false) true}.
 * Atoms: {@code outside}, {@code inside}, {@code night}, {@code day}, {@code dawn}, {@code raining}, {@code thundering},
 * {@code worldtime{t=A to B}} (wraps past midnight; also {@code <N}, {@code >N}, {@code N}), {@code height{h=<N}}
 * ({@code >N}, {@code <=N}, {@code >=N}, {@code AtoB}, {@code N}; also {@code y=}/{@code height=}) on the spawn block's Y,
 * {@code lunarphase{p=N}} (day % 8, Bukkit's moon phase). Anything else is a {@link IllegalArgumentException}: the
 * caller logs it and the rule never matches (no silent "true").
 */
public final class SpawnConditions {
    /** A compiled condition, tested at the spawn position. */
    @FunctionalInterface
    public interface Cond {
        boolean test(ServerLevel level, BlockPos pos);

        Cond TRUE = (l, p) -> true;
        Cond FALSE = (l, p) -> false;
    }

    private static final Pattern SUFFIX = Pattern.compile("(?s)(.*\\S)\\s+(true|false)");
    private static final Pattern ATOM = Pattern.compile("([a-z_]+)(?:\\{(.*)})?");

    private SpawnConditions() {
    }

    /** All lines of a rule, ANDed. Throws {@link IllegalArgumentException} on syntax it doesn't know. */
    public static Cond compile(List<String> lines) {
        if (lines.isEmpty()) return Cond.TRUE;
        List<Cond> all = new ArrayList<>();
        for (String l : lines) all.add(parse(l));
        if (all.size() == 1) return all.getFirst();
        return (level, pos) -> {
            for (Cond c : all) if (!c.test(level, pos)) return false;
            return true;
        };
    }

    /** One condition line. Throws {@link IllegalArgumentException} on syntax it doesn't know. */
    public static Cond parse(String line) {
        String s = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("empty condition");
        return line(s);
    }

    private static Cond line(String s) {
        s = s.trim();
        List<String> parts = split(s, "||");
        if (parts.size() > 1) {
            List<Cond> any = new ArrayList<>();
            for (String p : parts) any.add(line(p));
            return (level, pos) -> {
                for (Cond c : any) if (c.test(level, pos)) return true;
                return false;
            };
        }
        parts = split(s, "&&");
        if (parts.size() > 1) {
            List<Cond> all = new ArrayList<>();
            for (String p : parts) all.add(line(p));
            return (level, pos) -> {
                for (Cond c : all) if (!c.test(level, pos)) return false;
                return true;
            };
        }
        boolean want = true;
        Matcher m = SUFFIX.matcher(s);
        if (m.matches()) {
            s = m.group(1).trim();
            want = m.group(2).equals("true");
        }
        Cond c = wrapped(s) ? line(s.substring(1, s.length() - 1)) : atom(s);
        if (want) return c;
        return (level, pos) -> !c.test(level, pos);
    }

    /** "(...)" where the first parenthesis closes at the very end. */
    private static boolean wrapped(String s) {
        if (!s.startsWith("(") || !s.endsWith(")")) return false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i == s.length() - 1;
        }
        return false;
    }

    /** Splits at top-level {@code op} (not inside () or {}). */
    private static List<String> split(String s, String op) {
        List<String> out = new ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '{') depth++;
            else if (ch == ')' || ch == '}') depth--;
            else if (depth == 0 && s.startsWith(op, i)) {
                out.add(s.substring(from, i));
                from = i + op.length();
                i += op.length() - 1;
            }
        }
        out.add(s.substring(from));
        if (out.size() > 1) for (String p : out) if (p.isBlank()) throw new IllegalArgumentException("dangling " + op + " in " + s);
        return out;
    }

    private static Cond atom(String s) {
        Matcher m = ATOM.matcher(s.trim());
        if (!m.matches()) throw new IllegalArgumentException("unknown condition: " + s);
        String name = m.group(1), args = m.group(2);
        return switch (name) {
            case "outside" -> (level, pos) -> level.canSeeSky(pos.above());
            case "inside" -> (level, pos) -> !level.canSeeSky(pos.above());
            case "night" -> (level, pos) -> Conditions.night(level);
            case "day" -> (level, pos) -> Conditions.day(level);
            case "dawn" -> (level, pos) -> Conditions.dawn(level);
            case "raining" -> (level, pos) -> level.isRaining();
            case "thundering" -> (level, pos) -> level.isThundering();
            case "worldtime" -> worldtime(arg(args, s, "t", "time"));
            case "height" -> {
                Range r = Range.parse(arg(args, s, "h", "height", "y"));
                yield (level, pos) -> r.test(pos.getY());
            }
            case "lunarphase" -> {
                int phase = Integer.parseInt(arg(args, s, "p", "phase").trim());
                yield (level, pos) -> Math.floorMod(Math.floorDiv(level.getDefaultClockTime(), 24000L), 8L) == phase;
            }
            default -> throw new IllegalArgumentException("unknown condition: " + s);
        };
    }

    private static Cond worldtime(String v) {
        String t = v.replace(" ", "");
        int to = t.indexOf("to", 1);
        if (to > 0) {
            long a = Long.parseLong(t.substring(0, to)), b = Long.parseLong(t.substring(to + 2));
            return (level, pos) -> Conditions.timeIn(level, a, b);
        }
        Range r = Range.parse(t);
        return (level, pos) -> r.test(Conditions.time(level));
    }

    /** The value of {@code key=...} inside the braces (first matching alias). */
    private static String arg(String args, String atom, String... keys) {
        if (args == null) throw new IllegalArgumentException("missing arguments: " + atom);
        for (String part : args.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq).trim();
            for (String key : keys) if (k.equals(key)) return part.substring(eq + 1).trim();
        }
        throw new IllegalArgumentException("missing " + keys[0] + "= in " + atom);
    }

    /** A MythicMobs ranged number: {@code <N}, {@code >N}, {@code <=N}, {@code >=N}, {@code AtoB}, {@code N}. */
    record Range(double min, boolean minIncl, double max, boolean maxIncl) {
        static Range parse(String v) {
            String t = v.replace(" ", "");
            try {
                if (t.startsWith("<=")) return new Range(Double.NEGATIVE_INFINITY, true, Double.parseDouble(t.substring(2)), true);
                if (t.startsWith(">=")) return new Range(Double.parseDouble(t.substring(2)), true, Double.POSITIVE_INFINITY, true);
                if (t.startsWith("<")) return new Range(Double.NEGATIVE_INFINITY, true, Double.parseDouble(t.substring(1)), false);
                if (t.startsWith(">")) return new Range(Double.parseDouble(t.substring(1)), false, Double.POSITIVE_INFINITY, true);
                int to = t.indexOf("to", 1);
                if (to > 0) return new Range(Double.parseDouble(t.substring(0, to)), true, Double.parseDouble(t.substring(to + 2)), true);
                double n = Double.parseDouble(t);
                return new Range(n, true, n, true);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad range: " + v);
            }
        }

        boolean test(double x) {
            return (minIncl ? x >= min : x > min) && (maxIncl ? x <= max : x < max);
        }
    }
}
