package net.axther.aftrhrs.client.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.net.Decode;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The terminal the player has open, as Feather describes it (docs/companion-protocol.md, "Storage terminals"):
 * the network's item kinds and totals, kept up to date by {@code storage_delta}, and Quick Craft's recipe list for the
 * terminal grid's Craftable / All Recipes views ({@code quickcraft}, tooltips by {@code qc_info}). Requests go back by
 * key or recipe id; the server moves the real items and does every craft.
 */
public final class StorageSession {
	/**
	 * One item kind of the network. {@code name} (the shown name, lower case), {@code ns} and {@code path} (its id) and
	 * {@code pathSpaced} (the path with spaces) are worked out once, when it arrives: searching and sorting use them.
	 */
	public record Entry(int key, ItemStack item, long total, List<Change> log, String name, String ns, String path, String pathSpaced) {
		static Entry of(int key, ItemStack item, long total, List<Change> log) {
			Identifier id = BuiltInRegistries.ITEM.getKey(item.getItem());
			String path = id.getPath();
			return new Entry(key, item, total, log, item.getHoverName().getString().toLowerCase(Locale.ROOT), id.getNamespace(), path, path.replace('_', ' '));
		}
	}

	/** Who last changed an item kind: a name, a signed amount, epoch milliseconds (shown in this client's time zone). */
	public record Change(String who, int delta, long at) {
	}

	/** One recipe in the Craftable / All Recipes views: its id, what it makes, how ("direct", "auto", "none"), how often. */
	public record Recipe(String id, ItemStack item, String state, int max) {
		public boolean craftable() {
			return !"none".equals(state);
		}
	}

	private static StorageSession current;

	public final int id;
	public final boolean crafting;
	public final Component title;
	public final Map<Integer, Entry> items = new LinkedHashMap<>();
	public JsonObject info = new JsonObject();
	public final long opened = System.currentTimeMillis();
	/** Bumped on every change, so screens re-sort only when needed. */
	public int version;
	public String lastFill = "";

	// Quick Craft (absent when the server has none: no Craftable view).
	public final boolean quickCraft;
	public int craftDepth;
	public boolean craftAuto;
	public boolean craftAll;
	public int craftAmount = 1;
	public int[] craftAmounts = {1, 4, 16, 64};
	public int craftCap = 256;
	public int craftReady;
	/** The listed recipes; null entries are gaps the server keeps open right after a craft. Null before the first list. */
	public List<Recipe> recipes;
	public int craftVersion;
	/** Bumped on every Quick Craft change (list or tooltip), like {@link #version}. */
	public int craftChanges;
	/** Tooltip lines by recipe id, and the list version they came with (older ones show until the new ones arrive). */
	private final Map<String, List<Component>> tips = new HashMap<>();
	private final Map<String, Integer> tipVersion = new HashMap<>();
	private final Map<String, Long> asked = new HashMap<>();

	private StorageSession(JsonObject m) {
		this.id = Link.num(m, "s", -1);
		this.crafting = "crafting".equals(Link.str(m, "kind"));
		this.title = Decode.text(m.get("title"), Component.translatable(crafting ? "aftrhrs.storage.crafting" : "aftrhrs.storage.title"));
		for (JsonElement e : array(m, "items")) put(e);
		if (m.has("info") && m.get("info").isJsonObject()) info = m.getAsJsonObject("info");
		JsonObject qc = m.has("quickcraft") && m.get("quickcraft").isJsonObject() ? m.getAsJsonObject("quickcraft") : null;
		this.quickCraft = qc != null;
		if (qc != null) {
			craftDepth = Link.num(qc, "depth", 0);
			craftAuto = Link.bool(qc, "auto", false);
			craftAll = Link.bool(qc, "all", false);
			craftAmount = Link.num(qc, "amount", 1);
			com.google.gson.JsonArray a = array(qc, "amounts");
			if (!a.isEmpty()) {
				int[] list = new int[a.size()];
				for (int i = 0; i < list.length; i++) list[i] = a.get(i).isJsonPrimitive() ? a.get(i).getAsInt() : 1;
				craftAmounts = list;
			}
		}
	}

