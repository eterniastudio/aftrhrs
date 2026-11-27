package net.axther.aftrhrs.mixin.coast;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** {@link FeatherEvents#FISH_LURE}, {@code FISH_BITE}, {@code FISH_ESCAPE}, {@code FISH_REEL} and {@code FISH_CATCH}. */
@Mixin(FishingHook.class)
abstract class FishingHookMixin {
    @Shadow
    private int nibble;
    @Shadow
    private int timeUntilLured;
    @Shadow
    private int timeUntilHooked;
    @Shadow
    private @Nullable Entity hookedIn;

    @Unique
    private int aftrhrs$nibbleBefore;
    @Unique
    private boolean aftrhrs$rolling;

    @Inject(method = "catchingFish", at = @At("HEAD"))
    private void aftrhrs$before(BlockPos pos, CallbackInfo ci) {
        aftrhrs$nibbleBefore = nibble;
        aftrhrs$rolling = nibble <= 0 && timeUntilHooked <= 0 && timeUntilLured <= 0;
    }

    @Inject(method = "catchingFish", at = @At("TAIL"))
    private void aftrhrs$after(BlockPos pos, CallbackInfo ci) {
        FishingHook self = (FishingHook) (Object) this;
        if (!(self.getPlayerOwner() instanceof ServerPlayer p)) return;
        if (aftrhrs$rolling && timeUntilLured > 0) {
            timeUntilLured = Math.max(1, FeatherEvents.FISH_LURE.invoker().lureTicks(self, p, timeUntilLured));
        }
        if (aftrhrs$nibbleBefore <= 0 && nibble > 0) FeatherEvents.FISH_BITE.invoker().bite(self, p);
        else if (aftrhrs$nibbleBefore > 0 && nibble <= 0) FeatherEvents.FISH_ESCAPE.invoker().bite(self, p);
    }

    @Inject(method = "retrieve", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$reel(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        FishingHook self = (FishingHook) (Object) this;
        if (self.level().isClientSide() || hookedIn != null || !(self.getPlayerOwner() instanceof ServerPlayer p)) return;
        if (!FeatherEvents.FISH_REEL.invoker().allow(self, p, rod, nibble > 0)) cir.setReturnValue(0);
    }

    @ModifyVariable(method = "retrieve", at = @At("STORE"), ordinal = 0)
    private List<ItemStack> aftrhrs$loot(List<ItemStack> items, ItemStack rod) {
        FishingHook self = (FishingHook) (Object) this;
        if (!(self.getPlayerOwner() instanceof ServerPlayer p)) return items;
        List<ItemStack> loot = new ArrayList<>(items);
        FeatherEvents.FISH_CATCH.invoker().caught(self, p, rod, loot);
        loot.removeIf(ItemStack::isEmpty);
        return loot;
    }
}
