package net.axther.aftrhrs.client.christmas;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.axther.aftrhrs.client.render.RenderStacks;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.feature.christmas.ChristmasContent;
import net.axther.aftrhrs.feature.christmas.TreeBlockEntity;
import net.axther.aftrhrs.feature.christmas.TreeSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws what hangs on a fir tree ({@link TreeBlockEntity}): ornaments at their {@link TreeSlots} spots, turned to face
 * outwards, the star or angel on the tip and the light strand (an item model at half size, glowing while lit).
 */
public final class TreeRenderer implements BlockEntityRenderer<TreeBlockEntity, TreeRenderer.State> {
    private static final float PX = 1f / 16f;
    /** Ornament item models are ~6-10 px; on the tree they are a few pixels. */
    private static final float ORNAMENT_SCALE = 0.42f, STAR_SCALE = 0.62f, ANGEL_SCALE = 0.56f;

    public static void init() {
        BlockEntityRenderers.register(ChristmasContent.TREE, TreeRenderer::new);
    }

    public static final class State extends BlockEntityRenderState {
        float yaw;
        final List<Matrix4f> poses = new ArrayList<>();
        final List<ItemStackRenderState> items = new ArrayList<>();
    }

    private final ItemModelResolver itemModels;

    public TreeRenderer(BlockEntityRendererProvider.Context ctx) {
        this.itemModels = ctx.itemModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    /** Off screen: the ornaments and the topper reach about 3.5 blocks up and half a block out; culled here instead. */
    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public boolean shouldRender(TreeBlockEntity be, Vec3 cam) {
        if (!BlockEntityRenderer.super.shouldRender(be, cam)) return false;
        BlockPos p = be.getBlockPos();
        return Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum()
                .isVisible(new AABB(p.getX() - 1, p.getY(), p.getZ() - 1, p.getX() + 2, p.getY() + 4, p.getZ() + 2));
    }

    private void add(State s, TreeBlockEntity be, String id, Matrix4f m, int seed) {
        ItemStack stack = RenderStacks.content(be.getLevel(), id);
        if (stack.isEmpty()) return;
        ItemStackRenderState r = new ItemStackRenderState();
        itemModels.updateForTopItem(r, stack, ItemDisplayContext.NONE, be.getLevel(), null, seed);
        s.items.add(r);
        s.poses.add(m);
    }

    @Override
    public void extractRenderState(TreeBlockEntity be, State s, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, s, partialTicks, camera, breakProgress);
        s.items.clear();
        s.poses.clear();
        s.yaw = FurnitureBlock.yawDegrees(be.getBlockState());
        int seed = (int) be.getBlockPos().asLong();
        for (int i = 0; i < TreeSlots.SLOTS.length; i++) {
            String id = be.slot(i);
            if (id.isEmpty()) continue;
            float[] p = TreeSlots.SLOTS[i];
            // the item model's north side turned to the slot's outward direction
            Matrix4f m = new Matrix4f().translate(p[0] * PX, p[1] * PX, p[2] * PX)
                    .rotateY((float) Math.toRadians(-(p[3] + 90f))).scale(ORNAMENT_SCALE);
            add(s, be, id, m, seed + i);
        }
        String top = be.topper();
        if (!top.isEmpty()) {
            boolean angel = top.endsWith("angel");
            float k = angel ? ANGEL_SCALE : STAR_SCALE;
            // star: centred on its middle; angel: its feet (model y 1) on the tip
            float lift = angel ? (8f - 1f) * k : 6.5f * k;
            Matrix4f m = new Matrix4f().translate(TreeSlots.TOP[0] * PX, (TreeSlots.TOP[1] - 1.2f + lift) * PX, TreeSlots.TOP[2] * PX)
                    .scale(k);
            add(s, be, top, m, seed + 99);
        }
        if (be.hasLights()) {
            boolean lit = be.getBlockState().hasProperty(FurnitureBlock.LIT) && be.getBlockState().getValue(FurnitureBlock.LIT);
            Matrix4f m = new Matrix4f().translate(0.5f, 0.5f, 0.5f).scale(2f);
            add(s, be, lit ? ChristmasContent.LIGHTS_LIT : ChristmasContent.LIGHTS_UNLIT, m, seed + 98);
        }
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.5f, 0f, 0.5f);
        pose.rotateDegrees(Axis.YP, -s.yaw);
        pose.translate(-0.5f, 0f, -0.5f);
        for (int i = 0; i < s.items.size(); i++) {
            pose.pushPose();
            pose.mulPose(s.poses.get(i));
            s.items.get(i).submit(pose, out, s.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        pose.popPose();
    }
}
