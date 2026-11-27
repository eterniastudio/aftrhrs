package net.axther.aftrhrs.feature.holograms;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns line content into what a viewer sees: text with Feather's placeholders, {@code {player}}-style
 * placeholders and {@code {anim:name}} animations; items (vanilla or ex-Oraxen content), heads and block states. Also
 * decides which lines are the same for everyone (one shared entity) and which change over time or per viewer.
 */
final class HoloContent {
    /** Feather placeholders that describe the viewer (see Placeholders): a line using one differs per player. */
    private static final Pattern VIEWER_TAG = Pattern.compile("<(name|username|prefix|suffix|group|world|ping|x|y|z|level|gamemode|hp|max-hp|health|hearts|playtime|afk|bounty)[>:]",
            Pattern.CASE_INSENSITIVE);
    /** Feather placeholders that change over time but are the same for everyone. */
    private static final Pattern TIMED_TAG = Pattern.compile("<(online|max|tps|mspt|clock|date)[>:]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURLY = Pattern.compile("\\{([a-zA-Z0-9_:-]+)}");
    /** Curly placeholders that are Feather placeholders by another name. */
    private static final Map<String, String> CURLY_TAGS = Map.ofEntries(Map.entry("player", "<username>"), Map.entry("nick", "<name>"),
            Map.entry("online", "<online>"), Map.entry("max", "<max>"), Map.entry("world", "<world>"), Map.entry("ping", "<ping>"),
            Map.entry("tps", "<tps>"), Map.entry("clock", "<clock>"), Map.entry("date", "<date>"), Map.entry("server", "<server>"),
            Map.entry("level", "<level>"), Map.entry("health", "<health>"), Map.entry("playtime", "<playtime>"));
    private static final Set<String> CURLY_VIEWER = Set.of("player", "nick", "world", "ping", "level", "health", "playtime");
    private static final Set<String> CURLY_VIEWER_LIVE = Set.of("world", "ping", "level", "health", "playtime");
    private static final Set<String> CURLY_TIMED = Set.of("online", "max", "tps", "clock", "date");

    /** A {@code {anim:name}} animation: its frames (MiniMessage) and how many ticks each shows. */
    record Animation(int interval, List<String> frames) {
        String frame(long tick) {
            return frames.isEmpty() ? "" : frames.get((int) ((tick / Math.max(1, interval)) % frames.size()));
        }
    }

    private final Feather plugin;
    final Map<String, Animation> animations = new HashMap<>();
    int placeholderTicks = 30;

    HoloContent(Feather plugin) {
        this.plugin = plugin;
    }

    void reload(Yaml c) {
        placeholderTicks = c == null ? 30 : Math.max(0, c.getInt("placeholder-refresh-ticks", 30));
        animations.clear();
        Yaml a = c == null ? null : c.getConfigurationSection("animations");
        if (a == null) return;
        for (String name : a.getKeys(false)) {
            Yaml s = a.getConfigurationSection(name);
            if (s == null) continue;
            int interval = Math.max(1, s.getInt("interval", 10));
            List<String> frames = new ArrayList<>(s.getStringList("frames"));
            String typed = s.getString("typewriter");
            if (typed != null && !typed.isEmpty()) {
                // One frame per letter, then the whole text for `hold` ticks, then a short empty gap.
                String style = s.getString("style", "");
                int hold = Math.max(0, s.getInt("hold", 40)) / interval;
                for (int i = 1; i <= typed.length(); i++) {
                    frames.add(style + MiniMessage.miniMessage().escapeTags(typed.substring(0, i)) + (i < typed.length() ? s.getString("cursor", "_") : ""));
                }
                for (int i = 0; i < hold; i++) frames.add(style + MiniMessage.miniMessage().escapeTags(typed));
            }
            if (!frames.isEmpty()) animations.put(name.toLowerCase(Locale.ROOT), new Animation(interval, frames));
        }
    }

    // ------------------------------------------------------------------ classifying lines

    /** True when the line looks different to each viewer ({player}, <name> ...). */
    boolean perViewer(HoloLine l) {
        String c = l.content();
        return switch (l.type()) {
            case TEXT -> c.indexOf('%') >= 0 || VIEWER_TAG.matcher(c).find() || hasCurly(c, CURLY_VIEWER);
            case HEAD, ITEM -> c.toLowerCase(Locale.ROOT).contains("{player}");
            default -> false;
        };
    }

    /** How often (ticks) a text line has to be redrawn: 0 when it never changes. */
    int refreshTicks(Hologram h, HoloLine l) {
        if (l.type() != HoloLine.Type.TEXT) return 0;
        String c = l.content();
        int best = 0;
        Matcher m = CURLY.matcher(c);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            if (key.startsWith("anim:")) {
                Animation a = animations.get(key.substring(5));
                if (a != null) best = min(best, a.interval());
            }
        }
        if (c.contains("<shimmer")) best = min(best, 4);
        if (livePlaceholders(h, c) && placeholderTicks > 0) best = min(best, placeholderTicks);
        return best;
    }

