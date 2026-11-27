package net.axther.aftrhrs.entity.mixin;

import net.axther.aftrhrs.entity.ArcanaMount;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shift steers flying / swimming mounts (descend / dive) instead of dismounting while airborne or in water. */
@Mixin(Player.class)
abstract class PlayerRidingMixin {
    @Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$mountShift(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (self.getVehicle() instanceof ArcanaMount mount && !mount.allowShiftDismount(self)) cir.setReturnValue(false);
    }
}
