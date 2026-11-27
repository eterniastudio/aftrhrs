package net.axther.aftrhrs.client.fairylights;

import com.mojang.blaze3d.vertex.PoseStack;
import net.axther.aftrhrs.feature.fairylights.HookBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the strands that start at a String Hook (the hook itself is a block model). Rendered off screen too (strands are
 * long), with its own distance and frustum test on the strands' bounds.
 */
public final class HookRenderer implements BlockEntityRenderer<HookBlockEntity, HookRenderer.State> {
    public static final class State extends BlockEntityRenderState {
        final List<StrandRender.Draw> draws = new ArrayList<>();
    }

    public HookRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(HookBlockEntity be, State s, float pt, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, s, pt, camera, breakProgress);
        s.draws.clear();
        if (be.getLevel() != null) StrandRender.extract(be.fastener(), Vec3.atLowerCornerOf(be.getBlockPos()), camera, pt, be.getLevel(), s.draws);
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        StrandRender.submit(s.draws, pose, out, camera);
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    /** Drawn off screen (strands are long), so culled here: nothing to draw, too far, or none of it in view. */
    @Override
    public boolean shouldRender(HookBlockEntity be, Vec3 cam) {
        if (be.fastener().outgoing().isEmpty()) return false;   // only receives strands: the hook itself is a block model
        if (!BlockEntityRenderer.super.shouldRender(be, cam)) return false;   // 96 blocks
        return Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum().isVisible(be.renderBounds());
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
