package net.axther.aftrhrs.mixin.coast;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link FeatherEvents#VIBRATION}: a vibration listener may ignore a game event. */
@Mixin(VibrationSystem.Listener.class)
abstract class VibrationListenerMixin {
    @Shadow
    @Final
    private VibrationSystem system;

    @Inject(method = "handleGameEvent", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$vibration(ServerLevel level, Holder<GameEvent> event, GameEvent.Context context, Vec3 source,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (!FeatherEvents.VIBRATION.invoker().allow(level, system, event, context)) cir.setReturnValue(false);
    }
}
