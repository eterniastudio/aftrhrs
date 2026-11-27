package net.axther.aftrhrs.mixin.furniture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The bookshelf count an enchanting table rolls its offers with goes through {@link FeatherEvents#ENCHANTING_POWER}. */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {
    @WrapOperation(method = "lambda$slotsChanged$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;getEnchantmentCost(Lnet/minecraft/util/RandomSource;IILnet/minecraft/world/item/ItemStack;)I"))
    private int aftrhrs$power(RandomSource random, int slot, int bookcases, ItemStack item, Operation<Integer> original,
                              @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        int n = level.isClientSide() ? bookcases : FeatherEvents.ENCHANTING_POWER.invoker().power(level, pos, bookcases);
        return original.call(random, slot, n, item);
    }
}
