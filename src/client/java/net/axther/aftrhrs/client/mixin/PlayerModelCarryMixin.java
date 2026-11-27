package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.carry.CarryClient;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player carrying a block or a mob holds both arms out in front, under what they carry. */
@Mixin(PlayerModel.class)
public abstract class PlayerModelCarryMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void aftrhrs$carryArms(AvatarRenderState state, CallbackInfo ci) {
        if (!CarryClient.armsFull(state)) return;
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        float lift = state.isCrouching ? -1.05f : -1.2f;
        model.rightArm.xRot = lift;
        model.leftArm.xRot = lift;
        model.rightArm.yRot = -0.12f;
        model.leftArm.yRot = 0.12f;
        model.rightArm.zRot = 0.0f;
        model.leftArm.zRot = 0.0f;
    }
}
