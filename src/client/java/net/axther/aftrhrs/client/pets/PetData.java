package net.axther.aftrhrs.client.pets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.net.Decode;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What Feather says about the player's pets ({@code pets}; the codex is {@code client/codex}). The screens read it;
 * every change still goes through Feather ({@code pet_action}), which answers with a fresh snapshot.
 */
public final class PetData {
	public record Variant(String key, String name, int tint) {
	}

	public record Perk(String id, String name, String desc, boolean unlocked, int at) {
	}

	public record Pet(String id, String species, String speciesName, String kind, String name, boolean custom, String entity, ItemStack plush,
					  Variant variant, boolean shiny, int level, int maxLevel, long xp, long xpLevel, long xpNext, List<Perk> perks,
					  double health, double maxHealth, String state, boolean working, boolean staying, String where, Integer color,
					  boolean canColor, List<Integer> colors, String breeding, String heal, String fallenAt, String reviveNote, boolean out, boolean loaded, boolean canWork) {
		public boolean alive() {
			return state.equals("alive");
		}

		public boolean fallen() {
			return state.equals("fallen");
		}

		public boolean stabled() {
			return state.equals("stabled");
		}

		/** 0..1 towards the next bond level (1 at the top level). */
		public float bond() {
			if (xpNext <= xpLevel) return 1f;
			return Math.max(0f, Math.min(1f, (xp - xpLevel) / (float) (xpNext - xpLevel)));
		}

		/** The variant's tint (0xRRGGBB, 0xFFFFFF for none). */
		public int tint() {
			return variant == null ? 0xFFFFFF : variant.tint & 0xFFFFFF;
		}

		/** The creature's type in this mod (null if the species has no creature). */
		public net.minecraft.world.entity.EntityType<?> type() {
			return net.axther.aftrhrs.client.entity.CreatureView.type(entity);
		}

		/** This pet with new live values ({@code pets_hp}); {@code where} null keeps the old line. */
		public Pet withLive(double health, double maxHealth, String where) {
			return new Pet(id, species, speciesName, kind, name, custom, entity, plush, variant, shiny, level, maxLevel, xp, xpLevel, xpNext, perks,
				health, maxHealth, state, working, staying, where == null ? this.where : where, color, canColor, colors, breeding, heal, fallenAt,
				reviveNote, out, loaded, canWork);
		}
	}

	public static final PetData INSTANCE = new PetData();

	public List<Pet> pets = List.of();
	public int capPets = -1, capStable = -1, stabled;
	/** Whether the server has the creature codex on (the pet page's "View in Codex" link shows only then). */
	public boolean codexEnabled = true;
	public long version;

	private PetData() {
	}

	public static void register() {
		Link.on("pets", m -> {
			INSTANCE.acceptPets(m);
			open(Link.str(m, "open"));
		});
		// Only health (and the "where" line) of some pets moved: patch them, open nothing.
		Link.on("pets_hp", m -> INSTANCE.acceptHp(m));
	}

	/** Dev aid: feeds a snapshot as if Feather had sent it (DevScript {@code mockpets}). */
	public static void mock(JsonObject pets) {
		if (pets != null) INSTANCE.acceptPets(pets);
	}

