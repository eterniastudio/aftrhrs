/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (The NBT "pattern" / "string" / "color" / "text" of the connection items, as one item component.)
 */
package net.axther.aftrhrs.feature.fairylights;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a strand item carries (component {@code aftrhrs:strand}) and what a hung strand shows: its bulbs (String of
 * Lights), flags (Pennant Bunting), color (Tinsel), wire color and the bunting's letters. Empty lists = a bare strand.
 */
public record StrandSpec(List<Bulb> bulbs, List<Flag> flags, int color, Wire wire, String text) {
    public static final int MAX_PATTERN = 64;
    public static final int MAX_TEXT = 32;
    public static final StrandSpec EMPTY = new StrandSpec(List.of(), List.of(), -1, Wire.DARK, "");

    public static final Codec<StrandSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Bulb.CODEC.sizeLimitedListOf(MAX_PATTERN * 4).optionalFieldOf("bulbs", List.of()).forGetter(StrandSpec::bulbs),
            Flag.CODEC.sizeLimitedListOf(MAX_PATTERN * 4).optionalFieldOf("flags", List.of()).forGetter(StrandSpec::flags),
            Codec.INT.optionalFieldOf("color", -1).forGetter(StrandSpec::color),
            Wire.CODEC.optionalFieldOf("wire", Wire.DARK).forGetter(StrandSpec::wire),
            Codec.sizeLimitedString(256).optionalFieldOf("text", "").forGetter(StrandSpec::text)
    ).apply(i, StrandSpec::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, StrandSpec> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

    public StrandSpec {
        bulbs = List.copyOf(bulbs.size() > MAX_PATTERN ? bulbs.subList(0, MAX_PATTERN) : bulbs);
        flags = List.copyOf(flags.size() > MAX_PATTERN ? flags.subList(0, MAX_PATTERN) : flags);
        text = cleanText(text);
    }

    public StrandSpec withBulbs(List<Bulb> b) {
        return new StrandSpec(b, flags, color, wire, text);
    }

    public StrandSpec withFlags(List<Flag> f) {
        return new StrandSpec(bulbs, f, color, wire, text);
    }

    public StrandSpec withColor(int c) {
        return new StrandSpec(bulbs, flags, c, wire, text);
    }

    public StrandSpec withWire(Wire w) {
        return new StrandSpec(bulbs, flags, color, w, text);
    }

    public StrandSpec withText(String t) {
        return new StrandSpec(bulbs, flags, color, wire, t);
    }

    /** Uppercase letters, digits and a little punctuation (what the pennant letters can show). */
    public static String cleanText(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
            if (b.length() >= MAX_TEXT) break;
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || " !?.,'&+-*#@:".indexOf(c) >= 0) b.append(c);
        }
        return b.toString();
    }

    /** The pattern made {@code n} long (repeating), so one place can be changed on its own. */
    public static <T> List<T> expand(List<T> pattern, int n, T fill) {
        List<T> out = new ArrayList<>(Math.min(Math.max(n, pattern.size()), MAX_PATTERN));
        for (int i = 0; i < Math.min(Math.max(n, pattern.size()), MAX_PATTERN); i++) out.add(pattern.isEmpty() ? fill : pattern.get(i % pattern.size()));
        return out;
    }

    /** One bulb on a String of Lights. color -1 = the style's own color. */
    public record Bulb(BulbStyle style, int color, boolean twinkle) {
        public static final Codec<Bulb> CODEC = RecordCodecBuilder.create(i -> i.group(
                BulbStyle.CODEC.fieldOf("style").forGetter(Bulb::style),
                Codec.INT.optionalFieldOf("color", -1).forGetter(Bulb::color),
                Codec.BOOL.optionalFieldOf("twinkle", false).forGetter(Bulb::twinkle)
        ).apply(i, Bulb::new));

        public int rgb() {
            return color < 0 || !style.dyeable ? style.defaultColor : color & 0xFFFFFF;
        }
    }

    /** One pennant on a Pennant Bunting. */
    public record Flag(Pennant shape, int color) {
        public static final Codec<Flag> CODEC = RecordCodecBuilder.create(i -> i.group(
                Pennant.CODEC.fieldOf("shape").forGetter(Flag::shape),
                Codec.INT.optionalFieldOf("color", -1).forGetter(Flag::color)
        ).apply(i, Flag::new));

        public int rgb() {
            return color < 0 ? 0xF2EEE4 : color & 0xFFFFFF;
        }
    }

    public enum Pennant implements StringRepresentable {
        TRIANGLE, SWALLOWTAIL, SQUARE;

        public static final Codec<Pennant> CODEC = StringRepresentable.fromEnum(Pennant::values);

        public String itemId() {
            return "fairy_pennant_" + getSerializedName();
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The cord's color on a String of Lights (crafted with a dye: white dye = white wire, green dye = green). */
    public enum Wire implements StringRepresentable {
        DARK(0x2E2B29), WHITE(0xE8E4DA), GREEN(0x2F4A2C);

        public static final Codec<Wire> CODEC = StringRepresentable.fromEnum(Wire::values);
        public final int rgb;

        Wire(int rgb) {
            this.rgb = rgb;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
