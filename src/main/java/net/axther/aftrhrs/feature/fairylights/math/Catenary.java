/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * Ported to Aftr Hrs (Fabric 26.3) and reworked: util/Catenary + util/CatenaryUtils + util/CubicBezier + the
 * vertical helix of Connection.
 */
package net.axther.aftrhrs.feature.fairylights.math;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The hanging shape of a rope of a given length between two points: the catenary {@code y = cosh(x * a) / a + b},
 * solved for the rope length with a damped Newton search (after the MATLAB "catenary hanging rope between two points"
 * routine Fairy Lights used). Strands get extra length from their slack, eased by a cubic bezier so short strands
 * barely sag and long ones droop like real string lights.
 */
public final class Catenary {
    /** Points per block of rope. */
    public static final int POINTS_PER_BLOCK = 3;
    private static final int MIN_POINTS = 8;
    private static final int MAX_ITER = 100;
    private static final float MIN_GRAD = 1e-10f, MIN_VAL = 1e-8f, STEP_DEC = 0.5f, MIN_STEP = 1e-9f, MIN_HORIZ = 1e-3f;
    /** Slack easing of Fairy Lights' Connection (cubic-bezier 0.495, 0.505, 0.495, 0.505). */
    private static final Bezier SLACK = new Bezier(0.495f, 0.505f, 0.495f, 0.505f);

    private Catenary() {
    }

    /**
     * The curve from (0,0,0) to {@code to} (relative), with {@code slack} 0 (taut) .. 3, for strands up to {@code maxLength}
     * blocks long. Two fence posts right above each other get a loose helix instead (a straight vertical line looks odd).
     */
    public static Curve between(Vec3 to, float slack, float maxLength, boolean helixWhenVertical) {
        float dist = (float) to.length();
        if (dist < 1e-6f) return Curve.line(0, 0, 0, 0, 0, 0);
        if (helixWhenVertical && to.horizontalDistance() < 1e-2) return helix(to);
        float length;
        if (slack < 1e-2f || Math.abs(to.x) < 1e-6 && Math.abs(to.z) < 1e-6) {
            length = dist;
        } else {
            float eased = SLACK.eval(Mth.clamp(dist / maxLength, 0, 1)) * maxLength;
            length = dist + (eased - dist) * slack;
        }
        return rope(to, length, maxLength);
    }

    /** A rope of {@code ropeLength} from the origin to {@code dir}. */
    public static Curve rope(Vec3 dir, float ropeLength, float maxLength) {
        float endX = (float) Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float endY = (float) dir.y;
        float angle = endX < 1e-3f ? 0 : (float) Mth.atan2(dir.z, dir.x);
        float vx = Mth.cos(angle), vz = Mth.sin(angle);
        if (dir.length() > 2 * maxLength) return Curve.line(0, 0, 0, (float) dir.x, (float) dir.y, (float) dir.z);
        int count = Math.max((int) (ropeLength * POINTS_PER_BLOCK), MIN_POINTS);
        float[] x = new float[count], y = new float[count];
        solve(0, 0, endX, endY, ropeLength, count, x, y);
        float[] px = new float[count], pz = new float[count];
        for (int i = 0; i < count; i++) {
            px[i] = x[i] * vx;
            pz[i] = x[i] * vz;
        }
        return new Curve(px, y, pz);
    }

    private static Curve helix(Vec3 vec) {
        float length = (float) vec.length();
        float height = (float) vec.y;
        float step = 0.25f, loopsPerBlock = 1, radius = 0.33f;
        int steps = Math.max(4, (int) (Mth.TWO_PI * radius * loopsPerBlock * length / step));
        float rad = -Mth.TWO_PI * loopsPerBlock * length;
        float[] x = new float[steps], y = new float[steps], z = new float[steps];
        for (int i = 0; i < steps; i++) {
            float t = (float) i / (steps - 1);
            x[i] = radius * Mth.cos(t * rad) - radius;
            y[i] = t * height;
            z[i] = radius * Mth.sin(t * rad);
        }
        return new Curve(x, y, z);
    }

