package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BonemealableBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link FeatherEvents#PLANT_GROW}: bone meal used by a player, a dispenser or a farmer (refused: not used up). */
@Mixin(BoneMealItem.class)
abstract class BoneMealGrowMixin {
    @Inject(method = "growCrop", at = @At("HEAD"), cancellable = true)
    private static void aftrhrs$boneMeal(ItemStack stack, Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel sl && level.getBlockState(pos).getBlock() instanceof BonemealableBlock
                && !FeatherEvents.PLANT_GROW.invoker().allow(sl, pos)) {
            cir.setReturnValue(false);
        }
    }

    /** Sea grass and coral spreading through the water around {@code pos} (players and dispensers alike). */
    @Inject(method = "growWaterPlant", at = @At("HEAD"), cancellable = true)
    private static void aftrhrs$waterPlant(ItemStack stack, Level level, BlockPos pos, net.minecraft.core.Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel sl && !FeatherEvents.PLANT_GROW.invoker().allow(sl, pos)) cir.setReturnValue(false);
    }
}
