package net.axther.aftrhrs.client.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What Feather says about the player's creature codex ({@code codex}, docs/companion-protocol.md): its tabs, realms and
 * every creature the mod has, with what this player has found. The codex screen reads it; Feather sends a fresh
 * snapshot whenever the codex opens (command, hub, keybind, a pet's "View in Codex").
 */
public final class CodexData {
	public record Category(String id, String name, String blurb, String item) {
	}

	public record Realm(String id, String name) {
	}

	public record Variant(String key, String name, int tint, boolean seen) {
	}

	public record Drop(String item, int min, int max, double chance) {
	}

	public record Form(String species, String entity, boolean seen, String name, String note, double health, double attack) {
		public net.minecraft.world.entity.EntityType<?> type() {
			return net.axther.aftrhrs.client.entity.CreatureView.type(entity);
		}
	}

	public record Page(String id, String name, int done, int total, long reward, boolean paid) {
	}

	public record Entry(String species, String name, String kind, String state, String category, List<String> realms, boolean tameable,
						String danger, String dangerText, String entity, boolean swarm, int color, String habitat, String about, String behavior,
						String tame, String odds, String heal, String ability, String bond, double health, double attack, double armor, int xp,
						List<Drop> drops, String summonItem, String summonWhere, List<String> phases, List<Variant> variants, boolean shinySeen,
						List<Form> forms, int harvested, int slain, String bestiaryPage) {
		public boolean tamed() {
			return state.equals("tamed");
		}

		public boolean known() {
			return !state.equals("unknown");
		}

		public boolean boss() {
			return "boss".equals(danger);
		}

		public boolean inBestiary() {
			return bestiaryPage != null;
		}

		public net.minecraft.world.entity.EntityType<?> type() {
			return net.axther.aftrhrs.client.entity.CreatureView.type(entity);
		}

		/** Search: name (only once known), kind, habitat, what it does, its tab and realms. */
		public boolean matches(String query) {
			if (query.isEmpty()) return true;
			String q = query.toLowerCase(Locale.ROOT);
			String text = String.join(" ", known() ? name : "", kind, habitat, about, behavior, ability, tame, dangerText, category, String.join(" ", realms));
			return text.toLowerCase(Locale.ROOT).contains(q);
		}
	}

	public static List<Category> categories = List.of();
	public static List<Realm> realms = List.of();
	public static List<Entry> entries = List.of();
	public static List<Page> bestiary = List.of();
	public static int seen, tamed, total, tameable;
	public static String nextMilestone = "";
	/** Bumped on every snapshot. */
	public static long version;
	/** The screen that asked for the codex and gets it back on Escape (a pet's "View in Codex"), while the answer is on its way. */
	private static Screen returnTo;

	private CodexData() {
	}

	public static void register() {
		Link.on("codex", m -> {
			accept(m);
			open(Link.str(m, "open").isEmpty() ? null : Link.str(m, "category"), Link.str(m, "species"), !Link.str(m, "open").isEmpty());
		});
	}

	public static boolean active() {
		return Link.has("pets");
	}

	/** Asks Feather for the codex (it answers with a snapshot that opens the screen), on a tab or creature (both may be null). */
	public static void request(String category, String species) {
		returnTo = null; // opened from the key, the hub or a command: Escape closes
		send(category, species);
	}

	/** The same, from a screen Escape returns to once the codex is open ({@code from}: still the open screen when the answer comes). */
	public static void request(String category, String species, Screen from) {
		returnTo = from;
		send(category, species);
	}

	private static void send(String category, String species) {
		JsonObject m = Link.message("pets_open");
		m.addProperty("screen", "codex");
		if (category != null) m.addProperty("category", category);
		if (species != null) m.addProperty("species", species);
		Link.send(m);
	}

	/** Dev aid: a snapshot as if Feather had sent it (DevScript {@code mockpets}). */
	public static void mock(JsonObject codex) {
		accept(codex);
	}

	public static void clear() {
		categories = List.of();
		realms = List.of();
		entries = List.of();
		bestiary = List.of();
		seen = tamed = total = tameable = 0;
		nextMilestone = "";
		returnTo = null;
		version++;
	}

	private static void open(String category, String species, boolean show) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof CodexScreen s) {
			s.dataChanged(category, species);
			return;
		}
		if (!show) return;
		// back to the screen that asked, only if the player is still on it (closed or moved on meanwhile: no way back)
		Screen parent = returnTo != null && mc.gui.screen() == returnTo ? returnTo : null;
		returnTo = null;
		mc.gui.setScreen(new CodexScreen(category, species, parent));
	}

	static void accept(JsonObject m) {
		if (m.has("counts") && m.get("counts").isJsonObject()) {
			JsonObject c = m.getAsJsonObject("counts");
			seen = Link.num(c, "seen", 0);
			tamed = Link.num(c, "tamed", 0);
			total = Link.num(c, "total", 0);
			tameable = Link.num(c, "tameable", total);
		}
		nextMilestone = Link.str(m, "next");
		List<Category> cats = new ArrayList<>();
		for (JsonObject o : objects(m, "categories")) cats.add(new Category(Link.str(o, "id"), Link.str(o, "name"), Link.str(o, "blurb"), Link.str(o, "item")));
		List<Realm> rs = new ArrayList<>();
		for (JsonObject o : objects(m, "realms")) rs.add(new Realm(Link.str(o, "id"), Link.str(o, "name")));
		List<Page> pages = new ArrayList<>();
		for (JsonObject o : objects(m, "bestiary")) {
			pages.add(new Page(Link.str(o, "id"), Link.str(o, "name"), Link.num(o, "done", 0), Link.num(o, "total", 0), Link.lng(o, "reward", 0),
				Link.bool(o, "paid", false)));
		}
		List<Entry> list = new ArrayList<>();
		for (JsonObject o : objects(m, "entries")) list.add(entry(o));
		// an older Feather sends no tabs: one per kind it names
		if (cats.isEmpty()) {
			for (Entry e : list) if (cats.stream().noneMatch(c -> c.id().equals(e.category()))) cats.add(new Category(e.category(), e.category(), "", ""));
		}
		categories = List.copyOf(cats);
		realms = List.copyOf(rs);
		bestiary = List.copyOf(pages);
		entries = List.copyOf(list);
		version++;
	}

	private static Entry entry(JsonObject o) {
		List<Variant> variants = new ArrayList<>();
		for (JsonObject v : objects(o, "variants")) {
			variants.add(new Variant(Link.str(v, "key"), Link.str(v, "name"), hex(Link.str(v, "tint"), 0xFFFFFF), Link.bool(v, "seen", false)));
		}
		List<Drop> drops = new ArrayList<>();
		for (JsonObject d : objects(o, "drops")) drops.add(new Drop(Link.str(d, "item"), Link.num(d, "min", 1), Link.num(d, "max", 1), Link.dbl(d, "chance", 1)));
		List<Form> forms = new ArrayList<>();
		for (JsonObject f : objects(o, "forms")) {
			forms.add(new Form(Link.str(f, "species"), Link.str(f, "entity"), Link.bool(f, "seen", false), Link.str(f, "name"), Link.str(f, "note"),
				Link.dbl(f, "health", 0), Link.dbl(f, "attack", 0)));
		}
		List<String> realms = new ArrayList<>();
		if (o.has("realms") && o.get("realms").isJsonArray()) for (JsonElement r : o.getAsJsonArray("realms")) realms.add(r.getAsString());
		List<String> phases = new ArrayList<>();
		if (o.has("phases") && o.get("phases").isJsonArray()) for (JsonElement p : o.getAsJsonArray("phases")) phases.add(p.getAsString());
		JsonObject stats = o.has("stats") && o.get("stats").isJsonObject() ? o.getAsJsonObject("stats") : new JsonObject();
		JsonObject summon = o.has("summon") && o.get("summon").isJsonObject() ? o.getAsJsonObject("summon") : null;
		JsonObject best = o.has("bestiary") && o.get("bestiary").isJsonObject() ? o.getAsJsonObject("bestiary") : null;
		String kind = Link.str(o, "kind");
		String category = Link.str(o, "category");
		if (category.isEmpty()) category = kind.isEmpty() ? "companions" : kind.toLowerCase(Locale.ROOT);
		String entity = Link.str(o, "entity");
		if (entity.isEmpty()) entity = Link.str(o, "mid");
		if (entity.isEmpty()) entity = Link.str(o, "species");
		return new Entry(Link.str(o, "species"), Link.str(o, "name"), kind, Link.str(o, "state"), category, List.copyOf(realms),
			Link.bool(o, "tameable", true), Link.str(o, "danger"), Link.str(o, "dangerText"), entity, "swarm".equals(Link.str(o, "preview")),
			hex(Link.str(o, "color"), 0xF29BB8), Link.str(o, "habitat"), Link.str(o, "about"), Link.str(o, "behavior"), Link.str(o, "tame"),
			Link.str(o, "odds"), Link.str(o, "heal"), Link.str(o, "ability"), Link.str(o, "bond"), Link.dbl(stats, "health", 0),
			Link.dbl(stats, "attack", 0), Link.dbl(stats, "armor", 0), Link.num(stats, "xp", 0), List.copyOf(drops),
			summon == null ? null : Link.str(summon, "item"), summon == null ? null : Link.str(summon, "where"), List.copyOf(phases),
			List.copyOf(variants), Link.bool(o, "shinySeen", false), List.copyOf(forms), best == null ? 0 : Link.num(best, "harvested", 0),
			best == null ? 0 : Link.num(best, "slain", 0), best == null ? null : Link.str(best, "page"));
	}

	private static List<JsonObject> objects(JsonObject o, String key) {
		List<JsonObject> out = new ArrayList<>();
		if (!o.has(key) || !o.get(key).isJsonArray()) return out;
		JsonArray a = o.getAsJsonArray(key);
		for (JsonElement e : a) if (e.isJsonObject()) out.add(e.getAsJsonObject());
		return out;
	}

	static int hex(String s, int fallback) {
		if (s == null) return fallback;
		String h = s.startsWith("#") ? s.substring(1) : s;
		if (h.length() != 6) return fallback;
		try {
			return Integer.parseInt(h, 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
