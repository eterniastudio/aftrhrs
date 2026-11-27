package net.axther.aftrhrs.feature.smithing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What melts into what (SMITHING.md 3.2) and what burns how hot (3.1). Units: one ingot is 90, a nugget 10, a block
 * 810, raw ore 130 (a small bonus for melting ore). Scrap (vanilla metal gear, our parts and tools) gives back 75%.
 * Fluids are material ids ("iron", "rose_gold"...) plus {@code fish_oil} (fish rendered at a low heat, for quenching).
 */
public final class Smelt {
    public static final int INGOT = 90, NUGGET = 10, BLOCK = 810, RAW = 130, RAW_BLOCK = 1170;
    public static final String FISH_OIL = "fish_oil";
    public static final int FISH_OIL_HEAT = 150, FISH_UNITS = 30;

    /** One fluid an item melts into. */
    public record Melt(String fluid, int units) {
    }

    /** A fuel: how long one item burns and the hottest it can make the forge (bellows working). */
    public record Fuel(int ticks, int cap, Item remainder) {
    }

    private static final Map<Item, List<Melt>> VANILLA = new HashMap<>();
    private static final Map<Item, Fuel> FUELS = new HashMap<>();

    private Smelt() {
    }

    private static void put(Item item, String fluid, int units) {
        VANILLA.put(item, List.of(new Melt(fluid, units)));
    }

    private static int scrap(int ingots) {
        return Math.round(ingots * INGOT * 0.75f);
    }

    private static void gear(String fluid, Item pick, Item axe, Item shovel, Item hoe, Item sword, Item helm, Item chest, Item legs, Item boots) {
        put(pick, fluid, scrap(3));
        put(axe, fluid, scrap(3));
        put(shovel, fluid, scrap(1));
        put(hoe, fluid, scrap(2));
        put(sword, fluid, scrap(2));
        if (helm != null) put(helm, fluid, scrap(5));
        if (chest != null) put(chest, fluid, scrap(8));
        if (legs != null) put(legs, fluid, scrap(7));
        if (boots != null) put(boots, fluid, scrap(4));
    }

