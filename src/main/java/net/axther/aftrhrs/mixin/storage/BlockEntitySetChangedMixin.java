package net.axther.aftrhrs.mixin.storage;

import net.axther.aftrhrs.feature.storage.StorageLog;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Storage "who changed what" (docs/storage.md): a block entity reporting a change lets {@link StorageLog} compare a
 * watched storage block with its last copy. Unwatched blocks cost one map lookup.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntitySetChangedMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void aftrhrs$storageLog(CallbackInfo ci) {
        StorageLog.changed((BlockEntity) (Object) this);
    }
}
