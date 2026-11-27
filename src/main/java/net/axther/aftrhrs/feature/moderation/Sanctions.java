package net.axther.aftrhrs.feature.moderation;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Kicks, bans and warnings: /kick, /ban, /tempban, /unban (/pardon), /banlist, /warn, /warnings, /delwarn and /history.
 *
 * <p>Bans go into the server's own ban list ({@code banned-players.json}, the same file Paper used) with their expiry,
 * so vanilla enforces them and they hold even without Feather; Feather only swaps the login screen for its own
 * (reason, who, time left). These commands replace vanilla's /ban, /pardon, /kick and /banlist, and vanilla's
 * {@code /ban-ip} still works beside them. Warnings and the moderation history live in each player's Feather profile.
 * {@code moderation.warn-actions} can turn the n-th warning into a kick, mute or ban.
 */
public final class Sanctions implements FeatherCommand {
    private static volatile Sanctions active;

    private final Feather plugin;
    private final ModerationModule mod;

    Sanctions(Feather plugin, ModerationModule mod) {
        this.plugin = plugin;
        this.mod = mod;
    }

    void enable() {
        active = this;
        for (String c : List.of("kick", "ban", "tempban", "unban", "banlist", "warn", "warnings", "delwarn", "history")) plugin.bind(c, this);
        plugin.once("sanctions.events", () -> {
            FeatherEvents.PLAYER_LOGIN.register((profile, address, current) -> {
                Sanctions s = active;
                return s == null || current == null ? current : s.loginScreen(profile, current);
            });
            ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
                Sanctions s = active;
                if (s != null) s.joined(h.player);
            });
        });
    }

    static UserBanList bans() {
        return Feather.server().getPlayerList().getBans();
    }

    static NameAndId profile(OfflinePlayer p) {
        return new NameAndId(p.id(), p.name() == null ? "" : p.name());
    }

    /** The running ban of a player, or null (expired bans count as none). */
    static UserBanListEntry banOf(OfflinePlayer p) {
        UserBanListEntry e = bans().get(profile(p));
        if (e == null) return null;
        Date exp = e.getExpires();
        return exp != null && exp.getTime() <= System.currentTimeMillis() ? null : e;
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        switch (CommandBridge.nameOf(label)) {
            case "kick" -> kick(sender, args);
            case "ban" -> ban(sender, args, false);
            case "tempban" -> ban(sender, args, true);
            case "unban" -> unban(sender, args);
            case "banlist" -> banlist(sender);
            case "warn" -> warn(sender, args);
            case "warnings" -> warnings(sender, args);
            case "delwarn" -> delwarn(sender, args);
            case "history" -> history(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String rest(String[] args, int from) {
        return args.length > from ? String.join(" ", Arrays.copyOfRange(args, from, args.length)) : "";
    }

    // ------------------------------------------------------------------ kick

    private void kick(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            text.send(sender, "mod.kick-usage");
            return;
        }
        ServerPlayer t = plugin.findPlayer(sender, args[0]);
        if (t == null) {
            plugin.notFound(sender, args[0]);
            return;
        }
        if (mod.exempt(sender, OfflinePlayer.of(t))) {
            text.send(sender, "mod.exempt");
            return;
        }
        kick(sender, t, rest(args, 1), ModerationModule.staffName(sender));
    }

    private void kick(Sender sender, ServerPlayer t, String reason, String staff) {
        Text text = plugin.text();
        String shown = reason.isEmpty() ? text.raw("mod.no-reason") : reason;
        disconnect(t, text.msg("mod.kick-screen", server(), Text.unparsed("reason", shown), Text.unparsed("staff", staff)));
        text.send(sender, "mod.kicked", Text.unparsed("name", Names.name(t)), Text.unparsed("reason", shown));
        mod.log(t.getUUID(), "kick", staff, reason, 0);
        mod.notifyStaff(sender, "mod.notify-kick", Text.unparsed("staff", staff), Text.unparsed("name", Names.name(t)),
                Text.unparsed("reason", shown));
    }

    /** Disconnects a player with a screen; their leaving is not "logging out while frozen". */
    void disconnect(ServerPlayer p, Component screen) {
        if (mod.freeze() != null) mod.freeze().markKicked(p.getUUID());
        p.connection.disconnect(Adv.vanilla(screen));
    }

    private TagResolver server() {
        return Text.unparsed("server", plugin.placeholders().serverName());
    }

    // ------------------------------------------------------------------ bans

    private void ban(Sender sender, String[] args, boolean temp) {
        Text text = plugin.text();
        if (args.length == 0 || (temp && args.length < 2)) {
            text.send(sender, temp ? "mod.tempban-usage" : "mod.ban-usage");
            return;
        }
        OfflinePlayer t = mod.target(sender, args[0]);
        if (t == null) return;
        if (mod.exempt(sender, t)) {
            text.send(sender, "mod.exempt");
            return;
        }
        long duration = -1;
        int reasonStart = 1;
        if (temp || (args.length > 1 && Placeholders.looksLikeDuration(args[1]))) {
            duration = mod.durationArg(sender, args[1], !temp);
            if (duration == 0) return;
            reasonStart = 2;
        }
        ban(sender, t, ModerationModule.nameOf(t, args[0]), duration, rest(args, reasonStart), ModerationModule.staffName(sender));
    }

    /** Bans (duration -1 = for good) through the server's ban list and kicks the player if online. */
    void ban(Sender sender, OfflinePlayer t, String name, long duration, String reason, String staff) {
        Text text = plugin.text();
        Date until = duration < 0 ? null : new Date(System.currentTimeMillis() + duration);
        String shown = reason.isEmpty() ? text.raw("mod.no-reason") : reason;
        bans().add(new UserBanListEntry(new NameAndId(t.id(), name), new Date(), staff, until, reason.isEmpty() ? null : reason));
        ServerPlayer online = t.online();
        if (online != null) disconnect(online, banScreen(shown, staff, until == null ? -1 : until.getTime()));
        String time = mod.length(duration);
        text.send(sender, "mod.banned", Text.unparsed("name", name), Text.unparsed("time", time), Text.unparsed("reason", shown));
        mod.log(t.id(), duration < 0 ? "ban" : "tempban", staff, reason, until == null ? -1 : until.getTime());
        mod.notifyStaff(sender, "mod.notify-ban", Text.unparsed("staff", staff), Text.unparsed("name", name),
                Text.unparsed("time", time), Text.unparsed("reason", shown));
    }

    /** The disconnect screen of a banned player; {@code until} -1 = permanent. */
    Component banScreen(String reason, String staff, long until) {
        Text text = plugin.text();
        String left = until < 0 ? text.raw("mod.ban-screen-forever")
                : Placeholders.duration(Math.max(1000, until - System.currentTimeMillis()));
        return text.msg(until < 0 ? "mod.ban-screen" : "mod.tempban-screen", server(), Text.unparsed("reason", reason),
                Text.unparsed("staff", staff), Text.unparsed("time", left));
    }

    /** Banned players see Feather's screen instead of the vanilla one. Vanilla still enforces the ban itself. */
    private net.minecraft.network.chat.Component loginScreen(NameAndId profile, net.minecraft.network.chat.Component current) {
        UserBanListEntry e;
        try {
            e = bans().get(profile);
        } catch (RuntimeException ex) {
            return current;
        }
        if (e == null) return current;
        Date exp = e.getExpires();
        if (exp != null && exp.getTime() <= System.currentTimeMillis()) return current;
        String reason = e.getReason() == null || e.getReason().isBlank() || e.getReason().equals("Banned by an operator.")
                ? plugin.text().raw("mod.no-reason") : e.getReason();
        String staff = e.getSource() == null ? "Console" : e.getSource();
        return Adv.vanilla(banScreen(reason, staff, exp == null ? -1 : exp.getTime()));
    }

    private void unban(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            text.send(sender, "mod.unban-usage");
            return;
        }
        UserBanList list = bans();
        UserBanListEntry found = null;
        for (UserBanListEntry e : entries()) {
            NameAndId p = e.getUser();
            if (p != null && args[0].equalsIgnoreCase(p.name())) found = e;
        }
        if (found == null) {
            OfflinePlayer t = plugin.findOfflinePlayer(sender, args[0]); // a saved nickname works too
            if (t != null) found = list.get(profile(t));
        }
        if (found == null || found.getUser() == null) {
            text.send(sender, "mod.not-banned", Text.unparsed("name", args[0]));
            return;
        }
        NameAndId target = found.getUser();
        list.remove(target);
        String name = target.name() == null || target.name().isEmpty() ? args[0] : target.name();
        String staff = ModerationModule.staffName(sender);
        text.send(sender, "mod.unbanned", Text.unparsed("name", name));
        if (target.id() != null) mod.log(target.id(), "unban", staff, "", 0);
        mod.notifyStaff(sender, "mod.notify-unban", Text.unparsed("staff", staff), Text.unparsed("name", name));
    }

    private static List<UserBanListEntry> entries() {
        List<UserBanListEntry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (UserBanListEntry e : bans().getEntries()) {
            if (e.getExpires() != null && e.getExpires().getTime() <= now) continue;
            if (e.getUser() == null) continue;
            out.add(e);
        }
        out.sort(Comparator.comparing(e -> e.getUser().name() == null ? "" : e.getUser().name(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void banlist(Sender sender) {
        Text text = plugin.text();
        List<UserBanListEntry> list = entries();
        if (list.isEmpty()) {
            text.send(sender, "mod.banlist-empty");
            return;
        }
        text.send(sender, "mod.banlist-header", Text.unparsed("count", list.size()));
        for (UserBanListEntry e : list) {
            String name = e.getUser().name() == null ? "?" : e.getUser().name();
            Date exp = e.getExpires();
            String left = exp == null ? text.raw("mod.forever") : Placeholders.duration(exp.getTime() - System.currentTimeMillis());
            sender.sendMessage(text.msg("mod.banlist-entry", Text.unparsed("name", name),
                    Text.unparsed("reason", e.getReason() == null ? "-" : e.getReason()),
                    Text.unparsed("staff", e.getSource() == null ? "?" : e.getSource()),
                    Text.unparsed("time", left), Text.unparsed("date", mod.date(e.getCreated().getTime(), sender)),
                    Text.component("unban", text.button("mod.banlist-unban", "/unban " + name))));
        }
    }

    // ------------------------------------------------------------------ warnings

    private void warn(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length < 2) {
            text.send(sender, "mod.warn-usage");
            return;
        }
        OfflinePlayer t = mod.target(sender, args[0]);
        if (t == null) return;
        if (sender.isPlayer() && t.id().equals(sender.player().getUUID())) {
            text.send(sender, "mod.warn-self");
            return;
        }
        String reason = rest(args, 1);
        String staff = ModerationModule.staffName(sender);
        String name = ModerationModule.nameOf(t, args[0]);
        Profiles.Profile pr = plugin.profiles().load(t.id());
        pr.warnings.add(new Profiles.Warning(System.currentTimeMillis(), staff, reason));
        int count = pr.warnings.size();
        ServerPlayer online = t.online();
        if (online == null) pr.pendingWarnings.add(reason);
        plugin.profiles().save(pr);
        mod.log(t.id(), "warn", staff, reason, 0);
        if (online != null) showWarning(online, reason);
        text.send(sender, "mod.warned", Text.unparsed("name", name), Text.unparsed("reason", reason), Text.unparsed("count", count));
        mod.notifyStaff(sender, "mod.notify-warn", Text.unparsed("staff", staff), Text.unparsed("name", name),
                Text.unparsed("reason", reason), Text.unparsed("count", count));
        // The automatic kick/mute/ban runs as the console, so it must not reach players this sender couldn't sanction.
        if (!mod.exempt(sender, t)) warnAction(t, name, count);
    }

    private void showWarning(ServerPlayer p, String reason) {
        Text text = plugin.text();
        Adv.of(p).showTitle(Title.title(text.msg("mod.warn-title"), text.msg("mod.warn-subtitle", Text.unparsed("reason", reason)),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))));
        text.send(p, "mod.you-were-warned", Text.unparsed("reason", reason));
        text.sound(p, "error");
    }

    /** Warnings given while offline, shown once on the next join. */
    private void joined(ServerPlayer p) {
        Profiles.Profile pr = plugin.profiles().get(p);
        if (pr.pendingWarnings.isEmpty()) return;
        List<String> pending = new ArrayList<>(pr.pendingWarnings);
        pr.pendingWarnings.clear();
        plugin.profiles().save(pr);
        Tasks.later(40L, () -> {
            if (p.hasDisconnected()) return;
            for (String reason : pending) plugin.text().send(p, "mod.you-were-warned-offline", Text.unparsed("reason", reason));
            showWarning(p, pending.get(pending.size() - 1));
        });
    }

    /**
     * {@code moderation.warn-actions}: "<count>: <action>" where the action is kick, "mute <time|perm>",
     * "tempban <time>" or "ban [time]". Runs when a player reaches exactly that many warnings.
     */
    private void warnAction(OfflinePlayer t, String name, int count) {
        Yaml sec = plugin.getConfig().getConfigurationSection("moderation.warn-actions");
        if (sec == null) return;
        String action = sec.getString(String.valueOf(count));
        if (action == null || action.isBlank()) return;
        String[] parts = action.trim().split("\\s+");
        String staff = "Feather";
        String reason = plugin.text().raw("mod.warn-action-reason").replace("<count>", String.valueOf(count));
        Sender console = new Sender(Feather.server().createCommandSourceStack());
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "kick" -> {
                if (t.online() != null) kick(console, t.online(), reason, staff);
            }
            case "mute" -> {
                long d = parts.length > 1 ? Placeholders.punishmentDuration(parts[1]) : -1;
                if (d == 0) {
                    plugin.getLogger().warn("moderation.warn-actions.{}: bad length '{}'.", count, parts[1]);
                    return;
                }
                Profiles.Profile pr = plugin.profiles().load(t.id());
                pr.mutedUntil = d < 0 ? -1 : System.currentTimeMillis() + d;
                pr.muteReason = reason;
                plugin.profiles().save(pr);
                mod.log(t.id(), "mute", staff, reason, pr.mutedUntil);
                mod.notifyStaff(null, "mod.notify-mute", Text.unparsed("staff", staff), Text.unparsed("name", name),
                        Text.unparsed("time", mod.length(d)), Text.unparsed("reason", reason));
                if (t.online() != null) mod.blockMuted(t.online());
            }
            case "ban", "tempban" -> {
                long d = parts.length > 1 ? Placeholders.punishmentDuration(parts[1]) : -1;
                if (d == 0 || (d < 0 && parts[0].equalsIgnoreCase("tempban"))) {
                    plugin.getLogger().warn("moderation.warn-actions.{}: bad length in '{}'.", count, action);
                    return;
                }
                ban(console, t, name, d, reason, staff);
            }
            default -> plugin.getLogger().warn("moderation.warn-actions.{}: unknown action '{}' (use kick, mute <time>, tempban <time> or ban [time]).",
                    count, action);
        }
    }

    private void warnings(Sender sender, String[] args) {
        Text text = plugin.text();
        OfflinePlayer t;
        if (args.length > 0) {
            if (!sender.hasPermission("feather.warnings.others")) {
                text.send(sender, "general.no-permission");
                return;
            }
            t = mod.target(sender, args[0]);
            if (t == null) return;
        } else if (sender.isPlayer()) {
            t = OfflinePlayer.of(sender.player());
        } else {
            text.send(sender, "staff.need-player");
            return;
        }
        showWarnings(sender, t, ModerationModule.nameOf(t, args.length > 0 ? args[0] : "?"));
    }

    private void showWarnings(Sender sender, OfflinePlayer t, String name) {
        Text text = plugin.text();
        Profiles.Profile pr = plugin.profiles().load(t.id());
        List<Profiles.Warning> list = pr.warnings;
        ServerPlayer viewer = sender.player();
        if (viewer == null) {
            if (list.isEmpty()) text.send(sender, "mod.warnings-none", Text.unparsed("name", name));
            else {
                text.send(sender, "mod.warnings-header", Text.unparsed("name", name), Text.unparsed("count", list.size()));
                for (int i = 0; i < list.size(); i++) sender.sendMessage(warningLine(sender, list.get(i), i + 1));
            }
            return;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("mod.warnings-title", Text.unparsed("name", name), Text.unparsed("count", list.size())));
        if (list.isEmpty()) menu.text(text.msg("mod.warnings-none-body", Text.unparsed("name", name)), 300);
        boolean staff = Perms.has(viewer, "feather.warn") && !viewer.getUUID().equals(t.id());
        for (int i = 0; i < list.size(); i++) menu.text(warningLine(sender, list.get(i), i + 1), 300);
        if (staff) {
            for (int i = 0; i < list.size(); i++) {
                int index = i + 1;
                menu.button(text.msg("mod.warnings-remove", Text.unparsed("index", index)), text.msg("mod.warnings-remove-tooltip"), 100,
                        p -> {
                            CommandBridge.dispatch(Sender.of(p), "delwarn " + name + " " + index);
                            showWarnings(Sender.of(p), t, name);
                        });
            }
            menu.columns(3);
        }
        plugin.exitButton(menu, viewer);
        menu.open(viewer);
        text.sound(viewer, "menu-open");
    }

    private Component warningLine(Sender viewer, Profiles.Warning w, int index) {
        return plugin.text().msg("mod.warnings-entry", Text.unparsed("index", index), Text.unparsed("date", mod.date(w.time(), viewer)),
                Text.unparsed("staff", w.staff()), Text.unparsed("reason", w.reason()));
    }

    private void delwarn(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length < 2) {
            text.send(sender, "mod.delwarn-usage");
            return;
        }
        OfflinePlayer t = mod.target(sender, args[0]);
        if (t == null) return;
        Profiles.Profile pr = plugin.profiles().load(t.id());
        int index;
        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            index = -1;
        }
        if (index < 1 || index > pr.warnings.size()) {
            text.send(sender, "mod.delwarn-bad-index", Text.unparsed("input", args[1]), Text.unparsed("count", pr.warnings.size()));
            return;
        }
        Profiles.Warning removed = pr.warnings.remove(index - 1);
        plugin.profiles().save(pr);
        String name = ModerationModule.nameOf(t, args[0]);
        String staff = ModerationModule.staffName(sender);
        mod.log(t.id(), "delwarn", staff, removed.reason(), 0);
        text.send(sender, "mod.delwarned", Text.unparsed("name", name), Text.unparsed("index", index), Text.unparsed("reason", removed.reason()));
        mod.notifyStaff(sender, "mod.notify-delwarn", Text.unparsed("staff", staff), Text.unparsed("name", name),
                Text.unparsed("reason", removed.reason()));
    }

    // ------------------------------------------------------------------ history

    private void history(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            text.send(sender, "mod.history-usage");
            return;
        }
        OfflinePlayer t = mod.target(sender, args[0]);
        if (t == null) return;
        String name = ModerationModule.nameOf(t, args[0]);
        Profiles.Profile pr = plugin.profiles().load(t.id());
        List<Profiles.ModEntry> log = new ArrayList<>(pr.modLog);
        java.util.Collections.reverse(log);
        Component status = status(t, pr);
        ServerPlayer viewer = sender.player();
        if (viewer == null) {
            text.send(sender, "mod.history-header", Text.unparsed("name", name), Text.unparsed("count", log.size()));
            sender.sendMessage(status);
            for (Profiles.ModEntry e : log) sender.sendMessage(historyLine(sender, e));
            return;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("mod.history-title", Text.unparsed("name", name)));
        menu.text(status, 300);
        if (log.isEmpty()) menu.text(text.msg("mod.history-empty"), 300);
        int shown = 0;
        for (Profiles.ModEntry e : log) {
            if (shown++ >= 60) {
                menu.text(text.msg("mod.history-more", Text.unparsed("count", log.size() - 60)), 300);
                break;
            }
            menu.text(historyLine(sender, e), 300);
        }
        if (Perms.has(viewer, "feather.warn")) {
            menu.button(text.msg("mod.history-warnings", Text.unparsed("count", pr.warnings.size())), null, 150,
                    p -> showWarnings(Sender.of(p), t, name));
        }
        plugin.exitButton(menu, viewer);
        menu.open(viewer);
        text.sound(viewer, "menu-open");
    }

    /** One line: what's running against the player right now (mute, ban, freeze, warnings). */
    public Component status(OfflinePlayer t, Profiles.Profile pr) {
        Text text = plugin.text();
        UserBanListEntry ban = banOf(t);
        String banned = ban == null ? text.raw("mod.status-no")
                : ban.getExpires() == null ? text.raw("mod.forever") : Placeholders.duration(ban.getExpires().getTime() - System.currentTimeMillis());
        String muted = !pr.muted() ? text.raw("mod.status-no")
                : pr.mutedUntil < 0 ? text.raw("mod.forever") : Placeholders.duration(pr.mutedUntil - System.currentTimeMillis());
        int reports = mod.reports() == null ? 0 : mod.reports().countAgainst(t.id());
        return text.msg("mod.status", Text.unparsed("is-banned", banned), Text.unparsed("is-muted", muted),
                Text.unparsed("is-frozen", pr.frozen ? text.raw("mod.status-yes") : text.raw("mod.status-no")),
                Text.unparsed("warnings", pr.warnings.size()), Text.unparsed("reports", reports));
    }

    private Component historyLine(Sender viewer, Profiles.ModEntry e) {
        Text text = plugin.text();
        String type = e.type().toLowerCase(Locale.ROOT);
        String key = "mod.history-type." + type;
        String label = text.variants(key).isEmpty() ? type : text.raw(key);
        String until = e.until() == 0 ? "" : e.until() < 0 ? text.raw("mod.history-until-forever")
                : text.raw("mod.history-until").replace("<date>", mod.date(e.until(), viewer));
        return text.msg("mod.history-entry", Text.component("type", text.parse(label)),
                Text.unparsed("date", mod.date(e.time(), viewer)), Text.unparsed("staff", e.staff()),
                Text.unparsed("reason", e.reason().isEmpty() ? "-" : e.reason()), Text.component("until", text.parse(until)));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        String name = CommandBridge.nameOf(label);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (name.equals("unban")) {
                for (UserBanListEntry e : entries()) if (e.getUser().name() != null) out.add(e.getUser().name());
            } else if (!name.equals("banlist") && (!name.equals("warnings") || sender.hasPermission("feather.warnings.others"))) {
                out.addAll(plugin.completePlayers(sender, ""));
            }
        } else if (args.length == 2) {
            if (name.equals("ban")) out.addAll(List.of("1d", "7d", "30d", "perm"));
            else if (name.equals("tempban")) out.addAll(List.of("1h", "1d", "7d", "30d"));
            else if (name.equals("delwarn")) {
                OfflinePlayer t = plugin.findOfflinePlayer(sender, args[0]);
                if (t != null) {
                    int n = plugin.profiles().load(t.id()).warnings.size();
                    for (int i = 1; i <= n; i++) out.add(String.valueOf(i));
                }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
