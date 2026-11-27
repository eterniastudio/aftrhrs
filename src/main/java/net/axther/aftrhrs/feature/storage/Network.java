package net.axther.aftrhrs.feature.storage;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One terminal's network, read from the world at this moment: a flood fill from the terminal's six neighbors
 * through touching storage blocks (chests, trapped and copper chests, barrels, shulker boxes, Storage Vaults) and
 * Inventory Connectors (terminals don't pass it on), only through loaded chunks, up to {@code storage.max-blocks}
 * blocks and {@code storage.max-distance} from the terminal. A Storage Vault is one storage however many blocks it
 * has: it is read once, through its controller. Storage the viewer can't open (bank chests, mailboxes, chests
 * locked against them, claims without container trust, unopened loot chests) is left out, but still passes the
 * network on, so its shape is the same for everyone.
 *
 * <p>The containers are the live block entities, so every change lands in the real chest at once. A Network is only
 * used within the tick it was read in: a later click reads the world again.
 */
final class Network {
    /** One storage block's inventory (each half of a double chest on its own). */
    record Source(BlockPos pos, Container container, boolean shulker) {
    }

    /** One kind of item in the network: a single-item copy and how many there are. */
    static final class Entry {
        final ItemStack proto;
        long total;

        Entry(ItemStack proto) {
            this.proto = proto;
        }
    }

    final List<Source> sources;
    /** Whose takes and puts these are, for {@link StorageLog} (null: nobody known). */
    private final StorageLog.Actor actor;
    /** Storage blocks, a double chest counted once. */
    final int containers;
    final int connectors;
    final boolean truncated;

    private Network(List<Source> sources, int containers, int connectors, boolean truncated, ServerPlayer viewer) {
        this.sources = sources;
        this.actor = StorageLog.Actor.of(viewer);
        this.containers = containers;
        this.connectors = connectors;
        this.truncated = truncated;
    }

    static boolean isStorage(BlockState s) {
        return s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock || s.is(BlockTags.SHULKER_BOXES)
                || s.getBlock() instanceof StorageVaultBlock;
    }

    /** Reads the network around {@code terminal} as {@code viewer} sees it (viewer null: no lock or claim checks). */
    static Network scan(StorageModule module, ServerPlayer viewer, StorageBlock.Terminal.At terminal) {
        return scan(module, viewer, terminal, null);
    }

    /**
     * {@link #scan(StorageModule, ServerPlayer, StorageBlock.Terminal.At)} that also collects into {@code touched} the
     * chunks of every block the network has, and the not yet loaded chunks it would reach into (the remotes ticket
     * them, so the next read gets further).
     */
    static Network scan(StorageModule module, ServerPlayer viewer, StorageBlock.Terminal.At terminal, java.util.Set<Long> touched) {
        List<Source> sources = new ArrayList<>();
        if (!terminal.present()) return new Network(sources, 0, 0, false, viewer);
        ServerLevel level = terminal.level();
        BlockPos origin = terminal.pos();
        int maxBlocks = module.maxBlocks(), maxDist = module.maxDistance();
        Set<Long> seen = new HashSet<>();
        Set<Long> chestPairs = new HashSet<>();
        Set<Long> vaults = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(origin.asLong());
        int accepted = 0, containers = 0, connectors = 0;
        boolean truncated = false;
        enqueue(level, origin, origin, maxDist, seen, queue, touched);
        while (!queue.isEmpty()) {
            BlockPos b = queue.poll();
            BlockState state = level.getBlockState(b);
            if (state.getBlock() instanceof StorageBlock sb) {
                if (sb.kind != Kind.CONNECTOR) continue;
                if (accepted >= maxBlocks) {
                    truncated = true;
                    break;
                }
                accepted++;
                connectors++;
                if (touched != null) touched.add(net.minecraft.world.level.ChunkPos.pack(b));
                enqueue(level, b, origin, maxDist, seen, queue, touched);
                continue;
            }
            if (!isStorage(state)) continue;
            if (accepted >= maxBlocks) {
                truncated = true;
                break;
            }
            accepted++;
            if (touched != null) touched.add(net.minecraft.world.level.ChunkPos.pack(b));
            // Storage the viewer can't use still passes the network on (the shape is the same for everyone);
            // only its contents are left out.
            enqueue(level, b, origin, maxDist, seen, queue, touched);
            if (level.getBlockEntity(b) instanceof StorageVaultBE vault) {
                // Every block of a vault leads to the same inventory: take it once, at the controller.
                StorageVaultBE controller = vault.controllerBE();
                BlockPos at = controller.getBlockPos();
                if (vaults.contains(at.asLong())) continue;
                // Every block of it must be usable by the viewer (locks or claims on any one of them keep the whole vault out).
                boolean usable = true;
                for (StorageVaultBE m : controller.group()) {
                    if (!eligible(module.plugin(), level, m.getBlockPos(), viewer)) {
                        usable = false;
                        break;
                    }
                }
                if (!usable) continue;
                vaults.add(at.asLong());
                sources.add(new Source(at, controller, false));
                StorageLog.watch(controller);
                containers++;
                continue;
            }
            BlockPos partner = partner(level, b, state);
            // A half across the edge of the loaded area: its lock/claim can't be read without loading it, so skip it.
            if (partner != null && !level.hasChunk(partner.getX() >> 4, partner.getZ() >> 4)) {
                if (touched != null) touched.add(net.minecraft.world.level.ChunkPos.pack(partner));
                continue;
            }
            if (!eligible(module.plugin(), level, b, viewer) || (partner != null && !eligible(module.plugin(), level, partner, viewer))) continue;
            BlockEntity be = level.getBlockEntity(b);
            if (!(be instanceof Container c)) continue;
            if (be instanceof RandomizableContainerBlockEntity r && r.getLootTable() != null) continue; // unopened loot chests stay as they are
            sources.add(new Source(b, c, be instanceof ShulkerBoxBlockEntity));
            StorageLog.watch(be);
            if (partner == null) containers++;
            else if (chestPairs.add(Math.min(b.asLong(), partner.asLong()))) containers++;
        }
        return new Network(sources, containers, connectors, truncated, viewer);
    }

