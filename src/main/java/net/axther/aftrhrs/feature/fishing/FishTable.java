package net.axther.aftrhrs.feature.fishing;

import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.Coast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The species table from fishing.yml: biome groups (biome tags, glob patterns on the biome key, exact keys, other
 * groups with {@code @group}, exclusions with a leading {@code !}, so Terralith's {@code terralith:*} biomes sort in by
 * name), species with their conditions and weights, rods, bait and enchantments, and the roll itself. Rolling is two
 * steps: a rarity (base weight times rod, bait, Luck of the Sea, Lucky Lure, Ripplekit and hotspot, among rarities
 * that have at least one species that can bite here), then a species of that rarity. The weight roll is log-normal
 * around the typical weight, pushed toward the heavy end by Luck of the Sea (and a little by Lure), with a per-level
 * chance of a trophy roll near the species' max.
 */
final class FishTable {
    static final List<String> RARITIES = List.of("common", "uncommon", "rare", "legendary");

    static int rank(String rarity) {
        return rarity == null ? -1 : RARITIES.indexOf(rarity.toLowerCase(Locale.ROOT));
    }

    enum Water { ANY, FRESH, SALT }

    enum Time { ANY, DAY, NIGHT, DAWN, DUSK, TWILIGHT }

    enum Weather { ANY, CLEAR, RAIN, THUNDER }

    /**
     * A species. {@code biomes}: any of these groups (water and place); {@code climate}: and any of these groups
     * (temperature); {@code bait}: the bait it likes (its chance times {@code catch.preferred-bait-bonus}).
     */
    record Species(String id, String name, String rarity, double chance, Water water, List<String> biomes, List<String> climate,
                   Time time, Weather weather, Boolean underground, String needsRod, String bait, int minGrams, int typicalGrams,
                   int maxGrams, Map<String, Double> bonus) {
        String oraxenId() {
            return "arcana_fish_" + id;
        }

        int rank() {
            return FishTable.rank(rarity);
        }
    }

    /** A rod or bait: bite speed (wait time multiplier), rarity weight multipliers, when they apply, fight help. */
    record Gear(String id, double speed, Map<String, Double> weights, String when, double fight) {
    }

    /** Luck of the Sea / Lure numbers per enchantment level (fishing.yml {@code enchantments}). */
    record Enchant(Map<String, Double> perLevel, double weightSkew, double trophyChance, double fightPower) {
        static final Enchant NONE = new Enchant(Map.of(), 0, 0, 0);

        double rarity(String rarity, int level) {
            return level <= 0 ? 1 : 1 + perLevel.getOrDefault(rarity, 0.0) * level;
        }
    }

    /** Where and how a line is in the water when a fish bites. {@code luck}/{@code lure}: the rod's enchantment levels. */
    record Context(String biome, Set<String> groups, boolean salt, boolean underground, long time, boolean storm,
                   boolean thunder, boolean fullMoon, boolean openWater, String rod, String bait, boolean luckyLure,
                   boolean ripplekit, double hotspotRare, int luck, int lure) {
        boolean hotspot() {
            return hotspotRare > 1;
        }

        boolean night() {
            return time >= 13000 && time < 23000;
        }

        boolean day() {
            return time >= 0 && time < 12000 || time >= 23500;
        }

        boolean dawn() {
            return time >= 22500 || time < 1500;
        }

        boolean dusk() {
            return time >= 11500 && time < 13500;
        }
    }

    /** One rule of a group: a tag, a glob, another group ({@code @name}) or an exact key; {@code not} excludes. */
    private record Rule(String raw, Pattern glob, String tag, String group, boolean not) {
    }

    final Map<String, Species> species = new LinkedHashMap<>();
    final Map<String, Gear> rods = new LinkedHashMap<>();
    final Map<String, Gear> bait = new LinkedHashMap<>();
    private final Map<String, List<Rule>> groups = new LinkedHashMap<>();
    private final Map<String, Set<String>> groupCache = new HashMap<>();
    private final Map<String, Set<String>> tagCache = new HashMap<>();
    final Map<String, Double> rarityWeights = new LinkedHashMap<>();
    final Map<String, String> hintNames = new HashMap<>();
    List<String> saltGroups = List.of("ocean", "beach");
    double luckyLureRare = 1.5, luckyLureLegendary = 1.5;
    double ripplekitRare = 1.3;
    /** Rarity rank from which a fish only bites in open water (4 = never needed). */
    int openWaterFrom = 3;
    double preferredBaitBonus = 2.0;
    Enchant luckOfTheSea = Enchant.NONE, lureEnchant = Enchant.NONE;
    double maxSkew = 0.85, maxTrophy = 0.5;
    int undergroundMaxY = 50, undergroundMaxSky = 0;

