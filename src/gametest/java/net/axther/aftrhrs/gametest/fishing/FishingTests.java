package net.axther.aftrhrs.gametest.fishing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.feature.fishing.FishFight;
import net.axther.aftrhrs.feature.fishing.FishWeight;
import net.axther.aftrhrs.feature.fishing.FishingProbe;
import net.axther.aftrhrs.gametest.content.CT;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.IntTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fishing overhaul: pounds, real weights and the size they drive, the 97 species and where they live, the
 * enchantment odds and weight skew, the reel-in fight's server judgement, and that catches and data from before still
 * load.
 */
public class FishingTests {
    private static final Set<String> REEF = Set.of("clownfish", "surgeonfish", "butterfly_fish", "napoleonfish", "zebra_turkeyfish",
            "moray_eel", "ribbon_eel", "coral_snapper", "giant_trevally", "blowfish", "sea_horse", "tidecrown_koi");

    /** 1000 g is "2.2 lb", 200 g "0.44 lb", tiny and huge fish stay readable. */
    @GameTest
    public void poundsFormatting(GameTestHelper h) {
        CT.Fails f = new CT.Fails("fish-pounds");
        Object[][] cases = {{1000, "2.2 lb"}, {200, "0.44 lb"}, {454, "1.0 lb"}, {453, "1.0 lb"}, {440, "0.97 lb"}, {1, "0.0022 lb"},
                {4, "0.0088 lb"}, {5, "0.01 lb"},
                {4536, "10.0 lb"}, {1_100_000, "2,425.1 lb"}, {12_000_000, "26,455.5 lb"}, {0, "0 lb"}};
        for (Object[] c : cases) {
            String got = FishWeight.format((Integer) c[0]);
            f.check(got.equals(c[1]), c[0] + " g -> '" + got + "', want '" + c[1] + "'");
        }
        f.check(!FishWeight.format(250_000).contains("kg"), "still kilograms somewhere");
        f.finish(h);
    }

