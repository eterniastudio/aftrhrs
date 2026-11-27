package net.axther.aftrhrs.feature.bank;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;

/**
 * Goob Coins: the native item {@code aftrhrs:<coin.oraxen-id>} (default {@code aftrhrs:arcana_goob_coin}, the ported
 * Oraxen coin), or, while that item is missing, a gold nugget carrying Feather's {@code feather:goob_coin} tag. A coin
 * is recognized only by its item id or that tag (old Oraxen coins convert to the native item as they load; an Oraxen
 * coin whose native item didn't exist keeps {@code aftrhrs:missing_oraxen_id}), never by name, so a renamed nugget is
 * worth nothing.
 *
 * <p>Paper also blocked crafting, smelting, brewing and anvils with coins and made dropped coins fire-proof. The
 * native coin is in no recipe or tag, so those uses don't exist for it; fire resistance belongs to the item itself.
 */
final class Coins {
    /** Paper's PDC key {@code feather:goob_coin}; LegacyItems moves it to the top level of custom_data. */
    static final String TAG = "feather:goob_coin";

    private final Feather plugin;
    private String oraxenId = "arcana_goob_coin";
    private Item fallbackMaterial = Items.GOLD_NUGGET;
    private Identifier fallbackModel;
    private boolean warnedMissing;

    Coins(Feather plugin) {
        this.plugin = plugin;
    }

    void reload(Yaml c) {
        oraxenId = c == null ? "arcana_goob_coin" : c.getString("oraxen-id", "arcana_goob_coin");
        String mat = c == null ? "gold_nugget" : c.getString("fallback-material", "gold_nugget");
        Identifier mid = Identifier.tryParse(mat == null ? "" : mat.toLowerCase(Locale.ROOT));
        Item m = mid == null ? null : BuiltInRegistries.ITEM.getOptional(mid).orElse(null);
        fallbackMaterial = m != null && m != Items.AIR ? m : Items.GOLD_NUGGET;
        String model = c == null ? "" : c.getString("fallback-model", "");
        fallbackModel = model == null || model.isBlank() ? null : Identifier.tryParse(model.toLowerCase(Locale.ROOT));
        warnedMissing = false;
    }

    /** The native coin item, or null while it isn't registered. */
    Item nativeItem() {
        if (oraxenId == null || oraxenId.isBlank()) return null;
        Identifier id = Identifier.tryParse(oraxenId.contains(":") ? oraxenId.toLowerCase(Locale.ROOT) : AftrHrs.ID + ":" + oraxenId.toLowerCase(Locale.ROOT));
        if (id == null) return null;
        Item it = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return it == Items.AIR ? null : it;
    }

    /** One coin. */
    ItemStack one() {
        Item it = nativeItem();
        if (it != null) return new ItemStack(it, 1);
        if (!warnedMissing) {
            warnedMissing = true;
            plugin.getLogger().warn("There is no item 'aftrhrs:{}'; the bank hands out tagged {}s as Goob Coins instead.",
                    oraxenId, BuiltInRegistries.ITEM.getKey(fallbackMaterial).getPath());
        }
        ItemStack s = new ItemStack(fallbackMaterial, 1);
        Text text = plugin.text();
        Icons.name(s, text.itemMsg("bank.coin.name"));
        Icons.lore(s, text.lines("bank.coin.lore"));
        Pdc.itemPut(s, TAG, ByteTag.valueOf((byte) 1));
        if (fallbackModel != null) s.set(DataComponents.ITEM_MODEL, fallbackModel);
        return s;
    }

    ItemStack stack(int count) {
        ItemStack s = one();
        s.setCount(Math.max(1, Math.min(s.getMaxStackSize(), count)));
        return s;
    }

    boolean isCoin(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        Item it = nativeItem();
        if (it != null && item.is(it)) return true;
        CustomData d = item.get(DataComponents.CUSTOM_DATA);
        if (d == null) return false;
        CompoundTag t = d.copyTag();
        if (t.contains(TAG)) return true;
        // An Oraxen coin carried over while the native item was missing, or not migrated yet.
        String missing = t.getString("aftrhrs:missing_oraxen_id").orElse(null);
        if (oraxenId != null && oraxenId.equals(missing)) return true;
        String legacy = Pdc.legacyItemString(item, "oraxen:id");
        return oraxenId != null && oraxenId.equals(legacy);
    }

    /** The player's storage slots (0-35) plus the off hand. */
    private static int[] slots() {
        int[] s = new int[Inventory.INVENTORY_SIZE + 1];
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) s[i] = i;
        s[Inventory.INVENTORY_SIZE] = Inventory.SLOT_OFFHAND;
        return s;
    }

    private static final int[] SLOTS = slots();

    /** Coins in the player's storage slots and off hand. */
    long count(ServerPlayer p) {
        Inventory inv = p.getInventory();
        long n = 0;
        for (int i : SLOTS) {
            ItemStack s = inv.getItem(i);
            if (isCoin(s)) n += s.getCount();
        }
        return n;
    }

    /** Takes exactly {@code amount} coins (caller checked {@link #count}); returns how many were taken. */
    long take(ServerPlayer p, long amount) {
        Inventory inv = p.getInventory();
        long left = amount;
        for (int i : SLOTS) {
            if (left <= 0) break;
            ItemStack s = inv.getItem(i);
            if (!isCoin(s)) continue;
            int use = (int) Math.min(left, s.getCount());
            left -= use;
            if (use >= s.getCount()) inv.setItem(i, ItemStack.EMPTY);
            else s.shrink(use);
        }
        inv.setChanged();
        return amount - left;
    }

    /** Puts {@code amount} coins in the inventory; returns how many didn't fit (never dropped). */
    long give(ServerPlayer p, long amount) {
        if (amount <= 0) return 0;
        // A dead player's inventory is thrown away at respawn: everything stays pending.
        if (p.isDeadOrDying() || p.isRemoved()) return amount;
        ItemStack proto = one();
        int stack = Math.max(1, proto.getMaxStackSize());
        // Creative's Inventory.add "takes" whatever doesn't fit (infinite materials): only hand out what has room.
        long room = room(p);
        long left = Math.min(amount, room);
        long over = amount - left;
        while (left > 0) {
            int n = (int) Math.min(stack, left);
            ItemStack s = proto.copyWithCount(n);
            p.getInventory().add(s);
            int notFit = s.getCount();
            left -= n - notFit;
            if (notFit > 0) break;
        }
        p.getInventory().setChanged();
        return left + over;
    }

    /** Free room for coins in the storage slots. */
    long room(ServerPlayer p) {
        ItemStack proto = one();
        int stack = Math.max(1, proto.getMaxStackSize());
        long room = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) room += stack;
            else if (ItemStack.isSameItemSameComponents(s, proto)) room += Math.max(0, stack - s.getCount());
        }
        return room;
    }
}
