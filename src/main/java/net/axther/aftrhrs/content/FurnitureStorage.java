package net.axther.aftrhrs.content;

import net.axther.aftrhrs.core.Pdc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Opens storage furniture. STORAGE = the piece's own inventory (block entity), SHULKER = the same but the
 * contents stay in the dropped item; PERSONAL = one inventory per player,
 * shared by every personal piece (kept on the player); ENDERCHEST = the player's ender chest; DISPOSAL = a trash
 * can (whatever is left inside when closed is deleted).
 */
public final class FurnitureStorage {
    private static final String PERSONAL_KEY = "aftrhrs:personal_storage";

    private FurnitureStorage() {
    }

    static ChestMenu menu(int id, Inventory inv, Container c, int rows) {
        MenuType<ChestMenu> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };
        return new ChestMenu(type, id, inv, c, rows);
    }

    /** Swaps a chest menu's container slots for shulker-box slots (no sacks or shulker boxes inside). */
    static void noNesting(ChestMenu m, int size) {
        for (int i = 0; i < size && i < m.slots.size(); i++) {
            net.minecraft.world.inventory.Slot old = m.slots.get(i);
            net.minecraft.world.inventory.Slot s = new net.minecraft.world.inventory.ShulkerBoxSlot(old.container, old.getContainerSlot(), old.x, old.y);
            s.index = i;
            m.slots.set(i, s);
        }
    }

    static void sound(Level level, BlockPos pos, FurnitureSpec spec, boolean open) {
        if (spec.storage == null) return;
        String s = open ? spec.storage.openSound() : spec.storage.closeSound();
        if (s == null || s.isBlank()) return;
        Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
        if (id == null) return;
        level.playSound(null, pos, SoundEvent.createVariableRangeEvent(id), SoundSource.BLOCKS,
                spec.storage.volume(), spec.storage.pitch());
    }

    static void open(ServerPlayer player, FurnitureBlock block, BlockPos origin, BlockState state) {
        FurnitureSpec spec = block.spec;
        FurnitureSpec.Storage st = spec.storage;
        Component title = Texts.of(st.title());
        int rows = st.rows();
        switch (st.type()) {
            case STORAGE, SHULKER -> {
                if (player.level().getBlockEntity(origin) instanceof FurnitureStorageBlockEntity be) player.openMenu(be);
            }
            case ENDERCHEST -> {
                sound(player.level(), origin, spec, true);
                player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, player.getEnderChestInventory()), title));
            }
            case DISPOSAL -> {
                sound(player.level(), origin, spec, true);
                SimpleContainer trash = new SimpleContainer(rows * 9) {
                    @Override
                    public void stopOpen(ContainerUser user) {
                        super.stopOpen(user);
                        sound(player.level(), origin, spec, false);
                    }
                };
                player.openMenu(new SimpleMenuProvider((id, inv, p) -> menu(id, inv, trash, rows), title));
            }
            case PERSONAL -> {
                sound(player.level(), origin, spec, true);
                SimpleContainer personal = loadPersonal(player, rows * 9);
                SimpleContainer view = new SimpleContainer(rows * 9) {
                    @Override
                    public void stopOpen(ContainerUser user) {
                        super.stopOpen(user);
                        savePersonal(player, this);
                        sound(player.level(), origin, spec, false);
                    }
                };
                for (int i = 0; i < personal.getContainerSize(); i++) view.setItem(i, personal.getItem(i));
                player.openMenu(new SimpleMenuProvider((id, inv, p) -> menu(id, inv, view, rows), title));
            }
        }
    }

    private static SimpleContainer loadPersonal(ServerPlayer player, int size) {
        SimpleContainer c = new SimpleContainer(size);
        CompoundTag root = Pdc.of(player);
        if (!(root.get(PERSONAL_KEY) instanceof ListTag list)) return c;
        RegistryOps<Tag> ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (Tag t : list) {
            if (!(t instanceof CompoundTag ct)) continue;
            int slot = ct.getIntOr("Slot", -1);
            if (slot < 0 || slot >= size) continue;
            ItemStack.CODEC.parse(ops, ct.get("Item")).result().ifPresent(s -> c.setItem(slot, s));
        }
        return c;
    }

    private static void savePersonal(ServerPlayer player, SimpleContainer c) {
        RegistryOps<Tag> ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ListTag list = new ListTag();
        NonNullList<ItemStack> items = c.getItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            int slot = i;
            ItemStack.CODEC.encodeStart(ops, s).result().ifPresent(t -> {
                CompoundTag ct = new CompoundTag();
                ct.putInt("Slot", slot);
                ct.put("Item", t);
                list.add(ct);
            });
        }
        Pdc.putTag(player, PERSONAL_KEY, list);
    }
}
