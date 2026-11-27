package net.axther.aftrhrs.client.mixin;

import net.axther.aftrhrs.client.cosmetics.CosmeticsClient;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Players are drawn in their outfit: cosmetic armor instead of the real pieces, hidden pieces, the hat on the head. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererCosmeticsMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void aftrhrs$outfit(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        CosmeticsClient.dress(avatar, state);
        net.axther.aftrhrs.client.carry.CarryClient.extract(avatar, state);
    }
}
