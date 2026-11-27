package net.axther.aftrhrs.feature.fishing;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Legacy;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.Coast;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.IntTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fish and the other fishing items. Custom fish carry {@code feather:fish_species}, {@code feather:fish_rarity},
 * {@code feather:fish_weight} (grams) and {@code feather:fish_catcher} in their custom_data (Paper's item PDC keys, so
 * caught fish from the old server read the same), plus a rarity, weight and catcher lore line. Items are native
 * content now; a plain stand-in (named, {@code feather:fish_item} = the id it stands for) is only made for ids the
 * content table doesn't have.
 */
final class FishItems {
    static final String SPECIES = "feather:fish_species", RARITY = "feather:fish_rarity", WEIGHT = "feather:fish_weight",
            CATCHER = "feather:fish_catcher", ITEM_ID = "feather:fish_item";

    private final FishingModule m;
    private final Set<String> warned = new HashSet<>();
    private final Map<String, String> rarityLore = new HashMap<>();
    private final Map<String, String> rarityColor = new HashMap<>();
    private String weightLore = "<muted><weight>", catcherLore = "<dim>Caught by <name>";
    private final Map<String, String> sizeLore = new HashMap<>();
    private Item fallbackFish = Items.COD;
    private final Map<String, Item> fallbackMaterial = new HashMap<>();
    private final Map<String, String> fallbackName = new HashMap<>();

    FishItems(FishingModule m) {
        this.m = m;
    }

    void reload(Yaml yml) {
        rarityLore.clear();
        rarityColor.clear();
        String[][] def = {{"common", "<#C9C2E8>", "★ Common"}, {"uncommon", "<#86E8B0>", "★ Uncommon"},
                {"rare", "<#8FD3FF>", "★ Rare"}, {"legendary", "<#FFD24A>", "★ Legendary"}};
        for (String[] d : def) {
            rarityColor.put(d[0], yml.getString(Legacy.key(yml, "items.rarity-color." + d[0], "items.rarity-colour." + d[0]), d[1]));
            rarityLore.put(d[0], yml.getString("items.rarity-lore." + d[0], d[1] + d[2]));
        }
        weightLore = yml.getString("items.weight-lore", "<muted><weight>");
        catcherLore = yml.getString("items.catcher-lore", "<dim>Caught by <name>");
        sizeLore.clear();
        String[][] sizes = {{"juvenile", "<dim>Juvenile"}, {"adult", ""}, {"trophy", "<#FFD24A>✦ Trophy size"}};
        for (String[] d : sizes) sizeLore.put(d[0], yml.getString("items.size-lore." + d[0], d[1]));
        Item f = Coast.vanillaItem(yml.getString("items.fallback-fish", "COD"));
        fallbackFish = f == null || f == Items.AIR ? Items.COD : f;
        fallbackMaterial.clear();
        fallbackName.clear();
        Yaml fb = yml.getConfigurationSection("items.fallback");
        if (fb != null) {
            for (String id : fb.getKeys(false)) {
                Yaml c = fb.getConfigurationSection(id);
                if (c == null) continue;
                Item mat = Coast.vanillaItem(c.getString("material", "PAPER"));
                fallbackMaterial.put(id.toLowerCase(Locale.ROOT), mat == null || mat == Items.AIR ? Items.PAPER : mat);
                fallbackName.put(id.toLowerCase(Locale.ROOT), c.getString("name", FishTable.pretty(id.replaceFirst("^arcana_", ""))));
            }
        }
    }

    String color(String rarity) {
        return rarityColor.getOrDefault(rarity, "<text>");
    }

    private Text text() {
        return Feather.get().text();
    }

    /** The content item, or null when there is none. */
    @Nullable
    ItemStack content(String id) {
        if (id == null) return null;
        ItemStack s = Coast.item(id, 1);
        if (s == null && warned.add(id)) m.plugin().getLogger().info("Fishing: item '{}' doesn't exist; using a plain stand-in.", id);
        return s;
    }

    /** The item's id: its content id, the id a stand-in stands for, or null. A custom fish is its species' id. */
    @Nullable
    String idOf(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        String sp = Pdc.itemString(s, SPECIES);
        if (sp != null) return "arcana_fish_" + sp;
        String fb = Pdc.itemString(s, ITEM_ID);
        if (fb != null) return fb;
        return Coast.id(s);
    }

    // ------------------------------------------------------------------ fish

