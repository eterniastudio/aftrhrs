package net.axther.aftrhrs.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Tasks;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The server half of the Companion protocol (docs/companion-protocol.md), ported from Feather's
 * {@code CompanionModule}: the {@code aftrhrs:api} channel, the per-player session and the {@code hello} /
 * {@code welcome} feature negotiation. The client half is {@code net.axther.aftrhrs.client.net.Link}.
 *
 * <p>The mod is on both sides now, so every client offers every feature, but the handshake still runs: a player is
 * "modded" only after a valid hello, and the server agrees only to the features {@code companion.features.*} in
 * config.yml leaves on (and only while {@code modules.companion} is on). Features check {@link #has} and keep their
 * vanilla fallback otherwise.
 *
 * <p>Use (all static, server thread):
 * <pre>{@code
 * Companion.on("pets_request", "pets", (player, msg) -> ...);     // a client message, only from players with "pets"
 * if (Companion.has(player, "pets")) Companion.send(player, "pets", body);   // push data (body gets "t":"pets")
 * Companion.onWelcome((player, hello) -> ...);                   // right after a player's welcome (initial pushes)
 * Companion.onQuit(player -> ...);                               // a modded player left (end their sessions)
 * }</pre>
 * Handlers must validate every field they read ({@link Wire#str}, {@link Wire#num}, {@link Wire#bool}) and re-check
 * permissions and state: client messages are requests.
 */
public final class Companion {
    public static final int PROTO = 1;
    /** What the server can do with the client, in negotiation order. Module ports may add more with {@link #addFeature}. */
    private static final Map<String, String> SUPPORTED = new LinkedHashMap<>();
    private static final int MAX_PER_SECOND = 40;
    private static final int WAITING_MAX_BYTES = 4 * 1024 * 1024;

    static {
        for (String f : List.of("menus", "hub", "recipes", "storage", "craftfill", "creative", "claims", "pets", "places", "quests", "tpa",
                "hud", "trade", "chatgames", "wallet", "fishing", "inspect", "chess")) {
            SUPPORTED.put(f, f.equals("hub") ? "menus" : f.equals("craftfill") ? "recipes" : f);
        }
        // "creative" (the old server-item tabs) is still agreed to when asked, so older clients link; nothing uses it.
    }

    /** A linked client: its mod version and the features both sides agreed on. */
    public record Session(String mod, Set<String> features) {
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, BiConsumer<ServerPlayer, JsonObject>> HANDLERS = new ConcurrentHashMap<>();
    /** The feature a message type belongs to (only clients that agreed on it may send it); absent = any linked client. */
    private static final Map<String, String> HANDLER_FEATURES = new ConcurrentHashMap<>();
    private static final List<BiConsumer<ServerPlayer, JsonObject>> WELCOME_HOOKS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<ServerPlayer>> QUIT_HOOKS = new CopyOnWriteArrayList<>();
    private static final Map<UUID, int[]> RATE = new HashMap<>();
    /** Messages for clients whose channel registration hasn't arrived yet (capped by bytes). */
    private static final Map<UUID, List<byte[]>> WAITING = new HashMap<>();
    /** When each player last said hello: one handshake per 10 s, so a client can't make us resend big lists. */
    private static final Map<UUID, Long> LAST_HELLO = new HashMap<>();
    private static final AtomicInteger PART_IDS = new AtomicInteger();
    private static long rateSecond;
    private static boolean initialized;

    // Set by the module (config.yml); while the module is off the server never answers a hello.
    private static volatile boolean running;
    private static volatile Set<String> enabled = Set.of();
    private static boolean logJoins = true;
    private static boolean debug;
    /** Players the auth gate holds: nothing but the handshake is handled for them. Set by the auth port. */
    private static volatile Predicate<ServerPlayer> gate = p -> false;

    private Companion() {
    }

    /**
     * Called once from {@link AftrHrs#onInitialize} (both sides): registers {@code aftrhrs:api} for both directions,
     * the server receiver and connection hooks, and the core handlers. The static API works from here on, so modules
     * can call {@link #on} in their enable(); the {@code companion} Feather module itself ({@link #module()}, config
     * toggle {@code modules.companion}) is registered by {@code Modules.register} in Feather's start order.
     */
    public static void init(Feather feather) {
        if (initialized) return;
        initialized = true;
        PayloadTypeRegistry.clientboundPlay().registerLarge(ApiPayload.TYPE, ApiPayload.CODEC, Wire.MAX_OUT);
        PayloadTypeRegistry.serverboundPlay().registerLarge(ApiPayload.TYPE, ApiPayload.CODEC, Wire.MAX_IN);
        // Fabric runs this on the server thread.
        ServerPlayNetworking.registerGlobalReceiver(ApiPayload.TYPE, (payload, ctx) -> receive(ctx.player(), payload.bytes()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> quit(handler.player));
        ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(ApiPayload.TYPE.id())) server.execute(() -> flush(handler.player));
        });
        on("hello", Companion::hello);
        RecipeExport.init();
        Inspect.init();
        MenuBridge.init();
        // The mod's hub keybind and the way back from its HUD settings screen (Feather's openHubFromClient /
        // openSettingsFromClient: same checks as Shift+F).
        on("open_hub", (p, m) -> Feather.get().hub().openHubFromClient(p));
        on("open_settings", (p, m) -> Feather.get().hub().openSettingsFromClient(p));
        // The module itself is registered by Modules.register, in Feather's start order (after auth).
    }

    /**
     * The {@code companion} Feather module (config toggle {@code modules.companion}), for {@code Modules.register}: it
     * reads {@code companion.*} and switches answering hellos on and off.
     */
    public static java.util.function.Supplier<FeatherModule> module() {
        return Module::new;
    }

    // ------------------------------------------------------------------ API for module ports

    /** Whether the player's client linked with this feature agreed. */
    public static boolean has(ServerPlayer player, String feature) {
        Session s = player == null ? null : SESSIONS.get(player.getUUID());
        return s != null && s.features().contains(feature);
    }

    /** Whether the player's client completed the handshake. */
    public static boolean modded(ServerPlayer player) {
        return player != null && SESSIONS.containsKey(player.getUUID());
    }

    /** The player's session, or null before a valid hello. */
    public static Session session(ServerPlayer player) {
        return player == null ? null : SESSIONS.get(player.getUUID());
    }

    /** Handles a client message type (server thread) from any linked client. Handlers must validate everything they read. */
    public static void on(String type, BiConsumer<ServerPlayer, JsonObject> handler) {
        HANDLERS.put(type, handler);
        HANDLER_FEATURES.remove(type);
    }

    /** {@link #on(String, BiConsumer)} for a message only clients with this feature agreed may send. */
    public static void on(String type, String feature, BiConsumer<ServerPlayer, JsonObject> handler) {
        HANDLERS.put(type, handler);
        HANDLER_FEATURES.put(type, feature);
    }

    /**
     * Runs right after a player's {@code welcome} went out, with their hello (it carries the client's cached hashes,
     * e.g. {@code recipes} and {@code items}): send the initial data for agreed features here.
     */
    public static void onWelcome(BiConsumer<ServerPlayer, JsonObject> hook) {
        WELCOME_HOOKS.add(hook);
    }

    /** Runs when a linked player leaves (their sessions should end). */
    public static void onQuit(Consumer<ServerPlayer> hook) {
        QUIT_HOOKS.add(hook);
    }

    /**
     * Adds a feature the client may ask for (beyond Feather's list), switched by {@code companion.features.<configKey>}.
     * The client must offer it too ({@code Link.FEATURES}).
     */
    public static void addFeature(String feature, String configKey) {
        if (SUPPORTED.putIfAbsent(feature, configKey) != null) return;
        // Modules that add one start after the companion module read its config: offer it from the next hello on.
        if (running) readConfig();
    }

    /** Lets the auth gate hold players: while it answers true only the handshake is handled for them. */
    public static void gate(Predicate<ServerPlayer> held) {
        gate = held == null ? p -> false : held;
    }

    public static JsonObject message(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    /** Sends a message ({@code "t"} set) to a linked player, split into parts when big. Does nothing for others. */
    public static void send(ServerPlayer player, JsonObject message) {
        if (!modded(player)) return;
        sendPayloads(player, message.get("t").getAsString(), Wire.encode(message, PART_IDS.incrementAndGet()));
    }

    /**
     * Sends a message the caller already turned into JSON text (it must carry its {@code "t"}): for pushes that
     * compare the text with the last one sent, so it is made only once. {@code type} is only for the debug log.
     */
    public static void sendJson(ServerPlayer player, String type, String json) {
        if (!modded(player)) return;
        sendPayloads(player, type, Wire.encode(json, PART_IDS.incrementAndGet()));
    }

    /** Sends {@code body} as a message of this type (sets its {@code "t"}). */
    public static void send(ServerPlayer player, String type, JsonObject body) {
        body.addProperty("t", type);
        send(player, body);
    }

    /** Encodes a big message once (any thread), to send later or to several players with {@link #sendEncoded}. */
    public static List<byte[]> encode(JsonObject message) {
        return Wire.encode(message, PART_IDS.incrementAndGet());
    }

    /** Encodes a big message written as a stream ({@link Wire#encodeStreaming}: no JSON string is made); any thread. */
    static List<byte[]> encodeStreaming(Wire.StreamBody body) throws java.io.IOException {
        return Wire.encodeStreaming(PART_IDS.incrementAndGet(), body);
    }

    /** Sends a message made by {@link #encode}; {@code type} is only for the debug log. */
    public static void sendEncoded(ServerPlayer player, String type, List<byte[]> payloads) {
        sendPayloads(player, type, payloads);
    }

    /** Online players linked with this feature. */
    public static List<ServerPlayer> moddedWith(String feature) {
        List<ServerPlayer> list = new ArrayList<>();
        for (ServerPlayer p : Feather.get().onlinePlayers()) if (has(p, feature)) list.add(p);
        return list;
    }

    /** Whether the companion module runs (hellos are answered). */
    public static boolean running() {
        return running;
    }

    public static boolean debug() {
        return debug;
    }

    // ------------------------------------------------------------------ sending

    private static void sendPayloads(ServerPlayer player, String type, List<byte[]> payloads) {
        if (!modded(player) || player.hasDisconnected()) return;
        if (debug) AftrHrs.LOG.info("[companion] -> {} {} ({} payload(s))", player.getScoreboardName(), type, payloads.size());
        // The hello can arrive before the client's channel list: wait for it, keeping order behind the welcome.
        if (!ServerPlayNetworking.canSend(player, ApiPayload.TYPE) || WAITING.containsKey(player.getUUID())) {
            List<byte[]> q = WAITING.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
            long bytes = 0;
            for (byte[] b : q) bytes += b.length;
            for (byte[] b : payloads) bytes += b.length;
            if (bytes <= WAITING_MAX_BYTES) q.addAll(payloads);
            else AftrHrs.LOG.warn("Companion: dropped a '{}' message for {} (waiting for their channel, over {} MiB queued)", type,
                    player.getScoreboardName(), WAITING_MAX_BYTES / (1024 * 1024));
            return;
        }
        for (byte[] payload : payloads) ServerPlayNetworking.send(player, new ApiPayload(payload));
    }

    /** Sends what waited for the player's channel. {@code force}: send even without a channel list (a hello proves it). */
    private static void flush(ServerPlayer player, boolean force) {
        if (player == null || player.hasDisconnected()) return;
        if (!force && !ServerPlayNetworking.canSend(player, ApiPayload.TYPE)) return;
        List<byte[]> q = WAITING.remove(player.getUUID());
        if (q == null) return;
        if (logJoins) AftrHrs.LOG.info("{}'s Companion channel is open; sending {} waiting message(s)", player.getScoreboardName(), q.size());
        for (byte[] payload : q) ServerPlayNetworking.send(player, new ApiPayload(payload));
    }

    private static void flush(ServerPlayer player) {
        flush(player, false);
    }

    // ------------------------------------------------------------------ receiving

    private static void receive(ServerPlayer player, byte[] payload) {
        if (player == null || player.hasDisconnected() || throttled(player)) return;
        JsonObject msg = Wire.decode(payload);
        if (msg == null) return;
        String type = msg.get("t").getAsString();
        if (debug) AftrHrs.LOG.info("[companion] <- {} {}", player.getScoreboardName(), type);
        if (!running) return;
        // Nothing but the handshake before a valid hello, and nothing at all while the auth gate holds the player.
        if (!type.equals("hello") && (!modded(player) || gate.test(player))) return;
        BiConsumer<ServerPlayer, JsonObject> h = HANDLERS.get(type);
        if (h == null) return;
        String feature = HANDLER_FEATURES.get(type);
        if (feature != null && !has(player, feature)) return;
        try {
            h.accept(player, msg);
        } catch (RuntimeException e) {
            AftrHrs.LOG.warn("Companion message '{}' from {} failed", type, player.getScoreboardName(), e);
        }
    }

    private static boolean throttled(ServerPlayer player) {
        long second = System.currentTimeMillis() / 1000;
        if (second != rateSecond) {
            rateSecond = second;
            RATE.clear();
        }
        int[] n = RATE.computeIfAbsent(player.getUUID(), k -> new int[1]);
        return ++n[0] > MAX_PER_SECOND;
    }

    private static void hello(ServerPlayer player, JsonObject msg) {
        if (Wire.num(msg, "proto", -1) != PROTO) return;
        long now = System.currentTimeMillis();
        Long last = LAST_HELLO.get(player.getUUID());
        if (last != null && now - last < 10_000) return;
        LAST_HELLO.put(player.getUUID(), now);
        Set<String> wanted = new LinkedHashSet<>();
        JsonElement f = msg.get("features");
        if (f != null && f.isJsonArray()) {
            for (JsonElement e : f.getAsJsonArray()) {
                if (e.isJsonPrimitive() && wanted.size() < 64) wanted.add(e.getAsString());
            }
        }
        Set<String> agreed = new LinkedHashSet<>();
        for (String s : enabled) if (wanted.contains(s)) agreed.add(s);
        // Only a version-like string reaches the log (no line breaks or look-alike console lines).
        String mod = Wire.str(msg, "mod", "").replaceAll("[^A-Za-z0-9._+-]", "");
        if (mod.length() > 32) mod = mod.substring(0, 32);
        if (mod.isEmpty()) mod = "?";
        SESSIONS.put(player.getUUID(), new Session(mod, Collections.unmodifiableSet(agreed)));
        if (logJoins) AftrHrs.LOG.info("{} linked Aftr Hrs {} ({})", player.getScoreboardName(), mod, String.join(", ", agreed));
        // Client and server are the same jar; a different version links anyway (the protocol is tolerant) but is worth a note.
        if (!mod.equals(version()) && !"dev".equals(version())) {
            AftrHrs.LOG.warn("{} plays with Aftr Hrs {} on a {} server; some screens may differ", player.getScoreboardName(), mod, version());
        }

        // A valid hello proves the client listens; don't wait forever for a channel list that may never come.
        if (!ServerPlayNetworking.canSend(player, ApiPayload.TYPE)) Tasks.later(20, () -> flush(player, true));

        JsonObject welcome = message("welcome");
        welcome.addProperty("proto", PROTO);
        welcome.addProperty("server", "Aftr Hrs");
        welcome.addProperty("feather", version());
        JsonArray list = new JsonArray();
        agreed.forEach(list::add);
        welcome.add("features", list);
        send(player, welcome);
        for (BiConsumer<ServerPlayer, JsonObject> hook : WELCOME_HOOKS) {
            try {
                hook.accept(player, msg);
            } catch (RuntimeException e) {
                AftrHrs.LOG.warn("Companion welcome hook failed", e);
            }
        }
    }

    private static void quit(ServerPlayer player) {
        if (player == null) return;
        UUID id = player.getUUID();
        WAITING.remove(id);
        LAST_HELLO.remove(id);
        if (SESSIONS.remove(id) == null) return;
        for (Consumer<ServerPlayer> hook : QUIT_HOOKS) {
            try {
                hook.accept(player);
            } catch (RuntimeException e) {
                AftrHrs.LOG.warn("Companion quit hook failed", e);
            }
        }
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer(AftrHrs.ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    private static void readConfig() {
        var config = Feather.get().getConfig();
        Set<String> on = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : SUPPORTED.entrySet()) {
            if (config.getBoolean("companion.features." + e.getValue(), true)) on.add(e.getKey());
        }
        enabled = Collections.unmodifiableSet(on);
        logJoins = config.getBoolean("companion.log-joins", true);
        debug = config.getBoolean("companion.debug", false);
    }

    /** The {@code companion} Feather module: config, and the on/off switch for answering hellos. */
    static final class Module implements FeatherModule {
        @Override
        public String name() {
            return "companion";
        }

        @Override
        public void enable() {
            readConfig();
            running = true;
            MenuBridge.start(Feather.get());
            Feather.get().provide(net.axther.aftrhrs.core.Hooks.RecipesChanged.class, RecipeExport::recipesChanged);
            // Settings page: the mod's own HUD & display screen, for players whose client agreed on "hud" (as on Paper).
            Feather feather = Feather.get();
            feather.hub().addSettingsButton("hud", net.axther.aftrhrs.core.Hub.SETTINGS_HUD, p -> has(p, "hud"), (menu, player, width, back) ->
                    menu.button(feather.text().msg("menu.settings.hud"), feather.text().msg("menu.settings.hud-tooltip"), width, p -> {
                        feather.text().sound(p, "hub-toggle");
                        // "back": what the mod sends when its screen closes, to land on the Settings page again.
                        JsonObject m = message("hud_settings");
                        m.addProperty("back", "open_settings");
                        send(p, m);
                    }));
        }

        @Override
        public void reload() {
            readConfig();
        }

        @Override
        public void disable() {
            running = false;
            MenuBridge.stop(Feather.get());
            Feather.get().withdraw(net.axther.aftrhrs.core.Hooks.RecipesChanged.class);
            SESSIONS.clear();
            WAITING.clear();
            LAST_HELLO.clear();
            RATE.clear();
        }
    }
}
