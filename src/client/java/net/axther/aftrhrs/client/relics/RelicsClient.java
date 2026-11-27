package net.axther.aftrhrs.client.relics;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.axther.aftrhrs.feature.relics.BackpackWear;
import net.axther.aftrhrs.feature.relics.RelicsInit;
import net.axther.aftrhrs.feature.relics.ThrownRelicTrident;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;

/**
 * Client side of the relics: the thrown relic trident drawn with the relic's own thrown model, and backpacks worn on
 * the back (the server says who wears which with {@link RelicsInit.BackpackPayload}; the look matches Oraxen's
 * armor-stand-on-the-player cosmetic: the backpack item's HEAD display seen from an armor stand head riding the player,
 * so each tier's model sizes itself). Also the Equip/Open Backpack key ({@link RelicsInit.BackpackKeyPayload}) and the
 * server's back-slot settings ({@link RelicsInit.BackSlotPayload}). The same layer draws the player in the inventory
 * screen's preview.
 */
public final class RelicsClient {
    /** Entity id → the backpack shown on that player. */
    private static final Map<Integer, ItemStack> BACKPACKS = new HashMap<>();

    private RelicsClient() {
    }

    /**
     * Equip / open backpack: opens the worn backpack, or puts one on (the held one first); with Sneak, takes the worn
     * one off. Default V (B is the claim-borders key), or {@code backpack-key} in {@code config/aftrhrs/backpack.json};
     * rebindable in Controls like any key.
     */
    public static final KeyMapping BACKPACK_KEY = backpackKey();

