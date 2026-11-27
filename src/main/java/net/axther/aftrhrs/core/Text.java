package net.axther.aftrhrs.core;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * messages.yml: MiniMessage text with the palette tags ({@code <text>}, {@code <muted>}, ...) and {@code <prefix>},
 * plus the sound cues from config.yml {@code sounds:}. Same API as Feather's Text.
 */
public final class Text {
    public static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Feather plugin;
    private Yaml messages = new Yaml();
    private TagResolver palette = TagResolver.empty();
    private Component prefix = Component.empty();
    /** The palette tags and {@code <prefix>}, built once per reload so a parse needs no resolver builder. */
    private volatile Map<String, Tag> baseTags = Map.of();
    private final Map<String, Sound> sounds = new HashMap<>();

    Text(Feather plugin) {
        this.plugin = plugin;
    }

    void reload(Yaml config) {
        messages = plugin.loadResourceConfig("messages.yml");
        List<TagResolver> styles = new ArrayList<>();
        Map<String, Tag> base = new HashMap<>();
        // The bundled palette plus the server's own: a token added in an update works with an edited messages.yml too.
        Set<String> paletteKeys = new LinkedHashSet<>();
        Yaml def = messages.getDefaults();
        if (def != null) paletteKeys.addAll(def.section("palette").getKeys(false));
        paletteKeys.addAll(messages.section("palette").getKeys(false));
        for (String key : paletteKeys) {
            TextColor color = TextColor.fromHexString(messages.getString("palette." + key, "#ffffff"));
            if (color == null) {
                plugin.getLogger().warn("messages.yml palette.{} is not a #rrggbb color.", key);
                continue;
            }
            String name = key.toLowerCase(Locale.ROOT);
            styles.add(Placeholder.styling(name, color));
            base.put(name, Tag.styling(color));
        }
        palette = TagResolver.resolver(styles);
        prefix = MINI.deserialize(messages.getString("prefix", ""), palette);
        base.put("prefix", Tag.selfClosingInserting(prefix));
        baseTags = Map.copyOf(base);

        sounds.clear();
        Set<String> keys = new LinkedHashSet<>();
        Yaml snd = config.getConfigurationSection("sounds");
        if (snd != null) keys.addAll(snd.getKeys(false));
        for (String key : keys) {
            Sound parsed = parseSound(config.getString("sounds." + key, ""));
            if (parsed != null) sounds.put(key, parsed);
        }
    }

    public static Sound parseSound(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("none")) return null;
        String[] parts = spec.trim().split("\\s+");
        try {
            Key key = Key.key(parts[0].toLowerCase(Locale.ROOT));
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1f;
            return Sound.sound(key, Sound.Source.MASTER, volume, pitch);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Yaml messages() {
        return messages;
    }

    public Component parse(String mini, TagResolver... resolvers) {
        return MINI.deserialize(mini == null ? "" : mini, new Layered(baseTags, resolvers));
    }

    /**
     * The caller's resolvers over the palette and {@code <prefix>}: later resolvers win over earlier ones, and any of
     * them win over the base tags (the same order TagResolver.builder gave). Not a MappableResolver, so MiniMessage
     * keeps it as one resolver instead of copying the palette into a new map on every parse.
     */
    private static final class Layered implements TagResolver {
        private final Map<String, Tag> base;
        private final TagResolver[] extra;

        Layered(Map<String, Tag> base, TagResolver[] extra) {
            this.base = base;
            this.extra = extra;
        }

        @Override
        public boolean has(String name) {
            for (int i = extra.length - 1; i >= 0; i--) if (extra[i].has(name)) return true;
            return base.containsKey(name);
        }

        @Override
        public Tag resolve(String name, ArgumentQueue arguments, Context ctx) throws ParsingException {
            ParsingException thrown = null;
            for (int i = extra.length - 1; i >= 0; i--) {
                TagResolver r = extra[i];
                if (!r.has(name)) continue;
                try {
                    Tag tag = r.resolve(name, arguments, ctx);
                    if (tag != null) return tag;
                } catch (ParsingException e) {
                    if (thrown == null) thrown = e; // like SequentialTagResolver: try the rest first
                    arguments.reset();
                }
            }
            Tag tag = base.get(name);
            if (tag == null && thrown != null) throw thrown;
            return tag;
        }
    }

    public String raw(String key) {
        if (messages.isList(key)) return String.join("\n", messages.getStringList(key));
        String value = messages.getString(key);
        if (value == null) {
            plugin.getLogger().warn("messages.yml is missing '{}'.", key);
            return "<bad>" + key;
        }
        return value;
    }

    public boolean has(String key) {
        return messages.contains(key);
    }

    public Component msg(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    /**
     * {@link #msg} with {@code <prefix>} as nothing: a line that goes inside another message (a bullet of the
     * {@link JoinDigest}). The caller's resolvers still win over it.
     */
    public Component msgBare(String key, TagResolver... resolvers) {
        TagResolver[] all = new TagResolver[resolvers.length + 1];
        all[0] = Placeholder.component("prefix", Component.empty());
        System.arraycopy(resolvers, 0, all, 1, resolvers.length);
        return parse(raw(key), all);
    }

    /** The {@code prefix} of messages.yml, parsed. */
    public Component prefix() {
        return prefix;
    }

    public net.minecraft.network.chat.Component vanilla(String key, TagResolver... resolvers) {
        return Adv.vanilla(msg(key, resolvers));
    }

    public List<String> variants(String key) {
        if (messages.isList(key)) return messages.getStringList(key);
        String value = messages.getString(key);
        return value == null ? List.of() : List.of(value);
    }

    public List<Component> lines(String key, TagResolver... resolvers) {
        List<String> raw = messages.isList(key) ? messages.getStringList(key) : List.of(raw(key));
        List<Component> out = new ArrayList<>();
        for (String line : raw) out.add(item(line, resolvers));
        return out;
    }

    public Component item(String mini, TagResolver... resolvers) {
        return parse(mini, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public Component itemMsg(String key, TagResolver... resolvers) {
        return item(raw(key), resolvers);
    }

    public void send(Audience to, String key, TagResolver... resolvers) {
        String raw = raw(key);
        if (raw.isEmpty()) return;
        to.sendMessage(parse(raw, resolvers));
    }

    public void send(ServerPlayer to, String key, TagResolver... resolvers) {
        if (to != null) send(Adv.of(to), key, resolvers);
    }

    public void actionBar(Audience to, String key, TagResolver... resolvers) {
        String raw = raw(key);
        if (raw.isEmpty()) return;
        to.sendActionBar(parse(raw, resolvers));
    }

    public void actionBar(ServerPlayer to, String key, TagResolver... resolvers) {
        if (to != null) actionBar(Adv.of(to), key, resolvers);
    }

    public Component button(String key, String command, TagResolver... resolvers) {
        return msg(key + ".label", resolvers)
                .hoverEvent(HoverEvent.showText(msg(key + ".hover", resolvers)))
                .clickEvent(ClickEvent.runCommand(command));
    }

    public void sound(ServerPlayer player, String key) {
        Sound sound = sounds.get(key);
        if (sound != null && player != null) Adv.sound(player, sound);
    }

    public void sound(ServerPlayer player, String key, float pitchOverride) {
        Sound sound = sounds.get(key);
        if (sound != null && player != null) Adv.sound(player, Sound.sound(sound.name(), sound.source(), sound.volume(), pitchOverride));
    }

    public static TagResolver unparsed(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver component(String key, Component value) {
        return Placeholder.component(key, value);
    }
}
