package net.axther.aftrhrs.feature.analytics;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Icons;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /leaderboard [category]} (alias {@code /lb}): the top players per stat in a dialog, with medals for the
 * first three and the viewer's own rank. Rankings are computed off the server thread every few minutes and cached;
 * opening a board never sorts anything.
 */
final class LeaderboardMenu implements FeatherCommand {
    /** Category id → the analytics stat it ranks. Order is the menu order. */
    static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    /** Short names accepted by the command. */
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        for (String c : List.of("blocks-mined", "playtime", "mob-kills", "player-kills", "deaths", "distance", "fish-caught",
                "bosses-defeated", "bounties-claimed", "pets-tamed")) CATEGORIES.put(c, c);
        ALIASES.put("blocks", "blocks-mined");
        ALIASES.put("mined", "blocks-mined");
        ALIASES.put("time", "playtime");
        ALIASES.put("mobs", "mob-kills");
        ALIASES.put("kills", "player-kills");
        ALIASES.put("pvp", "player-kills");
        ALIASES.put("fish", "fish-caught");
        ALIASES.put("fishing", "fish-caught");
        ALIASES.put("bosses", "bosses-defeated");
        ALIASES.put("bounties", "bounties-claimed");
        ALIASES.put("pets", "pets-tamed");
        ALIASES.put("travel", "distance");
    }

    private record Board(List<AnalyticsModule.Ranked> top, Map<UUID, Integer> ranks) {
    }

    private final Feather plugin;
    private final AnalyticsModule analytics;
    private volatile Map<String, Board> cache = Map.of();
    private volatile long refreshedAt;
    private Tasks.Task task;
    private int size = 10;
    private Set<String> hidden = Set.of();

    LeaderboardMenu(Feather plugin, AnalyticsModule analytics) {
        this.plugin = plugin;
        this.analytics = analytics;
    }

    void reload() {
        Yaml c = plugin.getConfig();
        size = Math.max(3, Math.min(25, c.getInt("leaderboard.size", 10)));
        Set<String> h = new HashSet<>();
        for (String s : c.getStringList("leaderboard.hidden")) h.add(s.toLowerCase(Locale.ROOT));
        hidden = h;
        long period = Math.max(1, c.getInt("leaderboard.refresh-minutes", 5)) * 1200L;
        stop();
        // The timer ticks on the server thread; the ranking itself runs on the async pool.
        task = Tasks.timer(40L, period, () -> Tasks.async(this::refresh));
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /** Recomputes every board. Runs off the server thread (analytics counters are concurrent maps). */
    void refresh() {
        Map<String, Board> next = new HashMap<>();
        for (Map.Entry<String, String> e : CATEGORIES.entrySet()) {
            List<AnalyticsModule.Ranked> all = analytics.ranking(e.getValue(), hidden);
            Map<UUID, Integer> ranks = new HashMap<>();
            for (int i = 0; i < all.size(); i++) ranks.put(all.get(i).id(), i + 1);
            next.put(e.getKey(), new Board(new ArrayList<>(all.subList(0, Math.min(size, all.size()))), ranks));
        }
        cache = next;
        refreshedAt = System.currentTimeMillis();
    }

    /** A category id from user input (id, short alias or prefix), or null. */
    static String resolve(String input) {
        String in = input.toLowerCase(Locale.ROOT);
        if (CATEGORIES.containsKey(in)) return in;
        if (ALIASES.containsKey(in)) return ALIASES.get(in);
        for (String id : CATEGORIES.keySet()) if (id.startsWith(in)) return id;
        return null;
    }

    // ------------------------------------------------------------------ dialogs

    /** The list of boards. */
    void openCategories(ServerPlayer viewer) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("leaderboard.title"));
        menu.text(text.msg("leaderboard.body", Text.unparsed("updated", ago())), 300);
        for (String id : CATEGORIES.keySet()) {
            if (!analytics.tracking(CATEGORIES.get(id))) continue;
            Board b = cache.get(id);
            Integer rank = b == null ? null : b.ranks().get(viewer.getUUID());
            Component tip = rank == null ? text.msg("leaderboard.tooltip-unranked")
                    : text.msg("leaderboard.tooltip-rank", Text.unparsed("rank", rank));
            menu.button(text.msg("leaderboard.categories." + id), tip, 150, p -> open(p, id));
        }
        menu.columns(2);
        plugin.exitButton(menu, viewer);
        menu.open(viewer);
        text.sound(viewer, "menu-open");
    }

    /** One board: top players with medals, then the viewer's rank. */
    void open(ServerPlayer viewer, String id) {
        Text text = plugin.text();
        String stat = CATEGORIES.get(id);
        Board board = cache.get(id);
        Component label = text.msg("leaderboard.categories." + id);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("leaderboard.board-title", Text.component("category", label)));
        List<AnalyticsModule.Ranked> top = board == null ? List.of() : board.top();
        if (top.isEmpty()) {
            menu.text(text.msg("leaderboard.empty"), 300);
        } else {
            List<Component> rest = new ArrayList<>();
            for (int i = 0; i < top.size(); i++) {
                AnalyticsModule.Ranked r = top.get(i);
                TagResolver[] tags = {
                        Text.unparsed("rank", i + 1), Text.unparsed("player", r.name()),
                        Text.unparsed("value", AnalyticsModule.format(stat, r.value())),
                        Text.component("medal", i < 3 ? text.msg("leaderboard.medal-" + (i + 1)) : Component.empty())};
                if (i < 3) menu.item(Icons.head(r.id(), plugin.player(r.id())), text.msg("leaderboard.podium-row", tags), 240);
                else rest.add(text.msg("leaderboard.row", tags));
            }
            if (!rest.isEmpty()) menu.text(Component.join(JoinConfiguration.newlines(), rest), 300);
        }
        Integer rank = board == null ? null : board.ranks().get(viewer.getUUID());
        String value = AnalyticsModule.format(stat, analytics.get(viewer.getUUID(), stat));
        menu.text(rank == null ? text.msg("leaderboard.you-unranked", Text.unparsed("value", value))
                : text.msg("leaderboard.you", Text.unparsed("rank", rank), Text.unparsed("value", value),
                Text.unparsed("count", board.ranks().size())), 300);
        menu.text(text.msg("leaderboard.updated", Text.unparsed("updated", ago())), 300);
        menu.button(text.msg("leaderboard.all-boards"), null, 150, this::openCategories);
        if (Perms.has(viewer, "feather.stats")) menu.button(text.msg("leaderboard.my-stats"), null, 150, p -> analytics.openStats(p));
        menu.columns(2);
        plugin.exitButton(menu, viewer);
        menu.open(viewer);
        text.sound(viewer, "page");
    }

    private String ago() {
        if (refreshedAt == 0) return plugin.text().raw("leaderboard.never");
        return Placeholders.duration(System.currentTimeMillis() - refreshedAt);
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        if (cache.isEmpty()) refresh();   // first open right after start-up
        if (args.length == 0) {
            openCategories(p);
            return true;
        }
        String id = resolve(args[0]);
        if (id == null) {
            text.send(p, "leaderboard.unknown", Text.unparsed("input", args[0]));
            return true;
        }
        open(p, id);
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>(CATEGORIES.keySet());
        out.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
        return out;
    }
}
