package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link FeatherEvents#BLOCK_PLACE} for a player emptying a bucket (Bukkit's PlayerBucketEmptyEvent): the fluid
 * washes away whatever replaceable block is at {@code pos} (a grave's player head) without any break event.
 */
@Mixin(BucketItem.class)
abstract class BucketEmptyMixin {
    @Shadow
    @Final
    protected Fluid content;

    @Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$empty(LivingEntity user, Level level, BlockPos pos, BlockHitResult hit, CallbackInfoReturnable<Boolean> cir) {
        if (!(user instanceof ServerPlayer p) || !(level instanceof ServerLevel sl) || content == Fluids.EMPTY) return;
        ItemStack held = p.getMainHandItem().getItem() == (Object) this ? p.getMainHandItem() : p.getOffhandItem();
        if (!FeatherEvents.BLOCK_PLACE.invoker().allow(p, sl, pos, content.defaultFluidState().createLegacyBlock(), held)) {
            p.containerMenu.sendAllDataToRemote();
            cir.setReturnValue(false);
        }
    }
}
