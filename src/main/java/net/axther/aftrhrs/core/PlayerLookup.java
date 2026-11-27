package net.axther.aftrhrs.core;

import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds players from what someone typed: an exact username, then an exact nickname (plain text, any case, {@code _}
 * for spaces), then a unique prefix of either. Players a viewer can't see (vanish) never resolve for that viewer.
 * The offline variant also knows the usernames and nicknames saved in player files. One place for every command,
 * plus the shared tab completion of player names and nicknames. A null viewer is the console (sees everyone).
 */
public final class PlayerLookup {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    /** The outcome of a lookup: the player, or the players an ambiguous input could mean (empty when nobody). */
    public record Match(ServerPlayer player, List<ServerPlayer> candidates) {
        public boolean ambiguous() {
            return player == null && candidates.size() > 1;
        }
    }

    private static final Match NONE = new Match(null, List.of());

    private final Feather plugin;
    /** Normalized saved nickname -> player, from every player file (for offline lookups). */
    private final Map<String, UUID> savedNicks = new ConcurrentHashMap<>();
    private final Map<UUID, String> nickKeyOf = new ConcurrentHashMap<>();
    /** Lower-case username -> player, from player files and joins (Bukkit's offline-player cache). */
    private final Map<String, OfflinePlayer> names = new ConcurrentHashMap<>();
    /** The same players by id, for {@link #byId} (no scan of every known name). */
    private final Map<UUID, OfflinePlayer> byUuid = new ConcurrentHashMap<>();