    private static void enqueue(ServerLevel level, BlockPos from, BlockPos origin, int maxDist, Set<Long> seen, ArrayDeque<BlockPos> queue, Set<Long> touched) {
        for (Direction f : Direction.values()) {
            BlockPos n = from.relative(f);
            if (Math.abs(n.getX() - origin.getX()) > maxDist || Math.abs(n.getY() - origin.getY()) > maxDist
                    || Math.abs(n.getZ() - origin.getZ()) > maxDist) continue;
            if (level.isOutsideBuildHeight(n)) continue;
            if (!seen.add(n.asLong())) continue;
            if (!level.hasChunk(n.getX() >> 4, n.getZ() >> 4)) { // never loads chunks
                if (touched != null) touched.add(net.minecraft.world.level.ChunkPos.pack(n));
                continue;
            }
            queue.add(n);
        }
    }

    /** The other half of a double chest, or null. */
    static BlockPos partner(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE)) return null;
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
        if (!level.hasChunk(other.getX() >> 4, other.getZ() >> 4)) return other;   // never loads chunks
        return level.getBlockState(other).is(state.getBlock()) ? other : null;
    }

    /** Storage the viewer may use through a terminal. */
    static boolean eligible(Feather plugin, ServerLevel level, BlockPos b, ServerPlayer viewer) {
        if (plugin.isBankBlock(level, b)) return false;
        Hooks.Mailboxes mail = plugin.service(Hooks.Mailboxes.class);
        if (mail != null && mail.isMailbox(level, b)) return false;
        if (viewer == null) return true;
        if (plugin.lockedAgainst(level, b, viewer.getUUID())) return false;
        return plugin.claimAllows(viewer, Loc.of(level, b), ClaimAction.CONTAINER);
    }

    // ------------------------------------------------------------------ reading

    static boolean empty(ItemStack s) {
        return s == null || s.isEmpty();
    }

    static boolean similar(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    private static int storageSlots(Container c) {
        return c.getContainerSize();
    }

    /** Every kind of item with its total, in the order first found. */
    List<Entry> aggregate() {
        Map<Item, List<Entry>> byType = new IdentityHashMap<>();
        List<Entry> out = new ArrayList<>();
        for (Source s : sources) {
            Container c = s.container();
            if (c instanceof StorageVaultBE v) {
                // A vault holds more than a stack per kind: its real counts, not the slots hoppers see (read in place, no copies).
                for (VaultPool.Entry e : v.contents().list) if (e.count > 0) add(byType, out, e.proto, e.count);
                continue;
            }
            for (int i = 0; i < storageSlots(c); i++) {
                ItemStack it = c.getItem(i);
                if (!empty(it)) add(byType, out, it, it.getCount());
            }
        }
        return out;
    }

    private static void add(Map<Item, List<Entry>> byType, List<Entry> out, ItemStack it, long n) {
        List<Entry> list = byType.computeIfAbsent(it.getItem(), k -> new ArrayList<>(2));
        Entry found = null;
        for (Entry e : list) {
            if (similar(e.proto, it)) {
                found = e;
                break;
            }
        }
        if (found == null) {
            found = new Entry(it.copyWithCount(1));
            list.add(found);
            out.add(found);
        }
        found.total += n;
    }

    long count(ItemStack proto) {
        long n = 0;
        for (Source s : sources) {
            Container c = s.container();
            if (c instanceof StorageVaultBE v) {
                n += v.count(proto);
                continue;
            }
            for (int i = 0; i < storageSlots(c); i++) {
                ItemStack it = c.getItem(i);
                if (!empty(it) && similar(it, proto)) n += it.getCount();
            }
        }
        return n;
    }

    int usedSlots() {
        int n = 0;
        for (Source s : sources) {
            if (s.container() instanceof StorageVaultBE v) n += (int) Math.min(Integer.MAX_VALUE / 2, v.stacksUsed());
            else for (int i = 0; i < storageSlots(s.container()); i++) if (!empty(s.container().getItem(i))) n++;
        }
        return n;
    }

    int totalSlots() {
        int n = 0;
        for (Source s : sources) {
            // A vault takes any number of kinds: it counts as what it holds plus room for one more kind.
            if (s.container() instanceof StorageVaultBE v) n += (int) Math.min(Integer.MAX_VALUE / 2, v.stacksUsed() + (long) v.blocks() * StorageVaultBE.STACKS_PER_BLOCK);
            else n += storageSlots(s.container());
        }
        return n;
    }

    // ------------------------------------------------------------------ changing

    /** Takes up to {@code amount} items like {@code proto} out of the chests; returns how many were taken. */
    int extract(ItemStack proto, int amount) {
        Object prev = StorageLog.push(actor != null ? actor : StorageLog.AUTOMATION);
        try {
            return extractAs(proto, amount);
        } finally {
            StorageLog.pop(prev);
        }
    }

    private int extractAs(ItemStack proto, int amount) {
        int left = amount;
        // Farthest first: what is kept near the terminal stays the longest.
        for (int i = sources.size() - 1; i >= 0 && left > 0; i--) {
            Container c = sources.get(i).container();
            if (c instanceof StorageVaultBE v) {
                left -= v.extract(proto, left);
                continue;
            }
            boolean changed = false;
            for (int slot = 0; slot < storageSlots(c) && left > 0; slot++) {
                ItemStack it = c.getItem(slot);
                if (empty(it) || !similar(it, proto)) continue;
                int take = Math.min(left, it.getCount());
                left -= take;
                c.setItem(slot, take >= it.getCount() ? ItemStack.EMPTY : it.copyWithCount(it.getCount() - take));
                changed = true;
            }
            if (changed) c.setChanged();
        }
        return amount - left;
    }

    private static boolean accepts(Source s, ItemStack item) {
        // Vanilla never lets a shulker box (or anything else that can't nest) into a shulker box.
        return !s.shulker() || item.getItem().canFitInsideContainerItems();
    }

    private static int max(Source s, ItemStack item) {
        return Math.max(1, Math.min(item.getMaxStackSize(), s.container().getMaxStackSize(item)));
    }

    /**
     * Puts the item into the network: first topping up matching stacks, then into empty slots, nearest storage first.
     * Returns what didn't fit (null when everything did). Any amount works (also more than a stack).
     */
    ItemStack insert(ItemStack item) {
        Object prev = StorageLog.push(actor != null ? actor : StorageLog.AUTOMATION);
        try {
            return insertAs(item);
        } finally {
            StorageLog.pop(prev);
        }
    }

    private ItemStack insertAs(ItemStack item) {
        if (empty(item)) return null;
        int left = item.getCount();
        for (Source s : sources) {
            if (left <= 0) break;
            if (!accepts(s, item)) continue;
            Container c = s.container();
            if (c instanceof StorageVaultBE v) {
                if (v.count(item) > 0) left -= v.insert(item, left);
                continue;
            }
            int max = max(s, item);
            boolean changed = false;
            for (int slot = 0; slot < storageSlots(c) && left > 0; slot++) {
                ItemStack it = c.getItem(slot);
                if (empty(it) || it.getCount() >= max || !similar(it, item)) continue;
                int add = Math.min(left, max - it.getCount());
                c.setItem(slot, it.copyWithCount(it.getCount() + add));
                left -= add;
                changed = true;
            }
            if (changed) c.setChanged();
        }
        for (Source s : sources) {
            if (left <= 0) break;
            if (!accepts(s, item)) continue;
            Container c = s.container();
            if (c instanceof StorageVaultBE v) {
                left -= v.insert(item, left);
                continue;
            }
            int max = max(s, item);
            boolean changed = false;
            for (int slot = 0; slot < storageSlots(c) && left > 0; slot++) {
                if (!empty(c.getItem(slot)) || !c.canPlaceItem(slot, item)) continue;
                int add = Math.min(left, max);
                c.setItem(slot, item.copyWithCount(add));
                left -= add;
                changed = true;
            }
            if (changed) c.setChanged();
        }
        if (left <= 0) return null;
        return item.copyWithCount(left);
    }

    /** How many of this item would fit right now. */
    long capacity(ItemStack item) {
        long n = 0;
        for (Source s : sources) {
            if (!accepts(s, item)) continue;
            if (s.container() instanceof StorageVaultBE v) {
                n += v.room(item);
                continue;
            }
            int max = max(s, item);
            Container c = s.container();
            for (int slot = 0; slot < storageSlots(c); slot++) {
                ItemStack it = c.getItem(slot);
                if (empty(it)) n += c.canPlaceItem(slot, item) ? max : 0;
                else if (it.getCount() < max && similar(it, item)) n += max - it.getCount();
            }
        }
        return n;
    }
}
