package net.axther.aftrhrs.mixin.chat;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** {@link FeatherEvents#MOB_TARGET}: mobs leave some players alone (auth gate, god mode). */
@Mixin(Mob.class)
abstract class MobTargetMixin {
    @ModifyVariable(method = "setTarget", at = @At("HEAD"), argsOnly = true)
    private LivingEntity aftrhrs$target(LivingEntity target) {
        if (target == null) return null;
        return FeatherEvents.MOB_TARGET.invoker().allow((Mob) (Object) this, target) ? target : null;
    }
}
