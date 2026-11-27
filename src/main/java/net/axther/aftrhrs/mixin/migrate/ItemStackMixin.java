package net.axther.aftrhrs.mixin.migrate;

import net.axther.aftrhrs.migrate.Legacy;
import net.axther.aftrhrs.migrate.LegacyItems;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Oraxen items saved by the Paper server become native items as they load (see {@link LegacyItems}). Hooks the public
 * {@code ItemStack(Holder, int, DataComponentPatch)} constructor, which every decode path uses (the codecs, the stream
 * codecs, ItemStackTemplate.create(), /give and /item, transmuteCopy), and not the private one, which copy(),
 * copyWithCount and split also go through: a copy of an already converted stack has nothing to convert.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Shadow @Final @Mutable private Holder<Item> item;
    @Shadow @Final @Mutable private PatchedDataComponentMap components;

    @Inject(method = "<init>(Lnet/minecraft/core/Holder;ILnet/minecraft/core/component/DataComponentPatch;)V", at = @At("TAIL"))
    private void aftrhrs$convertLegacy(Holder<Item> holder, int count, DataComponentPatch patch, CallbackInfo ci) {
        // the delegated constructor already used holder and patch, so neither is null here; the EMPTY fallback makes
        // the lookup read the patch only
        if (!Legacy.ready() || patch.isEmpty()
                || !LegacyItems.isLegacy(patch.get(DataComponentMap.EMPTY, DataComponents.CUSTOM_DATA))) return;
        LegacyItems.Converted c = LegacyItems.convert(holder, patch);
        if (c == null) return;
        this.item = c.item();
        this.components = PatchedDataComponentMap.fromPatch(c.item().components(), c.patch());
    }
}
