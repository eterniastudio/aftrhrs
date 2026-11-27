package net.axther.aftrhrs.feature.farming;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.Coast;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** farming.yml, read once per reload (same keys as the plugin). */
final class FarmConfig {
    /** {@code min-max} (or one number). */
    record Range(int min, int max) {
        int roll() {
            return max > min ? min + ThreadLocalRandom.current().nextInt(max - min + 1) : min;
        }

        static Range parse(String raw, int min, int max) {
            if (raw == null || raw.isBlank()) return new Range(min, max);
            try {
                String[] p = raw.trim().split("-");
                int a = Integer.parseInt(p[0].trim());
                int b = p.length > 1 ? Integer.parseInt(p[1].trim()) : a;
                return new Range(Math.max(0, Math.min(a, b)), Math.max(0, Math.max(a, b)));
            } catch (NumberFormatException e) {
                return new Range(min, max);
            }
        }
    }

    /** A list of biome entries: plain path ('swamp', any namespace), full key ('terralith:x'), '*' globs and '#tags'. */
    static final class Biomes {
        private final List<Pattern> paths = new ArrayList<>();
        private final List<Pattern> keys = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private final Map<String, Boolean> cache = new HashMap<>();

        Biomes(List<String> raw) {
            for (String s : raw) {
                if (s == null || s.isBlank()) continue;
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (t.startsWith("#")) tags.add(t.substring(1).contains(":") ? t.substring(1) : "minecraft:" + t.substring(1));
                else if (t.contains(":")) keys.add(Coast.glob(t));
                else paths.add(Coast.glob(t));
            }
        }

        boolean isEmpty() {
            return paths.isEmpty() && keys.isEmpty() && tags.isEmpty();
        }

        boolean matches(String key) {
            if (key == null) return false;
            return cache.computeIfAbsent(key, this::compute);
        }

        private boolean compute(String key) {
            String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            for (Pattern p : keys) if (p.matcher(key).matches()) return true;
            for (Pattern p : paths) if (p.matcher(path).matches()) return true;
            for (String t : tags) if (Coast.biomeTag(t).contains(key)) return true;
            return false;
        }
    }

    static final class CropDef {
        final String id;
        final long stageMs;
        final Biomes affinity, seedBiomes;
        final boolean trellis, needsWater;
        final Range produce, seeds;
        final int weight;

        CropDef(String id, Yaml c) {
            this.id = id;
            stageMs = (long) (Math.max(0.05, c.getDouble("stage-minutes", 10)) * 60_000L);
            affinity = new Biomes(c.getStringList("affinity"));
            seedBiomes = c.isList("seed-biomes") ? new Biomes(c.getStringList("seed-biomes")) : affinity;
            trellis = c.getBoolean("trellis", false);
            needsWater = c.getBoolean("needs-water", false);
            produce = Range.parse(c.getString("produce"), 1, 2);
            seeds = Range.parse(c.getString("seeds"), 1, 2);
            weight = Math.max(1, c.getInt("weight-grams", 100));
        }

        String seedsId() {
            return "arcana_seeds_" + id;
        }

        String produceId() {
            return "arcana_crop_" + id;
        }

        /** The old Oraxen stage block id; {@code ArcanaContent.stateFor} maps it to the crop block at that age. */
        String stageId(int stage) {
            return "arcana_crop_" + id + "_stage" + stage;
        }

        /** The native crop block ({@code aftrhrs:arcana_<crop>_crop}), or null. */
        Block block() {
            return ArcanaContent.block("arcana_" + id + "_crop");
        }
    }

    /** An ingredient or accepted-item rule: material, item tag, Oraxen id glob or {@code #fish}. */
    static final class ItemRule {
        final String raw;
        final int amount;
        private final Item material;
        private final TagKey<Item> tag;
        private final Pattern oraxen;
        private final boolean fish;

        private ItemRule(String raw, int amount, Item material, TagKey<Item> tag, Pattern oraxen, boolean fish) {
            this.raw = raw;
            this.amount = amount;
            this.material = material;
            this.tag = tag;
            this.oraxen = oraxen;
            this.fish = fish;
        }

