package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#FORCED_POSE}: a held pose replaces the one vanilla works out each tick (server players only). */
@Mixin(Player.class)
abstract class PlayerPoseMixin {
    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$forcedPose(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer self)) return;
        Pose pose = FeatherEvents.FORCED_POSE.invoker().pose(self);
        if (pose == null) return;
        self.setPose(pose);
        ci.cancel();
    }
}
