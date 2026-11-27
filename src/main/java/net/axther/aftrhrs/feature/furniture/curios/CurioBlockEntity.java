package net.axther.aftrhrs.feature.furniture.curios;

import com.mojang.serialization.Codec;
import net.axther.aftrhrs.feature.furniture.FurnitureBE;
import net.axther.aftrhrs.feature.furniture.FurnitureEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * A curio piece's contents (what Paper kept in the Oraxen base entity's PDC): the pedestal's / statue's item or the
 * hat on a stand ({@code item}), the plants in a flower box, a doormat's lines and the item hidden under it. The
 * client draws them ({@code CurioRenderer}); a hat stand's wobble and a statue's wave are synced as counters/flags.
 */
public class CurioBlockEntity extends FurnitureBE {
    private ItemStack item = ItemStack.EMPTY;
    private ItemStack hidden = ItemStack.EMPTY;
    /** Plant item per flower-box slot ("" = empty), item ids like {@code minecraft:poppy}. */
    private final List<String> plants = new ArrayList<>();
    private final List<String> text = new ArrayList<>();
    private int wobble;
    private boolean waving;
    /** Set while the module swaps the piece (pedestal columns): nothing drops. */
    boolean silent;

    // client animation
    public long wobbleStart = -1000;
    /** Client only, never saved: the renderer's wrapped doormat text (it checks it still matches {@link #text()}). */
    public @org.jspecify.annotations.Nullable Object textRenderCache;
    private int seenWobble = -1;

    public CurioBlockEntity(BlockPos pos, BlockState state) {
        super(FurnitureEntities.CURIO, pos, state);
    }

    public ItemStack item() {
        return item;
    }

    public void setItem(ItemStack s) {
        item = s == null ? ItemStack.EMPTY : s;
        sync();
    }

    public ItemStack hidden() {
        return hidden;
    }

    public void setHidden(ItemStack s) {
        hidden = s == null ? ItemStack.EMPTY : s;
        setChanged();
    }

    public List<String> plants() {
        return plants;
    }

    /** The plant in a slot, or null. */
    public Item plant(int slot) {
        if (slot < 0 || slot >= plants.size() || plants.get(slot).isEmpty()) return null;
        Identifier id = Identifier.tryParse(plants.get(slot));
        Item i = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return i == Items.AIR ? null : i;
    }

    public void setPlant(int slot, Item item, int slots) {
        while (plants.size() < slots) plants.add("");
        if (slot >= 0 && slot < plants.size()) plants.set(slot, item == null ? "" : BuiltInRegistries.ITEM.getKey(item).toString());
        while (!plants.isEmpty() && plants.getLast().isEmpty()) plants.removeLast();
        sync();
    }

    public List<String> text() {
        return text;
    }

    public void setText(List<String> lines) {
        text.clear();
        text.addAll(lines);
        sync();
    }

    public void startWobble() {
        wobble++;
        sync();
    }

    public boolean waving() {
        return waving;
    }

    public void setWaving(boolean w) {
        if (waving == w) return;
        waving = w;
        sync();
    }

    /** Everything this piece holds, removed from it (for dropping or moving). */
    public List<ItemStack> takeContents() {
        List<ItemStack> out = new ArrayList<>();
        if (!item.isEmpty()) out.add(item);
        if (!hidden.isEmpty()) out.add(hidden);
        for (int i = 0; i < plants.size(); i++) {
            Item p = plant(i);
            if (p != null) out.add(new ItemStack(p));
        }
        item = ItemStack.EMPTY;
        hidden = ItemStack.EMPTY;
        plants.clear();
        setChanged();
        return out;
    }

    /** Copies contents to another curio (column swaps). */
    void copyTo(CurioBlockEntity o) {
        o.item = item.copy();
        o.hidden = hidden.copy();
        o.plants.clear();
        o.plants.addAll(plants);
        o.text.clear();
        o.text.addAll(text);
        o.sync();
    }

    @Override
    public void serverTick() {
        CuriosModule m = CuriosModule.active();
        if (m != null) m.tick(this);
    }

    @Override
    public void clientTick() {
        if (seenWobble == -1) seenWobble = wobble; // first sight of the piece: no wobble
        if (wobble != seenWobble) {
            seenWobble = wobble;
            if (level != null) wobbleStart = level.getGameTime();
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (silent || level == null || level.isClientSide()) return;
        for (ItemStack s : takeContents()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
        CuriosModule m = CuriosModule.active();
        if (m != null) m.removed(this);
    }

    /** Clients never learn what is hidden under a doormat. */
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        net.minecraft.nbt.CompoundTag t = super.getUpdateTag(registries);
        t.remove("Hidden");
        return t;
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        item = in.read("Item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        hidden = in.read("Hidden", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        plants.clear();
        plants.addAll(in.read("Plants", Codec.STRING.listOf()).orElse(List.of()));
        text.clear();
        text.addAll(in.read("Text", Codec.STRING.listOf()).orElse(List.of()));
        wobble = in.getIntOr("Wobble", 0);
        waving = in.getBooleanOr("Waving", false);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (!item.isEmpty()) out.store("Item", ItemStack.CODEC, item);
        if (!hidden.isEmpty()) out.store("Hidden", ItemStack.CODEC, hidden);
        if (!plants.isEmpty()) out.store("Plants", Codec.STRING.listOf(), List.copyOf(plants));
        if (!text.isEmpty()) out.store("Text", Codec.STRING.listOf(), List.copyOf(text));
        if (wobble != 0) out.putInt("Wobble", wobble);
        if (waving) out.putBoolean("Waving", true);
    }
}
