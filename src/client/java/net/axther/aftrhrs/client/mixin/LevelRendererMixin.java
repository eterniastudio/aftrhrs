package net.axther.aftrhrs.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.axther.aftrhrs.client.claims.ClaimWalls;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Claim walls go in with the level's other features (camera-relative, like entities). One mixin instead of three
 * loader events: Forge 26.3 has no event at this point.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "submitFeatures", at = @At("TAIL"))
	private void aftrhrs$claims(LevelRenderState levelRenderState, SubmitNodeCollector collector, boolean renderOutline, CallbackInfo ci) {
		ClaimWalls.submit(collector, new PoseStack());
		net.axther.aftrhrs.client.places.PlaceBeams.submit(collector, new PoseStack());
	}
}
