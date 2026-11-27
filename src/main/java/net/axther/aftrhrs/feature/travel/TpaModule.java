package net.axther.aftrhrs.feature.travel;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.net.Companion;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Teleport requests: /tpa, /tpahere, /tpaccept, /tpdeny, /tpacancel, /tptoggle. Requests arrive as a chat card with
 * clickable Accept / Deny buttons and a sound (plus the Companion's toast); /tpa on its own opens a player picker.
 */
public final class TpaModule implements FeatherModule, Travel.Named {
    private static volatile TpaModule active;

    /** {@code here}: the target is asked to come to the sender (/tpahere) instead of the other way round. */
    private record Request(UUID from, UUID to, boolean here, long expiresAt, Tasks.Task expiry) {
        UUID traveler() {
            return here ? to : from;
        }

        UUID host() {
            return here ? from : to;
        }
    }

    private final Feather plugin;
    /** target -> (sender -> request), newest last. */
    private final Map<UUID, LinkedHashMap<UUID, Request>> incoming = new HashMap<>();
    private final Map<UUID, Long> lastSent = new HashMap<>();
    private int timeoutSeconds;
    private int maxPending;
    private int cooldownSeconds;
    private boolean chestPicker;
    private TpaGui gui;

    public TpaModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Teleport requests";
    }

    @Override
    public void enable() {
        reload();
        gui = new TpaGui(plugin, this);
        active = this;
        Travel.bind(plugin, this, "tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel", "tptoggle");
        plugin.once("tpa.events", () -> ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            TpaModule m = active;
            if (m != null) m.onQuit(h.player);
        }));
        Hub hub = plugin.hub();
        hub.add(new Hub.Entry("visit", Hub.Group.MAIN, Hub.ORDER_VISIT, p -> Perms.has(p, "feather.tpa"),
                p -> withRequestBadge(p, plugin.text().msg("menu.hub.visit")), p -> plugin.text().msg("menu.hub.visit-tooltip"),
                "hub-teleport", this::openPicker));
        hub.requestCount(p -> pendingSenders(p).size());
        hub.addToggle(new Hub.Toggle("requests", Hub.TOGGLE_REQUESTS, p -> Perms.has(p, "feather.tptoggle"),
                p -> !plugin.profiles().get(p).tpToggledOff, p -> {
            // Same as /tptoggle.
            Profiles.Profile q = plugin.profiles().get(p);
            q.tpToggledOff = !q.tpToggledOff;
            plugin.profiles().save(q);
            plugin.text().send(p, q.tpToggledOff ? "tpa.toggled-off" : "tpa.toggled-on");
        }));
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        timeoutSeconds = Math.max(10, c.getInt("tpa.timeout-seconds", 120));
        maxPending = Math.max(1, c.getInt("tpa.max-pending-per-player", 5));
        cooldownSeconds = Math.max(0, c.getInt("tpa.request-cooldown-seconds", 5));
        chestPicker = !"dialog".equalsIgnoreCase(c.getString("tpa.picker", "heads"));
    }

    @Override
    public void disable() {
        if (active == this) active = null;
        for (Map<UUID, Request> m : incoming.values()) for (Request r : m.values()) r.expiry().cancel();
        incoming.clear();
    }

    /** The "✉ n" badge while requests wait for the player. */
    private Component withRequestBadge(ServerPlayer player, Component label) {
        int waiting = pendingSenders(player).size();
        return waiting > 0 ? label.append(plugin.text().msg("menu.hub.teleport-badge", Text.unparsed("count", waiting))) : label;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean run(String command, Sender sender, String[] args) {
        Text text = plugin.text();
        ServerPlayer player = sender.player();
        if (player == null) {
            text.send(sender, "general.players-only");
            return true;
        }
        switch (command) {
            case "tpa", "tpahere" -> {
                boolean here = command.equals("tpahere");
                if (args.length == 0) {
                    openPicker(player);
                    return true;
                }
                ServerPlayer target = plugin.findPlayer(player, args[0]);
                if (target == null) {
                    plugin.notFound(player, args[0]);
                    text.sound(player, "error");
                    return true;
                }
                send(player, target, here);
            }
            case "tpaccept" -> respond(player, args.length > 0 ? args[0] : null, true);
            case "tpdeny" -> respond(player, args.length > 0 ? args[0] : null, false);
            case "tpacancel" -> cancelOutgoing(player, args.length > 0 ? args[0] : null);
            case "tptoggle" -> {
                Profiles.Profile profile = plugin.profiles().get(player);
                profile.tpToggledOff = !profile.tpToggledOff;
                plugin.profiles().save(profile);
                text.send(player, profile.tpToggledOff ? "tpa.toggled-off" : "tpa.toggled-on");
                text.sound(player, "click");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> complete(String command, Sender sender, String[] args) {
        ServerPlayer player = sender.player();
        if (args.length != 1 || player == null) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        switch (command) {
            case "tpa", "tpahere" -> out.addAll(plugin.completeOthers(player, prefix));
            case "tpaccept", "tpdeny" -> {
                for (Request r : incomingOf(player).values()) addName(out, r.from(), prefix);
            }
            case "tpacancel" -> {
                for (Map<UUID, Request> m : incoming.values()) {
                    Request r = m.get(player.getUUID());
                    if (r != null) addName(out, r.to(), prefix);
                }
            }
            default -> {
            }
        }
        return out;
    }

    private void addName(List<String> out, UUID id, String prefix) {
        ServerPlayer p = plugin.player(id);
        if (p != null && Names.name(p).toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(Names.name(p));
    }

    // ------------------------------------------------------------------ requests

    void send(ServerPlayer sender, ServerPlayer target, boolean here) {
        Text text = plugin.text();
        String perm = here ? "feather.tpahere" : "feather.tpa";
        if (!Perms.has(sender, perm)) {
            text.send(sender, "general.no-permission");
            return;
        }
        if (target == sender) {
            text.send(sender, "tpa.self");
            text.sound(sender, "error");
            return;
        }
        Hooks.Ignores ign = plugin.service(Hooks.Ignores.class);
        boolean ignored = ign != null && ign.ignores(target, sender);
        if (ignored || plugin.profiles().get(target).tpToggledOff && !Perms.has(sender, "feather.tptoggle.bypass")) {
            text.send(sender, "tpa.target-toggled", Text.component("target", Names.display(target)));
            text.sound(sender, "error");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSent.get(sender.getUUID());
        if (cooldownSeconds > 0 && last != null && now - last < cooldownSeconds * 1000L
                && !Perms.has(sender, "feather.tpa.nocooldown")) {
            long left = (cooldownSeconds * 1000L - (now - last) + 999) / 1000;
            text.send(sender, "tpa.cooldown", Text.unparsed("seconds", left));
            text.sound(sender, "error");
            return;
        }
        lastSent.put(sender.getUUID(), now);

        LinkedHashMap<UUID, Request> pending = incoming.computeIfAbsent(target.getUUID(), k -> new LinkedHashMap<>());
        Request old = pending.remove(sender.getUUID());
        if (old != null) old.expiry().cancel();
        while (pending.size() >= maxPending) {
            UUID oldest = pending.keySet().iterator().next();
            Request dropped = pending.remove(oldest);
            dropped.expiry().cancel();
            clearToast(oldest, target.getUUID());
            ServerPlayer was = plugin.player(oldest);
            if (was != null) text.send(was, "tpa.request-dropped", Text.component("target", Names.display(target)));
        }
        UUID from = sender.getUUID();
        UUID to = target.getUUID();
        Tasks.Task expiry = Tasks.later(timeoutSeconds * 20L, () -> expire(from, to));
        pending.put(from, new Request(from, to, here, now + timeoutSeconds * 1000L, expiry));

        String senderName = Names.name(sender);
        Component accept = text.button("tpa.button-accept", "/tpaccept " + senderName);
        Component deny = text.button("tpa.button-deny", "/tpdeny " + senderName);
        Component cancel = text.button("tpa.button-cancel", "/tpacancel " + Names.name(target));

        text.send(target, here ? "tpa.received-here" : "tpa.received",
                Text.component("sender", Names.display(sender)),
                Text.component("accept", accept), Text.component("deny", deny),
                Text.unparsed("seconds", timeoutSeconds));
        text.sound(target, "request-received");
        toast(sender, target, here);

        text.send(sender, here ? "tpa.sent-here" : "tpa.sent",
                Text.component("target", Names.display(target)),
                Text.component("cancel", cancel),
                Text.unparsed("seconds", timeoutSeconds));
        text.sound(sender, "request-sent");
    }

    /**
     * The Companion's request toast ({@code tpa} feature), on top of the chat card. Its accept/deny keys run
     * {@code /tpaccept <name>} / {@code /tpdeny <name>}, so they answer exactly this request.
     */
    private void toast(ServerPlayer sender, ServerPlayer target, boolean here) {
        if (!Companion.has(target, "tpa")) return;
        JsonObject msg = Companion.message("tpa_request");
        msg.addProperty("from", Names.plain(sender));
        msg.addProperty("name", Names.name(sender));
        msg.addProperty("here", here);
        msg.addProperty("seconds", timeoutSeconds);
        Companion.send(target, msg);
    }

    /** Takes a request's toast off the target's screen (Companion), however it was answered or ended. */
    private void clearToast(UUID from, UUID to) {
        ServerPlayer target = plugin.player(to);
        if (target == null || !Companion.has(target, "tpa")) return;
        ServerPlayer sender = plugin.player(from);
        String name = sender != null ? Names.name(sender) : null;
        if (name == null) {
            OfflinePlayer off = plugin.lookup().byId(from);
            name = off == null ? null : off.name();
        }
        if (name == null) return;
        JsonObject msg = Companion.message("tpa_clear");
        msg.addProperty("name", name);
        Companion.send(target, msg);
    }

    private void expire(UUID from, UUID to) {
        Map<UUID, Request> pending = incoming.get(to);
        if (pending == null || pending.remove(from) == null) return;
        clearToast(from, to);
        if (pending.isEmpty()) incoming.remove(to);
        ServerPlayer sender = plugin.player(from);
        ServerPlayer target = plugin.player(to);
        Text text = plugin.text();
        if (sender != null) {
            text.send(sender, "tpa.expired-sender", Text.component("target", target == null ? Component.text("?") : Names.display(target)));
            text.sound(sender, "expired");
        }
        if (target != null) {
            text.send(target, "tpa.expired-target", Text.component("sender", sender == null ? Component.text("?") : Names.display(sender)));
        }
    }

    private Map<UUID, Request> incomingOf(ServerPlayer player) {
        Map<UUID, Request> m = incoming.get(player.getUUID());
        return m == null ? Map.of() : m;
    }

    /** Accepts or denies a request; with no name, the newest one (or the request menu if there are several). */
    void respond(ServerPlayer player, String fromName, boolean accept) {
        Text text = plugin.text();
        Map<UUID, Request> pending = incoming.get(player.getUUID());
        if (pending == null || pending.isEmpty()) {
            text.send(player, "tpa.none-pending");
            text.sound(player, "error");
            return;
        }
        Request request = null;
        if (fromName == null) {
            if (pending.size() > 1 && plugin.getConfig().getBoolean("tpa.menu-when-several", true)) {
                openMenu(player);
                return;
            }
            for (Request r : pending.values()) request = r; // newest
        } else {
            ServerPlayer from = plugin.findPlayer(player, fromName);
            if (from != null) request = pending.get(from.getUUID());
            if (request == null) {
                text.send(player, "tpa.none-from", Text.unparsed("input", fromName));
                text.sound(player, "error");
                return;
            }
        }
        if (accept) accept(player, request);
        else deny(player, request);
    }

    private void remove(Request r) {
        r.expiry().cancel();
        clearToast(r.from(), r.to());
        Map<UUID, Request> pending = incoming.get(r.to());
        if (pending != null) {
            pending.remove(r.from());
            if (pending.isEmpty()) incoming.remove(r.to());
        }
    }

    private void accept(ServerPlayer acceptor, Request r) {
        Text text = plugin.text();
        remove(r);
        ServerPlayer sender = plugin.player(r.from());
        if (sender == null) {
            text.send(acceptor, "tpa.sender-offline");
            text.sound(acceptor, "error");
            return;
        }
        ServerPlayer traveler = plugin.player(r.traveler());
        ServerPlayer host = plugin.player(r.host());
        if (traveler == null || host == null) return;
        text.send(acceptor, "tpa.accepted-target", Text.component("sender", Names.display(sender)));
        text.send(sender, "tpa.accepted-sender", Text.component("target", Names.display(acceptor)));
        text.sound(acceptor, "accepted");
        text.sound(sender, "accepted");
        plugin.advance(acceptor, "event", "tpa");
        plugin.advance(sender, "event", "tpa");
        UUID hostId = host.getUUID();
        UUID travelerId = traveler.getUUID();
        Component travelerName = Names.display(traveler);
        // Where the host stands now, not where they are when the warm-up ends; checked for safety on arrival.
        Loc spot = Loc.of(host);
        int seconds = plugin.teleports().warmupFor(traveler);
        if (seconds > 0) text.send(traveler, "tpa.hold-still", Text.unparsed("seconds", seconds));
        plugin.teleports().teleport(traveler, () -> plugin.player(hostId) == null ? null : spot, Names.display(host), () -> {
            ServerPlayer h = plugin.player(hostId);
            if (h != null) text.sound(h, "arrival-host");
            Hooks.Quests quests = plugin.service(Hooks.Quests.class);
            ServerPlayer t = plugin.player(travelerId);
            if (h != null && t != null && quests != null) quests.progress(t, "visit", null, 1, "visit:" + hostId);
        }, true, () -> {
            // Moved or got hurt during the warm-up: the request is used up, so both hear about it (the traveler in
            // the one cancel line, tpa.warmup-cancelled-traveler).
            ServerPlayer h = plugin.player(hostId);
            if (h != null) text.send(h, "tpa.warmup-cancelled-host", Text.component("traveler", travelerName));
        }, "tpa.warmup-cancelled-traveler");
    }

    private void deny(ServerPlayer denier, Request r) {
        Text text = plugin.text();
        remove(r);
        ServerPlayer sender = plugin.player(r.from());
        text.send(denier, "tpa.denied-target", Text.component("sender", sender == null ? Component.text("?") : Names.display(sender)));
        text.sound(denier, "denied");
        if (sender != null) {
            text.send(sender, "tpa.denied-sender", Text.component("target", Names.display(denier)));
            text.sound(sender, "denied");
        }
    }

    private void cancelOutgoing(ServerPlayer player, String targetName) {
        Text text = plugin.text();
        List<Request> mine = new ArrayList<>();
        for (Map<UUID, Request> m : incoming.values()) {
            Request r = m.get(player.getUUID());
            if (r != null) mine.add(r);
        }
        if (targetName != null) {
            ServerPlayer target = plugin.findPlayer(player, targetName);
            mine.removeIf(r -> target == null || !r.to().equals(target.getUUID()));
        }
        if (mine.isEmpty()) {
            text.send(player, "tpa.nothing-to-cancel");
            text.sound(player, "error");
            return;
        }
        for (Request r : mine) {
            remove(r);
            ServerPlayer target = plugin.player(r.to());
            if (target != null) text.send(target, "tpa.cancelled-target", Text.component("sender", Names.display(player)));
            text.send(player, "tpa.cancelled-sender",
                    Text.component("target", target == null ? Component.text("?") : Names.display(target)));
        }
        text.sound(player, "cancel");
    }

    /** A player left (quit, kick or ban): every request to or from them is dropped, and the other side is told. */
    private void onQuit(ServerPlayer quitter) {
        UUID id = quitter.getUUID();
        Text text = plugin.text();
        Component who = Names.display(quitter);
        // Requests others sent to the quitter: each sender hears it was dropped.
        Map<UUID, Request> toQuitter = incoming.remove(id);
        if (toQuitter != null) for (Request r : toQuitter.values()) {
            r.expiry().cancel();
            ServerPlayer s = plugin.player(r.from());
            if (s != null) {
                text.send(s, "tpa.target-left", Text.component("target", who));
                text.sound(s, "expired");
            }
        }
        // Requests the quitter sent: the toast goes (the quitter is still known here, for its name) and each target hears.
        for (Map<UUID, Request> m : incoming.values()) {
            Request r = m.remove(id);
            if (r == null) continue;
            r.expiry().cancel();
            clearToast(r.from(), r.to());
            ServerPlayer t = plugin.player(r.to());
            if (t != null) text.send(t, "tpa.sender-left", Text.component("sender", who));
        }
        incoming.values().removeIf(Map::isEmpty);
        lastSent.remove(id);
    }

    // ------------------------------------------------------------------ menus

    /** The player picker: the chest of player heads (default) or the dialog list. */
    void openPicker(ServerPlayer player) {
        if (chestPicker) gui.open(player, 0);
        else openMenu(player);
    }

    /** Players with a request waiting on {@code player}. */
    Set<UUID> pendingSenders(ServerPlayer player) {
        return new HashSet<>(incomingOf(player).keySet());
    }

    /** Player picker plus any pending requests. */
    void openMenu(ServerPlayer player) {
        Text text = plugin.text();
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.tpa.title"));
        List<Request> requests = new ArrayList<>(incomingOf(player).values());
        Collections.reverse(requests);
        if (!requests.isEmpty()) {
            menu.text(text.msg("menu.tpa.pending-header", Text.unparsed("count", requests.size())));
            for (Request r : requests) {
                ServerPlayer from = plugin.player(r.from());
                if (from == null) continue;
                long left = Math.max(0, (r.expiresAt() - System.currentTimeMillis()) / 1000);
                menu.button(text.msg(r.here() ? "menu.tpa.pending-here" : "menu.tpa.pending",
                                Text.component("sender", Names.display(from))),
                        text.msg("menu.tpa.pending-tooltip", Text.unparsed("seconds", left)), 148,
                        p -> openRequest(p, r));
            }
        }
        List<ServerPlayer> others = new ArrayList<>();
        for (ServerPlayer p : plugin.onlinePlayers()) if (p != player && plugin.canSee(player, p)) others.add(p);
        others.sort((a, b) -> Names.name(a).compareToIgnoreCase(Names.name(b)));
        if (others.isEmpty()) {
            menu.text(text.msg("menu.tpa.nobody-online"));
        } else {
            menu.text(text.msg("menu.tpa.players-header"));
            for (ServerPlayer other : others) {
                UUID id = other.getUUID();
                menu.button(Names.display(other), text.msg("menu.tpa.player-tooltip", Text.unparsed("name", Names.name(other))), 148,
                        p -> {
                            ServerPlayer o = plugin.player(id);
                            if (o == null) text.send(p, "tpa.sender-offline");
                            else openPlayer(p, o);
                        });
            }
        }
        menu.columns(2);
        plugin.exitButton(menu, player);
        menu.open(player);
        text.sound(player, "menu-open");
    }

    private void openPlayer(ServerPlayer player, ServerPlayer other) {
        Text text = plugin.text();
        UUID id = other.getUUID();
        Component name = Names.display(other);
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.tpa.player-title", Text.component("name", name)));
        menu.text(text.msg("menu.tpa.player-body", Text.component("name", name)));
        menu.button(text.msg("menu.tpa.go-to"), text.msg("menu.tpa.go-to-tooltip", Text.component("name", name)), 200,
                p -> withOnline(p, id, o -> send(p, o, false)));
        if (Perms.has(player, "feather.tpahere")) {
            menu.button(text.msg("menu.tpa.bring-here"), text.msg("menu.tpa.bring-here-tooltip", Text.component("name", name)), 200,
                    p -> withOnline(p, id, o -> send(p, o, true)));
        }
        menu.exit(text.msg("menu.back"), this::openMenu);
        menu.open(player);
        text.sound(player, "click");
    }

    private void openRequest(ServerPlayer player, Request r) {
        Text text = plugin.text();
        ServerPlayer from = plugin.player(r.from());
        if (from == null) {
            text.send(player, "tpa.sender-offline");
            return;
        }
        Dialogs.Menu menu = plugin.dialogs().menu(text.msg("menu.tpa.request-title"));
        menu.text(text.msg(r.here() ? "menu.tpa.request-body-here" : "menu.tpa.request-body", Text.component("sender", Names.display(from))));
        String name = Names.name(from);
        menu.button(text.msg("menu.tpa.accept"), null, 140, p -> respond(p, name, true));
        menu.button(text.msg("menu.tpa.deny"), null, 140, p -> respond(p, name, false));
        menu.columns(2);
        menu.exit(text.msg("menu.back"), this::openMenu);
        menu.open(player);
        text.sound(player, "click");
    }

    private void withOnline(ServerPlayer viewer, UUID id, Consumer<ServerPlayer> action) {
        ServerPlayer o = plugin.player(id);
        if (o == null) {
            plugin.text().send(viewer, "tpa.sender-offline");
            plugin.text().sound(viewer, "error");
        } else {
            action.accept(o);
        }
    }
}