    static synchronized void init() {
        if (!VANILLA.isEmpty()) return;
        put(Items.IRON_INGOT, "iron", INGOT);
        put(Items.IRON_NUGGET, "iron", NUGGET);
        put(Items.IRON_BLOCK, "iron", BLOCK);
        put(Items.RAW_IRON, "iron", RAW);
        put(Items.RAW_IRON_BLOCK, "iron", RAW_BLOCK);
        put(Items.BUCKET, "iron", scrap(3));
        put(Items.GOLD_INGOT, "gold", INGOT);
        put(Items.GOLD_NUGGET, "gold", NUGGET);
        put(Items.GOLD_BLOCK, "gold", BLOCK);
        put(Items.RAW_GOLD, "gold", RAW);
        put(Items.RAW_GOLD_BLOCK, "gold", RAW_BLOCK);
        put(Items.COPPER_INGOT, "copper", INGOT);
        put(Items.COPPER_NUGGET, "copper", NUGGET);
        for (Item cb : Items.COPPER_BLOCK.asList()) put(cb, "copper", BLOCK);
        put(Items.RAW_COPPER, "copper", RAW);
        put(Items.RAW_COPPER_BLOCK, "copper", RAW_BLOCK);
        put(Items.NETHERITE_INGOT, "netherite", INGOT);
        put(Items.NETHERITE_SCRAP, "netherite", 22);
        put(Items.NETHERITE_BLOCK, "netherite", BLOCK);
        put(Items.OBSIDIAN, "obsidian", INGOT);
        put(Items.CRYING_OBSIDIAN, "obsidian", INGOT);
        gear("iron", Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE, Items.IRON_SWORD,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
        gear("gold", Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_HOE, Items.GOLDEN_SWORD,
                Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS);
        gear("copper", Items.COPPER_PICKAXE, Items.COPPER_AXE, Items.COPPER_SHOVEL, Items.COPPER_HOE, Items.COPPER_SWORD,
                Items.COPPER_HELMET, Items.COPPER_CHESTPLATE, Items.COPPER_LEGGINGS, Items.COPPER_BOOTS);
        // netherite gear: the netherite ingot of the upgrade (the diamonds are lost)
        for (Item i : new Item[]{Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE, Items.NETHERITE_SWORD,
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS}) {
            put(i, "netherite", scrap(1));
        }

        fuel(Items.COAL, 1600, 1250);
        fuel(Items.CHARCOAL, 1600, 1250);
        fuel(Items.COAL_BLOCK, 14400, 1250);
        fuel(Items.BLAZE_ROD, 2400, 1450);
        fuel(Items.BLAZE_POWDER, 1000, 1450);
        FUELS.put(Items.LAVA_BUCKET, new Fuel(20000, 1650, Items.BUCKET));
        fuel(Items.DRIED_KELP_BLOCK, 4000, 900);
    }

    private static void fuel(Item item, int ticks, int cap) {
        FUELS.put(item, new Fuel(ticks, cap, null));
    }

    /** The fuel {@code s} is, or null. Other furnace fuels (wood, sticks) burn cool (700°). */
    public static Fuel fuel(ItemStack s, Level level) {
        init();
        Fuel f = FUELS.get(s.getItem());
        if (f != null) return f;
        if (s.has(net.minecraft.core.component.DataComponents.COOKING_FUEL)) return new Fuel(800, 700, null);
        return null;
    }

    /** What {@code s} (one item) melts into, or an empty list. */
    public static List<Melt> melt(ItemStack s) {
        init();
        if (s.isEmpty()) return List.of();
        List<Melt> v = VANILLA.get(s.getItem());
        if (v != null) return v;
        if (s.getItem() instanceof MetalItem mi) return List.of(new Melt(mi.metal, mi.nugget ? NUGGET : INGOT));
        if (s.getItem() instanceof PartItem pi) {
            SmithingDefs.Material m = SmithingDefs.material(s.get(Smithing.MATERIAL));
            if (m != null && m.metal()) return List.of(new Melt(m.id(), Math.round(pi.part.cost() * INGOT * 0.75f / Math.max(1, pi.part.makes()))));
            return List.of();
        }
        ToolBuild b = s.get(Smithing.BUILD);
        if (b != null) {
            Map<String, Integer> sum = new HashMap<>();
            for (ToolBuild.PartEntry e : b.parts()) {
                SmithingDefs.Material m = SmithingDefs.material(e.material());
                SmithingDefs.Part p = SmithingDefs.part(e.part());
                if (m == null || p == null || !m.metal()) continue;
                sum.merge(m.id(), Math.round(p.cost() * INGOT * 0.75f), Integer::sum);
            }
            List<Melt> out = new ArrayList<>();
            sum.forEach((k, u) -> out.add(new Melt(k, u)));
            return out;
        }
        if (isFish(s)) return List.of(new Melt(FISH_OIL, FISH_UNITS));
        return List.of();
    }

    public static boolean isFish(ItemStack s) {
        if (s.is(ItemTags.FISHES)) return true;
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return path.startsWith("arcana_fish_") && !path.contains("trophy");
    }

    /** The heat a fluid needs to melt (its material's melting point; fish oil renders at 150°). */
    public static int meltHeat(String fluid) {
        if (FISH_OIL.equals(fluid)) return FISH_OIL_HEAT;
        SmithingDefs.Material m = SmithingDefs.material(fluid);
        return m == null || m.melt() <= 0 ? 1000 : m.melt();
    }

    /** Tier-4 metals only melt in the Great Crucible. */
    public static boolean needsGreat(String fluid) {
        SmithingDefs.Material m = SmithingDefs.material(fluid);
        return m != null && m.tier() >= 4;
    }

    /** The color of a fluid (its material's brightest-but-one palette color, glowing when molten). */
    public static int color(String fluid) {
        if (FISH_OIL.equals(fluid)) return 0xC8902A;
        SmithingDefs.Material m = SmithingDefs.material(fluid);
        return m == null ? 0xFF8030 : m.molten();   // parsed once when the materials load
    }

    /** The ingot a metal casts into (vanilla where it exists). */
    public static ItemStack ingot(String metal, boolean nugget) {
        Item vanilla = switch (metal) {
            case "iron" -> nugget ? Items.IRON_NUGGET : Items.IRON_INGOT;
            case "gold" -> nugget ? Items.GOLD_NUGGET : Items.GOLD_INGOT;
            case "copper" -> nugget ? Items.COPPER_NUGGET : Items.COPPER_INGOT;
            case "netherite" -> nugget ? Items.NETHERITE_SCRAP : Items.NETHERITE_INGOT;
            case "obsidian" -> nugget ? null : Items.OBSIDIAN;
            default -> null;
        };
        if (vanilla != null) return new ItemStack(vanilla);
        MetalItem mi = nugget ? Smithing.NUGGETS.get(metal) : Smithing.INGOTS.get(metal);
        return mi == null ? ItemStack.EMPTY : new ItemStack(mi);
    }

    /** The metal an ingot-like item is (vanilla or ours), or null: what the anvil forges from scratch. */
    public static String ingotMetal(ItemStack s) {
        if (s.getItem() instanceof MetalItem mi && !mi.nugget) return mi.metal;
        if (s.is(Items.IRON_INGOT)) return "iron";
        if (s.is(Items.GOLD_INGOT)) return "gold";
        if (s.is(Items.COPPER_INGOT)) return "copper";
        if (s.is(Items.NETHERITE_INGOT)) return "netherite";
        return null;
    }
}
