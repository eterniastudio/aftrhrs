package net.axther.aftrhrs.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.axther.aftrhrs.feature.bossfx.ShakePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Boss camera shake ({@link ShakePayload}): a decaying tremble of the view, applied with the hurt tilt
 * ({@code GameRendererShakeMixin}). Several shakes overlap by taking the strongest that is still running. Scaled by the
 * Damage Tilt accessibility option, so players who turned the hurt tilt off get no shake either.
 */
public final class BossShake {
    private static float strength;
    private static long start;
    private static long length;

    private BossShake() {
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ShakePayload.TYPE, (payload, ctx) -> add(payload.strength(), payload.ticks()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> strength = 0);
    }

    static void add(float s, int ticks) {
        long now = System.nanoTime();
        float left = current(now);
        if (s >= left) {
            strength = Math.min(6f, s);
            start = now;
            length = Math.max(1, ticks) * 50_000_000L;
        }
    }

    private static float current(long now) {
        if (strength <= 0 || now - start >= length) return 0;
        float u = (now - start) / (float) length;
        return strength * (1 - u) * (1 - u);
    }

    /** Called before the hurt tilt is applied to the camera pose. */
    public static void apply(PoseStack pose) {
        long now = System.nanoTime();
        float s = current(now);
        if (s <= 0.001f) return;
        double tilt = Minecraft.getInstance().options.damageTiltStrength().get();
        s *= (float) tilt;
        if (s <= 0.001f) return;
        float t = (now % 100_000_000_000L) / 1.0e9f;
        float roll = s * (Mth.sin(t * 47f) * 0.6f + Mth.sin(t * 83f + 1.7f) * 0.4f);
        float pitch = s * 0.6f * (Mth.sin(t * 61f + 0.4f) * 0.7f + Mth.sin(t * 29f + 2.1f) * 0.3f);
        float yaw = s * 0.35f * Mth.sin(t * 37f + 0.9f);
        pose.rotateDegrees(Axis.ZP, roll);
        pose.rotateDegrees(Axis.XP, pitch);
        pose.rotateDegrees(Axis.YP, yaw);
    }
}
