package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.entity.ArcanaCreature;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tamed Arcana creatures never burn in daylight. Some burn while wild (minecraft:burn_in_daylight, e.g. the Rime
 * Wraith: gen_creatures.py SUNBURN); a pet doesn't go up in flames every morning.
 */
@Mixin(Mob.class)
abstract class MobSunburnMixin {
    @Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$petsDontBurn(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ArcanaCreature c && c.isTame()) cir.setReturnValue(false);
    }
}