    static final double[] DEFAULT_WEIGHTS = {62, 26, 10, 2};

    void load(Yaml yml, Consumer<String> warn) {
        species.clear();
        rods.clear();
        bait.clear();
        groups.clear();
        groupCache.clear();
        tagCache.clear();
        rarityWeights.clear();
        hintNames.clear();
        for (int i = 0; i < RARITIES.size(); i++) {
            rarityWeights.put(RARITIES.get(i), Math.max(0, yml.getDouble("catch.rarity-weights." + RARITIES.get(i), DEFAULT_WEIGHTS[i])));
        }
        // Open water: "catch.open-water-from" (none | rare | legendary); the old switch still works without it.
        String ow = yml.getString("catch.open-water-from", null);
        if (ow == null) openWaterFrom = yml.getBoolean("catch.rare-needs-open-water", false) ? 2 : 3;
        else openWaterFrom = rank(ow) < 0 ? 4 : rank(ow);
        preferredBaitBonus = Math.max(1, yml.getDouble("catch.preferred-bait-bonus", 2.0));
        undergroundMaxY = yml.getInt("catch.underground.max-y", 50);
        undergroundMaxSky = Math.max(0, Math.min(15, yml.getInt("catch.underground.max-sky-light", 0)));
        luckyLureRare = Math.max(1, yml.getDouble("lucky-lure.rare-multiplier", 1.5));
        luckyLureLegendary = Math.max(1, yml.getDouble("lucky-lure.legendary-multiplier", 1.5));
        ripplekitRare = Math.max(1, yml.getDouble("ripplekit.rare-weight", 1.3));
        luckOfTheSea = enchant(yml.getConfigurationSection("enchantments.luck-of-the-sea"),
                Map.of("uncommon", 0.1, "rare", 0.3, "legendary", 0.35), 0.12, 0.04, 0.05);
        lureEnchant = enchant(yml.getConfigurationSection("enchantments.lure"), Map.of(), 0.04, 0.0, 0.0);
        maxSkew = Math.max(0, Math.min(0.95, yml.getDouble("enchantments.max-weight-skew", 0.85)));
        maxTrophy = Math.max(0, Math.min(1, yml.getDouble("enchantments.max-trophy-chance", 0.5)));
        List<String> salt = yml.getStringList("salt-water-groups");
        saltGroups = salt.isEmpty() ? List.of("ocean", "beach") : salt;

        Yaml g = yml.getConfigurationSection("biome-groups");
        if (g != null) {
            for (String name : g.getKeys(false)) {
                List<Rule> rules = new ArrayList<>();
                for (String raw : g.getStringList(name)) {
                    String r = raw.trim().toLowerCase(Locale.ROOT);
                    boolean not = r.startsWith("!");
                    if (not) r = r.substring(1).trim();
                    if (r.isEmpty()) continue;
                    if (r.startsWith("@")) rules.add(new Rule(r, null, null, r.substring(1), not));
                    else if (r.startsWith("#")) rules.add(new Rule(r, null, r.substring(1).contains(":") ? r.substring(1) : "minecraft:" + r.substring(1), null, not));
                    else if (r.contains("*")) rules.add(new Rule(r, Pattern.compile(Pattern.quote(r).replace("*", "\\E.*\\Q")), null, null, not));
                    else rules.add(new Rule(r.contains(":") ? r : "minecraft:" + r, null, null, null, not));
                }
                groups.put(name.toLowerCase(Locale.ROOT), rules);
            }
            for (Map.Entry<String, List<Rule>> e : groups.entrySet()) {
                for (Rule r : e.getValue()) {
                    if (r.group() != null && !groups.containsKey(r.group())) warn.accept("fishing.yml biome-groups." + e.getKey() + ": unknown group '@" + r.group() + "'.");
                }
            }
        }
        Yaml hn = yml.getConfigurationSection("hint-names");
        if (hn != null) for (String k : hn.getKeys(false)) hintNames.put(k.toLowerCase(Locale.ROOT), hn.getString(k, k));

        Yaml s = yml.getConfigurationSection("species");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                Yaml c = s.getConfigurationSection(id);
                if (c == null) continue;
                String rarity = c.getString("rarity", "common").toLowerCase(Locale.ROOT);
                if (rank(rarity) < 0) {
                    warn.accept("fishing.yml species." + id + ": unknown rarity '" + rarity + "'; using common.");
                    rarity = "common";
                }
                List<Integer> grams = c.getIntegerList("grams");
                int min = grams.size() > 0 ? grams.get(0) : 100, typ = grams.size() > 1 ? grams.get(1) : min * 3, max = grams.size() > 2 ? grams.get(2) : typ * 3;
                min = Math.max(1, min);
                typ = Math.max(min, typ);
                max = Math.max(typ, max);
                List<String> biomes = groupList(c.getStringList("biomes"), id, "biome group", warn);
                List<String> climate = groupList(c.getStringList("climate"), id, "climate group", warn);
                Map<String, Double> bonus = new LinkedHashMap<>();
                Yaml bs = c.getConfigurationSection("bonus");
                if (bs != null) for (String k : bs.getKeys(false)) bonus.put(k.toLowerCase(Locale.ROOT), Math.max(0, bs.getDouble(k, 1)));
                Boolean underground = c.contains("underground") ? c.getBoolean("underground") : null;
                String rod = c.getString("needs-rod", "");
                String likes = c.getString("bait", "");
                species.put(id.toLowerCase(Locale.ROOT), new Species(id.toLowerCase(Locale.ROOT), c.getString("name", pretty(id)), rarity,
                        Math.max(0, c.getDouble("chance", 10)), parse(Water.class, c.getString("water", "any"), Water.ANY, warn, id),
                        biomes, climate, parse(Time.class, c.getString("time", "any"), Time.ANY, warn, id),
                        parse(Weather.class, c.getString("weather", "any"), Weather.ANY, warn, id), underground,
                        rod == null || rod.isBlank() ? null : rod.trim().toLowerCase(Locale.ROOT),
                        likes == null || likes.isBlank() ? null : likes.trim().toLowerCase(Locale.ROOT), min, typ, max, bonus));
            }
        }
        readGear(yml.getConfigurationSection("rods"), rods);
        readGear(yml.getConfigurationSection("bait"), bait);
        for (Species sp : species.values()) {
            if (sp.bait() != null && !bait.containsKey(sp.bait())) warn.accept("fishing.yml species." + sp.id() + ": bait '" + sp.bait() + "' is not in bait:.");
        }
    }

    private List<String> groupList(List<String> raw, String id, String what, Consumer<String> warn) {
        List<String> out = new ArrayList<>();
        for (String b : raw) {
            String key = b.trim().toLowerCase(Locale.ROOT);
            if (!groups.containsKey(key)) warn.accept("fishing.yml species." + id + ": unknown " + what + " '" + b + "'.");
            out.add(key);
        }
        return List.copyOf(out);
    }

    private static Enchant enchant(Yaml c, Map<String, Double> defRarity, double defSkew, double defTrophy, double defFight) {
        if (c == null) return new Enchant(defRarity, defSkew, defTrophy, defFight);
        Map<String, Double> per = new HashMap<>();
        for (String r : RARITIES) {
            double v = c.getDouble(r + "-per-level", defRarity.getOrDefault(r, 0.0));
            if (v != 0) per.put(r, Math.max(-0.5, v));
        }
        return new Enchant(Map.copyOf(per), Math.max(0, c.getDouble("weight-skew-per-level", defSkew)),
                Math.max(0, c.getDouble("trophy-chance-per-level", defTrophy)), Math.max(0, c.getDouble("fight-per-level", defFight)));
    }

    private static void readGear(Yaml sec, Map<String, Gear> into) {
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            Yaml c = sec.getConfigurationSection(id);
            if (c == null) continue;
            Map<String, Double> w = new LinkedHashMap<>();
            Yaml ws = c.getConfigurationSection("weights");
            if (ws != null) for (String k : ws.getKeys(false)) w.put(k.toLowerCase(Locale.ROOT), Math.max(0, ws.getDouble(k, 1)));
            into.put(id.toLowerCase(Locale.ROOT), new Gear(id.toLowerCase(Locale.ROOT), Math.max(0.05, Math.min(4, c.getDouble("speed", 1))), w,
                    c.getString("when", "any").toLowerCase(Locale.ROOT), Math.max(0, Math.min(1, c.getDouble("fight", 0)))));
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, E def, Consumer<String> warn, String id) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            warn.accept("fishing.yml species." + id + ": unknown " + type.getSimpleName().toLowerCase(Locale.ROOT) + " '" + raw + "'.");
            return def;
        }
    }

    static String pretty(String id) {
        return Coast.pretty(id);
    }

    Set<String> groupNames() {
        return groups.keySet();
    }

    String hintName(String group) {
        return hintNames.getOrDefault(group, group.replace('_', ' '));
    }

    // ------------------------------------------------------------------ biome groups

    /** The groups a biome key ("minecraft:river", "terralith:warm_river") belongs to. Cached per key. */
    Set<String> groupsOf(String biomeKey) {
        String key = biomeKey == null ? "" : biomeKey.toLowerCase(Locale.ROOT);
        Set<String> cached = groupCache.get(key);
        if (cached != null) return cached;
        Map<String, Boolean> memo = new HashMap<>();
        Set<String> out = new LinkedHashSet<>();
        for (String g : groups.keySet()) if (member(g, key, memo, new HashSet<>())) out.add(g);
        Set<String> result = Set.copyOf(out);
        groupCache.put(key, result);
        return result;
    }

    /** In the group: some rule that isn't an exclusion matches, and no exclusion does. A cycle counts as no match. */
    private boolean member(String group, String key, Map<String, Boolean> memo, Set<String> visiting) {
        Boolean known = memo.get(group);
        if (known != null) return known;
        List<Rule> rules = groups.get(group);
        if (rules == null || !visiting.add(group)) return false;
        boolean in = false;
        for (Rule r : rules) if (!r.not() && matches(r, key, memo, visiting)) {
            in = true;
            break;
        }
        if (in) for (Rule r : rules) if (r.not() && matches(r, key, memo, visiting)) {
            in = false;
            break;
        }
        visiting.remove(group);
        memo.put(group, in);
        return in;
    }

    private boolean matches(Rule r, String key, Map<String, Boolean> memo, Set<String> visiting) {
        if (r.group() != null) return member(r.group(), key, memo, visiting);
        if (r.tag() != null) return tagMembers(r.tag()).contains(key);
        if (r.glob() != null) return r.glob().matcher(key).matches();
        return r.raw().equals(key);
    }

    private Set<String> tagMembers(String tag) {
        return tagCache.computeIfAbsent(tag, t -> new LinkedHashSet<>(Coast.biomeTag(t)));
    }

    boolean salt(Set<String> groups) {
        for (String g : saltGroups) if (groups.contains(g)) return true;
        return false;
    }

    // ------------------------------------------------------------------ rolling

    /** Could this species live here (water, biome, climate, underground), whatever the time, weather and gear? */
    boolean habitat(Species s, Set<String> groups, boolean salt, boolean underground) {
        if (s.water() == Water.FRESH && salt) return false;
        if (s.water() == Water.SALT && !salt) return false;
        if (s.underground() != null && s.underground() != underground) return false;
        if (!anyOf(s.biomes(), groups)) return false;
        return anyOf(s.climate(), groups);
    }

    private static boolean anyOf(List<String> wanted, Set<String> have) {
        if (wanted.isEmpty()) return true;
        for (String b : wanted) if (have.contains(b)) return true;
        return false;
    }

    boolean eligible(Species s, Context c) {
        if (s.chance() <= 0) return false;
        if (s.needsRod() != null && !s.needsRod().equals(c.rod())) return false;
        if (!habitat(s, c.groups(), c.salt(), c.underground())) return false;
        boolean timeOk = switch (s.time()) {
            case ANY -> true;
            case DAY -> c.day();
            case NIGHT -> c.night();
            case DAWN -> c.dawn();
            case DUSK -> c.dusk();
            case TWILIGHT -> c.dawn() || c.dusk();
        };
        if (!timeOk) return false;
        boolean weatherOk = switch (s.weather()) {
            case ANY -> true;
            case CLEAR -> !c.storm();
            case RAIN -> c.storm();
            case THUNDER -> c.thunder();
        };
        if (!weatherOk) return false;
        return s.rank() < openWaterFrom || c.openWater();
    }

    double speciesWeight(Species s, Context c) {
        double w = s.chance();
        for (Map.Entry<String, Double> b : s.bonus().entrySet()) {
            boolean on = switch (b.getKey()) {
                case "rain" -> c.storm();
                case "thunder" -> c.thunder();
                case "night" -> c.night();
                case "day" -> c.day();
                case "dawn" -> c.dawn();
                case "dusk" -> c.dusk();
                case "full-moon", "full_moon" -> c.fullMoon() && c.night();
                case "clear" -> !c.storm();
                case "open-water" -> c.openWater();
                case "underground" -> c.underground();
                default -> b.getKey().startsWith("group:") && c.groups().contains(b.getKey().substring(6));
            };
            if (on) w *= b.getValue();
        }
        if (s.bait() != null && s.bait().equals(c.bait())) w *= preferredBaitBonus;
        return w;
    }

    double rarityMultiplier(String rarity, Context c) {
        double m = 1;
        Gear rod = c.rod() == null ? null : rods.get(c.rod());
        if (rod != null && applies(rod, c)) m *= rod.weights().getOrDefault(rarity, 1.0);
        Gear b = c.bait() == null ? null : bait.get(c.bait());
        if (b != null && applies(b, c)) m *= b.weights().getOrDefault(rarity, 1.0);
        m *= luckOfTheSea.rarity(rarity, c.luck());
        m *= lureEnchant.rarity(rarity, c.lure());
        int r = rank(rarity);
        if (c.luckyLure() && r == 2) m *= luckyLureRare;
        if (c.luckyLure() && r == 3) m *= luckyLureLegendary;
        if (c.ripplekit() && r >= 2) m *= ripplekitRare;
        if (c.hotspot() && r >= 2) m *= c.hotspotRare();
        return Math.max(0, m);
    }

    static boolean applies(Gear g, Context c) {
        return switch (g.when()) {
            case "salt", "ocean" -> c.salt();
            case "fresh" -> !c.salt();
            case "night" -> c.night();
            case "day" -> c.day();
            case "rain" -> c.storm();
            default -> true;
        };
    }

    double speed(String rod, String baitId) {
        double s = 1;
        Gear r = rod == null ? null : rods.get(rod);
        if (r != null) s *= r.speed();
        Gear b = baitId == null ? null : bait.get(baitId);
        if (b != null) s *= b.speed();
        return s;
    }

    /** The chance of each rarity here (0..1, summing to 1 over the rarities that can bite), as {@link #roll} picks. */
    Map<String, Double> rarityOdds(Context c) {
        Map<String, Double> tier = tierWeights(c, null, null);
        double total = 0;
        for (double v : tier.values()) total += v;
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : tier.entrySet()) out.put(e.getKey(), total <= 0 ? 0 : e.getValue() / total);
        return out;
    }

    private Map<String, Double> tierWeights(Context c, Map<String, List<Species>> byOut, Map<String, List<Double>> weightsOut) {
        Map<String, List<Species>> by = byOut != null ? byOut : new LinkedHashMap<>();
        Map<String, List<Double>> weights = weightsOut != null ? weightsOut : new LinkedHashMap<>();
        for (Species s : species.values()) {
            if (!eligible(s, c)) continue;
            double w = speciesWeight(s, c);
            if (w <= 0) continue;
            by.computeIfAbsent(s.rarity(), k -> new ArrayList<>()).add(s);
            weights.computeIfAbsent(s.rarity(), k -> new ArrayList<>()).add(w);
        }
        Map<String, Double> tier = new LinkedHashMap<>();
        for (String r : RARITIES) {
            List<Species> list = by.get(r);
            if (list == null) continue;
            double sumW = 0, sumC = 0;
            List<Double> ws = weights.get(r);
            for (int i = 0; i < list.size(); i++) {
                sumW += ws.get(i);
                sumC += list.get(i).chance();
            }
            double lift = sumC <= 0 ? 1 : Math.max(0.25, Math.min(4, sumW / sumC));
            double w = rarityWeights.getOrDefault(r, 0.0) * rarityMultiplier(r, c) * lift;
            if (w > 0) tier.put(r, w);
        }
        return tier;
    }

    /** One roll; null when nothing can bite here (the vanilla catch stays). */
    Species roll(Context c) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Map<String, List<Species>> by = new LinkedHashMap<>();
        Map<String, List<Double>> weights = new LinkedHashMap<>();
        Map<String, Double> tier = tierWeights(c, by, weights);
        double total = 0;
        for (double v : tier.values()) total += v;
        if (total <= 0) return null;
        double x = rnd.nextDouble() * total;
        String pick = null;
        for (Map.Entry<String, Double> e : tier.entrySet()) {
            x -= e.getValue();
            pick = e.getKey();
            if (x < 0) break;
        }
        List<Species> list = by.get(pick);
        List<Double> ws = weights.get(pick);
        double sum = 0;
        for (double w : ws) sum += w;
        double y = rnd.nextDouble() * sum;
        for (int i = 0; i < list.size(); i++) {
            y -= ws.get(i);
            if (y < 0) return list.get(i);
        }
        return list.get(list.size() - 1);
    }

    // ------------------------------------------------------------------ weight

    /** How far the typical weight moves toward the max (0..maxSkew) with these enchantment levels. */
    double weightSkew(int luck, int lure) {
        return Math.min(maxSkew, luckOfTheSea.weightSkew() * Math.max(0, luck) + lureEnchant.weightSkew() * Math.max(0, lure));
    }

    double trophyChance(int luck, int lure) {
        return Math.min(maxTrophy, luckOfTheSea.trophyChance() * Math.max(0, luck) + lureEnchant.trophyChance() * Math.max(0, lure));
    }

    /** The typical weight after the skew: (max/typical)^skew of the way up, in log terms. */
    static double skewedTypical(Species s, double skew) {
        return s.typicalGrams() * Math.pow((double) s.maxGrams() / s.typicalGrams(), Math.max(0, Math.min(0.95, skew)));
    }

    /** A weight in grams without gear: log-normal around the typical weight, kept between min and max. */
    static int rollGrams(Species s) {
        return rollGrams(s, 0, 0, ThreadLocalRandom.current());
    }

    int rollGrams(Species s, int luck, int lure) {
        return rollGrams(s, weightSkew(luck, lure), trophyChance(luck, lure), ThreadLocalRandom.current());
    }

    /** With Heavy Haul ({@code heavy} levels) on the rod: a stronger skew and trophy chance, under the same caps. */
    int rollGrams(Species s, int luck, int lure, int heavy) {
        return rollGrams(s, weightSkew(luck, lure, heavy), trophyChance(luck, lure, heavy), ThreadLocalRandom.current());
    }

    double weightSkew(int luck, int lure, int heavy) {
        return Math.min(maxSkew, weightSkew(luck, lure) + net.axther.aftrhrs.feature.enchants.Enchants.HEAVY_HAUL_SKEW * Math.max(0, heavy));
    }

    double trophyChance(int luck, int lure, int heavy) {
        return Math.min(maxTrophy, trophyChance(luck, lure) + net.axther.aftrhrs.feature.enchants.Enchants.HEAVY_HAUL_TROPHY * Math.max(0, heavy));
    }

    /**
     * Log-normal around the (skewed) typical weight with the species' own spread, kept between min and max; with
     * {@code trophyChance} a trophy roll instead (log-uniform from the trophy line to the max). For a fixed random
     * sequence the result never goes down as the skew goes up.
     */
    static int rollGrams(Species s, double skew, double trophyChance, Random rnd) {
        double sigma = Math.log((double) s.maxGrams() / s.typicalGrams()) / 2.4;
        if (sigma <= 0) sigma = 0.25;
        double gauss = rnd.nextGaussian(), u = rnd.nextDouble(), v = rnd.nextDouble();
        double typ = skewedTypical(s, skew);
        double g = typ * Math.exp(gauss * sigma);
        if (u < trophyChance && s.maxGrams() > s.typicalGrams()) {
            double lo = Math.log(Math.max(typ, FishWeight.trophyGrams(s.typicalGrams(), s.maxGrams()))), hi = Math.log(s.maxGrams());
            g = Math.max(g, Math.exp(lo + v * Math.max(0, hi - lo)));
        }
        return (int) Math.max(s.minGrams(), Math.min(s.maxGrams(), Math.round(g)));
    }
}