    /** A custom fish as caught. Every fish carries its species and rarity; weight and catcher only from uncommon up. */
    ItemStack fish(FishTable.Species s, int grams, UUID who, String whoName) {
        if (s.rank() < 1) {
            grams = 0;
            who = null;
            whoName = null;
        }
        ItemStack item = content(s.oraxenId());
        boolean standIn = item == null;
        if (standIn) {
            item = new ItemStack(fallbackFish);
            item.set(DataComponents.CUSTOM_NAME, Adv.vanilla(text().item(color(s.rarity()) + escape(s.name()))));
        }
        Coast.addLore(item, text().item(rarityLore.getOrDefault(s.rarity(), s.rarity())));
        if (grams > 0) {
            String size = sizeLore.getOrDefault(FishWeight.sizeClass(grams, s.minGrams(), s.typicalGrams(), s.maxGrams()).key(), "");
            Coast.addLore(item, text().item(weightLore, Text.unparsed("weight", FishWeight.format(grams))));
            if (!size.isEmpty()) Coast.addLore(item, text().item(size));
            setScale(item, grams);
        }
        if (whoName != null && !catcherLore.isEmpty()) Coast.addLore(item, text().item(catcherLore, Text.unparsed("name", whoName)));
        Pdc.itemPutString(item, SPECIES, s.id());
        Pdc.itemPutString(item, RARITY, s.rarity());
        if (grams > 0) Pdc.itemPut(item, WEIGHT, IntTag.valueOf(grams));
        if (who != null) Pdc.itemPutString(item, CATCHER, who.toString());
        return item;
    }

    /**
     * How big the held or dropped fish is drawn: its length scale as custom model data float 0 (the item model's range
     * dispatch picks the size step; without it, the species' typical size). Plaques and tanks scale their displays
     * themselves.
     */
    static void setScale(ItemStack item, int grams) {
        if (grams <= 0) return;
        item.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(FishWeight.itemScale(grams)), List.of(), List.of(), List.of()));
    }

    /**
     * A fish caught before weights drove the size (or before pounds): gives it its scale. The stored weight and lore
     * stay as they are. True when it changed.
     */
    boolean refresh(ItemStack item) {
        Fish f = read(item);
        if (f == null || f.grams() <= 0 || item.has(DataComponents.CUSTOM_MODEL_DATA)) return false;
        setScale(item, f.grams());
        return true;
    }

    /** The plain look of a species (journal icons): its item or a stand-in, no data. */
    ItemStack icon(FishTable.Species s) {
        ItemStack item = Coast.item(s.oraxenId(), 1);
        if (item != null) return item;
        item = new ItemStack(fallbackFish);
        item.set(DataComponents.CUSTOM_NAME, Adv.vanilla(text().item(color(s.rarity()) + escape(s.name()))));
        return item;
    }

    /** Any other fishing item (shellfish, extras, pearls) by id; a stand-in only when items.fallback lists it. */
    @Nullable
    ItemStack other(String id, int amount) {
        ItemStack item = Coast.item(id, amount);
        if (item == null) {
            Item mat = fallbackMaterial.get(id);
            if (mat == null) return null;
            item = new ItemStack(mat);
            item.set(DataComponents.CUSTOM_NAME, Adv.vanilla(text().item("<text>" + escape(fallbackName.getOrDefault(id, id)))));
            Pdc.itemPutString(item, ITEM_ID, id);
        }
        item.setCount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        return item;
    }

    private static String escape(String s) {
        return s.replace("<", "\\<");
    }

    // ------------------------------------------------------------------ reading

    record Fish(String species, String rarity, int grams, UUID catcher) {
    }

    /** The fish data of an item, or null when it isn't a custom fish. */
    @Nullable
    Fish read(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        String sp = Pdc.itemString(s, SPECIES);
        if (sp == null) return null;
        String r = Pdc.itemString(s, RARITY);
        int g = Coast.itemInt(s, WEIGHT, 0);
        UUID who = null;
        try {
            String c = Pdc.itemString(s, CATCHER);
            if (c != null) who = UUID.fromString(c);
        } catch (IllegalArgumentException ignored) {
        }
        return new Fish(sp, r == null ? "common" : r, g, who);
    }

    /** A weight as shown to players ("3.2 lb"); see {@link FishWeight#format}. */
    static String lb(int grams) {
        return FishWeight.format(grams);
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        long min = s / 60;
        if (min < 60) return min + "m " + (s % 60) + "s";
        return (min / 60) + "h " + (min % 60) + "m";
    }

    static String nameOf(UUID id) {
        if (id == null) return "?";
        var op = Feather.get().lookup().byId(id);
        return op == null || op.name() == null ? "?" : op.name();
    }

    static final Set<Item> VANILLA_FISH = Set.of(Items.COD, Items.SALMON, Items.TROPICAL_FISH, Items.PUFFERFISH);
    static final Set<Item> TREASURE = Set.of(Items.BOW, Items.ENCHANTED_BOOK, Items.FISHING_ROD, Items.NAME_TAG,
            Items.NAUTILUS_SHELL, Items.SADDLE);
}
