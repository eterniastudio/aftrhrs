package net.axther.aftrhrs.entity.ability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One MythicMobs skill line, parsed: {@code - potion{type=SLOWNESS;duration=%bond(60,12)%} @target ~onAttack ?hasowner 0.5}.
 * Arguments keep their text; {@link Ctx#num} evaluates numbers and the bond macros.
 */
public record Line(String text, String mechanic, Map<String, String> args, String target, Map<String, String> targetArgs,
                   @Nullable String trigger, @Nullable String triggerArg, int every, List<String> conditions, double chance) {

    public String arg(String... keys) {
        for (String k : keys) if (args.containsKey(k)) return args.get(k);
        return null;
    }

    public String arg(String key, String def) {
        String v = args.get(key);
        return v == null ? def : v;
    }

    public static @Nullable Line parse(String raw) {
        String line = raw.trim();
        if (line.startsWith("- ")) line = line.substring(2).trim();
        if (line.isEmpty()) return null;
        List<String> toks = tokens(line);
        String mech = toks.getFirst();
        String name = mech;
        Map<String, String> args = new LinkedHashMap<>();
        int brace = mech.indexOf('{');
        if (brace > 0 && mech.endsWith("}")) {
            name = mech.substring(0, brace);
            args = args(mech.substring(brace + 1, mech.length() - 1));
        }
        String target = "self";
        Map<String, String> targetArgs = Map.of();
        String trigger = null, triggerArg = null;
        int every = 0;
        double chance = 1;
        List<String> conds = new ArrayList<>();
        for (int i = 1; i < toks.size(); i++) {
            String t = toks.get(i);
            if (t.startsWith("@")) {
                int b = t.indexOf('{');
                target = (b > 0 ? t.substring(1, b) : t.substring(1)).toLowerCase(Locale.ROOT);
                if (b > 0 && t.endsWith("}")) targetArgs = args(t.substring(b + 1, t.length() - 1));
            } else if (t.startsWith("~")) {
                String tr = t.substring(1);
                int c = tr.indexOf(':');
                if (c > 0) {
                    triggerArg = tr.substring(c + 1);
                    tr = tr.substring(0, c);
                    if (tr.equalsIgnoreCase("onTimer")) every = Integer.parseInt(triggerArg);
                }
                trigger = tr.toLowerCase(Locale.ROOT);
            } else if (t.startsWith("?")) {
                conds.add(t.substring(1));
            } else if (t.equals("true") || t.equals("false")) {
                // "?cond false" form: negate the last condition
                if (t.equals("false") && !conds.isEmpty()) {
                    String last = conds.removeLast();
                    conds.add(last.startsWith("!") ? last.substring(1) : "!" + last);
                }
            } else {
                try {
                    chance = Double.parseDouble(t);
                } catch (NumberFormatException ignored) {
                    // unknown token
                }
            }
        }
        return new Line(raw.trim(), name.toLowerCase(Locale.ROOT), args, target, targetArgs, trigger, triggerArg, every, conds, chance);
    }

    static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (char ch : line.toCharArray()) {
            if (ch == '{' || ch == '[') depth++;
            else if (ch == '}' || ch == ']') depth--;
            if (ch == ' ' && depth == 0) {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    static Map<String, String> args(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        List<String> parts = new ArrayList<>();
        for (char ch : body.toCharArray()) {
            if (ch == '{' || ch == '[' || ch == '(') depth++;
            else if (ch == '}' || ch == ']' || ch == ')') depth--;
            if (ch == ';' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty()) parts.add(cur.toString());
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0) out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return out;
    }
}
