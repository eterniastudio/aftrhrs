package net.axther.aftrhrs.feature.trade;

import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * The two-sided trade window (both sides of the network). Slots 0-15 are the viewer's own offer (a 4x4 grid on the
 * left, backed by their side's escrow container), 16-31 mirror the partner's offer (look only), then the player's 36
 * inventory slots. Three synced values: whether the viewer is Ready, whether the partner is, and the countdown
 * (-1 while none runs). The Ready and Cancel buttons are vanilla menu button clicks ({@link #BUTTON_READY},
 * {@link #BUTTON_CANCEL}); the server decides everything.
 *
 * <p>Item safety (the old chest window's rules): only plain moves between the offer, the cursor and the player's own
 * inventory go through; the mirror never moves; middle-click clone, Q-drop, dropping the cursor outside the window and
 * double-click collecting are refused; shift-clicks from the inventory only land in the offer.
 */
public final class TradeMenu extends AbstractContainerMenu {
    public static final int OFFER = 16;
    public static final int BUTTON_READY = 0, BUTTON_CANCEL = 1;
    public static final int DATA_MY_READY = 0, DATA_THEIR_READY = 1, DATA_COUNTDOWN = 2;
    /** Layout shared with the client screen. */
    public static final int WIDTH = 176, HEIGHT = 222, GRID_Y = 28, MY_X = 8, THEIR_X = 98, INV_Y = 140;
    /** Indexes into {@link Data#labels}. */
    public static final int L_YOU = 0, L_THEM = 1, L_READY = 2, L_UNREADY = 3, L_CANCEL = 4, L_STATE_READY = 5,
            L_STATE_WAITING = 6, L_HOW = 7, L_HOW_LORE = 8;

    /** What the client needs to draw the window: who the partner is and messages.yml's labels, rendered by the server. */
    public record Data(UUID partner, String partnerName, List<Component> labels) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Data::partner,
                ByteBufCodecs.STRING_UTF8, Data::partnerName,
                ComponentSerialization.TRUSTED_STREAM_CODEC.apply(ByteBufCodecs.list(64)), Data::labels,
                Data::new);

        public Component label(int i) {
            return i >= 0 && i < labels.size() ? labels.get(i) : Component.empty();
        }
    }

    public static ExtendedMenuType<TradeMenu, Data> TYPE;

    /** Registers the menu type (mod init: registries freeze before modules start). */
    public static void init() {
        if (TYPE != null) return;
        TYPE = Registry.register(BuiltInRegistries.MENU, AftrHrs.id("trade"), new ExtendedMenuType<>(TradeMenu::new, Data.CODEC));
    }

    private final Data data;
    private final ContainerData state;
    /** The server side's half of the trade; null on the client. */
    final Trade.Side side;

    /** Client side. */
    public TradeMenu(int id, Inventory inv, Data data) {
        this(id, inv, data, new SimpleContainer(OFFER), new SimpleContainer(OFFER), new SimpleContainerData(3), null);
    }

    TradeMenu(int id, Inventory inv, Data data, Container mine, Container theirs, ContainerData state, Trade.Side side) {
        super(TYPE, id);
        this.data = data;
        this.state = state;
        this.side = side;
        for (int i = 0; i < OFFER; i++) addSlot(new Slot(mine, i, MY_X + (i % 4) * 18, GRID_Y + (i / 4) * 18));
        for (int i = 0; i < OFFER; i++) addSlot(new MirrorSlot(theirs, i, THEIR_X + (i % 4) * 18, GRID_Y + (i / 4) * 18));
        addStandardInventorySlots(inv, 8, INV_Y);
        addDataSlots(state);
    }

    public Data data() {
        return data;
    }

    public boolean myReady() {
        return state.get(DATA_MY_READY) != 0;
    }

    public boolean theirReady() {
        return state.get(DATA_THEIR_READY) != 0;
    }

    public int countdown() {
        return state.get(DATA_COUNTDOWN);
    }

    static boolean isMirror(int slot) {
        return slot >= OFFER && slot < OFFER * 2;
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (side != null && side.trade().ended) return;
        if (input == ContainerInput.CLONE || input == ContainerInput.THROW || input == ContainerInput.PICKUP_ALL) return;
        // Dropping the cursor outside the window (drags use -999 too, as QUICK_CRAFT).
        if (slot == -999 && input != ContainerInput.QUICK_CRAFT) return;
        if (isMirror(slot)) return;
        super.clicked(slot, button, input, player);
        // The escrow file holds the cursor too: moving an item between the inventory and the cursor changes no offer
        // slot, so queue a save here, or a hard crash restores an item that is long back in the inventory (a dupe).
        if (side != null) side.trade().queuePersist();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || isMirror(index)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index < OFFER) {
            if (!moveItemStackTo(stack, OFFER * 2, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, OFFER, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
        return !(target instanceof MirrorSlot);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return !(slot instanceof MirrorSlot);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (side == null || !(player instanceof ServerPlayer sp)) return false;
        side.trade().button(side, sp, id);
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return side == null || !side.trade().ended;
    }

    @Override
    public void removed(Player player) {
        if (side != null && side.menu == this && player instanceof ServerPlayer sp) {
            // The trade keeps the cursor too, so vanilla never drops it; it comes back with the offer.
            ItemStack carried = getCarried();
            if (!carried.isEmpty()) {
                side.extra.add(carried.copy());
                setCarried(ItemStack.EMPTY);
            }
            super.removed(player);
            side.trade().closed(side, sp);
            return;
        }
        super.removed(player);
    }

    /** The partner's offer: never moves. */
    static final class MirrorSlot extends Slot {
        MirrorSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean allowModification(Player player) {
            return false;
        }
    }
}
