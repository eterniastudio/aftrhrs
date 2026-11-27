package net.axther.aftrhrs.feature.quests;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The quests and server goals defined in {@code quests.yml}, and how a reported action ("mine" + "stone") matches one.
 * A quest has a type ({@link #TYPES}), an optional target, an amount, a difficulty, rewards and a weight (how often it
 * is picked). Targets are exact ids ({@code diamond_ore}, {@code ArcanaCorgi}), globs ({@code *_log}) or tags
 * ({@code #logs}, block, item or entity tags), several separated by commas.
 */
final class QuestPool {
    /** Every quest type, in the order the docs list them. */
    static final List<String> TYPES = List.of("tame", "codex", "bond", "harvest", "scythe", "mine", "hammer", "fell", "boss",
            "fish", "kill", "deposit", "visit", "walk", "ride", "chatgame", "undead_cull");
    static final List<String> DIFFICULTIES = List.of("easy", "medium", "hard");

    record Reward(long coins, int bond) {
    }

    /** One quest. {@code party}: boss quests only count fights with at least this many players. */
    record Def(String id, boolean weekly, String type, String target, String targetName, int amount, String difficulty,
               int party, Reward reward, int weight, String text, Predicate<String> matcher) {
        boolean matches(String reported) {
            return matcher == null || matcher.test(reported == null ? "" : reported);
        }
    }

    /** A timed server-wide boost a goal unlocks: {@code quest-coins} or {@code bond-xp}, times {@code multiplier}. */
    record Boost(String type, double multiplier, long hours) {
    }

    /** A server goal. {@code amount} is before the multiplier; {@code minContribution} 0 means 1% of the target. */
    record Goal(String id, String type, String target, String targetName, long amount, long minContribution, Reward reward,
                List<Boost> boosts, List<String> commands, String unlocks, int weight, String text, Predicate<String> matcher) {
        boolean matches(String reported) {
            return matcher == null || matcher.test(reported == null ? "" : reported);
        }
    }

    final Map<String, Def> daily = new LinkedHashMap<>();
    final Map<String, Def> weekly = new LinkedHashMap<>();
    final Map<String, Goal> goals = new LinkedHashMap<>();

    void load(Yaml yml, Logger log) {
        daily.clear();
        weekly.clear();
        goals.clear();
        Map<String, Reward> dailyRewards = rewards(yml.getConfigurationSection("rewards.daily"), new long[]{10, 20, 35}, new int[]{10, 20, 30});
        Map<String, Reward> weeklyRewards = rewards(yml.getConfigurationSection("rewards.weekly"), new long[]{60, 110, 180}, new int[]{50, 90, 140});
        readQuests(yml.getConfigurationSection("daily"), false, dailyRewards, daily, log);
        readQuests(yml.getConfigurationSection("weekly"), true, weeklyRewards, weekly, log);
        Yaml g = yml.getConfigurationSection("goals");
        Reward goalDefault = new Reward(Math.max(0, yml.getLong("server-goal.reward.coins", 150)), Math.max(0, yml.getInt("server-goal.reward.bond-xp", 60)));
        if (g != null) {
            for (String id : g.getKeys(false)) {
                Yaml s = g.getConfigurationSection(id);
                if (s == null) continue;
                String type = s.getString("type", "").trim().toLowerCase(Locale.ROOT);
                if (!TYPES.contains(type)) {
                    log.warn("quests.yml goals.{}: unknown type '{}' (use one of {}).", id, type, TYPES);
                    continue;
                }
                long amount = s.getLong("amount", 0);
                if (amount <= 0) {
                    log.warn("quests.yml goals.{}: amount must be above 0.", id);
                    continue;
                }
                List<Boost> boosts = new ArrayList<>();
                for (Map<?, ?> m : s.getMapList("boosts")) {
                    String bt = String.valueOf(m.get("type")).trim().toLowerCase(Locale.ROOT);
                    if (!bt.equals("quest-coins") && !bt.equals("bond-xp")) {
                        log.warn("quests.yml goals.{}: unknown boost type '{}' (quest-coins or bond-xp).", id, bt);
                        continue;
                    }
                    double mult = m.get("multiplier") instanceof Number n ? n.doubleValue() : 1.5;
                    long hours = m.get("hours") instanceof Number n ? n.longValue() : 48;
                    if (mult > 1 && hours > 0) boosts.add(new Boost(bt, Math.min(10, mult), Math.min(24 * 30, hours)));
                }
                Reward reward = new Reward(Math.max(0, s.getLong("reward.coins", goalDefault.coins())),
                        Math.max(0, s.getInt("reward.bond-xp", goalDefault.bond())));
                String target = s.getString("target", "");
                goals.put(id, new Goal(id, type, target, s.getString("target-name", ""), amount, Math.max(0, s.getLong("min-contribution", 0)),
                        reward, boosts, s.getStringList("commands"), s.getString("unlocks", ""), Math.max(0, s.getInt("weight", 1)),
                        s.getString("text", ""), matcher(target, log, "goals." + id)));
            }
        }
        log.info("Quests: {} daily, {} weekly, {} server goal(s).", daily.size(), weekly.size(), goals.size());
    }

    private static Map<String, Reward> rewards(Yaml s, long[] coins, int[] bond) {
        Map<String, Reward> out = new LinkedHashMap<>();
        for (int i = 0; i < DIFFICULTIES.size(); i++) {
            String d = DIFFICULTIES.get(i);
            out.put(d, new Reward(s == null ? coins[i] : Math.max(0, s.getLong(d + ".coins", coins[i])),
                    s == null ? bond[i] : Math.max(0, s.getInt(d + ".bond-xp", bond[i]))));
        }
        return out;
    }

    private void readQuests(Yaml sec, boolean isWeekly, Map<String, Reward> defaults, Map<String, Def> into, Logger log) {
        if (sec == null) return;
        String where = isWeekly ? "weekly." : "daily.";
        for (String id : sec.getKeys(false)) {
            Yaml s = sec.getConfigurationSection(id);
            if (s == null) continue;
            String type = s.getString("type", "").trim().toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                log.warn("quests.yml {}{}: unknown type '{}' (use one of {}).", where, id, type, TYPES);
                continue;
            }
            int amount = s.getInt("amount", 0);
            if (amount <= 0) {
                log.warn("quests.yml {}{}: amount must be above 0.", where, id);
                continue;
            }
            String diff = s.getString("difficulty", "easy").trim().toLowerCase(Locale.ROOT);
            if (!DIFFICULTIES.contains(diff)) {
                log.warn("quests.yml {}{}: difficulty must be easy, medium or hard; using easy.", where, id);
                diff = "easy";
            }
            Reward def = defaults.get(diff);
            Reward reward = new Reward(Math.max(0, s.getLong("rewards.coins", def.coins())), Math.max(0, s.getInt("rewards.bond-xp", def.bond())));
            String target = s.getString("target", "");
            if (daily.containsKey(id) || weekly.containsKey(id)) {
                log.warn("quests.yml: quest id '{}' is used twice; ids must be unique across daily and weekly.", id);
                continue;
            }
            into.put(id, new Def(id, isWeekly, type, target, s.getString("target-name", ""), amount, diff, Math.max(1, s.getInt("party", 1)),
                    reward, Math.max(0, s.getInt("weight", 1)), s.getString("text", ""), matcher(target, log, where + id)));
        }
    }

    Def def(String id) {
        if (id == null) return null;
        Def d = daily.get(id);
        return d != null ? d : weekly.get(id);
    }

    // ------------------------------------------------------------------ targets

    /** Null (matches everything) for an empty target. */
    static Predicate<String> matcher(String raw, Logger log, String where) {
        if (raw == null || raw.isBlank()) return null;
        List<Predicate<String>> parts = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String t = strip(piece);
            if (t.isEmpty()) continue;
            if (t.startsWith("#") && rarityTag(t.substring(1)) != null) {
                parts.add(rarityTag(t.substring(1)));
            } else if (t.startsWith("#")) {
                Predicate<String> tag = tag(t.substring(1));
                if (tag == null) log.warn("quests.yml {}: unknown tag #{}.", where, t.substring(1));
                else parts.add(tag);
            } else if (t.contains("*")) {
                Pattern p = Pattern.compile(Pattern.quote(t).replace("*", "\\E.*\\Q"), Pattern.CASE_INSENSITIVE);
                parts.add(s -> p.matcher(strip(s)).matches());
            } else {
                parts.add(s -> strip(s).equalsIgnoreCase(t));
            }
        }
        if (parts.isEmpty()) return null;
        return s -> {
            for (Predicate<String> p : parts) if (p.test(s)) return true;
            return false;
        };
    }

    private static final List<String> RARITIES = List.of("common", "uncommon", "rare", "legendary");

    /** {@code #rare} (that rarity) or {@code #rare+} (that rarity or better); null when the name isn't a rarity. */
    private static Predicate<String> rarityTag(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean plus = n.endsWith("+");
        int want = RARITIES.indexOf(plus ? n.substring(0, n.length() - 1) : n);
        if (want < 0) return null;
        return s -> {
            Feather f = Feather.get();
            Hooks.FishRarity lookup = f == null ? null : f.service(Hooks.FishRarity.class);
            String r = lookup == null ? null : lookup.rarity(strip(s));
            int have = r == null ? -1 : RARITIES.indexOf(r.toLowerCase(Locale.ROOT));
            return have >= 0 && (plus ? have >= want : have == want);
        };
    }

    private static String strip(String s) {
        String t = s == null ? "" : s.trim();
        return t.regionMatches(true, 0, "minecraft:", 0, 10) ? t.substring(10) : t;
    }

    /** A block, item or entity tag ({@code logs}, {@code fishes}, {@code skeletons}); null if no such tag exists. */
    private static Predicate<String> tag(String name) {
        Identifier key = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
        if (key == null) return null;
        List<Predicate<String>> checks = new ArrayList<>();
        addTag(checks, BuiltInRegistries.BLOCK, TagKey.create(Registries.BLOCK, key));
        addTag(checks, BuiltInRegistries.ITEM, TagKey.create(Registries.ITEM, key));
        addTag(checks, BuiltInRegistries.ENTITY_TYPE, TagKey.create(Registries.ENTITY_TYPE, key));
        if (checks.isEmpty()) return null;
        return s -> {
            for (Predicate<String> c : checks) if (c.test(s)) return true;
            return false;
        };
    }

    private static <T> void addTag(List<Predicate<String>> into, Registry<T> registry, TagKey<T> tag) {
        if (registry.get(tag).isEmpty()) return;
        into.add(s -> {
            Identifier id = Identifier.tryParse(strip(s).toLowerCase(Locale.ROOT));
            if (id == null) return false;
            Optional<Holder.Reference<T>> h = registry.get(ResourceKey.create(registry.key(), id));
            return h.isPresent() && h.get().is(tag);
        });
    }
}