    /**
     * Whether the text holds placeholders whose value can change between redraws (timed, live viewer ones, %...%,
     * the hologram's resolver). {player} and {nick} alone never change; {@code <hp>}, {@code <x>} ... can. Shared by
     * {@link #refreshTicks} and {@link #cacheable} so the two cannot drift apart.
     */
    private static boolean livePlaceholders(Hologram h, String c) {
        return (h != null && h.resolver != null) || TIMED_TAG.matcher(c).find() || hasCurly(c, CURLY_TIMED)
                || c.indexOf('%') >= 0 || VIEWER_TAG.matcher(c).find() || hasCurly(c, CURLY_VIEWER_LIVE);
    }

    /**
     * True when a text line's rendering depends only on its expanded text (its page and animation frame): the same
     * for everyone, no live placeholders, no shimmer (tick-dependent), no resolver. Such lines can reuse a rendered
     * frame instead of parsing it again.
     */
    boolean cacheable(Hologram h, HoloLine l) {
        if (l.type() != HoloLine.Type.TEXT || h.resolver != null || perViewer(l)) return false;
        String c = l.content();
        return !c.contains("<shimmer") && !livePlaceholders(h, c);
    }

    /** Whether one expanded frame may be cached (an animation frame can bring placeholders of its own). */
    static boolean staticFrame(String expanded) {
        return !expanded.contains("<shimmer") && !livePlaceholders(null, expanded);
    }

    private static int min(int a, int b) {
        return a == 0 ? b : Math.min(a, b);
    }

