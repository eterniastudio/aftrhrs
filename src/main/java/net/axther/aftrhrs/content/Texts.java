package net.axther.aftrhrs.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Content table texts: {@code {"key", "style"}} (a lang key with one style: name, lore line, title) or
 * {@code {"key", "segments": [{"text", "color", "bold", ...}]}} (multi-style text such as gradients).
 */
public final class Texts {
    private Texts() {
    }

    public static Component of(JsonObject t) {
        if (t == null) return Component.empty();
        if (t.has("segments")) {
            MutableComponent out = Component.empty();
            for (JsonElement e : t.getAsJsonArray("segments")) {
                JsonObject s = e.getAsJsonObject();
                out.append(Component.literal(s.get("text").getAsString()).withStyle(style(s)));
            }
            return out;
        }
        MutableComponent c = Component.translatable(t.get("key").getAsString());
        return t.has("style") ? c.withStyle(style(t.getAsJsonObject("style"))) : c;
    }

    /** Applies the style of a single-style text to another component (used for item names). */
    public static Component restyle(Component base, JsonObject t) {
        if (t == null) return base;
        if (t.has("segments")) return of(t);
        return t.has("style") ? base.copy().withStyle(style(t.getAsJsonObject("style"))) : base;
    }

    static Style style(JsonObject s) {
        Style st = Style.EMPTY;
        if (s.has("color")) {
            TextColor col = TextColor.parseColor(s.get("color").getAsString()).result().orElse(null);
            if (col != null) st = st.withColor(col);
        }
        if (s.has("bold")) st = st.withBold(s.get("bold").getAsBoolean());
        if (s.has("italic")) st = st.withItalic(s.get("italic").getAsBoolean());
        if (s.has("underlined")) st = st.withUnderlined(s.get("underlined").getAsBoolean());
        if (s.has("strikethrough")) st = st.withStrikethrough(s.get("strikethrough").getAsBoolean());
        if (s.has("obfuscated")) st = st.withObfuscated(s.get("obfuscated").getAsBoolean());
        return st;
    }
}
