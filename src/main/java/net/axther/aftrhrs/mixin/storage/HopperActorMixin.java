package net.axther.aftrhrs.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.axther.aftrhrs.feature.storage.StorageLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** Storage "who changed what": what a hopper moves in or out of a storage block is written down as "Hopper". */
@Mixin(HopperBlockEntity.class)
public abstract class HopperActorMixin {
    @WrapMethod(method = "pushItemsTick")
    private static void aftrhrs$storageActor(Level level, BlockPos pos, BlockState state, HopperBlockEntity entity, Operation<Void> original) {
        Object prev = StorageLog.pushHopper();
        try {
            original.call(level, pos, state, entity);
        } finally {
            StorageLog.pop(prev);
        }
    }
}
