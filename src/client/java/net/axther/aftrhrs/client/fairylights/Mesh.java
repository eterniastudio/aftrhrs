package net.axther.aftrhrs.client.fairylights;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Tiny immediate geometry writer for the strand renderers: boxes, quads and tubes in a local frame ({@link #local()},
 * in blocks) written into a submitted custom-geometry buffer, with per-face colors and the right normals. One matrix
 * transform per vertex (pose times frame, refreshed only when the frame changes) and the bulk vertex write.
 */
final class Mesh {
    /** Texture regions of {@code textures/entity/fairy_lights.png} (64x64), as u0, v0, u1, v1 in 0..1. */
    static final float[] WHITE = uv(0.5f, 0.5f, 3.5f, 3.5f);
    static final float[] PINE_A = uv(16, 0, 32, 16), PINE_B = uv(32, 0, 48, 16);
    static final float[] TINSEL = uv(48, 0, 50, 8);
    static final float[] PENNANT_TRIANGLE = uv(0, 16, 16, 32), PENNANT_SWALLOWTAIL = uv(16, 16, 32, 32), PENNANT_SQUARE = uv(32, 16, 48, 32);
    static final float[] BOW = uv(48, 16, 64, 32);
    static final float[] GLOW = uv(0, 32, 16, 48);
    static final float[] BERRY = uv(16, 32, 20, 36);
    /** Regions a baked quad can use, by {@link StrandRender.Baked#uv} index. */
    static final byte UV_WHITE = 0, UV_PINE_A = 1, UV_PINE_B = 2, UV_TINSEL = 3;
    private static final float[][] BAKED_UV = {WHITE, PINE_A, PINE_B, TINSEL};

    private static float[] uv(float x0, float y0, float x1, float y1) {
        return new float[]{x0 / 64f, y0 / 64f, x1 / 64f, y1 / 64f};
    }

    VertexConsumer buf;
    /** The local frame (blocks, relative to the pose). Private: writers go through {@link #local()}, which marks it dirty. */
    private final Matrix4f m = new Matrix4f();
    /** The pose (copied in {@link #set}), and the products refreshed lazily when the frame changed. */
    private final Matrix4f pm = new Matrix4f(), mv = new Matrix4f(), inv = new Matrix4f();
    private final Matrix3f pn = new Matrix3f(), nm = new Matrix3f(), mn = new Matrix3f();
    /** Frame axes as final eye-space normals (box faces use them as they are). */
    private final Vector3f ax = new Vector3f(), ay = new Vector3f(), az = new Vector3f();
    /** The camera in pose space, and in the current local frame. */
    private final Vector3f eyePose = new Vector3f(), eyeLocal = new Vector3f();
    /**
     * Culling slack in blocks: a face whose plane passes this close to the eye is still drawn. Covers float error and the
     * view bobbing, which GameRenderer folds into the projection matrix (it moves the real eye up to ~0.11 blocks off the
     * pose-space origin this works from).
     */
    private static final float CULL_SLACK = 0.15f;
    private float localScale = 1, epsLocal = CULL_SLACK;
    private boolean dirty = true;
    /**
     * Skip faces turned away from the camera (boxes, tubes, pennant cards). Only for opaque, closed shapes drawn in the
     * cutout pass, where a back face is always hidden behind the same shape's front faces: set by the caller after
     * {@link #set}, which clears it. Never for translucent parts (their back faces show through) or the glow cards.
     */
    boolean cull;

    /**
     * Whether {@link #cull} may be turned on: not while a shader pack is in use. Iris replays entity geometry in its
     * shadow pass with an orthographic, sun-facing pose, where the point the pose maps to the origin is not the
     * projection centre, so faces turned to the sun but away from the player would be dropped from the shadows.
     */
    static boolean cullable() {
        return !net.axther.aftrhrs.client.compat.IrisCompat.shaderPackInUse();
    }

    private final Vector3f a = new Vector3f(), n = new Vector3f();
    /** Scratch for callers (render thread only). */
    final Vector3f tmp = new Vector3f();

    Mesh set(PoseStack.Pose pose, VertexConsumer buf) {
        this.buf = buf;
        pm.set(pose.pose());
        pn.set(pose.normal());
        // the camera sits at the origin of the space the pose maps into (submitCustomGeometry poses are camera-relative)
        inv.set(pm).invertAffine().transformPosition(0, 0, 0, eyePose);
        m.identity();
        dirty = true;
        cull = false;
        return this;
    }

    /** The local frame, to change: marks the cached products stale. */
    Matrix4f local() {
        dirty = true;
        return m;
    }

    /** One transform per vertex: mv = pose * frame, nm = pose normal * frame normal, the frame's axes, the eye in the frame. */
    private void refresh() {
        if (!dirty) return;
        pm.mul(m, mv);
        pn.mul(m.normal(mn), nm);
        nm.getColumn(0, ax);
        nm.getColumn(1, ay);
        nm.getColumn(2, az);
        unit(ax);
        unit(ay);
        unit(az);
        localScale = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        m.invertAffine(inv).transformPosition(eyePose, eyeLocal);
        epsLocal = CULL_SLACK / Math.max(localScale, 1e-6f);
        dirty = false;
    }

    private static void unit(Vector3f v) {
        float l = v.length();
        if (l > 1e-6f) v.div(l);
    }

    /** A local normal through {@link #nm}, normalized into {@link #n} (once per quad or face). */
    private void normal(float nx, float ny, float nz) {
        nm.transform(nx, ny, nz, n);
        unit(n);
    }

    /**
     * One vertex in the local frame; the normal is {@link #n}, already in eye space. One transform, then the 11-argument
     * bulk write (BufferBuilder's fast path for the entity format, the default chain for others). Never the Pose/Matrix
     * overloads of VertexConsumer: their defaults allocate a Vector3f per call unless the JIT happens to inline them.
     */
    private void vertex(float x, float y, float z, float u, float v, int argb, int light) {
        mv.transformPosition(x, y, z, a);
        buf.addVertex(a.x, a.y, a.z, argb, u, v, OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
    }

    /** A quad (corners counter-clockwise seen from the front) with a texture region. */
    void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
              float[] t, int argb, int light, float nx, float ny, float nz) {
        refresh();
        normal(nx, ny, nz);
        corners(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, t, argb, light);
    }

    /** The four corners of a quad whose normal is already in {@link #n}. */
    private void corners(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
                         float[] t, int argb, int light) {
        vertex(x0, y0, z0, t[0], t[1], argb, light);
        vertex(x1, y1, z1, t[0], t[3], argb, light);
        vertex(x2, y2, z2, t[2], t[3], argb, light);
        vertex(x3, y3, z3, t[2], t[1], argb, light);
    }

    /** A box face whose normal is {@code s} times a frame axis. */
    private void face(Vector3f axis, float s, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                      float x3, float y3, float z3, int argb, int light) {
        n.set(axis).mul(s);
        corners(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, WHITE, argb, light);
    }

    /** An axis box in the local frame (blocks) with flat colors: lighter top, darker bottom (the mod's voxel shading). */
    void box(float x0, float y0, float z0, float x1, float y1, float z1, int rgb, int alpha, int light) {
        refresh();
        int mask = cull ? boxFaceMask(eyeLocal.x, eyeLocal.y, eyeLocal.z, x0, y0, z0, x1, y1, z1, epsLocal) : ALL_FACES;
        if (mask == 0) return;
        int top = argb(shade(rgb, 1.08f), alpha), side = argb(rgb, alpha), side2 = argb(shade(rgb, 0.9f), alpha), bottom = argb(shade(rgb, 0.72f), alpha);
        if ((mask & FACE_UP) != 0) face(ay, 1, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, top, light);
        if ((mask & FACE_DOWN) != 0) face(ay, -1, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, bottom, light);
        if ((mask & FACE_NORTH) != 0) face(az, -1, x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, side2, light);
        if ((mask & FACE_SOUTH) != 0) face(az, 1, x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, side, light);
        if ((mask & FACE_WEST) != 0) face(ax, -1, x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, side2, light);
        if ((mask & FACE_EAST) != 0) face(ax, 1, x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, side, light);
    }

    /** Face bits of {@link #boxFaceMask}: +Y, -Y, -Z, +Z, -X, +X. */
    static final int FACE_UP = 1, FACE_DOWN = 2, FACE_NORTH = 4, FACE_SOUTH = 8, FACE_WEST = 16, FACE_EAST = 32, ALL_FACES = 63;

    /**
     * The faces of the axis box ({@code x0..x1, y0..y1, z0..z1}, in its own frame) that the eye ({@code ex, ey, ez}, same
     * frame) can see: a face is kept when the eye lies in front of its plane, or less than {@code eps} behind it. Exact for
     * an axis box in its own frame (the face normal dotted with eye minus any point of the face). An eye inside the box
     * sees none (the shapes are never entered).
     */
    static int boxFaceMask(float ex, float ey, float ez, float x0, float y0, float z0, float x1, float y1, float z1, float eps) {
        int m = 0;
        if (ey > y1 - eps) m |= FACE_UP;
        if (ey < y0 + eps) m |= FACE_DOWN;
        if (ez < z0 + eps) m |= FACE_NORTH;
        if (ez > z1 - eps) m |= FACE_SOUTH;
        if (ex < x0 + eps) m |= FACE_WEST;
        if (ex > x1 - eps) m |= FACE_EAST;
        return m;
    }

    /**
     * Whether a flat card at {@code (px, py, pz)} with the local normal {@code (nx, ny, nz)} faces the eye (always, with
     * {@link #cull} off). For two-card shapes such as the pennants, where the card in front hides the one behind.
     */
    boolean facing(float px, float py, float pz, float nx, float ny, float nz) {
        if (!cull) return true;
        refresh();
        return nx * (eyeLocal.x - px) + ny * (eyeLocal.y - py) + nz * (eyeLocal.z - pz) > -epsLocal;
    }

    /** A flat textured card in the local XY plane (both sides drawn by the no-cull render type). */
    void card(float x0, float y0, float x1, float y1, float z, float[] t, int argb, int light) {
        quad(x0, y1, z, x0, y0, z, x1, y0, z, x1, y1, z, t, argb, light, 0, 0, 1);
    }

    /**
     * Baked decoration quads {@code [from, to)} (every {@code stride}-th), offset by ({@code bx, by, bz}) = strand start
     * minus the pose origin plus the frame's sway at each quad: straight through the pose (the frame is not used), one
     * transform per corner, no trig, no allocation.
     */
    void bakedQuads(StrandRender.Baked b, int from, int to, int stride, float bx, float by, float bz, float sway, float px, float pz, int color,
                    int[] light) {
        float[] c = b.corners, nn = b.normals;
        for (int q = from; q < to; q += stride) {
            float off = sway * b.sinT[q];
            float dx = bx + px * off, dy = by - Math.abs(off) * 0.12f, dz = bz + pz * off;
            int argb = b.tinted ? argb(shade(color, b.shadeK[q]), 255) : b.argb[q];
            float[] t = BAKED_UV[b.uv[q]];
            int l = light[b.lightIdx[q]];
            pn.transform(nn[q * 3], nn[q * 3 + 1], nn[q * 3 + 2], n);
            unit(n);
            int k = q * 12;
            for (int j = 0; j < 12; j += 3) {
                pm.transformPosition(c[k + j] + dx, c[k + j + 1] + dy, c[k + j + 2] + dz, a);
                // corner order 0..3 takes uv (u0,v0) (u0,v1) (u1,v1) (u1,v0)
                buf.addVertex(a.x, a.y, a.z, argb, j < 6 ? t[0] : t[2], j == 0 || j == 9 ? t[1] : t[3], OverlayTexture.NO_OVERLAY, l, n.x, n.y, n.z);
            }
        }
    }

    /** A square tube from a to b (in the current frame), {@code w} wide, for cords. */
    void tube(float ax, float ay, float az, float bx, float by, float bz, float w, int rgb, int la, int lb) {
        refresh();
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5f) return;
        dx /= len;
        dy /= len;
        dz /= len;
        // side = d x up (or x when vertical), up2 = side x d
        float sx = -dz, sy = 0, sz = dx;
        float sl = (float) Math.sqrt(sx * sx + sz * sz);
        if (sl < 1e-4f) {
            sx = 1;
            sz = 0;
            sl = 1;
        }
        sx /= sl;
        sz /= sl;
        float ux = sy * dz - sz * dy, uy = sz * dx - sx * dz, uz = sx * dy - sy * dx;
        float h = w / 2;
        int top = argb(shade(rgb, 1.12f), 255), side = argb(rgb, 255), bottom = argb(shade(rgb, 0.7f), 255);
        // culling: a long face is seen when the eye is in front of its plane (normal . (eye - a) > h); at most two are
        float du = 1, ds = 1, lim = h - epsLocal;
        if (cull) {
            float qx = eyeLocal.x - ax, qy = eyeLocal.y - ay, qz = eyeLocal.z - az;
            du = ux * qx + uy * qy + uz * qz;
            ds = sx * qx + sy * qy + sz * qz;
        }
        // four faces: up, side+, down, side-
        if (!cull || du > lim) tubeFace(ax, ay, az, bx, by, bz, ux * h, uy * h, uz * h, sx * h, sy * h, sz * h, top, la, lb, ux, uy, uz);
        if (!cull || ds > lim) tubeFace(ax, ay, az, bx, by, bz, sx * h, sy * h, sz * h, -ux * h, -uy * h, -uz * h, side, la, lb, sx, sy, sz);
        if (!cull || -du > lim) tubeFace(ax, ay, az, bx, by, bz, -ux * h, -uy * h, -uz * h, -sx * h, -sy * h, -sz * h, bottom, la, lb, -ux, -uy, -uz);
        if (!cull || -ds > lim) tubeFace(ax, ay, az, bx, by, bz, -sx * h, -sy * h, -sz * h, ux * h, uy * h, uz * h, side, la, lb, -sx, -sy, -sz);
    }

    /** One long face of a tube: offset o (face center), half-width e along the face. */
    private void tubeFace(float ax, float ay, float az, float bx, float by, float bz, float ox, float oy, float oz, float ex, float ey, float ez,
                          int argb, int la, int lb, float nx, float ny, float nz) {
        float[] t = WHITE;
        normal(nx, ny, nz);
        vertex(ax + ox - ex, ay + oy - ey, az + oz - ez, t[0], t[1], argb, la);
        vertex(ax + ox + ex, ay + oy + ey, az + oz + ez, t[0], t[3], argb, la);
        vertex(bx + ox + ex, by + oy + ey, bz + oz + ez, t[2], t[3], argb, lb);
        vertex(bx + ox - ex, by + oy - ey, bz + oz - ez, t[2], t[1], argb, lb);
    }

    static int argb(int rgb, int alpha) {
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    static int shade(int rgb, float f) {
        int r = Math.min(255, (int) ((rgb >> 16 & 255) * f)), g = Math.min(255, (int) ((rgb >> 8 & 255) * f)), b = Math.min(255, (int) ((rgb & 255) * f));
        return r << 16 | g << 8 | b;
    }

    static int mix(int a, int b, float t) {
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }
}
