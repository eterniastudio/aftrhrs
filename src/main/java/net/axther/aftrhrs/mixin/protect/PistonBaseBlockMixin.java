package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#PISTON}: the blocks a piston would move or break, before it moves any. */
@Mixin(PistonBaseBlock.class)
abstract class PistonBaseBlockMixin {
    @WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z"))
    private boolean aftrhrs$check(PistonStructureResolver resolver, Operation<Boolean> original, Level level, BlockPos pistonPos, Direction direction, boolean extending) {
        if (!original.call(resolver)) return false;
        if (!(level instanceof ServerLevel sl)) return true;
        Direction push = extending ? direction : direction.getOpposite();
        return FeatherEvents.PISTON.invoker().allow(sl, pistonPos.immutable(), push, extending, resolver.getToPush(), resolver.getToDestroy());
    }
}
