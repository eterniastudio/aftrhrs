package net.axther.aftrhrs.core;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;

/**
 * Hub art from the mod's assets, drawn by {@code art/hub_art.py}: the banner (font {@code feather:hub_banner},
 * character U+E000; on Paper an Oraxen animated glyph) and, in the font {@code feather:hub}, the moon-phase icons
 * (U+E200 + phase). The codepoints must match the generator. Each piece is only used when its font file ships in
 * the mod ({@code assets/feather/font/...}); otherwise the hub falls back to plain text.
 */
public final class HubArt {
    public static final Key FONT = Key.key("feather", "hub");
    public static final Key BANNER_FONT = Key.key("feather", "hub_banner");
    /** Two 150px buttons and the gap between them: a one-column button this wide lines up with a two-column grid. */
    public static final int GRID_WIDTH = 302;
    private static final int MOON_CP = 0xE200;

    private HubArt() {
    }

    /** Whether the hub art fonts are in the mod. */
    public static boolean available() {
        return ModAssets.has("feather", "font/hub.json");
    }

    /** The banner, or null if the mod doesn't ship it. */
    public static Component banner() {
        if (!ModAssets.has("feather", "font/hub_banner.json")) return null;
        // No drop shadow: it would smear the art.
        return Component.textOfChildren(Component.text("\uE000").font(BANNER_FONT).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()));
    }

    /** Moon icon for a phase index in vanilla's {@code MoonPhase} order (0 = full moon). */
    public static Component moon(int phase) {
        return glyphs(Character.toString(MOON_CP + Math.floorMod(phase, 8)));
    }

    private static Component glyphs(String s) {
        return Component.textOfChildren(Component.text(s).font(FONT).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()));
    }
}
