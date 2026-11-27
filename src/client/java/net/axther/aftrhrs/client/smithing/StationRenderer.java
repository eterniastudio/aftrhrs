package net.axther.aftrhrs.client.smithing;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.axther.aftrhrs.client.render.RenderStacks;
import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.Strike;
import net.axther.aftrhrs.feature.smithing.station.AnvilBE;
import net.axther.aftrhrs.feature.smithing.station.AssemblyBE;
import net.axther.aftrhrs.feature.smithing.station.CarvingBE;
import net.axther.aftrhrs.feature.smithing.station.CastingBE;
import net.axther.aftrhrs.feature.smithing.station.CrucibleBE;
import net.axther.aftrhrs.feature.smithing.station.ForgeBE;
import net.axther.aftrhrs.feature.smithing.station.GreatCrucibleBE;
import net.axther.aftrhrs.feature.smithing.station.MeltBE;
import net.axther.aftrhrs.feature.smithing.station.RuneTableBE;
import net.axther.aftrhrs.feature.smithing.station.SpoutBE;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.axther.aftrhrs.feature.smithing.station.StationBE;
import net.axther.aftrhrs.feature.smithing.station.TroughBE;
import net.axther.aftrhrs.feature.smithing.station.WheelBE;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws what's on every smithing station (SMITHING.md 3): pieces lying on the coals, the anvil, benches and table;
 * hot pieces glowing by their temperature; molten metal, water and oil surfaces; the forge's heat needle; the anvil's
 * strike meter; the spout's stream; the whetstone wheel turning. Everything is laid out in the station's north-facing
 * model pixels (the block-model contract) and turned with the block.
 */
public final class StationRenderer implements BlockEntityRenderer<StationBE, StationRenderer.State> {
    private static final float PX = 1 / 16f;
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final SpriteId WHITE = new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/white_concrete"));
    private static final SpriteId LAVA = new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/lava_still"));
    private static final SpriteId WATER = new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/water_still"));
    private static final Identifier WHEEL_MODEL = Smithing.id("smithing/whetstone_wheel_spin");

    /** A box (pixels) drawn with a sprite tinted {@code argb}. */
    record Box(float x0, float y0, float z0, float x1, float y1, float z1, int argb, int sprite, boolean bright, boolean topOnly) {
    }

    public static final class State extends BlockEntityRenderState {
        float yaw;
        final List<Matrix4f> poses = new ArrayList<>();
        final List<ItemStackRenderState> items = new ArrayList<>();
        final List<Box> boxes = new ArrayList<>();
        final List<Box> glows = new ArrayList<>();
        // the strike meter (anvil)
        boolean meter;
        float marker, center, half, perfect;
        int flash;
        Vec3 meterAt = Vec3.ZERO;
    }

    private final ItemModelResolver itemModels;
    private final SpriteGetter sprites;
    /** Drawn outside its block (the great crucible's pool, the spout's stream): off-screen rendering with its own cull. */
    private final boolean global;

