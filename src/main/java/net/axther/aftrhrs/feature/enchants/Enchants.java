package net.axther.aftrhrs.feature.enchants;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The Aftr Hrs enchantments (docs/enchants.md). Their definitions are data ({@code data/aftrhrs/enchantment}, written by
 * {@code tools/gen_enchants.py}); the effects vanilla can't express are worked by this package, keyed by these ids.
 * Reading a level needs no registry access: an item's enchantments are holders that know their own key.
 */
public final class Enchants {
    // melee
    public static final ResourceKey<Enchantment> RIMEBITE = key("rimebite");
    public static final ResourceKey<Enchantment> LEECHING = key("leeching");
    public static final ResourceKey<Enchantment> CULLING = key("culling");
    public static final ResourceKey<Enchantment> WITCHING_HOUR = key("witching_hour");
    public static final ResourceKey<Enchantment> CLEAVE = key("cleave");
    public static final ResourceKey<Enchantment> GIANTSLAYER = key("giantslayer");
    // ranged
    public static final ResourceKey<Enchantment> SEEKER = key("seeker");
    public static final ResourceKey<Enchantment> VOLLEY = key("volley");
    public static final ResourceKey<Enchantment> MARKING = key("marking");
    public static final ResourceKey<Enchantment> HARPOON = key("harpoon");
    // tools
    public static final ResourceKey<Enchantment> MOLTEN_TOUCH = key("molten_touch");
    public static final ResourceKey<Enchantment> PROSPECTOR = key("prospector");
    public static final ResourceKey<Enchantment> TIMBERFALL = key("timberfall");
    public static final ResourceKey<Enchantment> POCKETING = key("pocketing");
    public static final ResourceKey<Enchantment> REPLANTING = key("replanting");
    public static final ResourceKey<Enchantment> BOUNTIFUL = key("bountiful");
    public static final ResourceKey<Enchantment> TREASURE_HUNTER = key("treasure_hunter");
    // armor
    public static final ResourceKey<Enchantment> SECOND_WIND = key("second_wind");
    public static final ResourceKey<Enchantment> DEFLECTION = key("deflection");
    public static final ResourceKey<Enchantment> NIGHTSIGHT = key("nightsight");
    public static final ResourceKey<Enchantment> PACK_BOND = key("pack_bond");
    public static final ResourceKey<Enchantment> DRIFTWALKER = key("driftwalker");
    // fishing
    public static final ResourceKey<Enchantment> STEADY_LINE = key("steady_line");
    public static final ResourceKey<Enchantment> HEAVY_HAUL = key("heavy_haul");
    public static final ResourceKey<Enchantment> TWIN_HOOK = key("twin_hook");
    // anything
    public static final ResourceKey<Enchantment> SOULBOUND = key("soulbound");

    // ---- numbers the code uses (the descriptions in gen_enchants.py say the same)
    static final float LEECH_PER_LEVEL = 0.04f, LEECH_MAX = 4f;
    static final float CULL_BELOW = 0.35f, CULL_PER_LEVEL = 0.10f;
    static final float WITCHING_PER_LEVEL = 1f;
    static final float CLEAVE_PER_LEVEL = 0.20f, CLEAVE_RANGE = 2.5f;
    static final int CLEAVE_TARGETS = 3;
    static final float GIANT_HEALTH = 40f, GIANT_PER_LEVEL = 0.12f;
    static final int MARK_TICKS_PER_LEVEL = 80;
    static final float MARK_BONUS = 0.10f;
    static final double SEEKER_RANGE = 24, SEEKER_CONE = Math.cos(Math.toRadians(35));
    static final int PROSPECTOR_PER_LEVEL = 8;
    static final float BOUNTIFUL_PER_LEVEL = 0.15f;
    static final float TREASURE_PER_LEVEL = 0.005f;
    static final float SECOND_WIND_BELOW = 6f;
    static final int SECOND_WIND_COOLDOWN = 20 * 120;
    static final float DEFLECT_PER_LEVEL = 0.08f;
    static final int NIGHTSIGHT_LIGHT = 7;
    static final double PACK_RANGE = 24;
    static final float PACK_PER_LEVEL = 0.15f;
    static final double DRIFT_SPEED_PER_LEVEL = 0.15;
    public static final double STEADY_LINE_PER_LEVEL = 0.15;
    public static final double HEAVY_HAUL_SKEW = 0.08, HEAVY_HAUL_TROPHY = 0.02;
    static final float TWIN_HOOK_PER_LEVEL = 0.10f;

    private Enchants() {
    }

    static ResourceKey<Enchantment> key(String id) {
        return ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath("aftrhrs", id));
    }

    /** The level of {@code key} on a stack (0 when it hasn't got it). */
    public static int level(ItemStack stack, ResourceKey<Enchantment> key) {
        if (stack == null || stack.isEmpty()) return 0;
        ItemEnchantments e = stack.getEnchantments();
        if (e.isEmpty()) return 0;
        for (var entry : e.entrySet()) {
            Holder<Enchantment> h = entry.getKey();
            if (h.is(key)) return entry.getIntValue();
        }
        return 0;
    }

    /** The level on the main hand. */
    public static int held(LivingEntity e, ResourceKey<Enchantment> key) {
        return e == null ? 0 : level(e.getMainHandItem(), key);
    }

    /** The level on the piece worn in {@code slot}. */
    public static int worn(LivingEntity e, EquipmentSlot slot, ResourceKey<Enchantment> key) {
        return e == null ? 0 : level(e.getItemBySlot(slot), key);
    }
}