    PlayerLookup(Feather plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads the saved names and nicknames of every player file, off the server thread, and tells {@link Profiles} when
     * each file was last written (the server goal counts active players from that, without its own folder scan).
     */
    void start() {
        Profiles profiles = plugin.profiles();
        Path folder = profiles.folder();
        int gen = profiles.writtenGeneration();
        if (!Files.isDirectory(folder)) {
            profiles.markWrittenIndexed(gen);
            return;
        }
        Tasks.async(() -> {
            try {
                Files.walkFileTree(folder, Set.of(), 1, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String n = file.getFileName().toString();
                        if (!attrs.isRegularFile() || !n.endsWith(".yml")) return FileVisitResult.CONTINUE;
                        try {
                            profiles.noteWalked(gen, UUID.fromString(n.substring(0, n.length() - 4)), attrs.lastModifiedTime().toMillis());
                        } catch (IllegalArgumentException e) {
                            return FileVisitResult.CONTINUE;
                        }
                        index(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                plugin.getLogger().warn("Could not list player files for name lookups: {}", e.toString());
            } finally {
                profiles.markWrittenIndexed(gen);
            }
        });
    }

    private void index(Path file) {
        String fileName = file.getFileName().toString();
        UUID id;
        try {
            id = UUID.fromString(fileName.substring(0, fileName.length() - 4));
        } catch (IllegalArgumentException e) {
            return;
        }
        try {
            // Only the name and nickname lines matter; the rest of the file isn't parsed.
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("name:")) {
                    String name = Yaml.fromString(line).getString("name");
                    if (name != null) {
                        // putIfAbsent: a join racing this startup index keeps its fresher name
                        OfflinePlayer op = new OfflinePlayer(id, name);
                        names.putIfAbsent(name.toLowerCase(Locale.ROOT), op);
                        byUuid.putIfAbsent(id, op);
                    }
                } else if (line.startsWith("nickname:") && !nickKeyOf.containsKey(id)) {
                    String raw = Yaml.fromString(line).getString("nickname");
                    if (raw != null) remember(id, raw);
                }
            }
        } catch (Exception ignored) {
            // An unreadable file just isn't findable.
        }
    }

    /** A player joined: remember their name, and their nickname from the profile. */
    void joined(ServerPlayer player) {
        String name = Names.name(player);
        OfflinePlayer op = new OfflinePlayer(player.getUUID(), name);
        names.put(name.toLowerCase(Locale.ROOT), op);
        byUuid.put(player.getUUID(), op);
        nickChanged(player);
    }

    /** Updates the saved-nickname index for a player (call after their nickname changed or they joined). */
    public void nickChanged(ServerPlayer player) {
        Profiles.Profile profile = plugin.profiles().get(player);
        String raw = profile == null ? null : profile.nickRaw();
        if (raw == null) forget(player.getUUID());
        else remember(player.getUUID(), raw);
    }

    private void remember(UUID id, String raw) {
        forget(id);
        String key = key(plainOfRaw(raw));
        if (key.isEmpty()) return;
        nickKeyOf.put(id, key);
        savedNicks.put(key, id);
    }

    /** Who holds this nickname in the saved index (online or offline), or null. */
    public UUID nickOwner(String plain) {
        return savedNicks.get(key(plain));
    }

    private void forget(UUID id) {
        String old = nickKeyOf.remove(id);
        if (old != null) savedNicks.remove(old, id);
    }

    /** A known player by exact username (any case), online or not; null if they never played here. */
    public OfflinePlayer byName(String username) {
        if (username == null || username.isBlank()) return null;
        for (ServerPlayer p : plugin.onlinePlayers()) if (Names.name(p).equalsIgnoreCase(username)) return OfflinePlayer.of(p);
        return names.get(username.toLowerCase(Locale.ROOT));
    }

    /** A known player by id (online, else from player files / the server's name cache); null if unknown. */
    public OfflinePlayer byId(UUID id) {
        ServerPlayer p = plugin.player(id);
        if (p != null) return OfflinePlayer.of(p);
        OfflinePlayer known = byUuid.get(id);
        if (known != null) return known;
        var server = Feather.serverOrNull();
        if (server != null) {
            var cached = server.services().nameToIdCache().get(id);
            if (cached.isPresent()) return new OfflinePlayer(id, cached.get().name());
        }
        // No negative cache: a miss is one Files.exists, and the player may get a file or a cache entry later.
        Profiles.Profile pr = plugin.profiles().load(id);
        if (pr.lastName == null) return null;
        OfflinePlayer r = new OfflinePlayer(id, pr.lastName);
        byUuid.putIfAbsent(id, r);
        return r;
    }

    // ------------------------------------------------------------------ names

    /** The visible text of a stored nickname (MiniMessage or &-codes). */
    public static String plainOfRaw(String raw) {
        Component c = raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0
                ? AMPERSAND.deserialize(raw.replace('§', '&'))
                : MiniMessage.miniMessage().deserialize(raw);
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Lower case, spaces as {@code _}: how typed names and nicknames are compared. */
    public static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /** The player's display name as plain text (their nickname, or their username without one). */
    public static String nickPlain(ServerPlayer p) {
        return Names.plain(p);
    }

    /** The nickname as one typeable word ({@code _} for spaces), or null when it's just the username. */
    public static String nickToken(ServerPlayer p) {
        String nick = nickPlain(p).trim();
        if (nick.isEmpty() || nick.equalsIgnoreCase(Names.name(p))) return null;
        return nick.replace(' ', '_');
    }

    private boolean visible(ServerPlayer viewer, ServerPlayer p) {
        return viewer == null || viewer.equals(p) || plugin.canSee(viewer, p);
    }

    // ------------------------------------------------------------------ online

    /** Exact username, exact nickname, then a unique prefix of either, among the players the viewer can see. */
    public Match match(ServerPlayer viewer, String input) {
        if (input == null || input.isBlank()) return NONE;
        String key = key(input);
        List<ServerPlayer> online = plugin.onlinePlayers();
        for (ServerPlayer p : online) {
            if (Names.name(p).equalsIgnoreCase(input) && visible(viewer, p)) return new Match(p, List.of(p));
        }
        // A real username always means that player, even offline: a nickname or a prefix must never redirect it to
        // someone else (otherwise "/pay <offline name>" could pay whoever took that name as a nickname).
        OfflinePlayer known = names.get(input.toLowerCase(Locale.ROOT));
        // Online but not visible to this viewer (vanished) counts the same: never fall through to nicknames/prefixes.
        if (known != null) return NONE;
        ServerPlayer nick = exactNick(viewer, key);
        if (nick != null) return new Match(nick, List.of(nick));
        List<ServerPlayer> hits = new ArrayList<>();
        for (ServerPlayer p : online) {
            if (!visible(viewer, p)) continue;
            if (Names.name(p).toLowerCase(Locale.ROOT).startsWith(key) || key(nickPlain(p)).startsWith(key)) hits.add(p);
        }
        if (hits.size() == 1) return new Match(hits.get(0), hits);
        return new Match(null, hits);
    }

    /** The visible online player whose nickname is exactly this (normalized) key; null if none or several. */
    private ServerPlayer exactNick(ServerPlayer viewer, String key) {
        ServerPlayer found = null;
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (!visible(viewer, p) || !key(nickPlain(p)).equals(key)) continue;
            if (found != null) return null;
            found = p;
        }
        return found;
    }

    /** The online player for this input, or null (see {@link #match}). */
    public ServerPlayer find(ServerPlayer viewer, String input) {
        return match(viewer, input).player();
    }

    /** Only an exact username or nickname (no prefixes). */
    public ServerPlayer findExact(ServerPlayer viewer, String input) {
        if (input == null || input.isBlank()) return null;
        for (ServerPlayer p : plugin.onlinePlayers()) if (Names.name(p).equalsIgnoreCase(input) && visible(viewer, p)) return p;
        return exactNick(viewer, key(input));
    }

    /**
     * A player who is online or has played here: exact username (online, then offline), exact nickname (online,
     * then saved in player files), then a unique prefix of an online player. A vanished player resolves only as an
     * offline player for viewers who can't see them, exactly as a username lookup always did.
     */
    public OfflinePlayer findOffline(ServerPlayer viewer, String input) {
        if (input == null || input.isBlank()) return null;
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (Names.name(p).equalsIgnoreCase(input)) return OfflinePlayer.of(p);
        }
        OfflinePlayer cached = names.get(input.toLowerCase(Locale.ROOT));
        if (cached != null) return cached;
        String key = key(input);
        ServerPlayer nick = exactNick(viewer, key);
        if (nick != null) return OfflinePlayer.of(nick);
        UUID saved = savedNicks.get(key);
        if (saved != null) {
            ServerPlayer p = plugin.player(saved);
            // A nickname only finds its owner while it is still theirs.
            if (p != null && !visible(viewer, p)) return null;   // never reveal a vanished player through their nickname
            if (p != null && key(nickPlain(p)).equals(key)) return OfflinePlayer.of(p);
            if (p == null) return byId(saved);
        }
        ServerPlayer m = match(viewer, input).player();
        return m == null ? null : OfflinePlayer.of(m);
    }

