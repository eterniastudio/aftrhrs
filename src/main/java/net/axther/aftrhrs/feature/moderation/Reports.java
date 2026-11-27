package net.axther.aftrhrs.feature.moderation;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.JoinDigest;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Player reports: {@code /report <player> <reason>} tells online staff (with Teleport and View buttons) and keeps
 * the report in {@code data/reports.yml}; {@code /reports} lists the open ones in a dialog (Teleport, View, Close).
 * Staff who join while reports are open get a one-line notice.
 */
public final class Reports implements FeatherCommand {
    /** Most reports kept; past it the oldest closed ones are dropped. */
    private static final int KEEP = 500;
    /** Most open reports listed in the /reports dialog. */
    private static final int LIST_MAX = 15;

    static final class Report {
        int id;
        long time;
        UUID reporter;
        String reporterName;
        UUID target;
        String targetName;
        String reason;
        String world;
        double x, y, z;
        boolean open = true;
        String closedBy;
        long closedAt;
    }

    private static volatile Reports active;

    private final Feather plugin;
    private final ModerationModule mod;
    private final Path file;
    private final List<Report> reports = new ArrayList<>();
    private final Map<UUID, Long> lastReport = new HashMap<>();
    private int nextId = 1;
    /** reports.yml exists but couldn't be read: never save over it. */
    private boolean unreadable;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AftrHrs-Reports-IO");
        t.setDaemon(true);
        return t;
    });

    Reports(Feather plugin, ModerationModule mod) {
        this.plugin = plugin;
        this.mod = mod;
        this.file = plugin.dataFile("reports.yml");
    }

    void enable() {
        load();
        active = this;
        plugin.bind("report", this);
        plugin.bind("reports", this);
        JoinDigest.register("reports", 90, this::collectJoin);
    }

    void disable() {
        if (active == this) active = null;
        JoinDigest.unregister("reports");
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** All reports ever made against a player (open and closed). */
    public int countAgainst(UUID id) {
        int n = 0;
        for (Report r : reports) if (id.equals(r.target)) n++;
        return n;
    }

    private List<Report> open() {
        List<Report> out = new ArrayList<>();
        for (Report r : reports) if (r.open) out.add(r);
        return out;
    }

    private Report byId(String input) {
        try {
            int id = Integer.parseInt(input.startsWith("#") ? input.substring(1) : input);
            for (Report r : reports) if (r.id == id) return r;
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        if (CommandBridge.nameOf(label).equals("report")) report(sender, args);
        else reports(sender, args);
        return true;
    }

    private void report(Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            text.send(p, "mod.report-usage");
            return;
        }
        OfflinePlayer t = mod.target(sender, args[0]);
        if (t == null) return;
        if (t.id().equals(p.getUUID())) {
            text.send(p, "mod.report-self");
            return;
        }
        long cooldown = Math.max(0, plugin.getConfig().getLong("moderation.report-cooldown-seconds", 60)) * 1000L;
        Long last = lastReport.get(p.getUUID());
        long now = System.currentTimeMillis();
        if (last != null && now - last < cooldown && !Perms.has(p, "feather.reports")) {
            text.send(p, "mod.report-cooldown", Text.unparsed("time", Placeholders.duration(cooldown - (now - last) + 999)));
            text.sound(p, "error");
            return;
        }
        lastReport.put(p.getUUID(), now);
        Report r = new Report();
        r.id = nextId++;
        r.time = now;
        r.reporter = p.getUUID();
        r.reporterName = Names.name(p);
        r.target = t.id();
        r.targetName = ModerationModule.nameOf(t, args[0]);
        r.reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        ServerPlayer at = t.online() != null ? t.online() : p;
        r.world = Worlds.name(at.level());
        r.x = at.getX();
        r.y = at.getY();
        r.z = at.getZ();
        reports.add(r);
        prune();
        save();
        text.send(p, "mod.report-sent", Text.unparsed("name", r.targetName));
        text.sound(p, "click");
        TagResolver[] tags = {Text.unparsed("id", r.id), Text.unparsed("reporter", r.reporterName), Text.unparsed("name", r.targetName),
                Text.unparsed("reason", r.reason),
                Text.component("teleport", text.button("mod.report-button-tp", "/reports tp " + r.id)),
                Text.component("view", text.button("mod.report-button-view", "/reports view " + r.id))};
        Component line = text.msg("mod.report-notify", tags);
        for (ServerPlayer s : plugin.onlinePlayers()) {
            if (!Perms.has(s, "feather.reports")) continue;
            Adv.send(s, line);
            text.sound(s, "request-received");
        }
        plugin.getLogger().info("[Moderation] Report #{}: {} reported {}: {}", r.id, r.reporterName, r.targetName, r.reason);
    }

    private void reports(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length >= 2) {
            Report r = byId(args[1]);
            if (r == null) {
                text.send(sender, "mod.report-unknown", Text.unparsed("input", args[1]));
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "tp", "teleport" -> teleport(sender, r);
                case "view" -> view(sender, r);
                case "close" -> close(sender, r);
                default -> text.send(sender, "mod.reports-usage");
            }
            return;
        }
        List<Report> open = open();
        ServerPlayer p = sender.player();
        if (p == null) {
            if (open.isEmpty()) text.send(sender, "mod.reports-none");
            else for (Report r : open) sender.sendMessage(line(r, sender));
            return;
        }
        openList(p);
    }

    private Component line(Report r, Sender viewer) {
        return plugin.text().msg("mod.reports-entry", Text.unparsed("id", r.id), Text.unparsed("name", r.targetName),
                Text.unparsed("reporter", r.reporterName), Text.unparsed("reason", r.reason),
                Text.unparsed("date", mod.date(r.time, viewer)));
    }

    /** The open reports, newest first, each with Teleport, View and Close. */
    void openList(ServerPlayer p) {
        Text text = plugin.text();
        List<Report> open = open();
        java.util.Collections.reverse(open);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("mod.reports-title", Text.unparsed("count", open.size())));
        if (open.isEmpty()) menu.text(text.msg("mod.reports-none-body"), 300);
        int shown = 0;
        Sender viewer = Sender.of(p);
        for (Report r : open) {
            if (shown++ >= LIST_MAX) {
                menu.text(text.msg("mod.reports-more", Text.unparsed("count", open.size() - LIST_MAX)), 300);
                break;
            }
            menu.text(line(r, viewer), 300);
        }
        shown = 0;
        for (Report r : open) {
            if (shown++ >= LIST_MAX) break;
            TagResolver id = Text.unparsed("id", r.id);
            menu.button(text.msg("mod.reports-button-tp", id), text.msg("mod.reports-button-tp-tooltip", Text.unparsed("name", r.targetName)), 100,
                    q -> teleport(Sender.of(q), r));
            menu.button(text.msg("mod.reports-button-view", id), null, 100, q -> view(Sender.of(q), r));
            menu.button(text.msg("mod.reports-button-close", id), null, 100, q -> {
                close(Sender.of(q), r);
                openList(q);
            });
        }
        menu.columns(3);
        plugin.exitButton(menu, p);
        menu.open(p);
        text.sound(p, "menu-open");
    }

    private void view(Sender sender, Report r) {
        Text text = plugin.text();
        TagResolver[] tags = {Text.unparsed("id", r.id), Text.unparsed("name", r.targetName), Text.unparsed("reporter", r.reporterName),
                Text.unparsed("reason", r.reason), Text.unparsed("date", mod.date(r.time, sender)),
                Text.unparsed("location", r.world + " " + (int) Math.floor(r.x) + ", " + (int) Math.floor(r.y) + ", " + (int) Math.floor(r.z)),
                Text.unparsed("state", r.open ? text.raw("mod.report-state-open")
                        : text.raw("mod.report-state-closed").replace("<staff>", String.valueOf(r.closedBy))),
                Text.unparsed("count", countAgainst(r.target))};
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "mod.report-details", tags);
            return;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("mod.report-details-title", tags));
        menu.text(text.msg("mod.report-details", tags), 300);
        menu.button(text.msg("mod.report-details-tp"), null, 150, q -> teleport(Sender.of(q), r));
        if (Perms.has(p, "feather.history")) menu.button(text.msg("mod.report-details-history"), null, 150,
                q -> CommandBridge.dispatch(Sender.of(q), "history " + r.targetName));
        if (Perms.has(p, "feather.whois")) menu.button(text.msg("mod.report-details-whois"), null, 150,
                q -> CommandBridge.dispatch(Sender.of(q), "whois " + r.targetName));
        if (r.open) menu.button(text.msg("mod.report-details-close"), null, 150, q -> {
            close(Sender.of(q), r);
            openList(q);
        });
        menu.columns(2);
        menu.exit(text.msg("menu.back"), this::openList);
        menu.open(p);
    }

    private void teleport(Sender sender, Report r) {
        Text text = plugin.text();
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return;
        }
        ServerPlayer target = plugin.player(r.target);
        Loc to;
        if (target != null) to = Loc.of(target);
        else {
            ServerLevel w = Worlds.resolve(r.world);
            if (w == null) {
                text.send(p, "util.unknown-world", Text.unparsed("input", r.world));
                return;
            }
            to = new Loc(w, r.x, r.y, r.z, p.getYRot(), p.getXRot());
        }
        plugin.movePlayer(p, to);
        text.send(p, target != null ? "mod.report-tp" : "mod.report-tp-offline", Text.unparsed("name", r.targetName), Text.unparsed("id", r.id));
        text.sound(p, "teleport");
    }

    private void close(Sender sender, Report r) {
        Text text = plugin.text();
        if (!r.open) {
            text.send(sender, "mod.report-already-closed", Text.unparsed("id", r.id));
            return;
        }
        r.open = false;
        r.closedBy = ModerationModule.staffName(sender);
        r.closedAt = System.currentTimeMillis();
        save();
        text.send(sender, "mod.report-closed", Text.unparsed("id", r.id), Text.unparsed("name", r.targetName));
        mod.notifyStaff(sender, "mod.notify-report-closed", Text.unparsed("staff", r.closedBy), Text.unparsed("id", r.id),
                Text.unparsed("name", r.targetName));
        ServerPlayer reporter = plugin.player(r.reporter);
        if (reporter != null) text.send(reporter, "mod.report-handled", Text.unparsed("name", r.targetName));
    }

    /** Join digest (order 90): staff hear how many reports are open. */
    private void collectJoin(ServerPlayer p, List<JoinDigest.Line> out) {
        if (!Perms.has(p, "feather.reports")) return;
        int open = open().size();
        if (open == 0) return;
        Component line = JoinDigest.render("mod.reports-join", Text.unparsed("count", open),
                Text.component("open", plugin.text().button("mod.reports-join-button", "/reports")));
        if (line != null) out.add(new JoinDigest.Line(90, line, null));
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (CommandBridge.nameOf(label).equals("report")) {
            if (args.length == 1) out.addAll(plugin.completeOthers(sender, args[0]));
        } else if (args.length == 1) {
            out.addAll(List.of("tp", "view", "close"));
        } else if (args.length == 2) {
            for (Report r : open()) out.add(String.valueOf(r.id));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ disk

    private void prune() {
        while (reports.size() > KEEP) {
            Report oldestClosed = null;
            for (Report r : reports) if (!r.open) {
                oldestClosed = r;
                break;
            }
            if (oldestClosed == null) break;
            reports.remove(oldestClosed);
        }
    }

    private void load() {
        reports.clear();
        unreadable = false;
        if (!Files.exists(file)) return;
        Yaml yml = SafeYaml.read(file, plugin.getLogger(), "player reports");
        if (yml == null) {
            plugin.getLogger().error("{} is left untouched; new reports are not saved until the file is fixed and the server restarts.", file);
            unreadable = true;
            return;
        }
        nextId = Math.max(1, yml.getInt("next-id", 1));
        for (Map<?, ?> m : yml.getMapList("reports")) {
            try {
                Report r = new Report();
                r.id = ((Number) m.get("id")).intValue();
                r.time = ((Number) m.get("time")).longValue();
                r.reporter = UUID.fromString(String.valueOf(m.get("reporter")));
                r.reporterName = String.valueOf(m.get("reporter-name"));
                r.target = UUID.fromString(String.valueOf(m.get("target")));
                r.targetName = String.valueOf(m.get("target-name"));
                r.reason = String.valueOf(m.get("reason"));
                r.world = String.valueOf(m.get("world"));
                r.x = ((Number) m.get("x")).doubleValue();
                r.y = ((Number) m.get("y")).doubleValue();
                r.z = ((Number) m.get("z")).doubleValue();
                r.open = !Boolean.FALSE.equals(m.get("open"));
                if (m.get("closed-by") != null) r.closedBy = String.valueOf(m.get("closed-by"));
                if (m.get("closed-at") instanceof Number n) r.closedAt = n.longValue();
                reports.add(r);
                nextId = Math.max(nextId, r.id + 1);
            } catch (RuntimeException e) {
                plugin.getLogger().warn("Skipping a broken entry in reports.yml: {} ({})", m, e.toString());
            }
        }
    }

    /** Snapshots every report on the calling thread and writes the file in the background. */
    private void save() {
        if (unreadable) return;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Report r : reports) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("time", r.time);
            m.put("reporter", r.reporter.toString());
            m.put("reporter-name", r.reporterName);
            m.put("target", r.target.toString());
            m.put("target-name", r.targetName);
            m.put("reason", r.reason);
            m.put("world", r.world);
            m.put("x", r.x);
            m.put("y", r.y);
            m.put("z", r.z);
            m.put("open", r.open);
            if (r.closedBy != null) m.put("closed-by", r.closedBy);
            if (r.closedAt > 0) m.put("closed-at", r.closedAt);
            list.add(m);
        }
        Yaml yml = new Yaml();
        yml.set("next-id", nextId);
        yml.set("reports", list);
        // A detached copy taken here (server thread); turned into text on the reports writer.
        Object snap = yml.snapshot();
        Runnable write = () -> SafeYaml.write(file, "# Feather player reports (/report, /reports).\n" + Yaml.dump(snap), plugin.getLogger());
        if (io.isShutdown()) write.run();
        else io.execute(write);
    }
}