    public StationRenderer(BlockEntityRendererProvider.Context ctx, Station station) {
        this.itemModels = ctx.itemModelResolver();
        this.sprites = ctx.sprites();
        this.global = station == Station.GREAT_DRAIN || station == Station.SPOUT;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    private static float yaw(Direction d) {
        return switch (d) {
            case EAST -> -90;
            case SOUTH -> 180;
            case WEST -> 90;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ building the state

    /** An item lying flat on a surface at model pixels (x, y, z), {@code size} blocks across, turned {@code spin} degrees. */
    private void flat(State s, StationBE be, ItemStack stack, float x, float y, float z, float size, float spin, int seed) {
        if (stack == null || stack.isEmpty()) return;
        ItemStackRenderState r = new ItemStackRenderState();
        itemModels.updateForTopItem(r, stack, ItemDisplayContext.FIXED, be.getLevel(), null, seed);
        Matrix4f m = new Matrix4f().translate(x * PX, y * PX + 0.02f, z * PX).rotateY((float) Math.toRadians(spin))
                .rotateX((float) Math.toRadians(-90)).scale(size * 2f);
        s.items.add(r);
        s.poses.add(m);
    }

    private void standing(State s, StationBE be, ItemStack stack, float x, float y, float z, float size, float spin, int seed) {
        if (stack == null || stack.isEmpty()) return;
        ItemStackRenderState r = new ItemStackRenderState();
        itemModels.updateForTopItem(r, stack, ItemDisplayContext.FIXED, be.getLevel(), null, seed);
        s.items.add(r);
        s.poses.add(new Matrix4f().translate(x * PX, y * PX, z * PX).rotateY((float) Math.toRadians(spin)).scale(size * 2f));
    }

    /** A glow over a hot piece lying at (x, y, z) pixels, {@code r} pixels across. */
    private static void glow(State s, float temp, float x, float y, float z, float r) {
        int c = Heat.glow(temp);
        if (c == 0) return;
        s.glows.add(new Box(x - r, y + 0.15f, z - r, x + r, y + 0.15f, z + r, c, 0, true, true));
    }

    private static int argb(int rgb, int alpha) {
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    @Override
    public void extractRenderState(StationBE be, State s, float partialTicks, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, s, partialTicks, camera, breakProgress);
        s.items.clear();
        s.poses.clear();
        s.boxes.clear();
        s.glows.clear();
        s.meter = false;
        s.yaw = yaw(be.facing());
        if (be.getLevel() == null) return;
        long now = be.getLevel().getGameTime();
        float time = now + partialTicks;
        int seed = (int) be.getBlockPos().asLong();
        switch (be) {
            case ForgeBE f -> {
                for (int i = 0; i < ForgeBE.SLOTS; i++) {
                    ItemStack w = f.work(i);
                    if (w.isEmpty()) continue;
                    float[] p = ForgeBE.SPOTS[i];
                    flat(s, be, w, p[0], p[1], p[2], 0.22f, 15 * (i - 1), seed + i);
                    glow(s, Heat.of(w, now), p[0], p[1], p[2], 1.8f);
                }
                // the heat needle on the dial (centered 8, 13 on the front face): cold left, white heat right
                float frac = Math.min(1f, f.heat() / 1600f);
                double a = Math.PI * (1 - frac);
                float nx = 8 + (float) Math.cos(a) * 2.1f, ny = 13 + (float) Math.sin(a) * 2.1f;
                for (int k = 0; k <= 6; k++) {
                    float t = k / 6f;
                    float px = 8 + (nx - 8) * t, py = 13 + (ny - 13) * t;
                    s.boxes.add(new Box(px - 0.25f, py - 0.25f, -0.12f, px + 0.25f, py + 0.25f, -0.02f, 0xFF1A1414, 0, false, false));
                }
            }
            case GreatCrucibleBE g -> {
                if (!g.formed() || g.total() <= 0) return;
                float level = 32f * g.total() / Math.max(1, g.capacity());
                String fl = g.topFluid();
                s.boxes.add(new Box(0.01f, 0, 16.01f, 15.99f, level, 31.99f, argb(Smelt.color(fl), 255), 1, true, true));
            }
            case MeltBE m -> {
                // the crucible: molten surface between y 2.5 and 11.5, solids sinking in it
                int total = m.total();
                float level = 2.5f + 9f * total / Math.max(1, m.capacity());
                if (total > 0) {
                    String fl = m.topFluid();
                    boolean oil = Smelt.FISH_OIL.equals(fl);
                    s.boxes.add(new Box(4, 0, 4, 12, level, 12, argb(Smelt.color(fl), oil ? 230 : 255), oil ? 2 : 1, !oil, true));
                }
                int i = 0;
                for (ItemStack solid : m.solids()) {
                    if (i >= 4) break;
                    float ang = time * 2 + i * 90;
                    float x = 8 + (float) Math.cos(Math.toRadians(ang)) * 1.6f, z = 8 + (float) Math.sin(Math.toRadians(ang)) * 1.6f;
                    flat(s, be, solid, x, Math.max(3f, level - 0.8f), z, 0.14f, ang, seed + i);
                    i++;
                }
            }
            case CastingBE c -> {
                ItemStack mold = c.mold();
                if (mold.is(Smithing.MOLDING_BOX)) {
                    flat(s, be, mold, 8, 8, 8, 0.4f, 0, seed);
                    if (c.sand() > 0) s.boxes.add(new Box(4, 8, 4, 12, 8 + 0.5f * c.sand(), 12, 0xFFDBCFA3, 0, false, false));
                } else if (!mold.isEmpty()) {
                    flat(s, be, mold, 8, 8, 8, 0.4f, 0, seed);
                }
                for (int k = 0; k < c.clay(); k++) flat(s, be, RenderStacks.clay(be.getLevel()), 5 + (k % 2) * 6, 8, 5 + (k / 2) * 6, 0.18f, k * 40, seed + k);
                if (c.units() > 0 && c.needed() > 0) {
                    float fill = Math.min(1f, c.units() / (float) c.needed());
                    int col, molten = Smelt.color(c.fluid());
                    if (c.cool() > 0) col = Heat.glow(450 + 900f * c.cool() / CastingBE.COOL_TICKS);
                    else col = argb(darken(molten), 255);
                    if (col == 0) col = argb(molten, 255);
                    float r = 2 + 3 * fill;
                    s.boxes.add(new Box(8 - r, 8.1f, 8 - r, 8 + r, 8.7f, 8 + r, col, c.cool() > 0 ? 1 : 0, c.cool() > 0, true));
                }
            }
            case AnvilBE a -> {
                ItemStack piece = a.piece();
                if (piece.isEmpty()) return;
                float temp = Heat.of(piece, now);
                List<String> plan = a.plan();
                ItemStack shown = piece;
                // forging from ingots: the bar becomes a blank, then the part's shape
                if (!(piece.getItem() instanceof PartItem) && !plan.isEmpty() && a.move() * 2 >= plan.size() && !a.target().equals(AnvilBE.STEEL)) {
                    String metal = Smelt.ingotMetal(piece);
                    ItemStack part = Smithing.part(a.target(), metal == null ? "iron" : metal, 1);
                    if (!part.isEmpty()) shown = part;
                }
                int n = shown == piece ? Math.min(4, piece.getCount()) : 1;
                for (int k = 0; k < n; k++) flat(s, be, shown, 7 + k * 0.6f, 12 + k * 0.4f, 8, 0.24f, 90, seed + k);
                glow(s, temp, 7, 12, 8, 2.2f);
                if (!plan.isEmpty()) {
                    String metal = a.metal();
                    SmithingDefs.Material mat = SmithingDefs.material(metal);
                    int[] w = mat == null ? null : mat.window();
                    s.meter = true;
                    s.marker = Strike.marker(now, a.meterStart()) + (partialTicks / Strike.PERIOD) * dir(now, a.meterStart());
                    s.center = Strike.center(be.getBlockPos(), a.move(), a.seed());
                    float frac = w == null ? 0 : (temp - w[0]) / Math.max(1f, w[1] - w[0]);
                    s.half = w != null && temp < w[0] ? 0 : Strike.halfWidth(mat == null ? 2 : mat.tier(), frac, false, 0);
                    s.perfect = s.half * Strike.PERFECT;
                    s.flash = now - a.lastStrike() < 6 ? a.lastResult() : -1;
                    s.meterAt = new Vec3(8 * PX, 21 * PX, 8 * PX);
                }
            }
            case TroughBE t -> {
                if (t.fill() <= 0) return;
                float level = 1.5f + 5.5f * t.fill() / TroughBE.MAX;
                int col = switch (t.liquid()) {
                    case "fish_oil" -> 0xE0C8902A;
                    case "brine" -> 0xC87AB8C8;
                    case "frost_brine" -> 0xC8BFE6FF;
                    default -> 0xC03F76E4;
                };
                s.boxes.add(new Box(2, 0, 4, 14, level, 12, col, 2, false, true));
            }
            case CarvingBE c -> {
                ItemStack stock = c.stock();
                int n = Math.min(4, stock.getCount());
                for (int k = 0; k < n; k++) flat(s, be, stock, 6 + k * 1.2f, 12 + k * 0.3f, 8, 0.2f, 20 * k, seed + k);
                if (!c.target().isEmpty() && c.strikes() > 0) {
                    SmithingDefs.Material m = CarvingBE.materialOf(stock);
                    if (m != null) flat(s, be, Smithing.part(c.target(), m.id(), 3), 11.5f, 12.4f, 8, 0.16f, 0, seed + 9);
                }
            }
            case AssemblyBE a -> {
                if (!a.tool().isEmpty()) {
                    flat(s, be, a.tool(), 8, 15, 8, 0.42f, 45, seed);
                } else {
                    List<ItemStack> parts = a.parts();
                    for (int k = 0; k < parts.size(); k++) flat(s, be, parts.get(k), 4 + k * 2.8f, 15 + k * 0.05f, 8 - k * 0.8f, 0.26f, 45, seed + k);
                }
            }
            case RuneTableBE r -> {
                flat(s, be, r.tool(), 8, 14, 8, 0.34f, 45, seed);
                for (int k = 0; k < RuneTableBE.CORNERS; k++) {
                    float[] p = RuneTableBE.SPOTS[k];
                    flat(s, be, r.corner(k), p[0], p[1], p[2], 0.16f, k * 30, seed + k);
                }
            }
            case WheelBE w -> {
                ItemStack wheel = RenderStacks.wheel(be.getLevel(), WHEEL_MODEL);
                ItemStackRenderState r = new ItemStackRenderState();
                itemModels.updateForTopItem(r, wheel, ItemDisplayContext.NONE, be.getLevel(), null, seed);
                float spin = now - w.lastUse() < 20 ? time * 0.35f : 0;
                s.items.add(r);
                s.poses.add(new Matrix4f().translate(0.5f, 0.5f, 0.5f).rotateX(spin));
            }
            case SpoutBE sp -> {
                if (!sp.pouring() || sp.fluid().isEmpty()) return;
                s.boxes.add(new Box(7.4f, -6f, 5.4f, 8.6f, 5f, 6.6f, argb(Smelt.color(sp.fluid()), 255), 1, true, false));
            }
            default -> {
            }
        }
    }

    private static float dir(long now, long start) {
        long t = Math.floorMod(now - start, 2L * Strike.PERIOD);
        return t < Strike.PERIOD ? 1 : -1;
    }

    private static int darken(int rgb) {
        int r = (rgb >> 16 & 255) * 3 / 4, g = (rgb >> 8 & 255) * 3 / 4, b = (rgb & 255) * 3 / 4;
        return r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.5f, 0f, 0.5f);
        pose.rotateDegrees(Axis.YP, s.yaw);
        pose.translate(-0.5f, 0f, -0.5f);
        for (int i = 0; i < s.items.size(); i++) {
            pose.pushPose();
            pose.mulPose(s.poses.get(i));
            s.items.get(i).submit(pose, out, s.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        if (!s.boxes.isEmpty()) {
            TextureAtlasSprite[] tex = {sprites.get(WHITE), sprites.get(LAVA), sprites.get(WATER)};
            out.submitCustomGeometry(pose, RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS), (p, buf) -> {
                for (Box b : s.boxes) box(p, buf, tex[b.sprite()], b, b.bright() ? FULL_BRIGHT : s.lightCoords);
            });
        }
        if (!s.glows.isEmpty()) {
            out.submitCustomGeometry(pose, RenderTypes.lightning(), (p, buf) -> {
                for (Box b : s.glows) glowQuad(p, buf, b);
            });
        }
        if (s.meter) meter(s, pose, out, camera);
        pose.popPose();
    }

    private static void glowQuad(PoseStack.Pose p, VertexConsumer buf, Box b) {
        int c = b.argb();
        float a = (c >>> 24) / 255f, r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, bl = (c & 255) / 255f;
        float x0 = b.x0() * PX, x1 = b.x1() * PX, z0 = b.z0() * PX, z1 = b.z1() * PX, y = b.y0() * PX;
        float cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        Matrix4f pm = p.pose();
        // a soft square: bright middle, fading edges (two rings)
        glowVertex(pm, buf, x0, y, z0).setColor(r, g, bl, 0f);
        glowVertex(pm, buf, x0, y, z1).setColor(r, g, bl, 0f);
        glowVertex(pm, buf, cx, y, cz).setColor(r, g, bl, a);
        glowVertex(pm, buf, cx, y, cz).setColor(r, g, bl, a);
        glowVertex(pm, buf, x0, y, z1).setColor(r, g, bl, 0f);
        glowVertex(pm, buf, x1, y, z1).setColor(r, g, bl, 0f);
        glowVertex(pm, buf, cx, y, cz).setColor(r, g, bl, a);
        glowVertex(pm, buf, cx, y, cz).setColor(r, g, bl, a);
        glowVertex(pm, buf, x1, y, z1).setColor(r, g, bl, 0f);
        glowVertex(pm, buf, x1, y, z0).setColor(r, g, bl, 0f);
        glowVertex(pm, buf, cx, y, cz).setColor(r, g, bl, a);
        glowVertex(pm, buf, cx, y, cz).setColor(r, g, bl, a);
        glowVertex(pm, buf, x1, y, z0).setColor(r, g, bl, 0f);
        glowVertex(pm, buf, x0, y, z0).setColor(r, g, bl, 0f);
        glowVertex(pm, buf, cx, y, cz).setColor(r, g, bl, a);
        glowVertex(pm, buf, cx, y, cz).setColor(r, g, bl, a);
    }

    /** A position-colour vertex with the position through {@code pm} inline (the Pose overload allocates). */
    private static VertexConsumer glowVertex(Matrix4f pm, VertexConsumer buf, float x, float y, float z) {
        return buf.addVertex(pm.m00() * x + pm.m10() * y + pm.m20() * z + pm.m30(), pm.m01() * x + pm.m11() * y + pm.m21() * z + pm.m31(),
                pm.m02() * x + pm.m12() * y + pm.m22() * z + pm.m32());
    }

    private static void box(PoseStack.Pose p, VertexConsumer buf, TextureAtlasSprite t, Box b, int light) {
        float x0 = b.x0() * PX, y0 = b.y0() * PX, z0 = b.z0() * PX, x1 = b.x1() * PX, y1 = b.y1() * PX, z1 = b.z1() * PX;
        int c = b.argb();
        float u0 = t.getU(0), u1 = t.getU(Math.min(1f, (b.x1() - b.x0()) / 16f)), v0 = t.getV(0), v1 = t.getV(Math.min(1f, (b.z1() - b.z0()) / 16f));
        // top
        quad(p, buf, c, light, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, u0, v0, u1, v1);
        if (b.topOnly()) return;
        float vh = t.getV(Math.min(1f, (b.y1() - b.y0()) / 16f));
        quad(p, buf, c, light, 0, -1, 0, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, u0, v0, u1, v1);
        quad(p, buf, c, light, 0, 0, -1, x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, u0, v0, u1, vh);
        quad(p, buf, c, light, 0, 0, 1, x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, u0, v0, u1, vh);
        quad(p, buf, c, light, -1, 0, 0, x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, u0, v0, u1, vh);
        quad(p, buf, c, light, 1, 0, 0, x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, u0, v0, u1, vh);
    }

    /** Normal transformed once per quad (pose.normal(), never the position matrix), positions inline: no allocation. */
    private static void quad(PoseStack.Pose p, VertexConsumer buf, int argb, int light, float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1) {
        Matrix3f nm = p.normal();
        float tx = nm.m00() * nx + nm.m10() * ny + nm.m20() * nz;
        float ty = nm.m01() * nx + nm.m11() * ny + nm.m21() * nz;
        float tz = nm.m02() * nx + nm.m12() * ny + nm.m22() * nz;
        float l = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (l > 1e-6f) {
            tx /= l;
            ty /= l;
            tz /= l;
        }
        Matrix4f pm = p.pose();
        vertex(pm, buf, ax, ay, az, u0, v0, argb, light, tx, ty, tz);
        vertex(pm, buf, bx, by, bz, u0, v1, argb, light, tx, ty, tz);
        vertex(pm, buf, cx, cy, cz, u1, v1, argb, light, tx, ty, tz);
        vertex(pm, buf, dx, dy, dz, u1, v0, argb, light, tx, ty, tz);
    }

    /** One vertex: position through {@code pm} inline, the normal already in eye space; the 11-argument bulk write. */
    private static void vertex(Matrix4f pm, VertexConsumer buf, float x, float y, float z, float u, float v, int argb, int light,
                               float nx, float ny, float nz) {
        buf.addVertex(pm.m00() * x + pm.m10() * y + pm.m20() * z + pm.m30(), pm.m01() * x + pm.m11() * y + pm.m21() * z + pm.m31(),
                pm.m02() * x + pm.m12() * y + pm.m22() * z + pm.m32(), argb, u, v, OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
    }

    /** The strike meter: a bar facing the camera, the sweet spot (gold: perfect), a white marker sweeping across. */
    private void meter(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        pose.pushPose();
        pose.translate((float) s.meterAt.x, (float) s.meterAt.y, (float) s.meterAt.z);
        // undo the block's turn, then face the camera
        pose.rotateDegrees(Axis.YP, -s.yaw);
        pose.rotate(camera.orientation);
        TextureAtlasSprite white = sprites.get(WHITE);
        float w = 0.9f, h = 0.07f;
        int track = s.flash == 0 ? 0xD07A1C1C : 0xB0201A1A;
        out.submitCustomGeometry(pose, RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS), (p, buf) -> {
            flatRect(p, buf, white, -w / 2 - 0.02f, -h / 2 - 0.02f, w / 2 + 0.02f, h / 2 + 0.02f, 0.002f, 0xE0100C0C);
            flatRect(p, buf, white, -w / 2, -h / 2, w / 2, h / 2, 0.001f, track);
            if (s.half > 0) {
                float c = -w / 2 + s.center * w;
                int good = s.flash == 1 ? 0xFFB8F5A0 : 0xE058C04A;
                int perf = s.flash == 2 ? 0xFFFFFFFF : 0xF0F5C542;
                flatRect(p, buf, white, c - s.half * w, -h / 2, c + s.half * w, h / 2, 0f, good);
                flatRect(p, buf, white, c - s.perfect * w, -h / 2, c + s.perfect * w, h / 2, -0.001f, perf);
            }
            float m = -w / 2 + Math.max(0, Math.min(1, s.marker)) * w;
            flatRect(p, buf, white, m - 0.012f, -h / 2 - 0.03f, m + 0.012f, h / 2 + 0.03f, -0.002f, 0xFFFFFFFF);
        });
        pose.popPose();
    }

    private static void flatRect(PoseStack.Pose p, VertexConsumer buf, TextureAtlasSprite t, float x0, float y0, float x1, float y1, float z, int argb) {
        float u0 = t.getU(0), u1 = t.getU(1), v0 = t.getV(0), v1 = t.getV(1);
        quad(p, buf, argb, FULL_BRIGHT, 0, 0, 1, x0, y1, z, x0, y0, z, x1, y0, z, x1, y1, z, u0, v0, u1, v1);
    }

    /** Only the stations that draw outside their block skip section culling; the rest are culled with their section. */
    @Override
    public boolean shouldRenderOffScreen() {
        return global;
    }

    @Override
    public boolean shouldRender(StationBE be, Vec3 cam) {
        if (!BlockEntityRenderer.super.shouldRender(be, cam)) return false;   // 64 blocks
        if (!global) return true;   // its section was visible
        BlockPos p = be.getBlockPos();
        AABB box;
        if (be instanceof SpoutBE sp) {
            if (!sp.pouring() || sp.fluid().isEmpty()) return false;   // nothing to draw
            // the stream runs down into the block below
            box = new AABB(p.getX(), p.getY() - 1, p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1);
        } else {
            if (be instanceof GreatCrucibleBE g && (!g.formed() || g.total() <= 0)) return false;
            // the pool sits one block behind the drain (whichever way it faces), up to 2 blocks deep
            box = new AABB(p.getX() - 1, p.getY(), p.getZ() - 1, p.getX() + 2, p.getY() + 2, p.getZ() + 2);
        }
        return Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum().isVisible(box);
    }

    @SuppressWarnings("unused")
    private static boolean crucible(Object be) {
        return be instanceof CrucibleBE;
    }
}
