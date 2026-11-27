package net.axther.aftrhrs.mixin.smithing;

import net.axther.aftrhrs.feature.smithing.Effects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Traits that change mining speed with where you are (Deepdelver, Bedrock-born, Frostbite in the cold). */
@Mixin(Player.class)
public abstract class PlayerSmithMixin {
    @Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
    private void aftrhrs$smithSpeed(BlockState state, CallbackInfoReturnable<Float> cir) {
        Player self = (Player) (Object) this;
        float f = Effects.speedFactor(self, self.getMainHandItem(), state);
        if (f != 1f) cir.setReturnValue(cir.getReturnValue() * f);
    }
}
