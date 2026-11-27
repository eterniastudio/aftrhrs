package net.axther.aftrhrs.feature.relics;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaItem;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.gamerules.GameRules;
import org.jetbrains.annotations.Nullable;

/**
 * A backpack worn on the back: one Arcana backpack per player, out of the inventory, opened from the back with the
 * Equip/Open Backpack key.
 *
 * <p><b>Where it lives.</b> The {@link #WORN} data attachment on the player: saved with the player file, the same
 * object through dimension changes, copied by the End exit and (copy-on-death) to the respawned player. That copy is
 * what makes keepInventory keep it; without keepInventory the death has already taken it off: graves put it into the
 * grave at the start of the death ({@code Hooks.BackSlot}, slot {@link #GRAVE_SLOT}), and whatever is still on the
 * back when the loot drops falls with the inventory ({@link #dropOnDeath}, from {@code PlayerBackpackDropMixin}). So
 * it is in exactly one place: on the back, in the grave, or on the ground.
 *
 * <p><b>Dupe guards.</b> The worn stack only ever moves (never copied): equip / unequip swap the very object between an
 * inventory slot and the attachment, and the menu slot's moves split it (the old object is left empty). The back only
 * changes with no other screen open ({@link #free}), so an open backpack screen (from the back or from a hand) can't
 * have its stack swapped from under it; a backpack opened from the back stays usable only while that very stack is
 * still worn, every click checks it ({@code ArcanaItem.BackpackMenu}), and the death closes it.
 *
 * <p><b>The slot.</b> {@link BackSlot} is appended to every player's {@code InventoryMenu} (index {@link #MENU_SLOT},
 * {@code InventoryMenuBackSlotMixin}), on both sides, so drag and drop, shift-click and number keys work like the
 * armor slots and the server syncs it to its owner like any other menu slot. Only backpacks fit. Vanilla's
 * creative-slot packet stops at 45, so the slot is hidden in creative (the key still works there).
 */
public final class BackpackWear {
    /** The worn backpack; absent when nothing is worn. */
    public static final AttachmentType<ItemStack> WORN = AttachmentRegistry.create(AftrHrs.id("worn_backpack"),
            b -> b.persistent(ItemStack.CODEC).copyOnDeath());

    /** The back slot's index in {@code InventoryMenu} (vanilla's last slot, the offhand, is 45). */
    public static final int MENU_SLOT = 46;
    /** A grave entry's slot for the back (0-35 storage, 36-39 armor, 40 offhand are inventory slots). */
    public static final int GRAVE_SLOT = 41;
    /** The slot in the inventory screen: right of the player preview, above the offhand (77, 62). */
    public static final int SLOT_X = 77, SLOT_Y = 44;
    /** The empty back slot's silhouette (gui atlas). */
    public static final Identifier EMPTY_ICON = AftrHrs.id("container/slot/backpack");

    /** tools.yml {@code backpacks.wear} on this server (set by {@link RelicsModule#reload}). */
    static volatile boolean serverWear = true;
    /** What the server told this client ({@code BackSlotPayload}); off until it says, so other servers show no slot. */
    public static volatile boolean clientWear;
    /** The server's {@code backpacks.elytra: hide}. */
    public static volatile boolean clientHideWithElytra = true;

    public enum Result { DONE, DISABLED, BUSY, NOT_BACKPACK, NO_ROOM, NOTHING }

    private BackpackWear() {
    }

    public static void init() {
        // class-load registers the attachment type
    }

    /** May backpacks be put on (this side's setting)? */
    public static boolean wearEnabled(Player p) {
        return p.level().isClientSide() ? clientWear : serverWear;
    }

    /** The worn backpack, or EMPTY. */
    public static ItemStack worn(Player p) {
        ItemStack s = p.getAttached(WORN);
        return s == null || s.isEmpty() ? ItemStack.EMPTY : s;
    }

    static void setWorn(Player p, ItemStack s) {
        if (s == null || s.isEmpty()) p.removeAttached(WORN);
        else p.setAttached(WORN, s);
    }

    /** Takes the backpack off (returns it; EMPTY if none). */
    public static ItemStack take(Player p) {
        ItemStack s = worn(p);
        p.removeAttached(WORN);
        return s;
    }

    public static boolean isBackpack(ItemStack s) {
        return !s.isEmpty() && ArcanaItem.isBackpack(s);
    }

