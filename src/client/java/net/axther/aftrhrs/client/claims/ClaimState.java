package net.axther.aftrhrs.client.claims;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * What Feather told us about claims ({@code claims} feature): the claims around the player, the budget, the look
 * (colors, which items show borders), the claim tool's state and "show borders" timers. Everything here is display
 * only; claiming still goes through Feather's checks.
 */
public final class ClaimState {
	/** One piece of a claim: a rectangle, a chunk or a circle (stored as its bounding box). */
	public record Piece(int minX, int minZ, int maxX, int maxZ, int shape) {
		public static final int RECT = 0, CHUNK = 1, CIRCLE = 2;

		public boolean round() {
			return shape == CIRCLE;
		}

		public int centerX() {
			return (minX + maxX) >> 1;
		}

		public int centerZ() {
			return (minZ + maxZ) >> 1;
		}

		public int radius() {
			return (maxX - minX) >> 1;
		}

		/** Same rule as Feather's Area: a column is inside a circle when dx² + dz² ≤ r² + r. */
		public boolean contains(int x, int z) {
			if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
			if (!round()) return true;
			long dx = x - centerX(), dz = z - centerZ(), r = radius();
			return dx * dx + dz * dz <= r * r + r;
		}

		/** The covered x range of row z ({@code [from, to]}), or null. */
		public int[] span(int z) {
			if (z < minZ || z > maxZ) return null;
			if (!round()) return new int[]{minX, maxX};
			long dz = z - centerZ(), r = radius();
			long left = r * r + r - dz * dz;
			if (left < 0) return null;
			int w = (int) Math.floor(Math.sqrt(left));
			return new int[]{centerX() - w, centerX() + w};
		}

		/** Half the covered width of row z (0 for a one-column row), or -1 when the row is empty. No allocation. */
		private int halfSpan(int z) {
			if (z < minZ || z > maxZ) return -1;
			long dz = z - centerZ(), r = radius();
			long left = r * r + r - dz * dz;
			return left < 0 ? -1 : (int) Math.floor(Math.sqrt(left));
		}

		/** The first covered x of row z, or Integer.MAX_VALUE when the row is empty (same rule as {@link #span}). */
		public int spanFrom(int z) {
			if (z < minZ || z > maxZ) return Integer.MAX_VALUE;
			if (!round()) return minX;
			int w = halfSpan(z);
			return w < 0 ? Integer.MAX_VALUE : centerX() - w;
		}

		/** The last covered x of row z, or Integer.MIN_VALUE when the row is empty (same rule as {@link #span}). */
		public int spanTo(int z) {
			if (z < minZ || z > maxZ) return Integer.MIN_VALUE;
			if (!round()) return maxX;
			int w = halfSpan(z);
			return w < 0 ? Integer.MIN_VALUE : centerX() + w;
		}

		public long blocks() {
			if (!round()) return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
			long n = 0;
			for (int z = minZ; z <= maxZ; z++) {
				int[] s = span(z);
				if (s != null) n += s[1] - s[0] + 1;
			}
			return n;
		}
	}

	/** A claim near the player, as seen by this player. */
	public record Claim(int id, String name, String owner, String rel, String level, boolean enter, String greeting, boolean pvp, List<Piece> pieces) {
		public boolean contains(int x, int z) {
			for (Piece p : pieces) if (p.contains(x, z)) return true;
			return false;
		}

		public boolean admin() {
			return rel.equals("admin");
		}

		public boolean own() {
			return rel.equals("own");
		}
	}

	public static final ClaimState INSTANCE = new ClaimState();

	public String world = "";
	public List<Claim> claims = List.of();
	public long version;
	public boolean allowed = true;
	public long total, left, used, perHour;
	public int count, max;
	public int colOwn = 0x4FD1C5, colTrusted = 0x9DB4FF, colOther = 0xFF8A3D, colAdmin = 0xFFE066, colPreview = 0xF1EDFF, colGrid = 0xA49DC2;
	public int minWidth = 5, minRadius = 3, maxRadius = 160, showSeconds = 20;
	public Item tool = Items.GOLDEN_SHOVEL, viewItem = Items.STICK;
	/** Tool state: mode and the first corner / circle center (null when not selecting). */
	public String mode = "rect";
	public int[] corner;
	public int[] fixed;
	public String resizing;
	public long showUntil;
	/** Borders always on (the toggle key). */
	public boolean pinned;

	private ClaimState() {
	}

	public static void register() {
		Link.on("claims", INSTANCE::accept);
		Link.on("claim_tool", INSTANCE::acceptTool);
		Link.on("claim_show", m -> INSTANCE.showUntil = System.currentTimeMillis() + Link.lng(m, "ms", 20000));
		Link.on("claim_fx", ClaimFx::accept);
	}

	/** Leaving the server: nothing of this one (budget, look, the toggle) carries over to the next. */
	public void clear() {
		claims = List.of();
		world = "";
		corner = null;
		fixed = null;
		resizing = null;
		mode = "rect";
		showUntil = 0;
		pinned = false;
		allowed = true;
		total = left = used = perHour = 0;
		count = max = 0;
		colOwn = 0x4FD1C5;
		colTrusted = 0x9DB4FF;
		colOther = 0xFF8A3D;
		colAdmin = 0xFFE066;
		colPreview = 0xF1EDFF;
		colGrid = 0xA49DC2;
		minWidth = 5;
		minRadius = 3;
		maxRadius = 160;
		showSeconds = 20;
		tool = Items.GOLDEN_SHOVEL;
		viewItem = Items.STICK;
		version++;
	}