    /** Every species: known biome/climate groups, a sane weight range, a place it can live, an item and a model. */
    @GameTest(maxTicks = 100)
    public void speciesTableIsValid(GameTestHelper h) {
        CT.Fails f = new CT.Fails("fish-species");
        f.check(FishingProbe.running(), "fishing module not running");
        List<FishingProbe.SpeciesInfo> all = FishingProbe.species();
        f.check(all.size() >= 97, "only " + all.size() + " species");
        Set<String> groups = FishingProbe.groupNames();
        List<String> biomes = new ArrayList<>();
        h.getLevel().registryAccess().lookupOrThrow(Registries.BIOME).listElementIds().forEach(k -> biomes.add(k.identifier().toString()));
        for (FishingProbe.SpeciesInfo s : all) {
            for (String g : s.biomes()) f.check(groups.contains(g), s.id() + ": unknown biome group " + g);
            for (String g : s.climate()) f.check(groups.contains(g), s.id() + ": unknown climate group " + g);
            f.check(s.minGrams() >= 1 && s.minGrams() <= s.typicalGrams() && s.typicalGrams() <= s.maxGrams(),
                    s.id() + ": weights not min <= typical <= max: " + s.minGrams() + "/" + s.typicalGrams() + "/" + s.maxGrams());
            f.check(s.maxGrams() <= s.minGrams() * 2000L, s.id() + ": weight range spans more than 2000x");
            f.check(s.typicalGrams() <= 25_000_000, s.id() + ": heavier than any fish alive");
            if (!Boolean.TRUE.equals(s.underground())) {
                boolean somewhere = biomes.stream().anyMatch(b -> FishingProbe.livesIn(s.id(), b));
                f.check(somewhere, s.id() + ": no biome matches its water + biomes + climate");
            }
            String hint = FishingProbe.hint(s.id());
            f.check(hint.startsWith("Bites in ") && !hint.contains("_"), s.id() + ": odd hint '" + hint + "'");
            // the item, its model definition, and a size dispatch whose default matches the typical weight
            String item = "arcana_fish_" + s.id();
            f.check(ArcanaContent.stack(item, 1) != null, item + ": no content item");
            JsonObject def = json("/assets/aftrhrs/items/" + item + ".json");
            if (def == null) {
                f.add(item + ": no item model definition");
                continue;
            }
            String text = def.toString();
            var mm = java.util.regex.Pattern.compile("\"model\":\"aftrhrs:([a-z0-9_/]+)\"").matcher(text);
            f.check(mm.find() && json("/assets/aftrhrs/models/" + mm.group(1) + ".json") != null, item + ": model file missing");
            try {
                JsonObject sized = def.getAsJsonObject("model").getAsJsonObject("fallback");
                float typ = sized.getAsJsonObject("fallback").getAsJsonObject("transformation").getAsJsonArray("scale").get(0).getAsFloat();
                f.check(Math.abs(typ - FishWeight.lengthScale(s.typicalGrams())) < 0.002, item + ": default size " + typ + " != "
                        + FishWeight.lengthScale(s.typicalGrams()));
            } catch (RuntimeException e) {
                f.add(item + ": no size dispatch in the item model (" + e + ")");
            }
        }
        // real-world anchors: the order of magnitude has to be right
        Map<String, int[]> anchors = Map.of("whale_shark", new int[]{1_000_000, 25_000_000}, "great_white_shark", new int[]{200_000, 3_000_000},
                "tuna", new int[]{20_000, 700_000}, "guppy", new int[]{1, 5}, "neon_tetra", new int[]{1, 5}, "anchovy", new int[]{3, 100},
                "koi", new int[]{500, 50_000}, "salmon", new int[]{1_000, 50_000}, "carp", new int[]{500, 50_000}, "bluegill", new int[]{20, 3_000});
        for (Map.Entry<String, int[]> a : anchors.entrySet()) {
            FishingProbe.SpeciesInfo s = all.stream().filter(x -> x.id().equals(a.getKey())).findFirst().orElse(null);
            if (s == null) {
                f.add("missing species " + a.getKey());
                continue;
            }
            f.check(s.typicalGrams() >= a.getValue()[0] && s.typicalGrams() <= a.getValue()[1], a.getKey() + ": typical " + s.typicalGrams()
                    + " g is off by an order of magnitude");
        }
        // crab pot species only where they live
        f.check(FishingProbe.livesIn("crawfish", "minecraft:swamp") && !FishingProbe.livesIn("crawfish", "minecraft:ocean"),
                "crawfish habitat wrong");
        f.check(FishingProbe.livesIn("mitten_crab", "minecraft:river"), "mitten crab can't live in a river");
        f.finish(h);
    }

