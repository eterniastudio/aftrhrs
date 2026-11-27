package net.axther.aftrhrs.mixin.smithing;

import net.axther.aftrhrs.feature.smithing.Mods;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.ToolBuild;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hush (echo steel): a player holding it makes no sculk vibrations. */
@Mixin(Entity.class)
public abstract class EntityHushMixin {
    @Inject(method = "dampensVibrations", at = @At("RETURN"), cancellable = true)
    private void aftrhrs$hush(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || Smithing.BUILD == null || !((Object) this instanceof Player p)) return;
        ToolBuild b = p.getMainHandItem().get(Smithing.BUILD);
        if (b != null && Mods.trait(b, "hush") > 0) cir.setReturnValue(true);
    }
}
