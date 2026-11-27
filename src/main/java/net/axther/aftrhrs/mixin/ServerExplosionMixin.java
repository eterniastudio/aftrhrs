package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Explosions leave protected blocks alone ({@link FeatherEvents#BLOCK_PROTECTED}). */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
    private void aftrhrs$keepProtected(CallbackInfoReturnable<List<BlockPos>> cir) {
        List<BlockPos> list = cir.getReturnValue();
        if (list != null && !list.isEmpty()) list.removeIf(pos -> FeatherEvents.isProtected(level, pos));
    }
}
