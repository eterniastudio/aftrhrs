package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#HOPPER_PULL}: hoppers and hopper minecarts pulling from the container above. */
@Mixin(HopperBlockEntity.class)
abstract class HopperMixin {
    @WrapOperation(method = "suckInItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getSourceContainer(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/Container;"))
    private static Container aftrhrs$source(Level level, Hopper hopper, BlockPos pos, BlockState state, Operation<Container> original) {
        Container c = original.call(level, hopper, pos, state);
        if (c != null && level instanceof ServerLevel sl && !FeatherEvents.HOPPER_PULL.invoker().allow(sl, hopper, c)) return null;
        return c;
    }
}
