/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * Reworked from util/Curve, util/Curve3d and Catenary#visitPoints.
 */
package net.axther.aftrhrs.feature.fairylights.math;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A polyline relative to its start point: the shape of one strand. Immutable. */
public final class Curve {
    private final float[] x, y, z;
    private final float[] cum; // distance along the curve at each point
    private final float length;

    public Curve(float[] x, float[] y, float[] z) {
        this.x = x;
        this.y = y;
        this.z = z;
        cum = new float[x.length];
        float l = 0;
        for (int i = 1; i < x.length; i++) {
            float dx = x[i] - x[i - 1], dy = y[i] - y[i - 1], dz = z[i] - z[i - 1];
            l += Mth.sqrt(dx * dx + dy * dy + dz * dz);
            cum[i] = l;
        }
        length = l;
    }

    public static Curve line(float x0, float y0, float z0, float x1, float y1, float z1) {
        return new Curve(new float[]{x0, x1}, new float[]{y0, y1}, new float[]{z0, z1});
    }

    public int count() {
        return x.length;
    }

    public float x(int i) {
        return x[i];
    }

    public float y(int i) {
        return y[i];
    }

    public float z(int i) {
        return z[i];
    }

    public float length() {
        return length;
    }

    /** Distance along the curve of point i. */
    public float at(int i) {
        return cum[i];
    }

    /** Lowest point below the start (positive = below), for the sag readout. */
    public float sag() {
        float min = 0;
        float end = y[y.length - 1];
        for (int i = 0; i < y.length; i++) {
            float line = end * i / (y.length - 1f);
            min = Math.max(min, line - y[i]);
        }
        return min;
    }

    /** What a visitor gets for each point placed along the curve. */
    public interface PointVisitor {
        /** index along the curve; position relative to the start; yaw/pitch (radians) of the segment it sits on; t = 0..1 along the strand. */
        void visit(int index, float px, float py, float pz, float yaw, float pitch, float t);
    }

    /**
     * Points every {@code spacing} blocks along the curve. {@code center}: the leftover length is split between both
     * ends (lights hang evenly, none sits on the hook); otherwise the first point is at the start and the end gets one too.
     */
    public void visit(float spacing, boolean center, PointVisitor visitor) {
        if (count() < 2 || length <= 0) return;
        float distance = center ? (length % spacing + spacing) / 2f : 0;
        int index = 0;
        for (int i = 0; i + 1 < count(); i++) {
            float seg = cum[i + 1] - cum[i];
            if (seg <= 0) continue;
            float yaw = yaw(i), pitch = pitch(i);
            while (distance < seg) {
                float t = distance / seg;
                visitor.visit(index++, Mth.lerp(t, x[i], x[i + 1]), Mth.lerp(t, y[i], y[i + 1]), Mth.lerp(t, z[i], z[i + 1]), yaw, pitch,
                        (cum[i] + distance) / length);
                distance += spacing;
            }
            distance -= seg;
            if (!center && i + 2 == count()) visitor.visit(index++, x[i + 1], y[i + 1], z[i + 1], yaw, pitch, 1);
        }
    }

    /** Horizontal angle of segment i (radians, atan2(dz, dx)). */
    public float yaw(int i) {
        float dx = x[i + 1] - x[i], dz = z[i + 1] - z[i];
        if (Math.abs(dx) < 1e-6f && Math.abs(dz) < 1e-6f) {
            // a vertical segment: take the strand's overall direction
            float ex = x[x.length - 1], ez = z[z.length - 1];
            return Math.abs(ex) < 1e-6f && Math.abs(ez) < 1e-6f ? 0 : (float) Mth.atan2(ez, ex);
        }
        return (float) Mth.atan2(dz, dx);
    }

    /** Slope of segment i (radians, up is positive). */
    public float pitch(int i) {
        float dx = x[i + 1] - x[i], dy = y[i + 1] - y[i], dz = z[i + 1] - z[i];
        return (float) Mth.atan2(dy, Mth.sqrt(dx * dx + dz * dz));
    }

    /** Bounds relative to the start. */
    public AABB bounds() {
        float x0 = 0, y0 = 0, z0 = 0, x1 = 0, y1 = 0, z1 = 0;
        for (int i = 0; i < count(); i++) {
            x0 = Math.min(x0, x[i]);
            y0 = Math.min(y0, y[i]);
            z0 = Math.min(z0, z[i]);
            x1 = Math.max(x1, x[i]);
            y1 = Math.max(y1, y[i]);
            z1 = Math.max(z1, z[i]);
        }
        return new AABB(x0, y0, z0, x1, y1, z1);
    }

    /** Distance squared from a point (relative to the start) to the curve, and the 0..1 position of the nearest point. */
    public double[] nearest(Vec3 p) {
        double best = Double.MAX_VALUE, bestT = 0;
        for (int i = 0; i + 1 < count(); i++) {
            double ax = x[i], ay = y[i], az = z[i];
            double bx = x[i + 1] - ax, by = y[i + 1] - ay, bz = z[i + 1] - az;
            double ll = bx * bx + by * by + bz * bz;
            double u = ll < 1e-9 ? 0 : Mth.clamp(((p.x - ax) * bx + (p.y - ay) * by + (p.z - az) * bz) / ll, 0, 1);
            double cx = ax + bx * u - p.x, cy = ay + by * u - p.y, cz = az + bz * u - p.z;
            double d = cx * cx + cy * cy + cz * cz;
            if (d < best) {
                best = d;
                bestT = length <= 0 ? 0 : (cum[i] + Math.sqrt(ll) * u) / length;
            }
        }
        return new double[]{best, bestT};
    }
}
