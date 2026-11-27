package net.axther.aftrhrs.mixin.migrate;

import net.axther.aftrhrs.migrate.LegacyItems;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Same as {@link ItemStackMixin} for item templates (container and bundle contents in 26.x). */
@Mixin(ItemStackTemplate.class)
public abstract class ItemStackTemplateMixin {
    @Shadow @Final @Mutable private Holder<Item> item;
    @Shadow @Final @Mutable private DataComponentPatch components;

    @Inject(method = "<init>(Lnet/minecraft/core/Holder;ILnet/minecraft/core/component/DataComponentPatch;)V", at = @At("TAIL"))
    private void aftrhrs$convertLegacy(Holder<Item> holder, int count, DataComponentPatch patch, CallbackInfo ci) {
        if (holder == null || patch == null || patch.isEmpty()
                || !LegacyItems.isLegacy(patch.get(net.minecraft.core.component.DataComponentMap.EMPTY, net.minecraft.core.component.DataComponents.CUSTOM_DATA))) return;
        LegacyItems.Converted c = LegacyItems.convert(holder, patch);
        if (c == null) return;
        this.item = c.item();
        this.components = c.patch();
    }
}
