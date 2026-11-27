package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.GuideGlyphs;
import net.axther.aftrhrs.core.Pdc;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Plushies (ex-Oraxen furniture items {@code arcana_plush_<model>}, now the native {@code aftrhrs:arcana_plush_*}
 * items) and the taming guide's creature glyphs. Every call is null-safe: a plushie that isn't in the mod yet is
 * simply missing (revive by plushie is then unavailable; the menus fall back to a vanilla icon).
 */
final class PlushItems {
    private PlushItems() {
    }

    /** The Oraxen-style id of a stack ({@code arcana_plush_beagle}): the native item's id, or an unconverted Paper tag. */
    static String id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key != null && AftrHrs.ID.equals(key.getNamespace())) return key.getPath();
        String legacy = Pdc.itemString(stack, "oraxen:id");
        if (legacy == null) legacy = Pdc.legacyItemString(stack, "oraxen:id");
        return legacy;
    }

    static Item item(String id) {
        if (id == null) return null;
        Identifier key = Identifier.tryParse(AftrHrs.ID + ":" + id);
        if (key == null) return null;
        Item item = BuiltInRegistries.ITEM.getValue(key);
        return item == null || item == Items.AIR ? null : item;
    }

    static ItemStack build(String id) {
        Item item = item(id);
        return item == null ? null : new ItemStack(item);
    }

    /** A guide glyph tinted ({@code tint} multiplies the texture: near-black is a silhouette), or null when missing. */
    static Component glyph(String name, TextColor tint) {
        if (name == null) return null;
        Component g = GuideGlyphs.of(name);
        if (g.equals(Component.empty())) return null;
        return g.color(tint != null ? tint : NamedTextColor.WHITE);
    }
}
