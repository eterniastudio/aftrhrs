package net.axther.aftrhrs.client.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.client.Companion;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The terminal screens' own choices, kept in {@code config/aftrhrs/storage.json}: which view the grid opens with
 * (Items, Craftable or All Recipes), whether the search box follows JEI's search, what the box holds when a terminal
 * opens and whether it takes the keyboard at once. Client-side only; the server keeps Quick Craft's Auto-Craft and craft
 * amount for the player.
 */
public final class StoragePrefs {
	/** What the terminal grid shows. */
	public enum View {
		ITEMS, CRAFTABLE, ALL;

		public boolean crafting() {
			return this != ITEMS;
		}
	}

	/** What the search box holds when a terminal opens. */
	public enum OnOpen {
		/** JEI's search (an empty box without JEI). */
		JEI,
		/** Empty (and JEI's search too, while JEI sync is on). */
		CLEAR,
		/** The last search typed in a terminal (this game session). */
		KEEP;

		/** The next mode for the options' cycle button; JEI is skipped when JEI isn't running. */
		public OnOpen next(boolean jei) {
			return step(1, jei);
		}

		public OnOpen previous(boolean jei) {
			return step(values().length - 1, jei);
		}

		private OnOpen step(int by, boolean jei) {
			OnOpen n = values()[(ordinal() + by) % values().length];
			return n == JEI && !jei ? n.step(by, true) : n;
		}
	}

	private static View view = View.ITEMS;
	private static boolean jeiSync;
	private static OnOpen onOpen;
	private static boolean focusOnOpen;
	/** The last search typed in a terminal, for {@link OnOpen#KEEP} (not saved: a new game opens empty). */
	private static String lastSearch = "";
	private static boolean loaded;

	private StoragePrefs() {
	}

	public static View view() {
		load();
		return view;
	}

	public static void view(View v) {
		load();
		if (v == view) return;
		view = v;
		save();
	}

	public static boolean jeiSync() {
		load();
		return jeiSync;
	}

	public static void jeiSync(boolean on) {
		load();
		if (on == jeiSync) return;
		jeiSync = on;
		save();
	}

	public static OnOpen onOpen() {
		load();
		return onOpen;
	}

	public static void onOpen(OnOpen mode) {
		load();
		if (mode == onOpen) return;
		onOpen = mode;
		save();
	}

	/** The search box takes the keyboard when a terminal opens, so typing searches straight away. */
	public static boolean focusOnOpen() {
		load();
		return focusOnOpen;
	}

	public static void focusOnOpen(boolean on) {
		load();
		if (on == focusOnOpen) return;
		focusOnOpen = on;
		save();
	}

	public static String lastSearch() {
		return lastSearch;
	}

	public static void lastSearch(String text) {
		lastSearch = text == null ? "" : text;
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("aftrhrs").resolve("storage.json");
	}

	private static void load() {
		if (loaded) return;
		loaded = true;
		Path f = file();
		try {
			if (!Files.isRegularFile(f)) return;
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			if (o.has("view")) {
				try {
					view = View.valueOf(o.get("view").getAsString());
				} catch (RuntimeException ignored) {
				}
			}
			if (o.has("jeiSync") && o.get("jeiSync").isJsonPrimitive()) jeiSync = o.get("jeiSync").getAsBoolean();
			if (o.has("onOpen")) {
				try {
					onOpen = OnOpen.valueOf(o.get("onOpen").getAsString());
				} catch (RuntimeException ignored) {
				}
			}
			if (o.has("focusOnOpen") && o.get("focusOnOpen").isJsonPrimitive()) focusOnOpen = o.get("focusOnOpen").getAsBoolean();
		} catch (IOException | RuntimeException e) {
			Companion.LOG.warn("Couldn't read {}; the terminals use their defaults", f, e);
		} finally {
			// Before the setting existed, JEI sync on meant "open with JEI's search", off meant an empty box.
			if (onOpen == null) onOpen = jeiSync ? OnOpen.JEI : OnOpen.CLEAR;
		}
	}

	private static void save() {
		JsonObject o = new JsonObject();
		o.addProperty("view", view.name());
		o.addProperty("jeiSync", jeiSync);
		o.addProperty("onOpen", onOpen.name());
		o.addProperty("focusOnOpen", focusOnOpen);
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Companion.LOG.warn("Couldn't save {}", f, e);
		}
	}

	// ------------------------------------------------------------------ JEI search sync

	/** JEI's search text, set by the JEI plugin while JEI runs (null without JEI: the sync toggle is hidden). */
	public interface SearchLink {
		String get();

		void set(String text);
	}

	private static volatile SearchLink search;

	public static void searchLink(SearchLink link) {
		search = link;
	}

	/** JEI's search, or null when there is no JEI (or its runtime isn't up yet). */
	public static SearchLink searchLink() {
		return search;
	}
}
