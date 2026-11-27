package net.axther.aftrhrs.feature.fairylights;

import java.util.Locale;

/** What hangs on a strand (Fairy Lights' connection types; letter bunting became letters on the pennant bunting). */
public enum StrandKind {
    /** String of Lights: bulbs along a wire, real light at night. */
    LIGHTS("fairy_lights", 0.0625f),
    /** Pine Garland: evergreen boughs with berries, a ribbon bow on the posts. */
    GARLAND("fairy_garland", 0.16f),
    /** Tinsel: a shiny dyeable rope. */
    TINSEL("fairy_tinsel", 0.125f),
    /** Pennant Bunting: little flags, optionally with letters. */
    BUNTING("fairy_bunting", 0.05f);

    public final String itemId;
    /** Half thickness of the cord for clicks (blocks). */
    public final float radius;

    StrandKind(String itemId, float radius) {
        this.itemId = itemId;
        this.radius = radius;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static StrandKind byKey(String key) {
        for (StrandKind k : values()) if (k.key().equals(key)) return k;
        return null;
    }
}