    private static boolean hasCurly(String c, Set<String> keys) {
        Matcher m = CURLY.matcher(c);
        while (m.find()) if (keys.contains(m.group(1).toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // ------------------------------------------------------------------ text

    /** The text of a line as {@code viewer} sees it (null viewer: the shared text). */
    Component text(Hologram h, String raw, int page, int pages, ServerPlayer viewer, long tick) {
        return render(h, expand(raw, page, pages, tick), viewer);
    }

    /** A line's raw text with its {page}, {pages}, {anim:...} and curly placeholders filled in (no parsing yet). */
    String expand(String raw, int page, int pages, long tick) {
        return curly(raw, page, pages, tick);
    }

    /** Parses an expanded line (see {@link #expand}) with Feather's placeholders and the hologram's resolver. */
    Component render(Hologram h, String expanded, ServerPlayer viewer) {
        TagResolver extra = h.resolver == null ? TagResolver.empty() : h.resolver.apply(viewer);
        return plugin.placeholders().format(expanded, viewer, viewer, extra);
    }

    private String curly(String raw, int page, int pages, long tick) {
        if (raw.indexOf('{') < 0) return raw;
        Matcher m = CURLY.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String rep;
            if (key.equals("page")) rep = String.valueOf(page + 1);
            else if (key.equals("pages")) rep = String.valueOf(pages);
            else if (key.startsWith("anim:")) {
                Animation a = animations.get(key.substring(5));
                rep = a == null ? m.group() : a.frame(tick);
            } else rep = CURLY_TAGS.getOrDefault(key, m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** A line's text without its formatting (placeholders left as they are). */
    String plain(String raw) {
        try {
            return Adv.plain(plugin.text().parse(raw));
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /** Rough width of a text line in blocks (for the click box). */
    double width(String raw, double scale, int lineWidth) {
        String plain = plain(raw);
        int longest = 0;
        for (String row : plain.split("\n")) longest = Math.max(longest, row.length());
        double px = Math.min(lineWidth, longest * 6.0);
        return px * 0.025 * scale;
    }

    // ------------------------------------------------------------------ items, heads, blocks

    /** The item an {@code #ITEM:} spec shows, trying each {@code |} alternative; null if none resolves. */
    ItemStack item(String spec, ServerPlayer viewer) {
        for (String alt : spec.split("\\|")) {
            ItemStack it = oneItem(alt.strip(), viewer);
            if (it != null) return it;
        }
        return null;
    }

    private static ItemStack oneItem(String id, ServerPlayer viewer) {
        if (id.isEmpty()) return null;
        String low = id.toLowerCase(Locale.ROOT);
        if (low.startsWith("oraxen:")) {
            ItemStack it = ArcanaContent.stack(low.substring(7), 1);
            return it.isEmpty() ? null : it;
        }
        if (low.equals("{player}")) return viewer == null ? new ItemStack(Items.PLAYER_HEAD) : head(ResolvableProfile.createResolved(viewer.getGameProfile()));
        Item item = item(low);
        return item == null ? null : new ItemStack(item);
    }

    /** A registered item by id ({@code gold_nugget}, {@code minecraft:gold_nugget}, {@code aftrhrs:arcana_goob_coin}). */
    static Item item(String id) {
        Identifier key = Identifier.tryParse(id.toLowerCase(Locale.ROOT));
        if (key == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    /** The head a {@code #HEAD:} spec shows: a player (online, name, uuid), a texture (base64), {player} or an item. */
    ItemStack head(String spec, ServerPlayer viewer) {
        for (String alt : spec.split("\\|")) {
            String id = alt.strip();
            if (id.isEmpty()) continue;
            String low = id.toLowerCase(Locale.ROOT);
            boolean mobHead = (low.endsWith("_head") || low.endsWith("_skull")) && item(low) != null;
            if (low.startsWith("oraxen:") || low.startsWith("minecraft:") || mobHead) {
                ItemStack it = oneItem(id, viewer);
                if (it != null) return it;
                continue;
            }
            if (low.equals("{player}")) {
                if (viewer != null) return head(ResolvableProfile.createResolved(viewer.getGameProfile()));
                continue;
            }
            if (id.length() > 40 && id.matches("[A-Za-z0-9+/=]+")) {
                UUID uuid = UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                PropertyMap props = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", id)));
                return head(ResolvableProfile.createResolved(new GameProfile(uuid, "", props)));
            }
            UUID uuid = null;
            try {
                uuid = UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
            }
            if (uuid != null) {
                ServerPlayer online = plugin.player(uuid);
                return head(online != null ? ResolvableProfile.createResolved(online.getGameProfile()) : ResolvableProfile.createUnresolved(uuid));
            }
            if (id.matches("[A-Za-z0-9_]{1,16}")) {
                ServerPlayer online = Feather.server().getPlayerList().getPlayerByName(id);
                // A name the client looks up itself (its skin shows once Mojang answers).
                return head(online != null ? ResolvableProfile.createResolved(online.getGameProfile()) : ResolvableProfile.createUnresolved(id));
            }
        }
        return null;
    }

    private static ItemStack head(ResolvableProfile profile) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, profile);
        return head;
    }

    /** The block a {@code #BLOCK:} spec shows (a block id or full block state like {@code oak_stairs[facing=east]}), or null. */
    static BlockState block(String spec) {
        for (String alt : spec.split("\\|")) {
            String id = alt.strip().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            try {
                BlockState s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, id, false).blockState();
                if (s != null && !s.isAir()) return s;
            } catch (Exception ignored) {
                // not a block: try the next alternative
            }
            // ex-Oraxen blocks and furniture by their old id
            BlockState content = ArcanaContent.stateFor(id.startsWith("oraxen:") ? id.substring(7) : id);
            if (content != null) return content;
        }
        return null;
    }

    /** True when the spec names a mob Feather can show: a living, spawnable type or an ex-MythicMobs creature. */
    boolean entityKnown(String spec) {
        String id = spec.strip();
        if (id.toLowerCase(Locale.ROOT).startsWith("mythic:")) return ArcanaEntities.byMythicId(id.substring(7)) != null;
        return HoloRender.entityType(id) != null;
    }
}
