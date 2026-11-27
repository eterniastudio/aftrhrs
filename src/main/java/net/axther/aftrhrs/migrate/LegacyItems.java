package net.axther.aftrhrs.migrate;

import net.axther.aftrhrs.AftrHrs;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Oraxen items from the Paper server → native items. An Oraxen item was a vanilla base item (paper, leather horse
 * armor...) whose {@code custom_data.PublicBukkitValues} held {@code oraxen:id}. Every ItemStack built from saved data
 * passes through {@link #convert} (mixins on the public {@code ItemStack(Holder, int, DataComponentPatch)} constructor,
 * which every decode path uses, and on the ItemStackTemplate constructor), so inventories, chests, shulker boxes,
 * bundles, ender chests, dropped items and mail all convert lazily as they load. Copies (copy(), split) are not hooked:
 * a stack that only gains Paper-format custom_data after it was built is not converted.
 *
 * <p>Other Paper item tags (Feather's {@code feather:*} keys) move from {@code PublicBukkitValues} to the top level of
 * {@code custom_data} under the same names, which is where {@code Pdc.item*} reads them.
 */
public final class LegacyItems {
    public record Converted(Holder<Item> item, DataComponentPatch patch) {
    }

    private static final Map<String, String> RENAMED = new HashMap<>();
    private static final Map<String, java.util.function.Function<String, String>> TAGGED = new java.util.concurrent.ConcurrentHashMap<>();

    private LegacyItems() {
    }

    /** For ids that changed in the port (old Oraxen id → new registry path). */
    public static void rename(String oraxenId, String newPath) {
        RENAMED.put(oraxenId, newPath);
    }

    /**
     * Paper items that were plain vanilla items marked by a plugin tag (e.g. Feather's storage blocks: a chiseled
     * blackstone item with {@code feather:storage_item = terminal}): {@code toPath} maps the tag's value to the native
     * item's registry path (null: leave the item as it is). A converted item drops the tag and the name/lore the plugin
     * gave it (the native item has its own), like an Oraxen item.
     */
    public static void tagged(String key, java.util.function.Function<String, String> toPath) {
        TAGGED.put(key, toPath);
    }

    /** Cheap pre-check: does this custom_data hold Paper item data? */
    public static boolean isLegacy(CustomData cd) {
        return Legacy.ready() && cd != null
                && ((net.axther.aftrhrs.mixin.migrate.CustomDataAccessor) (Object) cd).aftrhrs$tag().contains("PublicBukkitValues");
    }

    /** Null when the patch carries no Paper data (the common, fast case). */
    public static Converted convert(Holder<Item> item, DataComponentPatch patch) {
        if (!Legacy.ready() || patch == null || patch.isEmpty()) return null;
        DataComponentPatch.SplitResult split = patch.split();
        CustomData cd = split.added().get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains("PublicBukkitValues")) return null;
        CompoundTag pbv = tag.getCompoundOrEmpty("PublicBukkitValues");
        tag.remove("PublicBukkitValues");
        String oraxen = pbv.getString("oraxen:id").orElse(null);
        pbv.remove("oraxen:id");
        // Oraxen storage contents (sacks) go to a key only this migration writes; placing the sack unpacks them
        if (pbv.get(OraxenStorage.KEY) instanceof net.minecraft.nbt.ByteArrayTag bytes && bytes.size() <= JavaSer.MAX_BYTES) {
            tag.put(OraxenStorage.MIGRATED, bytes.copy());
        }
        pbv.remove(OraxenStorage.KEY);
        boolean taggedItem = false;
        if (oraxen == null && !TAGGED.isEmpty()) {
            for (Map.Entry<String, java.util.function.Function<String, String>> t : TAGGED.entrySet()) {
                String value = pbv.getString(t.getKey()).orElse(null);
                String path = value == null ? null : t.getValue().apply(value);
                if (path == null) continue;
                oraxen = path;
                taggedItem = true;
                pbv.remove(t.getKey());
                break;
            }
        }
        if (oraxen == null) Legacy.count("item with Paper tags (moved to custom_data)");
        Holder<Item> target = item;
        DataComponentPatch.Builder b = DataComponentPatch.builder();
        boolean swapped = false;
        if (oraxen != null) {
            String path = RENAMED.getOrDefault(oraxen, oraxen);
            Identifier id = AftrHrs.id(path);
            Optional<Holder.Reference<Item>> found = BuiltInRegistries.ITEM.get(id);
            if (found.isPresent()) {
                target = found.get();
                swapped = true;
                Legacy.count(taggedItem ? "item from a Feather tag" : "item from Oraxen", path);
            } else {
                Legacy.count("item without a native match (kept as its base item)", oraxen);
                AftrHrs.LOG.warn("Oraxen item '{}' has no native item; kept as {}", oraxen, item.getRegisteredName());
                tag.putString("aftrhrs:missing_oraxen_id", oraxen);
            }
        }
        for (String k : pbv.keySet()) {
            // Oraxen's own markers (oraxen:furniture = 1 ...) mean nothing on the native item and would keep it from
            // stacking with fresh ones; kept only when the item could not be swapped
            if (swapped && k.startsWith("oraxen:")) continue;
            if (!tag.contains(k)) tag.put(k, pbv.get(k).copy());
        }
        // Oraxen wrote custom_name "" (or its display name again) on every item: not a player's rename
        net.minecraft.network.chat.Component oldName = split.added().get(DataComponents.ITEM_NAME);
        String oldNameText = oldName == null ? null : oldName.getString();
        for (net.minecraft.core.component.TypedDataComponent<?> c : split.added()) {
            var type = c.type();
            if (type == DataComponents.CUSTOM_DATA) continue;
            // The native item carries its own model, name and lore; a renamed item keeps its custom_name.
            if (swapped && (type == DataComponents.ITEM_MODEL || type == DataComponents.CUSTOM_MODEL_DATA
                    || type == DataComponents.ITEM_NAME || type == DataComponents.LORE)) continue;
            if (swapped && taggedItem && type == DataComponents.CUSTOM_NAME) continue;
            if (swapped && type == DataComponents.CUSTOM_NAME && c.value() instanceof net.minecraft.network.chat.Component cn) {
                String s = cn.getString();
                if (s.isBlank() || s.equals(oldNameText)) continue;
            }
            b.set(c);
        }
        for (var type : split.removed()) b.remove(type);
        if (!tag.isEmpty()) b.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return new Converted(target, b.build());
    }

}
