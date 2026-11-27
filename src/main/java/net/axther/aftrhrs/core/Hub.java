package net.axther.aftrhrs.core;

import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The Feather menu (/feather, /menu, Shift+F): the main hub, its group screens (Daily, Community, Style) and Settings.
 *
 * <p>On Paper the hub knew every module. Here modules <b>add their own buttons</b> when they enable, so the hub works
 * with whatever is ported: {@link #add(Entry)} a button into a {@link Group} at an order number (the old layout's
 * numbers are the {@code ORDER_*} constants), {@link #addToggle} a Settings switch, {@link #addSettingsButton} a
 * Settings row that opens something, and feed the greeting's status line with {@link #homeLimit}, {@link #petCount},
 * {@link #requestCount} and {@link #portrait}. Everything a module adds is removed again when the server stops.
 *
 * <p>Menus reached from the hub end with {@link #exitButton} ("← Back" to where the player came from, else "Close").
 */
public final class Hub {
    /** Where a button goes. {@code TRAVEL} (Wild, Go Back) shows in the warps menu, or on the hub without one. */
    public enum Group { MAIN, DAILY, COMMUNITY, STYLE, TRAVEL }

    // The plugin's layout, as order numbers. MAIN: the groups and Settings are placed by the hub itself.
    public static final int ORDER_HOMES = 10, ORDER_WARPS = 20, ORDER_VISIT = 30, ORDER_PETS = 40, ORDER_CLAIMS = 50,
            ORDER_DAILY = 60, ORDER_COMMUNITY = 70, ORDER_STYLE = 80, ORDER_GUIDE = 90, ORDER_SETTINGS = 100;
    public static final int ORDER_CALENDAR = 10, ORDER_QUESTS = 20, ORDER_GOAL = 30;
    public static final int ORDER_BOUNTIES = 10, ORDER_LEADERBOARDS = 20, ORDER_STATS = 30, ORDER_BOSSBOARD = 40,
            ORDER_MAIL = 50, ORDER_TRADE = 60, ORDER_SHOP = 70, ORDER_CHATGAMES = 80, ORDER_FISHING = 90;
    public static final int ORDER_NICK = 10, ORDER_HAT = 20, ORDER_TRAILS = 30, ORDER_SIT = 40, ORDER_LAY = 50,
            ORDER_CRAWL = 60, ORDER_AFK = 70;
    public static final int ORDER_WILD = 10, ORDER_BACK = 20;
    /** Settings toggles: requests, messages, carry, mentions, trades, chat games, damage numbers. */
    public static final int TOGGLE_REQUESTS = 10, TOGGLE_MESSAGES = 20, TOGGLE_CARRY = 30, TOGGLE_MENTIONS = 40,
            TOGGLE_TRADES = 50, TOGGLE_CHATGAMES = 60, TOGGLE_DAMAGE_NUMBERS = 70;
    /** Settings rows after the toggles: time zone, graves, the Companion's HUD screen. */
    public static final int SETTINGS_TIMEZONE = 10, SETTINGS_GRAVES = 20, SETTINGS_HUD = 30;

    /**
     * One hub button. {@code id} is unique (re-adding replaces); label and tooltip are computed per player (badges);
     * {@code sound} is a {@code sounds.*} key played on click; {@code visible} usually checks a permission.
     */
    public record Entry(String id, Group group, int order, Predicate<ServerPlayer> visible,
                        Function<ServerPlayer, Component> label, Function<ServerPlayer, Component> tooltip,
                        String sound, Consumer<ServerPlayer> action) {
    }

    /** A Settings switch: label {@code menu.settings.<key>}, tooltip {@code menu.settings.<key>-tooltip}. */
    public record Toggle(String key, int order, Predicate<ServerPlayer> visible, Predicate<ServerPlayer> enabled, Consumer<ServerPlayer> flip) {
    }

    /** A Settings row that isn't a switch (adds its own button; {@code back} reopens Settings). */
    @FunctionalInterface
    public interface SettingsButton {
        void add(Dialogs.Menu menu, ServerPlayer player, int width, Consumer<ServerPlayer> back);
    }

    private record SettingsRow(String id, int order, Predicate<ServerPlayer> visible, SettingsButton button) {
    }

    private final Feather plugin;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Toggle> toggles = new LinkedHashMap<>();
    private final Map<String, SettingsRow> settingsRows = new LinkedHashMap<>();
    private ToIntFunction<ServerPlayer> homeLimit;
    private Function<ServerPlayer, String> petCount;
    private ToIntFunction<ServerPlayer> requestCount;
    private Function<ServerPlayer, ItemStack> portrait;
    /**
     * Where "← Back" leads for each player who is browsing from the hub: the hub itself, or the section they
     * opened. A typed command clears it, so /homes ends in Close.
     */
    private final Map<UUID, Consumer<ServerPlayer>> menuOrigin = new HashMap<>();

    Hub(Feather plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ registration

    /** Adds (or replaces, by id) a hub button. */
    public void add(Entry entry) {
        entries.put(entry.id(), entry);
    }

    /**
     * A button labeled {@code menu.hub.<key>} with tooltip {@code menu.hub.<key>-tooltip} (id = key), shown to players
     * that pass {@code visible}.
     */
    public void add(String key, Group group, int order, String sound, Predicate<ServerPlayer> visible, Consumer<ServerPlayer> action) {
        Text text = plugin.text();
        add(new Entry(key, group, order, visible, p -> text.msg("menu.hub." + key), p -> text.msg("menu.hub." + key + "-tooltip"), sound, action));
    }

    /** {@link #add(String, Group, int, String, Predicate, Consumer)} visible with a permission node. */
    public void add(String key, Group group, int order, String sound, String permission, Consumer<ServerPlayer> action) {
        add(key, group, order, sound, p -> Perms.has(p, permission), action);
    }

    public void remove(String id) {
        entries.remove(id);
    }

    public void addToggle(Toggle toggle) {
        toggles.put(toggle.key(), toggle);
    }

    public void addSettingsButton(String id, int order, Predicate<ServerPlayer> visible, SettingsButton button) {
        settingsRows.put(id, new SettingsRow(id, order, visible, button));
    }

    /** The homes module: a player's home limit (negative = unlimited), for the greeting's "homes 2/5". */
    public void homeLimit(ToIntFunction<ServerPlayer> f) {
        this.homeLimit = f;
    }

    /** The pets module: "active/cap" (or "" when not applicable) for the greeting. */
    public void petCount(Function<ServerPlayer, String> f) {
        this.petCount = f;
    }

    /** The teleport-request module: how many requests wait for the player (the "✉ n" badge). */
    public void requestCount(ToIntFunction<ServerPlayer> f) {
        this.requestCount = f;
    }

    /** The pets module: the main pet's plushie for the greeting (null: the player's head). */
    public void portrait(Function<ServerPlayer, ItemStack> f) {
        this.portrait = f;
    }

    /** Waiting teleport requests for a player (0 without the module). */
    public int requestCount(ServerPlayer p) {
        return requestCount == null ? 0 : requestCount.applyAsInt(p);
    }

    void clear() {
        entries.clear();
        toggles.clear();
        settingsRows.clear();
        homeLimit = null;
        petCount = null;
        requestCount = null;
        portrait = null;
        menuOrigin.clear();
    }

    void forget(UUID player) {
        menuOrigin.remove(player);
    }

    /** A typed command: menus opened from it end in Close, not Back. */
    void typedCommand(ServerPlayer player) {
        menuOrigin.remove(player.getUUID());
    }

    // ------------------------------------------------------------------ menus

    /** The bottom button of a menu: "← Back" to the hub (or its section) for a player who came from there, else "Close". */
    public void exitButton(Dialogs.Menu menu, ServerPlayer player) {
        Consumer<ServerPlayer> back = menuOrigin.get(player.getUUID());
        if (back == null) menu.exit(plugin.text().msg("menu.close"), null);
        else menu.exit(plugin.text().msg("menu.back"), back);
    }

    /** Adds "Wild" and "Go Back" to the warps menu, for players who can use them. */
    public void addTravelExtras(Dialogs.Menu menu, ServerPlayer player, int width) {
        for (Entry e : visible(Group.TRAVEL, player)) menu.button(e.label().apply(player), e.tooltip().apply(player), width, cue(e.sound(), e.action()));
    }

    /** Runs a button's action by id if the player may see it (the hotkey's "homes" / "teleport" targets). */
    public boolean run(String id, ServerPlayer player) {
        Entry e = entries.get(id);
        if (e == null || !e.visible().test(player)) return false;
        e.action().accept(player);
        return true;
    }

    public void openHub(ServerPlayer player) {
        openHub(player, false);
    }

    /**
     * The main menu. Top: the Aftr Hrs banner, then the player's portrait (their main pet's plushie, else their head)
     * beside a greeting that follows the time of day, weather and moon, and a live status line (homes, pets,
     * requests). Below, two per row, the buttons modules added; groups with two or more entries become one button
     * that opens their own screen ({@link #openSection}), a group with a single entry becomes that entry's button.
     */
    public void openHub(ServerPlayer player, boolean quiet) {
        if (plugin.authPending(player)) return;
        Text text = plugin.text();
        boolean art = hubArt();
        Component banner = art ? HubArt.banner() : null;
        Dialogs.Menu menu = plugin.dialogs().menu(banner != null ? banner : text.msg("menu.hub.title"));
        Profiles.Profile profile = plugin.profiles().get(player);

        String homeCount = String.valueOf(profile.homes().size());
        if (homeLimit != null) {
            int limit = homeLimit.applyAsInt(player);
            homeCount += limit < 0 ? "" : "/" + limit;
        }
        String pets = "";
        if (petCount != null) {
            try {
                String s = petCount.apply(player);
                pets = s == null ? "" : s;
            } catch (RuntimeException ignored) {
            }
        }
        Component status = text.msg(pets.isEmpty() ? "menu.hub.status-no-pets" : "menu.hub.status",
                Text.unparsed("homes", homeCount), Text.unparsed("pets", pets),
                Text.component("requests", text.msg(profile.tpToggledOff ? "menu.hub.status-requests-off" : "menu.hub.status-requests-on")));
        menu.item(portrait(player), text.msg("menu.hub.greeting", Text.component("hello", hello(player, art)),
                Text.component("name", Names.display(player)), Text.component("status", status)), 280);

        for (Shown s : hubButtons(player)) {
            if (s.section() != null) menu.sectionButton(s.section(), s.label(), s.tooltip(), 150, cue(s.sound(), s.action()));
            else menu.button(s.label(), s.tooltip(), 150, cue(s.sound(), s.action()));
        }
        // The Companion mod shows the groups as tabs on this screen; vanilla dialogs open them with their buttons.
        menu.layout("hub");
        for (Group g : List.of(Group.DAILY, Group.COMMUNITY, Group.STYLE)) {
            List<Entry> list = visible(g, player);
            if (list.size() < 2) continue;
            List<Dialogs.Button> buttons = new ArrayList<>();
            for (Entry e : list) {
                Consumer<ServerPlayer> act = cue(e.sound(), e.action());
                buttons.add(new Dialogs.Button(e.label().apply(player), e.tooltip().apply(player), 150, (p, v) -> act.accept(p), null));
            }
            menu.section(key(g), text.msg("menu.hub." + key(g)), buttons);
        }
        menu.columns(2);
        menu.exit(text.msg("menu.close"), p -> menuOrigin.remove(p.getUUID()));
        menuOrigin.put(player.getUUID(), p -> openHub(p, true));
        menu.open(player);
        if (!quiet) text.sound(player, "menu-open");
        plugin.advance(player, "event", "hub");
    }

    /** What the hub shows for one button. */
    private record Shown(Component label, Component tooltip, String sound, Consumer<ServerPlayer> action, String section) {
    }

    private static String key(Group g) {
        return g.name().toLowerCase(Locale.ROOT);
    }

    private static String groupSound(Group g) {
        return switch (g) {
            case STYLE -> "hub-style";
            default -> "hub-social";
        };
    }

    private static int groupOrder(Group g) {
        return switch (g) {
            case DAILY -> ORDER_DAILY;
            case COMMUNITY -> ORDER_COMMUNITY;
            case STYLE -> ORDER_STYLE;
            default -> 0;
        };
    }

    /** The entries of a group this player can see, in order. */
    public List<Entry> visible(Group group, ServerPlayer player) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.group() != group) continue;
            try {
                if (e.visible().test(player)) out.add(e);
            } catch (RuntimeException ex) {
                plugin.getLogger().warn("Hub button {} failed its visibility check", e.id(), ex);
            }
        }
        out.sort(Comparator.comparingInt(Entry::order));
        return out;
    }

    private record Ordered(int order, Shown shown) {
    }

    /** The hub's buttons for this player, in order. */
    private List<Shown> hubButtons(ServerPlayer player) {
        Text text = plugin.text();
        List<Ordered> list = new ArrayList<>();
        boolean warps = false;
        for (Entry e : visible(Group.MAIN, player)) {
            if (e.id().equals("warps")) warps = true;
            list.add(new Ordered(e.order(), shown(e, player)));
        }
        // No warps menu to hold them: Wild and Go Back sit on the hub.
        if (!warps) for (Entry e : visible(Group.TRAVEL, player)) list.add(new Ordered(ORDER_WARPS, shown(e, player)));
        for (Group g : List.of(Group.DAILY, Group.COMMUNITY, Group.STYLE)) {
            List<Entry> in = visible(g, player);
            if (in.size() == 1) list.add(new Ordered(groupOrder(g), shown(in.get(0), player)));
            else if (!in.isEmpty()) {
                list.add(new Ordered(groupOrder(g), new Shown(text.msg("menu.hub." + key(g)), text.msg("menu.hub." + key(g) + "-tooltip"),
                        groupSound(g), p -> openSection(p, g), key(g))));
            }
        }
        if (!visibleToggles(player).isEmpty() || !visibleRows(player).isEmpty()) {
            list.add(new Ordered(ORDER_SETTINGS, new Shown(text.msg("menu.hub.settings"), text.msg("menu.hub.settings-tooltip"),
                    "hub-settings", this::openSettings, null)));
        }
        list.sort(Comparator.comparingInt(Ordered::order));
        List<Shown> out = new ArrayList<>(list.size());
        for (Ordered o : list) out.add(o.shown());
        return out;
    }

    private static Shown shown(Entry e, ServerPlayer p) {
        return new Shown(e.label().apply(p), e.tooltip().apply(p), e.sound(), e.action(), null);
    }

    /**
     * A group's screen: colored title ({@code menu.hub.<key>-title}), a muted line ({@code -body}), the entries two
     * per row, and "← Back" to the hub. Menus opened from here come back here.
     */
    public void openSection(ServerPlayer player, Group group) {
        List<Entry> list = visible(group, player);
        if (list.isEmpty()) {
            openHub(player, true);
            return;
        }
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.hub." + key(group) + "-title"));
        menu.text(text.msg("menu.hub." + key(group) + "-body"), 300);
        for (Entry e : list) menu.button(e.label().apply(player), e.tooltip().apply(player), 150, cue(e.sound(), e.action()));
        menu.columns(2);
        menu.exit(text.msg("menu.back"), p -> openHub(p, true));
        menuOrigin.put(player.getUUID(), p -> openSection(p, group));
        menu.open(player);
    }

    private List<Toggle> visibleToggles(ServerPlayer player) {
        List<Toggle> out = new ArrayList<>();
        for (Toggle t : toggles.values()) if (t.visible().test(player)) out.add(t);
        out.sort(Comparator.comparingInt(Toggle::order));
        return out;
    }

    private List<SettingsRow> visibleRows(ServerPlayer player) {
        List<SettingsRow> out = new ArrayList<>();
        for (SettingsRow r : settingsRows.values()) if (r.visible().test(player)) out.add(r);
        out.sort(Comparator.comparingInt(SettingsRow::order));
        return out;
    }

    /**
     * Settings: one full-width toggle per row, each showing its state. A click flips it (with the same chat note as its
     * command) and reopens Settings quietly; then the other rows (time zone, graves, HUD); "← Back" returns to the hub.
     */
    public void openSettings(ServerPlayer player) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.settings.title"));
        menu.text(text.msg("menu.settings.body"), 300);
        for (Toggle t : visibleToggles(player)) {
            Component label = text.msg(t.enabled().test(player) ? "menu.settings.enabled" : "menu.settings.disabled",
                    Text.component("setting", text.msg("menu.settings." + t.key())));
            menu.button(label, text.msg("menu.settings." + t.key() + "-tooltip"), HubArt.GRID_WIDTH, cue("hub-toggle", p -> {
                t.flip().accept(p);
                openSettings(p);
            }));
        }
        for (SettingsRow r : visibleRows(player)) r.button().add(menu, player, HubArt.GRID_WIDTH, this::openSettings);
        menu.columns(1);
        menu.exit(text.msg("menu.back"), p -> openHub(p, true));
        menu.open(player);
    }

    /** The Companion's hub keybind: the same as Shift+F. */
    public void openHubFromClient(ServerPlayer player) {
        if (!plugin.getConfig().getBoolean("hotkey.enabled", true) || !Perms.has(player, "feather.hotkey")) return;
        openHub(player);
    }

    /** The Companion's HUD settings closed after being opened from the Settings page: show that page again. */
    public void openSettingsFromClient(ServerPlayer player) {
        if (!plugin.getConfig().getBoolean("hotkey.enabled", true) || !Perms.has(player, "feather.hotkey")) return;
        openSettings(player);
    }

    // ------------------------------------------------------------------ pieces

    /** Hub art needs {@code hub.art} on and the art fonts in the mod (else glyphs show as boxes). */
    private boolean hubArt() {
        return plugin.getConfig().getBoolean("hub.art", true) && HubArt.available();
    }

    /** The player's main pet as a plushie when {@code hub.pet-portrait} is on, else their head. */
    private ItemStack portrait(ServerPlayer player) {
        if (portrait != null && plugin.getConfig().getBoolean("hub.pet-portrait", true)) {
            try {
                ItemStack plush = portrait.apply(player);
                if (plush != null && !plush.isEmpty()) return plush;
            } catch (RuntimeException ignored) {
            }
        }
        return Icons.head(player);
    }

    /**
     * A random greeting for the time of day in the player's world ({@code menu.hub.hello.<time>}): rain, dawn,
     * morning, afternoon, dusk or night, or "elsewhere" in worlds without a day cycle. Lines can use {@code <name>},
     * {@code <moon>} (tonight's moon icon) and {@code <phase>} (its name).
     */
    private Component hello(ServerPlayer player, boolean art) {
        Text text = plugin.text();
        ServerLevel world = player.level();
        String time;
        if (world.dimensionType().hasFixedTime()) time = "elsewhere";
        else if (world.isRaining()) time = "rain";
        else {
            long t = Math.floorMod(world.getDefaultClockTime(), 24000L);
            if (t >= 22500 || t < 1000) time = "dawn";
            else if (t < 6000) time = "morning";
            else if (t < 11500) time = "afternoon";
            else if (t < 13000) time = "dusk";
            else time = "night";
        }
        List<String> lines = text.variants("menu.hub.hello." + time);
        if (lines.isEmpty()) lines = text.variants("menu.hub.hello.afternoon");
        String line = lines.isEmpty() ? "<name>" : lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        int phase;
        try {
            phase = world.environmentAttributes().getDimensionValue(EnvironmentAttributes.MOON_PHASE).index();
        } catch (RuntimeException e) {
            phase = 0;
        }
        List<String> phases = text.variants("menu.hub.moon-phases");
        Component moon = art ? HubArt.moon(phase) : text.msg("menu.hub.moon-fallback");
        return text.parse(line, Text.component("name", Names.display(player)), Text.component("moon", moon),
                Text.unparsed("phase", phase < phases.size() ? phases.get(phase) : ""));
    }

    /** Plays a hub button's click sound ({@code sounds.hub-*}), then runs the button. */
    private Consumer<ServerPlayer> cue(String sound, Consumer<ServerPlayer> action) {
        return p -> {
            if (sound != null) plugin.text().sound(p, sound);
            action.accept(p);
        };
    }
}
