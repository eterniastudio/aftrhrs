package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.content.ArcanaContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A list of items from wildlife.yml: vanilla ids ({@code wheat}), content ids ({@code arcana_truffle}) and globs
 * ({@code arcana_*_seeds}). A content (ex-Oraxen) item only ever matches by its id.
 */
final class Match {
    static final Match NONE = new Match(List.of());

    private final List<Pattern> patterns = new ArrayList<>();
    private final List<String> raw;

    Match(List<String> entries) {
        this.raw = entries;
        for (String s : entries) {
            if (s == null || s.isBlank()) continue;
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("minecraft:")) t = t.substring("minecraft:".length());
            StringBuilder re = new StringBuilder();
            for (char c : t.toCharArray()) re.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
            patterns.add(Pattern.compile(re.toString()));
        }
    }

    boolean isEmpty() {
        return patterns.isEmpty();
    }

    List<String> raw() {
        return raw;
    }

    /** The id a stack matches by: its content id, else the vanilla item path. Null for an empty stack. */
    static String key(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String oid = ArcanaContent.id(stack);
        return oid != null ? oid : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty() || patterns.isEmpty()) return false;
        return matches(key(stack));
    }

    boolean matches(String id) {
        if (id == null) return false;
        String s = id.toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        for (Pattern p : patterns) if (p.matcher(s).matches()) return true;
        return false;
    }

    boolean matches(Item item) {
        return item != null && matches(BuiltInRegistries.ITEM.getKey(item).getPath());
    }
}