        static ItemRule parse(String spec, Consumer<String> warn, String where) {
            if (spec == null || spec.isBlank()) return null;
            String[] p = spec.trim().split("\\s+");
            String t = p[0].toLowerCase(Locale.ROOT);
            int amount = 1;
            if (p.length > 1) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(p[1])));
                } catch (NumberFormatException e) {
                    warn.accept(where + ": bad amount in '" + spec + "'");
                }
            }
            if (t.equals("#fish")) return new ItemRule(t, amount, null, null, null, true);
            if (t.startsWith("oraxen:")) return new ItemRule(t, amount, null, null, Coast.glob(t.substring(7)), false);
            if (t.startsWith("#")) {
                Identifier k = Identifier.tryParse(t.substring(1));
                if (k == null) {
                    warn.accept(where + ": unknown item tag " + t);
                    return null;
                }
                return new ItemRule(t, amount, null, TagKey.create(Registries.ITEM, k), null, false);
            }
            Item m = Coast.vanillaItem(t);
            if (m == null || m == Items.AIR) {
                warn.accept(where + ": unknown item '" + t + "'");
                return null;
            }
            return new ItemRule(t, amount, m, null, null, false);
        }

        /** {@code contentId}: the stack's content id or null. Content items only match oraxen: rules (and #fish). */
        boolean matches(ItemStack s, String contentId) {
            if (s == null || s.isEmpty()) return false;
            if (fish) {
                if (contentId != null) return contentId.startsWith("arcana_fish_");
                return s.is(Items.COD) || s.is(Items.SALMON);
            }
            if (oraxen != null) return contentId != null && oraxen.matcher(contentId).matches();
            if (contentId != null) return false;
            if (material != null) return s.is(material);
            return tag != null && s.is(tag);
        }

        /** Example items that satisfy this rule (recipe viewers). */
        List<ItemStack> examples() {
            List<ItemStack> out = new ArrayList<>();
            if (fish) {
                for (String id : ArcanaContent.entries().keySet()) {
                    if (id.startsWith("arcana_fish_") && out.size() < 32) add(out, ArcanaContent.stack(id, amount));
                }
                out.add(new ItemStack(Items.COD, amount));
                out.add(new ItemStack(Items.SALMON, amount));
            } else if (oraxen != null) {
                for (String id : ArcanaContent.entries().keySet()) {
                    if (oraxen.matcher(id).matches() && out.size() < 32) add(out, ArcanaContent.stack(id, amount));
                }
            } else if (material != null) {
                out.add(new ItemStack(material, amount));
            } else if (tag != null) {
                for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) if (out.size() < 32) out.add(new ItemStack(h, amount));
            }
            return out;
        }

        private static void add(List<ItemStack> out, ItemStack s) {
            if (s != null && !s.isEmpty()) out.add(s);
        }
    }

    record Effect(Holder<MobEffect> type, String name, int seconds, int level) {
    }

    static final class Meal {
        final String id, name, item;
        final List<String> stations;
        final List<ItemRule> ingredients;
        final List<Effect> effects;

        Meal(String id, String name, String item, List<String> stations, List<ItemRule> ingredients, List<Effect> effects) {
            this.id = id;
            this.name = name;
            this.item = item == null || item.isBlank() ? "arcana_meal_" + id : item.trim().toLowerCase(Locale.ROOT);
            this.stations = stations;
            this.ingredients = ingredients;
            this.effects = effects;
        }

        String itemId() {
            return item;
        }
    }

    // ---------------------------------------------------------------- values

    long checkTicks = 100;
    long retryMs = 120_000;
    int minLight = 9;
    long loadDelayTicks = 20;
    long maxCatchupMs = 72 * 3_600_000L;
    double affinityMultiplier = 1.5;
    double trellisRadius = 1.5;
    final List<Pattern> trellisIds = new ArrayList<>();
    final Map<String, CropDef> crops = new LinkedHashMap<>();

    double fineChance, prizeChance, waterFine, waterPrize, compostFine, compostPrize;
    String sporelingType = "ArcanaSporeling";
    double sporelingRadius = 8, sporelingPerBond = 0.4;
    double prizeMin = 1.2, prizeMax = 2.2;
    String loreFine, lorePrize, loreWeight;

    String canId = "arcana_watering_can_tool";
    int canUses = 16;
    String canLore;
    String compostId = "arcana_compost";
    int compostHarvests = 3;

    final List<Pattern> binIds = new ArrayList<>();
    int binItems = 8;
    long binMs = 180_000;
    int binMaxBatches = 16;
    final List<ItemRule> binAccepts = new ArrayList<>();

    String lineId = "arcana_seaweed_line", sporesId = "arcana_seaweed_spores", seaweedId = "arcana_crop_seaweed",
            overlayPrefix = "arcana_seaweed_line_fx_";
    long seaweedStageMs = 480_000;
    Range seaweedProduce = new Range(2, 4);
    double sporesChance = 0.25;

    final Map<String, List<Pattern>> stations = new LinkedHashMap<>();
    final Map<String, Double> qualityMultiplier = new HashMap<>();
    int fallbackFood = 6;
    float fallbackSaturation = 7f;
    final Map<String, Meal> meals = new LinkedHashMap<>();
    final Map<String, Meal> mealsByItem = new HashMap<>();

    double seedChance = 0.02;
    final List<Block> seedBlocks = new ArrayList<>();

    String canopipType = "ArcanaCanopip";
    double canopipRadius = 6;
    int farmhandContainerRadius = 3;
    double farmhandSeedChance = 0.03;

    boolean smokeEnabled = true;
    String jointId = "arcana_joint", budId = "arcana_crop_weed", paperId = "arcana_rolling_paper";
    final int[] smokeRegen = {10, 15, 20};
    int smokeRegenLevel = 1;
    final List<Effect> smokeEffects = new ArrayList<>();
    long smokeCooldownMs = 30_000;
    int smokeParticles = 12;
    String smokeSound = "block.fire.extinguish";
    float smokeVolume = 0.35f, smokePitch = 1.6f;

    boolean fairEnabled = true;
    DayOfWeek fairDay = DayOfWeek.SUNDAY;
    LocalTime fairTime = LocalTime.of(18, 0);
    ZoneId fairZone = ZoneId.systemDefault();
    final List<Pattern> counterIds = new ArrayList<>();
    double counterRadius = 6;
    final List<Long> fairPrizes = new ArrayList<>();
    boolean fairBroadcast = true;

    void load(Yaml y, Consumer<String> warn) {
        checkTicks = Math.max(20, (long) (y.getDouble("growth.check-seconds", 5) * 20));
        retryMs = (long) (Math.max(0.05, y.getDouble("growth.retry-minutes", 2)) * 60_000L);
        minLight = Math.max(0, Math.min(15, y.getInt("growth.min-light", 9)));
        loadDelayTicks = Math.max(1, y.getLong("growth.load-delay-ticks", 20));
        maxCatchupMs = (long) (Math.max(0, y.getDouble("growth.max-catchup-hours", 72)) * 3_600_000L);
        affinityMultiplier = Math.max(1, y.getDouble("growth.affinity-multiplier", 1.5));
        trellisRadius = Math.max(0.5, Math.min(4, y.getDouble("growth.trellis-radius", 1.5)));
        trellisIds.clear();
        for (String s : y.getStringList("growth.trellis-ids")) trellisIds.add(Coast.glob(s));

        crops.clear();
        Yaml cs = y.getConfigurationSection("crops");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                Yaml c = cs.getConfigurationSection(id);
                if (c != null) crops.put(id.toLowerCase(Locale.ROOT), new CropDef(id.toLowerCase(Locale.ROOT), c));
            }
        }

        fineChance = clamp01(y.getDouble("quality.fine-chance", 0.10));
        prizeChance = clamp01(y.getDouble("quality.prize-chance", 0.01));
        waterFine = clamp01(y.getDouble("quality.per-watered-stage.fine", 0.07));
        waterPrize = clamp01(y.getDouble("quality.per-watered-stage.prize", 0.02));
        compostFine = clamp01(y.getDouble("quality.compost.fine", 0.15));
        compostPrize = clamp01(y.getDouble("quality.compost.prize", 0.04));
        sporelingType = y.getString("quality.sporeling-type", "ArcanaSporeling");
        sporelingRadius = Math.max(1, y.getDouble("quality.sporeling-radius", 8));
        sporelingPerBond = Math.max(0, y.getDouble("quality.sporeling-radius-per-bond", 0.4));
        String[] f = y.getString("quality.prize-weight-factor", "1.2-2.2").split("-");
        try {
            prizeMin = Double.parseDouble(f[0].trim());
            prizeMax = f.length > 1 ? Double.parseDouble(f[1].trim()) : prizeMin;
        } catch (NumberFormatException e) {
            prizeMin = 1.2;
            prizeMax = 2.2;
        }
        loreFine = y.getString("quality.lore.fine", "<#7fd6a3>✦ Fine");
        lorePrize = y.getString("quality.lore.prize", "<#ffd36b>✦✦ Prize");
        loreWeight = y.getString("quality.lore.weight", "<muted>Weight <text><kg> kg");

        canId = y.getString("watering-can.id", "arcana_watering_can_tool");
        canUses = Math.max(1, y.getInt("watering-can.uses", 16));
        canLore = y.getString("watering-can.lore", "<muted>Water <text><uses></text>/<max>");
        compostId = y.getString("compost.id", "arcana_compost");
        compostHarvests = Math.max(1, y.getInt("compost.harvests", 3));

        binIds.clear();
        for (String s : y.getStringList("compost-bins.ids")) binIds.add(Coast.glob(s));
        binItems = Math.max(1, y.getInt("compost-bins.items-per-compost", 8));
        binMs = (long) (Math.max(0, y.getDouble("compost-bins.minutes", 3)) * 60_000L);
        binMaxBatches = Math.max(1, y.getInt("compost-bins.max-batches", 16));
        binAccepts.clear();
        for (String s : y.getStringList("compost-bins.accepts")) {
            ItemRule r = ItemRule.parse(s, warn, "compost-bins.accepts");
            if (r != null) binAccepts.add(r);
        }

        lineId = y.getString("seaweed.line-id", lineId);
        sporesId = y.getString("seaweed.spores-id", sporesId);
        seaweedId = y.getString("seaweed.produce-id", seaweedId);
        overlayPrefix = y.getString("seaweed.overlay-prefix", overlayPrefix);
        seaweedStageMs = (long) (Math.max(0.05, y.getDouble("seaweed.stage-minutes", 8)) * 60_000L);
        seaweedProduce = Range.parse(y.getString("seaweed.produce"), 2, 4);
        sporesChance = clamp01(y.getDouble("seaweed.spores-chance", 0.25));

        stations.clear();
        Yaml st = y.getConfigurationSection("cooking.stations");
        if (st != null) {
            for (String k : st.getKeys(false)) {
                List<Pattern> ids = new ArrayList<>();
                for (String s : st.getStringList(k)) ids.add(Coast.glob(s));
                stations.put(k.toLowerCase(Locale.ROOT), ids);
            }
        }
        qualityMultiplier.clear();
        for (String q : List.of("normal", "fine", "prize")) {
            qualityMultiplier.put(q, Math.max(0.1, y.getDouble("cooking.quality-multiplier." + q, q.equals("normal") ? 1 : q.equals("fine") ? 1.5 : 2)));
        }
        fallbackFood = Math.max(0, y.getInt("cooking.fallback-food", 6));
        fallbackSaturation = (float) Math.max(0, y.getDouble("cooking.fallback-saturation", 7));
        meals.clear();
        mealsByItem.clear();
        Yaml ms = y.getConfigurationSection("cooking.meals");
        if (ms != null) {
            for (String id : ms.getKeys(false)) {
                Yaml c = ms.getConfigurationSection(id);
                if (c == null) continue;
                List<ItemRule> ing = new ArrayList<>();
                for (String s : c.getStringList("ingredients")) {
                    ItemRule r = ItemRule.parse(s, warn, "cooking.meals." + id);
                    if (r != null) ing.add(r);
                }
                List<Effect> eff = new ArrayList<>();
                for (String s : c.getStringList("effects")) {
                    Effect e = parseEffect(s, warn, "cooking.meals." + id);
                    if (e != null) eff.add(e);
                }
                List<String> stationList = new ArrayList<>();
                for (String s : c.getStringList("stations")) stationList.add(s.toLowerCase(Locale.ROOT));
                if (ing.isEmpty()) {
                    warn.accept("cooking.meals." + id + ": no ingredients; skipped");
                    continue;
                }
                Meal meal = new Meal(id.toLowerCase(Locale.ROOT), c.getString("name", id), c.getString("item"), stationList, ing, eff);
                meals.put(meal.id, meal);
                mealsByItem.put(meal.itemId(), meal);
            }
        }

        seedChance = clamp01(y.getDouble("seed-drops.chance", 0.02));
        seedBlocks.clear();
        for (String s : y.getStringList("seed-drops.blocks")) {
            Identifier id = Identifier.tryParse(s.trim().toLowerCase(Locale.ROOT));
            if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(seedBlocks::add);
        }

        canopipType = y.getString("canopip.type", "ArcanaCanopip");
        canopipRadius = Math.max(1, y.getDouble("canopip.radius", 6));
        farmhandContainerRadius = Math.max(1, Math.min(8, y.getInt("farmhand.container-radius", 3)));
        farmhandSeedChance = clamp01(y.getDouble("farmhand.seed-find-chance", 0.03));

        smokeEnabled = y.getBoolean("smoking.enabled", true);
        jointId = y.getString("smoking.joint", "arcana_joint").trim().toLowerCase(Locale.ROOT);
        budId = y.getString("smoking.bud", "arcana_crop_weed").trim().toLowerCase(Locale.ROOT);
        paperId = y.getString("smoking.paper", "arcana_rolling_paper").trim().toLowerCase(Locale.ROOT);
        for (int q = 0; q < 3; q++) {
            smokeRegen[q] = Math.max(0, y.getInt("smoking.regeneration." + FarmingModule.QUALITIES[q], 10 + 5 * q));
        }
        smokeRegenLevel = Math.max(1, Math.min(10, y.getInt("smoking.regeneration.level", 1)));
        smokeEffects.clear();
        for (String s : y.getStringList("smoking.effects")) {
            Effect e = parseEffect(s, warn, "smoking.effects");
            if (e != null) smokeEffects.add(e);
        }
        smokeCooldownMs = (long) (Math.max(0, y.getDouble("smoking.cooldown-seconds", 30)) * 1000L);
        smokeParticles = Math.max(0, Math.min(64, y.getInt("smoking.smoke-particles", 12)));
        smokeSound = y.getString("smoking.sound", "block.fire.extinguish");
        smokeVolume = (float) Math.max(0, y.getDouble("smoking.sound-volume", 0.35));
        smokePitch = (float) Math.max(0.5, Math.min(2, y.getDouble("smoking.sound-pitch", 1.6)));

        fairEnabled = y.getBoolean("fair.enabled", true);
        try {
            fairDay = DayOfWeek.valueOf(y.getString("fair.day", "SUNDAY").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn.accept("fair.day: unknown day; using SUNDAY");
            fairDay = DayOfWeek.SUNDAY;
        }
        try {
            fairTime = LocalTime.parse(y.getString("fair.time", "18:00").trim());
        } catch (RuntimeException e) {
            warn.accept("fair.time: use HH:MM; using 18:00");
            fairTime = LocalTime.of(18, 0);
        }
        String zone = y.getString("fair.zone", "");
        try {
            fairZone = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone.trim());
        } catch (RuntimeException e) {
            warn.accept("fair.zone: unknown zone '" + zone + "'; using the server's");
            fairZone = ZoneId.systemDefault();
        }
        counterIds.clear();
        for (String s : y.getStringList("fair.counter-ids")) counterIds.add(Coast.glob(s));
        counterRadius = Math.max(1, y.getDouble("fair.counter-radius", 6));
        fairPrizes.clear();
        for (Object o : y.getList("fair.prizes", List.of(40, 20, 10))) {
            try {
                fairPrizes.add(Math.max(0, Long.parseLong(String.valueOf(o).trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        fairBroadcast = y.getBoolean("fair.broadcast", true);
    }

    /** {@code '<effect> <seconds> <level>'} (30 seconds and level 1 when left out), or null (warned). */
    static Effect parseEffect(String s, Consumer<String> warn, String where) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.trim().split("\\s+");
        Identifier k = Identifier.tryParse(p[0].toLowerCase(Locale.ROOT));
        Holder<MobEffect> type = k == null ? null : BuiltInRegistries.MOB_EFFECT.get(k).map(h -> (Holder<MobEffect>) h).orElse(null);
        if (type == null) {
            warn.accept(where + ": unknown effect '" + p[0] + "'");
            return null;
        }
        int sec = 30, lvl = 1;
        try {
            if (p.length > 1) sec = Integer.parseInt(p[1]);
            if (p.length > 2) lvl = Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            warn.accept(where + ": bad effect '" + s + "'");
        }
        return new Effect(type, k.getPath(), Math.max(1, sec), Math.max(1, Math.min(10, lvl)));
    }

    /** The station kind of a furniture id (stove, cutting_board, stew_pot), or null. */
    String stationOf(String furnitureId) {
        if (furnitureId == null) return null;
        for (Map.Entry<String, List<Pattern>> e : stations.entrySet()) if (Coast.matches(e.getValue(), furnitureId)) return e.getKey();
        return null;
    }

    /** Crop from a seeds id, or null. */
    CropDef cropOfSeeds(String id) {
        if (id == null || !id.startsWith("arcana_seeds_")) return null;
        return crops.get(id.substring("arcana_seeds_".length()));
    }

    /** The crop def of a native crop block ({@code arcana_rice_crop}), or null. */
    CropDef cropOfBlock(String blockId) {
        if (blockId == null || !blockId.startsWith("arcana_") || !blockId.endsWith("_crop")) return null;
        return crops.get(blockId.substring(7, blockId.length() - 5));
    }

    private static double clamp01(double d) {
        return Math.max(0, Math.min(1, d));
    }
}
