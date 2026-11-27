package net.axther.aftrhrs.core;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Per-player data (homes, nickname, teleport toggle, moderation history...) in {@code aftrhrs/data/players/<uuid>.yml},
 * the same files and keys as Feather's {@code plugins/Feather/data/players/}. Loaded when a player connects, kept in
 * memory while online, written off-thread on every {@link #save}.
 */
public final class Profiles {

    /** One saved place. Stored by world name so homes in unloaded worlds survive. */
    /** A saved place (home, warp, spawn, /back). {@code icon}: an item id shown for it in menus, or null for the default. */
    public record Home(String name, String world, double x, double y, double z, float yaw, float pitch, String icon) {
        public Home(String name, String world, double x, double y, double z, float yaw, float pitch) {
            this(name, world, x, y, z, yaw, pitch, null);
        }

        public static Home of(String name, Loc l) {
            return new Home(name, Worlds.name(l.level()), l.x(), l.y(), l.z(), l.yaw(), l.pitch());
        }

        public Home withIcon(String icon) {
            return new Home(name, world, x, y, z, yaw, pitch, icon == null || icon.isBlank() ? null : icon);
        }

        public Home withName(String name) {
            return new Home(name, world, x, y, z, yaw, pitch, icon);
        }

        /** The icon as an item, or {@code fallback} when none is set (or the item no longer exists). */
        public net.minecraft.world.item.ItemStack iconStack(net.minecraft.world.item.Item fallback) {
            net.minecraft.world.item.Item item = icon == null ? null : iconItem(icon);
            if (item == null) item = fallback;
            return item == null ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(item);
        }

        /** An icon id as an item (namespace optional, "minecraft:" by default), or null when there is no such item. */
        public static net.minecraft.world.item.Item iconItem(String id) {
            if (id == null) return null;
            net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id.trim().toLowerCase(Locale.ROOT));
            if (key == null) return null;
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(key)
                    .filter(i -> i != net.minecraft.world.item.Items.AIR).orElse(null);
        }

        public static Home of(String name, ServerPlayer p) {
            return of(name, Loc.of(p));
        }

        /** Where it is, or null while its world isn't loaded. */
        public Loc location() {
            ServerLevel w = Worlds.resolve(world);
            return w == null ? null : new Loc(w, x, y, z, yaw, pitch);
        }
    }

    /** Where and when a player died, and of what (a short text such as "Zombie" or "Fall"). */
    public record DeathPoint(String world, double x, double y, double z, long time, String cause) {
        public Loc location() {
            ServerLevel w = Worlds.resolve(world);
            return w == null ? null : new Loc(w, x, y, z);
        }
    }

    /** A warning: when, by whom (a name, or "Console"), and why. */
    public record Warning(long time, String staff, String reason) {
    }

    /**
     * One line of a player's moderation history: {@code type} is warn, delwarn, mute, unmute, kick, ban, tempban,
     * unban, freeze or unfreeze; {@code until} is 0 (not timed), -1 (permanent) or when it ends (epoch ms).
     */
    public record ModEntry(String type, long time, String staff, String reason, long until) {
    }

    /** A player's saved data. Fields are public for the modules; call {@link Profiles#save} after changing them. */
    public static final class Profile {
        public final UUID id;
        public String lastName;
        /** Lower-case name -> home, in creation order. */
        public final Map<String, Home> homes = new LinkedHashMap<>();
        /** The file exists but couldn't be read: never save over it (see {@link Profiles#save}). */
        public boolean unreadable;
        /** True when this player had never been on the server before this session (Bukkit's !hasPlayedBefore). */
        public boolean firstJoin;
        /** The nickname exactly as the player typed it (MiniMessage or &-codes), or null. */
        public String nickRaw;
        public boolean tpToggledOff;
        public boolean importedEssentials;
        public boolean importedNick;
        public boolean god;
        public boolean socialSpy;
        public boolean vanished;
        public boolean messagesOff;
        /** Where /back goes: the last place the player teleported away from, or died. */
        public Home back;
        public long lastSeen;
        /** Players whose chat, messages and teleport requests this player doesn't want. */
        public final Set<UUID> ignored = new LinkedHashSet<>();
        /** 0 = not muted, -1 = muted for good, else the time the mute ends (epoch ms). */
        public long mutedUntil;
        public String muteReason;
        /** The chosen flight trail style, "none" for off, or null for the server default. */
        public String trail;
        /** Whether others may carry this player; null = the server default (carry.allowed-by-default). */
        public Boolean carryAllowed;
        /** No sound or action bar when someone mentions this player in chat. */
        public boolean mentionsOff;
        /** The player's chosen time zone (a ZoneId such as "Europe/Paris"), or null for the server default. */
        public String timezone;
        /** The player's last deaths, newest first (see the death-point module). */
        public final List<DeathPoint> deaths = new ArrayList<>();
        // --- moderation suite
        /** Frozen by staff (/freeze): kept across relogs until /unfreeze. */
        public boolean frozen;
        /** The address the player last joined from, and where they were when they last left (for /whois). */
        public String lastIp;
        public Home lastLocation;
        /** Active warnings, oldest first (/warnings, /delwarn). */
        public final List<Warning> warnings = new ArrayList<>();
        /** Warning reasons given while the player was offline, shown on their next join. */
        public final List<String> pendingWarnings = new ArrayList<>();
        /** Moderation history (/history), oldest first, capped. */
        public final List<ModEntry> modLog = new ArrayList<>();
        /**
         * Last inventory snapshot for offline /invsee and /ec; 0 = none. Paper wrote {@code serializeItemsAsBytes}
         * (base64); the moderation port decides how to read old ones and what to write.
         */
        public long snapshotTime;
        public String snapshotInventory;
        public String snapshotEnder;

        Profile(UUID id) {
            this.id = id;
        }

        public UUID id() {
            return id;
        }

        public Map<String, Home> homes() {
            return homes;
        }

        public String nickRaw() {
            return nickRaw;
        }

        /** True while a mute is running (expired mutes are cleared here). */
        public boolean muted() {
            if (mutedUntil == 0) return false;
            if (mutedUntil > 0 && mutedUntil <= System.currentTimeMillis()) {
                mutedUntil = 0;
                muteReason = null;
                return false;
            }
            return true;
        }
    }

    private final Feather plugin;
    private final Map<UUID, Profile> loaded = new ConcurrentHashMap<>();
    /**
     * Saves still waiting for the writer thread, by player. {@link #read} uses these before the file, so an offline
     * profile edited twice in a row (two staff commands) or a player rejoining right after quitting never reads an
     * older file and saves over the newer data.
     */
    private final Map<UUID, Pending> unwritten = new ConcurrentHashMap<>();
    /**
     * When each player file was last written, by player: the startup walk ({@link PlayerLookup#start}) fills it from the
     * files' modified times, and every {@link #save} since adds its own. For the server goal's active-player count
     * ({@link #writtenSince}), so it needs no directory scan.
     */
    private final Map<UUID, Long> written = new ConcurrentHashMap<>();
    /** The startup walk finished: {@link #written} knows every player file. */
    private volatile boolean writtenReady;
    /**
     * Which data folder's walk may fill {@link #written}: {@link #start} and {@link #shutdown} move it on, so a walk still
     * running from a closed singleplayer world can't add its players to the next world's index or mark it complete.
     */
    private int writtenGeneration;
    private final Object writtenLock = new Object();
    private ExecutorService io;
    /** Told about every {@link #save} (server thread), for game tests; null normally. */
    volatile java.util.function.Consumer<Profile> onSave;

    /**
     * A save handed to the writer: the detached tree, turned into text once, by whoever needs it first (the writer
     * thread, or a {@link #read} of that player before the write ran).
     */
    private static final class Pending {
        private final Yaml tree;
        private String text;

        Pending(Yaml tree) {
            this.tree = tree;
        }

        synchronized String text() {
            if (text == null) text = tree.saveToString();
            return text;
        }
    }

    Profiles(Feather plugin) {
        this.plugin = plugin;
    }

    /** Hooks the connection events (once, from boot). */
    void init() {
        ServerPlayConnectionEvents.INIT.register((handler, server) -> {
            ServerPlayer p = handler.player;
            boolean played = hasPlayedBefore(p.getUUID());
            Profile profile = read(p.getUUID());
            profile.firstJoin = !played;
            profile.lastName = p.getGameProfile().name();
            loaded.put(p.getUUID(), profile);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer p = handler.player;
            UUID id = p.getUUID();
            Profile profile = loaded.get(id);
            if (profile != null) {
                profile.lastName = p.getGameProfile().name();
                profile.lastSeen = System.currentTimeMillis();
                save(profile);
            }
            // Drop it a moment later, after any quit handlers that still read it.
            Tasks.later(20L, () -> {
                if (plugin.player(id) == null) loaded.remove(id);
            });
        });
    }

    void start() {
        synchronized (writtenLock) {
            writtenGeneration++;
            written.clear();
            writtenReady = false;
        }
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AftrHrs-Profiles");
            t.setDaemon(true);
            return t;
        });
        for (ServerPlayer p : plugin.onlinePlayers()) loaded.computeIfAbsent(p.getUUID(), this::read);
    }

    void shutdown() {
        for (ServerPlayer p : plugin.onlinePlayers()) {
            Profile pr = loaded.get(p.getUUID());
            if (pr != null) {
                pr.lastSeen = System.currentTimeMillis();
                save(pr);
            }
        }
        ExecutorService ex = io;
        if (ex == null) return;
        ex.shutdown();
        try {
            if (!ex.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warn("Some player data writes timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        loaded.clear();
        synchronized (writtenLock) {
            writtenGeneration++;
            written.clear();
            writtenReady = false;
        }
    }

    /** The generation a startup walk belongs to: what it passes to {@link #noteWalked} and {@link #markWrittenIndexed}. */
    int writtenGeneration() {
        synchronized (writtenLock) {
            return writtenGeneration;
        }
    }

    /** A player file was written at {@code at} (epoch ms): from a save, or its modified time in the startup walk. Any thread. */
    void noteWritten(UUID id, long at) {
        written.merge(id, at, Math::max);
    }

    /** A player file's modified time, from the startup walk of generation {@code gen} (ignored once that is over). */
    void noteWalked(int gen, UUID id, long at) {
        synchronized (writtenLock) {
            if (gen == writtenGeneration) noteWritten(id, at);
        }
    }

    /** The startup walk of generation {@code gen} is done (or there was no folder to walk); ignored once that is over. */
    void markWrittenIndexed(int gen) {
        synchronized (writtenLock) {
            if (gen == writtenGeneration) writtenReady = true;
        }
    }

    /**
     * Adds every player whose file was written at or after {@code cutoff} (epoch ms) to {@code into}; how many there
     * were. -1 while the startup walk hasn't finished (the caller scans the folder itself then).
     */
    public int writtenSince(long cutoff, Set<UUID> into) {
        if (!writtenReady) return -1;
        int n = 0;
        for (Map.Entry<UUID, Long> e : written.entrySet()) {
            if (e.getValue() >= cutoff) {
                into.add(e.getKey());
                n++;
            }
        }
        return n;
    }

    /** The folder of the player files. */
    public Path folder() {
        return plugin.dataFile("players");
    }

    /** Reads (or returns the loaded) profile of any player, online or not. */
    public Profile load(UUID id) {
        Profile p = loaded.get(id);
        return p != null ? p : read(id);
    }

    /** The profile of an online player (always present after login). */
    public Profile get(ServerPlayer player) {
        return loaded.computeIfAbsent(player.getUUID(), this::read);
    }

    /** Whether a player file exists here or the world has their vanilla player data (Bukkit's hasPlayedBefore). */
    public boolean hasPlayedBefore(UUID id) {
        if (unwritten.containsKey(id) || Files.exists(folder().resolve(id + ".yml"))) return true;
        var server = Feather.serverOrNull();
        return server != null && Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"));
    }

    // ------------------------------------------------------------------ disk

    private Profile read(UUID id) {
        Profile profile = new Profile(id);
        Path file = folder().resolve(id + ".yml");
        Pending pending = unwritten.get(id);
        String pendingText = pending == null ? null : pending.text();
        if (pendingText == null && !Files.exists(file)) return profile;
        Yaml yml = new Yaml();
        try {
            yml.loadFromString(pendingText != null ? pendingText : Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().error("Could not read {}: {}. It is left untouched; this player's changes are not saved until the file is fixed.", file, e.toString());
            profile.unreadable = true;
            return profile;
        }
        profile.lastName = yml.getString("name");
        profile.nickRaw = yml.getString("nickname");
        profile.tpToggledOff = yml.getBoolean("teleport-requests-off", false);
        profile.importedEssentials = yml.getBoolean("imported-essentials", false);
        profile.importedNick = yml.getBoolean("imported-essentials-nick", false);
        profile.god = yml.getBoolean("god", false);
        profile.socialSpy = yml.getBoolean("socialspy", false);
        profile.vanished = yml.getBoolean("vanished", false);
        profile.messagesOff = yml.getBoolean("messages-off", false);
        profile.lastSeen = yml.getLong("last-seen", 0L);
        for (String raw : yml.getStringList("ignored")) {
            try {
                profile.ignored.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
        profile.mutedUntil = yml.getLong("muted-until", 0L);
        profile.muteReason = yml.getString("mute-reason");
        profile.trail = yml.getString("trail");
        if (yml.isBoolean("carry-allowed")) profile.carryAllowed = yml.getBoolean("carry-allowed");
        profile.mentionsOff = yml.getBoolean("mentions-off", false);
        profile.timezone = yml.getString("timezone");
        readModeration(profile, yml);
        Yaml back = yml.getConfigurationSection("back");
        if (back != null) profile.back = point("back", back);
        for (Map<?, ?> d : yml.getMapList("deaths")) {
            try {
                profile.deaths.add(new DeathPoint(String.valueOf(d.get("world")), ((Number) d.get("x")).doubleValue(),
                        ((Number) d.get("y")).doubleValue(), ((Number) d.get("z")).doubleValue(),
                        ((Number) d.get("time")).longValue(), d.get("cause") == null ? "" : String.valueOf(d.get("cause"))));
            } catch (RuntimeException ignored) {
                // A broken entry is dropped; the rest of the profile still loads.
            }
        }
        Yaml homes = yml.getConfigurationSection("homes");
        if (homes != null) {
            for (String key : homes.getKeys(false)) {
                Yaml h = homes.getConfigurationSection(key);
                if (h == null) continue;
                profile.homes.put(key.toLowerCase(Locale.ROOT), point(h.getString("name", key), h));
            }
        }
        return profile;
    }

    private static Home point(String name, Yaml s) {
        return new Home(name, s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"), s.getString("icon", null));
    }

    private static void writePoint(Yaml yml, String base, Home h) {
        yml.set(base + "world", h.world());
        yml.set(base + "x", h.x());
        yml.set(base + "y", h.y());
        yml.set(base + "z", h.z());
        yml.set(base + "yaw", (double) h.yaw());
        yml.set(base + "pitch", (double) h.pitch());
        if (h.icon() != null) yml.set(base + "icon", h.icon());
    }

    /**
     * Snapshots the profile on the calling thread (a tree of fresh maps and lists) and turns it into text and writes
     * it in the background.
     */
    public void save(Profile profile) {
        if (profile == null || profile.unreadable) return;
        java.util.function.Consumer<Profile> hook = onSave;
        if (hook != null) hook.accept(profile);
        Yaml yml = new Yaml();
        yml.set("name", profile.lastName);
        yml.set("nickname", profile.nickRaw);
        yml.set("teleport-requests-off", profile.tpToggledOff);
        yml.set("imported-essentials", profile.importedEssentials);
        yml.set("imported-essentials-nick", profile.importedNick);
        yml.set("god", profile.god);
        yml.set("socialspy", profile.socialSpy);
        yml.set("vanished", profile.vanished);
        yml.set("messages-off", profile.messagesOff);
        yml.set("last-seen", profile.lastSeen);
        if (!profile.ignored.isEmpty()) yml.set("ignored", profile.ignored.stream().map(UUID::toString).toList());
        if (profile.mutedUntil != 0) {
            yml.set("muted-until", profile.mutedUntil);
            yml.set("mute-reason", profile.muteReason);
        }
        if (profile.trail != null) yml.set("trail", profile.trail);
        if (profile.carryAllowed != null) yml.set("carry-allowed", profile.carryAllowed);
        if (profile.mentionsOff) yml.set("mentions-off", true);
        if (profile.timezone != null) yml.set("timezone", profile.timezone);
        if (profile.back != null) writePoint(yml, "back.", profile.back);
        if (!profile.deaths.isEmpty()) {
            List<Map<String, Object>> deaths = new ArrayList<>();
            for (DeathPoint d : profile.deaths) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("world", d.world());
                m.put("x", d.x());
                m.put("y", d.y());
                m.put("z", d.z());
                m.put("time", d.time());
                m.put("cause", d.cause());
                deaths.add(m);
            }
            yml.set("deaths", deaths);
        }
        for (Map.Entry<String, Home> e : profile.homes.entrySet()) {
            Home h = e.getValue();
            String base = "homes." + e.getKey() + ".";
            yml.set(base + "name", h.name());
            writePoint(yml, base, h);
        }
        saveModeration(profile, yml);
        // The tree is local and holds only copies (strings, numbers, fresh lists): safe to dump on the writer thread.
        Pending data = new Pending(yml);
        Path file = folder().resolve(profile.id + ".yml");
        unwritten.put(profile.id, data);
        noteWritten(profile.id, System.currentTimeMillis());
        Runnable write = () -> {
            try {
                Yaml.SafeFiles.writeAtomic(file, data.text());
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().error("Could not save {}: {}", file, e.toString());
            } finally {
                // Only this save's entry (by identity): a newer save of the same player stays until it is written.
                unwritten.remove(profile.id, data);
            }
        };
        ExecutorService ex = io;
        if (ex == null || ex.isShutdown()) write.run();
        else ex.execute(write);
    }

    // ------------------------------------------------------------------ moderation suite fields

    private static void readModeration(Profile profile, Yaml yml) {
        profile.frozen = yml.getBoolean("frozen", false);
        profile.lastIp = yml.getString("last-ip");
        Yaml at = yml.getConfigurationSection("last-location");
        if (at != null) profile.lastLocation = point("last", at);
        for (Map<?, ?> m : yml.getMapList("warnings")) {
            profile.warnings.add(new Warning(longOf(m.get("time")), String.valueOf(m.get("staff")), String.valueOf(m.get("reason"))));
        }
        profile.pendingWarnings.addAll(yml.getStringList("pending-warnings"));
        for (Map<?, ?> m : yml.getMapList("moderation-log")) {
            Object reason = m.get("reason");
            profile.modLog.add(new ModEntry(String.valueOf(m.get("type")), longOf(m.get("time")), String.valueOf(m.get("staff")),
                    reason == null ? "" : String.valueOf(reason), longOf(m.get("until"))));
        }
        profile.snapshotTime = yml.getLong("snapshot.time", 0L);
        profile.snapshotInventory = yml.getString("snapshot.inventory");
        profile.snapshotEnder = yml.getString("snapshot.ender-chest");
    }

    private static long longOf(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return o == null ? 0L : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static void saveModeration(Profile profile, Yaml yml) {
        if (profile.frozen) yml.set("frozen", true);
        if (profile.lastIp != null) yml.set("last-ip", profile.lastIp);
        if (profile.lastLocation != null) writePoint(yml, "last-location.", profile.lastLocation);
        if (!profile.warnings.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Warning w : profile.warnings) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", w.time());
                m.put("staff", w.staff());
                m.put("reason", w.reason());
                list.add(m);
            }
            yml.set("warnings", list);
        }
        if (!profile.pendingWarnings.isEmpty()) yml.set("pending-warnings", new ArrayList<>(profile.pendingWarnings));
        if (!profile.modLog.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ModEntry e : profile.modLog) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", e.type());
                m.put("time", e.time());
                m.put("staff", e.staff());
                if (!e.reason().isEmpty()) m.put("reason", e.reason());
                if (e.until() != 0) m.put("until", e.until());
                list.add(m);
            }
            yml.set("moderation-log", list);
        }
        if (profile.snapshotTime > 0) {
            yml.set("snapshot.time", profile.snapshotTime);
            yml.set("snapshot.inventory", profile.snapshotInventory);
            yml.set("snapshot.ender-chest", profile.snapshotEnder);
        }
    }
}
