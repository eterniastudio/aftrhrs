package net.axther.aftrhrs.mixin.smithing;

import net.axther.aftrhrs.feature.smithing.Soulbound;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Soulbound smithed tools stay with their owner when everything else drops on death. */
@Mixin(Inventory.class)
public abstract class InventorySoulboundMixin {
    @Inject(method = "dropAll", at = @At("HEAD"))
    private void aftrhrs$keepSoulbound(CallbackInfo ci) {
        Soulbound.stash((Inventory) (Object) this);
    }
}
