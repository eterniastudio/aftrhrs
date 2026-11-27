package net.axther.aftrhrs.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

/** A storage furniture's inventory (Oraxen {@code storage: {type: STORAGE}}): rows x 9 slots, title and sounds. */
public class FurnitureStorageBlockEntity extends RandomizableContainerBlockEntity {
    private NonNullList<ItemStack> items;
    private final ContainerOpenersCounter openers = new ContainerOpenersCounter() {
        @Override
        protected void onOpen(Level level, BlockPos pos, BlockState state) {
            FurnitureStorage.sound(level, pos, spec(), true);
            setOpen(state, true);
        }

        @Override
        protected void onClose(Level level, BlockPos pos, BlockState state) {
            FurnitureStorage.sound(level, pos, spec(), false);
            setOpen(state, false);
        }

        @Override
        protected void openerCountChanged(Level level, BlockPos pos, BlockState state, int previous, int current) {
        }

        @Override
        public boolean isOwnContainer(Player player) {
            return player.containerMenu instanceof ChestMenu m && m.getContainer() == FurnitureStorageBlockEntity.this;
        }
    };

    public FurnitureStorageBlockEntity(BlockPos pos, BlockState state) {
        super(ArcanaContent.STORAGE_BE, pos, state);
        this.items = NonNullList.withSize(size(state), ItemStack.EMPTY);
    }

    private static int size(BlockState state) {
        return state.getBlock() instanceof FurnitureBlock fb && fb.spec.storage != null ? fb.spec.storage.rows() * 9 : 27;
    }

    FurnitureSpec spec() {
        return ((FurnitureBlock) getBlockState().getBlock()).spec;
    }

    private void setOpen(BlockState state, boolean open) {
        if (level != null && state.hasProperty(FurnitureBlock.OPEN) && level.getBlockState(worldPosition).is(state.getBlock())) {
            level.setBlock(worldPosition, level.getBlockState(worldPosition).setValue(FurnitureBlock.OPEN, open), 3);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!trySaveLootTable(output)) ContainerHelper.saveAllItems(output, items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        if (!tryLoadLootTable(input)) ContainerHelper.loadAllItems(input, items);
    }

    @Override
    public int getContainerSize() {
        return size(getBlockState());
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return spec().storage != null ? Texts.of(spec().storage.title()) : Component.literal("Storage");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        ChestMenu m = FurnitureStorage.menu(id, inventory, this, spec().storage.rows());
        if (spec().storage.type() == FurnitureSpec.StorageType.SHULKER) FurnitureStorage.noNesting(m, getContainerSize());
        return m;
    }

    /** A sack keeps its contents in its item: like a shulker box it takes no other sack / shulker box (unbounded nesting). */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (spec().storage != null && spec().storage.type() == FurnitureSpec.StorageType.SHULKER && !stack.getItem().canFitInsideContainerItems()) return false;
        return super.canPlaceItem(slot, stack);
    }

    @Override
    public void startOpen(ContainerUser user) {
        if (!remove && !user.getLivingEntity().isSpectator()) {
            openers.incrementOpeners(user.getLivingEntity(), getLevel(), getBlockPos(), getBlockState(), user.getContainerInteractionRange());
        }
    }

    @Override
    public void stopOpen(ContainerUser user) {
        if (!remove && !user.getLivingEntity().isSpectator()) {
            openers.decrementOpeners(user.getLivingEntity(), getLevel(), getBlockPos(), getBlockState());
        }
    }

    @Override
    public List<ContainerUser> getEntitiesWithContainerOpen() {
        return openers.getEntitiesWithContainerOpen(getLevel(), getBlockPos());
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        // SHULKER storage keeps its contents in the dropped item (loot table copies minecraft:container)
        if (spec().storage == null || spec().storage.type() != FurnitureSpec.StorageType.SHULKER) super.preRemoveSideEffects(pos, state);
    }

    void recheckOpen() {
        if (!remove) openers.recheckOpeners(getLevel(), getBlockPos(), getBlockState());
    }

    /** Container view for other modules (e.g. the storage network). */
    public Container container() {
        return this;
    }
}