    /** A warm-reef fish never bites in a frozen biome, whatever the roll. */
    @GameTest(maxTicks = 100)
    public void warmReefNeverInFrozen(GameTestHelper h) {
        CT.Fails f = new CT.Fails("fish-reef-frozen");
        List<String> frozen = new ArrayList<>();
        h.getLevel().registryAccess().lookupOrThrow(Registries.BIOME).listElementIds().forEach(k -> {
            String b = k.identifier().toString();
            if (FishingProbe.groupsOf(b).contains("frozen") || FishingProbe.groupsOf(b).contains("cold")) frozen.add(b);
        });
        f.check(frozen.contains("minecraft:frozen_ocean") && frozen.contains("minecraft:frozen_river"), "frozen biomes not grouped: " + frozen);
        for (String b : frozen) for (String r : REEF) f.check(!FishingProbe.livesIn(r, b), r + " could live in " + b);
        for (String b : List.of("minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "minecraft:snowy_beach", "minecraft:cold_ocean")) {
            for (long t : new long[]{1000, 6000, 12500, 18000, 23000}) {
                Map<String, Integer> got = FishingProbe.roll(FishingProbe.Spot.of(b).at(t).gear("arcana_abyssal_rod", null, 3, 3), 400);
                for (String r : REEF) f.check(!got.containsKey(r), r + " rolled in " + b + " at " + t + ": " + got);
            }
        }
        Map<String, Integer> warm = FishingProbe.roll(FishingProbe.Spot.of("minecraft:warm_ocean"), 2000);
        f.check(warm.keySet().stream().anyMatch(REEF::contains), "no reef fish in a warm ocean: " + warm);
        f.finish(h);
    }

    /** The size grows with the weight (strictly), stays in its limits, and goes into the item for weighed fish. */
    @GameTest
    public void sizeScaleMonotonicAndClamped(GameTestHelper h) {
        CT.Fails f = new CT.Fails("fish-size");
        double prev = 0;
        for (double g = 0.5; g < 30_000_000; g *= 1.07) {
            double s = FishWeight.lengthScale(g);
            f.check(s > prev, "size not increasing at " + g + " g: " + s + " <= " + prev);
            f.check(s >= FishWeight.SIZE_MIN && s <= FishWeight.SIZE_MAX, "size out of range at " + g + " g: " + s);
            prev = s;
        }
        f.check(Math.abs(FishWeight.lengthScale(FishWeight.SIZE_REF_GRAMS) - 1) < 1e-9, "the reference weight isn't size 1");
        f.check(FishWeight.lengthScale(1) < 0.5 && FishWeight.lengthScale(12_000_000) > 2.0, "tiny and huge fish not far apart");
        ItemStack small = FishingProbe.fish("rainbow_trout", 300), big = FishingProbe.fish("rainbow_trout", 9000);
        f.check(small != null && big != null, "no trout items");
        if (small != null && big != null) {
            CustomModelData a = small.get(DataComponents.CUSTOM_MODEL_DATA), b = big.get(DataComponents.CUSTOM_MODEL_DATA);
            f.check(a != null && b != null && a.getFloat(0) != null && b.getFloat(0) != null && a.getFloat(0) < b.getFloat(0),
                    "a heavier trout isn't drawn bigger: " + a + " vs " + b);
            double ps = FishingProbe.plaqueSize(small), pb = FishingProbe.plaqueSize(big);
            f.check(ps < pb && ps >= 0.55 && pb <= 1.3, "plaque sizes " + ps + " / " + pb);
        }
        ItemStack shark = FishingProbe.fish("whale_shark", 20_000_000);
        f.check(shark != null && FishingProbe.plaqueSize(shark) <= 1.3, "a whale shark breaks the plaque");
        ItemStack common = FishingProbe.fish("anchovy", 20);
        f.check(common != null && !common.has(DataComponents.CUSTOM_MODEL_DATA), "a common (unweighed, stacking) fish got size data");
        f.finish(h);
    }

    /** Fish caught before (grams in custom data, no size, kg-era lore) and the old data file still read the same. */
    @GameTest
    public void storedCatchesStillLoad(GameTestHelper h) {
        CT.Fails f = new CT.Fails("fish-legacy");
        ItemStack old = ArcanaContent.stack("arcana_fish_moonkoi", 1);
        f.check(old != null, "no moonkoi item");
        if (old != null) {
            Pdc.itemPutString(old, "feather:fish_species", "moonkoi");
            Pdc.itemPutString(old, "feather:fish_rarity", "rare");
            Pdc.itemPut(old, "feather:fish_weight", IntTag.valueOf(3200));
            Object[] r = FishingProbe.read(old);
            f.check(r != null && "moonkoi".equals(r[0]) && "rare".equals(r[1]) && Integer.valueOf(3200).equals(r[2]),
                    "old fish read as " + Arrays.toString(r));
            f.check(!old.has(DataComponents.CUSTOM_MODEL_DATA), "an old fish already had size data");
            f.check(FishingProbe.refresh(old) && old.has(DataComponents.CUSTOM_MODEL_DATA), "refresh didn't give the old fish its size");
            f.check(!FishingProbe.refresh(old), "refresh changed it twice");
        }
        try {
            Path dir = Files.createTempDirectory("fishdata");
            Path file = dir.resolve("fishing.yml");
            Files.writeString(file, """
                    journal:
                      7f1c3a52-0c1a-4f5e-9d3c-111111111111:
                        name: Old Angler
                        paid: [common]
                        species:
                          moonkoi: {count: 3, best: 4100, first: 1700000000000}
                          minnow: {count: 9, best: 0, first: 1700000000000}
                    records:
                      moonkoi: {grams: 5830, holder: 7f1c3a52-0c1a-4f5e-9d3c-111111111111, name: Old Angler, at: 1700000000000}
                      abyssal_leviathan_eel: {grams: 150000, name: Marlow, at: 1700000000000}
                    tournament:
                      until: 0
                      started: 0
                      entrants:
                        7f1c3a52-0c1a-4f5e-9d3c-111111111111: {name: Old Angler, species: glass_eel, grams: 2210}
                    """);
            Map<String, Integer> d = FishingProbe.loadData(file);
            f.check(Integer.valueOf(5830).equals(d.get("record.moonkoi")), "record lost: " + d);
            f.check(Integer.valueOf(150000).equals(d.get("record.abyssal_leviathan_eel")), "big record lost: " + d);
            f.check(Integer.valueOf(4100).equals(d.get("best.7f1c3a52-0c1a-4f5e-9d3c-111111111111.moonkoi")), "journal best lost: " + d);
            f.check(Integer.valueOf(2210).equals(d.get("tournament.7f1c3a52-0c1a-4f5e-9d3c-111111111111")), "tournament entry lost: " + d);
            f.check(FishWeight.format(5830).equals("12.9 lb"), "record shows " + FishWeight.format(5830));
        } catch (Exception e) {
            f.add("data file: " + e);
        }
        f.finish(h);
    }

    /** Luck of the Sea multiplies rare and legendary odds per level; a geared angler sees legendaries, not too many. */
    @GameTest(maxTicks = 100)
    public void enchantOddsMath(GameTestHelper h) {
        CT.Fails f = new CT.Fails("fish-odds");
        FishingProbe.Spot base = FishingProbe.Spot.of("minecraft:deep_ocean").at(18000);
        for (int lvl = 0; lvl <= 3; lvl++) {
            FishingProbe.Spot s = base.gear(null, null, lvl, 0);
            double rare = FishingProbe.rarityMultiplier("rare", s), leg = FishingProbe.rarityMultiplier("legendary", s);
            f.check(Math.abs(rare - (1 + 0.30 * lvl)) < 1e-9, "LotS " + lvl + " rare x" + rare);
            f.check(Math.abs(leg - (1 + 0.35 * lvl)) < 1e-9, "LotS " + lvl + " legendary x" + leg);
        }
        double rod = FishingProbe.rarityMultiplier("legendary", base.gear("arcana_abyssal_rod", "arcana_glow_bait", 3, 0));
        f.check(Math.abs(rod - 3.0 * 1.3 * 2.05) < 1e-9, "abyssal rod + glow bait + LotS III legendary x" + rod);
        Map<String, Double> plain = FishingProbe.rarityOdds(base);
        Map<String, Double> geared = FishingProbe.rarityOdds(base.gear("arcana_abyssal_rod", "arcana_glow_bait", 3, 3));
        double lp = plain.getOrDefault("legendary", 0.0), lg = geared.getOrDefault("legendary", 0.0);
        double rp = plain.getOrDefault("rare", 0.0), rg = geared.getOrDefault("rare", 0.0);
        f.check(lp > 0.005 && lp < 0.06, "base legendary share in the deep ocean at night: " + plain);
        f.check(lg > 0.05 && lg < 0.25, "geared legendary share: " + geared);
        f.check(rg > rp && rg > 0.2, "geared rare share: " + geared + " vs " + plain);
        // and the roll follows the odds
        Map<String, Integer> rolls = FishingProbe.roll(base.gear("arcana_abyssal_rod", "arcana_glow_bait", 3, 3), 4000);
        long legs = rolls.entrySet().stream().filter(e -> FishingProbe.species().stream()
                .anyMatch(s -> s.id().equals(e.getKey()) && s.rarity().equals("legendary"))).mapToLong(Map.Entry::getValue).sum();
        f.check(Math.abs(legs / 4000.0 - lg) < 0.04, "rolled legendary share " + legs / 4000.0 + " vs odds " + lg);
        f.finish(h);
    }

    /** Luck of the Sea pushes weights up: per roll (same random draws) and in the median, level by level. */
    @GameTest(maxTicks = 100)
    public void weightSkewMonotonic(GameTestHelper h) {
        CT.Fails f = new CT.Fails("fish-skew");
        for (String sp : List.of("tuna", "rainbow_trout", "koi", "bluegill")) {
            int[] prev = null;
            int prevMedian = 0;
            for (int lvl = 0; lvl <= 3; lvl++) {
                int[] g = FishingProbe.rollGrams(sp, lvl, 0, 42L, 1001);
                if (prev != null) for (int i = 0; i < g.length; i++) {
                    if (g[i] < prev[i]) {
                        f.add(sp + ": roll " + i + " lighter at LotS " + lvl + " (" + g[i] + " < " + prev[i] + ")");
                        break;
                    }
                }
                int[] sorted = g.clone();
                Arrays.sort(sorted);
                int median = sorted[sorted.length / 2];
                if (lvl > 0) f.check(median > prevMedian, sp + ": median didn't grow at LotS " + lvl + ": " + median + " <= " + prevMedian);
                prev = g;
                prevMedian = median;
            }
            int[] lure = FishingProbe.rollGrams(sp, 0, 3, 42L, 1001), none = FishingProbe.rollGrams(sp, 0, 0, 42L, 1001), lots = FishingProbe.rollGrams(sp, 3, 0, 42L, 1001);
            Arrays.sort(lure);
            Arrays.sort(none);
            Arrays.sort(lots);
            f.check(lure[500] >= none[500] && lure[500] < lots[500], sp + ": Lure's weight bonus should be smaller than LotS's");
        }
        f.finish(h);
    }

    /** The fight: a real playthrough lands, impossible reports are rejected, and nothing the client says wins on its own. */
    @GameTest
    public void fightServerValidation(GameTestHelper h) {
        CT.Fails f = new CT.Fails("fish-fight");
        FishFight.Params p = new FishFight.Params(123456789L, FishFight.strength(2, 15000, false), 1.0f, 1.0f, 900);
        int[] play = FishFight.autopilot(p, 0.72f, 0.55f);
        int len = FishFight.length(p, play);
        f.check(FishFight.replay(p, len, play) == FishFight.Outcome.LANDED, "a careful player didn't land the fish");
        f.check(FishFight.judge(p, len, play, len * 50L, 1500) == FishFight.Outcome.LANDED, "the server didn't accept a real win");
        f.check(FishFight.judge(p, len, play, 1000, 0) == FishFight.Outcome.INVALID, "a fight played faster than real time was accepted");
        f.check(FishFight.judge(p, len + 40, play, (len + 40) * 50L, 1500) == FishFight.Outcome.INVALID, "a report running past the end was accepted");
        int[] unsorted = play.clone();
        if (unsorted.length > 2) {
            int t = unsorted[0];
            unsorted[0] = unsorted[1];
            unsorted[1] = t;
            f.check(FishFight.judge(p, len, unsorted, len * 50L, 1500) == FishFight.Outcome.INVALID, "out-of-order inputs were accepted");
        }
        f.check(FishFight.judge(p, len, new int[]{-1}, len * 50L, 1500) == FishFight.Outcome.INVALID, "a negative tick was accepted");
        f.check(FishFight.judge(p, 0, new int[0], 0, 1500) == FishFight.Outcome.INVALID, "zero ticks accepted");
        f.check(FishFight.judge(p, 901, new int[0], 901 * 50L, 1500) == FishFight.Outcome.INVALID, "past the time limit accepted");
        // never reeling: the fish takes the line; holding the reel the whole time: the line snaps
        FishFight.Outcome idle = FishFight.judge(p, 600, new int[0], 600 * 50L, 1500);
        f.check(idle != FishFight.Outcome.LANDED, "a fish landed without reeling: " + idle);
        int hold = FishFight.length(p, new int[]{0});
        f.check(FishFight.replay(p, hold, new int[]{0}) == FishFight.Outcome.SNAPPED, "holding the reel forever didn't snap the line");
        // giving up early (Esc): escaped, not landed
        f.check(FishFight.judge(p, len / 2, Arrays.copyOf(play, (int) Arrays.stream(play).filter(t -> t < len / 2).count()), len * 50L, 1500)
                == FishFight.Outcome.ESCAPED, "giving up halfway wasn't an escape");
        // heavier and legendary fish pull harder; Unbreaking-style line strength helps
        f.check(FishFight.strength(3, 100_000, true) > FishFight.strength(2, 5000, false), "a legendary trophy pulls no harder");
        int wins = 0;
        for (long seed = 1; seed <= 40; seed++) {
            FishFight.Params q = new FishFight.Params(seed * 7919L, FishFight.strength(3, 60000, false), 1.15f, 1.18f, 900);
            int[] in = FishFight.autopilot(q, 0.75f, 0.58f);
            if (FishFight.replay(q, FishFight.length(q, in), in) == FishFight.Outcome.LANDED) wins++;
        }
        f.check(wins >= 30, "a careful, well-equipped player lands only " + wins + "/40 legendaries");
        f.finish(h);
    }

    /**
     * A fishing reload reindexes the loaded chunks for scales, plaques, tanks and crab pots only when the
     * furniture globs changed: the same fishing.yml leaves the index alone, new globs reindex.
     */
    @GameTest
    public void furnitureReindexOnlyWhenGlobsChange(GameTestHelper h) throws Exception {
        CT.Fails f = new CT.Fails("fish-furniture-reindex");
        var fishing = net.axther.aftrhrs.core.Feather.get().module(net.axther.aftrhrs.feature.fishing.FishingModule.class);
        if (fishing == null) {
            f.add("the fishing module is off");
            f.finish(h);
            return;
        }
        Object furniture = reflectField(fishing, "furniture");
        java.lang.reflect.Method reload = furniture.getClass().getDeclaredMethod("reload", net.axther.aftrhrs.core.Yaml.class);
        reload.setAccessible(true);
        net.axther.aftrhrs.core.Yaml y = net.axther.aftrhrs.core.Feather.get().loadResourceConfig("fishing.yml");
        reload.invoke(furniture, y);   // the baseline: whatever was loaded last
        int r0 = (int) reflectField(furniture, "reindexes");
        reload.invoke(furniture, net.axther.aftrhrs.core.Feather.get().loadResourceConfig("fishing.yml"));
        f.check((int) reflectField(furniture, "reindexes") == r0, "an unchanged fishing.yml reindexed the furniture");
        net.axther.aftrhrs.core.Yaml changed = net.axther.aftrhrs.core.Yaml.fromString(y.saveToString());
        changed.setDefaults(y.getDefaults());
        List<String> pots = new ArrayList<>(y.getStringList("furniture.crab-pots"));
        if (pots.isEmpty()) pots.add("arcana_crab_pot");
        pots.add("arcana_no_such_crab_pot");
        changed.set("furniture.crab-pots", pots);
        try {
            reload.invoke(furniture, changed);
            f.check((int) reflectField(furniture, "reindexes") == r0 + 1, "new crab-pot globs didn't reindex");
        } finally {
            reload.invoke(furniture, y);
        }
        f.check((int) reflectField(furniture, "reindexes") == r0 + 2, "putting fishing.yml back didn't reindex");
        f.finish(h);
    }

    private static Object reflectField(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field fl = c.getDeclaredField(name);
                fl.setAccessible(true);
                return fl.get(o);
            } catch (NoSuchFieldException ignored) {
                // up the chain
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static JsonObject json(String path) {
        try (InputStream in = FishingProbe.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }
}
