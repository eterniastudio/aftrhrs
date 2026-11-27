package net.axther.aftrhrs.feature.storage;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Who changed what in a storage network: every change of a storage block's contents is recorded on that block
 * (a block entity attachment, {@code aftrhrs:storage_log}, saved with the block), per kind of item, newest first,
 * at most {@link #PER_KIND} changes per kind and {@link #KINDS} kinds per block. It survives the network being
 * reshaped, the server restarting and the block being moved by nothing (storage blocks don't move).
 *
 * <p>How: a storage block that a terminal, a remote or a kept-loaded network has looked at is "watched": a copy of
 * its slots is kept in memory, and each time the block reports a change ({@code setChanged}, see
 * {@code mixin.storage.BlockEntitySetChangedMixin}) the difference is written down for whoever is acting right now:
 * the terminal's player ({@link Network} sets it around every take and put, crafts included), a player clicking in
 * the chest's own window, a hopper, or "Automation" when nobody is known.
 */
public final class StorageLog {
    /** Changes kept per kind of item in one storage block. */
    static final int PER_KIND = 4;
    /** Kinds of item kept per storage block (the least recently changed go first). */
    static final int KINDS = 64;
    /** Changes by the same actor in the same direction within this long add up into one line. */
    static final long MERGE_MS = 60_000;

    public static final AttachmentType<CompoundTag> LOG = AttachmentRegistry.create(AftrHrs.id("storage_log"),
            b -> b.persistent(CompoundTag.CODEC).initializer(CompoundTag::new));

    /** One change: who (a player's name, or "Hopper" / "Automation"), how many (+ in, - out), when (epoch ms). */
    public record Change(String who, int delta, long at) {
    }

    /** Who is acting right now (null: nobody known). */
    record Actor(String name, UUID id) {
        static Actor of(ServerPlayer p) {
            return p == null ? null : new Actor(p.getGameProfile().name(), p.getUUID());
        }
    }

    static final Actor HOPPER = new Actor("Hopper", null);
    static final Actor AUTOMATION = new Actor("Automation", null);

    private static Actor current;
    private static boolean writing;
    /**
     * Watched blocks and what they held at the last look: a {@link Slots} copy for a chest, barrel or shulker box,
     * {@link Kinds} counts for a Storage Vault. Server thread only.
     */
    private static final Map<BlockEntity, Object> SNAPSHOTS = new WeakHashMap<>();
    /** Log keys of items with components (encoding them is the costly part); cleared when full or the server stops. */
    private static final Map<KindKey, String> KEYS = new HashMap<>();
    static final int KEYS_MAX = 4096;

    /** A chest's slots at the last look: private copies (hoppers grow live stacks in place); only changed slots are re-copied. */
    private static final class Slots {
        ItemStack[] items;

        Slots(ItemStack[] items) {
            this.items = items;
        }
    }

    /**
     * A vault's count per kind at the last look, by {@link VaultPool.Entry} (an entry stays the same object while its
     * kind is held; a reshape re-watches, see {@link #reshaped}). Each value is {count, the look it was last seen in}.
     */
    private static final class Kinds {
        final IdentityHashMap<VaultPool.Entry, long[]> counts = new IdentityHashMap<>();
        long look;
    }

    /** An item kind as a map key: same item, same components (like {@link VaultPool}'s). */
    private record KindKey(ItemStack stack) {
        @Override
        public boolean equals(Object o) {
            return o instanceof KindKey k && ItemStack.isSameItemSameComponents(stack, k.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }

    private StorageLog() {
    }

    /** Registers the attachment (mod init; the class load does it), and forgets every look when a server stops. */
    static void init() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> stopped());
    }

    /** The server stopped: no watched block or cached key outlives it (singleplayer opens the next world in this game). */
    static void stopped() {
        SNAPSHOTS.clear();
        KEYS.clear();
        DIRTY.clear();
        dirtyCount = 0;
        dirtyOverflow = false;
        current = null;
        writing = false;
    }

    // ------------------------------------------------------------------ who

    /** Sets who is acting until {@link #pop}; returns the previous one. */
    public static Object push(Object actor) {
        Actor prev = current;
        current = (Actor) actor;
        return prev;
    }

    public static void pop(Object prev) {
        current = (Actor) prev;
    }

    /** For the container-menu mixin: a player is clicking in some menu. */
    public static Object pushPlayer(ServerPlayer p) {
        return push(Actor.of(p));
    }

    /** For the hopper mixin. */
    public static Object pushHopper() {
        return push(HOPPER);
    }

    // ------------------------------------------------------------------ watching

    /** Starts watching a storage block (a network read it): from now on its changes are written down. */
    static void watch(BlockEntity be) {
        if (!(be instanceof Container c) || SNAPSHOTS.containsKey(be)) return;
        SNAPSHOTS.put(be, look(c));
    }

    static boolean watched(BlockEntity be) {
        return SNAPSHOTS.containsKey(be);
    }

    /**
     * Storage Vaults changed shape: a watched block that is no longer its vault's controller stops being watched, and
     * every watched vault (or one that took in a watched block) starts again from a copy of its new contents, so
     * blocks joining or leaving don't show up as items coming or going.
     */
    static void reshaped(java.util.Collection<StorageVaultBE> blocks) {
        java.util.Set<BlockEntity> again = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (StorageVaultBE be : blocks) {
            BlockEntity controller = be.controllerBE();
            if (SNAPSHOTS.remove(be) != null || SNAPSHOTS.containsKey(controller)) again.add(controller);
        }
        for (BlockEntity c : again) {
            SNAPSHOTS.remove(c);
            watch(c);
        }
    }

    /** What the block holds now, as the look later changes are compared with. */
    private static Object look(Container c) {
        if (c instanceof StorageVaultBE v) {
            // A vault: every kind with its real count (its slots only show up to a stack).
            Kinds k = new Kinds();
            for (VaultPool.Entry e : v.contents().list) k.counts.put(e, new long[]{e.count, 0});
            return k;
        }
        ItemStack[] out = new ItemStack[c.getContainerSize()];
        for (int i = 0; i < out.length; i++) out[i] = c.getItem(i).copy();
        return new Slots(out);
    }

    /**
     * A block entity reported a change (the mixin, and a vault's own takes and puts): what changed since the last look
     * is written down, right away, for whoever is acting now. Cheap when the block isn't watched; when it is, only the
     * slots or kinds that changed cost more than a compare.
     */
    public static void changed(BlockEntity be) {
        // Server thread only: the client's block entities report changes too (in singleplayer, on the render thread),
        // and so may a world generation thread. Neither may touch the map.
        if (!(be.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || writing || SNAPSHOTS.isEmpty()) return;
        // Any block of a Storage Vault speaks for the vault, which is watched (and logged) at its controller.
        if (be instanceof StorageVaultBE v) be = v.controllerBE();
        Object snap = SNAPSHOTS.get(be);
        if (snap == null || !(be instanceof Container c)) return;
        Map<String, Integer> delta;
        if (be instanceof StorageVaultBE v && snap instanceof Kinds k) delta = diff(v, k);
        else if (!(be instanceof StorageVaultBE) && snap instanceof Slots s) delta = diff(c, s);
        else {
            SNAPSHOTS.put(be, look(c)); // not the look it should have (can't normally happen): start again from here
            return;
        }
        if (delta == null) return;
        delta.values().removeIf(n -> n == 0);
        if (delta.isEmpty()) return;
        record(be, delta, current == null ? AUTOMATION : current, System.currentTimeMillis());
    }

    private static Map<String, Integer> add(Map<String, Integer> delta, String key, int n) {
        if (n == 0) return delta;
        if (delta == null) delta = new LinkedHashMap<>();
        delta.merge(key, n, Integer::sum);
        return delta;
    }

    /** A chest's change since the last look, slot by slot (totals per kind: items may have moved between slots). */
    private static Map<String, Integer> diff(Container c, Slots snap) {
        Map<String, Integer> delta = null;
        ItemStack[] before = snap.items;
        int size = c.getContainerSize();
        if (before.length != size) {
            // Slots came or went: what was in the ones that went is gone.
            for (int i = size; i < before.length; i++) if (!before[i].isEmpty()) delta = add(delta, key(before[i]), -before[i].getCount());
            int was = before.length;
            before = Arrays.copyOf(before, size);
            for (int i = was; i < size; i++) before[i] = ItemStack.EMPTY;
            snap.items = before;
        }
        for (int i = 0; i < size; i++) {
            ItemStack it = c.getItem(i), old = before[i];
            if (ItemStack.matches(old, it)) continue;
            if (!old.isEmpty()) delta = add(delta, key(old), -old.getCount());
            if (!it.isEmpty()) delta = add(delta, key(it), it.getCount());
            // A copy: hoppers grow the live stack in place.
            before[i] = it.isEmpty() ? ItemStack.EMPTY : it.copy();
        }
        return delta;
    }

    /**
     * A vault's change since the last look, kind by kind: no copies, a count compare per kind. A kind that ran out and
     * was forgotten (or everything, after a clear or a load) counts as all of it going; were the same kind back under
     * a new entry, it comes in under the same key, so the two cancel.
     */
    private static Map<String, Integer> diff(StorageVaultBE v, Kinds snap) {
        Map<String, Integer> delta = null;
        List<VaultPool.Entry> list = v.contents().list;
        long look = ++snap.look;
        for (VaultPool.Entry e : list) {
            long[] last = snap.counts.get(e);
            long was = last == null ? 0 : last[0];
            if (e.count != was) delta = add(delta, e.logKey(), clamp(e.count - was));
            if (last == null) snap.counts.put(e, new long[]{e.count, look});
            else {
                last[0] = e.count;
                last[1] = look;
            }
        }
        // Every listed entry is in the map now: more in the map means some are gone.
        if (snap.counts.size() != list.size()) {
            // Entries no longer in the vault (pruned, cleared, loaded over): what they still held went.
            var it = snap.counts.entrySet().iterator();
            while (it.hasNext()) {
                var en = it.next();
                if (en.getValue()[1] == look) continue;
                if (en.getValue()[0] > 0) delta = add(delta, en.getKey().logKey(), clamp(-en.getValue()[0]));
                it.remove();
            }
        }
        return delta;
    }

    private static int clamp(long n) {
        return (int) Math.max(-Integer.MAX_VALUE, Math.min(Integer.MAX_VALUE, n));
    }

    /** The kind of an item for the log: its id, plus a hash of its components when it has any. */
    static String key(ItemStack it) {
        String id = BuiltInRegistries.ITEM.getKey(it.getItem()).toString();
        if (it.getComponentsPatch().isEmpty()) return id;
        MinecraftServer server = net.axther.aftrhrs.core.Feather.serverOrNull();
        // Cached only on the server thread and with the server's registries (without them, components that name
        // registry entries can't be encoded, and the key would differ).
        boolean cache = server != null && server.isSameThread();
        if (cache) {
            String known = KEYS.get(new KindKey(it));
            if (known != null) return known;
        }
        // The components as saved data (the same text on every server start, unlike a hash of the objects).
        var ops = server == null ? NbtOps.INSTANCE : server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag t = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(ops, it.getComponentsPatch()).result().orElse(null);
        String key = id + "#" + Integer.toHexString(t == null ? 0 : t.toString().hashCode());
        if (cache) {
            if (KEYS.size() >= KEYS_MAX) KEYS.clear();
            KEYS.put(new KindKey(it.copyWithCount(1)), key);
        }
        return key;
    }

    /** Bumped on every write: readers that saw this number before have nothing new to read. */
    private static long version;

    static long version() {
        return version;
    }

    /**
     * Storage blocks (by dimension and position; a vault by its controller) that wrote something down since the last
     * {@link #drainDirty}: the storage windows' refresh pass only redraws windows showing one of them. Collected only
     * while the storage module runs; past {@link #DIRTY_MAX} positions it stops collecting and says "everything".
     */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> DIRTY = new HashMap<>();
    private static int dirtyCount;
    private static boolean dirtyOverflow;
    static final int DIRTY_MAX = 4096;

    /** What {@link #drainDirty} hands over: the changed positions per dimension, or {@code overflow} (treat all as changed). */
    record Dirty(Map<ResourceKey<Level>, LongOpenHashSet> pos, boolean overflow) {
        static final Dirty NONE = new Dirty(Map.of(), false);
    }

    /** The storage blocks that changed since the last call, and forgets them. */
    static Dirty drainDirty() {
        if (DIRTY.isEmpty() && !dirtyOverflow) return Dirty.NONE;
        Dirty d = new Dirty(new HashMap<>(DIRTY), dirtyOverflow);
        DIRTY.clear();
        dirtyCount = 0;
        dirtyOverflow = false;
        return d;
    }

    private static void markDirty(BlockEntity be) {
        if (dirtyOverflow || StorageModule.active() == null || be.getLevel() == null) return;
        if (dirtyCount >= DIRTY_MAX) {
            dirtyOverflow = true;
            DIRTY.clear();
            dirtyCount = 0;
            return;
        }
        if (DIRTY.computeIfAbsent(be.getLevel().dimension(), k -> new LongOpenHashSet()).add(be.getBlockPos().asLong())) dirtyCount++;
    }

    private static void record(BlockEntity be, Map<String, Integer> delta, Actor who, long at) {
        version++;
        markDirty(be);
        writing = true;
        try {
            CompoundTag log = be.getAttachedOrCreate(LOG);
            for (Map.Entry<String, Integer> e : delta.entrySet()) {
                ListTag list = log.getListOrEmpty(e.getKey());
                ListTag out = new ListTag();
                CompoundTag head = list.isEmpty() ? null : list.getCompoundOrEmpty(0);
                int d = e.getValue();
                if (head != null && who.name().equals(head.getStringOr("w", "")) && Integer.signum(head.getIntOr("d", 0)) == Integer.signum(d)
                        && at - head.getLongOr("t", 0L) < MERGE_MS) {
                    CompoundTag merged = head.copy();
                    merged.putInt("d", head.getIntOr("d", 0) + d);
                    merged.putLong("t", at);
                    out.add(merged);
                    for (int i = 1; i < list.size() && out.size() < PER_KIND; i++) out.add(list.get(i));
                } else {
                    CompoundTag c = new CompoundTag();
                    c.putString("w", who.name());
                    c.putInt("d", d);
                    c.putLong("t", at);
                    out.add(c);
                    for (int i = 0; i < list.size() && out.size() < PER_KIND; i++) out.add(list.get(i));
                }
                log.put(e.getKey(), out);
            }
            if (log.size() > KINDS) {
                // The kinds changed the longest ago go first.
                List<String> keys = new ArrayList<>(log.keySet());
                keys.sort(Comparator.comparingLong(k -> newest(log.getListOrEmpty(k))));
                for (int i = 0; i < keys.size() - KINDS; i++) log.remove(keys.get(i));
            }
            be.setAttached(LOG, log);
            be.setChanged();
        } finally {
            writing = false;
        }
    }

    private static long newest(ListTag list) {
        return list.isEmpty() ? 0L : list.getCompoundOrEmpty(0).getLongOr("t", 0L);
    }

    // ------------------------------------------------------------------ reading

    /** The changes recorded on one storage block for one kind of item, newest first. */
    static List<Change> changes(BlockEntity be, String key) {
        CompoundTag log = be.getAttached(LOG);
        if (log == null) return List.of();
        ListTag list = log.getListOrEmpty(key);
        List<Change> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompoundOrEmpty(i);
            out.add(new Change(c.getStringOr("w", "?"), c.getIntOr("d", 0), c.getLongOr("t", 0L)));
        }
        return out;
    }

    /** A network's recent changes by kind of item (newest first, at most {@code n} each), over all its blocks. */
    static Map<String, List<Change>> collect(Network net, int n) {
        Map<String, List<Change>> all = new HashMap<>();
        java.util.Set<BlockEntity> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Network.Source s : net.sources) {
            if (!(s.container() instanceof BlockEntity be) || !seen.add(be)) continue;
            CompoundTag log = be.getAttached(LOG);
            if (log == null) continue;
            for (String k : log.keySet()) {
                List<Change> list = all.computeIfAbsent(k, x -> new ArrayList<>());
                list.addAll(changes(be, k));
            }
        }
        return newestFirst(all, n);
    }

    /** {@link #collect(Network, int)} for these kinds only (what a window shows), without reading every other kind. */
    static Map<String, List<Change>> collect(Network net, int n, java.util.Collection<String> keys) {
        Map<String, List<Change>> all = new HashMap<>();
        if (keys.isEmpty()) return all;
        java.util.Set<BlockEntity> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Network.Source s : net.sources) {
            if (!(s.container() instanceof BlockEntity be) || !seen.add(be)) continue;
            CompoundTag log = be.getAttached(LOG);
            if (log == null || log.isEmpty()) continue;
            for (String k : keys) {
                if (!log.contains(k)) continue;
                all.computeIfAbsent(k, x -> new ArrayList<>()).addAll(changes(be, k));
            }
        }
        return newestFirst(all, n);
    }

    private static Map<String, List<Change>> newestFirst(Map<String, List<Change>> all, int n) {
        for (List<Change> list : all.values()) {
            list.sort(Comparator.comparingLong(Change::at).reversed());
            if (list.size() > n) list.subList(n, list.size()).clear();
        }
        return all;
    }

    // ------------------------------------------------------------------ text (see StorageTime)

    public static String when(long epochMs, ZoneId zone) {
        return StorageTime.when(epochMs, zone);
    }

    public static String ago(long epochMs, long now) {
        return StorageTime.ago(epochMs, now);
    }

    public static String signed(int delta) {
        return StorageTime.signed(delta);
    }
}
