package net.axther.aftrhrs.core;

import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/**
 * Persistent tags on entities, block entities, chunks and worlds (Bukkit's PersistentDataContainer), backed by a
 * Fabric data attachment holding one {@link CompoundTag}. Keys keep Feather's {@code namespace:key} names.
 *
 * <p>{@link #LEGACY} holds the {@code BukkitValues} a Paper server left on entities/chunks/block entities; a mixin
 * copies it in when the object loads, so the migration code can read old Feather/MythicMobs/Oraxen tags.
 */
public final class Pdc {
    public static final AttachmentType<CompoundTag> DATA = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(AftrHrs.ID, "pdc"), b -> b.persistent(CompoundTag.CODEC).initializer(CompoundTag::new).copyOnDeath());
    /** Paper's leftover BukkitValues / PublicBukkitValues / ChunkBukkitValues, read once by the migration. */
    public static final AttachmentType<CompoundTag> LEGACY = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(AftrHrs.ID, "bukkit_values"), b -> b.persistent(CompoundTag.CODEC));

    private Pdc() {
    }

    public static void init() {
        // class-load registers the attachment types
    }

    public static CompoundTag of(AttachmentTarget target) {
        return target.getAttachedOrCreate(DATA);
    }

    /** Call after changing the tag returned by {@link #of} (marks the holder dirty). */
    public static void changed(AttachmentTarget target, CompoundTag tag) {
        target.setAttached(DATA, tag);
    }

    public static boolean has(AttachmentTarget t, String key) {
        CompoundTag c = t.getAttached(DATA);
        return c != null && c.contains(key);
    }

    public static String getString(AttachmentTarget t, String key) {
        CompoundTag c = t.getAttached(DATA);
        return c == null ? null : c.getString(key).orElse(null);
    }

    public static void putString(AttachmentTarget t, String key, String value) {
        CompoundTag c = of(t).copy();
        if (value == null) c.remove(key);
        else c.putString(key, value);
        t.setAttached(DATA, c);
    }

    public static int getInt(AttachmentTarget t, String key, int def) {
        CompoundTag c = t.getAttached(DATA);
        return c == null ? def : c.getInt(key).orElse(def);
    }

    public static void putInt(AttachmentTarget t, String key, int value) {
        CompoundTag c = of(t).copy();
        c.putInt(key, value);
        t.setAttached(DATA, c);
    }

    public static long getLong(AttachmentTarget t, String key, long def) {
        CompoundTag c = t.getAttached(DATA);
        return c == null ? def : c.getLong(key).orElse(def);
    }

    public static void putLong(AttachmentTarget t, String key, long value) {
        CompoundTag c = of(t).copy();
        c.putLong(key, value);
        t.setAttached(DATA, c);
    }

    public static void putTag(AttachmentTarget t, String key, Tag value) {
        CompoundTag c = of(t).copy();
        if (value == null) c.remove(key);
        else c.put(key, value);
        t.setAttached(DATA, c);
    }

    public static void remove(AttachmentTarget t, String key) {
        CompoundTag c = t.getAttached(DATA);
        if (c == null || !c.contains(key)) return;
        c = c.copy();
        c.remove(key);
        t.setAttached(DATA, c);
    }

    public static UUID getUuid(AttachmentTarget t, String key) {
        String s = getString(t, key);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ items (custom_data component)

    public static CompoundTag item(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? new CompoundTag() : d.copyTag();
    }

    public static String itemString(ItemStack stack, String key) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        if (d == null) return null;
        return d.copyTag().getString(key).orElse(null);
    }

    public static void itemPut(ItemStack stack, String key, Tag value) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, c -> {
            if (value == null) c.remove(key);
            else c.put(key, value);
        });
    }

    public static void itemPutString(ItemStack stack, String key, String value) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, c -> {
            if (value == null) c.remove(key);
            else c.putString(key, value);
        });
    }

    /** A Paper item tag (custom_data.PublicBukkitValues["ns:key"]), for items carried over from the plugin server. */
    public static String legacyItemString(ItemStack stack, String key) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        if (d == null) return null;
        CompoundTag t = d.copyTag();
        return t.getCompound("PublicBukkitValues").flatMap(p -> p.getString(key)).orElse(null);
    }
}
