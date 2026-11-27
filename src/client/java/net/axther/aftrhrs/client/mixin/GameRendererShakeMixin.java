package net.axther.aftrhrs.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.axther.aftrhrs.client.entity.BossShake;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boss camera shake rides on the hurt tilt: the same pose stack, so it moves the world view and not the HUD. */
@Mixin(GameRenderer.class)
public abstract class GameRendererShakeMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"))
	private void aftrhrs$bossShake(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		BossShake.apply(poseStack);
	}
}
