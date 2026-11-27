package net.axther.aftrhrs.feature.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One block of a Storage Vault. A vault is a box of vault blocks up to 3x3 across and any height ({@link VaultForm});
 * its contents live on the controller (the box's lowest corner) as a count per kind of item ({@link VaultPool}): any
 * number of kinds, each up to {@link #STACKS_PER_BLOCK} stacks per block of the vault. Every block answers as the
 * whole vault: hoppers, comparators and the storage network all reach the same contents through any block.
 */
public final class StorageVaultBE extends BlockEntity implements Container {
    /** Stacks of each kind of item one vault block adds room for. */
    public static final int STACKS_PER_BLOCK = 20;
    /** The save format: 2 = the pooled contents (1 had 20 slots per block). */
    private static final int FORMAT = 2;

    /** Bumped whenever any vault is reshaped or a vault block comes or goes: cached member lists check it. */
    static int shapes;

    /** The vault's contents when this block leads it; otherwise empty (anything left here moves to the controller). */
    final VaultPool pool = new VaultPool();
    /** The vault's controller (null: this block leads, or the vault isn't formed yet). */
    private BlockPos controller;
    /** The vault's size (on the controller): across X, up, across Z. */
    private int sizeX = 1, height = 1, sizeZ = 1;
    /** Who placed this block (null: placed by a command or a structure). Outside claims only one owner's blocks join. */
    private UUID owner;
    /** Saved by an older version (lying sideways, 20 slots of its own): re-formed once it is in a world. */
    private boolean legacy;
    /** Being broken: no other block treats it as their controller any more. */
    private boolean breaking;

    private StorageVaultBE controllerCache;
    private List<StorageVaultBE> group;
    private int groupShapes = -1;

    public StorageVaultBE(BlockPos pos, BlockState state) {
        super(StorageContent.VAULT_BE, pos, state);
    }

    // ------------------------------------------------------------------ the vault this block belongs to

    void setShape(BlockPos controller, int sizeX, int height, int sizeZ) {
        this.controller = controller.equals(worldPosition) ? null : controller.immutable();
        this.sizeX = sizeX;
        this.height = height;
        this.sizeZ = sizeZ;
        legacy = false;
        controllerCache = null;
        group = null;
        setChanged(level, worldPosition, getBlockState());
    }

    UUID owner() {
        return owner;
    }

    void setOwner(UUID owner) {
        this.owner = owner;
        setChanged(level, worldPosition, getBlockState());
    }

    public int sizeX() {
        return controllerBE().sizeX;
    }

    public int sizeZ() {
        return controllerBE().sizeZ;
    }

    public int height() {
        return controllerBE().height;
    }

    /** Blocks in the vault (its whole shape, loaded or not). */
    public int blocks() {
        StorageVaultBE c = controllerBE();
        return c.sizeX * c.height * c.sizeZ;
    }

    /** How many of this kind of item the vault holds at most. */
    public long limit(ItemStack like) {
        return (long) blocks() * STACKS_PER_BLOCK * Math.max(1, like.getMaxStackSize());
    }

    /** The vault's controller (this block when it leads, isn't formed, or the controller isn't loaded). */
    StorageVaultBE controllerBE() {
        if (controller == null || level == null) return this;
        StorageVaultBE c = controllerCache;
        if (c == null || c.isRemoved() || c.level != level) {
            c = null;
            controllerCache = null;
            if (!level.isLoaded(controller)) return this;
            if (level.getBlockEntity(controller) instanceof StorageVaultBE be && be != this && !be.breaking) controllerCache = c = be;
            else return this;
        }
        if (c.breaking) {
            controllerCache = null;
            return this;
        }
        // Items put in while the controller wasn't loaded (this block stood in for it) join the vault's.
        if (!pool.isEmpty()) {
            c.pool.mergeFrom(pool);
            setChanged(level, worldPosition, getBlockState());
            setChanged(level, c.worldPosition, c.getBlockState());
        }
        return c;
    }

    /** Every loaded block of this vault, the controller first (asked of any block). */
    List<StorageVaultBE> group() {
        return controllerBE().members();
    }

    private List<StorageVaultBE> members() {
        if (group != null && groupShapes == shapes) return group;
        List<StorageVaultBE> out = new ArrayList<>();
        out.add(this);
        if (level != null) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < sizeX; x++) {
                    for (int z = 0; z < sizeZ; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        BlockPos p = worldPosition.offset(x, y, z);
                        if (!level.isLoaded(p)) continue;
                        if (level.getBlockEntity(p) instanceof StorageVaultBE m && worldPosition.equals(m.controller)) out.add(m);
                    }
                }
            }
        }
        group = List.copyOf(out);
        groupShapes = shapes;
        return group;
    }

    /** The vault's contents, up to date (any block). */
    VaultPool contents() {
        VaultPool p = controllerBE().pool;
        p.sync();
        return p;
    }

    // ------------------------------------------------------------------ for the storage network and the info line

    long count(ItemStack like) {
        return contents().count(like);
    }

    /** How many more of this kind fit. */
    long room(ItemStack like) {
        return Math.max(0, limit(like) - contents().count(like));
    }

    /** Puts up to {@code n} of this kind in (as many as fit); returns how many went in. */
    int insert(ItemStack like, int n) {
        int put = (int) Math.min(n, room(like));
        if (put <= 0) return 0;
        contents().add(like, put);
        touched();
        return put;
    }

    /** Takes up to {@code n} of this kind out; returns how many came out. */
    int extract(ItemStack like, int n) {
        int took = (int) contents().take(like, n);
        if (took > 0) touched();
        return took;
    }

    /** Every kind held, with its full count (a copy). */
    List<ItemStack> snapshot() {
        List<ItemStack> out = new ArrayList<>();
        for (VaultPool.Entry e : contents().list) if (e.count > 0) out.add(e.proto.copyWithCount((int) Math.min(Integer.MAX_VALUE, e.count)));
        return out;
    }

    int kinds() {
        return contents().kinds();
    }

    long stacksUsed() {
        return contents().stacks();
    }

    /** Comparator strength: by the fullest kind (15 once some kind is at the vault's limit). */
    int signal() {
        VaultPool p = contents();
        double fullest = 0;
        boolean any = false;
        for (VaultPool.Entry e : p.list) {
            if (e.count <= 0) continue;
            any = true;
            fullest = Math.max(fullest, e.count / (double) limit(e.proto));
        }
        return any ? Math.min(15, 1 + (int) Math.floor(Math.min(1, fullest) * 14)) : 0;
    }

    /** End of a tick that changed the vault (its scheduled block tick): kinds that ran out let go of their slots. */
    void settle() {
        controllerBE().pool.settle();
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        pool.clear();
        controller = in.read("Controller", BlockPos.CODEC).map(o -> worldPosition.offset(o)).filter(p -> !p.equals(worldPosition)).orElse(null);
        owner = in.read("Owner", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        if (in.getIntOr("Format", 1) < FORMAT) {
            // An older vault block: its own 20 slots become contents, and it re-forms (upright) once it is loaded.
            NonNullList<ItemStack> old = NonNullList.withSize(STACKS_PER_BLOCK, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(in, old);
            for (ItemStack s : old) if (!s.isEmpty()) pool.add(s, s.getCount());
            sizeX = height = sizeZ = 1;
            legacy = true;
        } else {
            sizeX = Math.max(1, Math.min(VaultForm.MAX_WIDTH, in.getIntOr("SizeX", 1)));
            sizeZ = Math.max(1, Math.min(VaultForm.MAX_WIDTH, in.getIntOr("SizeZ", 1)));
            height = Math.max(1, Math.min(4096, in.getIntOr("Height", 1)));
            for (ValueInput c : in.childrenListOrEmpty("Stored")) {
                ItemStack kind = c.read("Item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
                long n = c.getLongOr("Count", 0L);
                if (!kind.isEmpty() && n > 0) pool.add(kind, n);
            }
            legacy = false;
        }
        controllerCache = null;
        group = null;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        pool.sync();
        out.putInt("Format", FORMAT);
        if (owner != null) out.store("Owner", net.minecraft.core.UUIDUtil.CODEC, owner);
        if (controller != null) out.store("Controller", BlockPos.CODEC, controller.subtract(worldPosition));
        if (controller == null && sizeX * height * sizeZ > 1) {
            out.putInt("SizeX", sizeX);
            out.putInt("Height", height);
            out.putInt("SizeZ", sizeZ);
        }
        if (!pool.isEmpty()) {
            ValueOutput.ValueOutputList list = out.childrenList("Stored");
            for (VaultPool.Entry e : pool.list) {
                if (e.count <= 0) continue;
                ValueOutput c = list.addChild();
                c.store("Item", ItemStack.CODEC, e.proto);
                c.putLong("Count", e.count);
            }
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (legacy && level instanceof ServerLevel sl) {
            // Once the chunk is in (queued, never run now: this is called while the chunk is still loading): the old
            // (sideways) vault re-forms upright and its blocks' items gather at the new controller.
            var server = sl.getServer();
            server.schedule(new net.minecraft.server.TickTask(server.getTickCount() + 1, () -> {
                if (!isRemoved() && legacy && sl.isLoaded(worldPosition) && sl.getBlockEntity(worldPosition) == this) {
                    VaultForm.reform(sl, List.of(worldPosition));
                }
            }));
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        shapes++;
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        shapes++;
    }

    /**
     * Broken: the vault shrinks by one block, so of each kind only what no longer fits falls out here. If this block
     * led the vault, the rest goes to another of its blocks, and from there to the controller of what re-forms.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null || level.isClientSide()) return;
        StorageVaultBE lead = controllerBE();
        breaking = true;
        VaultPool p = lead.pool;
        p.sync();
        StorageVaultBE heir = null;
        if (lead == this) for (StorageVaultBE m : members()) if (m != this) {
            heir = m;
            break;
        }
        long left = lead == this ? (heir == null ? 0 : blocks() - 1L) : blocks() - 1L;
        List<ItemStack> drops = new ArrayList<>();
        for (VaultPool.Entry e : p.list) {
            long keep = left * STACKS_PER_BLOCK * e.max();
            long over = e.count - keep;
            if (over <= 0) continue;
            e.count = keep;
            while (over > 0) {
                int n = (int) Math.min(over, e.max());
                drops.add(e.proto.copyWithCount(n));
                over -= n;
            }
        }
        p.sync();
        if (lead == this && heir != null) {
            heir.pool.mergeFrom(p);
            setChanged(level, heir.worldPosition, heir.getBlockState());
        }
        for (ItemStack d : drops) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), d);
    }

    // ------------------------------------------------------------------ the whole vault as one Container

    /** The vault changed: the blocks save, comparators on any block of the vault update next tick, the log writes it down. */
    private void touched() {
        touched(true);
    }

    /**
     * {@code log}: the storage log writes the change down now, for whoever is acting now (a terminal's player, a
     * hopper). Not for a hopper's take ({@link #removeItem}): that may still be put back if the hopper has no room, and
     * the hopper's own {@code setChanged} after a take that worked writes it down. Not for {@link #setItem} either: the
     * {@code setChanged} that follows it (or never comes, for a simulation) decides.
     */
    private void touched(boolean log) {
        if (level == null) return;
        StorageVaultBE c = controllerBE();
        setChanged(level, c.worldPosition, c.getBlockState());
        StorageVaultBlock.comparatorsSoon(level, c.worldPosition);
        if (log) StorageLog.changed(c);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null) {
            controllerBE().pool.sync();
            StorageVaultBlock.comparatorsSoon(level, controllerBE().worldPosition);
        }
    }

    /** One slot per kind held (showing up to a stack of it), then one empty slot for a new kind. */
    @Override
    public int getContainerSize() {
        return contents().list.size() + 1;
    }

    @Override
    public boolean isEmpty() {
        return contents().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        VaultPool p = contents();
        return slot >= 0 && slot < p.list.size() ? p.list.get(slot).view : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack out = contents().removeShown(slot, count);
        if (!out.isEmpty()) touched(false);
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        VaultPool p = contents();
        return slot >= 0 && slot < p.list.size() ? p.removeShown(slot, p.list.get(slot).shown) : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        contents().setShown(slot, stack);
        // Not logged here: a Transfer API transaction writes (and on abort writes back) through setItem and calls
        // setChanged only on commit; a hopper's put or set-back also ends with setChanged, which the log then diffs.
        touched(false);
    }

    /** Room for the whole stack under that kind's limit (whatever slot it is offered to). */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !stack.isEmpty() && room(stack) >= stack.getCount();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        contents().clear();
        touched();
    }
}
