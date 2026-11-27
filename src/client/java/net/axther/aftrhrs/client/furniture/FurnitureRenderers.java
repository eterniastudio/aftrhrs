package net.axther.aftrhrs.client.furniture;

import net.axther.aftrhrs.feature.furniture.FurnitureEntities;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/** Block entity renderers for curios, gadgets and power (what Paper drew with display entities) and chess tables. */
public final class FurnitureRenderers {
    private FurnitureRenderers() {
    }

    public static void init() {
        BlockEntityRenderers.register(FurnitureEntities.CURIO, CurioRenderer::new);
        BlockEntityRenderers.register(FurnitureEntities.GADGET, GadgetRenderer::new);
        BlockEntityRenderers.register(FurnitureEntities.POWER, PowerRenderer::new);
        BlockEntityRenderers.register(FurnitureEntities.CHESS, ChessRenderer::new);
    }
}
