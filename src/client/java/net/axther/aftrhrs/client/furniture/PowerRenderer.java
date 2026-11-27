package net.axther.aftrhrs.client.furniture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.axther.aftrhrs.client.render.RenderStacks;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.feature.furniture.power.PowerBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Encased Fan's spinning propeller while it is powered (the block model shows a still propeller otherwise):
 * the propeller model turned like Paper's overlay display ({@code facing} rotation, then the spin about the fan's axis).
 */
public final class PowerRenderer implements BlockEntityRenderer<PowerBlockEntity, PowerRenderer.State> {
    private static String propeller;

    public static final class State extends BlockEntityRenderState {
        final ItemStackRenderState item = new ItemStackRenderState();
        boolean show;
        Direction facing = Direction.NORTH;
        float spin;
    }

    private final ItemModelResolver itemModels;

    public PowerRenderer(BlockEntityRendererProvider.Context ctx) {
        this.itemModels = ctx.itemModelResolver();
    }

    private static String propeller() {
        if (propeller == null) propeller = Feather.bundled("power.yml").getString("fan.propeller", "feather_encased_fan_propeller");
        return propeller;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PowerBlockEntity be, State s, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, s, partialTicks, camera, breakProgress);
        BlockState st = be.getBlockState();
        s.show = st.hasProperty(FurnitureBlock.POWERED) && st.getValue(FurnitureBlock.POWERED) && st.hasProperty(FurnitureBlock.FACING_ALL);
        if (!s.show) {
            s.item.clear();
            return;
        }
        s.facing = st.getValue(FurnitureBlock.FACING_ALL);
        s.spin = Mth.lerp(partialTicks, be.spinO, be.spin);
        ItemStack prop = RenderStacks.content(be.getLevel(), propeller());
        if (prop.isEmpty()) s.show = false;
        else itemModels.updateForTopItem(s.item, prop, ItemDisplayContext.NONE, be.getLevel(), null, 0);
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        if (!s.show) return;
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        switch (s.facing) {
            case SOUTH -> pose.rotate(Axis.YP, (float) Math.PI);
            case EAST -> pose.rotate(Axis.YP, (float) -Math.PI / 2);
            case WEST -> pose.rotate(Axis.YP, (float) Math.PI / 2);
            case UP -> pose.rotate(Axis.XP, (float) Math.PI / 2);
            case DOWN -> pose.rotate(Axis.XP, (float) -Math.PI / 2);
            default -> {
            }
        }
        pose.rotate(Axis.ZP, s.spin);
        pose.rotate(Axis.YP, (float) Math.PI); // the item display's own turn
        s.item.submit(pose, out, s.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }
}
