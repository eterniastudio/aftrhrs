package net.axther.aftrhrs.feature.auth;

import com.mojang.brigadier.suggestion.Suggestions;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.JoinDigest;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.OfflinePlayer;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Placeholders;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.feature.moderation.Restraint;
import net.axther.aftrhrs.net.Companion;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The auth gate: the whitelist is off on purpose, so a player who has never been here types the server code in chat
 * once. Until then they are blind, held in place (looking around is fine, {@link Restraint}), can't interact, fight,
 * take damage, move items, open inventories or menus, run or even see commands, and mobs ignore them; the Companion
 * answers nothing but the handshake ({@code Companion.gate}). What they type in chat is taken in the first chat phase
 * ({@link FeatherEvents#CHAT_GATE}) and never reaches anyone or any log. {@code auth.max-tries} wrong codes or
 * {@code auth.timeout-seconds} disconnect them; failed sessions put the account and its IP on a rejoin cooldown that
 * doubles each time. The right code trusts the UUID for good. Data: {@code data/auth.yml} (the code only as a salted
 * PBKDF2 hash). Without a code the gate is off. Provides {@link Hooks.Auth}.
 */
public final class AuthModule implements FeatherModule, FeatherCommand {
    static final String BYPASS = "feather.auth.bypass";
    static final String ADMIN = "feather.auth.admin";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int MIN_LENGTH = 4;
    /** A strike older than this is forgotten (the next failure starts the cooldown over). */
    private static final long STRIKE_MEMORY_MS = TimeUnit.HOURS.toMillis(24);

    /** A player who entered the code (or was trusted when the gate first came on). */
    record Trusted(String name, long date, String ip) {
    }

    /** Failed sessions of one account ("uuid:...") or one address ("ip:..."). */
    static final class Strike {
        int count;
        long until;
        long last;
        /** For account entries: the address of the last failed session (so /auth reset can clear it too). */
        String ip;
    }

    /** A player waiting to type the code. */
    static final class Session {
        final long started = System.currentTimeMillis();
        final String ip;
        /** Answers checked. */
        int tries;
        /** Answers taken for checking; never more than max-tries. */
        int reserved;
        boolean done;
        Tasks.Task prompt;
        Tasks.Task timeout;

        Session(String ip) {
            this.ip = ip;
        }
    }

    private static volatile AuthModule active;

    private final Feather plugin;
    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Trusted> trusted = new ConcurrentHashMap<>();
    private final Map<String, Strike> strikes = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AftrHrs-Auth-IO");
        t.setDaemon(true);
        return t;
    });

    private volatile String salt;
    private volatile String hash;
    private int iterations = ITERATIONS;
    private String setBy;
    private long setAt;
    private boolean grandfathered;
    /** auth.yml exists but couldn't be read: never save over it, and the gate stays off. */
    private boolean unreadable;

    private int maxTries;
    private long timeoutMs;
    private long promptTicks;
    private boolean trustExisting;
    private long cooldownStartMs;
    private long cooldownMaxMs;

    public AuthModule(Feather plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("auth.yml");
    }

    @Override
    public String name() {
        return "Auth gate";
    }

    @Override
    public void enable() {
        reload();
        load();
        active = this;
        plugin.bind("auth", this);
        plugin.provide(Hooks.Auth.class, this::pending);
        Companion.gate(this::pending);
        plugin.once("auth.events", AuthModule::hookEvents);
        if (unreadable) {
            plugin.getLogger().error("The auth gate is OFF because data/auth.yml couldn't be read. Fix it and restart.");
        } else if (hash == null) {
            plugin.getLogger().warn("The auth gate is off: no server code is set. Set one from the console with /auth setcode <code>.");
        } else {
            activate();
        }
    }

    /** Listeners (once per JVM); each does nothing while the module isn't running. */
    private static void hookEvents() {
        FeatherEvents.PLAYER_LOGIN.register((profile, address, current) -> {
            AuthModule m = active;
            return m == null || current != null ? current : m.cooldownScreen(profile, address);
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
            AuthModule m = active;
            if (m != null) m.joined(h.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            AuthModule m = active;
            if (m != null) m.quit(h.player);
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(FeatherEvents.CHAT_GATE, (message, sender, params) -> {
            AuthModule m = active;
            if (m == null) return true;
            Session s = m.sessions.get(sender.getUUID());
            if (s == null) return true;
            m.check(sender, s, message.signedContent());
            return false;
        });
        FeatherEvents.COMMAND.register((p, cmd) -> {
            AuthModule m = active;
            if (m == null || !m.pending(p)) return true;
            m.plugin.text().send(p, "auth.blocked-command");
            return false;
        });
        // An empty command tree until the code is in (then the real one is sent).
        FeatherEvents.SEND_COMMANDS.register(p -> {
            AuthModule m = active;
            return m == null || (!m.pending(p) && !m.needsAuth(p));
        });
        FeatherEvents.COMMAND_SUGGESTIONS.register((p, cmd, current) -> {
            AuthModule m = active;
            return m != null && m.pending(p) ? Suggestions.empty().join() : current;
        });
        FeatherEvents.MOB_TARGET.register((mob, target) -> {
            AuthModule m = active;
            return m == null || !(target instanceof ServerPlayer p) || !m.pending(p);
        });
        Restraint.register(p -> {
            AuthModule m = active;
            return m != null && m.pending(p);
        });
    }

    @Override
    public void reload() {
        Yaml c = plugin.getConfig();
        maxTries = Math.max(1, c.getInt("auth.max-tries", 3));
        timeoutMs = Math.max(10, c.getLong("auth.timeout-seconds", 120)) * 1000L;
        promptTicks = Math.max(1, c.getLong("auth.prompt-interval-seconds", 5)) * 20L;
        trustExisting = c.getBoolean("auth.trust-existing-players", true);
        cooldownStartMs = Math.max(1, c.getLong("auth.cooldown-start-minutes", 1)) * 60_000L;
        cooldownMaxMs = Math.max(cooldownStartMs, c.getLong("auth.cooldown-max-minutes", 60) * 60_000L);
    }

    @Override
    public void disable() {
        // Nobody stays on unauthenticated while the gate isn't watching.
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            ServerPlayer p = plugin.player(e.getKey());
            end(p, e.getValue());
            if (p != null) p.connection.disconnect(Adv.vanilla(plugin.text().msg("auth.kick-restart", server())));
        }
        sessions.clear();
        if (active == this) active = null;
        Companion.gate(null);
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warn("Saving auth.yml timed out.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ state

    /** The gate runs: a code is set and auth.yml was readable. */
    boolean active() {
        return hash != null && !unreadable;
    }

    /** True while the player still has to type the code (other modules skip them). */
    public boolean pending(ServerPlayer player) {
        return player != null && sessions.containsKey(player.getUUID());
    }

    /** Whether this player would have to type the code right now. */
    private boolean needsAuth(ServerPlayer p) {
        return active() && !trusted.containsKey(p.getUUID()) && !Perms.isOp(p) && !Perms.has(p, BYPASS);
    }

    /** Turns the gate on: the one-time grandfathering and sessions for who's online. */
    private void activate() {
        grandfather();
        for (ServerPlayer p : plugin.onlinePlayers()) if (needsAuth(p) && !pending(p)) start(p);
        plugin.getLogger().info("Auth gate on: {} trusted player(s). New players type the server code in chat.", trusted.size());
    }

    /**
     * Once, the first time the gate comes on: trusts every player with a Feather profile, every op and everyone
     * online, so the friends already playing here are never asked (auth.trust-existing-players).
     */
    private void grandfather() {
        if (grandfathered) return;
        grandfathered = true;
        if (!trustExisting) {
            save();
            return;
        }
        int before = trusted.size();
        long now = System.currentTimeMillis();
        Path players = plugin.profiles().folder();
        if (Files.isDirectory(players)) {
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(players, "*.yml")) {
                for (Path f : dir) {
                    String n = f.getFileName().toString();
                    try {
                        UUID id = UUID.fromString(n.substring(0, n.length() - 4));
                        Yaml y = SafeYaml.read(f, plugin.getLogger(), "nothing", false);
                        trusted.putIfAbsent(id, new Trusted(y == null ? "?" : y.getString("name", "?"), now, "existing"));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            } catch (java.io.IOException e) {
                plugin.getLogger().warn("Auth: could not list player profiles: {}", e.toString());
            }
        }
        for (ServerOpListEntry op : Feather.server().getPlayerList().getOps().getEntries()) {
            NameAndId u = op.getUser();
            if (u != null) trusted.putIfAbsent(u.id(), new Trusted(u.name() == null ? "?" : u.name(), now, "existing"));
        }
        for (ServerPlayer p : plugin.onlinePlayers()) trusted.putIfAbsent(p.getUUID(), new Trusted(Names.name(p), now, p.getIpAddress()));
        plugin.getLogger().info("Auth: trusted {} existing player(s) (Feather profiles, ops and players online); they never need the code.",
                trusted.size() - before);
        save();
    }

    /** {@code <server>} in auth messages: the server name from config.yml. */
    private TagResolver server() {
        return Text.unparsed("server", plugin.getConfig().getString("server-name", "Aftr Hrs"));
    }

    private static String ip(SocketAddress address) {
        return address instanceof InetSocketAddress in && in.getAddress() != null ? in.getAddress().getHostAddress() : "?";
    }

    // ------------------------------------------------------------------ the code

    private static String normalize(String code) {
        return code.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static byte[] derive(String code, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(normalize(code).toCharArray(), salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checks a typed line against the stored hash in constant time. */
    private boolean matches(String typed) {
        String s = salt, h = hash;
        if (s == null || h == null) return false;
        byte[] got = derive(typed, Base64.getDecoder().decode(s), iterations);
        return MessageDigest.isEqual(got, Base64.getDecoder().decode(h));
    }

    private void setCode(String code, String by) {
        byte[] s = new byte[16];
        random.nextBytes(s);
        iterations = ITERATIONS;
        hash = Base64.getEncoder().encodeToString(derive(code, s, ITERATIONS));
        salt = Base64.getEncoder().encodeToString(s);
        setBy = by;
        setAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ sessions

    /** A player on a rejoin cooldown is refused at login. */
    private net.minecraft.network.chat.Component cooldownScreen(NameAndId profile, SocketAddress address) {
        if (!active()) return null;
        UUID id = profile.id();
        if (trusted.containsKey(id)) return null;
        if (Feather.server().getPlayerList().isOp(profile)) return null;
        long now = System.currentTimeMillis();
        long until = 0;
        Strike a = strikes.get("uuid:" + id);
        if (a != null) until = Math.max(until, a.until);
        Strike b = strikes.get("ip:" + ip(address));
        if (b != null) until = Math.max(until, b.until);
        if (until <= now) return null;
        return Adv.vanilla(plugin.text().msg("auth.kick-cooldown", server(), Text.unparsed("wait", Placeholders.duration(until - now + 999))));
    }

    private void joined(ServerPlayer p) {
        if (needsAuth(p)) {
            start(p);
            return;
        }
        // A leftover from a session that ended while the gate was off: our blindness never times out.
        MobEffectInstance blind = p.getEffect(MobEffects.BLINDNESS);
        if (blind != null && blind.isInfiniteDuration() && !blind.isVisible() && !blind.showIcon()) p.removeEffect(MobEffects.BLINDNESS);
    }

    private void start(ServerPlayer p) {
        Session s = new Session(p.getIpAddress());
        sessions.put(p.getUUID(), s);
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
        p.closeContainer();
        updateCommands(p);
        s.prompt = Tasks.timer(10L, promptTicks, () -> prompt(p, s));
        s.timeout = Tasks.later(Math.max(1, timeoutMs / 50), () -> {
            if (sessions.get(p.getUUID()) == s && !s.done) fail(p, s, true);
        });
        plugin.getLogger().info("Auth: {} ({}) has to enter the server code.", Names.name(p), s.ip);
    }

    private static void updateCommands(ServerPlayer p) {
        MinecraftServer server = Feather.serverOrNull();
        if (server != null && !p.hasDisconnected()) server.getCommands().sendCommands(p);
    }

    private void prompt(ServerPlayer p, Session s) {
        if (p.hasDisconnected() || s.done) return;
        Text text = plugin.text();
        long left = Math.max(0, timeoutMs - (System.currentTimeMillis() - s.started));
        int tries = maxTries - s.tries;
        Adv.of(p).showTitle(Title.title(text.msg("auth.title", server()), text.msg("auth.subtitle", server()),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(promptTicks * 50 + 1500), Duration.ofMillis(300))));
        text.send(p, "auth.prompt", server(), Text.unparsed("tries", tries), Text.unparsed("time", Placeholders.duration(left)));
    }

    /** Stops a session's tasks and takes the blindness off; the player is free (or about to be kicked). */
    private void end(ServerPlayer p, Session s) {
        s.done = true;
        if (s.prompt != null) s.prompt.cancel();
        if (s.timeout != null) s.timeout.cancel();
        if (p == null) return;
        sessions.remove(p.getUUID(), s);
        MobEffectInstance blind = p.getEffect(MobEffects.BLINDNESS);
        if (blind != null && blind.isInfiniteDuration()) p.removeEffect(MobEffects.BLINDNESS);
        if (!p.hasDisconnected()) p.connection.send(new ClientboundClearTitlesPacket(true));
    }

    /** Lets a player in without the code (gate turned off, or they were trusted by staff). */
    private void release(ServerPlayer p, Session s) {
        end(p, s);
        if (p != null) {
            updateCommands(p);
            // What waited for them (calendar, quests, mail...): now that they're in.
            JoinDigest.run(p);
        }
    }

    private void succeed(ServerPlayer p, Session s) {
        end(p, s);
        UUID id = p.getUUID();
        trusted.put(id, new Trusted(Names.name(p), System.currentTimeMillis(), s.ip));
        strikes.remove("uuid:" + id);
        strikes.remove("ip:" + s.ip);
        save();
        updateCommands(p);
        Text text = plugin.text();
        Adv.of(p).showTitle(Title.title(text.msg("auth.welcome-title", server()), text.msg("auth.welcome-subtitle", server()),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(800))));
        text.send(p, "auth.welcome", server(), Text.component("name", Names.display(p)));
        text.sound(p, "accepted");
        plugin.getLogger().info("Auth: {} entered the server code and is now trusted.", Names.name(p));
        // What waited for them (calendar, quests, mail...): after the welcome.
        JoinDigest.run(p);
    }

    /** Ends a session that ran out of tries or time, adds a strike when they guessed, and disconnects them. */
    private void fail(ServerPlayer p, Session s, boolean timedOut) {
        end(p, s);
        long wait = s.tries > 0 ? strike(p.getUUID(), s.ip) : 0;
        Text text = plugin.text();
        Component msg = timedOut
                ? text.msg("auth.kick-timeout", server())
                : text.msg("auth.kick-tries", server(), Text.unparsed("wait", Placeholders.duration(wait)));
        plugin.getLogger().info("Auth: {} ({}) was disconnected: {}{}.", Names.name(p), s.ip,
                timedOut ? "no code in time" : "wrong code " + s.tries + "x", wait > 0 ? ", rejoin cooldown " + Placeholders.duration(wait) : "");
        p.connection.disconnect(Adv.vanilla(msg));
    }

    /** Records a failed session for the account and its address; returns the new cooldown (ms). */
    private long strike(UUID id, String ip) {
        long now = System.currentTimeMillis();
        long wait = 0;
        for (String key : List.of("uuid:" + id, "ip:" + ip)) {
            Strike st = strikes.computeIfAbsent(key, k -> new Strike());
            if (now - st.last > STRIKE_MEMORY_MS) st.count = 0;
            st.count++;
            st.last = now;
            long w = cooldownStartMs;
            for (int i = 1; i < st.count && w < cooldownMaxMs; i++) w *= 2;
            w = Math.min(w, cooldownMaxMs);
            st.until = now + w;
            if (key.startsWith("uuid:")) st.ip = ip;
            wait = Math.max(wait, w);
        }
        save();
        return wait;
    }

    private void quit(ServerPlayer p) {
        Session s = sessions.get(p.getUUID());
        if (s == null) return;
        // Leaving after a wrong guess counts as a failed session (no free tries by rejoining).
        boolean guessed = s.tries > 0 && !s.done;
        end(p, s);
        if (guessed) strike(p.getUUID(), s.ip);
    }

    // ------------------------------------------------------------------ chat

    /** The code goes in chat: checked off the server thread (the hash is slow on purpose), answered on it. */
    private void check(ServerPlayer p, Session s, String typed) {
        synchronized (s) {
            if (s.done || s.reserved >= maxTries) return;
            s.reserved++;
        }
        Tasks.async(() -> {
            boolean ok = !typed.isBlank() && matches(typed);
            Tasks.sync(() -> answer(p, s, ok));
        });
    }

    private void answer(ServerPlayer p, Session s, boolean ok) {
        if (p.hasDisconnected() || s.done || sessions.get(p.getUUID()) != s) return;
        if (ok) {
            succeed(p, s);
            return;
        }
        s.tries++;
        int left = maxTries - s.tries;
        if (left <= 0) {
            fail(p, s, false);
            return;
        }
        plugin.text().send(p, "auth.wrong", Text.unparsed("tries", left));
        plugin.text().sound(p, "error");
    }

    // ------------------------------------------------------------------ /auth

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        if (!sender.hasPermission(ADMIN)) {
            text.send(sender, "general.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setcode" -> {
                if (unreadable) {
                    text.send(sender, "auth.unreadable");
                    return true;
                }
                String code = args.length < 2 ? "" : normalize(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                if (code.length() < MIN_LENGTH) {
                    text.send(sender, "auth.code-too-short", Text.unparsed("min", MIN_LENGTH));
                    return true;
                }
                boolean wasActive = active();
                setCode(code, sender.getName());
                save();
                plugin.getLogger().info("Auth: the server code was {} by {} (stored as a salted PBKDF2 hash only).",
                        wasActive ? "changed" : "set", sender.getName());
                text.send(sender, wasActive ? "auth.code-changed" : "auth.code-set");
                if (sender.isPlayer()) text.send(sender, "auth.code-set-ingame");
                if (!wasActive) activate();
            }
            case "clearcode" -> {
                if (unreadable) {
                    text.send(sender, "auth.unreadable");
                    return true;
                }
                boolean was = active();
                hash = null;
                salt = null;
                setBy = null;
                setAt = 0;
                save();
                for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
                    ServerPlayer p = plugin.player(e.getKey());
                    release(p, e.getValue());
                    if (p != null) text.send(p, "auth.gate-off");
                }
                sessions.clear();
                plugin.getLogger().info("Auth: the server code was cleared by {}; the gate is off.", sender.getName());
                text.send(sender, was ? "auth.code-cleared" : "auth.no-code");
            }
            case "reset" -> {
                if (args.length < 2) {
                    text.send(sender, "auth.usage");
                    return true;
                }
                UUID id = resolve(args[1]);
                if (id == null) {
                    text.send(sender, "auth.reset-unknown", Text.unparsed("input", args[1]));
                    return true;
                }
                Trusted t = trusted.remove(id);
                Strike st = strikes.remove("uuid:" + id);
                if (st != null && st.ip != null) strikes.remove("ip:" + st.ip);
                if (t != null && t.ip() != null) strikes.remove("ip:" + t.ip());
                save();
                ServerPlayer online = plugin.player(id);
                String name = online != null ? Names.name(online) : t != null ? t.name() : args[1];
                text.send(sender, "auth.reset", Text.unparsed("player", name));
                if (online != null && needsAuth(online) && !pending(online)) start(online);
            }
            case "list" -> {
                if (trusted.isEmpty()) {
                    text.send(sender, "auth.list-empty");
                    return true;
                }
                text.send(sender, "auth.list-header", Text.unparsed("count", trusted.size()));
                List<Trusted> all = new ArrayList<>(trusted.values());
                all.sort(Comparator.comparingLong(Trusted::date).thenComparing(Trusted::name, String.CASE_INSENSITIVE_ORDER));
                for (Trusted t : all) {
                    text.send(sender, "auth.list-entry", Text.unparsed("player", t.name()),
                            Text.unparsed("date", java.time.LocalDate.ofInstant(Instant.ofEpochMilli(t.date()), java.time.ZoneId.systemDefault())),
                            Text.unparsed("how", t.ip() == null ? "" : t.ip().equals("existing") ? "existing player" : "code"));
                }
            }
            case "status" -> {
                long now = System.currentTimeMillis();
                long cooling = strikes.values().stream().filter(s -> s.until > now).count();
                List<String> waiting = new ArrayList<>();
                for (UUID id : sessions.keySet()) {
                    ServerPlayer p = plugin.player(id);
                    if (p != null) waiting.add(Names.name(p));
                }
                text.send(sender, unreadable ? "auth.status-broken" : active() ? "auth.status-on" : "auth.status-off",
                        Text.unparsed("by", setBy == null ? "?" : setBy),
                        Text.unparsed("ago", Placeholders.duration(now - setAt)),
                        Text.unparsed("trusted", trusted.size()),
                        Text.unparsed("waiting", waiting.isEmpty() ? "-" : String.join(", ", waiting)),
                        Text.unparsed("cooldowns", cooling),
                        Text.unparsed("tries", maxTries),
                        Text.unparsed("timeout", Placeholders.duration(timeoutMs)));
            }
            default -> text.send(sender, "auth.usage");
        }
        return true;
    }

    /** A UUID for a name: online player, trusted name, a UUID, or a known player. Exact names only. */
    private UUID resolve(String input) {
        for (ServerPlayer p : plugin.onlinePlayers()) if (Names.name(p).equalsIgnoreCase(input)) return p.getUUID();
        // Exact names only (no nicknames or prefixes): a security action must never land on the wrong player.
        for (Map.Entry<UUID, Trusted> e : trusted.entrySet()) if (e.getValue().name().equalsIgnoreCase(input)) return e.getKey();
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
        }
        OfflinePlayer o = plugin.lookup().byName(input);
        return o == null ? null : o.id();
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("status", "list", "reset", "setcode", "clearcode")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            for (Trusted t : trusted.values()) if (t.name().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(t.name());
            for (String s : plugin.completePlayers(sender, pre)) if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ disk

    private void load() {
        trusted.clear();
        strikes.clear();
        Yaml yml = SafeYaml.read(file, plugin.getLogger(), "auth changes");
        if (yml == null) {
            unreadable = true;
            return;
        }
        unreadable = false;
        Yaml code = yml.getConfigurationSection("code");
        if (code != null && code.getString("hash") != null && code.getString("salt") != null) {
            hash = code.getString("hash");
            salt = code.getString("salt");
            iterations = Math.max(1, code.getInt("iterations", ITERATIONS));
            setBy = code.getString("set-by");
            setAt = code.getLong("set-at", 0L);
        }
        grandfathered = yml.getBoolean("grandfathered", false);
        Yaml t = yml.getConfigurationSection("trusted");
        if (t != null) {
            for (String key : t.getKeys(false)) {
                try {
                    Yaml e = t.getConfigurationSection(key);
                    if (e == null) continue;
                    trusted.put(UUID.fromString(key), new Trusted(e.getString("name", "?"), parseDate(e.get("date")), e.getString("ip")));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warn("auth.yml: skipping a broken trusted entry '{}'.", key);
                }
            }
        }
        for (Map<?, ?> m : yml.getMapList("cooldowns")) {
            try {
                Strike st = new Strike();
                st.count = ((Number) m.get("strikes")).intValue();
                st.until = ((Number) m.get("until")).longValue();
                st.last = ((Number) m.get("last")).longValue();
                Object ip = m.get("ip");
                st.ip = ip == null ? null : String.valueOf(ip);
                strikes.put(String.valueOf(m.get("key")), st);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static long parseDate(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof java.util.Date d) return d.getTime();
        try {
            return o == null ? 0L : Instant.parse(String.valueOf(o)).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** Snapshots everything and writes auth.yml in the background (temp file + atomic move, keeps a .bak). */
    private synchronized void save() {
        if (unreadable) return;
        Yaml yml = new Yaml();
        if (hash != null) {
            yml.set("code.algorithm", ALGORITHM);
            yml.set("code.iterations", iterations);
            yml.set("code.salt", salt);
            yml.set("code.hash", hash);
            yml.set("code.set-by", setBy);
            yml.set("code.set-at", setAt);
        }
        yml.set("grandfathered", grandfathered);
        for (Map.Entry<UUID, Trusted> e : trusted.entrySet()) {
            String base = "trusted." + e.getKey() + ".";
            yml.set(base + "name", e.getValue().name());
            yml.set(base + "date", Instant.ofEpochMilli(e.getValue().date()).toString());
            yml.set(base + "ip", e.getValue().ip());
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> cd = new ArrayList<>();
        for (Map.Entry<String, Strike> e : strikes.entrySet()) {
            Strike st = e.getValue();
            if (now - st.last > STRIKE_MEMORY_MS && st.until < now) continue; // forgotten
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("strikes", st.count);
            m.put("until", st.until);
            m.put("last", st.last);
            if (st.ip != null) m.put("ip", st.ip);
            cd.add(m);
        }
        yml.set("cooldowns", cd);
        // A detached copy taken here (server thread); turned into text on the auth writer.
        Object snap = yml.snapshot();
        Runnable write = () -> SafeYaml.write(file, AUTH_HEADER + Yaml.dump(snap), plugin.getLogger());
        if (io.isShutdown()) write.run();
        else io.execute(write);
    }

    private static final String AUTH_HEADER = "# Feather auth gate. The server code is stored only as a salted PBKDF2 hash; set it with /auth setcode.\n"
            + "# trusted: players who entered the code (or were already here when the gate came on). Never need it again.\n";
}
