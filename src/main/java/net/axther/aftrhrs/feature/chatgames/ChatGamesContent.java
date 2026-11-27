package net.axther.aftrhrs.feature.chatgames;

import net.axther.aftrhrs.core.Yaml;
import org.slf4j.Logger;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the chat games ask: the trivia pool, word lists and Fast Type phrases from {@code chatgames.yml}, the creature
 * clues from {@code guide.yml}, and the maths problems made on the spot. Also how a typed line is compared with an
 * answer ({@link #norm}).
 */
final class ChatGamesContent {
    /** The game types, in the order they're listed in the docs and config. */
    enum Type {
        MATH("math"), TRIVIA("trivia"), UNSCRAMBLE("unscramble"), FAST_TYPE("fast-type"), CREATURE("creature"), GAPS("gaps");

        final String key;

        Type(String key) {
            this.key = key;
        }

        static Type of(String key) {
            if (key == null) return null;
            String k = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Type t : values()) if (t.key.equals(k) || t.key.replace("-", "").equals(k.replace("-", ""))) return t;
            return null;
        }
    }

    /**
     * One game: what the chat line shows ({@code shown}: the problem, question, scrambled word, phrase or clue), an
     * optional hint (a word's category, a creature's home), the answer shown afterwards, and the accepted answers
     * (already {@link #norm normalized}; for Fast Type the exact phrase).
     */
    record Round(Type type, String difficulty, String shown, String hint, String answer, Set<String> accepted, boolean exact) {
        boolean matches(String typed) {
            if (typed == null) return false;
            if (exact) return typed.trim().equals(answer);
            String n = norm(typed);
            if (n.isEmpty()) return false;
            return accepted.contains(n) || accepted.contains(n.replace(" ", ""));
        }
    }

    record Trivia(String question, List<String> answers) {
    }

    record Creature(String id, String name, String clue, String habitat, String kind, List<String> aliases) {
    }

    final List<Trivia> trivia = new ArrayList<>();
    /** Category -> words. */
    final Map<String, List<String>> words = new LinkedHashMap<>();
    final List<String> phrases = new ArrayList<>();
    final List<Creature> creatures = new ArrayList<>();
    /** Recently used entries per type, so the same one doesn't come back too soon. */
    private final Map<Type, Deque<String>> recent = new LinkedHashMap<>();

    void load(Yaml yml, Yaml guide, Logger log) {
        trivia.clear();
        words.clear();
        phrases.clear();
        creatures.clear();
        for (Map<?, ?> m : yml.getMapList("trivia")) {
            Object q = m.get("q");
            Object a = m.get("a");
            List<String> answers = new ArrayList<>();
            if (a instanceof List<?> l) {
                for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) answers.add(String.valueOf(o).trim());
            } else if (a != null && !String.valueOf(a).isBlank()) {
                answers.add(String.valueOf(a).trim());
            }
            if (q == null || String.valueOf(q).isBlank() || answers.isEmpty()) {
                log.warn("chatgames.yml trivia: skipping an entry without q and a.");
                continue;
            }
            trivia.add(new Trivia(String.valueOf(q), answers));
        }
        Yaml w = yml.getConfigurationSection("words");
        if (w != null) {
            for (String cat : w.getKeys(false)) {
                List<String> list = new ArrayList<>();
                for (String s : w.getStringList(cat)) if (s != null && norm(s).replace(" ", "").length() >= 4) list.add(s.trim());
                if (!list.isEmpty()) words.put(cat, list);
            }
        }
        for (String s : yml.getStringList("phrases")) if (s != null && !s.isBlank()) phrases.add(s.trim().replaceAll("\\s+", " "));

        Set<String> skip = new LinkedHashSet<>(yml.getStringList("creature-skip"));
        Yaml aliases = yml.getConfigurationSection("creature-aliases");
        Yaml cs = guide == null ? null : guide.getConfigurationSection("creatures");
        // An old guide.yml (book pages only) has no creatures: use the bundled ones.
        if ((cs == null || cs.getKeys(false).isEmpty()) && guide != null && guide.getDefaults() != null) {
            cs = guide.getDefaults().getConfigurationSection("creatures");
        }
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                Yaml c = cs.getConfigurationSection(id);
                if (c == null || skip.contains(id)) continue;
                String name = c.getString("name", "").trim();
                String ability = c.getString("ability", "").trim();
                if (name.isEmpty() || ability.isEmpty()) continue;
                List<String> al = aliases == null ? List.of() : aliases.getStringList(id);
                creatures.add(new Creature(id, name, hide(ability, name), hide(c.getString("habitat", ""), name), c.getString("kind", ""), al));
            }
        }
        recent.clear();
    }

    /** The clue without the creature's own name in it ("Arrives named Luna" gives it away). */
    private static String hide(String text, String name) {
        if (text == null) return "";
        String out = text.replaceAll("(?i)" + Pattern.quote(name), "???");
        for (String part : name.split("\\s+")) if (part.length() >= 4) out = out.replaceAll("(?i)\\b" + Pattern.quote(part) + "\\b", "???");
        return out;
    }

    /** Whether a game of this type can be made from the current content. */
    boolean has(Type t) {
        return switch (t) {
            case MATH -> true;
            case TRIVIA -> !trivia.isEmpty();
            case UNSCRAMBLE, GAPS -> !words.isEmpty();
            case FAST_TYPE -> !phrases.isEmpty();
            case CREATURE -> !creatures.isEmpty();
        };
    }

    /** A fresh round of this type, or null when its content is empty. */
    Round make(Type t, Random r, double mediumChance) {
        if (!has(t)) return null;
        return switch (t) {
            case MATH -> math(r, r.nextDouble() < mediumChance);
            case TRIVIA -> {
                Trivia q = pick(t, trivia, r, Trivia::question);
                yield new Round(t, "", q.question(), "", q.answers().get(0), accepted(q.answers()), false);
            }
            case UNSCRAMBLE -> {
                String[] cw = word(t, r);
                yield new Round(t, "", scramble(cw[1], r), cw[0], cw[1], accepted(List.of(cw[1])), false);
            }
            case GAPS -> {
                String[] cw = word(t, r);
                yield new Round(t, "", gaps(cw[1], r), cw[0], cw[1], accepted(List.of(cw[1])), false);
            }
            case FAST_TYPE -> {
                String p = pick(t, phrases, r, s -> s);
                yield new Round(t, "", p, "", p, Set.of(p), true);
            }
            case CREATURE -> {
                Creature c = pick(t, creatures, r, Creature::id);
                List<String> names = new ArrayList<>();
                names.add(c.name());
                names.addAll(c.aliases());
                yield new Round(t, "", c.clue(), c.habitat(), c.name(), accepted(names), false);
            }
        };
    }

    /** A random entry, avoiding the last few used (up to half the pool). */
    private <T> T pick(Type t, List<T> pool, Random r, java.util.function.Function<T, String> key) {
        Deque<String> used = recent.computeIfAbsent(t, k -> new ArrayDeque<>());
        int keep = Math.min(20, pool.size() / 2);
        List<T> fresh = new ArrayList<>();
        for (T e : pool) if (!used.contains(key.apply(e))) fresh.add(e);
        List<T> from = fresh.isEmpty() ? pool : fresh;
        T chosen = from.get(r.nextInt(from.size()));
        used.addLast(key.apply(chosen));
        while (used.size() > keep) used.pollFirst();
        return chosen;
    }

    /** {category, word}. */
    private String[] word(Type t, Random r) {
        List<String[]> all = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : words.entrySet()) for (String w : e.getValue()) all.add(new String[]{e.getKey(), w});
        return pick(t, all, r, a -> a[1]);
    }

    static Set<String> accepted(List<String> answers) {
        Set<String> out = new LinkedHashSet<>();
        for (String a : answers) {
            String n = norm(a);
            if (n.isEmpty()) continue;
            out.add(n);
            out.add(n.replace(" ", ""));
        }
        return out;
    }

    // ------------------------------------------------------------------ maths

    /** Easy: a + b, a − b, a × b. Medium: a × b ± c, a + b − c, a ÷ b (always whole, never negative). */
    static Round math(Random r, boolean medium) {
        String shown;
        long answer;
        if (!medium) {
            switch (r.nextInt(3)) {
                case 0 -> {
                    int a = 12 + r.nextInt(78), b = 12 + r.nextInt(78);
                    shown = a + " + " + b;
                    answer = a + b;
                }
                case 1 -> {
                    int a = 30 + r.nextInt(70), b = 11 + r.nextInt(a - 15);
                    shown = a + " − " + b;
                    answer = a - b;
                }
                default -> {
                    int a = 3 + r.nextInt(10), b = 3 + r.nextInt(10);
                    shown = a + " × " + b;
                    answer = (long) a * b;
                }
            }
        } else {
            switch (r.nextInt(4)) {
                case 0 -> {
                    int a = 11 + r.nextInt(15), b = 3 + r.nextInt(7), c = 5 + r.nextInt(36);
                    shown = a + " × " + b + " + " + c;
                    answer = (long) a * b + c;
                }
                case 1 -> {
                    int a = 11 + r.nextInt(15), b = 3 + r.nextInt(7);
                    int c = 1 + r.nextInt(Math.min(40, a * b - 1));
                    shown = a + " × " + b + " − " + c;
                    answer = (long) a * b - c;
                }
                case 2 -> {
                    int a = 100 + r.nextInt(400), b = 50 + r.nextInt(250), c = 20 + r.nextInt(a + b - 20);
                    shown = a + " + " + b + " − " + c;
                    answer = a + b - c;
                }
                default -> {
                    int b = 3 + r.nextInt(10), q = 6 + r.nextInt(20);
                    shown = (b * q) + " ÷ " + b;
                    answer = q;
                }
            }
        }
        String a = String.valueOf(answer);
        return new Round(Type.MATH, medium ? "medium" : "easy", shown, "", a, Set.of(a), false);
    }

    // ------------------------------------------------------------------ words

    /** Each word's letters shuffled (never left as they were), in capitals. */
    static String scramble(String phrase, Random r) {
        List<String> out = new ArrayList<>();
        for (String w : phrase.trim().split("\\s+")) {
            String up = w.toUpperCase(Locale.ROOT);
            String s = up;
            boolean allSame = up.chars().distinct().count() <= 1;
            for (int i = 0; i < 30 && s.equals(up) && !allSame; i++) {
                List<Character> cs = new ArrayList<>();
                for (char c : up.toCharArray()) cs.add(c);
                Collections.shuffle(cs, r);
                StringBuilder b = new StringBuilder();
                for (char c : cs) b.append(c);
                s = b.toString();
            }
            out.add(s);
        }
        return String.join("  ", out);
    }

    /**
     * The words in capitals with about 40% of each word's letters hidden (never its first letter, at least one in a word
     * of 3+ letters): "M O _ S B _ N".
     */
    static String gaps(String phrase, Random r) {
        List<String> out = new ArrayList<>();
        for (String w : phrase.trim().toUpperCase(Locale.ROOT).split("\\s+")) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = 1; i < w.length(); i++) if (Character.isLetter(w.charAt(i))) candidates.add(i);
            Collections.shuffle(candidates, r);
            int hide = w.length() < 3 ? 0 : Math.min(candidates.size(), Math.max(1, Math.round(w.length() * 0.4f)));
            Set<Integer> hidden = new java.util.HashSet<>(candidates.subList(0, hide));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < w.length(); i++) {
                if (i > 0) b.append(' ');
                b.append(hidden.contains(i) ? '_' : w.charAt(i));
            }
            out.add(b.toString());
        }
        return String.join("   ", out);
    }

    // ------------------------------------------------------------------ answers

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d)[,' _](?=\\d{3}(?!\\d))");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern ARTICLE = Pattern.compile("^(the|a|an) ");

    /**
     * An answer as compared: no accents, lower case, "1,000" as "1000", punctuation as spaces, single spaces, no leading
     * "the"/"a"/"an" and no trailing ".0" on a number.
     */
    static String norm(String s) {
        if (s == null) return "";
        String t = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        t = t.toLowerCase(Locale.ROOT).trim();
        String prev;
        do {
            prev = t;
            t = THOUSANDS.matcher(t).replaceAll("");
        } while (!t.equals(prev));
        t = t.replaceAll("(?<=\\d)\\.0+\\b", "");
        t = NON_WORD.matcher(t).replaceAll(" ").trim();
        t = ARTICLE.matcher(t).replaceFirst("");
        return t;
    }
}
