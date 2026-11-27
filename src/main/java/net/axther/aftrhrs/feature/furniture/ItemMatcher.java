package net.axther.aftrhrs.feature.furniture;

import net.axther.aftrhrs.content.ArcanaContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * An item filter read from curios.yml / gadgets.yml (port of the curios {@code Matcher}). Entries:
 * <ul>
 *   <li>{@code diamond_sword} / {@code minecraft:diamond_sword} - a vanilla item,</li>
 *   <li>{@code #minecraft:swords} - an item tag,</li>
 *   <li>{@code oraxen:arcana_desk_globe} / {@code oraxen:arcana_hat_*} - an ex-Oraxen id, {@code *} matching anything.</li>
 * </ul>
 * An ex-Oraxen item only matches {@code oraxen:} entries, so a hat made of paper never counts as paper.
 */
public final class ItemMatcher {
    private final Set<Item> items = new HashSet<>();
    private final List<TagKey<Item>> tags = new ArrayList<>();
    private final List<Pattern> oraxen = new ArrayList<>();

    public static ItemMatcher of(Collection<String> specs, String where, Consumer<String> warn) {
        ItemMatcher m = new ItemMatcher();
        if (specs == null) return m;
        for (String raw : specs) {
            String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (s.startsWith("oraxen:")) {
                m.oraxen.add(glob(s.substring(7)));
            } else if (s.startsWith("#")) {
                Identifier id = Identifier.tryParse(s.substring(1));
                if (id == null) warn.accept(where + ": bad item tag " + s);
                else m.tags.add(TagKey.create(Registries.ITEM, id));
            } else {
                Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
                Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null || item == Items.AIR) {
                    // an ex-Oraxen id written without its prefix
                    if (id != null && ArcanaContent.entry(id.getPath()) != null && !s.startsWith("minecraft:")) m.oraxen.add(glob(id.getPath()));
                    else warn.accept(where + ": unknown item " + s);
                } else {
                    m.items.add(item);
                }
            }
        }
        return m;
    }

    /** {@code arcana_*_pedestal} style pattern (only {@code *} is special). */
    public static Pattern glob(String s) {
        String[] parts = s.toLowerCase(Locale.ROOT).split("\\*", -1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append(".*");
            if (!parts[i].isEmpty()) b.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(b.toString());
    }

    public static boolean anyMatch(List<Pattern> patterns, String id) {
        if (id == null) return false;
        for (Pattern p : patterns) if (p.matcher(id).matches()) return true;
        return false;
    }

    public static List<Pattern> globs(Collection<String> specs) {
        List<Pattern> out = new ArrayList<>();
        if (specs != null) for (String s : specs) if (s != null && !s.isBlank()) out.add(glob(s.trim()));
        return out;
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = ArcanaContent.id(stack);
        if (id != null) return anyMatch(oraxen, id);
        if (items.contains(stack.getItem())) return true;
        for (TagKey<Item> t : tags) if (stack.is(t)) return true;
        return false;
    }

    public boolean isEmpty() {
        return items.isEmpty() && tags.isEmpty() && oraxen.isEmpty();
    }
}
