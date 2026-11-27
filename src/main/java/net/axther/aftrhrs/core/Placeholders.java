package net.axther.aftrhrs.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.GameType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The placeholders every Feather display text can use (tab list, nametags, server-list MOTD, announcements, info
 * commands), on top of the palette tags. Player placeholders describe the <em>subject</em> (whose nametag or tab
 * entry it is); {@code <online>} counts what the <em>viewer</em> can see. {@code <shimmer:#a:#b>text</shimmer>} is a
 * gradient whose phase moves with time, for animated titles. (PlaceholderAPI {@code %...%} codes were a Paper-only
 * extra and are not expanded.)
 */
public final class Placeholders {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern SHIMMER_OPEN = Pattern.compile("<shimmer(:[^>]*)?>");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Feather plugin;
    private LuckPermsHook luckPerms;
    private String serverName = "Aftr Hrs";
    private String heartFull = "<#ff6b81>❤";
    private String heartHalf = "<#ffb3c0>❤";
    private String heartEmpty = "<#4a4560>❤";
    private int heartsMax = 10;
    private long shimmerPeriod = 3000;
    private String afkTag = " <dim>AFK";

    Placeholders(Feather plugin) {
        this.plugin = plugin;
    }

    void reload(Yaml c) {
        serverName = c.getString("server-name", "Aftr Hrs");
        heartFull = c.getString("placeholders.hearts.full", heartFull);
        heartHalf = c.getString("placeholders.hearts.half", heartHalf);
        heartEmpty = c.getString("placeholders.hearts.empty", heartEmpty);
        heartsMax = Math.max(1, c.getInt("placeholders.hearts.max", 10));
        shimmerPeriod = Math.max(200, c.getLong("placeholders.shimmer-period-ms", 3000));
        afkTag = c.getString("placeholders.afk-tag", afkTag);
        if (luckPerms == null) luckPerms = LuckPermsHook.create();
    }

    public String serverName() {
        return serverName;
    }

    /** Prefix, suffix, group and weights from LuckPerms; null when LuckPerms isn't installed. */
    public LuckPermsHook luckPerms() {
        return luckPerms;
    }

    // ------------------------------------------------------------------ formatting

    /** Parses a display text for {@code subject} as seen by {@code viewer} (either may be null). */
    public Component format(String raw, ServerPlayer subject, ServerPlayer viewer, TagResolver... extra) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        TagResolver[] all = new TagResolver[extra.length + 1];
        all[0] = tags(subject, viewer);
        System.arraycopy(extra, 0, all, 1, extra.length);
        return plugin.text().parse(prepare(raw, subject), all);
    }

    /** The {@code <shimmer>} rewrite, done on the raw text before MiniMessage. */
    public String prepare(String raw, ServerPlayer subject) {
        String s = raw;
        if (s.contains("<shimmer")) {
            double phase = ((System.currentTimeMillis() % shimmerPeriod) / (double) shimmerPeriod) * 2 - 1;
            String ph = String.format(Locale.ROOT, "%.2f", phase);
            Matcher m = SHIMMER_OPEN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String args = m.group(1) == null ? ":#c3a6ff:#8fd3ff:#c3a6ff" : m.group(1);
                m.appendReplacement(sb, Matcher.quoteReplacement("<gradient" + args + ":" + ph + ">"));
            }
            m.appendTail(sb);
            s = sb.toString().replace("</shimmer>", "</gradient>");
        }
        return s;
    }

    /** Tags every text has. */
    static final Set<String> GLOBAL = Set.of("server", "online", "max", "tps", "mspt", "clock", "date");
    /** Tags about the subject; left as literal text when there is none. */
    static final Set<String> PLAYER = Set.of("name", "username", "prefix", "suffix", "group", "world", "ping", "x", "y", "z",
            "level", "gamemode", "hp", "max-hp", "health", "hearts", "playtime", "afk", "bounty");

    /**
     * All placeholders for a subject/viewer pair. Each value is worked out only if the text uses its tag: the tab list
     * and nametags format several lines per player many times a second, and building every value up front (the hearts
     * row and LuckPerms prefixes are MiniMessage parses, the online count asks canSee for every player) cost far more
     * than the few tags a template uses. One resolver dispatches on the tag name, so a format builds no resolver list.
     */
    public TagResolver tags(ServerPlayer subject, ServerPlayer viewer) {
        return new Ctx(subject, viewer);
    }

    private static double mspt(MinecraftServer server) {
        return server == null ? 0 : server.getAverageTickTimeNanos() / 1_000_000.0;
    }

    /** The placeholders of one subject/viewer pair; values are computed when (and each time) a tag is used. */
    private final class Ctx implements TagResolver {
        private final ServerPlayer subject;
        private final ServerPlayer viewer;
        private Rank rank;

        Ctx(ServerPlayer subject, ServerPlayer viewer) {
            this.subject = subject;
            this.viewer = viewer;
        }

        @Override
        public boolean has(String name) {
            return GLOBAL.contains(name) || (subject != null && PLAYER.contains(name));
        }

        @Override
        public Tag resolve(String name, ArgumentQueue arguments, Context ctx) {
            if (!has(name)) return null;
            return Tag.selfClosingInserting(value(name));
        }

        /** Prefix, suffix and group share one LuckPerms lookup per resolver. */
        private Rank rank() {
            if (rank == null) rank = new Rank(subject);
            return rank.get();
        }

        private Component value(String name) {
            ServerPlayer p = subject;
            return switch (name) {
                case "server" -> Component.text(serverName);
                case "online" -> {
                    int online = 0;
                    for (ServerPlayer o : plugin.onlinePlayers()) if (viewer == null || plugin.canSee(viewer, o)) online++;
                    yield text(online);
                }
                case "max" -> {
                    MinecraftServer server = Feather.serverOrNull();
                    yield text(server == null ? 0 : server.getPlayerList().getMaxPlayers());
                }
                case "tps" -> {
                    double mspt = mspt(Feather.serverOrNull());
                    yield text(String.format(Locale.ROOT, "%.1f", mspt <= 0 ? 20.0 : Math.min(20.0, 1000.0 / Math.max(50.0, mspt))));
                }
                case "mspt" -> text(String.format(Locale.ROOT, "%.1f", mspt(Feather.serverOrNull())));
                case "clock" -> text(CLOCK.format(LocalDateTime.now()));
                case "date" -> text(DATE.format(LocalDateTime.now()));
                case "name" -> Names.display(p);
                case "username" -> text(Names.name(p));
                case "prefix" -> rank().prefix;
                case "suffix" -> rank().suffix;
                case "group" -> text(rank().group);
                case "world" -> text(Worlds.name(p.level()));
                case "ping" -> text(p.connection == null ? 0 : p.connection.latency());
                case "x" -> text(p.getBlockX());
                case "y" -> text(p.getBlockY());
                case "z" -> text(p.getBlockZ());
                case "level" -> text(p.experienceLevel);
                case "gamemode" -> text(pretty(p.gameMode()));
                case "hp" -> text((int) Math.ceil(Math.max(0, p.getHealth())));
                case "max-hp" -> text((int) Math.ceil(p.getMaxHealth()));
                case "health" -> text(heartsNumber(Math.max(0, p.getHealth())));
                case "hearts" -> hearts(p);
                case "playtime" -> text(duration(p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 20L * 1000L));
                case "afk" -> plugin.isAfk(p) ? plugin.text().parse(afkTag) : Component.empty();
                case "bounty" -> {
                    Hooks.BountyTag bounty = plugin.service(Hooks.BountyTag.class);
                    yield bounty != null ? bounty.tag(p) : Component.empty();
                }
                default -> Component.empty();
            };
        }
    }

    private static Component text(Object value) {
        return Component.text(String.valueOf(value));
    }

    /** Prefix, suffix and group from LuckPerms, looked up once per formatted text and only if one of them is used. */
    private final class Rank {
        private final ServerPlayer p;
        private boolean done;
        private Component prefix = Component.empty(), suffix = Component.empty();
        private String group = "default";

        Rank(ServerPlayer p) {
            this.p = p;
        }

        Rank get() {
            if (done) return this;
            done = true;
            if (luckPerms == null) luckPerms = LuckPermsHook.create();   // LuckPerms may come up after Feather
            if (luckPerms != null) {
                try {
                    prefix = legacyOrMini(luckPerms.prefix(p));
                    suffix = legacyOrMini(luckPerms.suffix(p));
                    group = luckPerms.group(p);
                } catch (Throwable ignored) {
                }
            }
            return this;
        }
    }

    /** Health as a heart count: 20 HP = "10", 19 HP = "9.5". */
    public static String heartsNumber(double hp) {
        double hearts = Math.ceil(hp) / 2.0;
        return hearts == Math.floor(hearts) ? String.valueOf((long) hearts) : String.format(Locale.ROOT, "%.1f", hearts);
    }

    /** A row of heart symbols (full, half, empty) for the player's health, capped at {@code placeholders.hearts.max}. */
    public Component hearts(ServerPlayer p) {
        int halves = (int) Math.ceil(Math.max(0, p.getHealth()));
        int total = Math.min(heartsMax, (int) Math.ceil(p.getMaxHealth() / 2.0));
        int full = Math.min(total, halves / 2);
        int half = full < total && halves % 2 == 1 ? 1 : 0;
        int empty = Math.max(0, total - full - half);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < full; i++) sb.append(wrap(heartFull));
        if (half > 0) sb.append(wrap(heartHalf));
        for (int i = 0; i < empty; i++) sb.append(wrap(heartEmpty));
        return plugin.text().parse(sb.toString());
    }

    /** Closes a symbol's color so each heart keeps its own. */
    private static String wrap(String symbol) {
        return "<reset>" + symbol;
    }

    /** A LuckPerms-style prefix: &-codes or MiniMessage. */
    public static Component legacyOrMini(String s) {
        if (s == null || s.isEmpty()) return Component.empty();
        if (s.indexOf('&') >= 0 || s.indexOf('§') >= 0) return AMPERSAND.deserialize(s.replace('§', '&'));
        return MiniMessage.miniMessage().deserialize(s);
    }

    public static String pretty(GameType mode) {
        String n = mode.getName().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** "3d 4h", "5h 12m", "42m", "30s". */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    /** The longest timed punishment (100 years); anything longer is refused rather than made permanent. */
    public static final long MAX_DURATION = 100L * 365 * 86_400_000L;

    /**
     * Parses "30s", "10m", "2h", "7d", "1w" (or a bare number of minutes). -1 for "perm"/"forever"; 0 if unreadable,
     * zero, or so large it overflows (callers treat 0 as "not a duration", never as permanent).
     */
    public static long parseDuration(String s) {
        if (s == null || s.isEmpty()) return 0;
        String in = s.toLowerCase(Locale.ROOT);
        if (in.equals("perm") || in.equals("permanent") || in.equals("forever")) return -1;
        Matcher m = Pattern.compile("(\\d+)([smhdw]?)").matcher(in);
        long total = 0;
        int end = 0;
        try {
            while (m.find()) {
                if (m.start() != end) return 0;
                end = m.end();
                long n = Long.parseLong(m.group(1));
                long unit = switch (m.group(2)) {
                    case "s" -> 1000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    case "w" -> 604_800_000L;
                    default -> 60_000L;
                };
                total = Math.addExact(total, Math.multiplyExact(n, unit));
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return 0;
        }
        return end == in.length() ? total : 0;
    }

    /** True if the text is meant as a duration ("10m", "0", "-5m", "999999999w", "perm"), readable or not. */
    public static boolean looksLikeDuration(String s) {
        if (s == null || s.isEmpty()) return false;
        String in = s.toLowerCase(Locale.ROOT);
        if (in.equals("perm") || in.equals("permanent") || in.equals("forever")) return true;
        return in.matches("[-+]?\\d+[smhdw]?(\\d+[smhdw]?)*");
    }

    /**
     * A punishment length from a command argument: ms, -1 for permanent, or 0 when it is invalid (zero, negative,
     * overflowing, or longer than {@link #MAX_DURATION}). Never turns a bad value into a permanent one.
     */
    public static long punishmentDuration(String s) {
        if (s == null || s.startsWith("-") || s.startsWith("+")) return 0;
        long d = parseDuration(s);
        if (d < 0) return -1;
        return d > MAX_DURATION ? 0 : d;
    }
}