	/** Feather handed a vanilla menu to the mod (or answered our request): show the right tab. */
	private static void open(String screen) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof PetScreen s) {
			s.dataChanged(screen.isEmpty() ? null : PetScreen.Tab.of(screen));
			return;
		}
		if (screen.isEmpty()) return;
		mc.gui.setScreen(new PetScreen(PetScreen.Tab.of(screen)));
	}

	/** Asks Feather for the screen's data (and the hand-off still works the vanilla way if the feature is off). */
	public static void request(String screen) {
		JsonObject m = Link.message("pets_open");
		if (screen != null) m.addProperty("screen", screen);
		Link.send(m);
	}

	public static void action(Pet pet, String action, String key, String value) {
		JsonObject m = Link.message("pet_action");
		m.addProperty("pet", pet.id());
		m.addProperty("a", action);
		if (key != null) m.addProperty(key, value);
		Link.send(m);
	}

	public boolean active() {
		return Link.has("pets");
	}

	private boolean primed;

	/** Every tick: once the link is up, ask for the pets once (they may be out before the handshake; the pet HUD needs them). */
	public static void tick() {
		if (INSTANCE.primed || !INSTANCE.active()) return;
		INSTANCE.primed = true;
		request(null);
	}

	public void clear() {
		primed = false;
		pets = List.of();
		capPets = capStable = -1;
		stabled = 0;
		codexEnabled = true;
		version++;
	}

	private void acceptPets(JsonObject m) {
		if (m.has("cap") && m.get("cap").isJsonObject()) {
			JsonObject c = m.getAsJsonObject("cap");
			capPets = Link.num(c, "pets", -1);
			capStable = Link.num(c, "stable", -1);
			stabled = Link.num(c, "stabled", 0);
		}
		codexEnabled = Link.bool(m, "codex", true);
		List<Pet> list = new ArrayList<>();
		if (m.has("pets") && m.get("pets").isJsonArray()) {
			for (JsonElement e : m.getAsJsonArray("pets")) {
				if (e.isJsonObject()) list.add(pet(e.getAsJsonObject()));
			}
		}
		pets = List.copyOf(list);
		version++;
	}

	/** {@code pets_hp}: new health, max health and "where" for the listed pets; unknown ids are ignored. */
	private void acceptHp(JsonObject m) {
		if (!m.has("pets") || !m.get("pets").isJsonArray()) return;
		java.util.Map<String, JsonObject> patch = new java.util.HashMap<>();
		for (JsonElement e : m.getAsJsonArray("pets")) {
			if (e.isJsonObject()) patch.put(Link.str(e.getAsJsonObject(), "id"), e.getAsJsonObject());
		}
		if (patch.isEmpty()) return;
		List<Pet> list = new ArrayList<>(pets.size());
		boolean any = false;
		for (Pet p : pets) {
			JsonObject o = patch.get(p.id());
			if (o == null) {
				list.add(p);
				continue;
			}
			any = true;
			list.add(p.withLive(Link.dbl(o, "health", p.health()), Link.dbl(o, "maxHealth", p.maxHealth()), o.has("where") ? Link.str(o, "where") : null));
		}
		if (!any) return;
		pets = List.copyOf(list);
		version++;
	}

	private static Pet pet(JsonObject o) {
		Variant variant = null;
		if (o.has("variant") && o.get("variant").isJsonObject()) {
			JsonObject v = o.getAsJsonObject("variant");
			variant = new Variant(Link.str(v, "key"), Link.str(v, "name"), hex(Link.str(v, "tint"), 0xFFFFFF));
		}
		List<Perk> perks = new ArrayList<>();
		if (o.has("perks") && o.get("perks").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("perks")) {
				if (!e.isJsonObject()) continue;
				JsonObject p = e.getAsJsonObject();
				perks.add(new Perk(Link.str(p, "id"), Link.str(p, "name"), Link.str(p, "desc"), Link.bool(p, "unlocked", false), Link.num(p, "at", 0)));
			}
		}
		List<Integer> colors = new ArrayList<>();
		if (o.has("colors") && o.get("colors").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("colors")) if (e.isJsonPrimitive()) colors.add(hex(e.getAsString(), 0xFFFFFF));
		}
		ItemStack plush = ItemStack.EMPTY;
		try {
			if (o.has("plush")) plush = Decode.item(o.get("plush"));
		} catch (RuntimeException ignored) {
		}
		String color = Link.str(o, "color");
		return new Pet(Link.str(o, "id"), Link.str(o, "species"), Link.str(o, "speciesName"), Link.str(o, "kind"), Link.str(o, "name"),
			Link.bool(o, "custom", false), entity(o), plush, variant, Link.bool(o, "shiny", false), Link.num(o, "level", 1),
			Link.num(o, "maxLevel", 5), Link.lng(o, "xp", 0), Link.lng(o, "xpLevel", 0), Link.lng(o, "xpNext", -1), List.copyOf(perks),
			Link.dbl(o, "health", 0), Link.dbl(o, "maxHealth", 0), Link.str(o, "state"), Link.bool(o, "working", false),
			Link.bool(o, "staying", false), Link.str(o, "where"), color.isEmpty() ? null : hex(color, 0xFFFFFF),
			Link.bool(o, "canColor", false), List.copyOf(colors), Link.str(o, "breeding"), Link.str(o, "heal"), Link.str(o, "fallenAt"),
			Link.str(o, "reviveNote"), Link.bool(o, "out", false), Link.bool(o, "loaded", true), Link.bool(o, "canWork", true));
	}

	/** The creature: {@code entity} (its registry id), or the model id {@code mid} older servers sent, else the species id. */
	private static String entity(JsonObject o) {
		String e = Link.str(o, "entity");
		if (e.isEmpty()) e = Link.str(o, "mid");
		return e.isEmpty() ? Link.str(o, "species") : e;
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
