package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.perf.GeckoValueIntern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GeckoLib 5.5.7: once a reload's baked animations are applied, the keyframe value table is cleared ({@link GeckoValueIntern}). */
@Mixin(targets = "com.geckolib.cache.GeckoLibResources", remap = false)
public abstract class GeckoLibResourcesMixin {
	@Inject(method = "applyResources", at = @At("TAIL"), require = 0, remap = false)
	private static void aftrhrs$clearInterned(CallbackInfo ci) {
		GeckoValueIntern.clear();
	}
}
