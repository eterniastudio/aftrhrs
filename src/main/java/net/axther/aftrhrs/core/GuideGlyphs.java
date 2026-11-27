package net.axther.aftrhrs.core;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;

/**
 * Creature preview glyphs for the taming guide menu (ex Oraxen {@code glyphs/arcana_guide.yml}: {@code guide_<x>} big
 * previews, {@code guide_icon_<x>} button icons). On Paper Oraxen assigned the characters; in the mod every glyph has
 * its own font {@code aftrhrs:glyph/<name>} drawing U+E000, so nothing needs a character table. A glyph whose font
 * isn't in the mod comes back empty.
 */
public final class GuideGlyphs {
    private GuideGlyphs() {
    }

    /** The font key of a glyph. */
    public static Key font(String name) {
        return Key.key("aftrhrs", "glyph/" + name);
    }

    /** The glyph as white text without a drop shadow (glyph color multiplies the texture), or empty if missing. */
    public static Component of(String name) {
        if (name == null || !ModAssets.has("aftrhrs", "font/glyph/" + name + ".json")) return Component.empty();
        // Wrapped in a plain parent: text appended to the result must not inherit the glyph's font (it rendered as boxes).
        return Component.textOfChildren(Component.text("\uE000").font(font(name)).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()));
    }
}
