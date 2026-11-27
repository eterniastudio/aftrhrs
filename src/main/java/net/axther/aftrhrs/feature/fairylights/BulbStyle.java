/*
 * Light styles adapted from Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/item/SimpleLightVariant: spacing, cord orientation; the shapes are redrawn for Aftr Hrs.)
 */
package net.axther.aftrhrs.feature.fairylights;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.AABB;

import java.util.Locale;

/**
 * The light styles a String of Lights can carry. Each is an item ({@code aftrhrs:fairy_<id>}); the shapes live in the
 * client's {@code BulbModels} (and the item models from {@code tools/fairylights_art.py}).
 */
public enum BulbStyle implements StringRepresentable {
    // id, spacing (blocks), light level, dyeable, default color, hangs along the cord, hit box (px, attach point at 0)
    FAIRY("bulb", 0.5f, 10, true, 0xFFE2A8, true, box(-1.2, -3.8, -1.2, 1.2, 0.2, 1.2)),
    HOLIDAY("holiday_bulb", 0.75f, 12, true, 0xE0393B, false, box(-1.6, -5.6, -1.6, 1.6, 0.2, 1.6)),
    EDISON("edison_bulb", 1.0f, 13, false, 0xFFB45A, false, box(-2.0, -6.0, -2.0, 2.0, 0.2, 2.0)),
    PAPER_LANTERN("paper_lantern", 1.25f, 13, true, 0xE0483B, false, box(-3.2, -9.0, -3.2, 3.2, 0.2, 3.2)),
    ORB_LANTERN("orb_lantern", 1.0f, 12, true, 0xF4F0E4, false, box(-3.0, -7.0, -3.0, 3.0, 0.2, 3.0)),
    STAR("star", 1.0f, 11, true, 0xFFD45E, true, box(-3.6, -8.0, -0.8, 3.6, 0.2, 0.8)),
    SNOWFLAKE("snowflake", 1.0f, 11, true, 0xCFEAFF, true, box(-3.8, -8.2, -0.6, 3.8, 0.2, 0.6)),
    ICICLE("icicle", 0.5f, 10, true, 0xE6F6FF, false, box(-1.2, -11.5, -1.2, 1.2, 0.2, 1.2)),
    CANDLE_LANTERN("candle_lantern", 1.25f, 13, false, 0xFFB050, false, box(-2.4, -8.0, -2.4, 2.4, 0.2, 2.4)),
    HEART("heart", 0.75f, 10, true, 0xFF6F9F, true, box(-3.2, -6.4, -0.8, 3.2, 0.2, 0.8)),
    FLOWER("flower", 0.75f, 10, true, 0xFFB0D2, true, box(-3.2, -3.4, -3.2, 3.2, 0.6, 3.2)),
    PUMPKIN("pumpkin", 1.0f, 12, false, 0xFFB43C, false, box(-2.6, -6.6, -2.6, 2.6, 0.2, 2.6)),
    GHOST("ghost", 1.0f, 9, true, 0xEEF4FF, false, box(-2.6, -8.0, -2.6, 2.6, 0.2, 2.6));

    public static final Codec<BulbStyle> CODEC = StringRepresentable.fromEnum(BulbStyle::values);

    private final String id;
    public final float spacing;
    public final int light;
    public final boolean dyeable;
    public final int defaultColor;
    /** Turns with the cord (flat pieces face along it) instead of spinning a little per bulb. */
    public final boolean alongCord;
    /** Hit box in blocks, relative to where it hangs from the cord. */
    public final AABB bounds;

    BulbStyle(String id, float spacing, int light, boolean dyeable, int defaultColor, boolean alongCord, AABB bounds) {
        this.id = id;
        this.spacing = spacing;
        this.light = light;
        this.dyeable = dyeable;
        this.defaultColor = defaultColor;
        this.alongCord = alongCord;
        this.bounds = bounds;
    }

    private static AABB box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new AABB(x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16);
    }

    /** Item id path: {@code fairy_<id>}. */
    public String itemId() {
        return "fairy_" + id;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
