package net.axther.aftrhrs.client.recipes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.net.Decode;
import net.axther.aftrhrs.client.net.Link;
import net.fabricmc.fabric.api.recipe.v1.ingredient.DefaultCustomIngredients;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The server's recipes (Feather's {@code recipes} message) turned back into real vanilla recipe objects, so JEI and
 * REI show them in their own crafting/smelting/… categories and their "move items" buttons work. Feather
 * workstations ({@code feather:*}) are kept as {@link Machine} entries for the viewers' Aftr Hrs categories.
 * The list is cached per server, so a rejoin with an unchanged list costs one hash.
 *
 * <p>Cache file {@code config/aftrhrs/recipes-<server>.bin}: the ASCII line {@code AHR1 <hash>} then the GZIP'd JSON
 * list, so the hello reads the hash from the first bytes alone. Reading the list and writing it run on one background
 * thread (in order); the client thread only gets the parsed list back.
 *
 * <p>Making the list into recipe objects (decoding every item; about 75 ms for a big server) also runs on that thread,
 * and only once a viewer listens ({@link #onChange}): without JEI or REI nothing is built. The built list is handed
 * over on the client thread and then the listeners run.
 */
public final class ServerRecipes {
	private static final String MAGIC = "AHR1 ";
	/** Reads and writes of the cache file, one at a time and in order. */
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Aftr Hrs recipe cache");
		t.setDaemon(true);
		return t;
	});

	/** A Feather workstation recipe (stove meal, …). */
	public record Machine(Identifier id, String machine, ItemStack station, List<List<ItemStack>> inputs, ItemStack output, Component note) {
	}

	private JsonObject raw;
	private String hash = "";
	private Built built;
	/**
	 * Bumped by {@link #clear} (leaving a server) and by a full list arriving: a cache read or a build that finishes
	 * later is dropped. Written on the client thread only; volatile so a queued build can give up early.
	 */
	private volatile int generation;
	/** The list a build is queued or running for (null: none). Client thread. */
	private JsonObject building;
	/** The cache file's hash as last read or written this session (keyed by file, so it outlives {@link #clear}). Client thread. */
	private Path memoPath;
	private String memoHash = "";
	private final List<Consumer<ServerRecipes>> listeners = new ArrayList<>();

	/** The list made into objects (built off the client thread; see {@link #requestBuild}). */
	public static final class Built {
		public final List<RecipeHolder<CraftingRecipe>> crafting = new ArrayList<>();
		public final List<RecipeHolder<SmeltingRecipe>> smelting = new ArrayList<>();
		public final List<RecipeHolder<BlastingRecipe>> blasting = new ArrayList<>();
		public final List<RecipeHolder<SmokingRecipe>> smoking = new ArrayList<>();
		public final List<RecipeHolder<CampfireCookingRecipe>> campfire = new ArrayList<>();
		public final List<RecipeHolder<StonecutterRecipe>> stonecutting = new ArrayList<>();
		public final List<RecipeHolder<SmithingRecipe>> smithing = new ArrayList<>();
		public final Map<String, List<Machine>> machines = new LinkedHashMap<>();
		/** Every server item (one carrying components) the recipes use or make, for the viewers' item lists. */
		public final List<ItemStack> items = new ArrayList<>();
		public int skipped;

		public int size() {
			int n = crafting.size() + smelting.size() + blasting.size() + smoking.size() + campfire.size() + stonecutting.size() + smithing.size();
			for (List<Machine> m : machines.values()) n += m.size();
			return n;
		}
	}

	public void register() {
		Link.on("recipes", m -> {
			// Newer than any cached list still being read: that read must not replace this one.
			generation++;
			accept(m);
			save(m);
		});
		Link.on("recipes_ok", m -> {
			// The server's list is the one we cached: read it from disk off-thread, then take it here.
			Path file = cacheFile();
			String want = Link.str(m, "hash");
			int gen = generation;
			IO.execute(() -> {
				JsonObject cached = load(file);
				boolean ok = cached != null && !want.isEmpty() && Link.str(cached, "hash").equals(want);
				if (!ok) {
					// Unreadable or not what its first line said: drop it, so the next join asks for the whole list.
					Companion.LOG.warn("The cached server recipes ({}) don't match the server's list; asking for them again next join", file.getFileName());
					try {
						Files.deleteIfExists(file);
					} catch (IOException ignored) {
					}
				}
				Minecraft.getInstance().execute(() -> {
					if (!ok) {
						if (file.equals(memoPath)) memoPath = null;
						return;
					}
					if (gen == generation && Link.has("recipes")) accept(cached);
				});
			});
		});
	}

	/** Runs when a new list has been built (JEI/REI add the recipes); a list already received gets built now. */
	public void onChange(Consumer<ServerRecipes> listener) {
		listeners.add(listener);
		requestBuild();
	}

	/** The server sent its list (it may not be built yet: see {@link #ready}). */
	public boolean available() {
		return raw != null && Link.has("recipes");
	}

	/** The server's list is built: {@link #built} has it. */
	public boolean ready() {
		return built != null && Link.has("recipes");
	}

	public void clear() {
		raw = null;
		built = null;
		building = null;
		hash = "";
		generation++;
	}

	/** Client thread: takes a list (from the server or the cache) and queues its build. */
	private void accept(JsonObject m) {
		raw = m;
		hash = Link.str(m, "hash");
		built = null;
		Decode.clearCache();
		int n = m.has("recipes") && m.get("recipes").isJsonArray() ? m.getAsJsonArray("recipes").size() : 0;
		Companion.LOG.info("Received {} server recipes from Feather", n);
		requestBuild();
	}

	/** The built list, or an empty one while it isn't {@link #ready}; never builds on the calling thread. */
	public Built built() {
		return ready() ? built : new Built();
	}

	/**
	 * Client thread: builds the list received on the background thread when a viewer listens and it isn't built or
	 * being built yet. The result is taken on the client thread only if it is still the current list (no
	 * {@link #clear}, no newer list meanwhile); then the listeners run.
	 */
	public void requestBuild() {
		if (raw == null || listeners.isEmpty() || built != null || building == raw) return;
		int gen = generation;
		JsonObject r = raw;
		building = r;
		HolderLookup.Provider reg = Decode.currentRegistries();
		IO.execute(() -> {
			if (gen != generation) return;
			Built b;
			try {
				b = build(r, reg);
			} catch (Throwable t) {
				Companion.LOG.warn("Could not build server recipes", t);
				b = null;
			}
			Built fb = b;
			Minecraft.getInstance().execute(() -> {
				if (building == r) building = null;
				if (fb == null || gen != generation || raw != r || !Link.has("recipes")) return;
				built = fb;
				Companion.LOG.info("Built {} server recipes ({} skipped)", fb.size(), fb.skipped);
				for (Consumer<ServerRecipes> l : listeners) {
					try {
						l.accept(this);
					} catch (RuntimeException e) {
						Companion.LOG.warn("Recipe viewer update failed", e);
					}
				}
			});
		});
	}

	// ------------------------------------------------------------------ building

	/** One build's registries and its own decoded-item cache (the shared {@link Decode} cache is the client thread's). */
	private record Ctx(HolderLookup.Provider reg, Map<String, ItemStack> items) {
		ItemStack item(JsonElement el) {
			return Decode.item(el, reg, items);
		}
	}

	/** The list made into recipe objects. Pure: any thread ({@code reg} null: component-carrying items are skipped). */
	static Built build(JsonObject raw, HolderLookup.Provider reg) {
		Ctx c = new Ctx(reg, new HashMap<>());
		Built b = new Built();
		if (raw != null && raw.has("recipes")) {
			for (JsonElement e : raw.getAsJsonArray("recipes")) {
				try {
					if (!add(c, b, e.getAsJsonObject())) b.skipped++;
					else collectItems(c, b, e.getAsJsonObject());
				} catch (RuntimeException ex) {
					b.skipped++;
				}
			}
		}
		return b;
	}

	private static ResourceKey<Recipe<?>> key(String id) {
		Identifier rl = Identifier.tryParse(id);
		if (rl == null) rl = Identifier.fromNamespaceAndPath("aftrhrs", "unnamed_" + Integer.toHexString(id.hashCode()));
		return ResourceKey.create(Registries.RECIPE, rl);
	}

	private static boolean add(Ctx c, Built b, JsonObject o) {
		String type = Link.str(o, "type");
		ItemStack out = c.item(o.get("out"));
		if (out.isEmpty()) return false;
		ItemStackTemplate result = ItemStackTemplate.fromNonEmptyStack(out);
		ResourceKey<Recipe<?>> id = key(Link.str(o, "id"));
		String group = Link.str(o, "group");
		Recipe.CommonInfo common = new Recipe.CommonInfo(false);
		switch (type) {
			case "shaped" -> {
				int w = Link.num(o, "w", 0), h = Link.num(o, "h", 0);
				JsonArray in = o.getAsJsonArray("in");
				if (w < 1 || h < 1 || w > 3 || h > 3 || in.size() != w * h) return false;
				List<Optional<Ingredient>> slots = new ArrayList<>(in.size());
				for (JsonElement e : in) slots.add(ingredient(c, e));
				if (slots.stream().allMatch(Optional::isEmpty)) return false;
				ShapedRecipePattern pattern = new ShapedRecipePattern(w, h, slots, Optional.empty());
				b.crafting.add(new RecipeHolder<>(id, new ShapedRecipe(common, new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, group), pattern, result)));
			}
			case "shapeless" -> {
				List<Ingredient> list = new ArrayList<>();
				for (JsonElement e : o.getAsJsonArray("in")) {
					Optional<Ingredient> ing = ingredient(c, e);
					if (ing.isEmpty()) return false;
					list.add(ing.get());
				}
				if (list.isEmpty() || list.size() > 9) return false;
				b.crafting.add(new RecipeHolder<>(id, new ShapelessRecipe(common, new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, group), result, list)));
			}
			case "smelting", "blasting", "smoking", "campfire" -> {
				Optional<Ingredient> ing = ingredient(c, o.getAsJsonArray("in").get(0));
				if (ing.isEmpty()) return false;
				var book = new AbstractCookingRecipe.CookingBookInfo(CookingBookCategory.MISC, group);
				float xp = (float) Link.dbl(o, "xp", 0);
				int time = Link.num(o, "time", 200);
				switch (type) {
					case "smelting" -> b.smelting.add(new RecipeHolder<>(id, new SmeltingRecipe(common, book, ing.get(), result, xp, time)));
					case "blasting" -> b.blasting.add(new RecipeHolder<>(id, new BlastingRecipe(common, book, ing.get(), result, xp, time)));
					case "smoking" -> b.smoking.add(new RecipeHolder<>(id, new SmokingRecipe(common, book, ing.get(), result, xp, time)));
					default -> b.campfire.add(new RecipeHolder<>(id, new CampfireCookingRecipe(common, book, ing.get(), result, xp, time)));
				}
			}
			case "stonecutting" -> {
				Optional<Ingredient> ing = ingredient(c, o.getAsJsonArray("in").get(0));
				if (ing.isEmpty()) return false;
				b.stonecutting.add(new RecipeHolder<>(id, new StonecutterRecipe(common, ing.get(), result)));
			}
			case "smithing" -> {
				Optional<Ingredient> base = ingredient(c, o.get("base"));
				if (base.isEmpty()) return false;
				b.smithing.add(new RecipeHolder<>(id, new SmithingTransformRecipe(common, ingredient(c, o.get("template")), base.get(), ingredient(c, o.get("addition")), result)));
			}
			default -> {
				if (!type.startsWith("feather:")) return false;
				String machine = type.substring("feather:".length());
				List<List<ItemStack>> inputs = new ArrayList<>();
				for (JsonElement e : o.getAsJsonArray("in")) inputs.add(stacks(c, e));
				Identifier rid = id.identifier();
				b.machines.computeIfAbsent(machine, k -> new ArrayList<>()).add(new Machine(rid, machine, c.item(o.get("station")), inputs, out,
					o.has("note") ? Decode.text(o.get("note"), Component.empty(), c.reg()) : null));
			}
		}
		return true;
	}

	private static void collectItems(Ctx c, Built b, JsonObject o) {
		List<ItemStack> all = new ArrayList<>();
		all.add(c.item(o.get("out")));
		for (String k : new String[]{"base", "template", "addition"}) all.addAll(stacks(c, o.get(k)));
		if (o.has("in") && o.get("in").isJsonArray()) for (JsonElement e : o.getAsJsonArray("in")) all.addAll(stacks(c, e));
		for (ItemStack s : all) if (!s.isEmpty() && !s.getComponentsPatch().isEmpty()) b.items.add(s);
	}

	private static List<ItemStack> stacks(Ctx c, JsonElement e) {
		List<ItemStack> out = new ArrayList<>();
		if (e != null && e.isJsonArray()) {
			for (JsonElement s : e.getAsJsonArray()) {
				ItemStack st = c.item(s);
				if (!st.isEmpty()) out.add(st);
			}
		}
		return out;
	}

	/** One slot's alternatives as an ingredient; component-carrying items match on their components. */
	private static Optional<Ingredient> ingredient(Ctx c, JsonElement e) {
		List<ItemStack> alts = stacks(c, e);
		if (alts.isEmpty()) return Optional.empty();
		List<net.minecraft.world.item.Item> plain = new ArrayList<>();
		List<Ingredient> custom = new ArrayList<>();
		for (ItemStack s : alts) {
			Ingredient exact = s.getComponentsPatch().isEmpty() ? null : DefaultCustomIngredients.components(s.copyWithCount(1));
			if (exact != null) custom.add(exact);
			else if (!plain.contains(s.getItem())) plain.add(s.getItem());
		}
		if (custom.isEmpty()) return Optional.of(Ingredient.of(plain.toArray(net.minecraft.world.level.ItemLike[]::new)));
		if (!plain.isEmpty()) custom.add(Ingredient.of(plain.toArray(net.minecraft.world.level.ItemLike[]::new)));
		if (custom.size() == 1) return Optional.of(custom.getFirst());
		Ingredient any = DefaultCustomIngredients.any(custom.toArray(Ingredient[]::new));
		return Optional.of(any != null ? any : custom.getFirst());
	}

	// ------------------------------------------------------------------ cache

	/** The hash of the list cached for the server being joined ("" when none): the file's first line, read once. */
	public String cachedHash() {
		Path file = cacheFile();
		if (file.equals(memoPath)) return memoHash;
		String h = "";
		try (InputStream in = Files.newInputStream(file)) {
			String header = header(in);
			if (header != null) h = header;
		} catch (IOException | RuntimeException e) {
			// no cache (or unreadable): ask for the whole list
		}
		memoPath = file;
		memoHash = h;
		return h;
	}

	/** Reads {@code AHR1 <hash>} and its line break from the start of the stream: the hash, or null when it isn't a cache file. */
	private static String header(InputStream in) throws IOException {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 128; i++) {
			int c = in.read();
			if (c < 0) return null;
			if (c == '\n') return b.length() > MAGIC.length() && b.toString().startsWith(MAGIC) ? b.substring(MAGIC.length()) : null;
			b.append((char) c);
		}
		return null;
	}

	private static Path cacheFile() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		String name = server == null ? "local" : server.ip;
		String safe = HexFormat.of().formatHex(name.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
		if (safe.length() > 64) safe = safe.substring(0, 64);
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("aftrhrs").resolve("recipes-" + safe + ".bin");
	}

	/** The cached list (any thread; streams the parse), or null when there is none or it can't be read. */
	private static JsonObject load(Path file) {
		if (!Files.isRegularFile(file)) return null;
		try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
			if (header(raw) == null) return null;
			try (InputStreamReader in = new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8)) {
				JsonElement e = JsonParser.parseReader(in);
				return e.isJsonObject() ? e.getAsJsonObject() : null;
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Caches a new list for this server. The file and hash are taken here (client thread); the JSON is written on
	 * {@link #IO} to a temporary file that then replaces the cache in one move, so a failed write leaves the old one.
	 * Nothing changes {@code m} after it arrived, so it is safe to serialize there.
	 */
	private void save(JsonObject m) {
		Path file = cacheFile();
		String h = Link.str(m, "hash");
		memoPath = file;
		memoHash = h;
		IO.execute(() -> {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try {
				Files.createDirectories(file.getParent());
				try (OutputStream out = Files.newOutputStream(tmp)) {
					out.write((MAGIC + h + "\n").getBytes(StandardCharsets.US_ASCII));
					try (OutputStream gz = new GZIPOutputStream(out)) {
						gz.write(m.toString().getBytes(StandardCharsets.UTF_8));
					}
				}
				try {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				}
				// The old cache format (.json.gz, no hash line) is never read again.
				String name = file.getFileName().toString();
				Files.deleteIfExists(file.resolveSibling(name.substring(0, name.length() - ".bin".length()) + ".json.gz"));
			} catch (IOException e) {
				Companion.LOG.debug("Could not cache server recipes", e);
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
				}
				// The file doesn't hold h after all: forget it, so the next join reads what is really there.
				Minecraft.getInstance().execute(() -> {
					if (file.equals(memoPath) && h.equals(memoHash)) memoPath = null;
				});
			}
		});
	}

	public List<Consumer<ServerRecipes>> listeners() {
		return Collections.unmodifiableList(listeners);
	}
}
