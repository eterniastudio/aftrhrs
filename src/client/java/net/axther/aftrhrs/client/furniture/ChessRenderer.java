package net.axther.aftrhrs.client.furniture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.axther.aftrhrs.feature.chess.ChessBlockEntity;
import net.axther.aftrhrs.feature.chess.Position;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The chess table's live position: every piece as the little block figures the table's own model used to show
 * (quartz for White, polished blackstone for Black), standing on its square of the 16 px board (2 px a square, the
 * board top at y = 13). The block model itself has no pieces (gen_content.py BER_STRIP); the item keeps them.
 *
 * <p>Model frame (the table facing north): file a..h runs from x = 15 down to x = 1, rank 1..8 from z = 1 to z = 15,
 * so White sits on the north side; the whole thing turns with the table.
 */
public final class ChessRenderer implements BlockEntityRenderer<ChessBlockEntity, ChessRenderer.State> {
    private static final SpriteId WHITE = new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/quartz_block_side"));
    private static final SpriteId BLACK = new SpriteId(TextureAtlas.LOCATION_BLOCKS, Identifier.withDefaultNamespace("block/polished_blackstone"));
    /**
     * Boxes per piece type, relative to the square's center on the board top (px): {x0, y0, z0, x1, y1, z1}, with +z
     * pointing towards the opponent (the knight's head leans forward). Same shapes as the table's original model.
     */
    private static final float[][][] SHAPES = {
            {},
            {{-0.5f, 0, -0.5f, 0.5f, 1.5f, 0.5f}}, // pawn
            {{-0.5f, 0, -0.5f, 0.5f, 2f, 0.5f}, {-0.5f, 1.5f, 0.5f, 0.5f, 2.5f, 1f}}, // knight
            {{-0.5f, 0, -0.5f, 0.5f, 2.5f, 0.5f}, {-0.25f, 2.5f, -0.25f, 0.25f, 3f, 0.25f}}, // bishop
            {{-0.5f, 0, -0.5f, 0.5f, 2f, 0.5f}, {-0.75f, 2f, -0.75f, 0.75f, 2.5f, 0.75f}}, // rook
            {{-0.5f, 0, -0.5f, 0.5f, 3f, 0.5f}, {-0.75f, 3f, -0.75f, 0.75f, 3.5f, 0.75f}}, // queen
            {{-0.5f, 0, -0.5f, 0.5f, 3.5f, 0.5f}, {-0.25f, 3.5f, -0.25f, 0.25f, 4.5f, 0.25f}}, // king
    };

    public static final class State extends BlockEntityRenderState {
        final byte[] board = new byte[64];
        float yaw;
    }

    private final SpriteGetter sprites;

    public ChessRenderer(BlockEntityRendererProvider.Context ctx) {
        this.sprites = ctx.sprites();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ChessBlockEntity be, State s, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, s, partialTicks, camera, breakProgress);
        s.yaw = be.yaw();
        Position p = be.game().position();
        for (int i = 0; i < 64; i++) s.board[i] = (byte) p.piece(i);
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        TextureAtlasSprite white = sprites.get(WHITE), black = sprites.get(BLACK);
        int light = s.lightCoords;
        pose.pushPose();
        pose.translate(0.5f, 0f, 0.5f);
        pose.rotateDegrees(Axis.YP, -s.yaw);
        pose.translate(-0.5f, 0f, -0.5f);
        out.submitCustomGeometry(pose, RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS), (p, buf) -> {
            for (int sq = 0; sq < 64; sq++) {
                int piece = s.board[sq];
                if (piece == Position.EMPTY) continue;
                boolean isBlack = Position.isBlack(piece);
                float cx = 15 - 2 * Position.file(sq), cz = 1 + 2 * Position.rank(sq);
                float dir = isBlack ? -1 : 1; // forward is +z for White, -z for Black
                TextureAtlasSprite tex = isBlack ? black : white;
                for (float[] b : SHAPES[Position.type(piece)]) {
                    float z0 = cz + dir * b[2], z1 = cz + dir * b[5];
                    box(p, buf, tex, light, cx + b[0], 13 + b[1], Math.min(z0, z1), cx + b[3], 13 + b[4], Math.max(z0, z1));
                }
            }
        });
        pose.popPose();
    }

    /** A box in model pixels, every face textured with the matching part of the sprite. */
    private static void box(PoseStack.Pose p, VertexConsumer buf, TextureAtlasSprite t, int light,
                            float x0, float y0, float z0, float x1, float y1, float z1) {
        float a = x0 / 16, b = y0 / 16, c = z0 / 16, d = x1 / 16, e = y1 / 16, f = z1 / 16;
        // uv: a patch of the sprite as big as the face (a piece is never more than 4.5 px tall, so it stays inside)
        float w = x1 - x0, h = y1 - y0, l = z1 - z0;
        // up / down
        quad(p, buf, t, light, 0, 1, 0, a, e, c, a, e, f, d, e, f, d, e, c, w, l);
        quad(p, buf, t, light, 0, -1, 0, a, b, f, a, b, c, d, b, c, d, b, f, w, l);
        // north / south
        quad(p, buf, t, light, 0, 0, -1, d, e, c, d, b, c, a, b, c, a, e, c, w, h);
        quad(p, buf, t, light, 0, 0, 1, a, e, f, a, b, f, d, b, f, d, e, f, w, h);
        // west / east
        quad(p, buf, t, light, -1, 0, 0, a, e, c, a, b, c, a, b, f, a, e, f, l, h);
        quad(p, buf, t, light, 1, 0, 0, d, e, f, d, b, f, d, b, c, d, e, c, l, h);
    }

    /** Four corners counter-clockwise seen from outside (top-left, bottom-left, bottom-right, top-right); uv: a uw x vh px patch. */
    private static void quad(PoseStack.Pose p, VertexConsumer buf, TextureAtlasSprite t, int light, float nx, float ny, float nz,
                             float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
                             float x4, float y4, float z4, float uw, float vh) {
        float ua = t.getU(0.25f), ub = t.getU((4 + uw) / 16), va = t.getV(0.25f), vb = t.getV((4 + vh) / 16);
        // the normal once per quad through pose.normal() (never the position matrix), positions inline: no allocation
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
        vertex(pm, buf, x1, y1, z1, ua, va, light, tx, ty, tz);
        vertex(pm, buf, x2, y2, z2, ua, vb, light, tx, ty, tz);
        vertex(pm, buf, x3, y3, z3, ub, vb, light, tx, ty, tz);
        vertex(pm, buf, x4, y4, z4, ub, va, light, tx, ty, tz);
    }

    /** One vertex: position through {@code pm} inline, the normal already in eye space; the 11-argument bulk write. */
    private static void vertex(Matrix4f pm, VertexConsumer buf, float x, float y, float z, float u, float v, int light,
                               float nx, float ny, float nz) {
        buf.addVertex(pm.m00() * x + pm.m10() * y + pm.m20() * z + pm.m30(), pm.m01() * x + pm.m11() * y + pm.m21() * z + pm.m31(),
                pm.m02() * x + pm.m12() * y + pm.m22() * z + pm.m32(), -1, u, v, OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
    }
}
