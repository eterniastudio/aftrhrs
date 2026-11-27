package net.axther.aftrhrs.feature.christmas;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.feature.furniture.FurnitureBE;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * A gift box: nine slots, and once wrapped (the piece's {@code open} state is false) who it is from, who it is for, a
 * note and the day it may be opened. The contents and the gift travel with the item when it is broken (the item's
 * {@code container} + {@code custom_data} components, see {@link #toItem()}); hoppers can't reach in.
 */
public class PresentBlockEntity extends FurnitureBE implements WorldlyContainer {
    public static final int SIZE = 9;
    private static final int[] NO_SLOTS = new int[0];
    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private @Nullable Gift gift;

    /** Who wrapped it for whom; {@code to} null = anyone may open it. {@code until}: epoch day it may be opened. */
    public record Gift(UUID from, String fromName, @Nullable UUID to, String toName, String note, long until) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("from", from.toString());
            t.putString("from_name", fromName);
            if (to != null) t.putString("to", to.toString());
            t.putString("to_name", toName);
            if (!note.isEmpty()) t.putString("note", note);
            if (until > 0) t.putLong("until", until);
            return t;
        }

        static @Nullable Gift load(CompoundTag t) {
            try {
                String f = t.getStringOr("from", "");
                if (f.isEmpty()) return null;
                String to = t.getStringOr("to", "");
                return new Gift(UUID.fromString(f), t.getStringOr("from_name", "?"), to.isEmpty() ? null : UUID.fromString(to),
                        t.getStringOr("to_name", ""), t.getStringOr("note", ""), t.getLongOr("until", 0L));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        public boolean forAnyone() {
            return to == null;
        }

        /** Days left before it may be opened (0 = now). */
        public long daysLeft() {
            return Math.max(0, until - LocalDate.now().toEpochDay());
        }
    }

    public PresentBlockEntity(BlockPos pos, BlockState state) {
        super(ChristmasContent.PRESENT, pos, state);
    }

    public @Nullable Gift gift() {
        return gift;
    }

    public boolean wrapped() {
        BlockState s = getBlockState();
        return s.hasProperty(FurnitureBlock.OPEN) && !s.getValue(FurnitureBlock.OPEN);
    }

    void setGift(@Nullable Gift g) {
        gift = g;
        setChanged();
    }

    /** What may go inside: no other presents or item containers (no gifts in gifts in gifts...). */
    public static boolean allowed(ItemStack s) {
        if (s.isEmpty()) return true;
        if (ChristmasContent.isPresent(ArcanaContent.id(s))) return false;
        if (!s.getItem().canFitInsideContainerItems()) return false;
        ItemContainerContents c = s.get(DataComponents.CONTAINER);
        if (c != null && c.nonEmptyItems().iterator().hasNext()) return false;
        return s.get(DataComponents.BUNDLE_CONTENTS) == null || s.get(DataComponents.BUNDLE_CONTENTS).isEmpty();
    }

    /** The present as an item (what breaking it drops): contents, gift and the wrapped look. */
    public ItemStack toItem() {
        ItemStack s = ArcanaContent.stack(ArcanaContent.id(getBlockState()), 1);
        if (s.isEmpty()) return s;
        s.applyComponents(collectComponents());
        if (wrapped()) s.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(FurnitureBlock.OPEN, false));
        return s;
    }

    public List<ItemStack> takeContents() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            if (!items.get(i).isEmpty()) out.add(items.get(i));
            items.set(i, ItemStack.EMPTY);
        }
        setChanged();
        return out;
    }

    /** Like a shulker box: the contents stay in the dropped item (the loot hook), never spill on removal. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
    }

    // ------------------------------------------------------------------ item components

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        components.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        CustomData cd = components.get(DataComponents.CUSTOM_DATA);
        gift = cd == null ? null : cd.copyTag().getCompound("gift").map(Gift::load).orElse(null);
        // the lore and hidden-tooltip components describe the gift: rebuilt from it, never kept as they are
        components.get(DataComponents.LORE);
        components.get(DataComponents.TOOLTIP_DISPLAY);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        boolean any = false;
        for (ItemStack s : items) any |= !s.isEmpty();
        if (any) components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        if (gift != null && wrapped()) {
            CompoundTag t = new CompoundTag();
            t.put("gift", gift.save());
            components.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
            components.set(DataComponents.LORE, new ItemLore(lore(gift)));
            // wrapped: the tooltip doesn't give away what's inside
            LinkedHashSet<net.minecraft.core.component.DataComponentType<?>> hidden = new LinkedHashSet<>();
            hidden.add(DataComponents.CONTAINER);
            components.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(false, hidden));
        }
    }

    static List<Component> lore(Gift g) {
        List<Component> l = new ArrayList<>();
        l.add(Component.translatableWithFallback("aftrhrs.christmas.lore.to", "To: %s",
                g.forAnyone() ? Component.translatableWithFallback("aftrhrs.christmas.anyone", "anyone") : Component.literal(g.toName()))
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        l.add(Component.translatableWithFallback("aftrhrs.christmas.lore.from", "From: %s", g.fromName())
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));
        if (!g.note().isEmpty()) l.add(Component.literal("“" + g.note() + "”").withStyle(s -> s.withColor(ChatFormatting.WHITE).withItalic(true)));
        if (g.daysLeft() > 0) {
            l.add(Component.translatableWithFallback("aftrhrs.christmas.lore.wait", "Don't open until Christmas!")
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withItalic(false)));
        }
        return l;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void removeComponentsFromTag(ValueOutput output) {
        output.discard("Items");
        output.discard("Gift");
    }

    // ------------------------------------------------------------------ saving

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in, items);
        gift = in.read("Gift", CompoundTag.CODEC).map(Gift::load).orElse(null);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        ContainerHelper.saveAllItems(out, items);
        if (gift != null) out.store("Gift", CompoundTag.CODEC, gift.save());
    }

    /** Clients only need the gift tag (who it's for), never the contents. */
    @Override
    public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.remove("Items");
        return t;
    }

    // ------------------------------------------------------------------ container

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SIZE ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack s = ContainerHelper.removeItem(items, slot, count);
        if (!s.isEmpty()) setChanged();
        return s;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) return;
        items.set(slot, stack);
        stack.limitSize(getMaxStackSize(stack));
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return !wrapped() && net.minecraft.world.Container.stillValidBlockEntity(this, player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !wrapped() && allowed(stack);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return NO_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }
}
