package net.axther.aftrhrs.feature.fishing;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A read-mostly window on the running fishing module for the game tests and staff debugging (the module's classes are
 * package-private): the species table, biome groups, rolls and odds in a made-up spot, seeded weight rolls, fish
 * items, and loading a data file.
 */
public final class FishingProbe {
    private FishingProbe() {
    }

    public record SpeciesInfo(String id, String name, String rarity, String water, List<String> biomes, List<String> climate,
                              String time, String weather, Boolean underground, int minGrams, int typicalGrams, int maxGrams,
                              String bait) {
    }

    /** A spot to roll in. */
    public record Spot(String biome, long time, boolean rain, boolean thunder, boolean openWater, boolean underground,
                       String rod, String bait, int luck, int lure) {
        public static Spot of(String biome) {
            return new Spot(biome, 6000, false, false, true, false, null, null, 0, 0);
        }

        public Spot at(long t) {
            return new Spot(biome, t, rain, thunder, openWater, underground, rod, bait, luck, lure);
        }

        public Spot weather(boolean r, boolean th) {
            return new Spot(biome, time, r, th, openWater, underground, rod, bait, luck, lure);
        }

        public Spot gear(String r, String b, int lk, int lr) {
            return new Spot(biome, time, rain, thunder, openWater, underground, r, b, lk, lr);
        }
    }

    @Nullable
    private static FishingModule m() {
        return FishingModule.active;
    }

    public static boolean running() {
        return m() != null;
    }

    public static List<SpeciesInfo> species() {
        FishingModule m = m();
        if (m == null) return List.of();
        return m.table.species.values().stream().map(s -> new SpeciesInfo(s.id(), s.name(), s.rarity(), s.water().name().toLowerCase(java.util.Locale.ROOT),
                s.biomes(), s.climate(), s.time().name().toLowerCase(java.util.Locale.ROOT), s.weather().name().toLowerCase(java.util.Locale.ROOT), s.underground(),
                s.minGrams(), s.typicalGrams(), s.maxGrams(), s.bait())).toList();
    }

    public static Set<String> groupNames() {
        FishingModule m = m();
        return m == null ? Set.of() : Set.copyOf(m.table.groupNames());
    }

    public static Set<String> groupsOf(String biomeKey) {
        FishingModule m = m();
        return m == null ? Set.of() : m.table.groupsOf(biomeKey);
    }

    /** Could the species live in this biome (water, biome groups, climate; above ground)? */
    public static boolean livesIn(String speciesId, String biomeKey) {
        FishingModule m = m();
        if (m == null) return false;
        FishTable.Species s = m.table.species.get(speciesId);
        if (s == null) return false;
        Set<String> g = m.table.groupsOf(biomeKey);
        return m.table.habitat(s, g, m.table.salt(g), false);
    }

    private static FishTable.Context context(FishingModule m, Spot s) {
        Set<String> g = m.table.groupsOf(s.biome());
        return new FishTable.Context(s.biome(), g, m.table.salt(g), s.underground(), s.time(), s.rain(), s.thunder(), false,
                s.openWater(), s.rod(), s.bait(), false, false, 1, s.luck(), s.lure());
    }

    /** n rolls at the spot: species id (or "vanilla") -> count. */
    public static Map<String, Integer> roll(Spot spot, int n) {
        FishingModule m = m();
        Map<String, Integer> out = new LinkedHashMap<>();
        if (m == null) return out;
        FishTable.Context c = context(m, spot);
        for (int i = 0; i < n; i++) {
            FishTable.Species s = m.table.roll(c);
            out.merge(s == null ? "vanilla" : s.id(), 1, Integer::sum);
        }
        return out;
    }

    /** The chance of each rarity at the spot (what the first roll step uses). */
    public static Map<String, Double> rarityOdds(Spot spot) {
        FishingModule m = m();
        return m == null ? Map.of() : m.table.rarityOdds(context(m, spot));
    }

    /** The rarity weight multiplier the spot's gear and enchantments give. */
    public static double rarityMultiplier(String rarity, Spot spot) {
        FishingModule m = m();
        return m == null ? 1 : m.table.rarityMultiplier(rarity, context(m, spot));
    }

    /** n weight rolls with these enchantment levels, from a fixed seed. */
    public static int[] rollGrams(String speciesId, int luck, int lure, long seed, int n) {
        FishingModule m = m();
        int[] out = new int[n];
        if (m == null) return out;
        FishTable.Species s = m.table.species.get(speciesId);
        Random rnd = new Random(seed);
        for (int i = 0; i < n; i++) out[i] = FishTable.rollGrams(s, m.table.weightSkew(luck, lure), m.table.trophyChance(luck, lure), rnd);
        return out;
    }

    /** A caught fish of this species and weight (the catcher is left out). */
    @Nullable
    public static ItemStack fish(String speciesId, int grams) {
        FishingModule m = m();
        if (m == null) return null;
        FishTable.Species s = m.table.species.get(speciesId);
        return s == null ? null : m.items.fish(s, grams, null, null);
    }

    /** {species, rarity, grams} of a fish item, or null. */
    @Nullable
    public static Object[] read(ItemStack stack) {
        FishingModule m = m();
        FishItems.Fish f = m == null ? null : m.items.read(stack);
        return f == null ? null : new Object[]{f.species(), f.rarity(), f.grams()};
    }

    /** Gives an older fish its size data, as the fish scale does. */
    public static boolean refresh(ItemStack stack) {
        FishingModule m = m();
        return m != null && m.items.refresh(stack);
    }

    /** The size a plaque draws this fish at (relative to plaque.fish-scale). */
    public static double plaqueSize(ItemStack stack) {
        FishingModule m = m();
        return m == null ? 1 : m.furniture.displaySize(stack, 0.55, 1.3);
    }

    /** The journal hint of a species. */
    public static String hint(String speciesId) {
        FishingModule m = m();
        FishTable.Species s = m == null ? null : m.table.species.get(speciesId);
        return s == null ? "" : m.screens.hint(s);
    }

    /** Loads a data/fishing.yml: record grams by species, each journal's best grams, tournament entrant grams. */
    public static Map<String, Integer> loadData(Path file) {
        FishData d = new FishData(file, LoggerFactory.getLogger("fishing-probe"));
        d.load();
        Map<String, Integer> out = new LinkedHashMap<>();
        d.records.forEach((k, v) -> out.put("record." + k, v.grams()));
        d.journals.forEach((id, j) -> j.species.forEach((k, e) -> out.put("best." + id + "." + k, e.best)));
        d.tournament.forEach((id, e) -> out.put("tournament." + id, e.grams()));
        return out;
    }
}
