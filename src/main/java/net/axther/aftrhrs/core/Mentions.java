package net.axther.aftrhrs.core;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat mentions for the chat module (it creates one and calls {@link #enable}, {@link #reload}, {@link #disable}): finds who a line mentions ({@code @name}, {@code @nickname}, a unique
 * {@code @prefix} of 3+ letters, or a full username of 4+ letters as a whole word), highlights the mentioned player's
 * name in their own copy of the line, shows {@code @mentions} as clickable names to everyone else, and pings the
 * mentioned player (sound + action bar, with a cooldown per sender and target). Staff can use {@code @everyone} and
 * {@code @here}. Keeps every player's chat tab completions ({@code @} + Tab) up to date.
 *
 * <p>Vanished players are never matched, so a mention can't reveal them.
 */
public final class Mentions {
    public static final String EVERYONE_PERMISSION = "feather.chat.everyone";
    private static final String WORD = "[\\p{L}\\p{N}_]";
    /** A username matches without {@code @} only from this length (nicknames and shorter names always need it). */
    private static final int BARE_MIN = 4;

    private enum All { NONE, EVERYONE, HERE }

    /** One mention in a line: a player, or {@code @everyone}/{@code @here}; {@code at} when typed with {@code @}. */
    private record Hit(ServerPlayer player, All all, boolean at) {
    }

    /** What one chat line mentions, and how to draw it for each viewer. */
    public final class Scan {
        private final ServerPlayer sender;
        private final Pattern pattern;
        private final Map<String, ServerPlayer> byKey;
        /** Usernames only: what a bare word (no {@code @}) can match. */
        private final Map<String, ServerPlayer> byName;
        private final List<ServerPlayer> pool;
        private final boolean allowAll;
        private final Set<ServerPlayer> targets = new LinkedHashSet<>();
        private All all = All.NONE;

        private Scan(ServerPlayer sender, Pattern pattern, Map<String, ServerPlayer> byKey, Map<String, ServerPlayer> byName, List<ServerPlayer> pool,
                     boolean allowAll) {
            this.sender = sender;
            this.pattern = pattern;
            this.byKey = byKey;
            this.byName = byName;
            this.pool = pool;
            this.allowAll = allowAll;
        }

        /** The players this line mentions by name (not counting {@code @everyone}). */
        public Set<ServerPlayer> targets() {
            return targets;
        }

        public boolean any() {
            return !targets.isEmpty() || all != All.NONE;
        }

        private Hit resolve(MatchResult m) {
            String at = m.group(1);
            if (at != null) {
                String k = PlayerLookup.key(at);
                if (allowAll && k.equals("everyone")) return new Hit(null, All.EVERYONE, true);
                if (allowAll && k.equals("here")) return new Hit(null, All.HERE, true);
                ServerPlayer p = byKey.get(k);
                if (p == null && k.length() >= 3) p = uniquePrefix(k);
                return p == null ? null : new Hit(p, All.NONE, true);
            }
            String plain = m.group(2);
            if (plain == null) return null;
            ServerPlayer p = byName.get(PlayerLookup.key(plain));
            return p == null ? null : new Hit(p, All.NONE, false);
        }

        private ServerPlayer uniquePrefix(String k) {
            ServerPlayer found = null;
            for (ServerPlayer p : pool) {
                if (!Names.name(p).toLowerCase(Locale.ROOT).startsWith(k) && !PlayerLookup.key(PlayerLookup.nickPlain(p)).startsWith(k)) continue;
                if (found != null) return null;
                found = p;
            }
            return found;
        }

        /** The message as {@code viewer} sees it (null: someone who isn't mentioned, or the console). */
        public Component render(Component message, ServerPlayer viewer) {
            if (pattern == null) return message;
            Text text = plugin.text();
            return message.replaceText(TextReplacementConfig.builder().match(pattern).replacement((m, b) -> {
                Hit h = resolve(m);
                if (h == null) return b;
                String shown = m.group();
                if (h.all() != All.NONE || (viewer != null && h.player().equals(viewer))) {
                    return text.msg("chat.mention-highlight", Text.unparsed("mention", shown))
                            .hoverEvent(HoverEvent.showText(text.msg("chat.mention-highlight-hover",
                                    Text.component("sender", Names.display(sender)), Text.unparsed("username", Names.name(sender)))));
                }
                if (!h.at()) return b;
                ServerPlayer p = h.player();
                return text.msg("chat.mention-soft", Text.unparsed("mention", shown))
                        .hoverEvent(HoverEvent.showText(text.msg("chat.mention-soft-hover",
                                Text.component("name", Names.display(p)), Text.unparsed("username", Names.name(p)))))
                        .clickEvent(ClickEvent.suggestCommand("/msg " + Names.name(p) + " "));
            }).build());
        }
    }

    private final Feather plugin;
    private boolean enabled;
    private long cooldownMs;
    private boolean everyoneEnabled;
    private long everyoneCooldownMs;
    /** "sender>target" -> when the sender last pinged the target. */
    private final Map<String, Long> lastPing = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastEveryone = new ConcurrentHashMap<>();
    /** "sender>target" pairs already told the target is AFK (main thread only). */
    private final Set<String> afkNoted = new java.util.HashSet<>();
    /** What each player's client was last told to complete (main thread only). */
    private final Map<UUID, Set<String>> completions = new HashMap<>();
    private Tasks.Task ticker;
    private boolean active;
    private boolean refreshQueued;

    public Mentions(Feather plugin) {
        this.plugin = plugin;
    }

    /** The enabled instance the (once per JVM) join and quit listeners talk to. */
    private static volatile Mentions current;
    private static boolean hooked;

    public void enable() {
        active = true;
        current = this;
        reload();
        hook();
        // Also catches what no event reports: permission changes, finishing the auth gate.
        ticker = Tasks.timer(100L, 100L, this::tick);
    }

    /** Fabric events can't be unregistered: registered once, not on every enable (an integrated server reopening worlds). */
    private static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            Mentions m = current;
            if (m == null || !m.active) return;
            m.completions.remove(h.player.getUUID());
            m.queueRefresh();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            Mentions m = current;
            if (m == null || !m.active) return;
            String id = h.player.getUUID().toString();
            m.completions.remove(h.player.getUUID());
            m.afkNoted.removeIf(k -> k.contains(id));
            m.queueRefresh();
        });
    }

    public void disable() {
        active = false;
        if (current == this) current = null;
        if (ticker != null) ticker.cancel();
    }

    public void reload() {
        Yaml c = plugin.getConfig();
        enabled = c.getBoolean("chat.mentions", true);
        cooldownMs = Math.max(0, (long) (c.getDouble("chat.mention-cooldown-seconds", 3) * 1000));
        everyoneEnabled = c.getBoolean("chat.mention-everyone", true);
        everyoneCooldownMs = Math.max(0, (long) (c.getDouble("chat.mention-everyone-cooldown-seconds", 30) * 1000));
        queueRefresh();
    }

    private static boolean vanished(Feather plugin, ServerPlayer p) {
        Profiles.Profile pr = plugin.profiles().get(p);
        return pr != null && pr.vanished;
    }

    private boolean mayUseAll(ServerPlayer p) {
        return everyoneEnabled && Perms.has(p, EVERYONE_PERMISSION);
    }

    // ------------------------------------------------------------------ scanning

    /** Who the plain text of a line mentions. Safe off the main thread. Null when mentions are off. */
    public Scan scan(ServerPlayer sender, String plain) {
        if (!enabled) return null;
        // Longest first, so "Mirabel" wins over "Mira"; usernames win over nicknames.
        Map<String, ServerPlayer> byKey = new LinkedHashMap<>();
        Map<String, ServerPlayer> nicks = new LinkedHashMap<>();
        List<ServerPlayer> pool = new ArrayList<>();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (p.equals(sender) || vanished(plugin, p) || !plugin.canSee(sender, p)) continue;
            pool.add(p);
            byKey.putIfAbsent(PlayerLookup.key(Names.name(p)), p);
            String nick = PlayerLookup.nickPlain(p).trim();
            if (!nick.isEmpty()) nicks.putIfAbsent(PlayerLookup.key(nick), p);
        }
        Map<String, ServerPlayer> byName = new LinkedHashMap<>();
        for (ServerPlayer p : pool) {
            String k = PlayerLookup.key(Names.name(p));
            if (k.length() >= BARE_MIN) byName.putIfAbsent(k, p);
        }
        for (Map.Entry<String, ServerPlayer> e : nicks.entrySet()) byKey.putIfAbsent(e.getKey(), e.getValue());
        boolean allowAll = mayUseAll(sender);
        Pattern pattern = pattern(byKey.keySet(), byName.keySet(), allowAll);
        Scan scan = new Scan(sender, pattern, byKey, byName, pool, allowAll);
        if (pattern == null) return scan;
        Matcher m = pattern.matcher(plain);
        while (m.find()) {
            Hit h = scan.resolve(m);
            if (h == null) continue;
            if (h.all() == All.EVERYONE) scan.all = All.EVERYONE;
            else if (h.all() == All.HERE) {
                if (scan.all == All.NONE) scan.all = All.HERE;
            } else scan.targets.add(h.player());
        }
        return scan;
    }

    /**
     * Group 1: what follows an {@code @} (a name or nickname, everyone/here, or a prefix of 3+ letters). Group 2: a
     * full username without {@code @} ({@code bareKeys}, 4+ letters). Not inside words, e-mail addresses or links.
     */
    private static Pattern pattern(Collection<String> keys, Collection<String> bareKeys, boolean allowAll) {
        List<String> at = new ArrayList<>();
        for (String k : longestFirst(keys)) if (!k.isEmpty()) at.add(regex(k));
        List<String> plain = new ArrayList<>();
        for (String k : longestFirst(bareKeys)) if (k.length() >= BARE_MIN) plain.add(regex(k));
        if (allowAll) {
            at.add("everyone");
            at.add("here");
        }
        at.add(WORD + "{3,}");
        StringBuilder re = new StringBuilder("(?<![\\p{L}\\p{N}_@/.])(?:@(").append(String.join("|", at)).append(")");
        if (!plain.isEmpty()) re.append("|(").append(String.join("|", plain)).append(")");
        re.append(")(?!").append(WORD).append(")");
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static List<String> longestFirst(Collection<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    /** A name key as a pattern: {@code _} also matches a space. */
    private static String regex(String k) {
        StringBuilder re = new StringBuilder();
        for (String part : k.split("_", -1)) {
            if (re.length() > 0) re.append("[ _]");
            if (!part.isEmpty()) re.append(Pattern.quote(part));
        }
        return re.toString();
    }

    // ------------------------------------------------------------------ pinging

    /** Pings the mentioned players who see the line. Call from the chat thread; the work runs on the main thread. */
    public void ping(ServerPlayer sender, Scan scan, Set<ServerPlayer> viewers) {
        if (scan == null || !scan.any()) return;
        Tasks.sync(() -> {
            if (sender.hasDisconnected()) return;
            long now = System.currentTimeMillis();
            Text text = plugin.text();
            Set<ServerPlayer> pinged = new LinkedHashSet<>();
            for (ServerPlayer t : scan.targets()) {
                if (t.hasDisconnected() || !viewers.contains(t) || ignores(t, sender)) continue;
                String key = sender.getUUID() + ">" + t.getUUID();
                Long last = lastPing.get(key);
                if (last != null && now - last < cooldownMs) continue;
                lastPing.put(key, now);
                pinged.add(t);
                notify(t, sender);
                // One quiet note per AFK spell, not one per mention.
                if (plugin.isAfk(t)) {
                    if (afkNoted.add(key)) text.send(sender, "chat.mention-afk", Text.component("target", Names.display(t)));
                } else {
                    afkNoted.remove(key);
                }
            }
            if (scan.all == All.NONE) return;
            Long last = lastEveryone.get(sender.getUUID());
            if (last != null && now - last < everyoneCooldownMs) {
                long left = (everyoneCooldownMs - (now - last) + 999) / 1000;
                text.send(sender, "chat.mention-everyone-cooldown", Text.unparsed("seconds", left));
                return;
            }
            lastEveryone.put(sender.getUUID(), now);
            for (ServerPlayer v : viewers) {
                if (v.equals(sender) || pinged.contains(v) || v.hasDisconnected() || ignores(v, sender)) continue;
                if (scan.all == All.HERE && plugin.isAfk(v)) continue;
                notify(v, sender);
            }
        });
    }

    private boolean ignores(ServerPlayer receiver, ServerPlayer sender) {
        Hooks.Ignores ig = plugin.service(Hooks.Ignores.class);
        return ig != null && ig.ignores(receiver, sender);
    }

    /** The sound and the action bar, unless the player turned Mention Pings off. */
    private void notify(ServerPlayer target, ServerPlayer sender) {
        if (plugin.profiles().get(target).mentionsOff) return;
        plugin.text().sound(target, "mention");
        plugin.text().actionBar(target, "chat.mentioned", Text.component("sender", Names.display(sender)));
    }

    // ------------------------------------------------------------------ chat tab completion

    /** Recomputes everyone's {@code @} completions on the next tick (several changes in one tick count once). */
    public void queueRefresh() {
        if (refreshQueued || !active) return;
        refreshQueued = true;
        Tasks.sync(() -> {
            refreshQueued = false;
            refreshAll();
        });
    }

    private void tick() {
        refreshAll();
        long now = System.currentTimeMillis();
        long keep = Math.max(cooldownMs, 1000);
        lastPing.values().removeIf(t -> now - t > keep);
        lastEveryone.values().removeIf(t -> now - t > Math.max(everyoneCooldownMs, 1000));
    }

    private void refreshAll() {
        for (ServerPlayer viewer : plugin.onlinePlayers()) {
            Set<String> now = completionsFor(viewer);
            Set<String> before = completions.get(viewer.getUUID());
            if (now.equals(before)) continue;
            viewer.connection.send(new ClientboundCustomChatCompletionsPacket(ClientboundCustomChatCompletionsPacket.Action.SET, new ArrayList<>(now)));
            completions.put(viewer.getUUID(), now);
        }
    }

    private Set<String> completionsFor(ServerPlayer viewer) {
        Set<String> out = new LinkedHashSet<>();
        if (!enabled || plugin.authPending(viewer)) return out;
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (p.equals(viewer) || vanished(plugin, p) || !plugin.canSee(viewer, p)) continue;
            out.add("@" + Names.name(p));
            String nick = PlayerLookup.nickToken(p);
            if (nick != null) out.add("@" + nick);
        }
        if (mayUseAll(viewer)) {
            out.add("@everyone");
            out.add("@here");
        }
        return out;
    }
}
