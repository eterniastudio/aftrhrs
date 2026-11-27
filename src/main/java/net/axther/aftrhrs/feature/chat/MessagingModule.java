package net.axther.aftrhrs.feature.chat;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.feature.moderation.ModerationModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /msg, /r, /msgtoggle, /socialspy, /realname, /seen. Provides {@link Hooks.Ignores} (the /ignore lists). */
public final class MessagingModule implements FeatherModule, FeatherCommand {
    private static volatile MessagingModule active;

    private final Feather plugin;
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    public MessagingModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Messaging";
    }

    @Override
    public void enable() {
        active = this;
        for (String cmd : List.of("msg", "reply", "msgtoggle", "socialspy", "realname", "seen")) plugin.bind(cmd, this);
        plugin.provide(Hooks.Ignores.class, (receiver, sender) -> ignores(plugin, receiver, sender));
        plugin.once("messaging.events", () -> ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            MessagingModule m = active;
            if (m != null) m.lastPartner.remove(h.player.getUUID());
        }));
        plugin.hub().addToggle(new Hub.Toggle("messages", Hub.TOGGLE_MESSAGES, p -> Perms.has(p, "feather.msgtoggle"),
                p -> !plugin.profiles().get(p).messagesOff, p -> {
            // Same as /msgtoggle.
            Profiles.Profile q = plugin.profiles().get(p);
            q.messagesOff = !q.messagesOff;
            plugin.profiles().save(q);
            plugin.text().send(p, q.messagesOff ? "msg.toggled-off" : "msg.toggled-on");
        }));
    }

    @Override
    public void disable() {
        if (active == this) active = null;
    }

    /**
     * True if {@code receiver} ignores {@code sender} (/ignore). Staff with {@code feather.ignore.exempt} can't be
     * ignored. Shared by chat, /msg, mentions, teleport requests and trades.
     */
    public static boolean ignores(Feather plugin, ServerPlayer receiver, ServerPlayer sender) {
        if (receiver == null || sender == null || receiver == sender || Perms.has(sender, "feather.ignore.exempt")) return false;
        return plugin.profiles().get(receiver).ignored.contains(sender.getUUID());
    }

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        String name = command(label);
        switch (name) {
            case "msg" -> {
                if (args.length < 2) {
                    text.send(sender, "msg.usage");
                    return true;
                }
                ServerPlayer to = plugin.findPlayer(sender, args[0]);
                if (to == null) {
                    plugin.notFound(sender, args[0]);
                    return true;
                }
                send(sender, to, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "reply" -> {
                ServerPlayer p = sender.player();
                if (p == null) {
                    text.send(sender, "general.players-only");
                    return true;
                }
                if (args.length == 0) {
                    text.send(sender, "msg.reply-usage");
                    return true;
                }
                UUID last = lastPartner.get(p.getUUID());
                ServerPlayer to = last == null ? null : plugin.player(last);
                if (to == null) {
                    text.send(p, "msg.nobody-to-reply");
                    text.sound(p, "error");
                    return true;
                }
                send(sender, to, String.join(" ", args));
            }
            case "msgtoggle" -> {
                ServerPlayer p = sender.player();
                if (p == null) return true;
                Profiles.Profile pr = plugin.profiles().get(p);
                pr.messagesOff = !pr.messagesOff;
                plugin.profiles().save(pr);
                text.send(p, pr.messagesOff ? "msg.toggled-off" : "msg.toggled-on");
            }
            case "socialspy" -> {
                ServerPlayer p = sender.player();
                if (p == null) return true;
                Profiles.Profile pr = plugin.profiles().get(p);
                pr.socialSpy = !pr.socialSpy;
                plugin.profiles().save(pr);
                text.send(p, pr.socialSpy ? "msg.spy-on" : "msg.spy-off");
            }
            case "realname" -> {
                if (args.length == 0) {
                    text.send(sender, "msg.realname-usage");
                    return true;
                }
                String q = args[0].toLowerCase(Locale.ROOT);
                boolean found = false;
                for (ServerPlayer p : plugin.onlinePlayers()) {
                    if (!plugin.canSee(sender.player(), p)) continue;
                    if (Names.plain(p).toLowerCase(Locale.ROOT).contains(q)) {
                        text.send(sender, "msg.realname", Text.component("nick", Names.display(p)), Text.unparsed("name", Names.name(p)));
                        found = true;
                    }
                }
                if (!found) text.send(sender, "general.player-not-found", Text.unparsed("input", args[0]));
            }
            case "seen" -> {
                if (args.length == 0) {
                    text.send(sender, "msg.seen-usage");
                    return true;
                }
                ServerPlayer online = plugin.findPlayer(sender, args[0]);
                if (online != null) {
                    text.send(sender, "msg.seen-online", Text.component("name", Names.display(online)));
                    return true;
                }
                OfflinePlayer off = plugin.findOfflinePlayer(sender, args[0]);
                if (off == null || !off.hasPlayedBefore()) {
                    text.send(sender, "msg.seen-never", Text.unparsed("name", args[0]));
                    return true;
                }
                long last = plugin.profiles().load(off.id()).lastSeen;
                if (last <= 0) last = lastPlayed(off.id());
                text.send(sender, "msg.seen", Text.unparsed("name", off.name() == null ? args[0] : off.name()),
                        Text.unparsed("ago", ago(System.currentTimeMillis() - last)),
                        Text.unparsed("date", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(last))));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String command(String label) {
        return net.axther.aftrhrs.core.cmd.CommandBridge.nameOf(label);
    }

    /** When the vanilla player data was last written (Bukkit's getLastSeen fallback), or 0. */
    public static long lastPlayed(UUID id) {
        var server = Feather.serverOrNull();
        if (server == null) return 0;
        Path dat = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat");
        try {
            return Files.exists(dat) ? Files.getLastModifiedTime(dat).toMillis() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void send(Sender from, ServerPlayer to, String message) {
        Text text = plugin.text();
        ServerPlayer fp = from.player();
        if (fp != null && fp.equals(to)) {
            text.send(fp, "msg.self");
            return;
        }
        ModerationModule mod = plugin.module(ModerationModule.class);
        if (mod != null && fp != null && mod.blockMuted(fp)) return;
        if (fp != null && ignores(plugin, to, fp)) {
            text.send(from, "msg.target-off", Text.component("target", Names.display(to)));
            return;
        }
        if (plugin.profiles().get(to).messagesOff && !from.hasPermission("feather.msgtoggle.bypass")) {
            text.send(from, "msg.target-off", Text.component("target", Names.display(to)));
            return;
        }
        Component fromName = fp != null ? Names.display(fp) : Component.text("Console");
        String fromRaw = fp != null ? Names.name(fp) : "Console";
        Component body = Component.text(message);
        text.send(from, "msg.sent", Text.component("target", Names.display(to)), Text.component("message", body));
        net.axther.aftrhrs.core.Adv.send(to, text.msg("msg.received", Text.component("sender", fromName), Text.component("message", body))
                .clickEvent(ClickEvent.suggestCommand("/msg " + fromRaw + " ")));
        text.sound(to, "message");
        Hooks.Afk afk = plugin.service(Hooks.Afk.class);
        if (afk != null && afk.isAfk(to)) {
            String why = afk.reason(to);
            text.send(from, why == null || why.isEmpty() ? "afk.target-afk" : "afk.target-afk-message",
                    Text.component("target", Names.display(to)), Text.unparsed("message", why == null ? "" : why));
        }
        if (fp != null) {
            lastPartner.put(fp.getUUID(), to.getUUID());
            lastPartner.put(to.getUUID(), fp.getUUID());
            plugin.advance(fp, "event", "msg");
        }
        for (ServerPlayer spy : plugin.onlinePlayers()) {
            if (spy == fp || spy == to || !plugin.profiles().get(spy).socialSpy || !Perms.has(spy, "feather.socialspy")) continue;
            text.send(spy, "msg.spy", Text.unparsed("sender", fromRaw), Text.unparsed("target", Names.name(to)), Text.component("message", body));
        }
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 48) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        String name = command(label);
        if (args.length == 1 && (name.equals("msg") || name.equals("seen") || name.equals("realname"))) {
            return plugin.completePlayers(sender, args[0]);
        }
        return List.of();
    }
}
