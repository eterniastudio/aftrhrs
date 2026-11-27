package net.axther.aftrhrs.feature.storage;

import net.minecraft.world.item.ItemStack;

/**
 * The items that place storage blocks: the native block items. On Paper they were a fallback block (or an Oraxen skin)
 * tagged {@code feather:storage_item = <kind>}; old items convert as they load ({@code LegacyItems.tagged}).
 */
final class StorageItems {
    static final String TAG = "feather:storage_item";

    private StorageItems() {
    }

    /** One stack (amount clamped to the stack size). */
    static ItemStack make(Kind kind, int amount) {
        ItemStack it = new ItemStack(StorageContent.ITEMS.get(kind));
        it.setCount(Math.max(1, Math.min(amount, it.getMaxStackSize())));
        return it;
    }
}
