package net.axther.aftrhrs.mixin.progress;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FeatherEvents#STAT_AWARDED} whenever a vanilla statistic goes up. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerStatMixin {
    @Inject(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At("HEAD"))
    private void aftrhrs$statAwarded(Stat<?> stat, int count, CallbackInfo ci) {
        if (count > 0) FeatherEvents.STAT_AWARDED.invoker().awarded((ServerPlayer) (Object) this, stat, count);
    }
}