    public static void init() {
        EntityRendererRegistry.register(RelicsInit.THROWN_RELIC_TRIDENT, ThrownRelicRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(RelicsInit.BackpackPayload.TYPE, (payload, ctx) -> {
            if (payload.stack().isEmpty()) BACKPACKS.remove(payload.entityId());
            else BACKPACKS.put(payload.entityId(), payload.stack());
        });
        ClientPlayNetworking.registerGlobalReceiver(RelicsInit.BackSlotPayload.TYPE, (payload, ctx) -> {
            BackpackWear.clientWear = payload.wear();
            BackpackWear.clientHideWithElytra = payload.hideWithElytra();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BACKPACKS.clear();
            BackpackWear.clientWear = false; // the next server says for itself
            BackpackWear.clientHideWithElytra = true;
        });
        KeyMappingHelper.registerKeyMapping(BACKPACK_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (BACKPACK_KEY.consumeClick()) {
                if (mc.player != null && mc.gui.screen() == null && ClientPlayNetworking.canSend(RelicsInit.BackpackKeyPayload.TYPE)) {
                    ClientPlayNetworking.send(new RelicsInit.BackpackKeyPayload(mc.player.isShiftKeyDown()));
                }
            }
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Player) BACKPACKS.remove(entity.getId());
        });
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                @SuppressWarnings("unchecked")
                RenderLayerParent<AvatarRenderState, PlayerModel> parent = (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) avatar;
                helper.register(new BackpackLayer(parent, context.getItemModelResolver()));
            }
        });
    }

    /**
     * The key's default: {@code config/aftrhrs/backpack.json} {@code {"backpack-key": "key.keyboard.v"}} (any
     * {@code key.keyboard.*} / {@code key.mouse.*} name; written with V the first time). Only the default: a binding
     * picked in Controls is kept in options.txt as usual.
     */
    private static KeyMapping backpackKey() {
        InputConstants.Key key = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_V);
        java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aftrhrs/backpack.json");
        try {
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, "{\n  \"backpack-key\": \"key.keyboard.v\"\n}\n");
            } else {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
                if (o.has("backpack-key")) key = InputConstants.getKey(o.get("backpack-key").getAsString());
            }
        } catch (RuntimeException | java.io.IOException e) {
            net.axther.aftrhrs.client.Companion.LOG.warn("config/aftrhrs/backpack.json: {}; the backpack key defaults to V", e.toString());
        }
        return new KeyMapping("key.aftrhrs.backpack", key.getType(), key.getValue(), net.axther.aftrhrs.client.Companion.CATEGORY);
    }

    // ------------------------------------------------------------------ thrown relic trident

    static final class ThrownRelicState extends EntityRenderState {
        final ItemStackRenderState item = new ItemStackRenderState();
        float yRot, xRot;
    }

    static final class ThrownRelicRenderer extends EntityRenderer<ThrownRelicTrident, ThrownRelicState> {
        private final ItemModelResolver items;

        ThrownRelicRenderer(EntityRendererProvider.Context context) {
            super(context);
            this.items = context.getItemModelResolver();
        }

        @Override
        public ThrownRelicState createRenderState() {
            return new ThrownRelicState();
        }

        @Override
        public void extractRenderState(ThrownRelicTrident entity, ThrownRelicState state, float partialTicks) {
            super.extractRenderState(entity, state, partialTicks);
            state.yRot = entity.getYRot(partialTicks);
            state.xRot = entity.getXRot(partialTicks);
            ItemStack relic = entity.relic().copy();
            Identifier thrown = RelicsInit.thrownModel(relic);
            if (thrown != null) relic.set(DataComponents.ITEM_MODEL, thrown);
            items.updateForNonLiving(state.item, relic, ItemDisplayContext.FIXED, entity);
        }

        @Override
        protected AABB getBoundingBoxForCulling(ThrownRelicTrident entity, float partialTicks) {
            return super.getBoundingBoxForCulling(entity, partialTicks).inflate(1.5);
        }

        @Override
        public void submit(ThrownRelicState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
            pose.pushPose();
            // like the vanilla trident, but the relic model's tip points up its +Y (the vanilla prongs point to -Y)
            pose.rotateDegrees(Axis.YP, state.yRot - 90.0f);
            pose.rotateDegrees(Axis.ZP, state.xRot - 90.0f);
            state.item.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
            pose.popPose();
            super.submit(state, pose, collector, camera);
        }
    }

    // ------------------------------------------------------------------ backpack on the back

    static final class BackpackLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
        /** Neck (body pivot) to the riding armor stand's head-item origin, in model units (blocks, y down). */
        private static final float STAND_HEAD = -1.9875f;
        /**
         * Pushed back this far over chest armor: the backpacks' straps start 0.134 behind the body pivot plane, the
         * chestplate's back (body + 1 px inflation) ends at 0.1875; one more pixel clears it with 0.01 to spare.
         */
        private static final float ARMOR_GAP = 0.0625f;
        private final ItemModelResolver items;
        private final ItemStackRenderState item = new ItemStackRenderState();

        BackpackLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, ItemModelResolver items) {
            super(parent);
            this.items = items;
        }

        @Override
        public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
            ItemStack bp = BACKPACKS.get(state.id);
            if (bp == null || bp.isEmpty() || state.isSpectator || state.isInvisible || state.isVisuallySwimming || state.isFallFlying) return;
            ItemStack chest = state.chestEquipment;
            boolean elytra = chest.has(DataComponents.GLIDER);
            // Folded elytra wings lie right where the backpack goes: hidden (backpacks.elytra: hide), or drawn anyway.
            if (elytra && BackpackWear.clientHideWithElytra) return;
            items.updateForTopItem(item, bp, ItemDisplayContext.HEAD, Minecraft.getInstance().level, null, state.id);
            if (item.isEmpty()) return;
            pose.pushPose();
            PlayerModel model = getParentModel();
            model.root().translateAndRotate(pose);
            model.body.translateAndRotate(pose);
            // A chestplate's back sits 1 px behind the body; the straps would clip into it.
            if (!chest.isEmpty() && !elytra) pose.translate(0.0f, 0.0f, ARMOR_GAP);
            pose.translate(0.0f, STAND_HEAD, 0.0f);
            pose.rotateDegrees(Axis.YP, 180.0f);
            pose.scale(0.625f, -0.625f, -0.625f);
            item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
            pose.popPose();
        }
    }
}