    /** May the back change now? Alive, not a spectator, and no screen but the inventory open (see the class doc). */
    static boolean free(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator() && !p.hasDisconnected() && p.containerMenu == p.inventoryMenu;
    }

    // ------------------------------------------------------------------ actions (server)

    /** Puts the backpack in inventory slot {@code invSlot} on the back; a backpack already worn takes its place. */
    public static Result equip(ServerPlayer p, int invSlot) {
        if (!serverWear) return Result.DISABLED;
        if (!free(p)) return Result.BUSY;
        Inventory inv = p.getInventory();
        if (invSlot < 0 || invSlot >= inv.getContainerSize()) return Result.NOT_BACKPACK;
        ItemStack s = inv.getItem(invSlot);
        if (!isBackpack(s)) return Result.NOT_BACKPACK;
        ItemStack old = worn(p);
        if (s.getCount() > 1) {
            // never stacked (max_stack_size 1), but if it were: one goes on, the rest stays
            ItemStack one = s.split(1);
            setWorn(p, one);
            if (!old.isEmpty()) inv.placeItemBackInInventory(old, net.minecraft.util.Prediction.SERVER_ONLY);
        } else {
            inv.setItem(invSlot, old);
            setWorn(p, s);
        }
        changed(p, true);
        return Result.DONE;
    }

    /** Takes the backpack off into {@code preferred} (if free) or the first free inventory slot. */
    public static Result unequip(ServerPlayer p, int preferred) {
        if (!free(p)) return Result.BUSY;
        ItemStack w = worn(p);
        if (w.isEmpty()) return Result.NOTHING;
        Inventory inv = p.getInventory();
        int slot = preferred >= 0 && preferred < Inventory.INVENTORY_SIZE && inv.getItem(preferred).isEmpty() ? preferred : inv.getFreeSlot();
        if (slot < 0) return Result.NO_ROOM;
        setWorn(p, ItemStack.EMPTY);
        inv.setItem(slot, w);
        changed(p, false);
        return Result.DONE;
    }

    /** Opens the worn backpack. */
    public static Result open(ServerPlayer p) {
        if (!free(p)) return Result.BUSY;
        ItemStack w = worn(p);
        if (w.isEmpty()) return Result.NOTHING;
        // Usable only while this very stack is still on this player's back.
        return ArcanaItem.openBackpack(p, w, pl -> pl.isAlive() && worn(pl) == w) ? Result.DONE : Result.NOTHING;
    }

    /**
     * Sneak + right-click with a backpack in {@code hand}: put it on (swapping with a worn one). False when wearing is
     * off, so the click opens the backpack as before.
     */
    public static boolean sneakEquip(ServerPlayer p, InteractionHand hand) {
        if (!serverWear) return false;
        int slot = hand == InteractionHand.MAIN_HAND ? p.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
        tell(p, equip(p, slot));
        return true;
    }

    /** The Equip/Open Backpack key: open the worn one (sneak: take it off), else put one on (the held one first). */
    public static void key(ServerPlayer p, boolean sneak) {
        if (p.isSpectator() || !p.isAlive()) return;
        ItemStack w = worn(p);
        if (!w.isEmpty()) {
            if (sneak) {
                Result r = unequip(p, p.getInventory().getSelectedSlot());
                tell(p, r == Result.DONE ? null : r);
            } else {
                Result r = open(p);
                if (r != Result.DONE) tell(p, r);
            }
            return;
        }
        if (!serverWear) {
            tell(p, Result.DISABLED);
            return;
        }
        Inventory inv = p.getInventory();
        int slot = -1;
        if (isBackpack(inv.getSelectedItem())) slot = inv.getSelectedSlot();
        else if (isBackpack(inv.getItem(Inventory.SLOT_OFFHAND))) slot = Inventory.SLOT_OFFHAND;
        else for (int i = 0; i < Inventory.INVENTORY_SIZE && slot < 0; i++) if (isBackpack(inv.getItem(i))) slot = i;
        if (slot < 0) {
            tell(p, Result.NOTHING);
            return;
        }
        tell(p, equip(p, slot));
    }

