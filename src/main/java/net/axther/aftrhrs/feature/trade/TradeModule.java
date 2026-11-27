package net.axther.aftrhrs.feature.trade;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.PlayerPicker;
import net.axther.aftrhrs.core.Profiles;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.net.Companion;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Safe trading: {@code /trade <player>} sends a request (clickable Accept / Decline, 60 s; a toast on the Companion);
 * accepting opens the two-sided trade window for both players (see {@link Trade}, {@link TradeMenu}). When both are
 * Ready a short countdown runs, then both offers swap in one go. Any cancel (the Cancel button, closing the window,
 * quitting, death, a teleport, walking apart, a freeze, a game mode change, a bank robbery, {@code /feather reload}, a
 * server stop) hands each player their own items back.
 *
 * <p>What a trade holds is written to {@code data/trades.yml} (Paper's item bytes, so the live file loads as it is),
 * before the player's own file is saved without it, so a crash mid-trade gives the items back on the next join. When a
 * trade ends the file lets go of the items before they are handed out, so a crash then can lose them but never pay
 * them twice. Items owed to a player who isn't around wait there too. Every trade is logged to
 * {@code data/trade-log.txt}.
 */
public final class TradeModule implements FeatherModule, FeatherCommand {
    private record Request(UUID from, UUID to, long expiresAt, Tasks.Task expiry) {
    }

    /** Ticks between escrow saves while items move in a trade. */
    static final long PERSIST_DELAY = 20L;
    private static volatile TradeModule active;

    private final Feather plugin;
    private final Path file;
    private final Path logFile;
    /** Player -> their open trade (both players map to the same one). */
    private final Map<UUID, Trade> trades = new HashMap<>();
    /** Target -> (sender -> request), newest last. */
    private final Map<UUID, LinkedHashMap<UUID, Request>> incoming = new HashMap<>();
    private final Map<UUID, Long> lastSent = new HashMap<>();
    /** Players who turned trade requests off. */
    private final Set<UUID> requestsOff = new LinkedHashSet<>();
    /** Items owed back to players who weren't there to take them (dead, offline, or from before a crash). */
    private final Map<UUID, List<ItemStack>> pending = new LinkedHashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    /** data/trades.yml exists but couldn't be read: no trades, and never saved over. */
    private boolean unreadable;
    private Tasks.Task checker;
    private double maxDistance;
    private double breakDistance;
    private int timeoutSeconds;
    private int cooldownSeconds;
    private int countdownSeconds;

    public TradeModule(Feather plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("trades.yml");
        this.logFile = plugin.dataFile("trade-log.txt");
    }

    @Override
    public String name() {
        return "Trading";
    }

    @Override
    public void enable() {
        readConfig();
        load();
        active = this;
        plugin.bind("trade", this);
        checker = Tasks.timer(10L, 10L, this::tick);
        plugin.hub().add("trade", Hub.Group.COMMUNITY, Hub.ORDER_TRADE, "hub-social", "feather.trade", this::openPicker);
        plugin.hub().addToggle(new Hub.Toggle("trades", Hub.TOGGLE_TRADES, p -> Perms.has(p, "feather.trade"), this::allows, this::toggle));
        plugin.once("trade.events", () -> {
            FeatherEvents.PLAYER_DEATH.register((p, source) -> {
                TradeModule m = active;
                if (m != null) m.onDeath(p);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
                TradeModule m = active;
                if (m != null) m.onQuit(h.player);
            });
            ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
                TradeModule m = active;
                if (m != null) m.onJoin(h.player);
            });
            FeatherEvents.PLAYER_RESPAWN.register((old, now, alive) -> {
                TradeModule m = active;
                if (m != null && m.pending.containsKey(now.getUUID())) Tasks.later(5L, () -> m.deliverId(now.getUUID()));
            });
            FeatherEvents.PLAYER_TELEPORTED.register((p, from, to, cause) -> {
                TradeModule m = active;
                Trade t = m == null ? null : m.tradeOf(p);
                if (t != null) Tasks.later(1L, () -> m.validate(t));
            });
            ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> {
                TradeModule m = active;
                Trade t = m == null ? null : m.tradeOf(p);
                if (t != null) m.validate(t);
            });
        });
    }

    @Override
    public void reload() {
        endAll("reload");
        readConfig();
    }

    @Override
    public void disable() {
        endAll("stopped");
        if (checker != null) checker.cancel();
        for (Map<UUID, Request> m : incoming.values()) for (Request r : m.values()) r.expiry().cancel();
        incoming.clear();
        if (active == this) active = null;
    }

    private void readConfig() {
        Yaml c = plugin.getConfig();
        maxDistance = Math.max(1, c.getDouble("trade.max-distance", 16));
        breakDistance = Math.max(maxDistance, c.getDouble("trade.break-distance", 24));
        timeoutSeconds = Math.max(5, c.getInt("trade.request-timeout-seconds", 60));
        cooldownSeconds = Math.max(0, c.getInt("trade.request-cooldown-seconds", 5));
        countdownSeconds = Math.max(1, Math.min(30, c.getInt("trade.countdown-seconds", 3)));
    }

    Feather plugin() {
        return plugin;
    }

    Text text() {
        return plugin.text();
    }

    int countdownSeconds() {
        return countdownSeconds;
    }

    private static Component display(ServerPlayer p) {
        return Names.display(p);
    }

    // ------------------------------------------------------------------ data

    private void load() {
        Yaml yml = SafeYaml.read(file, plugin.getLogger(), "trade escrow and settings");
        if (yml == null) {
            unreadable = true;
            return;
        }
        for (String raw : yml.getStringList("requests-off")) {
            try {
                requestsOff.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
        Yaml escrow = yml.getConfigurationSection("escrow");
        if (escrow == null) return;
        for (String key : escrow.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String data = escrow.getString(key + ".items", "");
                List<ItemStack> list = data.isEmpty() ? List.of() : Stacks.copies(net.axther.aftrhrs.migrate.PaperItemBytes.decodeBase64(data));
                if (!list.isEmpty()) pending.computeIfAbsent(id, k -> new ArrayList<>()).addAll(list);
                names.put(id, escrow.getString(key + ".name", "?"));
            } catch (RuntimeException e) {
                // Never drop it silently: keep the file as it is and stop trading until someone looks.
                plugin.getLogger().error("data/trades.yml: could not read the items held for {}. Trading is off and the file is left "
                        + "untouched until it is fixed.", key, e);
                unreadable = true;
                return;
            }
        }
        if (!pending.isEmpty()) {
            plugin.getLogger().info("Trading: items from {} unfinished trade(s) go back to their owners when they are around.", pending.size());
        }
    }

    /** Writes the opt-outs and everything held or owed. Main thread, so it is on disk before a player's file changes. */
    void persist() {
        if (unreadable) return;
        Yaml yml = new Yaml();
        if (!requestsOff.isEmpty()) yml.set("requests-off", requestsOff.stream().map(UUID::toString).toList());
        Map<UUID, List<ItemStack>> all = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<ItemStack>> e : pending.entrySet()) all.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        for (Trade t : liveTrades()) {
            for (Trade.Side s : t.sides()) {
                if (!s.persisted.isEmpty()) all.computeIfAbsent(s.id, k -> new ArrayList<>()).addAll(s.persisted);
                names.put(s.id, s.name);
            }
        }
        for (Map.Entry<UUID, List<ItemStack>> e : all.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String base = "escrow." + e.getKey() + ".";
            yml.set(base + "name", names.getOrDefault(e.getKey(), "?"));
            yml.set(base + "items", net.axther.aftrhrs.migrate.PaperItemBytes.encodeBase64(e.getValue()));
        }
        SafeYaml.write(file, yml, plugin.getLogger());
    }

    /** Saves what the trade now holds (offer + cursor), then each changed player's own file. */
    void persistIfChanged(Trade t) {
        List<ServerPlayer> changed = new ArrayList<>();
        for (Trade.Side s : t.sides()) {
            List<ItemStack> now = s.held();
            if (!Stacks.same(now, s.persisted)) {
                s.persisted = now;
                ServerPlayer p = s.player();
                if (p != null) changed.add(p);
            }
        }
        if (changed.isEmpty()) return;
        persist();
        for (ServerPlayer p : changed) saveData(p);
    }

    private void saveData(ServerPlayer p) {
        try {
            if (p != null && !p.hasDisconnected()) Feather.server().getPlayerList().save(p);
        } catch (RuntimeException e) {
            plugin.getLogger().warn("Could not save {}'s player data after a trade change.", Names.name(p), e);
        }
    }

    private void log(String line) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, Instant.now() + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warn("Could not write {}: {}", logFile, e.toString());
        }
    }

    // ------------------------------------------------------------------ who may trade

    private Set<Trade> liveTrades() {
        return new LinkedHashSet<>(trades.values());
    }

    Trade tradeOf(ServerPlayer p) {
        return trades.get(p.getUUID());
    }

    public boolean allows(ServerPlayer p) {
        return !requestsOff.contains(p.getUUID());
    }

    /** Settings toggle and /trade toggle. */
    public void toggle(ServerPlayer p) {
        if (!requestsOff.remove(p.getUUID())) requestsOff.add(p.getUUID());
        persist();
        text().send(p, allows(p) ? "trade.toggled-on" : "trade.toggled-off");
    }

    private boolean robbing(ServerPlayer p) {
        Hooks.Bank bank = plugin.service(Hooks.Bank.class);
        return bank != null && bank.robbing(p);
    }

    /** Why this player can't trade right now ({@code trade.why.*}), or null. */
    private String blocked(ServerPlayer p) {
        if (plugin.authPending(p)) return "auth";
        Profiles.Profile profile = plugin.profiles().get(p);
        if (profile != null && profile.frozen) return "frozen";
        if (p.isSpectator()) return "spectator";
        if (robbing(p)) return "robbery";
        if (p.isDeadOrDying()) return "dead";
        return null;
    }

    private static boolean near(ServerPlayer a, ServerPlayer b, double max) {
        return a.level() == b.level() && a.distanceToSqr(b) <= max * max;
    }

    private void error(ServerPlayer p, String key, TagResolver... r) {
        text().send(p, key, r);
        text().sound(p, "error");
    }

    /** Checks both players for a request or opening the window; tells {@code teller} why not. */
    private boolean canOpen(ServerPlayer me, ServerPlayer other, ServerPlayer teller) {
        String why = blocked(me);
        if (why != null) {
            if (!why.equals("auth")) error(teller, "trade.blocked-you", Text.component("why", text().msg("trade.why." + why)));
            return false;
        }
        why = blocked(other);
        if (why != null) {
            error(teller, "trade.blocked-them", Text.component("player", display(other)),
                    Text.component("why", text().msg("trade.why." + (why.equals("auth") ? "busy" : why))));
            return false;
        }
        if (tradeOf(me) != null) {
            error(teller, "trade.busy-you");
            return false;
        }
        if (tradeOf(other) != null) {
            error(teller, "trade.busy-them", Text.component("player", display(other)));
            return false;
        }
        if (me.level() != other.level()) {
            error(teller, "trade.other-world", Text.component("player", display(other)));
            return false;
        }
        if (!near(me, other, maxDistance)) {
            error(teller, "trade.too-far", Text.component("player", display(other)), Text.unparsed("distance", (long) maxDistance));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        ServerPlayer p = sender.player();
        if (p == null) {
            text().send(sender, "general.players-only");
            return true;
        }
        if (plugin.authPending(p)) return true;
        if (!Perms.has(p, "feather.trade")) {
            text().send(p, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            openPicker(p);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> respond(p, args.length > 1 ? args[1] : null, true);
            case "deny", "decline" -> respond(p, args.length > 1 ? args[1] : null, false);
            case "toggle" -> {
                toggle(p);
                text().sound(p, "click");
            }
            default -> {
                ServerPlayer target = plugin.findPlayer(p, args[0]);
                if (target == null) {
                    plugin.notFound(p, args[0]);
                    text().sound(p, "error");
                } else send(p, target);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        ServerPlayer p = sender.player();
        if (p == null) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("accept", "deny", "toggle")) if (s.startsWith(prefix)) out.add(s);
            out.addAll(plugin.lookup().complete(p, prefix, true));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            Map<UUID, Request> mine = incoming.getOrDefault(p.getUUID(), new LinkedHashMap<>());
            for (UUID id : mine.keySet()) {
                ServerPlayer o = plugin.player(id);
                if (o != null && Names.name(o).toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(Names.name(o));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ requests

    void send(ServerPlayer sender, ServerPlayer target) {
        if (unreadable) {
            error(sender, "trade.closed");
            return;
        }
        if (target == sender) {
            error(sender, "trade.self");
            return;
        }
        Hooks.Ignores ignores = plugin.service(Hooks.Ignores.class);
        boolean ignored = ignores != null && ignores.ignores(target, sender);
        if (ignored || !allows(target) || !Perms.has(target, "feather.trade")) {
            error(sender, "trade.target-off", Text.component("player", display(target)));
            return;
        }
        if (!canOpen(sender, target, sender)) return;
        // They already asked us: that's a yes.
        Map<UUID, Request> mine = incoming.get(sender.getUUID());
        if (mine != null && mine.containsKey(target.getUUID())) {
            accept(sender, mine.get(target.getUUID()));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSent.get(sender.getUUID());
        if (cooldownSeconds > 0 && last != null && now - last < cooldownSeconds * 1000L) {
            long left = (cooldownSeconds * 1000L - (now - last) + 999) / 1000;
            error(sender, "trade.cooldown", Text.unparsed("seconds", left));
            return;
        }
        lastSent.put(sender.getUUID(), now);
        // One open request per sender; whoever had the older one hears that it's gone.
        for (Map<UUID, Request> m : incoming.values()) {
            Request old = m.remove(sender.getUUID());
            if (old == null) continue;
            old.expiry().cancel();
            // Same target: the new trade_request replaces the toast in place (like tpa), so no clear in between.
            if (!old.to().equals(target.getUUID())) companionClear(old.to(), Names.name(sender));
            ServerPlayer was = plugin.player(old.to());
            if (was != null && !old.to().equals(target.getUUID())) {
                text().send(was, "trade.withdrawn", Text.component("sender", display(sender)));
            }
        }
        incoming.values().removeIf(Map::isEmpty);
        UUID from = sender.getUUID(), to = target.getUUID();
        Tasks.Task expiry = Tasks.later(timeoutSeconds * 20L, () -> expire(from, to));
        incoming.computeIfAbsent(to, k -> new LinkedHashMap<>()).put(from, new Request(from, to, now + timeoutSeconds * 1000L, expiry));
        names.put(from, Names.name(sender));
        names.put(to, Names.name(target));

        Component accept = text().button("trade.button-accept", "/trade accept " + Names.name(sender));
        Component deny = text().button("trade.button-deny", "/trade deny " + Names.name(sender));
        text().send(target, "trade.received", Text.component("sender", display(sender)),
                Text.component("accept", accept), Text.component("deny", deny), Text.unparsed("seconds", timeoutSeconds));
        companionRequest(target, sender);
        text().sound(target, "request-received");
        text().send(sender, "trade.sent", Text.component("target", display(target)), Text.unparsed("seconds", timeoutSeconds));
        text().sound(sender, "request-sent");
    }

    private void expire(UUID from, UUID to) {
        Map<UUID, Request> m = incoming.get(to);
        if (m == null || m.remove(from) == null) return;
        if (m.isEmpty()) incoming.remove(to);
        companionClear(to, nameOf(from));
        ServerPlayer sender = plugin.player(from), target = plugin.player(to);
        if (sender != null) {
            text().send(sender, "trade.expired-sender", Text.component("target",
                    target == null ? Component.text(names.getOrDefault(to, "?")) : display(target)));
            text().sound(sender, "expired");
        }
        if (target != null && sender != null) text().send(target, "trade.expired-target", Text.component("sender", display(sender)));
    }

    private void removeRequest(Request r) {
        r.expiry().cancel();
        Map<UUID, Request> m = incoming.get(r.to());
        if (m != null) {
            m.remove(r.from());
            if (m.isEmpty()) incoming.remove(r.to());
        }
        companionClear(r.to(), nameOf(r.from()));
    }

    /**
     * The Companion's request toast ({@code trade} feature), on top of the chat card. Its accept / deny keys run
     * {@code /trade accept <name>} / {@code /trade deny <name>}, so they answer exactly this request and every check
     * ({@link #canOpen}) runs again.
     */
    private void companionRequest(ServerPlayer target, ServerPlayer sender) {
        if (!Companion.has(target, "trade")) return;
        JsonObject msg = Companion.message("trade_request");
        msg.addProperty("from", PlainTextComponentSerializer.plainText().serialize(display(sender)));
        msg.addProperty("name", Names.name(sender));
        msg.addProperty("seconds", timeoutSeconds);
        Companion.send(target, msg);
    }

    /** Takes a trade request's toast off the target's screen (Companion), however it was answered or ended. */
    private void companionClear(UUID to, String senderName) {
        if (senderName == null) return;
        ServerPlayer target = plugin.player(to);
        if (target == null || !Companion.has(target, "trade")) return;
        JsonObject msg = Companion.message("trade_clear");
        msg.addProperty("name", senderName);
        Companion.send(target, msg);
    }

    /** A player's real name, online or not (null when the server never saw them). */
    private String nameOf(UUID id) {
        ServerPlayer p = plugin.player(id);
        if (p != null) return Names.name(p);
        String known = names.get(id);
        if (known != null && !known.equals("?")) return known;
        OfflinePlayer o = plugin.lookup().byId(id);
        return o == null ? null : o.name();
    }

    private void respond(ServerPlayer p, String fromName, boolean accept) {
        Map<UUID, Request> m = incoming.get(p.getUUID());
        if (m == null || m.isEmpty()) {
            error(p, "trade.none-pending");
            return;
        }
        Request r = null;
        if (fromName == null) {
            for (Request x : m.values()) r = x; // newest
        } else {
            ServerPlayer from = plugin.findPlayer(p, fromName);
            if (from != null) r = m.get(from.getUUID());
            if (r == null) {
                error(p, "trade.none-from", Text.unparsed("input", fromName));
                return;
            }
        }
        if (accept) accept(p, r);
        else {
            removeRequest(r);
            ServerPlayer sender = plugin.player(r.from());
            text().send(p, "trade.denied-target", Text.component("sender", sender == null ? Component.text("?") : display(sender)));
            text().sound(p, "denied");
            if (sender != null) {
                text().send(sender, "trade.denied-sender", Text.component("target", display(p)));
                text().sound(sender, "denied");
            }
        }
    }

    private void accept(ServerPlayer acceptor, Request r) {
        ServerPlayer sender = plugin.player(r.from());
        if (sender == null) {
            removeRequest(r);
            error(acceptor, "trade.offline");
            return;
        }
        if (unreadable) {
            error(acceptor, "trade.closed");
            return;
        }
        if (!canOpen(acceptor, sender, acceptor)) return;
        removeRequest(r);
        // Any other request between these two is answered by this one.
        Map<UUID, Request> back = incoming.get(sender.getUUID());
        if (back != null && back.containsKey(acceptor.getUUID())) removeRequest(back.get(acceptor.getUUID()));
        Trade t = new Trade(this, sender, acceptor);
        trades.put(sender.getUUID(), t);
        trades.put(acceptor.getUUID(), t);
        if (!t.open()) {
            end(t, "unavailable", null, null);
            return;
        }
        for (Trade.Side s : t.sides()) {
            ServerPlayer p = s.player();
            text().send(p, "trade.opened", Text.component("player", display(s.other().player())),
                    Text.unparsed("seconds", countdownSeconds));
            text().sound(p, "accepted");
        }
    }

    /** The hub's Trade button: a picker of nearby players; a click sends the request. */
    public void openPicker(ServerPlayer p) {
        Set<UUID> far = new HashSet<>();
        for (ServerPlayer o : plugin.onlinePlayers()) if (!near(p, o, maxDistance)) far.add(o.getUUID());
        plugin.picker().open(p, new PlayerPicker.Request(text().msg("trade.picker-title"), Set::of, id ->
                // After the picker has redrawn itself: close it, then send (an accepted request opens the trade window).
                Tasks.sync(() -> {
                    p.closeContainer();
                    ServerPlayer target = plugin.player(id);
                    if (target == null) error(p, "trade.offline");
                    else send(p, target);
                }), far, () -> plugin.openHub(p)), 0);
        text().sound(p, "menu-open");
    }

    // ------------------------------------------------------------------ ending

    /** Checks an open trade; ends it (and returns false) when it can't go on. */
    boolean validate(Trade t) {
        if (t.ended) return false;
        ServerPlayer pa = t.a.player(), pb = t.b.player();
        if (pa == null || pb == null) {
            end(t, "left", pa == null ? t.a : t.b, null);
            return false;
        }
        for (Trade.Side s : t.sides()) {
            ServerPlayer p = s.player();
            if (!s.viewing(p)) {
                end(t, "closed", s, null);
                return false;
            }
            if (p.isDeadOrDying()) {
                end(t, "died", s, null);
                return false;
            }
            if (blocked(p) != null) {
                end(t, "unavailable", s, null);
                return false;
            }
        }
        if (!near(pa, pb, breakDistance)) {
            end(t, "too-far", null, null);
            return false;
        }
        return true;
    }

    private void tick() {
        for (Trade t : liveTrades()) validate(t);
        if (!pending.isEmpty()) for (UUID id : new ArrayList<>(pending.keySet())) deliverId(id);
    }

    private void deliverId(UUID id) {
        ServerPlayer p = plugin.player(id);
        if (p != null) deliver(p);
    }

    /** Hands a player what they're owed, once they're alive, through the gate and not trading. */
    private void deliver(ServerPlayer p) {
        if (!pending.containsKey(p.getUUID()) || p.hasDisconnected() || p.isDeadOrDying() || plugin.authPending(p) || tradeOf(p) != null) return;
        List<ItemStack> items = pending.remove(p.getUUID());
        persist();   // the file lets go first: a crash now can lose them, never give them twice
        boolean dropped = Stacks.give(p, items);
        saveData(p);
        text().send(p, dropped ? "trade.returned-dropped" : "trade.returned");
        text().sound(p, "item-pickup");
        log("RETURNED " + Names.name(p) + " " + p.getUUID() + " | " + Stacks.describe(items));
    }

    /** The cursor of a side's open window, taken off it (empty when not viewing). */
    private static ItemStack takeCursor(Trade.Side s, ServerPlayer p) {
        if (!s.viewing(p) || s.menu.getCarried().isEmpty()) return ItemStack.EMPTY;
        ItemStack c = s.menu.getCarried().copy();
        s.menu.setCarried(ItemStack.EMPTY);
        return c;
    }

    /**
     * Calls a trade off: each player gets their own offer (and cursor) back. {@code by} is the side that caused it (or
     * null); {@code closing} is a player whose window is closing right now (it isn't closed again).
     */
    void end(Trade t, String reason, Trade.Side by, ServerPlayer closing) {
        if (t.ended) return;
        t.ended = true;
        t.stopCountdown();
        trades.remove(t.a.id, t);
        trades.remove(t.b.id, t);
        boolean stopping = reason.equals("stopped");
        Map<Trade.Side, List<ItemStack>> back = new LinkedHashMap<>();
        for (Trade.Side s : t.sides()) {
            List<ItemStack> items = s.takeAll();
            items.addAll(s.extra);
            s.extra.clear();
            ItemStack cursor = takeCursor(s, s.player());
            if (!cursor.isEmpty()) items.add(cursor);
            back.put(s, items);
        }
        Map<Trade.Side, Boolean> dropped = new HashMap<>();
        List<Trade.Side> handOut = new ArrayList<>();
        for (Map.Entry<Trade.Side, List<ItemStack>> e : back.entrySet()) {
            Trade.Side s = e.getKey();
            ServerPlayer p = s.player();
            boolean away = p == null || p.hasDisconnected() || p.isDeadOrDying() || (reason.equals("died") && s == by)
                    || (reason.equals("left") && s == by);
            if (e.getValue().isEmpty()) continue;
            if (away) pending.computeIfAbsent(s.id, k -> new ArrayList<>()).addAll(e.getValue());
            else handOut.add(s);
        }
        // The file lets go of what is handed out now before anyone gets it: a crash can lose it, never return it twice.
        for (Trade.Side s : t.sides()) s.persisted = List.of();
        persist();
        for (Trade.Side s : handOut) {
            ServerPlayer p = s.player();
            dropped.put(s, Stacks.give(p, back.get(s)));
            saveData(p);
        }
        for (Trade.Side s : t.sides()) {
            ServerPlayer p = s.player();
            if (p != null && p != closing && s.viewing(p)) p.closeContainer();
        }
        Component who = by == null ? Component.empty() : by.player() != null ? display(by.player()) : Component.text(by.name);
        for (Trade.Side s : t.sides()) {
            ServerPlayer p = s.player();
            if (p == null || p.hasDisconnected()) continue;
            String key = "trade.ended." + reason + (by == null ? "" : by == s ? "-you" : "-them");
            text().send(p, key, Text.component("player", who));
            if (!back.get(s).isEmpty()) {
                if (Boolean.TRUE.equals(dropped.get(s))) text().send(p, "trade.back-dropped");
                else if (pending.containsKey(s.id)) text().send(p, "trade.back-later");
                else text().send(p, "trade.back");
            }
            if (!stopping) text().sound(p, "cancel");
        }
        log("CANCELLED " + t.a.name + " " + t.a.id + " <-> " + t.b.name + " " + t.b.id + " reason=" + reason
                + (by == null ? "" : " by " + by.name) + " | back to " + t.a.name + ": " + Stacks.describe(back.get(t.a))
                + " | back to " + t.b.name + ": " + Stacks.describe(back.get(t.b)));
    }

    /** The countdown ran out and both have room: swap. */
    void complete(Trade t, ServerPlayer pa, ServerPlayer pb) {
        t.ended = true;
        t.stopCountdown();
        trades.remove(t.a.id, t);
        trades.remove(t.b.id, t);
        List<ItemStack> gaveA = t.a.takeAll(), gaveB = t.b.takeAll();
        List<ItemStack> toA = new ArrayList<>(gaveB), toB = new ArrayList<>(gaveA);
        toA.addAll(t.a.extra);
        toB.addAll(t.b.extra);
        t.a.extra.clear();
        t.b.extra.clear();
        ItemStack ca = takeCursor(t.a, pa), cb = takeCursor(t.b, pb);
        if (!ca.isEmpty()) toA.add(ca);
        if (!cb.isEmpty()) toB.add(cb);
        // The file lets go first: a crash from here can lose this swap, never pay it out twice.
        t.a.persisted = List.of();
        t.b.persisted = List.of();
        persist();
        boolean dropA = Stacks.give(pa, toA), dropB = Stacks.give(pb, toB);
        saveData(pa);
        saveData(pb);
        pa.closeContainer();
        pb.closeContainer();
        log("TRADE " + t.a.name + " " + t.a.id + " <-> " + t.b.name + " " + t.b.id + " | " + t.a.name + " gave: " + Stacks.describe(gaveA)
                + " | " + t.b.name + " gave: " + Stacks.describe(gaveB) + " | coins: none");
        text().send(pa, "trade.done", Text.component("player", display(pb)), Text.unparsed("count", Stacks.count(gaveB)));
        text().send(pb, "trade.done", Text.component("player", display(pa)), Text.unparsed("count", Stacks.count(gaveA)));
        if (dropA) text().send(pa, "trade.back-dropped");
        if (dropB) text().send(pb, "trade.back-dropped");
        text().sound(pa, "accepted");
        text().sound(pb, "accepted");
        plugin.advance(pa, "event", "trade");
        plugin.advance(pb, "event", "trade");
    }

    private void endAll(String reason) {
        for (Trade t : liveTrades()) end(t, reason, null, null);
    }

    // ------------------------------------------------------------------ events

    private void onDeath(ServerPlayer p) {
        Trade t = tradeOf(p);
        if (t != null) end(t, "died", t.side(p.getUUID()), null);
    }

    private void onQuit(ServerPlayer p) {
        Trade t = tradeOf(p);
        if (t != null) end(t, "left", t.side(p.getUUID()), p);
        UUID id = p.getUUID();
        Map<UUID, Request> mine = incoming.remove(id);
        if (mine != null) for (Request r : mine.values()) r.expiry().cancel();
        for (Map<UUID, Request> m : incoming.values()) {
            Request r = m.remove(id);
            if (r == null) continue;
            r.expiry().cancel();
            companionClear(r.to(), Names.name(p));
        }
        incoming.values().removeIf(Map::isEmpty);
        lastSent.remove(id);
    }

    private void onJoin(ServerPlayer p) {
        names.put(p.getUUID(), Names.name(p));
        if (pending.containsKey(p.getUUID())) Tasks.later(40L, () -> deliverId(p.getUUID()));
    }
}
