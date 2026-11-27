package net.axther.aftrhrs.feature.display;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.LuckPermsHook;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.ScoreTeams;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The tab list (tab.yml): a header and footer with placeholders and a rotating message, each player's list name
 * (LuckPerms prefix, nickname, AFK tag), sorting by rank (scoreboard teams, core {@link ScoreTeams}), and health
 * shown next to names as hearts or a number.
 */
public final class TabModule implements FeatherModule {
    static final String HEALTH_OBJECTIVE = "feather_hp";
    static final String BELOW_OBJECTIVE = "feather_hpb";

    private static volatile TabModule active;

    private final Feather plugin;
    private final ScoreTeams teams;
    private Yaml cfg;
    private Tasks.Task task;
    private Tasks.Task healthTask;
    private String header;
    private String footer;
    private String listName;
    private List<String> rotating = List.of();
    private long rotateMillis;
    private boolean rotateRandom;
    private int rotIndex;
    private long rotChanged;
    private String healthMode;
    private boolean belowName;
    private String belowNameLabel;
    private boolean sortEnabled;
    private List<String> groupOrder = List.of();
    private boolean collisions;
    private final Map<UUID, Integer> lastHealth = new HashMap<>();
    /** The header and footer last sent to each player, and to which connection (a reconnect always gets them again). */
    private final Map<UUID, Sent> lastHeaderFooter = new HashMap<>();

    private record Sent(Object connection, Component header, Component footer) {
    }

    public TabModule(Feather plugin) {
        this.plugin = plugin;
        this.teams = plugin.scoreTeams();
    }

    @Override
    public String name() {
        return "Tab list";
    }

