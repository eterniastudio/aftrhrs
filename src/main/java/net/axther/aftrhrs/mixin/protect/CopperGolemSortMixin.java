package net.axther.aftrhrs.mixin.protect;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** {@link FeatherEvents#MOB_USE_CONTAINER}: copper golems choosing a chest to take from or fill. */
@Mixin(TransportItemsBetweenContainers.class)
abstract class CopperGolemSortMixin {
    @Inject(method = "isTargetValidToPick", at = @At("RETURN"), cancellable = true)
    private void aftrhrs$mayUse(PathfinderMob body, Level level, BlockEntity blockEntity, Set<GlobalPos> visited, Set<GlobalPos> unreachable,
                                AABB area, CallbackInfoReturnable<TransportItemsBetweenContainers.TransportItemTarget> cir) {
        if (cir.getReturnValue() != null && level instanceof ServerLevel sl
                && !FeatherEvents.MOB_USE_CONTAINER.invoker().allow(body, sl, blockEntity.getBlockPos())) {
            cir.setReturnValue(null);
        }
    }
}