	/** Another dimension: the claims and the selection were the old world's (budget and look stay). */
	public void worldChanged() {
		claims = List.of();
		corner = null;
		fixed = null;
		resizing = null;
		version++;
		ClaimFx.clear();
	}

	private void accept(JsonObject m) {
		world = Link.str(m, "w");
		allowed = Link.bool(m, "allowed", true);
		if (m.has("budget") && m.get("budget").isJsonObject()) {
			JsonObject b = m.getAsJsonObject("budget");
			total = Link.lng(b, "total", 0);
			left = Link.lng(b, "left", 0);
			used = Link.lng(b, "used", 0);
			perHour = Link.lng(b, "perHour", 0);
			count = Link.num(b, "claims", 0);
			max = Link.num(b, "max", 0);
		}
		if (m.has("look") && m.get("look").isJsonObject()) {
			JsonObject l = m.getAsJsonObject("look");
			colOwn = color(l, "own", colOwn);
			colTrusted = color(l, "trusted", colTrusted);
			colOther = color(l, "other", colOther);
			colAdmin = color(l, "admin", colAdmin);
			colPreview = color(l, "preview", colPreview);
			colGrid = color(l, "grid", colGrid);
			minWidth = Link.num(l, "minWidth", minWidth);
			minRadius = Link.num(l, "minRadius", minRadius);
			maxRadius = Link.num(l, "maxRadius", maxRadius);
			showSeconds = Link.num(l, "showSeconds", showSeconds);
			tool = item(Link.str(l, "tool"), tool);
			viewItem = item(Link.str(l, "view"), viewItem);
		}
		List<Claim> list = new ArrayList<>();
		if (m.has("list") && m.get("list").isJsonArray()) {
			for (JsonElement e : m.getAsJsonArray("list")) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				list.add(new Claim(Link.num(o, "id", 0), Link.str(o, "name"), Link.str(o, "owner"), Link.str(o, "rel"), Link.str(o, "lvl"),
					Link.bool(o, "enter", true), Link.str(o, "greet"), Link.bool(o, "pvp", false), pieces(o.get("areas"))));
			}
		}
		claims = List.copyOf(list);
		version++;
	}

	private void acceptTool(JsonObject m) {
		mode = Link.str(m, "mode");
		corner = ints(m.get("corner"), 3);
		fixed = ints(m.get("fixed"), 2);
		resizing = m.has("resize") ? Link.str(m, "resize") : null;
	}

	static List<Piece> pieces(JsonElement e) {
		List<Piece> out = new ArrayList<>();
		if (e == null || !e.isJsonArray()) return out;
		for (JsonElement a : e.getAsJsonArray()) {
			int[] v = ints(a, 5);
			if (v != null) out.add(new Piece(v[0], v[1], v[2], v[3], v[4]));
		}
		return out;
	}

	private static int[] ints(JsonElement e, int n) {
		if (e == null || !e.isJsonArray()) return null;
		JsonArray a = e.getAsJsonArray();
		if (a.size() < n) return null;
		int[] v = new int[n];
		for (int i = 0; i < n; i++) {
			JsonElement x = a.get(i);
			// One bad number drops that piece, not the whole message.
			if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber()) return null;
			v[i] = x.getAsInt();
		}
		return v;
	}

	private static int color(JsonObject o, String key, int fallback) {
		String s = Link.str(o, key);
		if (!s.startsWith("#") || s.length() != 7) return fallback;
		try {
			return Integer.parseInt(s.substring(1), 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static Item item(String id, Item fallback) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return fallback;
		return BuiltInRegistries.ITEM.getOptional(key).orElse(fallback);
	}

	// ------------------------------------------------------------------ queries

	public boolean active() {
		return Link.has("claims");
	}

	/** RGB for a relation ("own", "trusted", "other", "admin"). */
	public int colorOf(String rel) {
		return switch (rel) {
			case "own" -> colOwn;
			case "trusted" -> colTrusted;
			case "admin" -> colAdmin;
			default -> colOther;
		};
	}

	/**
	 * A glyph for a relation, so the claim chip and the map don't rely on color alone: own ✔, trusted ✚, admin ★,
	 * anyone else's ✖, and · for the wilderness (null).
	 */
	public static String glyphOf(String rel) {
		if (rel == null) return "·";
		return switch (rel) {
			case "own" -> "✔";
			case "trusted" -> "✚";
			case "admin" -> "★";
			default -> "✖";
		};
	}

	public Claim at(int x, int z) {
		for (Claim c : claims) if (c.contains(x, z)) return c;
		return null;
	}

	public boolean holdingTool(Player p) {
		return p.getMainHandItem().is(tool);
	}

	public boolean holdingView(Player p) {
		return p.getMainHandItem().is(viewItem) || p.getOffhandItem().is(viewItem);
	}

	/** Borders are drawn while holding the tool or the view item, for a while after "show borders", or when pinned. */
	public boolean bordersVisible() {
		Player p = Minecraft.getInstance().player;
		if (p == null || !active()) return false;
		return pinned || holdingTool(p) || holdingView(p) || showUntil > System.currentTimeMillis();
	}
}
