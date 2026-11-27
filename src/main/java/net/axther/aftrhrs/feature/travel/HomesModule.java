package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Homes: /home, /homes (menu), /sethome, /delhome, /homeicon. Limits come from permissions (Essentials-compatible nodes).
 * Each home can have an icon (an item, shown on its button in the menus and in the Companion's Places screen): set from
 * the home's page with the item in hand, or /homeicon &lt;home&gt; [item|clear]. Moving or renaming a home keeps it.
 * Homes live in the player files ({@code data/players/<uuid>.yml homes:}), read by core {@code Profiles}.
 * The EssentialsX import is gone: on Paper every player's Essentials homes were already copied over.
 */
public final class HomesModule implements FeatherModule, Travel.Named {
    static final String SET_PERMISSION = "feather.sethome";
    /** A home without its own icon shows this one. */
    static final net.minecraft.world.item.Item DEFAULT_ICON = net.minecraft.world.item.Items.BED.red();
    /** How long "/sethome <existing>" waits for the same command again before it moves that home. */
    private static final long OVERWRITE_CONFIRM_MS = 10_000L;
    private static volatile HomesModule active;

    /** A /sethome that would move an existing home, waiting to be repeated. */
    private record Overwrite(String key, long at) {
    }

    private final Feather plugin;
    private final Map<UUID, Overwrite> overwrites = new HashMap<>();
    private final Map<String, Integer> limits = new LinkedHashMap<>();
    private Pattern namePattern;
    private String defaultName;
    private boolean menuOnBareHome;

    public HomesModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Homes";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        Travel.bind(plugin, this, "home", "homes", "sethome", "delhome", "homeicon");
        plugin.once("homes.events", () -> ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            HomesModule m = active;
            if (m != null) m.overwrites.remove(h.player.getUUID());
        }));
        plugin.hub().add("homes", Hub.Group.MAIN, Hub.ORDER_HOMES, "hub-homes", "feather.home", this::openList);
        plugin.hub().homeLimit(this::limit);
    }

    @Override
    public void disable() {
        if (active == this) active = null;
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        limits.clear();
        Yaml sec = c.getConfigurationSection("homes.limits");
        if (sec != null) for (String key : sec.getKeys(false)) limits.put(key.toLowerCase(Locale.ROOT), sec.getInt(key));
        if (limits.isEmpty()) limits.put("default", 3);
        try {
            namePattern = Pattern.compile(c.getString("homes.name-pattern", "^[A-Za-z0-9_-]{1,16}$"));
        } catch (RuntimeException e) {
            plugin.getLogger().warn("homes.name-pattern is not a valid pattern; using the default.");
            namePattern = Pattern.compile("^[A-Za-z0-9_-]{1,16}$");
        }
        defaultName = c.getString("homes.default-name", "home");
        menuOnBareHome = c.getBoolean("homes.menu-on-bare-home", true);
    }

    /** Highest limit the player qualifies for; -1 means unlimited. */
    int limit(ServerPlayer p) {
        if (Perms.has(p, "feather.homes.unlimited") || Perms.has(p, "essentials.sethome.multiple.unlimited")) return -1;
        int best = limits.getOrDefault("default", 1);
        for (Map.Entry<String, Integer> e : limits.entrySet()) {
            String key = e.getKey();
            if (Perms.has(p, "feather.homes." + key) || Perms.has(p, "essentials.sethome.multiple." + key)) {
                best = Math.max(best, e.getValue());
            }
        }
        return best;
    }

    private Component limitText(ServerPlayer p) {
        int limit = limit(p);
        return Component.text(limit < 0 ? "∞" : Integer.toString(limit));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer player = sender.player();
        if (player == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        Profiles.Profile profile = plugin.profiles().get(player);
        switch (command) {
            case "homes" -> openList(player);
            case "home" -> {
                if (args.length == 0) {
                    if (profile.homes().isEmpty()) {
                        text.send(player, "homes.none", Text.component("button",
                                text.button("homes.button-set-here", "/sethome " + defaultName)));
                        text.sound(player, "error");
                    } else if (profile.homes().size() == 1 || !menuOnBareHome) {
                        Profiles.Home h = profile.homes().containsKey(defaultName.toLowerCase(Locale.ROOT))
                                ? profile.homes().get(defaultName.toLowerCase(Locale.ROOT))
                                : profile.homes().values().iterator().next();
                        go(player, h);
                    } else {
                        openList(player);
                    }
                    return true;
                }
                Profiles.Home h = profile.homes().get(args[0].toLowerCase(Locale.ROOT));
                if (h == null) {
                    text.send(player, "homes.unknown", Text.unparsed("name", args[0]),
                            Text.component("button", text.button("homes.button-list", "/homes")));
                    text.sound(player, "error");
                    return true;
                }
                go(player, h);
            }
            case "sethome" -> {
                String name = args.length > 0 ? args[0] : defaultName;
                String key = name.toLowerCase(Locale.ROOT);
                // Moving a home that already exists takes the same command twice.
                if (profile.homes().containsKey(key) && Perms.has(player, SET_PERMISSION)) {
                    Overwrite asked = overwrites.remove(player.getUUID());
                    if (asked == null || !asked.key().equals(key) || System.currentTimeMillis() - asked.at() > OVERWRITE_CONFIRM_MS) {
                        overwrites.put(player.getUUID(), new Overwrite(key, System.currentTimeMillis()));
                        text.send(player, "homes.overwrite-confirm", Text.unparsed("name", profile.homes().get(key).name()),
                                Text.unparsed("seconds", OVERWRITE_CONFIRM_MS / 1000));
                        text.sound(player, "click");
                        return true;
                    }
                }
                setHome(player, name);
            }
            case "delhome" -> {
                if (args.length == 0) {
                    text.send(player, "homes.delhome-usage");
                    return true;
                }
                delete(player, args[0]);
            }
            case "homeicon" -> {
                if (args.length == 0) {
                    text.send(player, "homes.icon-usage");
                    return true;
                }
                setIcon(player, args[0], args.length > 1 ? args[1] : null);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> complete(String command, Sender sender, String[] args) {
        ServerPlayer player = sender.player();
        if (player == null) return List.of();
        if (command.equals("homeicon") && args.length == 2) return Travel.completeIcon(args[1]);
        if (args.length != 1) return List.of();
        if (!command.equals("home") && !command.equals("delhome") && !command.equals("sethome") && !command.equals("homeicon")) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Profiles.Home h : plugin.profiles().get(player).homes().values()) {
            if (h.name().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(h.name());
        }
        return out;
    }

    // ------------------------------------------------------------------ actions

    void go(ServerPlayer player, Profiles.Home home) {
        Text text = plugin.text();
        if (home.location() == null) {
            text.send(player, "homes.world-missing", Text.unparsed("name", home.name()), Text.unparsed("world", home.world()));
            text.sound(player, "error");
            return;
        }
        text.send(player, "homes.teleporting", Text.unparsed("name", home.name()));
        plugin.teleports().teleport(player, home::location, Component.text(home.name()), null, true, null);
    }

    /** Returns an error message key, or null on success. */
    private String validateName(String name) {
        if (!namePattern.matcher(name).matches()) return "homes.bad-name";
        return null;
    }

    boolean setHome(ServerPlayer player, String name) {
        Text text = plugin.text();
        if (!Perms.has(player, SET_PERMISSION)) {
            text.send(player, "general.no-permission");
            text.sound(player, "error");
            return false;
        }
        String error = validateName(name);
        if (error != null) {
            text.send(player, error, Text.unparsed("name", name));
            text.sound(player, "error");
            return false;
        }
        Loc here = Loc.of(player);
        // Someone else's claim (claims.yml homes-need-access); the claims module explains why.
        Hooks.HomeGuard guard = plugin.service(Hooks.HomeGuard.class);
        if (guard != null && !guard.homeAllowed(player, here)) return false;
        Profiles.Profile profile = plugin.profiles().get(player);
        String key = name.toLowerCase(Locale.ROOT);
        boolean replacing = profile.homes().containsKey(key);
        int limit = limit(player);
        if (!replacing && limit >= 0 && profile.homes().size() >= limit) {
            text.send(player, "homes.limit", Text.component("limit", limitText(player)));
            text.sound(player, "error");
            return false;
        }
        // Moving a home keeps its icon.
        Profiles.Home old = profile.homes().get(key);
        profile.homes().put(key, Profiles.Home.of(name, here).withIcon(old == null ? null : old.icon()));
        plugin.profiles().save(profile);
        plugin.placesChanged(player.getUUID());
        if (!replacing) plugin.stat(player.getUUID(), "homes-set", 1);
        text.send(player, replacing ? "homes.moved" : "homes.set", Text.unparsed("name", name),
                Text.unparsed("coords", here.coords()),
                Text.unparsed("count", profile.homes().size()), Text.component("limit", limitText(player)));
        text.sound(player, "home-set");
        player.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 0.5, player.getZ(), 14, 0.4, 0.3, 0.4, 0);
        plugin.advance(player, "event", "sethome");
        return true;
    }

    /**
     * Sets a home's icon: {@code item} is an item id, "clear" for the default, or null for the item in the main hand.
     * True when it changed.
     */
    boolean setIcon(ServerPlayer player, String home, String item) {
        Text text = plugin.text();
        if (!Perms.has(player, SET_PERMISSION)) {
            text.send(player, "general.no-permission");
            text.sound(player, "error");
            return false;
        }
        Profiles.Profile profile = plugin.profiles().get(player);
        String key = home.toLowerCase(Locale.ROOT);
        Profiles.Home h = profile.homes().get(key);
        if (h == null) {
            text.send(player, "homes.unknown", Text.unparsed("name", home),
                    Text.component("button", text.button("homes.button-list", "/homes")));
            text.sound(player, "error");
            return false;
        }
        String icon = Travel.iconId(player, item);
        if (icon == null) {
            text.send(player, item == null ? "homes.icon-hold" : "homes.icon-unknown", Text.unparsed("item", item == null ? "" : item));
            text.sound(player, "error");
            return false;
        }
        boolean clear = icon.isEmpty();
        profile.homes().put(key, h.withIcon(clear ? null : icon));
        plugin.profiles().save(profile);
        plugin.placesChanged(player.getUUID());
        text.send(player, clear ? "homes.icon-cleared" : "homes.icon-set", Text.unparsed("name", h.name()), Text.unparsed("item", icon));
        text.sound(player, "home-set");
        return true;
    }

    private void delete(ServerPlayer player, String name) {
        Text text = plugin.text();
        Profiles.Profile profile = plugin.profiles().get(player);
        Profiles.Home removed = profile.homes().remove(name.toLowerCase(Locale.ROOT));
        if (removed == null) {
            text.send(player, "homes.unknown", Text.unparsed("name", name),
                    Text.component("button", text.button("homes.button-list", "/homes")));
            text.sound(player, "error");
            return;
        }
        plugin.profiles().save(profile);
        plugin.placesChanged(player.getUUID());
        text.send(player, "homes.deleted", Text.unparsed("name", removed.name()));
        text.sound(player, "deleted");
    }

    // ------------------------------------------------------------------ menus

    void openList(ServerPlayer player) {
        Text text = plugin.text();
        Profiles.Profile profile = plugin.profiles().get(player);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.homes.title",
                Text.unparsed("count", profile.homes().size()), Text.component("limit", limitText(player))));
        if (profile.homes().isEmpty()) {
            menu.text(text.msg("menu.homes.empty"));
        } else {
            menu.text(text.msg("menu.homes.body"));
            for (Map.Entry<String, Profiles.Home> e : profile.homes().entrySet()) {
                Profiles.Home h = e.getValue();
                String key = e.getKey();
                menu.button(text.msg("menu.homes.entry", Text.unparsed("name", h.name())),
                        text.msg("menu.homes.entry-tooltip", Text.unparsed("world", Travel.prettyWorld(h.world())),
                                Text.unparsed("coords", Travel.coords(h.x(), h.y(), h.z()))),
                        218, h.iconStack(DEFAULT_ICON), p -> {
                            Profiles.Home now = plugin.profiles().get(p).homes().get(key);
                            if (now != null) go(p, now);
                            else openList(p);
                        });
                menu.button(text.msg("menu.homes.manage"), text.msg("menu.homes.manage-tooltip", Text.unparsed("name", h.name())),
                        78, p -> openHome(p, key));
            }
        }
        int limit = limit(player);
        if (Perms.has(player, SET_PERMISSION) && (limit < 0 || profile.homes().size() < limit)) {
            menu.button(text.msg("menu.homes.new"), text.msg("menu.homes.new-tooltip"), 218, p -> openCreate(p, null, null));
        }
        menu.columns(2);
        plugin.exitButton(menu, player);
        menu.open(player);
        text.sound(player, "menu-open");
    }

    private void openHome(ServerPlayer player, String key) {
        Text text = plugin.text();
        Profiles.Home h = plugin.profiles().get(player).homes().get(key);
        if (h == null) {
            openList(player);
            return;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.homes.home-title", Text.unparsed("name", h.name())));
        Loc loc = h.location();
        String distance = "—";
        Loc here = Loc.of(player);
        if (loc != null && loc.sameLevel(here)) distance = Math.round(Math.sqrt(loc.distanceSquared(here))) + "m";
        menu.item(h.iconStack(DEFAULT_ICON), text.msg("menu.homes.home-body", Text.unparsed("name", h.name()),
                Text.unparsed("world", Travel.prettyWorld(h.world())), Text.unparsed("coords", Travel.coords(h.x(), h.y(), h.z())),
                Text.unparsed("distance", distance)), 280);
        menu.button(text.msg("menu.homes.teleport"), null, 148, p -> {
            Profiles.Home now = plugin.profiles().get(p).homes().get(key);
            if (now != null) go(p, now);
        });
        // Moving, renaming and deleting change homes: the same node as /sethome and /delhome.
        if (Perms.has(player, SET_PERMISSION)) {
            menu.button(text.msg("menu.homes.move-here"), text.msg("menu.homes.move-here-tooltip"), 148, p -> openMove(p, key));
            menu.button(text.msg("menu.homes.rename"), null, 148, p -> openRename(p, key, null));
            menu.button(text.msg("menu.homes.delete"), null, 148, p -> openDelete(p, key));
            menu.button(text.msg("menu.homes.icon"), text.msg("menu.homes.icon-tooltip"), 148, p -> {
                setIcon(p, key, null);
                openHome(p, key);
            });
            if (h.icon() != null) menu.button(text.msg("menu.homes.icon-clear"), null, 148, p -> {
                setIcon(p, key, "clear");
                openHome(p, key);
            });
        }
        menu.columns(2);
        menu.exit(text.msg("menu.back"), this::openList);
        menu.open(player);
        text.sound(player, "click");
    }

    private void openCreate(ServerPlayer player, String initial, Component error) {
        Text text = plugin.text();
        Profiles.Profile profile = plugin.profiles().get(player);
        String suggestion = initial;
        if (suggestion == null) {
            suggestion = defaultName;
            int n = 2;
            while (profile.homes().containsKey(suggestion.toLowerCase(Locale.ROOT))) suggestion = defaultName + n++;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.homes.new-title"));
        menu.text(text.msg("menu.homes.new-body", Text.unparsed("coords", Loc.of(player).coords())));
        if (error != null) menu.text(error);
        menu.input(Dialogs.Input.text("name", text.msg("menu.homes.name-label")).initial(suggestion).maxLength(16).width(220).build());
        menu.button(text.msg("menu.homes.save"), null, 148, (p, view) -> {
            String name = view.getText("name");
            name = name == null ? "" : name.trim();
            String err = validateName(name);
            if (err != null) {
                openCreate(p, name, text.msg(err, Text.unparsed("name", name)));
                text.sound(p, "error");
                return;
            }
            if (plugin.profiles().get(p).homes().containsKey(name.toLowerCase(Locale.ROOT))) {
                openCreate(p, name, text.msg("menu.homes.name-taken", Text.unparsed("name", name)));
                text.sound(p, "error");
                return;
            }
            if (setHome(p, name)) openList(p);
        });
        menu.exit(text.msg("menu.back"), this::openList);
        menu.open(player);
    }

    private void openRename(ServerPlayer player, String key, Component error) {
        Text text = plugin.text();
        Profiles.Home h = plugin.profiles().get(player).homes().get(key);
        if (h == null) {
            openList(player);
            return;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.homes.rename-title", Text.unparsed("name", h.name())));
        if (error != null) menu.text(error);
        menu.input(Dialogs.Input.text("name", text.msg("menu.homes.name-label")).initial(h.name()).maxLength(16).width(220).build());
        menu.button(text.msg("menu.homes.save"), null, 148, (p, view) -> {
            String name = view.getText("name");
            name = name == null ? "" : name.trim();
            Profiles.Profile profile = plugin.profiles().get(p);
            Profiles.Home current = profile.homes().get(key);
            if (current == null) {
                openList(p);
                return;
            }
            String err = validateName(name);
            String newKey = name.toLowerCase(Locale.ROOT);
            if (err == null && !newKey.equals(key) && profile.homes().containsKey(newKey)) err = "menu.homes.name-taken";
            if (err != null) {
                openRename(p, key, text.msg(err, Text.unparsed("name", name)));
                text.sound(p, "error");
                return;
            }
            // Rebuild the map to keep the order.
            Map<String, Profiles.Home> copy = new LinkedHashMap<>(profile.homes());
            profile.homes().clear();
            for (Map.Entry<String, Profiles.Home> e : copy.entrySet()) {
                if (e.getKey().equals(key)) {
                    profile.homes().put(newKey, e.getValue().withName(name));
                } else {
                    profile.homes().put(e.getKey(), e.getValue());
                }
            }
            plugin.profiles().save(profile);
            plugin.placesChanged(p.getUUID());
            text.send(p, "homes.renamed", Text.unparsed("old", current.name()), Text.unparsed("name", name));
            text.sound(p, "home-set");
            openHome(p, newKey);
        });
        menu.exit(text.msg("menu.back"), p -> openHome(p, key));
        menu.open(player);
    }

    /** "Move this home here?": the old spot is gone once it moves. */
    private void openMove(ServerPlayer player, String key) {
        Text text = plugin.text();
        Profiles.Home h = plugin.profiles().get(player).homes().get(key);
        if (h == null) {
            openList(player);
            return;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.homes.move-title"));
        menu.text(text.msg("menu.homes.move-body", Text.unparsed("name", h.name()),
                Text.unparsed("coords", Loc.of(player).coords())));
        menu.button(text.msg("menu.homes.move-confirm"), null, 148, p -> {
            if (setHome(p, h.name())) openHome(p, key);
        });
        menu.button(text.msg("menu.homes.move-keep"), null, 148, p -> openHome(p, key));
        menu.columns(2);
        menu.open(player);
        text.sound(player, "click");
    }

    private void openDelete(ServerPlayer player, String key) {
        Text text = plugin.text();
        Profiles.Home h = plugin.profiles().get(player).homes().get(key);
        if (h == null) {
            openList(player);
            return;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.homes.delete-title"));
        menu.text(text.msg("menu.homes.delete-body", Text.unparsed("name", h.name())));
        menu.button(text.msg("menu.homes.delete-confirm"), null, 148, p -> {
            delete(p, h.name());
            openList(p);
        });
        menu.button(text.msg("menu.homes.delete-keep"), null, 148, p -> openHome(p, key));
        menu.columns(2);
        menu.open(player);
        text.sound(player, "click");
    }
}
