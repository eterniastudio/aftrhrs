package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.fairylights.FairyLightsClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fairy lights: punching or right-clicking a strand (strands are not entities or blocks, so vanilla's crosshair never
 * finds them; {@code StrandPicker} does, when nothing nearer is under the crosshair).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStrandMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void aftrhrs$attackStrand(CallbackInfoReturnable<Boolean> cir) {
		if (FairyLightsClient.attack((Minecraft) (Object) this)) cir.setReturnValue(false);
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void aftrhrs$useStrand(CallbackInfo ci) {
		if (FairyLightsClient.use((Minecraft) (Object) this)) {
			rightClickDelay = 4;
			ci.cancel();
		}
	}
}
