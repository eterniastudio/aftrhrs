package net.axther.aftrhrs.client.carry;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.axther.aftrhrs.feature.travel.CarriedBlock;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client side of carrying: a player carrying a block holds it out in front with both arms (the block from the synced
 * {@link CarriedBlock} attachment, drawn as its item), a player holding a mob holds their arms out under it (the mob
 * itself is a passenger placed in their arms, see {@code CarryPose}). In first person the block is the main-hand item
 * ({@code FirstPersonCarryMixin}).
 */
public final class CarryClient {
    /** Render-state entity id → the block's item, for players carrying a block this frame. */
    private static final Map<Integer, ItemStack> BLOCKS = new HashMap<>();
    /** Render-state entity ids of players whose arms are full (a block or a mob). */
    private static final Set<Integer> ARMS = new HashSet<>();

    private CarryClient() {
    }

    public static void register() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                @SuppressWarnings("unchecked")
                RenderLayerParent<AvatarRenderState, PlayerModel> parent = (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) avatar;
                helper.register(new CarriedBlockLayer(parent, context.getItemModelResolver()));
            }
        });
    }

    /** The block a player carries, as an item to draw (empty when none or it has no item). */
    public static ItemStack blockItem(Player p) {
        CarriedBlock c = p.getAttached(CarriedBlock.CARRIED);
        if (c == null) return ItemStack.EMPTY;
        var item = c.state().getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** Called when a player's render state is filled ({@code AvatarRendererCosmeticsMixin}). */
    public static void extract(Avatar avatar, AvatarRenderState state) {
        BLOCKS.remove(state.id);
        ARMS.remove(state.id);
        if (!(avatar instanceof Player p)) return;
        ItemStack block = blockItem(p);
        boolean mob = false;
        for (Entity e : p.getPassengers()) if (net.axther.aftrhrs.feature.travel.CarryPose.inArms(p, e)) mob = true;
        if (!block.isEmpty()) BLOCKS.put(state.id, block);
        if (!block.isEmpty() || mob || p.hasAttached(CarriedBlock.CARRIED)) ARMS.add(state.id);
    }

    /** Whether this player's arms are held out (block or mob). */
    public static boolean armsFull(AvatarRenderState state) {
        return ARMS.contains(state.id);
    }

    /** The block held out in front of the chest, about two-thirds of a block across. */
    static final class CarriedBlockLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
        private final ItemModelResolver items;
        private final ItemStackRenderState item = new ItemStackRenderState();

        CarriedBlockLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, ItemModelResolver items) {
            super(parent);
            this.items = items;
        }

        @Override
        public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
            ItemStack block = BLOCKS.get(state.id);
            if (block == null || state.isSpectator || state.isInvisible) return;
            items.updateForTopItem(item, block, ItemDisplayContext.FIXED, Minecraft.getInstance().level, null, state.id);
            if (item.isEmpty()) return;
            pose.pushPose();
            PlayerModel model = getParentModel();
            model.root().translateAndRotate(pose);
            model.body.translateAndRotate(pose);
            // Model space: y down, -z forward. Resting on the outstretched hands, in front of the chest. FIXED is the
            // item-frame view: the block centred at half size, so 1.36 makes it about two-thirds of a block.
            pose.translate(0.0f, 0.3f, -0.5f);
            pose.rotateDegrees(Axis.YP, 180.0f);
            pose.scale(1.36f, -1.36f, -1.36f);
            item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
            pose.popPose();
        }
    }
}
