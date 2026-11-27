package net.axther.aftrhrs.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.net.ApiPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The connection to Feather: handshake, part reassembly and message dispatch. Payloads are unzipped, reassembled and
 * parsed on one background thread, in the order they came, and handled on the client thread. Until a {@code welcome}
 * arrives the mod stays dormant and every screen is the vanilla one.
 */
public final class Link {
	public static final int PROTO = 1;
	/**
	 * What this mod can do; the server answers with the ones it agrees to. ("creative", the old server-item tabs, is no
	 * longer offered: every item is registered content with its own tabs. Servers still accept it.)
	 */
	private static final String[] FEATURES = {"menus", "hub", "recipes", "storage", "craftfill", "claims", "pets", "places", "quests", "tpa", "hud", "trade", "chatgames", "wallet",
		"fishing", "inspect", "chess", "storage_lean", "pets_hp", "quest_goal"};

	private static final Map<String, Consumer<JsonObject>> HANDLERS = new HashMap<>();
	private static final Map<Integer, Parts> PARTS = new HashMap<>();
	/** Decodes every payload, one at a time, so messages are handled in the order the server sent them. */
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Aftr Hrs Companion decode");
		t.setDaemon(true);
		return t;
	});
	/** Bumped by {@link #reset} (a new connection): anything decoded for an older one is dropped. */
	private static volatile int generation;
	private static volatile Set<String> features = Set.of();
	private static String serverFeather = "";
	private static long helloAt;
	private static int helloTries;

	private record Parts(byte[][] pieces, long started) {
	}

	private Link() {
	}

	public static void init() {
		on("welcome", Link::welcome);
	}

	/** The loader saw the player join a server. */
	public static void onJoin() {
		reset();
		hello();
		helloAt = System.currentTimeMillis();
		helloTries = 1;
	}

	/** The server just listed {@code aftrhrs:api} as a channel it listens on: say hello now instead of on the retry. */
	public static void channelOpened() {
		if (helloTries == 0 || connected()) return;
		hello();
		helloAt = System.currentTimeMillis();
	}

	/** The loader saw the player leave. */
	public static void onLeave() {
		Minecraft.getInstance().execute(Link::reset);
	}

	/** No welcome yet: say hello again a couple of times (servers without Feather never answer). */
	public static void tick(Minecraft mc) {
		if (helloTries == 0 || connected() || mc.getConnection() == null) return;
		if (System.currentTimeMillis() - helloAt < 3000) return;
		if (helloTries >= 3) {
			helloTries = 0;
			return;
		}
		helloTries++;
		helloAt = System.currentTimeMillis();
		hello();
	}

	private static void reset() {
		features = Set.of();
		serverFeather = "";
		synchronized (PARTS) {
			generation++;
			PARTS.clear();
		}
		Companion.onDisconnect();
	}

	/** Handles a server message type on the client thread. */
	public static void on(String type, Consumer<JsonObject> handler) {
		HANDLERS.put(type, handler);
	}

	/** Whether Feather agreed to this feature for the current connection. */
	public static boolean has(String feature) {
		return features.contains(feature);
	}

	public static boolean connected() {
		return !features.isEmpty();
	}

	public static String serverVersion() {
		return serverFeather;
	}

	private static void hello() {
		// Before the server lists the channel (the hello at join, usually) there is nobody to tell: skip the work.
		if (!ClientPlayNetworking.canSend(ApiPayload.TYPE)) return;
		JsonObject o = message("hello");
		o.addProperty("proto", PROTO);
		o.addProperty("mod", Companion.version());
		o.addProperty("mc", "26.3");
		JsonArray f = new JsonArray();
		for (String s : FEATURES) f.add(s);
		o.add("features", f);
		o.addProperty("recipes", Companion.recipes().cachedHash());
		sendRaw(o);
	}

	private static void welcome(JsonObject o) {
		if (num(o, "proto", PROTO) != PROTO) return;
		Set<String> agreed = new LinkedHashSet<>();
		if (o.has("features") && o.get("features").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("features")) if (e.isJsonPrimitive()) agreed.add(e.getAsString());
		}
		features = Set.copyOf(agreed);
		serverFeather = str(o, "feather");
		Companion.LOG.info("Linked to Aftr Hrs {} ({})", serverFeather, String.join(", ", agreed));
	}

	public static JsonObject message(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("t", type);
		return o;
	}

	/** Sends a message once linked (dropped otherwise). */
	public static void send(JsonObject message) {
		if (connected()) sendRaw(message);
	}

	private static void sendRaw(JsonObject message) {
		if (!ClientPlayNetworking.canSend(ApiPayload.TYPE)) return;
		byte[] json = message.toString().getBytes(StandardCharsets.UTF_8);
		byte[] payload;
		if (json.length > 2048) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(1);
			try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
				gz.write(json);
			} catch (IOException e) {
				return;
			}
			payload = out.toByteArray();
		} else {
			payload = new byte[json.length + 1];
			System.arraycopy(json, 0, payload, 1, json.length);
		}
		ClientPlayNetworking.send(new ApiPayload(payload));
	}

	// ------------------------------------------------------------------ receiving

	/**
	 * A payload from the server (client thread; decodes off-thread). Every payload, small ones too, goes through the
	 * one decoder thread, so handlers see the server's order (a {@code storage} before its {@code storage_delta}).
	 */
	public static void receive(byte[] payload) {
		final int gen = generation;
		DECODER.execute(() -> decodeAndQueue(payload, gen));
	}

	/** Decoder thread: unzip, reassemble and parse, then hand the message to the client thread (if still current). */
	private static void decodeAndQueue(byte[] payload, int gen) {
		try {
			if (gen != generation) return;
			JsonObject o = decode(payload);
			if (o == null) return;
			if ("part".equals(str(o, "t"))) {
				byte[] whole = part(o, gen);
				if (whole == null) return;
				o = decode(whole);
				if (o == null) return;
			}
			JsonObject msg = o;
			Minecraft.getInstance().execute(() -> {
				if (gen == generation) dispatch(msg);
			});
		} catch (Throwable t) {
			Companion.LOG.warn("Could not decode a Feather message", t);
		}
	}

	/** Dev aid ({@link net.axther.aftrhrs.client.DevScript}): handle a message as if Feather had sent it. */
	public static void inject(JsonObject msg) {
		dispatch(msg);
	}

	private static void dispatch(JsonObject msg) {
		String type = str(msg, "t");
		// Only the handshake before the server has agreed to anything.
		if (!type.equals("welcome") && !connected()) return;
		Consumer<JsonObject> h = HANDLERS.get(type);
		if (h == null) return;
		try {
			h.accept(msg);
		} catch (RuntimeException e) {
			Companion.LOG.warn("Feather message '{}' failed", type, e);
		}
	}

	private static byte[] part(JsonObject o, int gen) {
		synchronized (PARTS) {
			// A piece of the last connection must not land after reset cleared the table.
			if (gen != generation) return null;
			long now = System.currentTimeMillis();
			PARTS.values().removeIf(p -> now - p.started() > 30_000);
			int id = num(o, "id", -1), i = num(o, "i", -1), n = num(o, "n", -1);
			if (n <= 0 || n > 256 || i < 0 || i >= n) return null;
			Parts p = PARTS.computeIfAbsent(id, k -> new Parts(new byte[n][], now));
			if (p.pieces().length != n) return null;
			p.pieces()[i] = Base64.getDecoder().decode(str(o, "b"));
			for (byte[] piece : p.pieces()) if (piece == null) return null;
			PARTS.remove(id);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (byte[] piece : p.pieces()) out.writeBytes(piece);
			return out.toByteArray();
		}
	}

	private static JsonObject decode(byte[] payload) {
		if (payload == null || payload.length < 2) return null;
		try {
			byte[] body;
			if (payload[0] == 0) {
				body = Arrays.copyOfRange(payload, 1, payload.length);
			} else if (payload[0] == 1) {
				try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(payload, 1, payload.length - 1))) {
					body = in.readNBytes(64 * 1024 * 1024);
				}
			} else {
				return null;
			}
			JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
			return e.isJsonObject() && e.getAsJsonObject().has("t") ? e.getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			Companion.LOG.warn("Unreadable Feather message", e);
			return null;
		}
	}

	public static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	public static int num(JsonObject o, String key, int fallback) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
	}

	public static double dbl(JsonObject o, String key, double fallback) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : fallback;
	}

	public static long lng(JsonObject o, String key, long fallback) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : fallback;
	}

	public static boolean bool(JsonObject o, String key, boolean fallback) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
	}
}
