package net.axther.aftrhrs.mixin.progress;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.advancements.triggers.FishingRodHookedTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** {@link FeatherEvents#FISH_CAUGHT}: the fishing-rod advancement trigger fires with the loot of every catch. */
@Mixin(FishingRodHookedTrigger.class)
abstract class FishCaughtMixin {
    @Inject(method = "trigger", at = @At("HEAD"))
    private void aftrhrs$caught(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> items, CallbackInfo ci) {
        if (items != null && !items.isEmpty()) FeatherEvents.FISH_CAUGHT.invoker().caught(player, rod, hook, items);
    }
}
