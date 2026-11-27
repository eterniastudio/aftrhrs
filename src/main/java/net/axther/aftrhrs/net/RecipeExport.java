package net.axther.aftrhrs.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Tasks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.TransmuteRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The server's recipes for the client's recipe viewer (JEI), as the {@code recipes} message (port of Feather's
 * {@code RecipeExport}; the client half is {@code client/recipes/ServerRecipes}). Since 1.21.2 a dedicated server no
 * longer sends its recipe list, so JEI on a client only knows vanilla's built-in recipes: this sends every recipe in
 * the server's {@code RecipeManager} (vanilla, the mod's furniture/item recipes, datapacks, the custom recipes
 * module) plus Feather workstations from {@link #source} (cooking meals...). Kept encoded with its hash, and sent on
 * each welcome to clients with the {@code recipes} feature ({@code recipes_ok} when the client cached that hash).
 *
 * <p>Built in the background: the server thread only takes a snapshot (the recipe list, which a change replaces
 * rather than edits, and what the workstation sources export, copied); the {@code aftrhrs-recipe-export} thread turns
 * it into JSON and streams it through gzip ({@link Wire#encodeStreaming}: no multi-MB string), and the result is
 * installed back on the server thread unless something changed meanwhile (then the next build starts). A welcome
 * while a build runs gets the previous list (then the push), or waits for the first one.
 *
 * <p>Rebuilt after a datapack reload and whenever a module calls {@code feather.recipesUpdated()} (this provides
 * {@code Hooks.RecipesChanged} while the companion module runs) or {@link #changed()}; a moved hash is pushed to
 * linked clients. A {@link #changed()} whose snapshot is the same as the last build's (a module reload) builds nothing.
 */
public final class RecipeExport {
    /** Alternatives kept per ingredient slot (big tags would otherwise blow up the message). */
    private static final int MAX_ALTERNATIVES = 96;

    /** A Feather workstation's recipes (stove meals...); called on the server thread when the list is rebuilt. */
    public interface Source {
        void export(Sink sink);
    }

    /** Where a {@link Source} writes. Ingredients are lists of alternatives; {@code station} and {@code note} may be null. */
    public interface Sink {
        void machine(String id, String machine, ItemStack station, List<List<ItemStack>> inputs, ItemStack out, net.kyori.adventure.text.Component note);
    }

    /** One workstation recipe as a source wrote it (private copies: the build reads them on another thread). */
    record Machine(String id, String machine, ItemStack station, List<List<ItemStack>> inputs, ItemStack out, net.kyori.adventure.text.Component note) {
    }

    /** What a build reads, taken on the server thread. */
    record Snapshot(List<RecipeHolder<?>> holders, ContextMap ctx, List<Machine> machines, Object signature) {
    }

    /** A finished build ({@code background}: off the server thread). */
    record Result(List<byte[]> encoded, String hash, int recipes, int machines, long ms, boolean background) {
    }

    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    // Server thread only from here on.
    /** The encoded list (the last one built: also while a newer one is being built), or null until the first. */
    private static List<byte[]> encoded;
    /** Counts changes; {@code builtGen} is the count the current {@link #encoded} was built at. */
    private static long gen;
    private static long builtGen = -1;
    private static String hash = "";
    private static boolean pending;
    private static boolean initialized;
    /** Bumped when the server stops: a build finishing after that is dropped. */
    private static long epoch;
    private static CompletableFuture<?> inFlight;
    private static long inFlightGen = -1;
    /** Players whose welcome came before the first list: they get it once it is built. */
    private static final List<Map.Entry<ServerPlayer, String>> waiting = new ArrayList<>();
    /** The snapshot the installed list was built from (null: the next build can't be skipped). */
    private static Object lastSignature;
    private static ExecutorService executor;
    private static boolean executorBroken;
    /** How long the last snapshot took on the server thread (for the log line). */
    private static long snapshotMs;

    private RecipeExport() {
    }

    /** Called once from {@link Companion#init}: welcome sends and datapack reloads. */
    static void init() {
        if (initialized) return;
        initialized = true;
        Companion.onWelcome((player, hello) -> {
            if (Companion.has(player, "recipes")) sendTo(player, Wire.str(hello, "recipes", ""));
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            // Tags may have changed with the same recipe list: never skipped.
            if (success) server.execute(RecipeExport::recipesChanged);
        });
        // Built once at startup (as Feather did on enable), so the first join doesn't wait for it.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (Companion.running()) startBuild(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            epoch++;
            inFlight = null;
            inFlightGen = -1;
            waiting.clear();
            lastSignature = null;
            encoded = null;
            hash = "";
            pending = false;
            builtGen = -1;
        });
    }

    /**
     * Adds (or replaces, by key) a workstation's recipes. Sources can't be removed (modules re-enable in place), so a
     * source should export nothing while its module is off.
     */
    public static void source(String key, Source source) {
        synchronized (SOURCES) {
            SOURCES.put(key, source);
        }
        changed();
    }

    /**
     * The recipes may have changed: rebuild on the next tick (in the background) and push to linked clients if the
     * list moved. Nothing is built when the snapshot is the same as last time.
     */
    public static void changed() {
        changed(false);
    }

    /** The recipe list changed for sure ({@code feather.recipesUpdated()}, a datapack reload): always rebuilt. */
    public static void recipesChanged() {
        changed(true);
    }

    private static void changed(boolean force) {
        gen++;
        if (force) lastSignature = null;
        if (Feather.serverOrNull() == null) {
            encoded = null;
            return;
        }
        if (pending) return;
        pending = true;
        Tasks.later(1, () -> {
            pending = false;
            // Already covered: e.g. the startup build (SERVER_STARTED) after the sources registered at SERVER_STARTING.
            if (encoded != null && builtGen == gen) return;
            // Never sent and nobody waiting: the first welcome builds it.
            if (hash.isEmpty() && waiting.isEmpty() && inFlight == null) {
                encoded = null;
                return;
            }
            // One running: when it ends it sees the newer gen and starts the next build. The old list stays meanwhile.
            if (inFlight != null) return;
            MinecraftServer server = Feather.serverOrNull();
            if (server != null) startBuild(server);
        });
    }

    /** Sends the list, or {@code recipes_ok} when the client already has this hash; before the first list, queues it. */
    static void sendTo(ServerPlayer player, String clientHash) {
        if (encoded == null) {
            waiting.removeIf(e -> e.getKey() == player);
            waiting.add(Map.entry(player, clientHash == null ? "" : clientHash));
            MinecraftServer server = Feather.serverOrNull();
            if (inFlight == null && server != null) startBuild(server);
            return;
        }
        if (hash.equals(clientHash)) {
            JsonObject ok = Companion.message("recipes_ok");
            ok.addProperty("hash", hash);
            Companion.send(player, ok);
        } else {
            Companion.sendEncoded(player, "recipes", encoded);
        }
    }

    // ------------------------------------------------------------------ building

    private static synchronized ExecutorService executor() {
        if (executor == null && !executorBroken) {
            try {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "aftrhrs-recipe-export");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
            } catch (RuntimeException | Error e) {
                executorBroken = true;
                AftrHrs.LOG.warn("Recipe export: no background thread; building on the server thread", e);
            }
        }
        return executor;
    }

    /** Server thread: takes the snapshot and starts the background build (or skips it when nothing changed). */
    private static void startBuild(MinecraftServer server) {
        long g = gen, ep = epoch;
        long t0 = System.nanoTime();
        Snapshot snap;
        try {
            snap = snapshot(server);
        } catch (RuntimeException e) {
            AftrHrs.LOG.warn("Recipe export: snapshot failed; building on the server thread", e);
            install(server, g, null, buildNow(server));
            return;
        }
        snapshotMs = (System.nanoTime() - t0) / 1_000_000;
        if (encoded != null && snap.signature().equals(lastSignature)) {
            // The same recipes and workstation lists as the installed one: nothing to build.
            builtGen = g;
            drainWaiting();
            return;
        }
        ExecutorService ex = executor();
        if (ex == null) {
            install(server, g, snap.signature(), buildNow(server));
            return;
        }
        inFlightGen = g;
        CompletableFuture<Result> f;
        try {
            f = CompletableFuture.supplyAsync(() -> build(snap), ex);
        } catch (RuntimeException e) {
            AftrHrs.LOG.warn("Recipe export: the background build couldn't start", e);
            inFlightGen = -1;
            install(server, g, snap.signature(), buildNow(server));
            return;
        }
        inFlight = f;
        f.whenComplete((r, t) -> server.execute(() -> publish(server, ep, g, snap.signature(), t == null ? r : null, f)));
    }

    /** Server thread: a background build finished. */
    private static void publish(MinecraftServer server, long ep, long g, Object signature, Result r, CompletableFuture<?> f) {
        if (ep != epoch || Feather.serverOrNull() != server || inFlight != f) return;
        inFlight = null;
        inFlightGen = -1;
        if (g != gen) {
            // Changed meanwhile: this one is already old. The installed list stays until the next is done.
            startBuild(server);
            return;
        }
        if (r == null) {
            AftrHrs.LOG.warn("Recipe export: the background build failed; building on the server thread");
            install(server, g, signature, buildNow(server));
            return;
        }
        install(server, g, signature, r);
    }

    /** Installs a build, pushes a moved list to linked clients and answers the welcomes that waited for it. */
    private static void install(MinecraftServer server, long g, Object signature, Result r) {
        if (r == null) return;
        String before = hash;
        encoded = r.encoded();
        hash = r.hash();
        builtGen = g;
        lastSignature = signature;
        if (r.background()) {
            AftrHrs.LOG.info("Recipe export: {} recipes + {} workstation recipes for JEI ({} ms in the background, {} ms snapshot)", r.recipes(),
                    r.machines(), r.ms(), snapshotMs);
        } else {
            AftrHrs.LOG.info("Recipe export: {} recipes + {} workstation recipes for JEI ({} ms)", r.recipes(), r.machines(), r.ms());
        }
        if (!before.isEmpty() && !hash.equals(before)) {
            for (ServerPlayer p : Companion.moddedWith("recipes")) {
                boolean queued = false;
                for (Map.Entry<ServerPlayer, String> w : waiting) queued |= w.getKey() == p;
                if (!queued) Companion.sendEncoded(p, "recipes", encoded);
            }
        }
        drainWaiting();
    }

    private static void drainWaiting() {
        if (waiting.isEmpty() || encoded == null) return;
        List<Map.Entry<ServerPlayer, String>> todo = new ArrayList<>(waiting);
        waiting.clear();
        for (Map.Entry<ServerPlayer, String> w : todo) {
            ServerPlayer p = w.getKey();
            if (p.hasDisconnected() || !Companion.has(p, "recipes")) continue;
            sendTo(p, w.getValue());
        }
    }

    /**
     * Server thread: what a build reads. The recipe list is copied (a change replaces the manager's map, never edits
     * it, so the holders stay as they are); the workstation sources run now (they read their module's state) into
     * private copies.
     */
    static Snapshot snapshot(MinecraftServer server) {
        List<RecipeHolder<?>> holders = List.copyOf(server.getRecipeManager().getRecipes());
        ContextMap ctx = SlotDisplayContext.fromLevel(server.overworld());
        List<Machine> machines = new ArrayList<>();
        Sink sink = (id, machine, station, inputs, out, note) -> {
            List<List<ItemStack>> in = new ArrayList<>(inputs.size());
            for (List<ItemStack> alts : inputs) {
                List<ItemStack> a = new ArrayList<>(alts.size());
                for (ItemStack s : alts) a.add(s == null ? ItemStack.EMPTY : s.copy());
                in.add(a);
            }
            machines.add(new Machine(id, machine, station == null ? null : station.copy(), in, out == null ? ItemStack.EMPTY : out.copy(), note));
        };
        List<Map.Entry<String, Source>> sources;
        synchronized (SOURCES) {
            sources = List.copyOf(SOURCES.entrySet());
        }
        for (Map.Entry<String, Source> s : sources) {
            try {
                s.getValue().export(sink);
            } catch (RuntimeException e) {
                AftrHrs.LOG.warn("Recipe source '{}' failed", s.getKey(), e);
            }
        }
        long ids = 1;
        for (RecipeHolder<?> h : holders) ids = ids * 31 + System.identityHashCode(h);
        long content = 1;
        for (Machine m : machines) content = content * 31 + machineHash(m);
        return new Snapshot(holders, ctx, List.copyOf(machines), List.of(ids, (long) holders.size(), content, (long) machines.size()));
    }

    private static long machineHash(Machine m) {
        long h = Objects.hash(m.id(), m.machine(), m.note());
        h = h * 31 + stackHash(m.station());
        h = h * 31 + stackHash(m.out());
        for (List<ItemStack> alts : m.inputs()) {
            h = h * 31 + alts.size();
            for (ItemStack s : alts) h = h * 31 + stackHash(s);
        }
        return h;
    }

    private static long stackHash(ItemStack s) {
        return s == null || s.isEmpty() ? 0 : ItemStack.hashItemAndComponents(s) * 31L + s.getCount();
    }

    /** True for what says the build touched state another thread was using (the build is then done on the server thread). */
    private static boolean threadProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConcurrentModificationException) return true;
            if (t instanceof IllegalStateException && t.getMessage() != null && t.getMessage().contains("thread")) return true;
        }
        return false;
    }

    /** Builds and encodes a snapshot (the background thread). Null when it failed (the caller builds on the server thread). */
    static Result build(Snapshot snap) {
        try {
            long started = System.nanoTime();
            Json json = new Json(snap.ctx());
            int[] counts = new int[2];
            String[] digest = new String[1];
            MessageDigest md = sha1();
            List<byte[]> payloads = Companion.encodeStreaming(out -> {
                Writer w = out.writer();
                w.write("{\"t\":\"recipes\",\"recipes\":");
                // The hash covers the "recipes" array only, written exactly as its tree would print.
                if (md != null) out.digest(md);
                JsonWriter jw = new JsonWriter(w);
                jw.setStrictness(Strictness.LENIENT);
                jw.beginArray();
                for (RecipeHolder<?> h : snap.holders()) {
                    JsonObject o;
                    try {
                        o = json.recipe(h);
                    } catch (RuntimeException e) {
                        if (threadProblem(e)) throw e;
                        continue; // one odd recipe must not cost everyone the list
                    }
                    if (o == null) continue;
                    Streams.write(o, jw);
                    counts[0]++;
                }
                for (Machine m : snap.machines()) {
                    JsonObject o = json.machine(m);
                    if (o == null) continue;
                    Streams.write(o, jw);
                    counts[1]++;
                }
                jw.endArray();
                jw.flush();
                out.digest(null);
                digest[0] = md == null ? Long.toHexString(out.bytes()) : HexFormat.of().formatHex(md.digest());
                w.write(",\"hash\":\"" + digest[0] + "\"}");
            });
            return new Result(payloads, digest[0], counts[0], counts[1], (System.nanoTime() - started) / 1_000_000, true);
        } catch (Throwable t) {
            AftrHrs.LOG.warn("Recipe export: background build failed", t);
            return null;
        }
    }

    /**
     * The old way, all on the server thread: the JSON tree, then one string, then gzip. Only used when the background
     * build can't run or failed. The same list and hash as {@link #build}.
     */
    static Result buildNow(MinecraftServer server) {
        long started = System.nanoTime();
        Json json = new Json(SlotDisplayContext.fromLevel(server.overworld()));
        JsonArray arr = new JsonArray();
        int recipes = 0;
        for (RecipeHolder<?> h : server.getRecipeManager().getRecipes()) {
            try {
                JsonObject o = json.recipe(h);
                if (o != null) {
                    arr.add(o);
                    recipes++;
                }
            } catch (RuntimeException e) {
                // One odd recipe must not cost everyone the list.
            }
        }
        int machines = 0;
        for (Machine m : snapshotMachines()) {
            JsonObject o = json.machine(m);
            if (o != null) {
                arr.add(o);
                machines++;
            }
        }
        JsonObject msg = Companion.message("recipes");
        msg.add("recipes", arr);
        String h = sha1(arr.toString());
        msg.addProperty("hash", h);
        return new Result(Companion.encode(msg), h, recipes, machines, (System.nanoTime() - started) / 1_000_000, false);
    }

    /** The workstation recipes as the sources export them now (server thread). */
    private static List<Machine> snapshotMachines() {
        List<Machine> machines = new ArrayList<>();
        Sink sink = (id, machine, station, inputs, out, note) -> machines.add(new Machine(id, machine, station, inputs, out, note));
        List<Map.Entry<String, Source>> sources;
        synchronized (SOURCES) {
            sources = List.copyOf(SOURCES.entrySet());
        }
        for (Map.Entry<String, Source> s : sources) {
            try {
                s.getValue().export(sink);
            } catch (RuntimeException e) {
                AftrHrs.LOG.warn("Recipe source '{}' failed", s.getKey(), e);
            }
        }
        return machines;
    }

    // ------------------------------------------------------------------ one recipe

    /**
     * Turns recipes into JSON for one build. Items and ingredient slots are cached for the build (the same plain item
     * or tag comes up thousands of times); the cached JSON objects are shared, never changed after they are made.
     */
    private static final class Json {
        final ContextMap ctx;
        final Map<Item, JsonObject> plainItems = new IdentityHashMap<>();
        final Map<ItemStack, JsonObject> items = new IdentityHashMap<>();
        final Map<Ingredient, JsonArray> byIngredient = new IdentityHashMap<>();
        final Map<SlotDisplay, JsonArray> byDisplay = new HashMap<>();

        Json(ContextMap ctx) {
            this.ctx = ctx;
        }

        /** {@code Wire.item(stack, 1)}, cached. */
        JsonObject one(ItemStack s) {
            if (s == null || s.isEmpty()) return null;
            if (s.getComponentsPatch().isEmpty()) return plainItems.computeIfAbsent(s.getItem(), k -> Wire.item(s, 1));
            JsonObject j = items.get(s);
            if (j == null && !items.containsKey(s)) {
                j = Wire.item(s, 1);
                items.put(s, j);
            }
            return j;
        }

        JsonObject recipe(RecipeHolder<?> holder) {
            Recipe<?> r = holder.value();
            List<RecipeDisplay> displays = r.display();
            if (displays.isEmpty()) return null;
            // Complex recipes (dyeing, map cloning...) have no fixed result.
            JsonObject out = Wire.item(displays.getFirst().result().resolveForFirstStack(ctx));
            if (out == null) return null;
            JsonObject o = new JsonObject();
            o.addProperty("id", holder.id().identifier().toString());
            switch (r) {
                case ShapedRecipe s -> {
                    int w = s.getWidth(), h = s.getHeight();
                    List<Optional<Ingredient>> ings = s.getIngredients();
                    if (w == 0 || h == 0 || ings.size() != w * h) return null;
                    JsonArray in = new JsonArray();
                    for (Optional<Ingredient> i : ings) in.add(choice(i.orElse(null)));
                    o.addProperty("type", "shaped");
                    o.addProperty("w", w);
                    o.addProperty("h", h);
                    o.addProperty("group", s.group());
                    o.add("in", in);
                }
                case ShapelessRecipe s -> {
                    JsonArray in = new JsonArray();
                    for (Ingredient i : s.placementInfo().ingredients()) in.add(choice(i));
                    if (in.isEmpty()) return null;
                    o.addProperty("type", "shapeless");
                    o.addProperty("group", s.group());
                    o.add("in", in);
                }
                case TransmuteRecipe t -> {
                    // Input + one material (the smallest version, as JEI's first page shows it).
                    List<Ingredient> ings = t.placementInfo().ingredients();
                    if (ings.size() < 2) return null;
                    JsonArray in = new JsonArray();
                    in.add(choice(ings.get(0)));
                    in.add(choice(ings.get(1)));
                    o.addProperty("type", "shapeless");
                    o.addProperty("group", t.group());
                    o.add("in", in);
                }
                case AbstractCookingRecipe c -> {
                    String type = c instanceof BlastingRecipe ? "blasting" : c instanceof SmokingRecipe ? "smoking"
                            : c instanceof CampfireCookingRecipe ? "campfire" : c instanceof SmeltingRecipe ? "smelting" : null;
                    if (type == null) return null;
                    JsonArray in = new JsonArray();
                    in.add(choice(c.input()));
                    o.addProperty("type", type);
                    o.addProperty("group", c.group());
                    o.add("in", in);
                    o.addProperty("xp", c.experience());
                    o.addProperty("time", c.cookingTime());
                }
                case StonecutterRecipe s -> {
                    JsonArray in = new JsonArray();
                    in.add(choice(s.input()));
                    o.addProperty("type", "stonecutting");
                    o.addProperty("group", s.group());
                    o.add("in", in);
                }
                case SmithingTransformRecipe s -> {
                    o.addProperty("type", "smithing");
                    o.add("template", choice(s.templateIngredient().orElse(null)));
                    o.add("base", choice(s.baseIngredient()));
                    o.add("addition", choice(s.additionIngredient().orElse(null)));
                }
                default -> {
                    return null;
                }
            }
            o.add("out", out);
            return o;
        }

        /** One slot: its alternatives (with their components, for exact-match ingredients), or an empty list. */
        JsonArray choice(Ingredient ingredient) {
            if (ingredient == null || ingredient.isEmpty()) return new JsonArray();
            JsonArray cached = byIngredient.get(ingredient);
            if (cached != null) return cached;
            SlotDisplay display = ingredient.display();
            JsonArray a = byDisplay.get(display);
            if (a == null) {
                a = new JsonArray();
                for (ItemStack s : display.resolveForStacks(ctx)) {
                    JsonObject j = one(s);
                    if (j != null && a.size() < MAX_ALTERNATIVES && !a.contains(j)) a.add(j);
                }
                byDisplay.put(display, a);
            }
            byIngredient.put(ingredient, a);
            return a;
        }

        /** A workstation recipe, or null without a result. */
        JsonObject machine(Machine m) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.id());
            o.addProperty("type", "feather:" + m.machine());
            if (m.station() != null) add(o, "station", Wire.item(m.station(), 1));
            JsonArray in = new JsonArray();
            for (List<ItemStack> alts : m.inputs()) {
                JsonArray a = new JsonArray();
                for (ItemStack s : alts) {
                    JsonObject j = Wire.item(s);
                    if (j != null && a.size() < MAX_ALTERNATIVES) a.add(j);
                }
                in.add(a);
            }
            o.add("in", in);
            add(o, "out", Wire.item(m.out()));
            if (m.note() != null) add(o, "note", Wire.text(m.note()));
            return o.has("out") ? o : null;
        }
    }

    private static void add(JsonObject o, String key, JsonElement v) {
        if (v != null) o.add(key, v);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String sha1(String s) {
        MessageDigest md = sha1();
        return md == null ? Long.toHexString(s.getBytes(StandardCharsets.UTF_8).length) : HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ for game tests

    /** The installed list's hash ("" before the first). */
    static String hash() {
        return hash;
    }

    /** Whether a background build is running. */
    static boolean building() {
        return inFlight != null;
    }
}
