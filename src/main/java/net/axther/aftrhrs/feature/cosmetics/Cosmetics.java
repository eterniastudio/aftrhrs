package net.axther.aftrhrs.feature.cosmetics;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.gamerules.GameRules;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Cosmetic armor and the hat slot: what a player looks like, apart from what protects them.
 *
 * <ul>
 *   <li><b>Cosmetic armor</b> (head, chest, legs, feet): an item there is drawn instead of the real armor in that slot,
 *       for everyone. The real armor keeps protecting; nothing reads the cosmetic slots for attributes or enchantments.</li>
 *   <li><b>Hide</b> (one switch per piece): with the cosmetic slot empty, the real piece isn't drawn at all. An elytra
 *       always shows (gliding without wings would look broken).</li>
 *   <li><b>Hat</b>: any one item (what /hat accepts), drawn on the head over the helmet.</li>
 * </ul>
 *
 * <p><b>Where it lives.</b> The {@link #OUTFIT} attachment on the player: saved with the player file, synced to the
 * player and everyone tracking them (the client draws from it), copied to the respawned player (keepInventory keeps
 * it). Without keepInventory the death takes the items first: graves put them in the grave (slots
 * {@link #GRAVE_FIRST}..), and whatever is still worn when the loot drops falls with it ({@link #dropOnDeath}). The hide
 * switches always survive the death.
 *
 * <p><b>The slots.</b> Five {@link OutfitSlot}s follow the back slot in every player's {@code InventoryMenu} (indexes
 * {@link #MENU_FIRST}.., {@code InventoryMenuBackSlotMixin}), so drag and drop, shift-click and number keys work and the
 * server syncs them to the owner. One item each. Hidden in creative (vanilla's creative-slot packet stops at 45) and on
 * servers where the cosmetics module is off ({@link SettingsPayload}).
 */
public final class Cosmetics {
    /** Slot order in an {@link Outfit}, the menu and the grave: the hat, then the cosmetic armor head to feet. */
    public static final int HAT = 0, HEAD = 1, CHEST = 2, LEGS = 3, FEET = 4, SIZE = 5;
    /** The armor slot each cosmetic piece stands in for (index 0, the hat, has none). */
    public static final EquipmentSlot[] PIECE = {null, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** The first outfit slot in {@code InventoryMenu} (the back slot is 46). */
    public static final int MENU_FIRST = 47;
    /** A grave entry's slot for the outfit's first item (41 is the back; 0-40 are inventory slots). */
    public static final int GRAVE_FIRST = 42;
    /** Inventory-screen positions: the hat right of the player preview above the back slot; the armor on a side panel. */
    public static final int HAT_X = 77, HAT_Y = 26, ARMOR_X = -22;
    public static final int[] ARMOR_Y = {8, 26, 44, 62};

    /** Empty-slot silhouettes (gui atlas). */
    public static final Identifier HAT_ICON = AftrHrs.id("container/slot/hat");
    private static final Identifier[] ARMOR_ICONS = {Identifier.withDefaultNamespace("container/slot/helmet"),
            Identifier.withDefaultNamespace("container/slot/chestplate"), Identifier.withDefaultNamespace("container/slot/leggings"),
            Identifier.withDefaultNamespace("container/slot/boots")};

    /** What a player wears for looks: {@link #SIZE} items (EMPTY where none) and the hide switches (bit 0 head .. 3 feet). */
    public record Outfit(List<ItemStack> items, int hidden) {
        public static final Outfit EMPTY = new Outfit(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), 0);

        public static final Codec<Outfit> CODEC = RecordCodecBuilder.create(i -> i.group(
                ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(Outfit::items),
                Codec.INT.optionalFieldOf("hidden", 0).forGetter(Outfit::hidden)).apply(i, Outfit::of));

        public static final StreamCodec<RegistryFriendlyByteBuf, Outfit> STREAM_CODEC = StreamCodec.composite(
                ItemStack.OPTIONAL_LIST_STREAM_CODEC, Outfit::items, ByteBufCodecs.VAR_INT, Outfit::hidden, Outfit::of);

        /** Always {@link #SIZE} items (a short or long saved list is padded or cut). */
        static Outfit of(List<ItemStack> items, int hidden) {
            List<ItemStack> out = new ArrayList<>(SIZE);
            for (int i = 0; i < SIZE; i++) out.add(i < items.size() && items.get(i) != null ? items.get(i) : ItemStack.EMPTY);
            return new Outfit(List.copyOf(out), hidden & 0xF);
        }

        public ItemStack get(int i) {
            return items.get(i);
        }

        Outfit with(int i, ItemStack stack) {
            List<ItemStack> out = new ArrayList<>(items);
            out.set(i, stack == null ? ItemStack.EMPTY : stack);
            return new Outfit(List.copyOf(out), hidden);
        }

        Outfit withHidden(int hidden) {
            return new Outfit(items, hidden & 0xF);
        }

        /** Is the real armor of cosmetic piece {@code i} (1..4) hidden? */
        public boolean hides(int i) {
            return i >= HEAD && (hidden & (1 << (i - HEAD))) != 0;
        }

        boolean bare() {
            for (ItemStack s : items) if (!s.isEmpty()) return false;
            return true;
        }
    }

    public static final AttachmentType<Outfit> OUTFIT = AttachmentRegistry.create(AftrHrs.id("outfit"),
            b -> b.persistent(Outfit.CODEC).copyOnDeath().syncWith(Outfit.STREAM_CODEC, AttachmentSyncPredicate.all()));

    /** S2C: whether this server has the cosmetic slots (sent at join and when the module turns on or off). */
    public record SettingsPayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<SettingsPayload> TYPE = new Type<>(AftrHrs.id("cosmetics_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SettingsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, SettingsPayload::enabled, SettingsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: flip the hide switch of cosmetic piece {@code piece} (1 head .. 4 feet). */
    public record TogglePayload(int piece) implements CustomPacketPayload {
        public static final Type<TogglePayload> TYPE = new Type<>(AftrHrs.id("cosmetics_toggle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TogglePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TogglePayload::piece, TogglePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The cosmetics module is on (this server). */
    static volatile boolean serverEnabled;
    /** What the server told this client; off until it says, so other servers show no slots. */
    public static volatile boolean clientEnabled;
    /** Set by the inventory screen while the recipe book covers the side panel: the armor slots step aside. */
    public static volatile boolean clientPanelHidden;

    private Cosmetics() {
    }

    /** Registers the attachment and the payloads (mod init, both sides). */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TogglePayload.TYPE, TogglePayload.CODEC);
    }

    public static boolean enabled(Player p) {
        return p.level().isClientSide() ? clientEnabled : serverEnabled;
    }

    public static Outfit outfit(Player p) {
        Outfit o = p.getAttached(OUTFIT);
        return o == null ? Outfit.EMPTY : o;
    }

    /** Stores the outfit (a fresh record every change: that is what syncs it). */
    static void setOutfit(Player p, Outfit o) {
        if (o.bare() && o.hidden() == 0) p.removeAttached(OUTFIT);
        else p.setAttached(OUTFIT, o);
    }

    /** May {@code stack} go into outfit slot {@code i}? The hat takes any item; a cosmetic piece what fits that armor slot. */
    public static boolean fits(int i, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (i == HAT) return true;
        Equippable e = stack.get(DataComponents.EQUIPPABLE);
        return e != null && e.slot() == PIECE[i];
    }

    // ------------------------------------------------------------------ the hide switches (server)

    static void toggle(ServerPlayer p, int piece) {
        if (!serverEnabled || piece < HEAD || piece > FEET || !p.isAlive()) return;
        Outfit o = outfit(p);
        setOutfit(p, o.withHidden(o.hidden() ^ (1 << (piece - HEAD))));
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.6f,
                o.hides(piece) ? 1.2f : 0.9f);
    }

    // ------------------------------------------------------------------ death and graves

    /** The outfit items for a grave (copies; EMPTY where none), without Curse of Vanishing ones. */
    public static List<ItemStack> forGrave(ServerPlayer p) {
        List<ItemStack> out = new ArrayList<>(SIZE);
        Outfit o = outfit(p);
        for (int i = 0; i < SIZE; i++) {
            ItemStack s = o.get(i);
            out.add(s.isEmpty() || EnchantmentHelper.has(s, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP) ? ItemStack.EMPTY : s.copy());
        }
        return out;
    }

    /** Takes every outfit item off (they are in the grave or on the ground now); the hide switches stay. */
    public static void clearItems(Player p) {
        setOutfit(p, Outfit.of(List.of(), outfit(p).hidden()));
    }

    /** Puts {@code stack} back into outfit slot {@code i} if it fits and the slot is free (a grave's Take All). */
    public static boolean wear(ServerPlayer p, int i, ItemStack stack) {
        // one item per slot: a bigger stack (never one from the outfit) goes wherever it fits instead
        if (i < 0 || i >= SIZE || !serverEnabled || stack.getCount() != 1 || !fits(i, stack)) return false;
        Outfit o = outfit(p);
        if (!o.get(i).isEmpty()) return false;
        setOutfit(p, o.with(i, stack));
        return true;
    }

    /**
     * {@code Player.dropEquipment}, the death loot: without keepInventory what the outfit still holds (no grave took it)
     * drops with the inventory, or vanishes with Curse of Vanishing.
     */
    public static void dropOnDeath(Player player, ServerLevel level) {
        if (!(player instanceof ServerPlayer p) || level.getGameRules().get(GameRules.KEEP_INVENTORY)) return;
        Outfit o = outfit(p);
        if (o.bare()) return;
        clearItems(p);
        for (ItemStack s : o.items()) {
            if (s.isEmpty() || EnchantmentHelper.has(s, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) continue;
            ItemEntity drop = p.createItemStackToDrop(s, true, false);
            if (drop != null) level.addFreshEntity(drop);
        }
    }

    // ------------------------------------------------------------------ the inventory-screen slots

    /** The five outfit slots for {@code owner}'s inventory menu, in menu order. */
    public static List<Slot> slots(Player owner) {
        List<Slot> out = new ArrayList<>(SIZE);
        OutfitContainer c = new OutfitContainer(owner);
        out.add(new OutfitSlot(c, owner, HAT, HAT_X, HAT_Y));
        for (int i = HEAD; i <= FEET; i++) out.add(new OutfitSlot(c, owner, i, ARMOR_X, ARMOR_Y[i - HEAD]));
        return out;
    }

    /** A view of the {@link #OUTFIT} attachment (read on every call: a respawned player has its own). */
    static final class OutfitContainer implements Container {
        private final Player owner;

        OutfitContainer(Player owner) {
            this.owner = owner;
        }

        @Override
        public int getContainerSize() {
            return SIZE;
        }

        @Override
        public boolean isEmpty() {
            return outfit(owner).bare();
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot >= 0 && slot < SIZE ? outfit(owner).get(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            ItemStack s = getItem(slot);
            if (s.isEmpty() || count <= 0) return ItemStack.EMPTY;
            ItemStack out = s.split(count);
            setOutfit(owner, outfit(owner).with(slot, s.isEmpty() ? ItemStack.EMPTY : s));
            return out;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack s = getItem(slot);
            if (s.isEmpty()) return ItemStack.EMPTY;
            setOutfit(owner, outfit(owner).with(slot, ItemStack.EMPTY));
            return s;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (slot < 0 || slot >= SIZE) return;
            setOutfit(owner, outfit(owner).with(slot, stack));
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            // a stack changed in place (count): store a fresh record so it syncs
            if (!owner.level().isClientSide()) setOutfit(owner, Outfit.of(outfit(owner).items(), outfit(owner).hidden()));
        }

        @Override
        public boolean stillValid(Player player) {
            return player == owner;
        }

        @Override
        public void clearContent() {
            clearItems(owner);
        }
    }

    /** One outfit slot: one item that fits it, a silhouette when empty, only where the server has the cosmetics. */
    public static final class OutfitSlot extends Slot {
        private final Player owner;
        public final int index;

        OutfitSlot(Container c, Player owner, int index, int x, int y) {
            super(c, index, x, y);
            this.owner = owner;
            this.index = index;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return enabled(owner) && fits(index, stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            super.setByPlayer(stack, previous);
            if (owner instanceof ServerPlayer sp && !stack.isEmpty() && stack != previous) {
                sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.8f, 1.1f);
            }
        }

        /** Shown where the server has the cosmetics (or something is still in it), never in creative. */
        @Override
        public boolean isActive() {
            if (!owner.level().isClientSide()) return true;
            if (owner.hasInfiniteMaterials()) return false;
            if (index != HAT && clientPanelHidden) return false;
            return clientEnabled || hasItem();
        }

        @Override
        public @Nullable Identifier getNoItemIcon() {
            return index == HAT ? HAT_ICON : ARMOR_ICONS[index - HEAD];
        }
    }
}
