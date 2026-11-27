package net.axther.aftrhrs.feature.storage;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a Storage Vault holds: a count per kind of item (item plus components), any number of kinds, each up to the
 * vault's per-kind limit ({@link StorageVaultBE#STACKS_PER_BLOCK} stacks per block). Counts go past a stack.
 *
 * <p>Hoppers (and anything else using {@link net.minecraft.world.Container}) see one slot per kind, showing up to a
 * stack of it ({@link Entry#view}), plus one empty slot for a new kind. Vanilla hoppers change the stack they got from
 * {@code getItem} directly (grow it when they put items in, set its count back when a take didn't fit anywhere), so
 * those views stay live: {@link #sync} turns any change to a view's count into a change of the kind's real count, and
 * runs before every read or write.
 */
final class VaultPool {
    static final class Entry {
        final ItemStack proto;
        long count;
        /** The stack a hopper sees for this kind (its count is {@link #shown} unless something changed it). */
        ItemStack view;
        int shown;
        /**
         * Something was just taken through this slot: its view stays as it is (not shown full again) until the end of
         * the tick or until it changes, so a hopper that couldn't place the item can still set it back.
         */
        boolean pending;
        /** {@link StorageLog#key} of {@link #proto}, made on first use (proto never changes). */
        private String logKey;

        Entry(ItemStack like) {
            proto = like.copyWithCount(1);
            view = proto.copyWithCount(0);
        }

        String logKey() {
            String k = logKey;
            if (k == null) logKey = k = StorageLog.key(proto);
            return k;
        }

        int max() {
            return Math.max(1, proto.getMaxStackSize());
        }
    }

    private record Key(ItemStack stack) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && ItemStack.isSameItemSameComponents(stack, k.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }

    final List<Entry> list = new ArrayList<>();
    private final Map<Key, Entry> byKind = new HashMap<>();

    Entry get(ItemStack like) {
        return like == null || like.isEmpty() ? null : byKind.get(new Key(like));
    }

    private Entry getOrAdd(ItemStack like) {
        Entry e = get(like);
        if (e == null) {
            e = new Entry(like);
            list.add(e);
            byKind.put(new Key(e.proto), e);
        }
        return e;
    }

    long count(ItemStack like) {
        Entry e = get(like);
        return e == null ? 0 : e.count;
    }

    /** Adds without looking at any limit (callers check room first; nothing is ever thrown away here). */
    void add(ItemStack like, long n) {
        if (n <= 0 || like.isEmpty()) return;
        Entry e = getOrAdd(like);
        e.count += n;
        refresh(e);
    }

    /** Takes up to {@code n}; returns how many were taken. */
    long take(ItemStack like, long n) {
        Entry e = get(like);
        if (e == null || n <= 0) return 0;
        long t = Math.min(n, e.count);
        e.count -= t;
        refresh(e);
        return t;
    }

    boolean isEmpty() {
        for (Entry e : list) if (e.count > 0) return false;
        return true;
    }

    void clear() {
        list.clear();
        byKind.clear();
    }

    /** Kinds that ran out are forgotten (their slots go away). Only between ticks: hoppers count on slots staying put. */
    void prune() {
        list.removeIf(e -> {
            if (e.count > 0) return false;
            byKind.remove(new Key(e.proto));
            return true;
        });
    }

    void mergeFrom(VaultPool other) {
        other.sync();
        for (Entry e : other.list) if (e.count > 0) add(e.proto, e.count);
        other.clear();
    }

    int kinds() {
        int n = 0;
        for (Entry e : list) if (e.count > 0) n++;
        return n;
    }

    /** Stacks it takes to hold everything (each kind rounded up to whole stacks). */
    long stacks() {
        long n = 0;
        for (Entry e : list) if (e.count > 0) n += (e.count + e.max() - 1) / e.max();
        return n;
    }

    /** Applies whatever was done to the views since the last call, then shows up to a stack of each kind again. */
    void sync() {
        for (Entry e : list) {
            int now = e.view.getCount();
            if (now != e.shown) {
                e.count = Math.max(0, e.count + (now - e.shown));
                e.pending = false;
            }
            if (e.pending) e.shown = now;
            else refresh(e);
        }
    }

    /** End of a tick: every slot shows up to a full stack again, and kinds that ran out are forgotten. */
    void settle() {
        sync();
        for (Entry e : list) {
            e.pending = false;
            refresh(e);
        }
        prune();
    }

    private static void refresh(Entry e) {
        if (e.pending) {
            e.shown = e.view.getCount();
            return;
        }
        int show = (int) Math.min(e.count, e.max());
        e.view.setCount(show);
        e.shown = show;
    }

    /** The kind in this slot gives up to {@code n} of what it shows (a hopper or a player taking from a slot). */
    ItemStack removeShown(int slot, int n) {
        if (slot < 0 || slot >= list.size() || n <= 0) return ItemStack.EMPTY;
        Entry e = list.get(slot);
        int t = (int) Math.min(Math.min(n, e.shown), e.count);
        if (t <= 0) return ItemStack.EMPTY;
        e.count -= t;
        e.shown -= t;
        // Not refreshed yet: a hopper that can't place it sets this view's count back, and that is put back in.
        e.view.setCount(e.shown);
        e.pending = true;
        return e.proto.copyWithCount(t);
    }

    /** What the slot shows is replaced by {@code stack} (which may be the slot's own view, set back by a hopper). */
    void setShown(int slot, ItemStack stack) {
        int n = stack.isEmpty() ? 0 : stack.getCount();
        ItemStack kind = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (slot >= 0 && slot < list.size()) {
            Entry e = list.get(slot);
            e.count = Math.max(0, e.count - e.shown);
            e.shown = 0;
            e.pending = false;
            refresh(e);
        }
        if (n > 0) add(kind, n);
    }
}