    @Override
    public void enable() {
        TabListNames.init();
        active = this;
        reload();
        plugin.once("tab.events", () -> {
            ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
                TabModule m = active;
                if (m == null) return;
                ServerPlayer p = h.player;
                Tasks.later(2L, () -> {
                    if (!p.hasDisconnected() && active == m) m.update(p);
                });
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                TabModule m = active;
                if (m != null) m.quit(h.player);
            });
            FeatherEvents.NAMES_CHANGED.register(p -> {
                TabModule m = active;
                if (m != null && !p.hasDisconnected()) m.update(p);
            });
            FeatherEvents.AFK_CHANGED.register((p, afk) -> {
                TabModule m = active;
                if (m != null && !p.hasDisconnected()) m.update(p);
            });
        });
    }

    @Override
    public void reload() {
        cfg = plugin.loadResourceConfig("tab.yml");
        header = String.join("\n", lines("header"));
        footer = String.join("\n", lines("footer"));
        listName = cfg.getString("list-name", "<prefix><name><suffix><afk><bounty>");
        // tab.yml files written before bounties still hold the old default untouched: give them the bounty tag too.
        if (listName.equals("<prefix><name><suffix><afk>")) listName = "<prefix><name><suffix><afk><bounty>";
        rotating = cfg.getStringList("rotating.messages");
        rotateMillis = Math.max(1, cfg.getInt("rotating.interval-seconds", 8)) * 1000L;
        rotateRandom = cfg.getString("rotating.order", "in-order").equalsIgnoreCase("random");
        rotIndex = 0;
        rotChanged = System.currentTimeMillis();
        healthMode = cfg.getString("health.tab", "hearts").toLowerCase(Locale.ROOT);
        belowName = cfg.getBoolean("health.below-name", true);
        belowNameLabel = cfg.getString("health.below-name-label", "<#ff6b81>❤");
        sortEnabled = cfg.getBoolean("sort.enabled", true);
        groupOrder = cfg.getStringList("sort.groups").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        collisions = cfg.getBoolean("collisions", true);
        if (Feather.server().getPlayerList() != null) teams.configure(teams.hidingNametags(), collisions);
        else Tasks.later(1L, () -> teams.configure(teams.hidingNametags(), collisions)); // team changes are broadcast
        lastHealth.clear();
        lastHeaderFooter.clear();
        setupObjectives();
        if (task != null) task.cancel();
        int ticks = Math.max(2, cfg.getInt("update-ticks", 20));
        task = Tasks.timer(5L, ticks, this::tick);
        // Health changes show within a few ticks (Paper listened to damage and healing).
        if (healthTask != null) healthTask.cancel();
        healthTask = Tasks.timer(4L, 4L, () -> {
            for (ServerPlayer p : plugin.onlinePlayers()) updateHealth(p);
        });
    }

    private List<String> lines(String key) {
        if (cfg.isList(key)) return cfg.getStringList(key);
        String s = cfg.getString(key);
        return s == null ? List.of() : List.of(s);
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (task != null) task.cancel();
        if (healthTask != null) healthTask.cancel();
        lastHeaderFooter.clear();
        Scoreboard board = board();
        if (board != null) {
            for (String name : List.of(HEALTH_OBJECTIVE, BELOW_OBJECTIVE)) {
                Objective o = board.getObjective(name);
                if (o != null) board.removeObjective(o);
            }
        }
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (!p.hasDisconnected()) p.connection.send(new ClientboundTabListPacket(net.minecraft.network.chat.Component.empty(), net.minecraft.network.chat.Component.empty()));
        }
        TabListNames.clearAll();
    }

    private static Scoreboard board() {
        MinecraftServer s = Feather.serverOrNull();
        return s == null ? null : s.getScoreboard();
    }

    // ------------------------------------------------------------------ health objectives

    private void setupObjectives() {
        Scoreboard board = board();
        if (board == null) return;
        if (Feather.server().getPlayerList() == null) {
            // Still starting (the scoreboard tells players about display slots): once the server ticks.
            Tasks.later(1L, () -> {
                if (active == this) setupObjectives();
            });
            return;
        }
        Objective hp = board.getObjective(HEALTH_OBJECTIVE);
        if (healthMode.equals("off")) {
            if (hp != null) board.removeObjective(hp);
        } else {
            ObjectiveCriteria.RenderType type = healthMode.equals("number") ? ObjectiveCriteria.RenderType.INTEGER : ObjectiveCriteria.RenderType.HEARTS;
            if (hp == null) hp = board.addObjective(HEALTH_OBJECTIVE, ObjectiveCriteria.DUMMY, net.minecraft.network.chat.Component.literal("Health"), type, false, null);
            hp.setRenderType(type);
            board.setDisplayObjective(DisplaySlot.LIST, hp);
        }
        // The below-name score sits under the vanilla nametag, so it only makes sense while that nametag shows.
        Objective below = board.getObjective(BELOW_OBJECTIVE);
        boolean wantBelow = belowName && !teams.hidingNametags();
        if (!wantBelow) {
            if (below != null) board.removeObjective(below);
        } else {
            net.minecraft.network.chat.Component label = Adv.vanilla(plugin.text().parse(belowNameLabel));
            if (below == null) below = board.addObjective(BELOW_OBJECTIVE, ObjectiveCriteria.DUMMY, label, ObjectiveCriteria.RenderType.INTEGER, false, null);
            below.setDisplayName(label);
            board.setDisplayObjective(DisplaySlot.BELOW_NAME, below);
        }
        lastHealth.clear();
    }

    /** Called when the nametag module starts or stops hiding vanilla nametags. */
    public void nametagsChanged() {
        setupObjectives();
    }

    private void updateHealth(ServerPlayer p) {
        int hp = (int) Math.ceil(Math.max(0, p.getHealth()));
        Integer before = lastHealth.put(p.getUUID(), hp);
        if (before != null && before == hp) return;
        Scoreboard board = board();
        if (board == null) return;
        ScoreHolder holder = ScoreHolder.forNameOnly(Names.name(p));
        Objective o = board.getObjective(HEALTH_OBJECTIVE);
        if (o != null) board.getOrCreatePlayerScore(holder, o).set(hp);
        Objective b = board.getObjective(BELOW_OBJECTIVE);
        if (b != null) board.getOrCreatePlayerScore(holder, b).set(hp);
    }

    // ------------------------------------------------------------------ updates

    private void tick() {
        long now = System.currentTimeMillis();
        if (!rotating.isEmpty() && now - rotChanged >= rotateMillis) {
            rotChanged = now;
            if (rotateRandom && rotating.size() > 1) {
                int next;
                do next = ThreadLocalRandom.current().nextInt(rotating.size()); while (next == rotIndex);
                rotIndex = next;
            } else {
                rotIndex = (rotIndex + 1) % rotating.size();
            }
        }
        for (ServerPlayer p : plugin.onlinePlayers()) update(p);
    }

    private Component rotatingMessage(ServerPlayer viewer) {
        if (rotating.isEmpty()) return Component.empty();
        return plugin.placeholders().format(rotating.get(rotIndex % rotating.size()), viewer, viewer);
    }

    /** Refreshes one player's header, footer, list name, health and sorting team. */
    public void update(ServerPlayer p) {
        Placeholders ph = plugin.placeholders();
        Component rot = rotatingMessage(p);
        Component hAdv = ph.format(header, p, p, Text.component("rotating", rot));
        Component fAdv = ph.format(footer, p, p, Text.component("rotating", rot));
        Sent last = lastHeaderFooter.get(p.getUUID());
        if (last == null || last.connection() != p.connection || !hAdv.equals(last.header()) || !fAdv.equals(last.footer())) {
            lastHeaderFooter.put(p.getUUID(), new Sent(p.connection, hAdv, fAdv));
            p.connection.send(new ClientboundTabListPacket(Adv.vanilla(hAdv), Adv.vanilla(fAdv)));
        }
        TabListNames.set(p, ph.format(listName, p, null));
        updateHealth(p);
        if (sortEnabled) teams.assign(p, sortKey(p));
        else teams.ensure(p);
    }

    /** Team key: position of the player's group in {@code sort.groups}, then by LuckPerms weight, then group name. */
    private String sortKey(ServerPlayer p) {
        LuckPermsHook lp = plugin.placeholders().luckPerms();
        if (lp == null) return "500";
        String group;
        try {
            group = lp.group(p).toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "500";
        }
        int index = groupOrder.indexOf(group);
        String key;
        if (index >= 0) key = String.format(Locale.ROOT, "%03d", index);
        else {
            int weight = 0;
            try {
                weight = lp.weight(group);
            } catch (Throwable ignored) {
            }
            // Higher weight first: 999 - weight keeps the order inside the 100-999 range.
            key = String.format(Locale.ROOT, "%03d", Math.max(100, Math.min(999, 999 - weight)));
        }
        String g = group.replaceAll("[^a-z0-9]", "");
        return key + (g.length() > 10 ? g.substring(0, 10) : g);
    }

    private void quit(ServerPlayer p) {
        lastHealth.remove(p.getUUID());
        lastHeaderFooter.remove(p.getUUID());
        teams.remove(p);
        Scoreboard board = board();
        if (board == null) return;
        ScoreHolder holder = ScoreHolder.forNameOnly(Names.name(p));
        for (String name : List.of(HEALTH_OBJECTIVE, BELOW_OBJECTIVE)) {
            Objective o = board.getObjective(name);
            if (o != null) board.resetSinglePlayerScore(holder, o);
        }
    }
}
