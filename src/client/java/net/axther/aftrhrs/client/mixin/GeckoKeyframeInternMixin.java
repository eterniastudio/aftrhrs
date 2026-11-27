package net.axther.aftrhrs.client.mixin;

import com.geckolib.loading.math.MathValue;
import net.axther.aftrhrs.client.perf.GeckoValueIntern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * GeckoLib 5.5.7: the keyframe triplet {@code bakeTriplet} builds gets the canonical baked value for each channel number
 * and a shared empty easing array ({@link GeckoValueIntern}). Fail-soft: if a GeckoLib update changes the call, nothing
 * is applied and GeckoLib bakes as before.
 */
@Mixin(targets = "com.geckolib.loading.definition.animation.ActorBoneAnimationKeyframe", remap = false)
public abstract class GeckoKeyframeInternMixin {
	private static final String TRIPLET = "Lcom/geckolib/loading/definition/animation/object/KeyframeTriplet;<init>(DDLcom/geckolib/loading/definition/animation/object/KeyframeTriplet;Lcom/geckolib/loading/math/MathValue;Lcom/geckolib/loading/math/MathValue;Lcom/geckolib/loading/math/MathValue;Lcom/geckolib/animation/object/EasingType;[Lcom/geckolib/loading/math/MathValue;)V";

	@ModifyArg(method = "bakeTriplet", at = @At(value = "INVOKE", target = TRIPLET), index = 3, require = 0, remap = false)
	private static MathValue aftrhrs$internX(MathValue v) {
		return GeckoValueIntern.intern(v);
	}

	@ModifyArg(method = "bakeTriplet", at = @At(value = "INVOKE", target = TRIPLET), index = 4, require = 0, remap = false)
	private static MathValue aftrhrs$internY(MathValue v) {
		return GeckoValueIntern.intern(v);
	}

	@ModifyArg(method = "bakeTriplet", at = @At(value = "INVOKE", target = TRIPLET), index = 5, require = 0, remap = false)
	private static MathValue aftrhrs$internZ(MathValue v) {
		return GeckoValueIntern.intern(v);
	}

	@ModifyArg(method = "bakeTriplet", at = @At(value = "INVOKE", target = TRIPLET), index = 7, require = 0, remap = false)
	private static MathValue[] aftrhrs$internArgs(MathValue[] args) {
		return GeckoValueIntern.internArgs(args);
	}
}
