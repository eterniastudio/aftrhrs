/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (client/renderer/block/entity/*Renderer: wire, lights, garland rings, tinsel strips, pennants and letters; redrawn
 * for the 26.3 render-state pipeline and the Aftr Hrs art.)
 */
package net.axther.aftrhrs.client.fairylights;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.feature.fairylights.BulbStyle;
import net.axther.aftrhrs.feature.fairylights.Fastener;
import net.axther.aftrhrs.feature.fairylights.Strand;
import net.axther.aftrhrs.feature.fairylights.StrandKind;
import net.axther.aftrhrs.feature.fairylights.StrandSpec;
import net.axther.aftrhrs.feature.fairylights.math.Curve;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Turns a fastener's strands into render-state snapshots ({@link #extract}) and draws them ({@link #submit}). */
public final class StrandRender {
    static final Identifier TEXTURE = AftrHrs.id("textures/entity/fairy_lights.png");
    private static final int FULL = LightCoordsUtil.FULL_BRIGHT;

    /**
     * One strand, frozen for this frame (positions relative to the renderer's origin). One per strand shape, kept on its
     * {@link Baked} and refilled every frame: the arrays and lights are made once, only the per-frame fields change.
     */
    public static final class Draw {
        StrandKind kind;
        int wire, color;
        long seed;
        float[] x, y, z;
        int[] light;
        /** The lights / pennants: {@code feats[0, featCount)}. */
        Feat[] feats;
        int featCount;
        boolean highlight;
        int highlightFeature = -1;
        float time;
        /** The strand's baked decorations (kept: a rebake later in the frame can't change what this draws). */
        Baked baked;
        /** Strand start minus the pose origin; the sway and its sideways direction. */
        float bx, by, bz, sway, px, pz;
        /** Detail level: 0 near (under 32 blocks), 1 middle (to 64), 2 far. */
        int lod;
    }

    /** Detail falls off with the camera's distance to the nearest point of a strand (squared blocks). */
    private static final double LOD_NEAR = 32 * 32, LOD_FAR = 64 * 64;
    /** Tinsel strips drawn: every one near, every 4th in the middle, every 8th far. */
    private static final int[] TINSEL_STRIDE = {1, 4, 8};

    /**
     * Garland boughs and tinsel strips of one strand shape, worked out once on the unswayed curve: quad corners (12
     * floats each, relative to the strand's start, matrices already applied), unit normals (3 each), how much of the
     * sway each quad takes ({@code sinT}), its curve point for light, colour (garland) or sparkle (tinsel, tinted by the
     * strand's colour every frame) and texture region. Garland berries and cones are the tail from {@link #boxStart}.
     * Cached on the strand ({@link Strand#renderCache}) until its {@link Curve} is recomputed.
     */
    static final class Baked {
        final Curve curve;
        final AABB bounds;
        final boolean tinted;
        float[] corners = new float[0], normals = new float[0], sinT = new float[0], shadeK = new float[0];
        short[] lightIdx = new short[0];
        int[] argb = new int[0];
        byte[] uv = new byte[0];
        int quadCount, boxStart;
        /**
         * This shape's frame snapshot, refilled by every {@link #extract}. Reusing it is safe because extract, submit and
         * the custom-geometry lambdas all run on the render thread within one frame (no double buffering).
         */
        Draw draw;
        /** {@link FairyLightsClient#renderTick} of the last light sample ({@link #extract} samples once per client tick). */
        int lightTick = Integer.MIN_VALUE;

        Baked(Curve curve, boolean tinted) {
            this.curve = curve;
            this.bounds = curve.bounds();
            this.tinted = tinted;
        }
    }

    /** A light or pennant on the strand: the shape's fields are set once (with its {@link Draw}), the rest every frame. */
    static final class Feat {
        int index, light;
        float x, y, z, yaw, pitch, roll, spin;
        /** Along the strand (0..1) and the point on the unswayed curve, relative to its start. */
        float t, fx, fy, fz;
        BulbStyle style;
        int rgb;
        float bright;
        StrandSpec.Pennant shape;
        char letter, letterBack;
        boolean twinkle;
        /** Drawn with {@link BulbShapes#LOD_PARTS}: past LOD_DIST, back to full detail inside 0.9 x LOD_DIST. */
        boolean far;
    }

    /**
     * The one geometry writer for every strand and knot lambda: they run one after another on the render thread and each
     * starts with {@link Mesh#set}.
     */
    static final Mesh MESH = new Mesh();
    /** extract's block position for light lookups (render thread only). */
    private static final BlockPos.MutableBlockPos LIGHT_POS = new BlockPos.MutableBlockPos();

    /** The camera's right and up for the halo cards (render thread only: written and read inside one frame's lambda). */
    private static final Vector3f HALO_RIGHT = new Vector3f(), HALO_UP = new Vector3f();

    private StrandRender() {
    }

    /**
     * Snapshots every strand starting at {@code f}; {@code origin} is where the renderer's pose sits (world), {@code camera}
     * the camera position (for the detail level).
     */
    public static void extract(Fastener f, Vec3 origin, Vec3 camera, float pt, Level level, List<Draw> out) {
        if (f.outgoing().isEmpty()) return;
        Vec3 from = f.point(pt);
        float time = level.getGameTime() + pt;
        for (Strand s : f.outgoing()) {
            Fastener d = s.resolveDest(level);
            if (d == null) continue;
            Vec3 to = d.point(pt);
            Curve c = s.curve(from, to);
            long seed = s.id.getMostSignificantBits() ^ s.id.getLeastSignificantBits();
            Baked bk = s.renderCache instanceof Baked k && k.curve == c ? k : null;
            if (bk == null) {
                bk = bake(s, c, seed);
                bk.draw = newDraw(s, c, bk, seed);
                s.renderCache = bk;
            }
            Draw dr = bk.draw;
            AABB bb = bk.bounds;
            double cx = Mth.clamp(camera.x, from.x + bb.minX, from.x + bb.maxX) - camera.x;
            double cy = Mth.clamp(camera.y, from.y + bb.minY, from.y + bb.maxY) - camera.y;
            double cz = Mth.clamp(camera.z, from.z + bb.minZ, from.z + bb.maxZ) - camera.z;
            double dist2 = cx * cx + cy * cy + cz * cz;
            dr.lod = dist2 < LOD_NEAR ? 0 : dist2 < LOD_FAR ? 1 : 2;
            dr.time = time;
            float sway = Mth.lerp(pt, s.swayPrev, s.swayPos);
            float swing = s.swayPos - s.swayPrev;
            double hx = to.x - from.x, hz = to.z - from.z, hl = Math.sqrt(hx * hx + hz * hz);
            double px = hl < 1e-4 ? 1 : -hz / hl, pz = hl < 1e-4 ? 0 : hx / hl;
            dr.bx = (float) (from.x - origin.x);
            dr.by = (float) (from.y - origin.y);
            dr.bz = (float) (from.z - origin.z);
            dr.sway = sway;
            dr.px = (float) px;
            dr.pz = (float) pz;
            // light once per client tick (also under /tick freeze), at the swayed points; a new or moved shape samples at once
            boolean sample = bk.lightTick != FairyLightsClient.renderTick;
            bk.lightTick = FairyLightsClient.renderTick;
            float len = Math.max(c.length(), 1e-4f);
            int n = c.count();
            BlockPos.MutableBlockPos mp = LIGHT_POS;
            for (int i = 0; i < n; i++) {
                float t = c.at(i) / len;
                float off = sway * Mth.sin(t * Mth.PI);
                double wx = from.x + c.x(i) + px * off, wy = from.y + c.y(i) - Math.abs(off) * 0.12, wz = from.z + c.z(i) + pz * off;
                dr.x[i] = (float) (wx - origin.x);
                dr.y[i] = (float) (wy - origin.y);
                dr.z[i] = (float) (wz - origin.z);
                if (sample) dr.light[i] = LightCoordsUtil.getLightCoords(level, mp.set(wx, wy, wz));
            }
            Feat[] feats = dr.feats;
            for (int i = 0; i < dr.featCount; i++) {
                Feat ft = feats[i];
                float off = sway * Mth.sin(ft.t * Mth.PI);
                double wx = from.x + ft.fx + px * off, wy = from.y + ft.fy - Math.abs(off) * 0.12, wz = from.z + ft.fz + pz * off;
                ft.x = (float) (wx - origin.x);
                ft.y = (float) (wy - origin.y);
                ft.z = (float) (wz - origin.z);
                float wobble = Mth.sin(time * 0.06f + ft.index * 1.7f) * 0.03f;
                ft.roll = Mth.clamp(-swing * 9 * Mth.sin(ft.t * Mth.PI), -0.7f, 0.7f) + wobble;
                if (sample) ft.light = LightCoordsUtil.getLightCoords(level, mp.set(wx, wy - 0.2, wz));
                if (ft.style != null) {
                    ft.bright = s.lit ? (ft.twinkle ? twinkle(dr.seed, ft.index, time) : flicker(ft.style, ft.index, time)) : 0;
                    // far lights: the few-box shape, each by its own distance, with a little hysteresis against popping
                    double ex = wx - camera.x, ey = wy - camera.y, ez = wz - camera.z;
                    double d2 = ex * ex + ey * ey + ez * ez;
                    float lod = BulbShapes.LOD_DIST[ft.style.ordinal()] * (ft.far ? 0.9f : 1f);
                    ft.far = d2 > lod * lod;
                }
            }
            dr.highlight = false;
            dr.highlightFeature = -1;
            StrandPicker.Hit hit = StrandPicker.current();
            if (hit != null && hit.strand() == s) {
                dr.highlight = true;
                dr.highlightFeature = hit.feature();
            }
            out.add(dr);
        }
    }

    /**
     * The frame snapshot of a fresh shape: arrays for its curve points and the lights / pennants with everything that only
     * changes with the shape or the spec (both make a new {@link Baked}), so {@link #extract} only fills per-frame fields.
     */
    private static Draw newDraw(Strand s, Curve c, Baked bk, long seed) {
        Draw dr = new Draw();
        int n = c.count();
        dr.x = new float[n];
        dr.y = new float[n];
        dr.z = new float[n];
        dr.light = new int[n];
        dr.kind = s.kind;
        dr.seed = seed;
        dr.wire = switch (s.kind) {
            case LIGHTS -> s.spec.wire().rgb;
            case GARLAND -> 0x3A5A2A;
            case TINSEL -> s.spec.color() < 0 ? 0xDDE3EA : s.spec.color();
            case BUNTING -> 0xE8E2D2;
        };
        dr.color = dr.wire;
        dr.baked = bk;
        String text = s.spec.text();
        List<Strand.Feature> list = s.features();
        int textStart = (list.size() - text.length()) / 2;
        dr.feats = new Feat[list.size()];
        for (Strand.Feature fe : list) {
            Feat ft = new Feat();
            ft.index = fe.index();
            ft.t = fe.t();
            ft.fx = fe.x();
            ft.fy = fe.y();
            ft.fz = fe.z();
            ft.yaw = fe.yaw();
            ft.pitch = fe.pitch();
            ft.spin = (float) Math.floorMod(hash(fe.index() + (int) seed), 360) * Mth.DEG_TO_RAD + Mth.PI / 4;
            StrandSpec.Bulb b = s.kind == StrandKind.BUNTING ? null : s.bulb(fe.index());
            if (b != null) {
                ft.style = b.style();
                ft.rgb = b.rgb();
                ft.twinkle = b.twinkle();
            } else {
                StrandSpec.Flag fl = s.flag(fe.index());
                if (fl == null) continue;
                ft.shape = fl.shape();
                ft.rgb = fl.rgb();
                int li = fe.index() - textStart;
                ft.letter = li >= 0 && li < text.length() ? text.charAt(li) : ' ';
                // seen from the other side the order flips, so that side gets the mirrored letter (Fairy Lights)
                ft.letterBack = li >= 0 && li < text.length() ? text.charAt(text.length() - 1 - li) : ' ';
            }
            dr.feats[dr.featCount++] = ft;
        }
        return dr;
    }

    static int hash(int x) {
        x = (x >> 16 ^ x) * 0x45D9F3B;
        x = (x >> 16 ^ x) * 0x45D9F3B;
        return x >> 16 ^ x;
    }

    /** Twinkling bulbs: now and then one dims and comes back (eased), each on its own schedule. */
    static float twinkle(long seed, int i, float time) {
        int h = hash(i * 31 + (int) seed);
        float period = 50 + Math.floorMod(h, 70);
        float phase = Math.floorMod(h >> 8, 1000) / 1000f * period;
        float u = ((time + phase) % period) / period;
        if ((Math.floorMod(h >> 4, 3) == 0) || u > 0.35f) return 1;
        float k = u / 0.35f;
        return 1 - 0.88f * Mth.sin(k * Mth.PI);
    }

    /** Candle and oil flames flicker a little; electric bulbs hold steady. */
    static float flicker(BulbStyle style, int i, float time) {
        if (style != BulbStyle.CANDLE_LANTERN) return 1;
        return 0.86f + 0.14f * (Mth.sin(time * 0.9f + i * 2.1f) * 0.5f + Mth.sin(time * 2.3f + i) * 0.5f);
    }

    // ------------------------------------------------------------------ drawing

    public static void submit(List<Draw> draws, PoseStack pose, SubmitNodeCollector out, net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        if (draws.isEmpty()) return;
        Mesh mesh = MESH;
        out.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, buf) -> {
            mesh.set(p, buf);
            // opaque closed boxes, cords and two-sided pennants: back faces are always hidden (but see Mesh.cullable)
            mesh.cull = Mesh.cullable();
            for (Draw d : draws) solid(mesh, d);
        });
        out.submitCustomGeometry(pose, RenderTypes.entityTranslucent(TEXTURE), (p, buf) -> {
            mesh.set(p, buf);
            for (Draw d : draws) translucent(mesh, d);
        });
        // halos round lit bulbs: emissive, soft-edged (the glow texture's alpha)
        out.submitCustomGeometry(pose, RenderTypes.eyes(TEXTURE), (p, buf) -> {
            mesh.set(p, buf);
            Vector3f right = HALO_RIGHT.set(1, 0, 0).rotate(camera.orientation), up = HALO_UP.set(0, 1, 0).rotate(camera.orientation);
            for (Draw d : draws) {
                if (d.lod >= 2) continue;   // far: no halos
                for (int i = 0; i < d.featCount; i++) {
                    Feat f = d.feats[i];
                    if (f.style != null && f.bright > 0.02f) halo(mesh, f, right, up);
                }
            }
        });
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        for (Draw d : draws) {
            if (d.kind != StrandKind.BUNTING || d.lod >= 2) continue;   // far: no letters
            for (int i = 0; i < d.featCount; i++) {
                Feat f = d.feats[i];
                if (f.letter != ' ' && f.letter != 0) letter(pose, out, font, f);
            }
        }
    }

    private static void solid(Mesh mesh, Draw d) {
        float w = switch (d.kind) {
            case LIGHTS -> 1.3f / 16;
            case GARLAND -> 1.6f / 16;
            case TINSEL -> 1.0f / 16;
            case BUNTING -> 0.9f / 16;
        };
        mesh.local().identity();
        for (int i = 0; i + 1 < d.x.length; i++) {
            mesh.tube(d.x[i], d.y[i], d.z[i], d.x[i + 1], d.y[i + 1], d.z[i + 1], w, d.wire, d.light[i], d.light[i + 1]);
        }
        Baked b = d.baked;
        if (b != null && b.quadCount > 0) {
            // garland: berries and cones only near; tinsel: fewer strips further away
            int to = d.kind == StrandKind.GARLAND && d.lod >= 1 ? b.boxStart : b.quadCount;
            int stride = d.kind == StrandKind.TINSEL ? TINSEL_STRIDE[d.lod] : 1;
            mesh.bakedQuads(b, 0, to, stride, d.bx, d.by, d.bz, d.sway, d.px, d.pz, d.color, d.light);
        }
        for (int i = 0; i < d.featCount; i++) {
            Feat f = d.feats[i];
            if (f.style != null) bulb(mesh, f, false);
            else if (f.shape != null) pennant(mesh, f);
        }
    }

    private static void translucent(Mesh mesh, Draw d) {
        for (int i = 0; i < d.featCount; i++) if (d.feats[i].style != null) bulb(mesh, d.feats[i], true);
        if (d.highlight) {
            mesh.local().identity();
            if (d.highlightFeature >= 0) {
                for (int i = 0; i < d.featCount; i++) {
                    Feat f = d.feats[i];
                    if (f.index != d.highlightFeature) continue;
                    frame(mesh, f);
                    var b = f.style != null ? f.style.bounds : new net.minecraft.world.phys.AABB(-0.22, -0.5, -0.03, 0.22, 0.02, 0.03);
                    mesh.box((float) b.minX - 0.01f, (float) b.minY - 0.01f, (float) b.minZ - 0.01f, (float) b.maxX + 0.01f, (float) b.maxY + 0.01f,
                            (float) b.maxZ + 0.01f, 0xFFFFFF, 60, FULL);
                }
            } else {
                for (int i = 0; i + 1 < d.x.length; i++) {
                    mesh.tube(d.x[i], d.y[i], d.z[i], d.x[i + 1], d.y[i + 1], d.z[i + 1], 3.2f / 16, 0xFFFFFF, FULL, FULL);
                }
            }
        }
    }

    /** Sets the mesh frame to a feature: at its point, turned along the cord, swinging. */
    private static void frame(Mesh mesh, Feat f) {
        mesh.local().identity().translate(f.x, f.y, f.z).rotateY(-f.yaw);
        boolean along = f.style == null || f.style.alongCord;
        if (along) mesh.local().rotateZ(f.pitch);
        mesh.local().rotateX(f.roll);
        if (!along) mesh.local().rotateY(f.spin);
    }

    // ------------------------------------------------------------------ lights

    private static void bulb(Mesh mesh, Feat f, boolean translucentPass) {
        frame(mesh, f);
        mesh.local().scale(1 / 16f);
        int st = f.style.ordinal();
        // far: the few-box shape, from where the light is about 8 px tall (each light by its own distance, see extract)
        float[][] parts = f.far ? BulbShapes.LOD_PARTS[st] : BulbShapes.PARTS[st];
        boolean lit = f.bright > 0.02f;
        for (float[] p : parts) {
            int mat = (int) p[6];
            boolean clear = mat == BulbShapes.CLEAR;
            if (clear != translucentPass) continue;
            int rgb, light = f.light, alpha = 255;
            switch (mat) {
                case BulbShapes.GLASS, BulbShapes.PAPER, BulbShapes.RIB -> {
                    int base = mat == BulbShapes.RIB ? Mesh.shade(f.rgb, 0.72f) : f.rgb;
                    if (lit) {
                        rgb = Mesh.mix(Mesh.shade(base, 0.5f), Mesh.mix(base, 0xFFFFFF, mat == BulbShapes.PAPER ? 0.18f : 0.32f), f.bright);
                        light = FULL;
                    } else {
                        rgb = Mesh.shade(base, 0.62f);
                    }
                }
                case BulbShapes.CLEAR -> {
                    rgb = lit ? Mesh.mix(0xFFF2D0, f.rgb, 0.45f) : 0xE8EEF2;
                    alpha = lit ? 110 : 70;
                    if (lit) light = FULL;
                }
                case BulbShapes.FLAME, BulbShapes.FILAMENT, BulbShapes.FACE -> {
                    if (lit) {
                        rgb = Mesh.mix(0x8A5A20, BulbShapes.COLORS[mat], f.bright);
                        light = FULL;
                    } else {
                        rgb = mat == BulbShapes.FACE ? 0x2A1608 : 0x4A3C30;
                    }
                }
                default -> rgb = BulbShapes.COLORS[mat];
            }
            mesh.box(p[0], p[1], p[2], p[3], p[4], p[5], rgb, alpha, light);
        }
    }

    /** Soft glow: a camera-facing card round the glass. */
    private static void halo(Mesh mesh, Feat f, Vector3f right, Vector3f up) {
        frame(mesh, f);
        float gy = BulbShapes.GLOW[f.style.ordinal()][0] / 16, gs = BulbShapes.GLOW[f.style.ordinal()][1] * 0.62f / 16;
        Vector3f c = mesh.local().transformPosition(0, gy, 0, mesh.tmp);
        mesh.local().identity();
        int col = Mesh.argb(Mesh.mix(f.rgb, 0xFFFFFF, 0.3f), (int) (165 * f.bright));
        float rx = right.x * gs, ry = right.y * gs, rz = right.z * gs, ux = up.x * gs, uy = up.y * gs, uz = up.z * gs;
        mesh.quad(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, c.x - rx - ux, c.y - ry - uy, c.z - rz - uz,
                c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, c.x + rx + ux, c.y + ry + uy, c.z + rz + uz, Mesh.GLOW, col, FULL, 0, 1, 0);
    }

    // ------------------------------------------------------------------ garland, tinsel, pennants

    /**
     * Bakes the strand's decorations on curve {@code c} (relative to its start): garland boughs every 0.22 blocks (three
     * crossed needle cards, now and then a berry pair or a pinecone), tinsel strips every 1/40 block. Same points, hashes
     * and matrices the per-frame drawing used, so a strand that hangs still looks the same.
     */
    static Baked bake(Strand s, Curve c, long seed) {
        Baker k = new Baker(c, s.kind == StrandKind.TINSEL);
        switch (s.kind) {
            case GARLAND -> {
                List<Tail> tail = new ArrayList<>();
                walk(c, 0.22f, (i, x, y, z, yaw, pitch, li, sinT) -> {
                    int h = hash(i * 7919 + (int) seed);
                    k.m.identity().translate(x, y, z).rotateY(-yaw).rotateZ(pitch)
                            .rotateX((Math.floorMod(h, 360)) * Mth.DEG_TO_RAD).rotateZ((Math.floorMod(h >> 9, 40) - 20) * Mth.DEG_TO_RAD);
                    byte t = (h & 1) == 0 ? Mesh.UV_PINE_A : Mesh.UV_PINE_B;
                    int tint = Mesh.argb(Mesh.shade(0xFFFFFF, 0.86f + Math.floorMod(h >> 3, 15) / 100f), 255);
                    float sz = 0.26f;
                    k.card(-sz, -sz, sz, sz, t, tint, 0, li, sinT);
                    k.m.rotateX(Mth.HALF_PI);
                    k.card(-sz, -sz, sz, sz, t, tint, 0, li, sinT);
                    k.m.rotateY(Mth.HALF_PI);
                    k.card(-sz * 0.8f, -sz * 0.8f, sz * 0.8f, sz * 0.8f, t, tint, 0, li, sinT);
                    // berries and cones go after every bough (a tail the middle distance skips)
                    if (Math.floorMod(h >> 12, 5) == 0) {
                        tail.add(new Tail(true, x, y, z, yaw, h, li, sinT));
                    } else if (Math.floorMod(h >> 12, 11) == 3) {
                        tail.add(new Tail(false, x, y, z, yaw, h, li, sinT));
                    }
                });
                k.boxStart();
                for (Tail e : tail) {
                    if (e.berry) {
                        k.m.identity().translate(e.x, e.y, e.z).rotateY(-e.yaw + (e.h >> 6) % 3).translate(0.06f, -0.09f, 0.1f);
                        k.box(-0.035f, -0.035f, -0.035f, 0.035f, 0.035f, 0.035f, 0xC8242B, e.li, e.sinT);
                        k.box(0.02f, -0.06f, -0.08f, 0.08f, 0.0f, -0.02f, 0xB51F27, e.li, e.sinT);
                    } else {
                        k.m.identity().translate(e.x, e.y - 0.1f, e.z).rotateY(-e.yaw);
                        k.box(-0.04f, -0.09f, -0.04f, 0.04f, 0.02f, 0.04f, 0x6B4A2E, e.li, e.sinT);
                        k.box(-0.025f, -0.12f, -0.025f, 0.025f, -0.09f, 0.025f, 0x5A3D25, e.li, e.sinT);
                    }
                }
            }
            case TINSEL -> walk(c, 1 / 40f, (i, x, y, z, yaw, pitch, li, sinT) -> {
                int h = hash(i * 131 + (int) seed);
                k.m.identity().translate(x, y, z).rotateY(-yaw).rotateZ(pitch)
                        .rotateX(Math.floorMod(h, 360) * Mth.DEG_TO_RAD).rotateY((Math.floorMod(h >> 9, 180) - 90) * Mth.DEG_TO_RAD)
                        .rotateZ((Math.floorMod(h >> 17, 44) - 22) * Mth.DEG_TO_RAD);
                float len = 0.16f + Math.floorMod(h >> 5, 7) / 100f;
                float sparkle = 0.8f + Math.floorMod(h >> 3, 40) / 100f;
                k.card(-0.03f, -len, 0.03f, len, Mesh.UV_TINSEL, 0, sparkle, li, sinT);
            });
            default -> {
            }
        }
        return k.done();
    }

    /** A garland berry pair (or a pinecone) waiting for the tail of the bake. */
    private record Tail(boolean berry, float x, float y, float z, float yaw, int h, int li, float sinT) {
    }

    /** Fills a {@link Baked}: quads through a scratch frame {@link #m}, corners and normals stored transformed. */
    private static final class Baker {
        final Matrix4f m = new Matrix4f();
        private final Vector3f v = new Vector3f();
        private final Baked b;
        private int q;
        private boolean boxesStarted;

        Baker(Curve c, boolean tinted) {
            b = new Baked(c, tinted);
        }

        void boxStart() {
            b.boxStart = q;
            boxesStarted = true;
        }

        void card(float x0, float y0, float x1, float y1, byte uv, int argb, float shadeK, int li, float sinT) {
            quad(x0, y1, 0, x0, y0, 0, x1, y0, 0, x1, y1, 0, 0, 0, 1, uv, argb, shadeK, li, sinT);
        }

        /** Mesh.box's six faces, colours shaded the same way. */
        void box(float x0, float y0, float z0, float x1, float y1, float z1, int rgb, int li, float sinT) {
            int top = Mesh.argb(Mesh.shade(rgb, 1.08f), 255), side = Mesh.argb(rgb, 255), side2 = Mesh.argb(Mesh.shade(rgb, 0.9f), 255),
                    bottom = Mesh.argb(Mesh.shade(rgb, 0.72f), 255);
            quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0, Mesh.UV_WHITE, top, 0, li, sinT);
            quad(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0, -1, 0, Mesh.UV_WHITE, bottom, 0, li, sinT);
            quad(x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, 0, 0, -1, Mesh.UV_WHITE, side2, 0, li, sinT);
            quad(x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, 0, 0, 1, Mesh.UV_WHITE, side, 0, li, sinT);
            quad(x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, -1, 0, 0, Mesh.UV_WHITE, side2, 0, li, sinT);
            quad(x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, 1, 0, 0, Mesh.UV_WHITE, side, 0, li, sinT);
        }

        private void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
                          float nx, float ny, float nz, byte uv, int argb, float shadeK, int li, float sinT) {
            if (q == b.uv.length) grow();
            int k = q * 12;
            corner(k, x0, y0, z0);
            corner(k + 3, x1, y1, z1);
            corner(k + 6, x2, y2, z2);
            corner(k + 9, x3, y3, z3);
            m.transformDirection(nx, ny, nz, v);
            float l = v.length();
            if (l > 1e-6f) v.div(l);
            b.normals[q * 3] = v.x;
            b.normals[q * 3 + 1] = v.y;
            b.normals[q * 3 + 2] = v.z;
            b.uv[q] = uv;
            b.argb[q] = argb;
            b.shadeK[q] = shadeK;
            b.lightIdx[q] = (short) li;
            b.sinT[q] = sinT;
            q++;
        }

        private void corner(int k, float x, float y, float z) {
            m.transformPosition(x, y, z, v);
            b.corners[k] = v.x;
            b.corners[k + 1] = v.y;
            b.corners[k + 2] = v.z;
        }

        private void grow() {
            int n = Math.max(16, b.uv.length * 2);
            b.corners = Arrays.copyOf(b.corners, n * 12);
            b.normals = Arrays.copyOf(b.normals, n * 3);
            b.sinT = Arrays.copyOf(b.sinT, n);
            b.shadeK = Arrays.copyOf(b.shadeK, n);
            b.lightIdx = Arrays.copyOf(b.lightIdx, n);
            b.argb = Arrays.copyOf(b.argb, n);
            b.uv = Arrays.copyOf(b.uv, n);
        }

        Baked done() {
            b.quadCount = q;
            if (!boxesStarted) b.boxStart = q;
            return b;
        }
    }

    private static void pennant(Mesh mesh, Feat f) {
        frame(mesh, f);
        float[] t = switch (f.shape) {
            case TRIANGLE -> Mesh.PENNANT_TRIANGLE;
            case SWALLOWTAIL -> Mesh.PENNANT_SWALLOWTAIL;
            case SQUARE -> Mesh.PENNANT_SQUARE;
        };
        // 7 px wide, 8 px tall flag hanging from the cord; with culling only the card on the camera's side (it hides the other)
        if (mesh.facing(0, 0, 0.004f, 0, 0, 1)) mesh.card(-0.22f, -0.5f, 0.22f, 0.0f, 0.004f, t, Mesh.argb(f.rgb, 255), f.light);
        if (mesh.facing(0, 0, -0.004f, 0, 0, -1)) mesh.card(0.22f, -0.5f, -0.22f, 0.0f, -0.004f, t, Mesh.argb(Mesh.shade(f.rgb, 0.92f), 255), f.light);
    }

    private static void letter(PoseStack pose, SubmitNodeCollector out, Font font, Feat f) {
        int color = f.rgb;
        float lum = ((color >> 16 & 255) * 0.3f + (color >> 8 & 255) * 0.59f + (color & 255) * 0.11f) / 255f;
        int ink = lum > 0.6f ? 0xFF2A2420 : 0xFFFFF8EC;
        for (int side = -1; side <= 1; side += 2) {
            String s = String.valueOf(side > 0 ? f.letter : f.letterBack);
            float w = font.width(s);
            pose.pushPose();
            pose.translate(f.x, f.y, f.z);
            pose.rotate(Axis.YP, -f.yaw);
            pose.rotate(Axis.ZP, f.pitch);
            pose.rotate(Axis.XP, f.roll);
            pose.translate(0, -0.2f, 0.012f * side);
            float sc = 0.026f;
            pose.scale(sc * side, -sc, sc);
            out.submitText(pose, -w / 2 + 0.5f, -4, Component.literal(s).getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET,
                    f.light, ink, 0, 0);
            pose.popPose();
        }
    }

    interface Walker {
        /** A point: running index, position relative to the start, its segment's yaw / pitch, nearest curve point, sway share. */
        void at(int i, float x, float y, float z, float yaw, float pitch, int lightIdx, float sinT);
    }

    /** Points every {@code step} blocks along the unswayed curve (the carry runs on across segments). */
    private static void walk(Curve c, float step, Walker w) {
        float carry = 0;
        int idx = 0;
        float total = Math.max(c.length(), 1e-4f);
        for (int i = 0; i + 1 < c.count(); i++) {
            float dx = c.x(i + 1) - c.x(i), dy = c.y(i + 1) - c.y(i), dz = c.z(i + 1) - c.z(i);
            float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-5f) continue;
            float yaw = (float) Mth.atan2(dz, dx), pitch = (float) Mth.atan2(dy, Mth.sqrt(dx * dx + dz * dz));
            float dist = carry;
            while (dist < len) {
                float t = dist / len;
                float sinT = Mth.sin((c.at(i) + dist) / total * Mth.PI);
                w.at(idx++, c.x(i) + dx * t, c.y(i) + dy * t, c.z(i) + dz * t, yaw, pitch, t < 0.5f ? i : i + 1, sinT);
                dist += step;
            }
            carry = dist - len;
        }
    }
}
