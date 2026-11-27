package net.axther.aftrhrs.client.furniture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.axther.aftrhrs.client.render.RenderStacks;
import net.axther.aftrhrs.feature.furniture.gadgets.GadgetBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws a gadget's liquid / cookie overlay (an item model in the piece's model space, like Paper's overlay
 * ItemDisplay) and the caged mob (a client-side copy, frozen, standing at model point 8,1,8 facing out).
 */
public final class GadgetRenderer implements BlockEntityRenderer<GadgetBlockEntity, GadgetRenderer.State> {
    public static final class State extends BlockEntityRenderState {
        float yaw;
        final ItemStackRenderState overlay = new ItemStackRenderState();
        boolean hasOverlay;
        @Nullable EntityRenderState mob;
    }

    private final ItemModelResolver itemModels;
    private final EntityRenderDispatcher entities;

    public GadgetRenderer(BlockEntityRendererProvider.Context ctx) {
        this.itemModels = ctx.itemModelResolver();
        this.entities = ctx.entityRenderer();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(GadgetBlockEntity be, State s, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, s, partialTicks, camera, breakProgress);
        s.yaw = be.yaw();
        String overlay = be.overlay();
        ItemStack item = overlay == null ? ItemStack.EMPTY : RenderStacks.content(be.getLevel(), overlay);
        s.hasOverlay = !item.isEmpty();
        if (s.hasOverlay) itemModels.updateForTopItem(s.overlay, item, ItemDisplayContext.NONE, be.getLevel(), null, 0);
        else s.overlay.clear();
        s.mob = null;
        Entity mob = be.clientMob();
        if (mob != null) {
            float y = s.yaw + 180f;
            mob.setYRot(y);
            mob.yRotO = y;
            mob.setYHeadRot(y);
            if (mob instanceof net.minecraft.world.entity.LivingEntity le) {
                le.yBodyRot = y;
                le.yBodyRotO = y;
                le.yHeadRotO = y;
            }
            mob.setPos(Vec3.atCenterOf(be.getBlockPos()));
            s.mob = entities.extractEntity(mob, 0f);
            s.mob.lightCoords = s.lightCoords;
        }
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        if (s.hasOverlay) {
            pose.pushPose();
            pose.translate(0.5f, 0.5f, 0.5f);
            pose.rotateDegrees(Axis.YP, -s.yaw);
            pose.rotate(Axis.YP, (float) Math.PI); // the item display's own turn
            s.overlay.submit(pose, out, s.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        if (s.mob != null) {
            pose.pushPose();
            entities.submit(s.mob, camera, 0.5, 1 / 16.0, 0.5, pose, out);
            pose.popPose();
        }
    }
}
