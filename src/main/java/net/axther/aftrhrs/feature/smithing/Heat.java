package net.axther.aftrhrs.feature.smithing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Heat of a workpiece (the {@code aftrhrs:smith_heat} component): a temperature and the game time it was measured.
 * Cooling is computed, never ticked: {@code T(t) = air + (T0 - air) e^(-(t - t0) / tau)}, so a part cools in a chest
 * or a hand the same way. Colors follow the smith's scale (dull red, cherry, orange, yellow, white).
 */
public final class Heat {
    public static final float AIR = 20f;
    /** Cooling time constant in ticks: iron from 1100° stays above 800° about 10 s. */
    public static final float TAU = 600f;
    /** Above this a part burns a bare hand. */
    public static final float BURNS = 300f;

    public record Stamp(float temp, long time) {
        public static final Codec<Stamp> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("temp").forGetter(Stamp::temp),
                Codec.LONG.fieldOf("time").forGetter(Stamp::time)).apply(i, Stamp::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Stamp> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Stamp::temp, ByteBufCodecs.VAR_LONG, Stamp::time, Stamp::new);

        public float at(long now) {
            if (now <= time) return temp;
            return AIR + (temp - AIR) * (float) Math.exp(-(now - time) / TAU);
        }
    }

    private Heat() {
    }

    /** Temperature of {@code s} right now (air temperature when it has no heat). */
    public static float of(ItemStack s, Level level) {
        Stamp st = s.get(Smithing.HEAT);
        return st == null || level == null ? AIR : st.at(level.getGameTime());
    }

    public static float of(ItemStack s, long now) {
        Stamp st = s.get(Smithing.HEAT);
        return st == null ? AIR : st.at(now);
    }

    public static void set(ItemStack s, float temp, long now) {
        if (temp <= AIR + 40) s.remove(Smithing.HEAT);
        else s.set(Smithing.HEAT, new Stamp(temp, now));
    }

    public static void clear(ItemStack s) {
        s.remove(Smithing.HEAT);
    }

    /** Moves {@code s} toward {@code target} by one step of {@code ticks} (the forge heating a workpiece). */
    public static void approach(ItemStack s, float target, int ticks, long now) {
        float t = of(s, now);
        float k = 1f - (float) Math.exp(-ticks / 140f);
        // on the coals the stamp stays even while barely warm, so the warmth adds up
        s.set(Smithing.HEAT, new Stamp(t + (target - t) * k, now));
    }

    /** 0 below 400° (no glow), rising to 1 at white heat; packed ARGB color of the glow. */
    public static int glow(float temp) {
        if (temp < 400) return 0;
        float[][] stops = {{400, 0x40, 0x08, 0x04}, {600, 0x9A, 0x16, 0x0A}, {800, 0xD8, 0x30, 0x12}, {1000, 0xFF, 0x7A, 0x1E},
                {1200, 0xFF, 0xC8, 0x48}, {1450, 0xFF, 0xF4, 0xD8}};
        for (int i = 1; i < stops.length; i++) {
            if (temp <= stops[i][0] || i == stops.length - 1) {
                float f = Math.min(1, Math.max(0, (temp - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0])));
                int r = (int) (stops[i - 1][1] + (stops[i][1] - stops[i - 1][1]) * f);
                int g = (int) (stops[i - 1][2] + (stops[i][2] - stops[i - 1][2]) * f);
                int b = (int) (stops[i - 1][3] + (stops[i][3] - stops[i - 1][3]) * f);
                int a = (int) Math.min(255, 90 + (temp - 400) / 5);
                return a << 24 | r << 16 | g << 8 | b;
            }
        }
        return 0;
    }

    /** Words for a temperature ("cherry red"), for tooltips and recipe notes. */
    public static Component describe(float temp) {
        String k;
        ChatFormatting c;
        if (temp < 100) {
            k = "cold";
            c = ChatFormatting.GRAY;
        } else if (temp < 400) {
            k = "warm";
            c = ChatFormatting.GOLD;
        } else if (temp < 600) {
            k = "dull_red";
            c = ChatFormatting.DARK_RED;
        } else if (temp < 800) {
            k = "cherry";
            c = ChatFormatting.RED;
        } else if (temp < 1000) {
            k = "orange";
            c = ChatFormatting.GOLD;
        } else if (temp < 1200) {
            k = "yellow";
            c = ChatFormatting.YELLOW;
        } else {
            k = "white";
            c = ChatFormatting.WHITE;
        }
        return Component.translatable("aftrhrs.smithing.heat." + k, Math.round(temp)).withStyle(c);
    }

    /** The bare-hand burn: a little damage and a hint to use tongs. */
    public static void burn(Player p) {
        if (!(p instanceof ServerPlayer sp) || sp.isCreative()) return;
        ServerLevel level = sp.level();
        sp.hurtServer(level, level.damageSources().hotFloor(), 1f);
        sp.sendOverlayMessage(Component.translatable("aftrhrs.smithing.burn").withStyle(ChatFormatting.RED));
    }
}
