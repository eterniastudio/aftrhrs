package net.axther.aftrhrs.feature.moderation;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.PlayerLookup;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.title.Title;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /freeze and /unfreeze: a frozen player can look around but not move, build, interact, use or move items, fight or
 * take damage ({@link Restraint}), or run commands other than the ones in {@code moderation.freeze-allowed-commands}
 * (/msg and /r, to talk to staff). Saved in the profile, so it holds across relogs. Leaving while frozen tells staff,
 * or bans ({@code moderation.freeze-quit-action: ban}).
 */
public final class Freeze implements FeatherCommand {
    private static volatile Freeze active;

    private final Feather plugin;
    private final ModerationModule mod;
    private Tasks.Task reminder;
    /** Players kicked by staff (or banned) this tick: their quit isn't "logging out while frozen". */
    private final Set<UUID> kicked = ConcurrentHashMap.newKeySet();

    Freeze(Feather plugin, ModerationModule mod) {
        this.plugin = plugin;
        this.mod = mod;
    }

    void enable() {
        active = this;
        plugin.bind("freeze", this);
        plugin.bind("unfreeze", this);
        // Keep the title up while frozen.
        reminder = Tasks.timer(60L, 60L, () -> {
            for (ServerPlayer p : plugin.onlinePlayers()) if (frozen(p)) showTitle(p);
        });
        plugin.once("freeze.events", () -> {
            Restraint.register(p -> {
                Freeze f = active;
                return f != null && f.frozen(p);
            });
            FeatherEvents.COMMAND.register((p, cmd) -> {
                Freeze f = active;
                return f == null || f.onCommand(p, cmd);
            });
            ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
                Freeze f = active;
                if (f != null) f.joined(h.player);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
                Freeze f = active;
                if (f != null) f.quit(h.player);
            });
        });
    }

    void disable() {
        if (active == this) active = null;
        if (reminder != null) reminder.cancel();
    }

    public boolean frozen(ServerPlayer p) {
        return plugin.profiles().get(p).frozen;
    }

    /** The player is about to be kicked or banned by staff: their leaving isn't a frozen player logging out. */
    void markKicked(UUID id) {
        kicked.add(id);
        Tasks.later(1L, () -> kicked.remove(id));
    }

    private void showTitle(ServerPlayer p) {
        Text text = plugin.text();
        Adv.of(p).showTitle(Title.title(text.msg("mod.freeze-title"), text.msg("mod.freeze-subtitle"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(3500), Duration.ofMillis(300))));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        boolean freeze = CommandBridge.nameOf(label).equals("freeze");
        if (args.length == 0) {
            text.send(sender, freeze ? "mod.freeze-usage" : "mod.unfreeze-usage");
            return true;
        }
        OfflinePlayer t = mod.target(sender, args[0]);
        if (t == null) return true;
        String name = ModerationModule.nameOf(t, args[0]);
        String staff = ModerationModule.staffName(sender);
        if (freeze && mod.exempt(sender, t)) {
            text.send(sender, "mod.exempt");
            return true;
        }
        if (freeze && sender.isPlayer() && sender.player().getUUID().equals(t.id())) {
            text.send(sender, "mod.freeze-self");
            return true;
        }
        Profiles.Profile pr = plugin.profiles().load(t.id());
        if (pr.frozen == freeze) {
            text.send(sender, freeze ? "mod.already-frozen" : "mod.not-frozen", Text.unparsed("name", name));
            return true;
        }
        pr.frozen = freeze;
        plugin.profiles().save(pr);
        mod.log(t.id(), freeze ? "freeze" : "unfreeze", staff, "", 0);
        text.send(sender, freeze ? "mod.frozen" : "mod.unfrozen", Text.unparsed("name", name));
        mod.notifyStaff(sender, freeze ? "mod.notify-freeze" : "mod.notify-unfreeze", Text.unparsed("staff", staff), Text.unparsed("name", name));
        ServerPlayer online = t.online();
        if (online != null) {
            if (freeze) {
                online.stopRiding();
                showTitle(online);
                text.send(online, "mod.you-are-frozen");
                text.sound(online, "error");
            } else {
                online.connection.send(new ClientboundClearTitlesPacket(true));
                text.send(online, "mod.you-are-unfrozen");
                text.sound(online, "accepted");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            boolean freeze = CommandBridge.nameOf(label).equals("freeze");
            for (ServerPlayer p : plugin.onlinePlayers()) {
                if (!plugin.canSee(sender.player(), p) || frozen(p) == freeze) continue;
                out.add(Names.name(p));
                String nick = PlayerLookup.nickToken(p);
                if (nick != null) out.add(nick);
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ what a frozen player can't do

    private boolean onCommand(ServerPlayer p, String line) {
        if (!frozen(p) || Perms.has(p, "feather.freeze")) return true;
        int space = line.indexOf(' ');
        String label = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (plugin.getConfig().getStringList("moderation.freeze-allowed-commands").contains(label)) return true;
        plugin.text().send(p, "mod.frozen-command");
        plugin.text().sound(p, "error");
        return false;
    }

    // ------------------------------------------------------------------ join / leave

    private void joined(ServerPlayer p) {
        if (!frozen(p)) return;
        Tasks.later(20L, () -> {
            if (p.hasDisconnected() || !frozen(p)) return;
            showTitle(p);
            plugin.text().send(p, "mod.you-are-frozen");
        });
        mod.notifyStaff(null, "mod.notify-frozen-join", Text.unparsed("name", Names.name(p)));
    }

    private void quit(ServerPlayer p) {
        if (!frozen(p) || kicked.remove(p.getUUID())) return;
        boolean ban = plugin.getConfig().getString("moderation.freeze-quit-action", "notify").equalsIgnoreCase("ban");
        mod.notifyStaff(null, ban ? "mod.notify-frozen-quit-ban" : "mod.notify-frozen-quit", Text.unparsed("name", Names.name(p)));
        OfflinePlayer who = OfflinePlayer.of(p);
        if (ban && Sanctions.banOf(who) == null && mod.sanctions() != null) {
            String reason = plugin.text().raw("mod.freeze-quit-reason");
            // After the player is gone, so the ban doesn't kick someone who's already leaving.
            Tasks.later(1L, () -> mod.sanctions().ban(new Sender(Feather.server().createCommandSourceStack()), who, Names.name(p), -1, reason, "Feather"));
        }
    }
}
