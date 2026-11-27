package net.axther.aftrhrs.mixin.migrate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads custom_data without copying it (the legacy-item check runs on every ItemStack built). */
@Mixin(CustomData.class)
public interface CustomDataAccessor {
    @Accessor("tag")
    CompoundTag aftrhrs$tag();
}
