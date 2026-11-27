package net.axther.aftrhrs.feature.trade;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Bukkit-style inventory helpers ({@code addItem} on the 36 storage slots, {@code isSimilar}, owner-only drops). */
public final class Stacks {
    private Stacks() {
    }

    public static boolean empty(ItemStack it) {
        return it == null || it.isEmpty();
    }

    /** Bukkit's {@code isSimilar}: same item and components, any count. */
    public static boolean similar(ItemStack a, ItemStack b) {
        return !empty(a) && !empty(b) && ItemStack.isSameItemSameComponents(a, b);
    }

    /** Slot-by-slot equality (null/empty match each other), counts included. */
    public static boolean same(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            ItemStack x = a.get(i), y = b.get(i);
            if (empty(x) != empty(y)) return false;
            if (!empty(x) && !ItemStack.matches(x, y)) return false;
        }
        return true;
    }

    /** Copies of the items (no empties). */
    public static List<ItemStack> copies(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : items) if (!empty(it)) out.add(it.copy());
        return out;
    }

    /** Puts one item into the slots like Bukkit's addItem (top up similar stacks, then empty slots); returns what is left. */
    private static int add(List<ItemStack> slots, ItemStack item, boolean apply) {
        int max = Math.max(1, item.getMaxStackSize());
        int left = item.getCount();
        for (int i = 0; i < slots.size() && left > 0; i++) {
            ItemStack s = slots.get(i);
            if (!similar(s, item) || s.getCount() >= max) continue;
            int add = Math.min(left, max - s.getCount());
            if (apply) s.grow(add);
            else slots.set(i, s.copyWithCount(s.getCount() + add));
            left -= add;
        }
        for (int i = 0; i < slots.size() && left > 0; i++) {
            if (!empty(slots.get(i))) continue;
            int n = Math.min(left, max);
            slots.set(i, item.copyWithCount(n));
            left -= n;
        }
        return left;
    }

    /** Whether every item fits into the player's 36 storage slots. */
    public static boolean fits(ServerPlayer p, List<ItemStack> items) {
        List<ItemStack> slots = new ArrayList<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) slots.add(empty(s) ? ItemStack.EMPTY : s.copy());
        for (ItemStack it : items) {
            if (empty(it)) continue;
            if (add(slots, it, false) > 0) return false;
        }
        return true;
    }

    /** Puts the items in the player's storage slots; what doesn't fit drops at their feet for them only. True if any dropped. */
    public static boolean give(ServerPlayer p, List<ItemStack> items) {
        boolean dropped = false;
        NonNullList<ItemStack> storage = p.getInventory().getNonEquipmentItems();
        for (ItemStack it : items) {
            if (empty(it)) continue;
            List<ItemStack> view = new ArrayList<>(storage);
            int left = add(view, it.copy(), true);
            for (int i = 0; i < storage.size(); i++) if (view.get(i) != storage.get(i)) p.getInventory().setItem(i, view.get(i));
            if (left > 0) {
                drop(p, it.copyWithCount(left));
                dropped = true;
            }
        }
        p.getInventory().setChanged();
        return dropped;
    }

    /** An item at the player's feet that only they can pick up, right away. */
    public static void drop(ServerPlayer p, ItemStack stack) {
        ItemEntity e = new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), stack);
        e.setTarget(p.getUUID());
        e.setThrower(p);
        e.setNoPickUpDelay();
        p.level().addFreshEntity(e);
    }

    /** "3x minecraft:diamond, 1x minecraft:stick \"Wand\"" for the logs. */
    public static String describe(List<ItemStack> items) {
        if (items.isEmpty()) return "nothing";
        List<String> parts = new ArrayList<>();
        for (ItemStack it : items) {
            String s = it.getCount() + "x " + BuiltInRegistries.ITEM.getKey(it.getItem());
            Component name = it.get(DataComponents.CUSTOM_NAME);
            if (name != null) s += " \"" + name.getString() + "\"";
            parts.add(s);
        }
        return String.join(", ", parts);
    }

    public static int count(List<ItemStack> items) {
        int n = 0;
        for (ItemStack it : items) if (!empty(it)) n += it.getCount();
        return n;
    }
}
