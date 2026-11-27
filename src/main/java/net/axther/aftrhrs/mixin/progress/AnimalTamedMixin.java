package net.axther.aftrhrs.mixin.progress;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.advancements.triggers.TameAnimalTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#ANIMAL_TAMED}: every vanilla taming (wolves, cats, parrots, horses ...) fires this trigger. */
@Mixin(TameAnimalTrigger.class)
abstract class AnimalTamedMixin {
    @Inject(method = "trigger", at = @At("HEAD"))
    private void aftrhrs$tamed(ServerPlayer player, Animal animal, CallbackInfo ci) {
        if (player != null && animal != null) FeatherEvents.ANIMAL_TAMED.invoker().tamed(player, animal);
    }
}
