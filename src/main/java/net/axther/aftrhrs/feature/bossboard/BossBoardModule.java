package net.axther.aftrhrs.feature.bossboard;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The weekly boss board ({@code /bossboard}, alias {@code /bosses}): every altar boss kill of the week (boss, party with
 * damage shares, fight time), the kills per boss, the fastest kill per boss and the top boss hunters, plus last week's
 * results. The week turns over at {@code bossboard.reset-day} / {@code reset-hour} in {@code bossboard.time-zone}
 * (default Monday 00:00, server time); then last week's champions are announced and the optional reward commands run.
 * The altar module reports kills through {@link Hooks.BossBoard} ({@link #recordKill}). Data: {@code data/bossboard.yml}.
 */
public final class BossBoardModule implements FeatherModule, FeatherCommand, Hooks.BossBoard {
    /** One altar boss kill. {@code bossName} is the boss's MiniMessage name from altars.yml. */
    record Kill(String boss, String bossName, long time, long duration, List<Hunter> party) {
    }

    private static final class Week {
        long start;
        final List<Kill> kills = new ArrayList<>();

        Week(long start) {
            this.start = start;
        }
    }

    /** A hunter's tally for one week: kills (fights with at least min-share of the damage) and summed damage share. */
    private record Tally(UUID id, String name, int kills, double share) {
    }

    private final Feather plugin;
    private final Path file;
    private Week current, last;
    /** data/bossboard.yml exists but could not be read: never save over it. */
    private boolean unreadable;
    /** A kill not written yet (its async write failed): the next save writes it. */
    private boolean dirty;
    private final Map<String, String> bossNames = new LinkedHashMap<>();
    private Tasks.Task timer;

    private ZoneId zone = ZoneId.systemDefault();
    private DayOfWeek resetDay = DayOfWeek.MONDAY;
    private int resetHour;
    private double minShare;
    private int huntersShown;
    private boolean announceRecords;
    private List<String> rewardTop = List.of(), rewardFastest = List.of();

    public BossBoardModule(Feather plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("bossboard.yml");
    }

    @Override
    public String name() {
        return "Boss board";
    }

    @Override
    public void enable() {
        reload();
        load();
        plugin.bind("bossboard", this);
        plugin.provide(Hooks.BossBoard.class, this);
        plugin.hub().add("bossboard", Hub.Group.COMMUNITY, Hub.ORDER_BOSSBOARD, "hub-social", "feather.bossboard", this::open);
        checkRollover();
        timer = Tasks.timer(1200L, 1200L, this::checkRollover);
    }

    @Override
    public void disable() {
        if (timer != null) timer.cancel();
        timer = null;
        plugin.withdraw(Hooks.BossBoard.class);
        plugin.hub().remove("bossboard");
        save();
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        String z = c.getString("bossboard.time-zone", "");
        try {
            zone = z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z.trim());
        } catch (RuntimeException e) {
            plugin.getLogger().warn("bossboard.time-zone: unknown zone {}; using the server's ({}).", z, ZoneId.systemDefault());
            zone = ZoneId.systemDefault();
        }
        try {
            resetDay = DayOfWeek.valueOf(c.getString("bossboard.reset-day", "MONDAY").trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            plugin.getLogger().warn("bossboard.reset-day must be MONDAY ... SUNDAY; using MONDAY.");
            resetDay = DayOfWeek.MONDAY;
        }
        resetHour = Math.max(0, Math.min(23, c.getInt("bossboard.reset-hour", 0)));
        minShare = Math.max(0, Math.min(1, c.getDouble("bossboard.min-share", 0.05)));
        huntersShown = Math.max(3, Math.min(20, c.getInt("bossboard.hunters-shown", 8)));
        announceRecords = c.getBoolean("bossboard.announce-records", true);
        rewardTop = c.getStringList("bossboard.rewards.top-hunter");
        rewardFastest = c.getStringList("bossboard.rewards.fastest-kill");
        // A data file that could not be read is tried again on /feather reload (after an admin fixed it).
        if (unreadable) load();
    }

    /** The summonable bosses (boss type → MiniMessage name), in altars.yml order. Set by the altar module. */
    @Override
    public void bosses(Map<String, String> names) {
        bossNames.clear();
        bossNames.putAll(names);
    }

    // ------------------------------------------------------------------ weeks

    /** Start (epoch ms) of the board week that contains {@code now}. */
    long weekStart(long now) {
        ZonedDateTime at = Instant.ofEpochMilli(now).atZone(zone);
        ZonedDateTime start = at.toLocalDate().with(TemporalAdjusters.previousOrSame(resetDay)).atTime(resetHour, 0).atZone(zone);
        if (start.isAfter(at)) start = start.minusWeeks(1);
        return start.toInstant().toEpochMilli();
    }

    private long nextReset() {
        return Instant.ofEpochMilli(weekStart(System.currentTimeMillis())).atZone(zone).plusWeeks(1).toInstant().toEpochMilli();
    }

    /** Turns the week over once its reset time has passed (checked every minute and before each kill). */
    private void checkRollover() {
        long now = System.currentTimeMillis();
        long start = weekStart(now);
        if (current == null) current = new Week(start);
        if (current.start < start) rollover(start);
    }

    /**
     * This week becomes last week (announced, rewards given); a new week starts at {@code start}. After a longer
     * downtime the old week is more than a week ago: last week is then empty and nothing is announced.
     */
    private void rollover(long start) {
        Week done = current;
        long prevStart = Instant.ofEpochMilli(start).atZone(zone).minusWeeks(1).toInstant().toEpochMilli();
        boolean wasLastWeek = done.start >= prevStart;
        last = wasLastWeek ? done : new Week(prevStart);
        current = new Week(start);
        save();
        if (wasLastWeek) announceChampions(done);
    }

    private void announceChampions(Week week) {
        if (week.kills.isEmpty()) return;
        Text text = plugin.text();
        List<Component> lines = new ArrayList<>();
        lines.add(text.msg("bossboard.weekly-header"));
        List<Tally> hunters = tallies(week);
        if (!hunters.isEmpty()) {
            int best = hunters.get(0).kills();
            List<Tally> top = hunters.stream().filter(t -> t.kills() == best).toList();
            lines.add(text.msg("bossboard.weekly-hunter", Text.unparsed("player", top.stream().map(Tally::name).collect(Collectors.joining(", "))),
                    Text.unparsed("kills", best)));
            for (Tally t : top) runRewards(rewardTop, t.name(), "");
        }
        for (Map.Entry<String, Kill> e : fastest(week).entrySet()) {
            Kill k = e.getValue();
            lines.add(text.msg("bossboard.weekly-fastest", Text.component("boss", text.parse(k.bossName())),
                    Text.unparsed("time", clock(k.duration())), Text.unparsed("party", partyNames(k))));
            for (Hunter h : counted(k)) runRewards(rewardFastest, h.name(), k.boss());
        }
        lines.add(text.msg("bossboard.weekly-footer"));
        broadcast(Component.join(JoinConfiguration.newlines(), lines));
        plugin.getLogger().info("Boss board: a new week started; last week had {} boss kill(s).", week.kills.size());
    }

    /** Bukkit.broadcast: every player and the console. */
    private void broadcast(Component message) {
        for (ServerPlayer p : plugin.onlinePlayers()) Adv.send(p, message);
        MinecraftServer server = Feather.serverOrNull();
        if (server != null) server.sendSystemMessage(Adv.vanilla(message));
    }

    private void runRewards(List<String> commands, String player, String boss) {
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return;
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String cmd = raw.replace("<player>", player).replace("<boss>", boss).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            } catch (RuntimeException e) {
                plugin.getLogger().warn("Boss board reward command failed: {} ({})", cmd, e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ kills

    /**
     * Records an altar boss kill. {@code party} are the players who damaged it with their share of the damage;
     * those below {@code bossboard.min-share} still show in the data but don't count as hunters.
     */
    @Override
    public void recordKill(String boss, String bossName, long startedAt, long endedAt, List<Hunter> party) {
        checkRollover();
        List<Hunter> sorted = new ArrayList<>(party);
        sorted.sort(Comparator.comparingDouble(Hunter::share).reversed());
        Kill kill = new Kill(boss, bossName == null ? boss : bossName, endedAt, Math.max(0, endedAt - startedAt), sorted);
        Kill before = fastest(current).get(boss);
        current.kills.add(kill);
        dirty = true;
        saveIfDirtyAsync();
        plugin.getLogger().info("Boss board: {} defeated in {} by {}.", boss, clock(kill.duration()), partyNames(kill));
        if (announceRecords && (before == null || kill.duration() < before.duration())) {
            broadcast(plugin.text().msg(before == null ? "bossboard.first-kill" : "bossboard.new-record",
                    Text.component("boss", plugin.text().parse(kill.bossName())), Text.unparsed("time", clock(kill.duration())),
                    Text.unparsed("party", partyNames(kill))));
        }
    }

    /** The players who count for a kill (at least min-share of the damage; the top damager always counts). */
    private List<Hunter> counted(Kill k) {
        List<Hunter> out = new ArrayList<>();
        for (Hunter h : k.party()) if (h.share() >= minShare || out.isEmpty()) out.add(h);
        return out;
    }

    private String partyNames(Kill k) {
        return counted(k).stream().map(Hunter::name).collect(Collectors.joining(", "));
    }

    /** Fastest kill per boss in a week, by boss type. */
    private static Map<String, Kill> fastest(Week week) {
        Map<String, Kill> out = new LinkedHashMap<>();
        if (week == null) return out;
        for (Kill k : week.kills) out.merge(k.boss(), k, (a, b) -> b.duration() < a.duration() ? b : a);
        return out;
    }

    /** Hunters of a week, most kills first (then the bigger summed damage share). */
    private List<Tally> tallies(Week week) {
        Map<UUID, Tally> map = new HashMap<>();
        if (week == null) return List.of();
        for (Kill k : week.kills) {
            for (Hunter h : counted(k)) {
                Tally t = map.get(h.id());
                map.put(h.id(), t == null ? new Tally(h.id(), h.name(), 1, h.share())
                        : new Tally(h.id(), h.name(), t.kills() + 1, t.share() + h.share()));
            }
        }
        List<Tally> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparingInt(Tally::kills).reversed().thenComparing(Comparator.comparingDouble(Tally::share).reversed()));
        return list;
    }

    /** "45s", "4m 05s", "1h 02m". */
    static String clock(long ms) {
        long s = Math.max(0, Math.round(ms / 1000.0));
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + String.format(Locale.ROOT, "%02d", s % 60) + "s";
        return (m / 60) + "h " + String.format(Locale.ROOT, "%02d", m % 60) + "m";
    }

    // ------------------------------------------------------------------ dialog

    /** This week's board. */
    public void open(ServerPlayer viewer) {
        open(viewer, false);
    }

    void open(ServerPlayer viewer, boolean lastWeek) {
        checkRollover();
        Text text = plugin.text();
        Week week = lastWeek ? last : current;
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg(lastWeek ? "bossboard.title-last" : "bossboard.title"));
        DateTimeFormatter day = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
        if (lastWeek) {
            menu.text(week == null ? text.msg("bossboard.body-last-none")
                    : text.msg("bossboard.body-last", Text.unparsed("date", Instant.ofEpochMilli(week.start).atZone(zone).format(day))), 300);
        } else {
            menu.text(text.msg("bossboard.body", Text.unparsed("date", Instant.ofEpochMilli(week.start).atZone(zone).format(day)),
                    Text.unparsed("reset", Placeholders.duration(nextReset() - System.currentTimeMillis()))), 300);
        }

        // Kills and the fastest kill per boss: every summonable boss, then any other boss that has kills.
        Map<String, Kill> fastest = fastest(week);
        Map<String, Integer> kills = new HashMap<>();
        Map<String, String> names = new LinkedHashMap<>(bossNames);
        if (week != null) {
            for (Kill k : week.kills) {
                kills.merge(k.boss(), 1, Integer::sum);
                names.putIfAbsent(k.boss(), k.bossName());
            }
        }
        List<Component> rows = new ArrayList<>();
        for (Map.Entry<String, String> b : names.entrySet()) {
            Component boss = text.parse(b.getValue());
            Kill best = fastest.get(b.getKey());
            if (best == null) {
                rows.add(text.msg("bossboard.boss-none", Text.component("boss", boss)));
            } else {
                rows.add(text.msg("bossboard.boss-row", Text.component("boss", boss), Text.unparsed("kills", kills.getOrDefault(b.getKey(), 0)),
                        Text.unparsed("time", clock(best.duration())), Text.unparsed("party", partyNames(best))));
            }
        }
        if (rows.isEmpty()) rows.add(text.msg("bossboard.no-bosses"));
        menu.text(Component.join(JoinConfiguration.newlines(), rows), 300);

        // Top hunters.
        List<Tally> hunters = tallies(week);
        List<Component> top = new ArrayList<>();
        top.add(text.msg("bossboard.hunters-title"));
        if (hunters.isEmpty()) top.add(text.msg("bossboard.hunters-empty"));
        int rank = 0;
        Integer mine = null;
        for (int i = 0; i < hunters.size(); i++) {
            Tally t = hunters.get(i);
            if (t.id().equals(viewer.getUUID())) mine = i;
            if (rank >= huntersShown) continue;
            rank++;
            top.add(text.msg("bossboard.hunter-row", Text.unparsed("rank", rank), Text.unparsed("player", t.name()),
                    Text.unparsed("kills", t.kills()),
                    Text.component("medal", rank <= 3 ? text.msg("leaderboard.medal-" + rank) : Component.empty())));
        }
        menu.text(Component.join(JoinConfiguration.newlines(), top), 300);
        menu.text(mine == null ? text.msg("bossboard.you-none")
                : text.msg("bossboard.you", Text.unparsed("rank", mine + 1), Text.unparsed("kills", hunters.get(mine).kills())), 300);

        if (lastWeek) menu.button(text.msg("bossboard.this-week"), null, 150, p -> open(p, false));
        else menu.button(text.msg("bossboard.last-week"), text.msg("bossboard.last-week-tooltip"), 150, p -> open(p, true));
        plugin.exitButton(menu, viewer);
        menu.open(viewer);
        text.sound(viewer, lastWeek ? "page" : "menu-open");
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("rollover") || sub.equals("reset")) {
            if (!sender.hasPermission("feather.bossboard.admin")) {
                text.send(sender, "general.no-permission");
                return true;
            }
            checkRollover();
            if (sub.equals("reset")) {
                current = new Week(current.start);
                last = null;
                save();
                text.send(sender, "bossboard.cleared");
                return true;
            }
            // End this week now: it becomes last week (announced, rewards), a fresh week runs until the next reset.
            Week done = current;
            last = done;
            current = new Week(System.currentTimeMillis());
            save();
            announceChampions(done);
            text.send(sender, "bossboard.rolled-over", Text.unparsed("kills", done.kills.size()));
            return true;
        }
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        open(p, sub.equals("last") || sub.equals("lastweek"));
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("last"));
        if (sender.hasPermission("feather.bossboard.admin")) out.addAll(List.of("rollover", "reset"));
        out.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ disk

    private void load() {
        Yaml yml = SafeYaml.read(file, plugin.getLogger(), "boss board kills");
        if (yml == null) {
            unreadable = true;
            if (current == null) current = new Week(weekStart(System.currentTimeMillis()));
            return;
        }
        unreadable = false;
        Week cur = readWeek(yml.getConfigurationSection("current"));
        if (cur != null) current = cur;
        else if (current == null) current = new Week(weekStart(System.currentTimeMillis()));
        Week prev = readWeek(yml.getConfigurationSection("last"));
        if (prev != null) last = prev;
    }

    private Week readWeek(Yaml s) {
        if (s == null) return null;
        Week w = new Week(s.getLong("start"));
        for (Map<?, ?> m : s.getMapList("kills")) {
            try {
                List<Hunter> party = new ArrayList<>();
                if (m.get("party") instanceof List<?> list) {
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> h)) continue;
                        party.add(new Hunter(UUID.fromString(String.valueOf(h.get("id"))), String.valueOf(h.get("name")),
                                h.get("share") instanceof Number n ? n.doubleValue() : 0));
                    }
                }
                String boss = String.valueOf(m.get("boss"));
                w.kills.add(new Kill(boss, m.get("name") == null ? boss : String.valueOf(m.get("name")),
                        ((Number) m.get("time")).longValue(), ((Number) m.get("duration")).longValue(), party));
            } catch (RuntimeException e) {
                plugin.getLogger().warn("Skipping a broken kill in bossboard.yml: {} ({})", m, e.toString());
            }
        }
        return w;
    }

    private static final String HEADER = "# Feather boss board: this week's and last week's altar boss kills. Times are epoch ms.\n\n";

    /**
     * Writes the board now and waits for it (week rollovers and admin resets, before champions are rewarded; shutdown).
     * False when it couldn't be written.
     */
    private boolean save() {
        if (unreadable || current == null) return false;
        boolean ok = SafeYaml.writeSync(file, HEADER, snapshot(), plugin.getLogger());
        if (ok) dirty = false;
        return ok;
    }

    /** A kill: snapshot now, dumped and written on the data thread (dirty again if that fails). */
    private void saveIfDirtyAsync() {
        if (unreadable || current == null || !dirty) return;
        dirty = false;
        SafeYaml.writeAsync(file, HEADER, snapshot(), plugin.getLogger()).thenAccept(ok -> {
            if (!ok) Tasks.sync(() -> dirty = true);
        });
    }

    /** bossboard.yml's tree as plain maps (server thread). */
    private Object snapshot() {
        Yaml yml = new Yaml();
        writeWeek(yml, "current", current);
        if (last != null) writeWeek(yml, "last", last);
        return yml.snapshot();
    }

    private static void writeWeek(Yaml yml, String path, Week w) {
        yml.set(path + ".start", w.start);
        List<Map<String, Object>> kills = new ArrayList<>();
        for (Kill k : w.kills) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("boss", k.boss());
            m.put("name", k.bossName());
            m.put("time", k.time());
            m.put("duration", k.duration());
            List<Map<String, Object>> party = new ArrayList<>();
            for (Hunter h : k.party()) {
                Map<String, Object> hm = new LinkedHashMap<>();
                hm.put("id", h.id().toString());
                hm.put("name", h.name());
                hm.put("share", Math.round(h.share() * 1000) / 1000.0);
                party.add(hm);
            }
            m.put("party", party);
            kills.add(m);
        }
        yml.set(path + ".kills", kills);
    }
}
