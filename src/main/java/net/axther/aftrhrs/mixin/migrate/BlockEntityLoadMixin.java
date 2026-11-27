package net.axther.aftrhrs.mixin.migrate;

import net.axther.aftrhrs.core.Pdc;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper kept block-entity plugin data (chest locks, bank chests...) in PublicBukkitValues; move it into Pdc. */
@Mixin(BlockEntity.class)
public abstract class BlockEntityLoadMixin {
    @Inject(method = {"loadWithComponents", "loadCustomOnly"}, at = @At("TAIL"))
    private void aftrhrs$capturePublicBukkitValues(ValueInput input, CallbackInfo ci) {
        input.read("PublicBukkitValues", CompoundTag.CODEC).ifPresent(tag -> {
            if (tag.isEmpty()) return;
            BlockEntity self = (BlockEntity) (Object) this;
            CompoundTag data = Pdc.of(self).copy();
            for (String k : tag.keySet()) if (!data.contains(k)) data.put(k, tag.get(k).copy());
            self.setAttached(Pdc.DATA, data);
            net.axther.aftrhrs.migrate.Legacy.count("block entity Paper data", String.join(",", new java.util.TreeSet<>(tag.keySet())));
        });
    }
}
