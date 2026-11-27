package net.axther.aftrhrs.feature.shop;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;

/**
 * The market stall chest background (ex Oraxen glyph {@code feather_stall_gui}, drawn by {@code art/stall_gui.py}),
 * shipped in the mod as font {@code aftrhrs:glyph/feather_stall_gui}: U+E000 is the 176x188 image (the whole three-row
 * chest plus a 20 px awning, ascent 33), U+E001 and U+E002 are spaces of -8 and -169 px. The title is drawn at x 8, so
 * we shift back 8, draw the glyph (177 px advance), then shift back 169 to write the title at x 8 again.
 */
final class StallArt {
    static final Key FONT = Key.key("aftrhrs", "glyph/feather_stall_gui");

    private StallArt() {
    }

    static Component title(Component text) {
        Component art = Component.text("").font(FONT).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none());
        return Component.empty().append(art).append(text);
    }
}
