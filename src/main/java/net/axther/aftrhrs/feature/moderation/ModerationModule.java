package net.axther.aftrhrs.feature.moderation;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Timezones;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.chat.ChatModule;
import net.axther.aftrhrs.feature.chat.MessagingModule;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Chat moderation and player comfort: /mute and /unmute (timed or permanent, kept across restarts), /ignore and
 * /unignore (hides a player's chat, private messages and teleport requests from you), /clearchat and /mutechat
 * (locks public chat for everyone without {@code feather.mutechat.bypass}).
 *
 * <p>Also runs the rest of the moderation suite, each in its own class: {@link Sanctions} (kick, bans, warnings,
 * history), {@link StaffChat}, {@link Reports}, {@link Freeze} and {@link Inspector} (inventory snapshots for
 * offline /invsee and /ec, offline /whois). The shared bits (looking players up, the moderation log, staff
 * notifications, durations) live here. Provides {@link Hooks.Ignores} and {@link Hooks.ChatGate}.
 */
public final class ModerationModule implements FeatherModule, FeatherCommand {
    /** Most entries kept in a player's moderation log; the oldest go first. */
    private static final int LOG_LIMIT = 200;

    private static volatile ModerationModule active;

    private final Feather plugin;
    private boolean chatLocked;
    private Sanctions sanctions;
    private StaffChat staffChat;
    private Reports reports;
    private Freeze freeze;
    private Inspector inspector;

    public ModerationModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Moderation";
    }

    @Override
    public void enable() {
        for (String c : List.of("mute", "unmute", "ignore", "unignore", "clearchat", "mutechat")) plugin.bind(c, this);
        sanctions = new Sanctions(plugin, this);
        sanctions.enable();
        staffChat = new StaffChat(plugin);
        staffChat.enable();
        reports = new Reports(plugin, this);
        reports.enable();
        freeze = new Freeze(plugin, this);
        freeze.enable();
        inspector = new Inspector(plugin, this);
        inspector.enable();
        plugin.provide(Hooks.Ignores.class, (receiver, sender) -> MessagingModule.ignores(plugin, receiver, sender));
        plugin.provide(Hooks.ChatGate.class, p -> plugin.profiles().get(p).muted() || chatLockedFor(p));
        active = this;
        plugin.once("moderation.events", () -> {
            // Staff chat first: a toggled line goes to staff before mutes or the chat lock look at it.
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(FeatherEvents.CHAT_MODERATION, (message, sender, params) -> {
                ModerationModule m = active;
                return m == null || m.staffChat.onChat(sender, message.signedContent());
            });
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(FeatherEvents.CHAT_MODERATION, (message, sender, params) -> {
                ModerationModule m = active;
                return m == null || m.onChat(sender);
            });
            // Without the chat module nobody else delivers per viewer: keep /ignore working on plain vanilla chat.
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(FeatherEvents.CHAT_FORMAT, (message, sender, params) -> {
                ModerationModule m = active;
                if (m == null || ChatModule.active() != null) return true;
                List<ServerPlayer> viewers = new ArrayList<>();
                boolean anyIgnores = false;
                for (ServerPlayer v : plugin.onlinePlayers()) {
                    if (v != sender && MessagingModule.ignores(plugin, v, sender)) anyIgnores = true;
                    else viewers.add(v);
                }
                if (!anyIgnores) return true;
                for (ServerPlayer v : viewers) v.sendChatMessage(OutgoingChatMessage.create(message), sender.shouldFilterMessageTo(v), params);
                ServerMessageEvents.CHAT_MESSAGE.invoker().onChatMessage(message, sender, params);
                return false;
            });
            FeatherEvents.COMMAND.register((p, cmd) -> {
                ModerationModule m = active;
                return m == null || m.onCommand(p, cmd);
            });
        });
    }

    @Override
    public void reload() {
        if (inspector != null) inspector.reload();
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        if (inspector != null) inspector.disable();
        if (freeze != null) freeze.disable();
        if (reports != null) reports.disable();
        if (staffChat != null) staffChat.disable();
    }

    public Sanctions sanctions() {
        return sanctions;
    }

    public Reports reports() {
        return reports;
    }

    public Freeze freeze() {
        return freeze;
    }

    public Inspector inspector() {
        return inspector;
    }

    public StaffChat staffChat() {
        return staffChat;
    }

    // ------------------------------------------------------------------ shared by the moderation suite

    /** The name moderation records and screens show for whoever ran a command. */
    static String staffName(Sender sender) {
        return sender.isPlayer() ? Names.name(sender.player()) : "Console";
    }

    /** Whether a player is a server operator (ops.json), online or not. */
    static boolean isOp(OfflinePlayer p) {
        var server = Feather.serverOrNull();
        return server != null && p != null && server.getPlayerList().isOp(new NameAndId(p.id(), p.name() == null ? "" : p.name()));
    }

    /**
     * Whether {@code target} is protected from bans, kicks and freezes ({@code feather.moderation.exempt}). Offline
     * players can't be asked for permissions, so an offline op counts as exempt. The console overrides it.
     */
    boolean exempt(Sender sender, OfflinePlayer target) {
        if (!sender.isPlayer()) return false;
        ServerPlayer online = target.online();
        if (online != null) return Perms.has(online, "feather.moderation.exempt") && !online.equals(sender.player());
        return isOp(target);
    }

    /** Adds a line to a player's moderation history (online or offline) and saves it. */
    void log(UUID player, String type, String staff, String reason, long until) {
        Profiles.Profile pr = plugin.profiles().load(player);
        pr.modLog.add(new Profiles.ModEntry(type, System.currentTimeMillis(), staff, reason == null ? "" : reason, until));
        while (pr.modLog.size() > LOG_LIMIT) pr.modLog.remove(0);
        plugin.profiles().save(pr);
    }

    /**
     * Tells online staff ({@code feather.moderation.notify}) about a moderation action, wrapped in {@code mod.notify},
     * and writes it to the console log. {@code except} (the one who did it, already told) is skipped.
     */
    void notifyStaff(Sender except, String key, TagResolver... tags) {
        Text text = plugin.text();
        Component inner = text.msg(key, tags);
        Component line = text.msg("mod.notify", Text.component("message", inner));
        ServerPlayer skip = except == null ? null : except.player();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (p.equals(skip) || !Perms.has(p, "feather.moderation.notify")) continue;
            Adv.send(p, line);
        }
        plugin.getLogger().info("[Moderation] {}", Adv.plain(inner));
    }

    /** A date and time, in the viewer's time zone when the time-zone feature is on. */
    public String date(long epochMs, Sender viewer) {
        return date(epochMs, viewer == null ? null : viewer.player());
    }

    public String date(long epochMs, ServerPlayer viewer) {
        Timezones zones = plugin.timezones();
        if (zones != null && viewer != null) return zones.format(epochMs, viewer);
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(epochMs));
    }

    /** "2d 4h", or the permanent word (mod.forever) for -1. */
    String length(long ms) {
        return ms < 0 ? plugin.text().raw("mod.forever") : Placeholders.duration(ms);
    }

    /**
     * Reads a punishment length argument: ms, or -1 for permanent. Returns 0 after telling the sender when the
     * value is invalid (zero, negative, too large), so a typo never becomes a permanent punishment.
     */
    long durationArg(Sender sender, String arg, boolean allowPermanent) {
        long d = Placeholders.punishmentDuration(arg);
        if (d == 0 || (d < 0 && !allowPermanent)) {
            plugin.text().send(sender, "mod.bad-duration", Text.unparsed("input", arg));
            return 0;
        }
        return d;
    }

    // ------------------------------------------------------------------ queries used by chat, /msg and /tpa

    /** True if {@code receiver} ignores {@code sender} (staff with feather.ignore.exempt can't be ignored). */
    public boolean ignores(ServerPlayer receiver, ServerPlayer sender) {
        return MessagingModule.ignores(plugin, receiver, sender);
    }

    /** True while /mutechat has public chat paused for this player. */
    public boolean chatLockedFor(ServerPlayer p) {
        return chatLocked && !Perms.has(p, "feather.mutechat.bypass");
    }

    /** Tells a muted player so and returns true. */
    public boolean blockMuted(ServerPlayer p) {
        Profiles.Profile pr = plugin.profiles().get(p);
        if (!pr.muted()) return false;
        plugin.text().send(p, pr.mutedUntil < 0 ? "mod.you-are-muted-perm" : "mod.you-are-muted",
                Text.unparsed("time", Placeholders.duration(pr.mutedUntil - System.currentTimeMillis())),
                Text.unparsed("reason", pr.muteReason == null ? "" : pr.muteReason));
        plugin.text().sound(p, "error");
        return true;
    }

    // ------------------------------------------------------------------ chat

    private boolean onChat(ServerPlayer p) {
        if (plugin.authPending(p)) return true; // the server code, see AuthModule
        if (blockMuted(p)) return false;
        if (chatLocked && !Perms.has(p, "feather.mutechat.bypass")) {
            plugin.text().send(p, "mod.chat-locked");
            return false;
        }
        return true;
    }

    private boolean onCommand(ServerPlayer p, String line) {
        int space = line.indexOf(' ');
        String label = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!plugin.getConfig().getStringList("moderation.muted-blocked-commands").contains(label)) return true;
        return !blockMuted(p);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        switch (CommandBridge.nameOf(label)) {
            case "mute" -> mute(sender, args);
            case "unmute" -> {
                if (args.length == 0) {
                    text.send(sender, "mod.unmute-usage");
                    return true;
                }
                OfflinePlayer t = lookup(sender, args[0]);
                if (t == null) {
                    text.send(sender, "msg.seen-never", Text.unparsed("name", args[0]));
                    return true;
                }
                Profiles.Profile pr = plugin.profiles().load(t.id());
                pr.mutedUntil = 0;
                pr.muteReason = null;
                plugin.profiles().save(pr);
                text.send(sender, "mod.unmuted", Text.unparsed("name", nameOf(t, args[0])));
                log(t.id(), "unmute", staffName(sender), "", 0);
                notifyStaff(sender, "mod.notify-unmute", Text.unparsed("staff", staffName(sender)), Text.unparsed("name", nameOf(t, args[0])));
                if (t.online() != null) text.send(t.online(), "mod.you-are-unmuted");
            }
            case "ignore" -> ignore(sender, args, true);
            case "unignore" -> ignore(sender, args, false);
            case "clearchat" -> {
                net.minecraft.network.chat.Component blank = net.minecraft.network.chat.Component.literal(" ");
                for (ServerPlayer p : plugin.onlinePlayers()) {
                    if (Perms.has(p, "feather.clearchat.bypass")) continue;
                    for (int i = 0; i < 100; i++) p.sendSystemMessage(blank);
                }
                broadcast(text.msg("mod.chat-cleared", Text.component("by", sender.isPlayer() ? Names.display(sender.player()) : Component.text("Console"))));
            }
            case "mutechat" -> {
                chatLocked = !chatLocked;
                broadcast(text.msg(chatLocked ? "mod.chat-locked-on" : "mod.chat-locked-off"));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Everyone and the console (Bukkit.broadcast). */
    void broadcast(Component message) {
        net.minecraft.network.chat.Component c = Adv.vanilla(message);
        Feather.server().getPlayerList().broadcastSystemMessage(c, false);
    }

    private OfflinePlayer lookup(Sender viewer, String name) {
        OfflinePlayer exact = plugin.lookup().byName(name);
        if (exact != null && (exact.isOnline() || exact.hasPlayedBefore())) return exact;
        OfflinePlayer off = plugin.findOfflinePlayer(viewer, name); // saved nicknames too
        return off != null && (off.isOnline() || off.hasPlayedBefore()) ? off : null;
    }

    /**
     * A player for a moderation command: online (exact name, nickname or unique prefix the sender can see), else
     * someone who has played here. Null (and the sender told) if neither.
     */
    OfflinePlayer target(Sender sender, String input) {
        ServerPlayer online = plugin.findPlayer(sender, input);
        if (online != null) return OfflinePlayer.of(online);
        OfflinePlayer off = lookup(sender, input);
        if (off == null) plugin.text().send(sender, "msg.seen-never", Text.unparsed("name", input));
        return off;
    }

    static String nameOf(OfflinePlayer p, String fallback) {
        return p.name() == null ? fallback : p.name();
    }

    private void mute(Sender sender, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            text.send(sender, "mod.mute-usage");
            return;
        }
        OfflinePlayer t = lookup(sender, args[0]);
        if (t == null) {
            text.send(sender, "msg.seen-never", Text.unparsed("name", args[0]));
            return;
        }
        // Same rule as ban/kick/freeze for offline targets (ops are exempt), plus the mute-specific node online.
        if ((t.online() != null && Perms.has(t.online(), "feather.mute.exempt") && !t.online().equals(sender.player()))
                || exempt(sender, t)) {
            text.send(sender, "mod.mute-exempt");
            return;
        }
        long duration = -1;
        int reasonStart = 1;
        // A second word that looks like a time is the length; "0", "-5m" or an overflowing one is refused instead
        // of quietly becoming a permanent mute.
        if (args.length > 1 && Placeholders.looksLikeDuration(args[1])) {
            duration = durationArg(sender, args[1], true);
            if (duration == 0) return;
            reasonStart = 2;
        }
        String reason = args.length > reasonStart ? String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length)) : "";
        Profiles.Profile pr = plugin.profiles().load(t.id());
        long until = duration < 0 ? -1 : System.currentTimeMillis() + duration;
        pr.mutedUntil = until;
        pr.muteReason = reason.isEmpty() ? null : reason;
        plugin.profiles().save(pr);
        String time = length(duration);
        String name = nameOf(t, args[0]);
        text.send(sender, "mod.muted", Text.unparsed("name", name), Text.unparsed("time", time),
                Text.unparsed("reason", reason.isEmpty() ? "-" : reason));
        log(t.id(), "mute", staffName(sender), reason, until);
        notifyStaff(sender, "mod.notify-mute", Text.unparsed("staff", staffName(sender)), Text.unparsed("name", name),
                Text.unparsed("time", time), Text.unparsed("reason", reason.isEmpty() ? "-" : reason));
        if (t.online() != null) blockMuted(t.online());
    }

    private void ignore(Sender sender, String[] args, boolean add) {
        Text text = plugin.text();
        ServerPlayer p = sender.player();
        if (p == null) {
            text.send(sender, "general.players-only");
            return;
        }
        Profiles.Profile pr = plugin.profiles().get(p);
        if (args.length == 0) {
            if (pr.ignored.isEmpty()) {
                text.send(p, "mod.ignore-none");
                return;
            }
            List<String> names = new ArrayList<>();
            for (UUID id : pr.ignored) {
                OfflinePlayer o = plugin.lookup().byId(id);
                names.add(o == null || o.name() == null ? id.toString().substring(0, 8) : o.name());
            }
            text.send(p, "mod.ignore-list", Text.unparsed("players", String.join(", ", names)));
            return;
        }
        OfflinePlayer t = lookup(sender, args[0]);
        if (t == null) {
            text.send(p, "msg.seen-never", Text.unparsed("name", args[0]));
            return;
        }
        if (t.id().equals(p.getUUID())) {
            text.send(p, "mod.ignore-self");
            return;
        }
        if (add) {
            if (t.online() != null && Perms.has(t.online(), "feather.ignore.exempt")) {
                text.send(p, "mod.ignore-exempt");
                return;
            }
            pr.ignored.add(t.id());
            text.send(p, "mod.ignored", Text.unparsed("name", nameOf(t, args[0])));
        } else {
            pr.ignored.remove(t.id());
            text.send(p, "mod.unignored", Text.unparsed("name", nameOf(t, args[0])));
        }
        plugin.profiles().save(pr);
        text.sound(p, "click");
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        String name = CommandBridge.nameOf(label);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (name.equals("unignore") && sender.isPlayer()) {
                for (UUID id : plugin.profiles().get(sender.player()).ignored) {
                    OfflinePlayer o = plugin.lookup().byId(id);
                    if (o != null && o.name() != null) out.add(o.name());
                }
            } else if (!name.equals("clearchat") && !name.equals("mutechat")) {
                out.addAll(plugin.completePlayers(sender, ""));
            }
        } else if (args.length == 2 && name.equals("mute")) {
            out.addAll(List.of("10m", "1h", "1d", "7d", "perm"));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
