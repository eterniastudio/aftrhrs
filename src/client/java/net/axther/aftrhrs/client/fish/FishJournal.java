package net.axther.aftrhrs.client.fish;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.client.net.Decode;
import net.axther.aftrhrs.client.net.Link;
import net.axther.aftrhrs.feature.fishing.FishWeight;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What Feather says about the player's Fish Journal and the server records ({@code fishing}). The Angler's Journal
 * screen reads it; a Claim goes to Feather ({@code fish_claim}), which pays (or not) and answers with a fresh snapshot.
 */
public final class FishJournal {
	/** A species slot. An uncaught one has only {@code hint} (like the dialog's "???" row). */
	public record Fish(boolean caught, String name, ItemStack item, int count, int best, long first, int recordGrams, String recordName, String hint) {
	}

	public record Tier(String rarity, String name, int color, int have, int total, long coins, String state, List<Fish> fish) {
		public boolean claimable() {
			return state.equals("claim");
		}

		public boolean done() {
			return state.equals("done");
		}
	}

	public record Rec(String name, String rarity, ItemStack item, int grams, String holder, int mine) {
	}

	public static int have, total;
	/** What the player's rod does for the catch (Luck of the Sea, Lure, the rod), as Feather words it; "" without a rod. */
	public static String gear = "";
	public static List<Tier> tiers = List.of();
	public static List<Rec> records = List.of();
	/** Bumped on every snapshot (the screen relays out). */
	public static long version;

	private FishJournal() {
	}

	public static void register() {
		Link.on("fish_journal", m -> {
			accept(m);
			open(Link.str(m, "open"));
		});
		FightScreen.register();
	}

	public static boolean active() {
		return Link.has("fishing");
	}

	/** Asks Feather for the screen ("journal" or "records"); it answers with a snapshot that opens it. */
	public static void request(String screen) {
		JsonObject m = Link.message("fish_journal_open");
		m.addProperty("screen", screen);
		Link.send(m);
	}

	/** Asks Feather to pay a finished tier. Feather checks everything and answers with a fresh snapshot. */
	public static void claim(String rarity) {
		JsonObject m = Link.message("fish_claim");
		m.addProperty("rarity", rarity);
		Link.send(m);
	}

	public static void clear() {
		have = total = 0;
		tiers = List.of();
		records = List.of();
		version++;
	}

	private static void open(String screen) {
		Minecraft mc = Minecraft.getInstance();
		AnglerScreen.Tab tab = screen.isEmpty() ? null : AnglerScreen.Tab.of(screen);
		if (mc.gui.screen() instanceof AnglerScreen s) {
			s.dataChanged(tab);
			return;
		}
		if (tab != null) mc.gui.setScreen(new AnglerScreen(tab));
	}

