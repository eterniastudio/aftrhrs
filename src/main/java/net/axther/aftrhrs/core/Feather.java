package net.axther.aftrhrs.core;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Feather, the Aftr Hrs core, living inside the mod. Owns the data folder ({@code <server>/aftrhrs/}, the same
 * layout as {@code plugins/Feather/}), config.yml + messages.yml, the module list and the shared services:
 * {@link #profiles()}, {@link #teleports()}, {@link #dialogs()}, {@link #hub()}, {@link #lookup()}, {@link #picker()},
 * {@link #placeholders()}, {@link #scoreTeams()}, and the {@link #service service registry} through which modules
 * answer each other ({@link Hooks}).
 *
 * <p>Modules register with {@link #register(String, Supplier)} from {@code AftrHrs.onInitialize}; the ones whose
 * {@code modules.<key>} toggle is on are built and enabled when the server starts, in registration order.
 */
public final class Feather {
    private static Feather instance;
    private static MinecraftServer server;

    /** {@code <server>/aftrhrs/} on a dedicated server; {@code <world>/aftrhrs/} in singleplayer (set when the world opens). */
    private Path dataFolder;
    private Yaml config = new Yaml();
    private final Text text = new Text(this);
    private final Map<String, Supplier<? extends FeatherModule>> factories = new LinkedHashMap<>();
    private final List<FeatherModule> modules = new ArrayList<>();
    private final Map<Class<?>, FeatherModule> byType = new LinkedHashMap<>();
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    private final Set<String> once = new HashSet<>();
    private final Logger log = AftrHrs.LOG;

    private final Profiles profiles = new Profiles(this);
    private final Teleports teleports = new Teleports(this);
    private final Dialogs dialogs = new Dialogs(this);
    private final Hub hub = new Hub(this);
    private final PlayerLookup lookup = new PlayerLookup(this);
    private final PlayerPicker picker = new PlayerPicker(this);
    private final Placeholders placeholders = new Placeholders(this);
    private final ScoreTeams scoreTeams = new ScoreTeams();
    private boolean running;

    private Feather(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    public static Feather get() {
        return instance;
    }

    public static MinecraftServer server() {
        if (server == null) throw new IllegalStateException("No server running");
        return server;
    }

    public static MinecraftServer serverOrNull() {
        return server;
    }

    /** Called once from the mod initializer. */
    public static Feather boot() {
        if (instance != null) return instance;
        Path folder = FabricLoader.getInstance().getGameDir().resolve("aftrhrs");
        instance = new Feather(folder);
        Yaml pluginYml = Yaml.load(Feather.class.getResourceAsStream("/feather/plugin.yml"));
        Perms.load(pluginYml);
        CommandBridge.load(pluginYml);
        Tasks.init();
        FeatherEvents.init();
        instance.hookEvents();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> CommandBridge.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            instance.start();
        });
        // Modules have bound their commands by now: Feather's replace same-named vanilla ones only where bound.
        ServerLifecycleEvents.SERVER_STARTED.register(CommandBridge::takeOver);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> instance.stop());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
        return instance;
    }

    /** The core's own listeners (once per JVM). */
    private void hookEvents() {
        profiles.init();
        teleports.init();
        FeatherEvents.CUSTOM_CLICK.register(dialogs::handleClick);
        FeatherEvents.COMMAND.register((p, cmd) -> {
            hub.typedCommand(p);
            return true;
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> lookup.joined(h.player));
        DataAlerts.init();   // staff hear about unreadable data files when they join
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            // ServerDisconnectThreadMixin keeps this on the server thread; say so loudly if that ever breaks.
            if (!s.isSameThread()) getLogger().warn("Quit event for {} ran on {} (not the server thread)", h.player.getScoreboardName(), Thread.currentThread().getName());
            UUID id = h.player.getUUID();
            dialogs.forget(id);
            hub.forget(id);
            Names.forget(id);
        });
    }

    /** Adds a module, built only if {@code config.yml modules.<toggle>} is on (or toggle is null). */
    public void register(String toggle, Supplier<? extends FeatherModule> factory) {
        factories.put(toggle == null ? "core." + factories.size() : toggle, factory);
    }

    private void start() {
        // Singleplayer: each world keeps its own Feather data in its save folder (homes, claims, bank, pets...), instead
        // of every world sharing .minecraft/aftrhrs. A dedicated server keeps <server>/aftrhrs (the plugins/Feather copy).
        MinecraftServer s = server;
        if (s != null && !s.isDedicatedServer()) {
            dataFolder = s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().resolve("aftrhrs");
        } else {
            dataFolder = FabricLoader.getInstance().getGameDir().resolve("aftrhrs");
        }
        try {
            Files.createDirectories(dataFolder.resolve("data"));
        } catch (IOException e) {
            log.error("Could not create {}", dataFolder, e);
        }
        // A new data folder (another singleplayer world) gets its config files checked for upgrades again.
        upgradeChecked.clear();
        pass = new java.util.HashMap<>();
        try {
            config = loadResourceConfig("config.yml");
            text.reload(config);
            placeholders.reload(config);
            teleports.reload(config);
            profiles.start();
            lookup.start();
            running = true;
            for (Map.Entry<String, Supplier<? extends FeatherModule>> e : factories.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith("core.") && !config.getBoolean("modules." + key, true)) continue;
                FeatherModule m;
                try {
                    m = e.getValue().get();
                } catch (Throwable t) {
                    log.error("Could not create module {}", key, t);
                    continue;
                }
                try {
                    m.enable();
                    modules.add(m);
                    byType.put(m.getClass(), m);
                } catch (Throwable t) {
                    log.error("Could not enable {}", m.name(), t);
                }
            }
        } finally {
            pass = null;
        }
        log.info("Feather core up: {} modules {}", modules.size(), modules.stream().map(FeatherModule::name).toList());
    }

    private void stop() {
        running = false;
        teleports.cancelAll();
        for (int i = modules.size() - 1; i >= 0; i--) {
            FeatherModule m = modules.get(i);
            try {
                m.disable();
            } catch (Throwable t) {
                log.warn("Error disabling {}", m.name(), t);
            }
        }
        modules.clear();
        byType.clear();
        scoreTeams.shutdown();
        profiles.shutdown();
        // The modules' last saves are queued on the data thread: wait for them (it stays up for the next world).
        SafeYaml.drain(15);
        // the broken files were this data folder's: another world must not be told about them
        DataAlerts.clear();
        dialogs.clear();
        hub.clear();
        services.clear();
        Tasks.cancelAll();
        CommandBridge.unbindAll();
    }

    /** /feather reload: config, messages, placeholders, teleports, then every module's {@code reload()}. */
    public void reload() {
        // Every module of this reload reads a config file once (tools.yml, guide.yml... have several readers).
        pass = new java.util.HashMap<>();
        long t0 = System.nanoTime();
        // the slowest modules of this reload, for the log line ("where did the reload go")
        List<Map.Entry<String, Long>> took = new ArrayList<>();
        try {
            config = loadResourceConfig("config.yml");
            text.reload(config);
            placeholders.reload(config);
            teleports.reload(config);
            took.add(Map.entry("core", System.nanoTime() - t0));
            for (FeatherModule m : modules) {
                long t = System.nanoTime();
                try {
                    m.reload();
                } catch (Throwable t1) {
                    log.warn("Error reloading {}", m.name(), t1);
                }
                took.add(Map.entry(m.name(), System.nanoTime() - t));
            }
        } finally {
            pass = null;
        }
        took.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        StringBuilder slow = new StringBuilder();
        for (int i = 0; i < Math.min(5, took.size()); i++) {
            if (i > 0) slow.append(", ");
            slow.append(took.get(i).getKey()).append(' ').append(took.get(i).getValue() / 100_000 / 10.0).append(" ms");
        }
        log.info("Feather reloaded in {} ms (slowest: {})", (System.nanoTime() - t0) / 1_000_000, slow);
    }

    /** True between server start and stop (Bukkit's {@code plugin.isEnabled()}). */
    public boolean isEnabled() {
        return running;
    }

    /**
     * Runs {@code register} only the first time this key is seen in this JVM. Fabric events can't be unregistered, so
     * a module registers its listeners with {@code feather.once("homes.events", () -> ...)} in {@code enable()} and
     * has them return early while it isn't running ({@code feather.module(HomesModule.class) == null}).
     */
    public void once(String key, Runnable register) {
        if (once.add(key)) register.run();
    }

    // ------------------------------------------------------------------ basics

    public Yaml getConfig() {
        return config;
    }

    public Text text() {
        return text;
    }

    public Logger getLogger() {
        return log;
    }

    public Path getDataFolder() {
        return dataFolder;
    }

    public Path dataFile(String name) {
        return dataFolder.resolve("data").resolve(name);
    }

    public List<FeatherModule> modules() {
        return modules;
    }

    /** The running module of a type, or null if it is off. */
    @SuppressWarnings("unchecked")
    public <T extends FeatherModule> T module(Class<T> type) {
        FeatherModule m = byType.get(type);
        if (m != null) return (T) m;
        for (FeatherModule x : modules) if (type.isInstance(x)) return (T) x;
        return null;
    }

    /** {@link #module} for any type (Feather's {@code moduleOf}). */
    public <T> T moduleOf(Class<T> type) {
        for (FeatherModule m : modules) if (type.isInstance(m)) return type.cast(m);
        return null;
    }

    public void bind(String command, FeatherCommand handler) {
        CommandBridge.bind(command, handler);
    }

    /** {@link #bind} (Feather's name for modules in other packages). */
    public void bindCommand(String command, FeatherCommand handler) {
        bind(command, handler);
    }

    public ServerPlayer player(UUID id) {
        if (server == null || id == null || server.getPlayerList() == null) return null;
        return server.getPlayerList().getPlayer(id);
    }

    /** Everyone online (empty before the player list exists, i.e. while the server is still starting). */
    public List<ServerPlayer> onlinePlayers() {
        return server == null || server.getPlayerList() == null ? List.of() : server.getPlayerList().getPlayers();
    }

    // ------------------------------------------------------------------ service registry

    /**
     * Registers this module's answer to a service interface (usually one of {@link Hooks}, or an interface the module
     * defines for others). Replaces any earlier provider. Everything is withdrawn when the server stops.
     */
    public <T> void provide(Class<T> type, T impl) {
        if (impl == null) services.remove(type);
        else services.put(type, impl);
    }

    /** The provider of a service, or null when no running module provides it. */
    public <T> T service(Class<T> type) {
        Object o = services.get(type);
        return o == null ? null : type.cast(o);
    }

    public <T> void withdraw(Class<T> type) {
        services.remove(type);
    }

    // ------------------------------------------------------------------ shared services

    public Profiles profiles() {
        return profiles;
    }

    public Teleports teleports() {
        return teleports;
    }

    public Dialogs dialogs() {
        return dialogs;
    }

    /** The Feather menu: modules add their buttons, toggles and settings rows here. */
    public Hub hub() {
        return hub;
    }

    public PlayerLookup lookup() {
        return lookup;
    }

    /** The shared player-head picker chest (see {@link PlayerPicker}). */
    public PlayerPicker picker() {
        return picker;
    }

    public Placeholders placeholders() {
        return placeholders;
    }

    /** Scoreboard teams shared by the tab list (sorting) and nametags (hiding the vanilla tag). */
    public ScoreTeams scoreTeams() {
        return scoreTeams;
    }

    /** Per-player time zones, or null while the tool-stats module (which provides them) is off. */
    public Timezones timezones() {
        return service(Timezones.class);
    }

    // ------------------------------------------------------------------ hooks into modules

    /**
     * True while the player still has to type the server code (auth module). Chat, command and menu code skips these
     * players: nothing they type may reach anyone or anything else.
     */
    public boolean authPending(ServerPlayer player) {
        Hooks.Auth a = service(Hooks.Auth.class);
        return a != null && player != null && a.pending(player);
    }

    /** {@link #authPending} (Feather's name for modules in other packages). */
    public boolean authBlocked(ServerPlayer player) {
        return authPending(player);
    }

    /** True while the player is AFK (false when the AFK module is off). */
    public boolean isAfk(ServerPlayer player) {
        Hooks.Afk a = service(Hooks.Afk.class);
        return a != null && player != null && a.isAfk(player);
    }

    /** Whether {@code viewer} can see {@code target} (vanish); always true without a vanish provider or for the console. */
    public boolean canSee(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null || viewer == target) return true;
        Hooks.Vanish v = service(Hooks.Vanish.class);
        return v == null || v.canSee(viewer, target);
    }

    /** True while the player is robbing the bank: no teleports, pearls, elytra, mounts but horses (docs/bank-robbery.md). */
    public boolean travelLocked(ServerPlayer player) {
        Hooks.TravelLock t = service(Hooks.TravelLock.class);
        return t != null && player != null && t.travelLocked(player);
    }

    /** True when a chest lock on this block keeps {@code who} out (not the owner, not on its allow list). */
    public boolean lockedAgainst(ServerLevel level, BlockPos pos, UUID who) {
        Hooks.Locks l = service(Hooks.Locks.class);
        return l != null && who != null && l.lockedAgainst(level, pos, who);
    }

    /** True for a bank chest, which other modules (chest locks) must leave alone. */
    public boolean isBankBlock(ServerLevel level, BlockPos pos) {
        Hooks.BankBlocks b = service(Hooks.BankBlocks.class);
        return b != null && b.isBankBlock(level, pos);
    }

    /** Installs the advancements module (Feather's {@code advancer}). */
    public void advancer(Hooks.Advancer advancer) {
        provide(Hooks.Advancer.class, advancer);
    }

    /**
     * Reports something a player did, for advancements: {@code trigger} is the kind ("event", "tame", "kill",
     * "obtain", "wear", "place", ...) and {@code target} what it was (an event name, mob type or item id).
     * Does nothing while the advancements module is off.
     */
    public void advance(ServerPlayer player, String trigger, String target) {
        Hooks.Advancer a = service(Hooks.Advancer.class);
        if (a == null || player == null) return;
        try {
            a.award(player, trigger, target);
        } catch (RuntimeException e) {
            log.warn("Advancement hook failed for {}:{}", trigger, target, e);
        }
    }

    /** Counts a stat for analytics (does nothing while analytics is off). */
    public void stat(UUID player, String stat, long amount) {
        Hooks.Stats s = service(Hooks.Stats.class);
        if (s != null && player != null) s.increment(player, stat, amount);
    }

    /** A server letter in the player's mail; false while mail is off or closed. */
    public boolean mailLetter(UUID to, String fromName, String body) {
        Hooks.Mail m = service(Hooks.Mail.class);
        return m != null && m.systemLetter(to, fromName, body);
    }

    /** Server items waiting at the mailbox; false while mail is off or closed. */
    public boolean mailItems(UUID to, String fromName, String note, List<ItemStack> items) {
        Hooks.Mail m = service(Hooks.Mail.class);
        return m != null && m.systemGift(to, fromName, note, items);
    }

    /** A player's homes, warps, death point or graves changed (null: everyone's): the Companion's places list follows. */
    public void placesChanged(UUID player) {
        Hooks.PlacesChanged p = service(Hooks.PlacesChanged.class);
        if (p != null) p.changed(player);
    }

    /** Custom recipes changed: tell the Companion (its JEI/REI list). Modules call this after changing recipes. */
    public void recipesUpdated() {
        Hooks.RecipesChanged r = service(Hooks.RecipesChanged.class);
        if (r != null) r.changed();
    }

    /** A player's nickname or vanish state changed: refresh lookups and chat @-completions. */
    public void playerNamesChanged(ServerPlayer player) {
        lookup.nickChanged(player);
        FeatherEvents.NAMES_CHANGED.invoker().changed(player);
    }

    // ------------------------------------------------------------------ claims

    /** Registers (or with null, removes) the claims module's answers. */
    public void claimsHook(ClaimsHook hook) {
        provide(ClaimsHook.class, hook);
    }

    /** Whether claims let this player do this here; true when claims are off or nobody claimed the spot. */
    public boolean claimAllows(ServerPlayer player, Loc where, ClaimAction action) {
        ClaimsHook h = service(ClaimsHook.class);
        return h == null || player == null || where == null || h.allows(player, where, action);
    }

    /** {@link #claimAllows(ServerPlayer, Loc, ClaimAction)} for a player who may be offline. */
    public boolean claimAllows(UUID player, Loc where, ClaimAction action) {
        ClaimsHook h = service(ClaimsHook.class);
        return h == null || player == null || where == null || h.allows(player, where, action);
    }

    /** Whether a claim covers this spot (false when claims are off). */
    public boolean claimed(Loc where) {
        ClaimsHook h = service(ClaimsHook.class);
        return h != null && where != null && h.claimed(where);
    }

    /** Whether one claim covers both spots (false when claims are off or either spot is unclaimed). */
    public boolean sameClaim(Loc a, Loc b) {
        ClaimsHook h = service(ClaimsHook.class);
        return h != null && a != null && b != null && h.sameClaim(a, b);
    }

    // ------------------------------------------------------------------ menus

    public void openHub(ServerPlayer player) {
        hub.openHub(player);
    }

    public void openSettings(ServerPlayer player) {
        hub.openSettings(player);
    }

    /** The bottom button of a menu: "← Back" to the hub section a player came from, else "Close". */
    public void exitButton(Dialogs.Menu menu, ServerPlayer player) {
        hub.exitButton(menu, player);
    }

    /** {@link #exitButton} (Feather's name for modules in other packages). */
    public void menuExit(Dialogs.Menu menu, ServerPlayer player) {
        hub.exitButton(menu, player);
    }

    /** Adds "Wild" and "Go Back" to the warps menu, for players who can use them. */
    public void addTravelExtras(Dialogs.Menu menu, ServerPlayer player, int width) {
        hub.addTravelExtras(menu, player, width);
    }

    // ------------------------------------------------------------------ teleports

    /**
     * Moves a player right away (no warm-up), as {@link TeleportCause#COMMAND}. Refused while they rob the bank.
     * Completed at once (the future keeps Paper's {@code teleportAsync} shape for ported code).
     */
    public CompletableFuture<Boolean> movePlayer(ServerPlayer player, Loc to) {
        if (travelLocked(player)) {
            text.send(player, "bank.robbery.teleport-blocked");
            text.sound(player, "error");
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.completedFuture(teleportNow(player, to, TeleportCause.COMMAND));
    }

    /** {@link #movePlayer} (Feather's name for modules in other packages). */
    public CompletableFuture<Boolean> teleportPlayer(ServerPlayer player, Loc to) {
        return movePlayer(player, to);
    }

    /**
     * The raw move every Feather teleport ends in: {@link #beforeTeleport}, then a vanilla teleport tagged with
     * {@code cause} (so {@link FeatherEvents#PLAYER_TELEPORT} listeners can refuse it). True when the player moved.
     * No travel-lock check: use {@link #movePlayer} for player-requested moves.
     */
    public boolean teleportNow(ServerPlayer player, Loc to, TeleportCause cause) {
        if (player == null || to == null || to.level() == null || player.hasDisconnected()) return false;
        beforeTeleport(player, to);
        TeleportTransition t = new TeleportTransition(to.level(), new Vec3(to.x(), to.y(), to.z()), Vec3.ZERO, to.yaw(), to.pitch(),
                Set.of(), TeleportTransition.DO_NOTHING);
        ServerPlayer moved = cause.with(() -> player.teleport(t));
        if (moved == null) return false;
        moved.resetFallDistance();
        return true;
    }

    /**
     * Call before any teleport of a player you do yourself: when the destination is in another level it fires
     * {@link FeatherEvents#BEFORE_WORLD_CHANGE} (nametags come off) and drops carried players (a carried player is a
     * passenger too).
     */
    public void beforeTeleport(ServerPlayer player, Loc to) {
        if (to == null || to.level() == player.level()) return;
        FeatherEvents.BEFORE_WORLD_CHANGE.invoker().before(player, to);
        for (Entity e : List.copyOf(player.getPassengers())) if (e instanceof ServerPlayer) e.stopRiding();
    }

    // ------------------------------------------------------------------ finding players

    /**
     * Finds an online player the viewer can see: exact username, exact nickname ({@code _} for spaces), then a
     * unique prefix of either. Null if nobody or several match; tell the viewer with {@link #notFound}.
     */
    public ServerPlayer findPlayer(Sender viewer, String input) {
        return lookup.find(viewer == null ? null : viewer.player(), input);
    }

    public ServerPlayer findPlayer(ServerPlayer viewer, String input) {
        return lookup.find(viewer, input);
    }

    /** {@link #findPlayer}, falling back to players who have played here (by username or saved nickname). */
    public OfflinePlayer findOfflinePlayer(Sender viewer, String input) {
        return lookup.findOffline(viewer == null ? null : viewer.player(), input);
    }

    public OfflinePlayer findOfflinePlayer(ServerPlayer viewer, String input) {
        return lookup.findOffline(viewer, input);
    }

    /** Sends "nobody called ... is online", or the names an ambiguous input could mean. */
    public void notFound(Sender to, String input) {
        lookup.notFound(to, input);
    }

    public void notFound(ServerPlayer to, String input) {
        lookup.notFound(Adv.of(to), to, input);
    }

    /** Usernames and nicknames the viewer can see starting with {@code prefix}, for tab completion. */
    public List<String> completePlayers(Sender viewer, String prefix) {
        return lookup.complete(viewer == null ? null : viewer.player(), prefix);
    }

    public List<String> completePlayers(ServerPlayer viewer, String prefix) {
        return lookup.complete(viewer, prefix);
    }

    /** {@link #completePlayers} without the viewer themselves (/pay, /tpa ...). */
    public List<String> completeOthers(Sender viewer, String prefix) {
        return lookup.complete(viewer == null ? null : viewer.player(), prefix, true);
    }

    public List<String> completeOthers(ServerPlayer viewer, String prefix) {
        return lookup.complete(viewer, prefix, true);
    }

    // ------------------------------------------------------------------ files

    /**
     * Writes the bundled {@code feather/<name>} to the data folder if it is not there yet. A file that is byte-for-byte
     * an earlier bundled version (feather/history.json, made by tools/gen_config_history.py) was never edited by the
     * server owner, so it is replaced by the new bundled one: an update that adds creatures, harvests or guide pages
     * reaches existing servers and worlds. Edited files are kept as they are (path lookups still fall back to the
     * bundled defaults). The replaced file is kept as {@code <name>.old}.
     */
    public void saveDefault(String name) {
        Path file = dataFolder.resolve(name);
        if (Files.exists(file)) {
            // Once per file per server start: an unedited file only changes when the mod does.
            if (upgradeChecked.add(file.toAbsolutePath())) upgradeUnedited(name, file);
            return;
        }
        byte[] bundled = bundledBytes(name);
        if (bundled == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bundled, java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.warn("Could not write default {}: {}", name, e.toString());
        }
    }

    /** Config files already checked by {@link #upgradeUnedited} since the server started (cleared in {@link #start}). */
    private final Set<Path> upgradeChecked = ConcurrentHashMap.newKeySet();
    /**
     * The config files read so far in this start or {@code /feather reload} (server thread; null outside one): several
     * modules read tools.yml, guide.yml and others, which are parsed once per pass. Outside a pass (a module's own
     * reload command) every read goes to the disk.
     */
    private Map<String, Yaml> pass;

    private static volatile Map<String, Set<String>> history;

    private static Map<String, Set<String>> history() {
        Map<String, Set<String>> h = history;
        if (h != null) return h;
        h = new java.util.HashMap<>();
        try (InputStream in = Feather.class.getResourceAsStream("/feather/history.json")) {
            if (in != null) {
                var root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : root.entrySet()) {
                    Set<String> s = new HashSet<>();
                    e.getValue().getAsJsonArray().forEach(x -> s.add(x.getAsString()));
                    h.put(e.getKey(), s);
                }
            }
        } catch (Exception e) {
            AftrHrs.LOG.warn("Could not read feather/history.json: {}", e.toString());
        }
        return history = h;
    }

    /** SHA-256 of a config file with line endings normalized (CRLF to LF) and trailing whitespace dropped. */
    static String configHash(byte[] data) {
        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").stripTrailing();
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void upgradeUnedited(String name, Path file) {
        Set<String> past = history().get(name);
        if (past == null || past.isEmpty()) return;
        byte[] bundled = bundledBytes(name);
        if (bundled == null) return;
        try {
            String onDisk = configHash(Files.readAllBytes(file));
            if (onDisk.equals(BUNDLED_HASH.computeIfAbsent(name, n -> configHash(bundled))) || !past.contains(onDisk)) return;
            Files.copy(file, file.resolveSibling(name.substring(name.lastIndexOf('/') + 1) + ".old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(file, bundled);
            log.info("Updated {} to this version's defaults (it was an unedited earlier version; the old file is {}.old)", name, name);
        } catch (IOException e) {
            log.warn("Could not update {}: {}", name, e.toString());
        }
    }

    /**
     * Loads a YAML file from the data folder (writing the bundled default first), with the bundled file as defaults.
     * Within one start or {@code /feather reload} every caller gets the same instance: read it, don't change it.
     */
    public Yaml loadResourceConfig(String name) {
        Map<String, Yaml> p = pass;
        MinecraftServer s = server;
        if (p != null && s != null && !s.isSameThread()) p = null;
        if (p != null) {
            Yaml y = p.get(name);
            if (y != null) return y;
        }
        saveDefault(name);
        Yaml yml = Yaml.load(dataFolder.resolve(name));
        yml.setDefaults(bundled(name));
        if (p != null) p.put(name, yml);
        return yml;
    }

    /** The bundled files, parsed once per JVM (they are in the jar and never change). */
    private static final ConcurrentHashMap<String, Yaml> BUNDLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.util.Optional<byte[]>> BUNDLED_BYTES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> BUNDLED_HASH = new ConcurrentHashMap<>();

    /** The bytes of {@code feather/<name>} in the jar, or null when there is no such file. Shared: don't change them. */
    static byte[] bundledBytes(String name) {
        return BUNDLED_BYTES.computeIfAbsent(name, n -> {
            try (InputStream in = Feather.class.getResourceAsStream("/feather/" + n)) {
                return java.util.Optional.ofNullable(in == null ? null : in.readAllBytes());
            } catch (IOException e) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * The bundled default of a config file (empty when there is none). The instance is shared by every caller and
     * thread: read it only, never set anything on it.
     */
    public static Yaml bundled(String name) {
        return BUNDLED.computeIfAbsent(name, n -> {
            byte[] b = bundledBytes(n);
            return b == null ? new Yaml() : Yaml.fromString(new String(b, java.nio.charset.StandardCharsets.UTF_8));
        });
    }
}
