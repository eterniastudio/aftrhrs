package net.axther.aftrhrs.client.entity;

import java.util.Map;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.entity.EntityType;

/** Registers a renderer for every Arcana creature (one line in AftrHrsClient). */
public final class ArcanaRenderers {
    private ArcanaRenderers() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init() {
        // Under a shader pack the glow layer needs Iris's emissive-entity program (Iris logged "Missing program
        // geckolib:pipeline/emissive in override list" and drew the glowmasks with the vanilla shader). In the shadow
        // pass it draws as an entity, like vanilla's eyes layer.
        net.axther.aftrhrs.client.compat.IrisCompat.assign(ArcanaGeoRenderer.GlowLayer.pipeline(), "EMISSIVE_ENTITIES", "SHADOW_ENTITIES");
        for (Map.Entry<EntityType<?>, Species> e : ArcanaEntities.all().entrySet()) {
            Species s = e.getValue();
            EntityType type = e.getKey();
            if (s.model == null) EntityRendererRegistry.register(type, ctx -> new NoopRenderer<>(ctx));
            else EntityRendererRegistry.register(type, ctx -> new ArcanaGeoRenderer(ctx, s));
        }
    }
}