	private void craftList(JsonObject m) {
		craftVersion = Link.num(m, "v", craftVersion + 1);
		craftAll = Link.bool(m, "all", craftAll);
		craftAuto = Link.bool(m, "auto", craftAuto);
		craftDepth = Link.num(m, "depth", craftDepth);
		craftAmount = Link.num(m, "amount", craftAmount);
		craftReady = Link.num(m, "ready", 0);
		craftCap = Link.num(m, "cap", craftCap);
		List<Recipe> list = new ArrayList<>();
		for (JsonElement e : array(m, "recipes")) {
			if (!e.isJsonObject()) {
				list.add(null);
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			ItemStack item = Decode.item(o.get("item"));
			String id = Link.str(o, "r");
			if (item.isEmpty() || id.isEmpty()) {
				list.add(null);
				continue;
			}
			list.add(new Recipe(id, item, Link.str(o, "st"), Link.num(o, "n", 0)));
		}
		recipes = list;
		asked.clear();
		if (tips.size() > 512) {
			tips.clear();
			tipVersion.clear();
		}
		craftChanges++;
	}

	/**
	 * The server's tooltip lines for a recipe, asking for them when they are missing or from an older list (the older
	 * lines show meanwhile); null until the first ones arrive.
	 */
	public List<Component> craftInfo(String id) {
		List<Component> lines = tips.get(id);
		if (lines != null && tipVersion.getOrDefault(id, -1) == craftVersion) return lines;
		long now = System.currentTimeMillis();
		Long at = asked.get(id);
		if (at == null || now - at > 2000) {
			asked.put(id, now);
			JsonObject o = Link.message("qc_info");
			o.addProperty("s", this.id);
			o.addProperty("r", id);
			Link.send(o);
		}
		return lines;
	}

	/** The array under {@code key}, or an empty one (missing or not an array). */
	private static com.google.gson.JsonArray array(JsonObject m, String key) {
		return m.has(key) && m.get(key).isJsonArray() ? m.getAsJsonArray(key) : new com.google.gson.JsonArray();
	}

	private void put(JsonElement e) {
		if (!e.isJsonObject()) return;
		JsonObject o = e.getAsJsonObject();
		int k = Link.num(o, "k", -1);
		long total = Link.lng(o, "total", 0);
		List<Change> log = new ArrayList<>();
		for (JsonElement l : array(o, "log")) {
			if (!l.isJsonObject() || log.size() >= 5) continue;
			JsonObject c = l.getAsJsonObject();
			log.add(new Change(Link.str(c, "who"), Link.num(c, "d", 0), Link.lng(c, "at", 0)));
		}
		if (!o.has("item")) {
			// storage_lean: a kind this session already holds comes without its item. One it doesn't hold (it never
			// arrived, or its item couldn't be read) is ignored; the server sends the item again once the kind is gone.
			Entry old = items.get(k);
			if (old == null) return;
			if (total > 0) items.put(k, new Entry(k, old.item(), total, List.copyOf(log), old.name(), old.ns(), old.path(), old.pathSpaced()));
			else items.remove(k);
			return;
		}
		ItemStack item = Decode.item(o.get("item"));
		if (k < 0 || item.isEmpty() || total <= 0) items.remove(k);
		else items.put(k, Entry.of(k, item, total, List.copyOf(log)));
	}

	public static StorageSession current() {
		return current;
	}

	public static void clear() {
		current = null;
	}

	public static void register() {
		Link.on("storage", m -> {
			StorageSession s = new StorageSession(m);
			current = s;
			Minecraft mc = Minecraft.getInstance();
			if (!s.crafting) {
				mc.gui.setScreen(new StorageScreen(s));
			} else if (mc.gui.screen() instanceof CraftingScreen cs && !(cs instanceof TerminalScreen)) {
				// The crafting window opened first: swap in the terminal screen for the same menu.
				mc.gui.setScreen(new TerminalScreen(cs.getMenu(), mc.player.getInventory(), cs.getTitle(), s));
			}
		});
		Link.on("storage_delta", m -> {
			StorageSession s = current;
			if (s == null || s.id != Link.num(m, "s", -2)) return;
			for (JsonElement e : array(m, "set")) s.put(e);
			for (JsonElement e : array(m, "remove")) {
				if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) s.items.remove(e.getAsInt());
			}
			if (m.has("info") && m.get("info").isJsonObject()) s.info = m.getAsJsonObject("info");
			s.version++;
		});
		Link.on("storage_closed", m -> {
			StorageSession s = current;
			if (s == null || s.id != Link.num(m, "s", -2)) return;
			current = null;
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() instanceof StorageScreen) mc.gui.setScreen(null);
		});
		Link.on("quickcraft", m -> {
			StorageSession s = current;
			if (s == null || s.id != Link.num(m, "s", -2)) return;
			s.craftList(m);
		});
		Link.on("qc_info", m -> {
			StorageSession s = current;
			if (s == null || s.id != Link.num(m, "s", -2) || Link.num(m, "v", -1) != s.craftVersion) return;
			List<Component> lines = new ArrayList<>();
			for (JsonElement e : array(m, "lore")) lines.add(Decode.text(e, Component.empty()));
			s.tips.put(Link.str(m, "r"), lines);
			s.tipVersion.put(Link.str(m, "r"), s.craftVersion);
			s.craftChanges++;
		});
		Link.on("qc_result", m -> {
			StorageSession s = current;
			if (s == null || s.id != Link.num(m, "s", -2)) return;
			s.craftChanges++;
		});
		Link.on("st_fill_result", m -> {
			StorageSession s = current;
			if (s == null || s.id != Link.num(m, "s", -2)) return;
			s.lastFill = Link.bool(m, "ok", false) ? "" : "missing";
			s.version++;
		});
	}

	/**
	 * Called by the screen mixin: a vanilla crafting screen opening right after a Crafting Terminal session started
	 * becomes the terminal screen.
	 */
	public static Screen wrap(Screen screen) {
		StorageSession s = current;
		if (s == null || !s.crafting || !(screen instanceof CraftingScreen cs) || screen instanceof TerminalScreen) return screen;
		if (System.currentTimeMillis() - s.opened > 5000) return screen;
		Minecraft mc = Minecraft.getInstance();
		Inventory inv = mc.player == null ? null : mc.player.getInventory();
		return inv == null ? screen : new TerminalScreen(cs.getMenu(), inv, cs.getTitle(), s);
	}

	// ------------------------------------------------------------------ requests

	public void take(int key, int amount, String to) {
		JsonObject o = Link.message("st_take");
		o.addProperty("s", id);
		o.addProperty("k", key);
		o.addProperty("amount", amount);
		o.addProperty("to", to);
		Link.send(o);
	}

	public void putCursor(int amount) {
		JsonObject o = Link.message("st_put");
		o.addProperty("s", id);
		o.addProperty("from", "cursor");
		o.addProperty("amount", amount);
		Link.send(o);
	}

	public void putSlot(int slot, int amount) {
		JsonObject o = Link.message("st_put");
		o.addProperty("s", id);
		o.addProperty("from", "slot");
		o.addProperty("slot", slot);
		o.addProperty("amount", amount);
		Link.send(o);
	}

	public void depositAll() {
		JsonObject o = Link.message("st_deposit_all");
		o.addProperty("s", id);
		Link.send(o);
	}

	public void fill(String recipe, boolean max) {
		JsonObject o = Link.message("st_fill");
		o.addProperty("s", id);
		o.addProperty("recipe", recipe);
		o.addProperty("times", max ? 64 : 1);
		Link.send(o);
	}

	/**
	 * JEI/REI "+" outside a terminal: Feather fills the open crafting grid (table or the inventory's 2x2) from the
	 * player's inventory. False when the server can't (then the viewer's own transfer is used).
	 */
	public static boolean craftFill(String recipe, boolean max) {
		if (!Link.has("craftfill")) return false;
		JsonObject o = Link.message("craft_fill");
		o.addProperty("recipe", recipe);
		o.addProperty("times", max ? 64 : 1);
		Link.send(o);
		return true;
	}

	// ------------------------------------------------------------------ Quick Craft requests

	/** The grid switched to Craftable ({@code all} false) or All Recipes, with the search box's text. */
	public void craftOpen(boolean all, String query) {
		JsonObject o = Link.message("qc_open");
		o.addProperty("s", id);
		o.addProperty("all", all);
		o.addProperty("q", query == null ? "" : query);
		craftAll = all;
		Link.send(o);
	}

	/** Changes one Quick Craft choice; {@code key} is "q", "all", "auto" or "amount". The server answers with a new list. */
	public void craftOption(String key, Object value) {
		JsonObject o = Link.message("qc_options");
		o.addProperty("s", id);
		if (value instanceof Boolean b) o.addProperty(key, b);
		else if (value instanceof Number n) o.addProperty(key, n);
		else o.addProperty(key, String.valueOf(value));
		Link.send(o);
	}

	/** Crafts a listed recipe: {@code max} as many as fit (right-click), {@code store} into the storage first (shift). */
	public void craft(String recipe, boolean max, boolean store) {
		JsonObject o = Link.message("qc_craft");
		o.addProperty("s", id);
		o.addProperty("r", recipe);
		o.addProperty("max", max);
		o.addProperty("store", store);
		Link.send(o);
	}

	/** The grid went back to the network's items. */
	public void craftClose() {
		JsonObject o = Link.message("qc_close");
		o.addProperty("s", id);
		Link.send(o);
		recipes = null;
	}

	public void close() {
		JsonObject o = Link.message("st_close");
		o.addProperty("s", id);
		Link.send(o);
		if (current == this) current = null;
	}
}