    /** {@link #findOffline} as an id. */
    public UUID findOfflineId(ServerPlayer viewer, String input) {
        OfflinePlayer p = findOffline(viewer, input);
        return p == null ? null : p.id();
    }

    /** Tells the viewer nobody matched, or which players an ambiguous input could mean. */
    public void notFound(Audience to, ServerPlayer viewer, String input) {
        Match m = match(viewer, input);
        Text text = plugin.text();
        if (m.ambiguous()) {
            List<String> list = new ArrayList<>();
            for (ServerPlayer p : m.candidates()) {
                String nick = nickToken(p);
                list.add(nick == null ? Names.name(p) : nick + " (" + Names.name(p) + ")");
                if (list.size() >= 6) break;
            }
            text.send(to, "general.player-ambiguous", Text.unparsed("input", input),
                    Text.unparsed("matches", String.join(", ", list)));
        } else {
            text.send(to, "general.player-not-found", Text.unparsed("input", input));
        }
    }

    public void notFound(Sender to, String input) {
        notFound(to, to.player(), input);
    }

    // ------------------------------------------------------------------ tab completion

    /** Usernames and nicknames ({@code _} for spaces) of the players the viewer can see that start with the prefix. */
    public List<String> complete(ServerPlayer viewer, String prefix) {
        return complete(viewer, prefix, false);
    }

    /** As {@link #complete(ServerPlayer, String)}, optionally leaving the viewer out. */
    public List<String> complete(ServerPlayer viewer, String prefix, boolean excludeSelf) {
        String key = key(prefix);
        Set<String> out = new LinkedHashSet<>();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (!visible(viewer, p) || (excludeSelf && p.equals(viewer))) continue;
            String name = Names.name(p);
            if (name.toLowerCase(Locale.ROOT).startsWith(key)) out.add(name);
            String nick = nickToken(p);
            if (nick != null && nick.toLowerCase(Locale.ROOT).startsWith(key)) out.add(nick);
        }
        return new ArrayList<>(out);
    }
}