    /**
     * Action-bar feedback (translated on the client; the key names come from the player's own bindings). {@code null}:
     * taken off.
     */
    private static void tell(ServerPlayer p, @Nullable Result r) {
        Component key = Component.keybind("key.aftrhrs.backpack");
        Component msg;
        if (r == null) msg = Component.translatable("aftrhrs.backpack.unequipped");
        else msg = switch (r) {
            case DONE -> Component.translatable("aftrhrs.backpack.equipped", key, key);
            case DISABLED -> Component.translatable("aftrhrs.backpack.disabled");
            case NO_ROOM -> Component.translatable("aftrhrs.backpack.no_room");
            case NOTHING -> Component.translatable("aftrhrs.backpack.none");
            case BUSY, NOT_BACKPACK -> null;
        };
        if (msg != null) p.sendSystemMessage(msg, true);
    }

    /** After the back changed: a sound for everyone near and the render sync right away. */
    static void changed(ServerPlayer p, boolean on) {
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1f, on ? 1f : 0.8f);
        RelicsModule m = RelicsModule.active;
        if (m != null) m.backpackChanged(p);
    }

    // ------------------------------------------------------------------ death

    /**
     * {@code Player.dropEquipment}, i.e. the death loot: without keepInventory a backpack still on the back (no grave
     * took it) drops with the inventory, or vanishes with Curse of Vanishing, like any other item.
     */
    public static void dropOnDeath(Player player, ServerLevel level) {
        if (!(player instanceof ServerPlayer p) || level.getGameRules().get(GameRules.KEEP_INVENTORY)) return;
        ItemStack w = take(p);
        if (w.isEmpty()) return;
        if (EnchantmentHelper.has(w, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) return;
        ItemEntity drop = p.createItemStackToDrop(w, true, false);
        if (drop != null) level.addFreshEntity(drop);
    }

    // ------------------------------------------------------------------ the inventory-screen slot

    /** The back slot for {@code owner}'s inventory menu. */
    public static Slot slot(Player owner) {
        return new BackSlot(owner);
    }

    /** One-slot view of the {@link #WORN} attachment (read on every call: a respawned player has its own). */
    static final class BackContainer implements Container {
        private final Player owner;

        BackContainer(Player owner) {
            this.owner = owner;
        }

        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return worn(owner).isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot == 0 ? worn(owner) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            ItemStack w = worn(owner);
            if (slot != 0 || w.isEmpty() || count <= 0) return ItemStack.EMPTY;
            ItemStack out = w.split(count);
            if (w.isEmpty()) setWorn(owner, ItemStack.EMPTY);
            setChanged();
            return out;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return slot == 0 ? take(owner) : ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack itemStack) {
            if (slot != 0) return;
            setWorn(owner, itemStack);
            setChanged();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            if (owner instanceof ServerPlayer sp) {
                RelicsModule m = RelicsModule.active;
                if (m != null) m.backpackChanged(sp);
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return player == owner;
        }

        @Override
        public void clearContent() {
            setWorn(owner, ItemStack.EMPTY);
        }
    }

    /** The back slot: backpacks only, one, with a backpack silhouette when empty. */
    public static final class BackSlot extends Slot {
        private final Player owner;

        BackSlot(Player owner) {
            super(new BackContainer(owner), 0, SLOT_X, SLOT_Y);
            this.owner = owner;
        }

        @Override
        public boolean mayPlace(ItemStack itemStack) {
            return isBackpack(itemStack) && wearEnabled(owner);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack itemStack) {
            return 1;
        }

        @Override
        public void setByPlayer(ItemStack itemStack, ItemStack previous) {
            super.setByPlayer(itemStack, previous);
            if (owner instanceof ServerPlayer sp && !itemStack.isEmpty() && itemStack != previous) {
                sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1f, 1f);
            }
        }

        /** Shown when the server allows wearing (or one is worn anyway), never in creative (see the class doc). */
        @Override
        public boolean isActive() {
            if (!owner.level().isClientSide()) return true;
            return (clientWear || hasItem()) && !owner.hasInfiniteMaterials();
        }

        @Override
        public @Nullable Identifier getNoItemIcon() {
            return EMPTY_ICON;
        }
    }

    /** The backpack as shown on the back to every client: one item, no contents (they stay private). */
    static ItemStack shown(ItemStack worn) {
        if (worn.isEmpty()) return ItemStack.EMPTY;
        ItemStack show = worn.copyWithCount(1);
        show.remove(DataComponents.CONTAINER);
        return show;
    }
}