	private static JsonArray arr(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	/** "#8FD3FF" → 0x8FD3FF; the text color when unreadable. */
	static int hex(String s) {
		try {
			if (s.startsWith("#") && s.length() == 7) return Integer.parseInt(s.substring(1), 16);
		} catch (NumberFormatException ignored) {
		}
		return 0xF1EDFF;
	}

	static void accept(JsonObject m) {
		have = Link.num(m, "have", 0);
		gear = Link.str(m, "gear");
		total = Link.num(m, "total", 0);
		List<Tier> ts = new ArrayList<>();
		for (JsonElement e : arr(m, "tiers")) {
			if (!e.isJsonObject()) continue;
			JsonObject t = e.getAsJsonObject();
			List<Fish> fish = new ArrayList<>();
			for (JsonElement fe : arr(t, "fish")) {
				if (!fe.isJsonObject()) continue;
				JsonObject f = fe.getAsJsonObject();
				boolean caught = Link.bool(f, "caught", false);
				JsonObject rec = f.has("record") && f.get("record").isJsonObject() ? f.getAsJsonObject("record") : null;
				fish.add(new Fish(caught, Link.str(f, "name"), caught ? Decode.item(f.get("item")) : ItemStack.EMPTY,
					Link.num(f, "count", 0), Link.num(f, "best", 0), Link.lng(f, "first", 0),
					rec == null ? 0 : Link.num(rec, "grams", 0), rec == null ? "" : Link.str(rec, "name"), Link.str(f, "hint")));
			}
			String state = Link.str(t, "state");
			ts.add(new Tier(Link.str(t, "rarity"), Link.str(t, "name"), hex(Link.str(t, "color")), Link.num(t, "have", 0),
				Link.num(t, "total", fish.size()), Link.lng(t, "coins", 0), state.isEmpty() ? "open" : state, List.copyOf(fish)));
		}
		List<Rec> rs = new ArrayList<>();
		for (JsonElement e : arr(m, "records")) {
			if (!e.isJsonObject()) continue;
			JsonObject r = e.getAsJsonObject();
			rs.add(new Rec(Link.str(r, "name"), Link.str(r, "rarity"), Decode.item(r.get("item")), Link.num(r, "grams", 0),
				Link.str(r, "holder"), Link.num(r, "mine", 0)));
		}
		tiers = List.copyOf(ts);
		records = List.copyOf(rs);
		version++;
	}

	/** The color of a rarity, from its tier (records carry only the rarity). */
	static int colorOf(String rarity) {
		for (Tier t : tiers) if (t.rarity().equals(rarity)) return t.color();
		return 0xF1EDFF;
	}

	/** "3.2 lb" ("0.45 lb" under 1 lb), exactly as Feather shows weights ({@link FishWeight#format}). */
	static String lb(int grams) {
		return FishWeight.format(grams);
	}

	// ------------------------------------------------------------------ dev aid

	/** DevScript {@code screen fishjournal} / {@code fishrecords}: a made-up journal (vanilla items), as if Feather sent it. */
	public static void mock() {
		long day = 86_400_000L, now = System.currentTimeMillis();
		JsonObject m = new JsonObject();
		m.addProperty("t", "fish_journal");
		JsonArray tiers = new JsonArray();
		Object[][] spec = {
			{"common", "Common", "#C9C2E8", 40L, "done", new String[]{"Pond Perch", "minecraft:cod", "Brook Minnow", "minecraft:salmon", "Mud Carp", "minecraft:cod", "Reed Bass", "minecraft:salmon"}},
			{"uncommon", "Uncommon", "#86E8B0", 100L, "claim", new String[]{"Glass Eel", "minecraft:tropical_fish", "Tide Mullet", "minecraft:cod", "Coral Wrasse", "minecraft:tropical_fish"}},
			{"rare", "Rare", "#8FD3FF", 250L, "open", new String[]{"Moonkoi", "minecraft:tropical_fish", "", "salt water, night", "Storm Pike", "minecraft:salmon", "", "fresh water, swamp, rain", "", "underground"}},
			{"legendary", "Legendary", "#FFD24A", 800L, "open", new String[]{"", "salt water, deep ocean, night, thunder", "", "fresh water, Ironbark Rod"}}};
		int have = 0, total = 0, n = 0;
		for (Object[] s : spec) {
			JsonObject t = new JsonObject();
			t.addProperty("rarity", (String) s[0]);
			t.addProperty("name", (String) s[1]);
			t.addProperty("color", (String) s[2]);
			t.addProperty("coins", (Long) s[3]);
			t.addProperty("state", (String) s[4]);
			String[] list = (String[]) s[5];
			JsonArray fish = new JsonArray();
			int th = 0;
			for (int i = 0; i < list.length; i += 2) {
				JsonObject f = new JsonObject();
				n++;
				if (list[i].isEmpty()) {
					f.addProperty("caught", false);
					f.addProperty("hint", list[i + 1]);
				} else {
					th++;
					f.addProperty("id", list[i].toLowerCase(Locale.ROOT).replace(' ', '_'));
					f.addProperty("caught", true);
					f.addProperty("name", list[i]);
					JsonObject item = new JsonObject();
					item.addProperty("id", list[i + 1]);
					f.add("item", item);
					f.addProperty("count", 1 + (n * 7) % 23);
					f.addProperty("best", 300 + (n * 977) % 4200);
					f.addProperty("first", now - day * (3 + n * 2));
					if (n % 3 != 0) {
						JsonObject r = new JsonObject();
						r.addProperty("grams", n == 2 ? 300 + (n * 977) % 4200 : 600 + (n * 1531) % 5200);
						r.addProperty("name", n == 2 ? "Kev" : n % 2 == 0 ? "Alice" : "Marlow");
						f.add("record", r);
					}
				}
				fish.add(f);
			}
			t.addProperty("have", th);
			t.addProperty("total", list.length / 2);
			t.add("fish", fish);
			tiers.add(t);
			have += th;
			total += list.length / 2;
		}
		m.add("tiers", tiers);
		m.addProperty("have", have);
		m.addProperty("total", total);
		m.add("records", JsonParser.parseString("""
			[{"id":"moonkoi","name":"Moonkoi","rarity":"rare","item":{"id":"minecraft:tropical_fish"},"grams":4100,"holder":"Alice","mine":3200},
			 {"id":"storm_pike","name":"Storm Pike","rarity":"rare","item":{"id":"minecraft:salmon"},"grams":5830,"holder":"Marlow"},
			 {"id":"glass_eel","name":"Glass Eel","rarity":"uncommon","item":{"id":"minecraft:tropical_fish"},"grams":2210,"holder":"Kev","mine":2210},
			 {"id":"pond_perch","name":"Pond Perch","rarity":"common","item":{"id":"minecraft:cod"},"grams":870,"holder":"Alice","mine":640},
			 {"id":"reed_bass","name":"Reed Bass","rarity":"common","item":{"id":"minecraft:salmon"},"grams":1340,"holder":"Juniper"}]
			""").getAsJsonArray());
		accept(m);
	}
}