    private static void solve(float x1, float y1, float x2, float y2, float length, int n, float[] x, float[] y) {
        float d = x2 - x1, h = y2 - y1;
        linspace(x1, x2, n, x);
        if (Math.abs(d) < MIN_HORIZ || length <= Mth.sqrt(d * d + h * h)) {
            linspace(y1, y2, n, y);
            return;
        }
        float sag = 1;
        for (int iter = 0; iter < MAX_ITER; iter++) {
            float val = g(sag, d, length, h);
            float grad = dg(sag, d);
            if (Math.abs(val) < MIN_VAL || Math.abs(grad) < MIN_GRAD) break;
            float search = -val / grad;
            float alpha = 1;
            float next = sag + alpha * search;
            float valAbs = Math.abs(val);
            while (next < 0 || Math.abs(g(next, d, length, h)) > valAbs) {
                alpha *= STEP_DEC;
                if (alpha < MIN_STEP) break;
                next = sag + alpha * search;
            }
            sag = next;
        }
        float xLeft = 0.5f * ((float) Math.log((length + h) / (length - h)) / sag - d);
        float xMin = x1 - xLeft;
        float bias = y1 - (float) Math.cosh(xLeft * sag) / sag;
        for (int i = 0; i < n; i++) y[i] = (float) Math.cosh((x[i] - xMin) * sag) / sag + bias;
        // guard against float blow-ups on extreme inputs: fall back to a straight line
        for (int i = 0; i < n; i++) {
            if (!Float.isFinite(y[i])) {
                linspace(y1, y2, n, y);
                return;
            }
        }
    }

    private static float dg(float s, float d) {
        return 2 * (float) Math.cosh(s * d / 2) * d / (2 * s) - 2 * (float) Math.sinh(s * d / 2) / (s * s);
    }

    private static float g(float s, float d, float length, float h) {
        return 2 * (float) Math.sinh(s * d / 2) / s - Mth.sqrt(length * length - h * h);
    }

    private static void linspace(float base, float limit, int n, float[] out) {
        float step = n > 1 ? (limit - base) / (n - 1) : 0;
        for (int i = 0; i < n; i++) out[i] = base + step * i;
    }

    /** Cubic bezier easing (after gre/bezier-easing, as Fairy Lights' CubicBezier). */
    public static final class Bezier {
        private static final int TABLE = 11;
        private static final float STEP = 1f / (TABLE - 1);
        private final float x1, y1, x2, y2;
        private final float[] samples = new float[TABLE];

        public Bezier(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            for (int i = 0; i < TABLE; i++) samples[i] = calc(i * STEP, x1, x2);
        }

        public float eval(float x) {
            if (x1 == y1 && x2 == y2) return x;
            if (x <= 0) return 0;
            if (x >= 1) return 1;
            return calc(tForX(x), y1, y2);
        }

        private float tForX(float x) {
            float start = 0;
            int cur = 1;
            for (; cur != TABLE - 1 && samples[cur] <= x; cur++) start += STEP;
            cur--;
            float dist = (x - samples[cur]) / (samples[cur + 1] - samples[cur]);
            float t = start + dist * STEP;
            for (int i = 0; i < 4; i++) {
                float slope = 3 * a(x1, x2) * t * t + 2 * b(x1, x2) * t + 3 * x1;
                if (slope == 0) break;
                t -= (calc(t, x1, x2) - x) / slope;
            }
            return t;
        }

        private static float calc(float t, float a1, float a2) {
            return ((a(a1, a2) * t + b(a1, a2)) * t + 3 * a1) * t;
        }

        private static float a(float a1, float a2) {
            return 1 - 3 * a2 + 3 * a1;
        }

        private static float b(float a1, float a2) {
            return 3 * a2 - 6 * a1;
        }
    }
}
