package net.axther.aftrhrs.core;

import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Per-player time zones for the times Feather shows (tool stats, /inspect, calendar, quests). The server can't know
 * a client's zone, so players pick one with {@code /timezone <Area/City>} or the picker ({@link #openTimezonePicker});
 * it is saved in their profile. Everyone else sees {@code tool-stats.default-timezone} (blank = the server's zone).
 *
 * <p>Owned by the tool-stats module (as on Paper): it creates one, {@code feather.provide(Timezones.class, zones)}s
 * it, binds {@code /timezone} to it and calls {@link #reload}. Others reach it with {@link Feather#timezones()}.
 */
public final class Timezones implements FeatherCommand {
    private final Feather plugin;
    /** Lower-case id -> canonical ZoneId string, for case-insensitive input. */
    private final Map<String, String> ids = new HashMap<>();
    private final List<String> sorted;
    private ZoneId fallback = ZoneId.systemDefault();
    private DateTimeFormatter dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
    private List<String> shortcuts = List.of();

    public Timezones(Feather plugin) {
        this.plugin = plugin;
        TreeSet<String> all = new TreeSet<>(ZoneId.getAvailableZoneIds());
        all.add("UTC");
        for (String id : all) ids.put(id.toLowerCase(Locale.ROOT), id);
        sorted = new ArrayList<>(all);
    }

    public void reload() {
        Yaml c = plugin.getConfig();
        String def = c.getString("tool-stats.default-timezone", "");
        ZoneId zone = def == null || def.isBlank() ? ZoneId.systemDefault() : parse(def);
        if (zone == null) {
            plugin.getLogger().warn("tool-stats.default-timezone '{}' is not a time zone; using the server's.", def);
            zone = ZoneId.systemDefault();
        }
        fallback = zone;
        try {
            dateTime = DateTimeFormatter.ofPattern(c.getString("tool-stats.date-format", "d MMM yyyy, HH:mm"), Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warn("tool-stats.date-format is not a valid pattern: {}", e.getMessage());
            dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
        }
        List<String> list = new ArrayList<>();
        for (String s : c.getStringList("tool-stats.timezone-shortcuts")) {
            ZoneId z = parse(s);
            if (z != null) list.add(z.getId());
        }
        shortcuts = list;
    }

    /** A zone from user input (any case), or null if it isn't one. */
    public ZoneId parse(String input) {
        if (input == null || input.isBlank()) return null;
        String canonical = ids.get(input.trim().toLowerCase(Locale.ROOT));
        try {
            return ZoneId.of(canonical != null ? canonical : input.trim().toUpperCase(Locale.ROOT));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** The zone a player sees times in. */
    public ZoneId zone(ServerPlayer player) {
        String id = player == null ? null : plugin.profiles().get(player).timezone;
        ZoneId z = id == null ? null : parse(id);
        return z == null ? fallback : z;
    }

    /** The server default zone. */
    public ZoneId fallback() {
        return fallback;
    }

    /** Whether the player picked a zone themselves. */
    public boolean chosen(ServerPlayer player) {
        return plugin.profiles().get(player).timezone != null;
    }

    /** An epoch-millis time in the player's zone, e.g. "13 Nov 2026, 14:02". */
    public String format(long epochMs, ServerPlayer viewer) {
        return dateTime.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone(viewer)));
    }

    public void set(ServerPlayer player, ZoneId zone) {
        Profiles.Profile profile = plugin.profiles().get(player);
        profile.timezone = zone == null ? null : zone.getId();
        plugin.profiles().save(profile);
    }

    // ------------------------------------------------------------------ /timezone

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        if (args.length == 0) {
            openTimezonePicker(p);
            return true;
        }
        String in = String.join("_", args);
        if (in.equalsIgnoreCase("reset") || in.equalsIgnoreCase("default")) {
            set(p, null);
            text.send(p, "inspect.timezone.reset", Text.unparsed("zone", fallback.getId()), Text.unparsed("time", format(System.currentTimeMillis(), p)));
            text.sound(p, "click");
            return true;
        }
        ZoneId zone = parse(in);
        if (zone == null) {
            text.send(p, "inspect.timezone.unknown", Text.unparsed("input", in));
            text.sound(p, "error");
            return true;
        }
        set(p, zone);
        text.send(p, "inspect.timezone.set", Text.unparsed("zone", zone.getId()), Text.unparsed("time", format(System.currentTimeMillis(), p)));
        text.sound(p, "nick-set");
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ("reset".startsWith(prefix)) out.add("reset");
        for (String id : sorted) {
            String low = id.toLowerCase(Locale.ROOT);
            // "paris" finds Europe/Paris too.
            if (low.startsWith(prefix) || (prefix.length() >= 2 && low.substring(low.lastIndexOf('/') + 1).startsWith(prefix))) out.add(id);
            if (out.size() >= 200) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ picker

    /** The time-zone picker dialog (for the hub's Settings menu too). */
    public void openTimezonePicker(ServerPlayer player) {
        openTimezonePicker(player, null, null);
    }

    /** The time-zone picker; {@code back} (may be null) adds a back button that runs it, e.g. to return to Settings. */
    public void openTimezonePicker(ServerPlayer player, Consumer<ServerPlayer> back) {
        openTimezonePicker(player, back, null);
    }

    private void openTimezonePicker(ServerPlayer player, Consumer<ServerPlayer> back, Component error) {
        Text text = plugin.text();
        ZoneId zone = zone(player);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("inspect.timezone.title"));
        menu.text(text.msg(chosen(player) ? "inspect.timezone.current" : "inspect.timezone.current-default",
                Text.unparsed("zone", zone.getId()), Text.unparsed("time", format(System.currentTimeMillis(), player))), 300);
        menu.text(text.msg("inspect.timezone.tip"), 300);
        if (error != null) menu.text(error, 300);
        menu.input(Dialogs.Input.text("zone", text.msg("inspect.timezone.input-label")).initial(zone.getId()).maxLength(64).width(300).build());
        menu.button(text.msg("inspect.timezone.save"), text.msg("inspect.timezone.save-tooltip"), 148, (p, view) -> {
            String raw = view.getText("zone");
            ZoneId picked = parse(raw == null ? "" : raw.replace(' ', '_'));
            if (picked == null) {
                text.sound(p, "error");
                openTimezonePicker(p, back, text.msg("inspect.timezone.unknown", Text.unparsed("input", raw == null ? "" : raw)));
                return;
            }
            set(p, picked);
            text.sound(p, "nick-set");
            openTimezonePicker(p, back, null);
        });
        menu.button(text.msg("inspect.timezone.use-default"), text.msg("inspect.timezone.use-default-tooltip",
                Text.unparsed("zone", fallback.getId())), 148, p -> {
            set(p, null);
            text.sound(p, "click");
            openTimezonePicker(p, back, null);
        });
        for (String id : shortcuts) {
            Component label = text.msg(id.equals(zone.getId()) ? "inspect.timezone.shortcut-selected" : "inspect.timezone.shortcut",
                    Text.unparsed("zone", id), Text.unparsed("city", city(id)));
            menu.button(label, null, 148, p -> {
                set(p, ZoneId.of(id));
                text.sound(p, "nick-set");
                openTimezonePicker(p, back, null);
            });
        }
        menu.columns(2);
        if (back != null) menu.exit(text.msg("menu.back"), back);
        else menu.exit(text.msg("menu.close"), null);
        menu.open(player);
    }

    private static String city(String id) {
        return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
    }
}
