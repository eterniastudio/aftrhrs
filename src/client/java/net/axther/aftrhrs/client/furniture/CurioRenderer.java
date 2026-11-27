package net.axther.aftrhrs.client.furniture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.axther.aftrhrs.client.render.RenderStacks;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.feature.furniture.curios.CurioBlockEntity;
import net.axther.aftrhrs.feature.furniture.curios.CuriosConfig;
import net.axther.aftrhrs.feature.furniture.curios.CuriosModule;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws curios (what Paper showed with display entities, CurioDisplays): the pedestal item, the hat on a stand, the
 * statue's arm and held item, flower-box plants and doormat text. Same math as Paper: a model-space transform
 * {@code E} becomes the display transformation {@code F E F^-1} (F = the item display's 180 degree turn), drawn at
 * the block center turned by the piece's yaw.
 */
public final class CurioRenderer implements BlockEntityRenderer<CurioBlockEntity, CurioRenderer.State> {
    private static final float DEG = (float) (Math.PI / 180.0);
    private static CuriosConfig cfg;

    public static final class State extends BlockEntityRenderState {
        float yaw;
        final List<Matrix4f> itemPoses = new ArrayList<>();
        final List<ItemStackRenderState> items = new ArrayList<>();
        final List<Matrix4f> blockPoses = new ArrayList<>();
        final List<BlockModelRenderState> blocks = new ArrayList<>();
        @Nullable Matrix4f textPose;
        List<FormattedCharSequence> lines = List.of();
        int textWidth;
        int textColor;
    }

    /** A doormat's text wrapped for {@code font} (cached on the block entity; rebuilt when the text or the font changes). */
    private record Wrapped(List<String> src, Font font, List<FormattedCharSequence> lines, int width) {
    }

    private final ItemModelResolver itemModels;
    private final BlockModelResolver blockModels;
    private final Font font;

    public CurioRenderer(BlockEntityRendererProvider.Context ctx) {
        this.itemModels = ctx.itemModelResolver();
        this.blockModels = ctx.blockModelResolver();
        this.font = ctx.font();
    }

    static CuriosConfig cfg() {
        if (cfg == null) {
            CuriosConfig c = new CuriosConfig();
            c.load(Feather.bundled("curios.yml"), w -> { });
            cfg = c;
        }
        return cfg;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    // ------------------------------------------------------------------ math (CurioDisplays)

    private static Vector3f v(Vector3f px) {
        return new Vector3f((px.x - 8) / 16f, (px.y - 8) / 16f, (px.z - 8) / 16f);
    }

    /** Model space (centered, in blocks) to the piece's local frame. */
    private static Matrix4f frame() {
        Matrix4f m = new Matrix4f();
        if (cfg().flip) m.rotateY((float) Math.PI);
        return m;
    }

    /** frame * E * F^-1: the display transformation of an item drawn through E. */
    private static Matrix4f itemPart(Matrix4f e) {
        Matrix4f m = frame().mul(e);
        if (cfg().flip) m.rotateY((float) -Math.PI);
        return m;
    }

    private void addItem(State s, ItemStack stack, ItemDisplayContext ctx, Matrix4f m, CurioBlockEntity be, int seed) {
        if (stack == null || stack.isEmpty()) return;
        ItemStackRenderState r = new ItemStackRenderState();
        itemModels.updateForTopItem(r, stack, ctx, be.getLevel(), null, seed);
        s.items.add(r);
        s.itemPoses.add(m);
    }

    // ------------------------------------------------------------------ extract

    @Override
    public void extractRenderState(CurioBlockEntity be, State s, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, s, partialTicks, camera, breakProgress);
        s.items.clear();
        s.itemPoses.clear();
        s.blocks.clear();
        s.blockPoses.clear();
        s.textPose = null;
        s.yaw = be.yaw();
        CuriosConfig k = cfg();
        String id = be.furnitureId();
        CuriosConfig.Kind kind = k.kindOf(id);
        if (kind == null || be.getLevel() == null) return;
        float time = be.getLevel().getGameTime() + partialTicks;
        int seed = (int) be.getBlockPos().asLong();
        switch (kind) {
            case PEDESTAL -> {
                ItemStack stack = be.item();
                if (stack.isEmpty() || coveredAbove(be, k, id)) return;
                CuriosConfig.Pose p = k.poseOf(stack);
                float spin = p.spinSeconds() > 0 ? (time / (p.spinSeconds() * 20f)) * 360f % 360f : 0f;
                Matrix4f m = itemPart(new Matrix4f().translate(v(k.pedestalPoint).add(0, p.lift(), 0)));
                m.rotateZ(p.tilt() * DEG).rotateY(spin * DEG).rotateZ(p.rotateZ() * DEG).scale(p.scale());
                addItem(s, stack, p.display(), m, be, seed);
            }
            case HAT_STAND -> {
                ItemStack stack = be.item();
                if (stack.isEmpty()) return;
                float deg = wobbleAngle(be, k, time);
                Vector3f foot = v(new Vector3f(8, 0, 8));
                Matrix4f e = new Matrix4f().translate(foot).rotateX(deg * DEG).translate(-foot.x, -foot.y, -foot.z)
                        .translate(v(k.headPoint)).translate(0, 0.25f * k.headScale / 0.625f, 0)
                        .rotateY(k.headYaw * DEG).scale(k.headScale);
                addItem(s, stack, ItemDisplayContext.HEAD, itemPart(e), be, seed);
            }
            case STATUE -> {
                ItemStack held = be.item();
                float raise, swing = 0;
                if (be.waving()) {
                    raise = k.waveRaise;
                    float steps = time / k.waveTicks;
                    int i = (int) Math.floor(steps);
                    float from = (i & 1) == 0 ? -k.waveSwing : k.waveSwing;
                    swing = Mth.lerp(steps - i, from, -from);
                } else {
                    raise = k.raiseFor(held);
                }
                ItemStack arm = RenderStacks.content(be.getLevel(), k.armItem);
                Matrix4f armModel = new Matrix4f().translate(v(k.shoulder)).rotateZ(swing * DEG).rotateX(raise * DEG);
                addItem(s, arm, ItemDisplayContext.NONE, itemPart(armModel), be, seed);
                if (!held.isEmpty()) {
                    Vector3f off = new Vector3f(k.handOffset).div(16f);
                    Matrix4f e = new Matrix4f(armModel).translate(off)
                            .rotateZ((float) Math.PI)
                            .rotateX(-90 * DEG).rotateY(180 * DEG)
                            .translate(1 / 16f, 2 / 16f, -10 / 16f);
                    addItem(s, held, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, itemPart(e), be, seed + 1);
                }
            }
            case FLOWER_BOX -> {
                float sc = k.plantScale;
                for (int i = 0; i < k.slots.size(); i++) {
                    Item plant = be.plant(i);
                    if (plant == null) continue;
                    BlockState lower = CuriosModule.plantState(plant);
                    if (lower == null) continue;
                    Matrix4f base = frame().translate(v(k.slots.get(i))).scale(sc);
                    boolean dbl = lower.getBlock() instanceof DoublePlantBlock && lower.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
                    if (dbl) lower = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                    addBlock(s, lower, new Matrix4f(base).translate(-0.5f, 0, -0.5f));
                    if (dbl) {
                        addBlock(s, lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                                new Matrix4f(base).translate(-0.5f, 1, -0.5f));
                    }
                }
            }
            case DOORMAT -> {
                List<String> lines = be.text();
                if (lines.isEmpty()) return;
                Wrapped w = be.textRenderCache instanceof Wrapped c && c.font == font && c.src.equals(lines) ? c : null;
                if (w == null) {
                    List<FormattedCharSequence> out = new ArrayList<>();
                    int width = 0;
                    for (String line : lines) {
                        for (FormattedCharSequence seq : font.split(Component.literal(line), 200)) {
                            out.add(seq);
                            width = Math.max(width, font.width(seq));
                        }
                    }
                    w = new Wrapped(List.copyOf(lines), font, List.copyOf(out), width);
                    be.textRenderCache = w;
                }
                s.lines = w.lines;
                s.textWidth = w.width;
                s.textColor = k.textArgb;
                float h = lines.size() * 0.25f;
                Matrix4f m = frame().translate(v(new Vector3f(8, k.textHeight, 8)));
                if (k.flip) m.rotateY((float) -Math.PI);
                m.rotateX(-90 * DEG).scale(k.textScale).translate(0, -h / 2f, 0);
                s.textPose = m;
            }
        }
    }

    private void addBlock(State s, BlockState state, Matrix4f m) {
        BlockModelRenderState r = new BlockModelRenderState();
        blockModels.update(r, state, DisplayRenderer.BLOCK_DISPLAY_CONTEXT);
        s.blocks.add(r);
        s.blockPoses.add(m);
    }

    /** The damped wobble (degrees) a hat stand shows right now: Paper's per-swing steps, interpolated. */
    private static float wobbleAngle(CurioBlockEntity be, CuriosConfig k, float time) {
        float e = time - be.wobbleStart;
        if (e < 0 || k.wobble.isEmpty()) return 0;
        float steps = e / k.wobbleTicks;
        int i = (int) Math.floor(steps);
        if (i >= k.wobble.size()) return 0;
        float from = i == 0 ? 0 : k.wobble.get(i - 1);
        return Mth.lerp(steps - i, from, k.wobble.get(i));
    }

    private static boolean coveredAbove(CurioBlockEntity be, CuriosConfig k, String id) {
        if (!k.stackEnabled || be.getLevel() == null) return false;
        String above = ArcanaContent.id(be.getLevel().getBlockState(be.getBlockPos().above()));
        return above != null && k.kindOf(above) == CuriosConfig.Kind.PEDESTAL
                && k.pedestalVariant(above).equals(k.pedestalVariant(id));
    }

    // ------------------------------------------------------------------ submit

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        pose.rotateDegrees(Axis.YP, -s.yaw);
        for (int i = 0; i < s.items.size(); i++) {
            pose.pushPose();
            pose.mulPose(s.itemPoses.get(i));
            pose.rotate(Axis.YP, (float) Math.PI); // the item display's own turn
            s.items.get(i).submit(pose, out, s.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        for (int i = 0; i < s.blocks.size(); i++) {
            pose.pushPose();
            pose.mulPose(s.blockPoses.get(i));
            s.blocks.get(i).submit(pose, out, s.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        if (s.textPose != null && !s.lines.isEmpty()) {
            pose.pushPose();
            pose.mulPose(s.textPose);
            Matrix4f p = pose.last().pose();
            p.rotate((float) Math.PI, 0f, 1f, 0f);
            p.scale(-0.025f, -0.025f, -0.025f);
            int lineHeight = 10;
            int height = s.lines.size() * lineHeight - 1;
            p.translate(1f - s.textWidth / 2f, -height, 0f);
            float y = 0;
            for (FormattedCharSequence line : s.lines) {
                float x = s.textWidth / 2f - font.width(line) / 2f;
                out.submitText(pose, x, y, line, false, Font.DisplayMode.POLYGON_OFFSET, s.lightCoords, s.textColor, 0, 0);
                y += lineHeight;
            }
            pose.popPose();
        }
        pose.popPose();
    }
}
