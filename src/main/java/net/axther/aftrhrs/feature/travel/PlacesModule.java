package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.audience.Audience;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Server places: /spawn, /setspawn, /warp (menu), /setwarp, /delwarp, /warpicon, /back. A warp can have an icon (an item,
 * shown in the warps menu and the Companion's Places screen): /warpicon &lt;warp&gt; [item|clear], the held item when none
 * is named; moving a warp keeps it. New players go to spawn and get the
 * starter kit; players without a bed respawn at spawn. Data: {@code data/places.yml} (same file and format as Paper).
 */
public final class PlacesModule implements FeatherModule, Travel.Named {
    private static final Pattern WARP_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,24}$");
    /** A warp without its own icon shows this one; spawn shows the compass. */
    static final Item DEFAULT_ICON = net.minecraft.world.item.Items.ENDER_PEARL, SPAWN_ICON = net.minecraft.world.item.Items.COMPASS;
    private static volatile PlacesModule active;

    private final Feather plugin;
    private final Path file;
    private Profiles.Home spawn;
    private final Map<String, Profiles.Home> warps = new LinkedHashMap<>();
    /** places.yml exists but couldn't be read: never save over it. */
    private boolean unreadable;

    public PlacesModule(Feather plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("places.yml");
    }

    @Override
    public String name() {
        return "Spawn, warps and /back";
    }

    @Override
    public void enable() {
        load();
        active = this;
        Travel.bind(plugin, this, "spawn", "setspawn", "warp", "setwarp", "delwarp", "warpicon", "back");
        plugin.provide(Hooks.Spawn.class, this::spawnLocation);
        plugin.once("places.events", () -> {
            FeatherEvents.PLAYER_TELEPORTED.register((p, from, to, cause) -> {
                PlacesModule m = active;
                if (m != null) m.onTeleported(p, from, to, cause);
            });
            FeatherEvents.PLAYER_DEATH.register((p, source) -> {
                PlacesModule m = active;
                if (m != null) m.onDeath(p);
            });
            FeatherEvents.RESPAWN_POSITION.register((p, current, bed) -> {
                PlacesModule m = active;
                return m == null ? null : m.respawnAt(current, bed);
            });
            ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
                PlacesModule m = active;
                if (m != null) m.onJoin(h.player);
            });
        });
        Hub hub = plugin.hub();
        hub.add("warps", Hub.Group.MAIN, Hub.ORDER_WARPS, "hub-warps",
                p -> Perms.has(p, "feather.warp") || Perms.has(p, "feather.spawn"), this::openWarps);
        hub.add("back", Hub.Group.TRAVEL, Hub.ORDER_BACK, "hub-back",
                p -> Perms.has(p, "feather.back") && plugin.profiles().get(p).back != null, p -> Travel.perform(p, "back"));
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        plugin.withdraw(Hooks.Spawn.class);
    }

    // ------------------------------------------------------------------ storage

    /** {@code /feather reload} reads places.yml again (hand edits, or a file fixed after it couldn't be read). */
    @Override
    public void reload() {
        load();
        plugin.placesChanged(null);
    }

    private void load() {
        Yaml y = SafeYaml.read(file, plugin.getLogger(), "spawn and warp changes");
        if (y == null) {
            // Keep whatever is in memory; saves are refused until the file is fixed.
            unreadable = true;
            return;
        }
        unreadable = false;
        spawn = point("spawn", y.getConfigurationSection("spawn"));
        warps.clear();
        Yaml w = y.getConfigurationSection("warps");
        if (w != null) {
            for (String key : w.getKeys(false)) {
                Profiles.Home h = point(w.getString(key + ".name", key), w.getConfigurationSection(key));
                if (h != null) warps.put(key.toLowerCase(Locale.ROOT), h);
            }
        }
    }

    private static Profiles.Home point(String name, Yaml s) {
        if (s == null || !s.contains("x")) return null;
        return new Profiles.Home(name, s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"), s.getString("icon", null));
    }

    private static void put(Yaml y, String path, Profiles.Home h) {
        y.set(path + ".name", h.name());
        y.set(path + ".world", h.world());
        y.set(path + ".x", h.x());
        y.set(path + ".y", h.y());
        y.set(path + ".z", h.z());
        y.set(path + ".yaw", (double) h.yaw());
        y.set(path + ".pitch", (double) h.pitch());
        if (h.icon() != null) y.set(path + ".icon", h.icon());
    }

    /** Writes places.yml (atomically, keeping a .bak). False when it couldn't, e.g. while the file is unreadable. */
    private boolean save() {
        if (unreadable) {
            plugin.getLogger().error("Not saving {}: it couldn't be read. Fix it (a .broken- copy was kept) and run /feather reload.", file);
            return false;
        }
        Yaml y = new Yaml();
        // Paper's EssentialsX import ran once; keep the marker so the file reads the same on both.
        y.set("imported-essentials", true);
        if (spawn != null) put(y, "spawn", spawn);
        for (Map.Entry<String, Profiles.Home> e : warps.entrySet()) put(y, "warps." + e.getKey(), e.getValue());
        // dumped and written on the data thread, in order with every other data save; waited for (the sender is told)
        return SafeYaml.writeSync(file, null, y.snapshot(), plugin.getLogger());
    }

    /** Saves after a staff change, telling them when it didn't stick. */
    private void saveFor(Audience sender) {
        if (!save()) plugin.text().send(sender, "places.save-failed");
    }

    /** The spawn point, falling back to the server's spawn. */
    Loc spawnLocation() {
        Loc l = spawn == null ? null : spawn.location();
        if (l != null) return l;
        MinecraftServer s = Feather.server();
        LevelData.RespawnData rd = s.getRespawnData();
        ServerLevel level = s.getLevel(rd.dimension());
        if (level == null) level = s.overworld();
        BlockPos pos = rd.pos();
        return new Loc(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, rd.yaw(), rd.pitch());
    }

    List<Profiles.Home> warpList() {
        return List.copyOf(warps.values());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        Text text = plugin.text();
        if (command.equals("spawn") && args.length > 0) {
            if (!sender.hasPermission("feather.spawn.others")) {
                text.send(sender, "general.no-permission");
                return true;
            }
            ServerPlayer target = plugin.findPlayer(sender, args[0]);
            if (target == null) {
                plugin.notFound(sender, args[0]);
                return true;
            }
            plugin.movePlayer(target, spawnLocation());
            text.send(sender, "places.spawn-other", Text.component("target", Names.display(target)));
            return true;
        }
        ServerPlayer player = sender.player();
        if (player == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        switch (command) {
            case "spawn" -> plugin.teleports().teleport(player, this::spawnLocation, text.msg("places.spawn-name"), arrived(player, "spawn"));
            case "setspawn" -> {
                Loc here = Loc.of(player);
                spawn = Profiles.Home.of("spawn", here);
                saveFor(sender);
                plugin.placesChanged(null);
                // The vanilla spawn is server-wide here; only an overworld spot moves it (Paper kept one per world).
                if (player.level().dimension() == Level.OVERWORLD) {
                    player.level().setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, here.blockPos(), here.yaw(), 0f));
                }
                text.send(player, "places.spawn-set", Text.unparsed("coords", here.coords()));
                text.sound(player, "home-set");
            }
            case "warp" -> {
                if (args.length == 0) openWarps(player);
                else warp(player, args[0]);
            }
            case "setwarp" -> {
                if (args.length == 0 || !WARP_NAME.matcher(args[0]).matches()) {
                    text.send(player, "places.warp-bad-name");
                    return true;
                }
                // Moving a warp keeps its icon.
                Profiles.Home old = warps.get(args[0].toLowerCase(Locale.ROOT));
                boolean replaced = warps.put(args[0].toLowerCase(Locale.ROOT), Profiles.Home.of(args[0], player).withIcon(old == null ? null : old.icon())) != null;
                saveFor(sender);
                plugin.placesChanged(null);
                text.send(player, replaced ? "places.warp-moved" : "places.warp-set", Text.unparsed("name", args[0]));
                text.sound(player, "home-set");
            }
            case "delwarp" -> {
                Profiles.Home h = args.length == 0 ? null : warps.remove(args[0].toLowerCase(Locale.ROOT));
                if (h == null) {
                    text.send(player, "places.warp-unknown", Text.unparsed("name", args.length == 0 ? "" : args[0]));
                    return true;
                }
                saveFor(sender);
                plugin.placesChanged(null);
                text.send(player, "places.warp-deleted", Text.unparsed("name", h.name()));
                text.sound(player, "deleted");
            }
            case "warpicon" -> {
                Profiles.Home h = args.length == 0 ? null : warps.get(args[0].toLowerCase(Locale.ROOT));
                if (h == null) {
                    text.send(player, args.length == 0 ? "places.warp-icon-usage" : "places.warp-unknown", Text.unparsed("name", args.length == 0 ? "" : args[0]));
                    return true;
                }
                String item = args.length > 1 ? args[1] : null;
                String icon = Travel.iconId(player, item);
                if (icon == null) {
                    text.send(player, item == null ? "homes.icon-hold" : "homes.icon-unknown", Text.unparsed("item", item == null ? "" : item));
                    text.sound(player, "error");
                    return true;
                }
                warps.put(args[0].toLowerCase(Locale.ROOT), h.withIcon(icon.isEmpty() ? null : icon));
                saveFor(sender);
                plugin.placesChanged(null);
                text.send(player, icon.isEmpty() ? "places.warp-icon-cleared" : "places.warp-icon-set", Text.unparsed("name", h.name()), Text.unparsed("item", icon));
                text.sound(player, "home-set");
            }
            case "back" -> {
                Profiles.Home back = plugin.profiles().get(player).back;
                if (back == null || back.location() == null) {
                    text.send(player, "places.no-back");
                    text.sound(player, "error");
                    return true;
                }
                plugin.teleports().teleport(player, back::location, text.msg("places.back-name"), arrived(player, "back"), true, null);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void warp(ServerPlayer player, String name) {
        Text text = plugin.text();
        Profiles.Home h = warps.get(name.toLowerCase(Locale.ROOT));
        if (h == null) {
            text.send(player, "places.warp-unknown", Text.unparsed("name", name));
            text.sound(player, "error");
            return;
        }
        if (plugin.getConfig().getBoolean("warps.per-warp-permissions", false)
                && !Perms.has(player, "feather.warp." + name.toLowerCase(Locale.ROOT))) {
            text.send(player, "general.no-permission");
            return;
        }
        if (h.location() == null) {
            text.send(player, "homes.world-missing", Text.unparsed("name", h.name()), Text.unparsed("world", h.world()));
            return;
        }
        plugin.teleports().teleport(player, h::location, Component.text(h.name()), arrived(player, "warp"), true, null);
    }

    /** Reports the arrival for advancements. */
    private Runnable arrived(ServerPlayer player, String event) {
        UUID id = player.getUUID();
        return () -> plugin.advance(plugin.player(id), "event", event);
    }

    void openWarps(ServerPlayer player) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.warps.title", Text.unparsed("count", warps.size())));
        if (warps.isEmpty()) menu.text(text.msg("menu.warps.empty"));
        else menu.text(text.msg("menu.warps.body"));
        if (Perms.has(player, "feather.spawn")) {
            menu.button(text.msg("menu.warps.spawn"), text.msg("menu.hub.spawn-tooltip"), 148, new ItemStack(SPAWN_ICON),
                    p -> plugin.teleports().teleport(p, this::spawnLocation, text.msg("places.spawn-name"), arrived(p, "spawn")));
        }
        for (Profiles.Home h : usableWarps(player)) {
            String n = h.name();
            menu.button(text.msg("menu.warps.entry", Text.unparsed("name", n)),
                    text.msg("menu.homes.entry-tooltip", Text.unparsed("world", Travel.prettyWorld(h.world())),
                            Text.unparsed("coords", Travel.coords(h.x(), h.y(), h.z()))),
                    148, h.iconStack(DEFAULT_ICON), p -> warp(p, n));
        }
        plugin.addTravelExtras(menu, player, 148);
        // "☠ Death Point" while the player has a kept death point.
        DeathPointModule deaths = plugin.module(DeathPointModule.class);
        if (deaths != null) deaths.addWarpsButton(menu, player, 148);
        menu.columns(2);
        plugin.exitButton(menu, player);
        menu.open(player);
        text.sound(player, "menu-open");
    }

    /** The warps this player may use (the menu leaves the others out, as /warp would refuse them). */
    List<Profiles.Home> usableWarps(ServerPlayer player) {
        List<Profiles.Home> out = new ArrayList<>();
        if (!Perms.has(player, "feather.warp")) return out;
        boolean perWarp = plugin.getConfig().getBoolean("warps.per-warp-permissions", false);
        for (Profiles.Home h : warps.values()) {
            if (!perWarp || Perms.has(player, "feather.warp." + h.name().toLowerCase(Locale.ROOT))) out.add(h);
        }
        return out;
    }

    @Override
    public List<String> complete(String command, Sender sender, String[] args) {
        if (args.length == 2 && command.equals("warpicon")) return Travel.completeIcon(args[1]);
        if (args.length == 1 && (command.equals("warp") || command.equals("delwarp") || command.equals("setwarp") || command.equals("warpicon"))) {
            List<String> out = new ArrayList<>();
            for (Profiles.Home h : warps.values()) if (h.name().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(h.name());
            return out;
        }
        if (args.length == 1 && command.equals("spawn") && sender.hasPermission("feather.spawn.others")) {
            return plugin.completePlayers(sender, args[0]);
        }
        return List.of();
    }

    // ------------------------------------------------------------------ events

    /** Remembers where /back should go: the spot a command or Feather teleport left from. */
    private void onTeleported(ServerPlayer player, Loc from, Loc to, TeleportCause cause) {
        if (cause != TeleportCause.COMMAND && cause != TeleportCause.PLUGIN) return;
        if (from.sameLevel(to) && from.distanceSquared(to) < 4) return;
        plugin.profiles().get(player).back = Profiles.Home.of("back", from);
    }

    private void onDeath(ServerPlayer p) {
        Profiles.Profile profile = plugin.profiles().get(p);
        profile.back = Profiles.Home.of("back", p);
        plugin.profiles().save(profile);
        if (Perms.has(p, "feather.back") && plugin.getConfig().getBoolean("back.hint-on-death", true)) {
            UUID id = p.getUUID();
            Tasks.later(20L, () -> {
                ServerPlayer now = plugin.player(id);
                if (now != null) plugin.text().send(now, "places.death-hint",
                        Text.component("button", plugin.text().button("places.button-back", "/back")));
            });
        }
    }

    private TeleportTransition respawnAt(TeleportTransition current, boolean bedOrAnchor) {
        if (!plugin.getConfig().getBoolean("spawn.respawn-at-spawn", true) || bedOrAnchor) return null;
        Loc l = spawn == null ? null : spawn.location();
        if (l == null) return null;
        return new TeleportTransition(l.level(), new Vec3(l.x(), l.y(), l.z()), Vec3.ZERO, l.yaw(), l.pitch(),
                current.missingRespawnBlock(), false, Set.of(), current.postTeleportTransition());
    }

    private void onJoin(ServerPlayer p) {
        Profiles.Profile profile = plugin.profiles().get(p);
        if (!profile.firstJoin) return;
        Yaml c = plugin.getConfig();
        Loc to = spawn == null ? null : spawn.location();
        if (c.getBoolean("spawn.new-players-to-spawn", true) && to != null) plugin.teleportNow(p, to, TeleportCause.PLUGIN);
        for (String spec : c.getStringList("spawn.starter-kit")) {
            String[] parts = spec.trim().split("\\s+");
            int amount = 1;
            try {
                if (parts.length > 1) amount = Math.max(1, Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                plugin.getLogger().warn("spawn.starter-kit: bad amount in '{}', giving 1.", spec);
            }
            String id = parts[0].toLowerCase(Locale.ROOT);
            // Oraxen items ("oraxen:arcana_taming_guide") are the mod's own items now (aftrhrs:<id>).
            Identifier key = id.startsWith("oraxen:") ? AftrHrs.id(id.substring("oraxen:".length())) : Identifier.tryParse(id);
            Optional<Item> item = key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key);
            if (item.isEmpty()) {
                plugin.getLogger().warn("spawn.starter-kit: unknown item {}", parts[0]);
                continue;
            }
            // Like Bukkit's addItem: "bread 128" is two stacks, "iron_sword 2" two swords (not one capped stack).
            int max = Math.max(1, item.get().getDefaultMaxStackSize());
            for (int left = Math.min(amount, 36 * 64); left > 0; left -= max) {
                Travel.giveOrDrop(p, new ItemStack(item.get(), Math.min(left, max)));
            }
        }
    }
}
