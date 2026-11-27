package net.axther.aftrhrs.feature.storage;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.feature.recipes.RecipeInjector;
import net.kyori.adventure.text.Component;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.TransmuteRecipe;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Quick Craft's engine: the crafting-table recipes of the server (shaped, shapeless and transmute; cached and rebuilt
 * whenever the server's recipe list changes), what a player can make from their inventory plus a network, and the
 * craft itself.
 *
 * <p>Plain ingredients ("any planks") only ever use plain items: a renamed, enchanted or otherwise changed item is
 * never eaten by a recipe. Custom ingredients (a Fabric custom ingredient, e.g. exact components) match by testing.
 *
 * <p>A craft re-reads everything at the click, checks each distinct grid with the server's own recipe lookup (the
 * recipe the table would pick), only then takes the ingredients (putting everything back if anything is missing),
 * and gives the results and the crafting remainders (buckets, bottles) to the player, then the network, then the
 * ground.
 *
 * <p>Auto-Craft ({@link #plan}, {@link #craftDeep}): when ingredients are missing but can be crafted from what is
 * there, a plan of ingredient crafts is worked out from counts, then run as normal crafts, ingredients first.
 */
final class QuickCraft {
    /** One ingredient of a recipe and how many grid slots need it. */
    record Need(Ingredient choice, int count) {
    }

    static final class CachedRecipe {
        final ResourceKey<Recipe<?>> key;
        final ItemStack result;
        final Ingredient[] grid;
        final List<Need> needs;
        final String sortName;
        /** The result is a plain item (no name, lore, model ...), so "any of" ingredients accept it. */
        final boolean plainResult;

        CachedRecipe(ResourceKey<Recipe<?>> key, ItemStack result, Ingredient[] grid, List<Need> needs) {
            this.key = key;
            this.result = result;
            this.grid = grid;
            this.needs = needs;
            this.sortName = result.getHoverName().getString().toLowerCase(Locale.ROOT);
            this.plainResult = result.getComponentsPatch().isEmpty();
        }

        /** The recipe's id, e.g. {@code minecraft:chest} (what the Companion screens send back). */
        String id() {
            return key.identifier().toString();
        }
    }

    /** One step of an Auto-Craft: make {@code recipe} {@code times} times. */
    record Step(CachedRecipe recipe, int times) {
    }

    /** One kind of item the player could use, with how many are in their inventory and in the network. */
    static final class Pool {
        final ItemStack proto;
        final boolean plain;
        long inv;
        long net;

        Pool(ItemStack proto) {
            this.proto = proto;
            this.plain = proto.getComponentsPatch().isEmpty();
        }

        long avail() {
            return inv + net;
        }
    }

    /** Everything available for one refresh (or one craft), with the candidate lists cached per ingredient. */
    static final class Pools {
        final Map<Item, List<Pool>> byType = new IdentityHashMap<>();
        final List<Pool> all = new ArrayList<>();
        final Map<Ingredient, List<Pool>> memo = new IdentityHashMap<>();

        Pool add(ItemStack item, long amount, boolean network) {
            List<Pool> list = byType.computeIfAbsent(item.getItem(), k -> new ArrayList<>(2));
            Pool found = null;
            for (Pool p : list) {
                if (Network.similar(p.proto, item)) {
                    found = p;
                    break;
                }
            }
            if (found == null) {
                found = new Pool(item.copyWithCount(1));
                list.add(found);
                all.add(found);
            }
            if (network) found.net += amount;
            else found.inv += amount;
            return found;
        }

        List<Pool> candidates(Ingredient c) {
            List<Pool> cached = memo.get(c);
            if (cached != null) return cached;
            List<Pool> out = new ArrayList<>();
            if (c.requiresTesting()) {
                for (Pool p : all) {
                    try {
                        if (p.avail() > 0 && c.test(p.proto)) out.add(p);
                    } catch (RuntimeException ignored) {
                    }
                }
            } else {
                c.items().forEach(h -> {
                    for (Pool p : byType.getOrDefault(h.value(), List.of())) if (p.plain && p.avail() > 0 && !out.contains(p)) out.add(p);
                });
            }
            memo.put(c, out);
            return out;
        }
    }

    private final StorageModule module;
    private List<CachedRecipe> recipes = List.of();
    /** The recipes by the item they make (vanilla first, then fewest ingredients), for Auto-Craft. */
    private Map<Item, List<CachedRecipe>> producers = Map.of();
    private final Map<Ingredient, List<CachedRecipe>> producerMemo = new IdentityHashMap<>();
    private Map<String, CachedRecipe> byId = Map.of();
    /** The recipe manager's map the cache was built from ({@link RecipeInjector#aftrhrs$recipeMap}; compared by identity). */
    private Object builtFrom;
    private int builds;
    private long version;
    private int contentHash, contentSize = -1;
    private long lastRebuild;

    QuickCraft(StorageModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------------ recipes

    /** {@link #rebuild} at most once every 10 seconds (the Refresh button; recipes rarely change). */
    void rebuildSoon() {
        if (System.currentTimeMillis() - lastRebuild < 10_000) return;
        rebuild();
    }

    void rebuild() {
        lastRebuild = System.currentTimeMillis();
        MinecraftServer server = module.server();
        if (server == null) return;
        List<CachedRecipe> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        ServerLevel level = server.overworld();
        Object map = ((RecipeInjector) server.getRecipeManager()).aftrhrs$recipeMap();
        int content = 1;
        for (RecipeHolder<?> h : server.getRecipeManager().getRecipes()) {
            try {
                CachedRecipe c = cache(h, level);
                if (c == null) continue;
                // Some recipes are registered twice (same result, same grid): list them once.
                // Every recipe counts (before duplicates are dropped, so which copy wins doesn't matter), by text that is
                // the same on every read: id, result type and count, grid.
                content += (c.id() + "=" + c.result.getItem() + "x" + c.result.getCount() + "+" + c.result.getComponentsPatch().size() + ":"
                        + gridSig(c.grid, true)).hashCode();
                String sig = c.result.getItem() + "x" + c.result.getCount() + ":" + gridSig(c.grid, false) + ":" + c.result.getComponentsPatch();
                if (seen.putIfAbsent(sig, Boolean.TRUE) != null) continue;
                out.add(c);
            } catch (RuntimeException e) {
                module.plugin().getLogger().debug("Quick Craft skipped a recipe: {}", e.toString());
            }
        }
        out.sort(Comparator.comparing((CachedRecipe c) -> c.sortName).thenComparing(c -> c.key.identifier().toString()));
        Map<Item, List<CachedRecipe>> by = new IdentityHashMap<>();
        Map<String, CachedRecipe> ids = new HashMap<>();
        for (CachedRecipe c : out) {
            by.computeIfAbsent(c.result.getItem(), k -> new ArrayList<>(2)).add(c);
            ids.putIfAbsent(c.id(), c);
        }
        // Vanilla recipes first (oak planks from oak logs before some odd custom one), then fewest ingredients.
        for (List<CachedRecipe> list : by.values()) {
            list.sort(Comparator.comparing((CachedRecipe c) -> !c.key.identifier().getNamespace().equals("minecraft"))
                    .thenComparingInt(c -> c.needs.size()));
        }
        recipes = out;
        producers = by;
        byId = ids;
        producerMemo.clear();
        builtFrom = map;
        builds++;
        // Rebuilt only when the recipe list object changed (a reload, /recipes, a module-owned recipe), and that often
        // leaves the crafting recipes as they were: the version (views start over) only bumps when they changed.
        if (content != contentHash || out.size() != contentSize) {
            version++;
            if (contentSize >= 0) module.plugin().getLogger().debug("Quick Craft: the recipes changed ({} now)", out.size());
        }
        contentHash = content;
        contentSize = out.size();
    }

    /**
     * A grid as text (plain ingredients by their items). {@code stable}: the same text on every read (a custom
     * ingredient by its kind and items), for noticing changes; else a custom ingredient counts as itself, so two
     * recipes are only merged when they really share it.
     */
    private static String gridSig(Ingredient[] grid, boolean stable) {
        StringBuilder b = new StringBuilder();
        for (Ingredient c : grid) {
            if (c == null) b.append('-');
            else if (c.requiresTesting() && !stable) b.append("T").append(System.identityHashCode(c));
            else if (c.requiresTesting()) {
                b.append("T").append(c.getCustomIngredient() == null ? "?" : c.getCustomIngredient().getSerializer().getIdentifier()).append(':');
                c.items().forEach(h -> b.append(h.getRegisteredName()).append('|'));
            }
            else c.items().forEach(h -> b.append(h.getRegisteredName()).append('|'));
            b.append(',');
        }
        return b.toString();
    }

    /** Bumps whenever the recipes changed, so views know their cached lists are stale. */
    long version() {
        recipes();
        return version;
    }

    /** The cached recipe with this id, or null. */
    CachedRecipe byId(String id) {
        recipes();
        return id == null ? null : byId.get(id);
    }

    /** How many times the cache was built (game tests). */
    int builds() {
        return builds;
    }

    private List<CachedRecipe> recipes() {
        MinecraftServer s = module.server();
        if (s != null && ((RecipeInjector) s.getRecipeManager()).aftrhrs$recipeMap() != builtFrom) rebuild();
        return recipes;
    }

    /** The recipe's ingredients as 3x3 grid slots (shaped: as drawn, top left; shapeless: in order); null otherwise. */
    static Ingredient[] layout(Recipe<?> r, int size) {
        Ingredient[] grid = new Ingredient[size * size];
        if (r instanceof ShapedRecipe s) {
            if (s.getWidth() > size || s.getHeight() > size) return null;
            List<Optional<Ingredient>> ings = s.getIngredients();
            for (int row = 0; row < s.getHeight(); row++) {
                for (int col = 0; col < s.getWidth(); col++) grid[row * size + col] = ings.get(row * s.getWidth() + col).orElse(null);
            }
            return grid;
        }
        if (r instanceof ShapelessRecipe s) {
            List<Ingredient> list = s.placementInfo().ingredients();
            if (list.isEmpty() || list.size() > size * size) return null;
            for (int i = 0; i < list.size(); i++) grid[i] = list.get(i);
            return grid;
        }
        if (r instanceof TransmuteRecipe t) {
            List<Ingredient> list = t.placementInfo().ingredients();
            if (list.size() < 2) return null;
            grid[0] = list.get(0);
            grid[1] = list.get(1);
            return grid;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static CachedRecipe cache(RecipeHolder<?> h, ServerLevel level) {
        if (!(h.value() instanceof CraftingRecipe recipe)) return null;
        Ingredient[] grid = layout(recipe, 3);
        if (grid == null) return null;
        // The result: what the recipe makes from the first item of each ingredient.
        List<ItemStack> sample = new ArrayList<>(9);
        for (Ingredient c : grid) {
            Optional<Holder<Item>> first = c == null ? Optional.empty() : c.items().findFirst();
            sample.add(first.map(ItemStack::new).orElse(ItemStack.EMPTY));
        }
        ItemStack result = recipe.assemble(CraftingInput.of(3, 3, sample));
        if (result == null || result.isEmpty()) return null;
        Map<Ingredient, Integer> counts = new LinkedHashMap<>();
        for (Ingredient c : grid) if (c != null) counts.merge(c, 1, Integer::sum);
        if (counts.isEmpty()) return null;
        List<Need> needs = new ArrayList<>();
        for (Map.Entry<Ingredient, Integer> e : counts.entrySet()) needs.add(new Need(e.getKey(), e.getValue()));
        return new CachedRecipe((ResourceKey<Recipe<?>>) (ResourceKey<?>) h.id(), result.copy(), grid, needs);
    }

    // ------------------------------------------------------------------ what can be made

    /** The player's main inventory (not armor or off-hand) plus the network, if any. */
    static Pools pools(ServerPlayer p, Network net) {
        Pools pools = new Pools();
        for (ItemStack it : p.getInventory().getNonEquipmentItems()) {
            if (!it.isEmpty()) pools.add(it, it.getCount(), false);
        }
        if (net != null) for (Network.Entry e : net.aggregate()) pools.add(e.proto, e.total, true);
        return pools;
    }

    /** Needs with the fewest options first, so a greedy pick doesn't use up what only one ingredient can use. */
    private static List<Need> ordered(CachedRecipe r, Pools pools) {
        List<Need> list = new ArrayList<>(r.needs);
        list.sort(Comparator.comparingInt(n -> pools.candidates(n.choice()).size()));
        return list;
    }

    private static boolean feasible(CachedRecipe r, Pools pools, long times) {
        Map<Pool, Long> used = new IdentityHashMap<>();
        for (Need n : ordered(r, pools)) {
            long req = n.count() * times;
            for (Pool p : pools.candidates(n.choice())) {
                long left = p.avail() - used.getOrDefault(p, 0L);
                if (left <= 0) continue;
                long take = Math.min(req, left);
                used.merge(p, take, Long::sum);
                req -= take;
                if (req == 0) break;
            }
            if (req > 0) return false;
        }
        return true;
    }

    /** How many times the recipe can be made, at most {@code cap}. */
    static int maxCrafts(CachedRecipe r, Pools pools, int cap) {
        if (cap <= 0 || !feasible(r, pools, 1)) return 0;
        int lo = 1, hi = cap;
        while (lo < hi) {
            int mid = (int) (((long) lo + hi + 1) / 2);
            if (feasible(r, pools, mid)) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /**
     * Whether the player may craft this recipe right now: it still exists on the server (not removed by a reload)
     * and, with limited crafting on, the player has unlocked it.
     */
    boolean usableNow(ServerPlayer p, CachedRecipe r) {
        MinecraftServer server = p.level().getServer();
        if (server == null || server.getRecipeManager().byKey(r.key).isEmpty()) return false;
        return allowed(p).test(r);
    }

    /** Which recipes the player may use: all of them, or with limited crafting only the unlocked ones. */
    static Predicate<CachedRecipe> allowed(ServerPlayer p) {
        boolean limited = p.level().getGameRules().get(GameRules.LIMITED_CRAFTING);
        return limited ? r -> p.getRecipeBook().contains(r.key) : r -> true;
    }

    /** Every recipe the player may use, in name order. */
    List<CachedRecipe> usable(ServerPlayer p) {
        Predicate<CachedRecipe> ok = allowed(p);
        List<CachedRecipe> out = new ArrayList<>();
        for (CachedRecipe r : recipes()) if (ok.test(r)) out.add(r);
        return out;
    }

    /** The recipes the player can make at least once right now from what is there, in name order. */
    List<CachedRecipe> craftable(ServerPlayer p, Pools pools) {
        List<CachedRecipe> out = new ArrayList<>();
        for (CachedRecipe r : usable(p)) if (feasible(r, pools, 1)) out.add(r);
        return out;
    }

    /** Whether the recipe can be made once right now straight from what is there. */
    static boolean direct(CachedRecipe r, Pools pools) {
        return feasible(r, pools, 1);
    }

    /** How many of a recipe's ingredient the player has (inventory plus network), for "missing" lines. */
    static long have(Ingredient c, Pools pools) {
        long n = 0;
        for (Pool p : pools.candidates(c)) n += p.avail();
        return n;
    }

    /** A hash of everything in the pools (kinds and counts), so a view can tell when nothing changed. */
    static long signature(Pools pools) {
        long h = 1;
        for (Pool p : pools.all) {
            h = h * 31 + ItemStack.hashItemAndComponents(p.proto);
            h = h * 31 + p.inv;
            h = h * 31 + p.net;
        }
        return h;
    }

    // ------------------------------------------------------------------ Auto-Craft (recursive crafting)

    /** Most ingredient recipes one plan tries before giving up. */
    static final int BUDGET = 256;

    private static boolean test(Ingredient c, ItemStack item) {
        try {
            return c.test(item);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Recipes that make an item this ingredient accepts (plain results only for "any of" ingredients). */
    private List<CachedRecipe> producersOf(Ingredient c) {
        List<CachedRecipe> cached = producerMemo.get(c);
        if (cached != null) return cached;
        List<CachedRecipe> out = new ArrayList<>();
        boolean testing = c.requiresTesting();
        c.items().forEach(h -> {
            for (CachedRecipe r : producers.getOrDefault(h.value(), List.of())) {
                if (out.contains(r)) continue;
                if (testing ? test(c, r.result) : r.plainResult) out.add(r);
            }
        });
        producerMemo.put(c, out);
        return out;
    }

    /** Whether an ingredient accepts an item that a plan crafts on the way (the same rules as {@link Pools#candidates}). */
    private static boolean accepts(Ingredient c, ItemStack item, boolean plain) {
        if (c.requiresTesting()) return test(c, item);
        return plain && test(c, item);
    }

    /**
     * Per-list filter for Auto-Craft: could this ingredient be had at all within {@code depth} crafting steps, counting
     * nothing (any amount)? A "no" means no plan can work, so the list skips the costly plan.
     */
    final class Reach {
        private record Key(Ingredient c, int depth) {
        }

        private final Pools pools;
        private final Predicate<CachedRecipe> allowed;
        private final Map<Key, Boolean> memo = new HashMap<>();

        Reach(Pools pools, Predicate<CachedRecipe> allowed) {
            this.pools = pools;
            this.allowed = allowed;
        }

        boolean recipe(CachedRecipe r, int depth) {
            for (Need n : r.needs) if (!choice(n.choice(), depth)) return false;
            return true;
        }

        boolean choice(Ingredient c, int depth) {
            if (!pools.candidates(c).isEmpty()) return true;
            if (depth <= 0) return false;
            Key k = new Key(c, depth);
            Boolean cached = memo.get(k);
            if (cached != null) return cached;
            memo.put(k, Boolean.FALSE);
            boolean ok = false;
            for (CachedRecipe r : producersOf(c)) {
                if (allowed.test(r) && recipe(r, depth - 1)) {
                    ok = true;
                    break;
                }
            }
            memo.put(k, ok);
            return ok;
        }

        private final Map<Key, Boolean> makeMemo = new HashMap<>();

        /** Whether Auto-Craft could make more of some ingredient of the recipe (a recipe for it is within reach). */
        boolean more(CachedRecipe r, int depth) {
            for (Need n : r.needs) if (producible(n.choice(), depth)) return true;
            return false;
        }

        private boolean producible(Ingredient c, int depth) {
            if (depth <= 0) return false;
            Key k = new Key(c, depth);
            Boolean cached = makeMemo.get(k);
            if (cached != null) return cached;
            boolean ok = false;
            for (CachedRecipe r : producersOf(c)) {
                if (allowed.test(r) && recipe(r, depth - 1)) {
                    ok = true;
                    break;
                }
            }
            makeMemo.put(k, ok);
            return ok;
        }
    }

    Reach reach(Pools pools, Predicate<CachedRecipe> allowed) {
        return new Reach(pools, allowed);
    }

    /** An item a plan crafts on the way, and how much of it is still unused. */
    private static final class Made {
        final ItemStack proto;
        final boolean plain;
        long left;

        Made(CachedRecipe r, long amount) {
            this.proto = r.result.copyWithCount(1);
            this.plain = r.plainResult;
            this.left = amount;
        }
    }

    /**
     * Works out, counts only, how to make a recipe when some ingredients are missing but can themselves be crafted
     * (up to {@code maxDepth} steps deep, {@link #BUDGET} tries). It never makes an item while it is already making
     * one of the same type (no iron block to ingots to block loops).
     */
    private final class Planner {
        final Pools pools;
        final Predicate<CachedRecipe> allowed;
        final int maxDepth;
        int budget = BUDGET;
        final Map<Pool, Long> used = new IdentityHashMap<>();
        final List<Made> made = new ArrayList<>();
        final List<Step> steps = new ArrayList<>();
        final Map<Item, Integer> producing = new IdentityHashMap<>();

        Planner(Pools pools, Predicate<CachedRecipe> allowed, int maxDepth, Budget shared) {
            this.pools = pools;
            this.allowed = allowed;
            this.maxDepth = maxDepth;
            if (shared != null) budget = Math.min(BUDGET, Math.max(0, shared.left));
        }

        private record Snapshot(Map<Pool, Long> used, long[] made, int steps) {
        }

        Snapshot snapshot() {
            long[] m = new long[made.size()];
            for (int i = 0; i < m.length; i++) m[i] = made.get(i).left;
            return new Snapshot(new IdentityHashMap<>(used), m, steps.size());
        }

        void restore(Snapshot s) {
            used.clear();
            used.putAll(s.used());
            while (made.size() > s.made().length) made.remove(made.size() - 1);
            for (int i = 0; i < s.made().length; i++) made.get(i).left = s.made()[i];
            while (steps.size() > s.steps()) steps.remove(steps.size() - 1);
        }

        boolean make(CachedRecipe r, long times, int depth) {
            if (times <= 0 || times > Integer.MAX_VALUE / 64) return false;
            Item type = r.result.getItem();
            producing.merge(type, 1, Integer::sum);
            try {
                for (Need n : ordered(r, pools)) if (!take(n.choice(), n.count() * times, depth)) return false;
            } finally {
                producing.merge(type, -1, (a, b) -> a + b == 0 ? null : a + b);
            }
            steps.add(new Step(r, (int) times));
            made.add(new Made(r, times * r.result.getCount()));
            return true;
        }

        /**
         * Inside an ingredient craft, items of a type that is being made further up never count as ingredients:
         * no diamonds to block of diamond to diamonds, no planks to sticks to planks.
         */
        private boolean looped(Item type, int depth) {
            return depth > 0 && producing.containsKey(type);
        }

        private long takeMade(Ingredient c, long amount, int depth) {
            long left = amount;
            for (Made m : made) {
                if (left == 0) break;
                if (m.left <= 0 || looped(m.proto.getItem(), depth) || !accepts(c, m.proto, m.plain)) continue;
                long t = Math.min(left, m.left);
                m.left -= t;
                left -= t;
            }
            return left;
        }

        boolean take(Ingredient c, long amount, int depth) {
            long left = amount;
            for (Pool p : pools.candidates(c)) {
                if (looped(p.proto.getItem(), depth)) continue;
                long free = p.avail() - used.getOrDefault(p, 0L);
                if (free <= 0) continue;
                long t = Math.min(left, free);
                used.merge(p, t, Long::sum);
                left -= t;
                if (left == 0) return true;
            }
            left = takeMade(c, left, depth);
            if (left == 0) return true;
            if (depth >= maxDepth) return false;
            for (CachedRecipe r : producersOf(c)) {
                if (budget-- <= 0) return false;
                if (!allowed.test(r) || producing.containsKey(r.result.getItem())) continue;
                long times = (left + r.result.getCount() - 1) / r.result.getCount();
                Snapshot s = snapshot();
                if (make(r, times, depth + 1) && takeMade(c, left, depth) == 0) return true;
                restore(s);
            }
            return false;
        }
    }

    /**
     * The steps to make the recipe {@code times} times, crafting missing ingredients first (they come first in the
     * list, the recipe itself last), or null when it can't be done. {@code maxDepth} 0: no ingredient crafting.
     */
    List<Step> plan(CachedRecipe r, Pools pools, int times, Predicate<CachedRecipe> allowed, int maxDepth) {
        return plan(r, pools, times, allowed, maxDepth, null);
    }

    /**
     * Tries a plan may still spend, shared by every plan of one list (so a huge network with many Auto-Craft recipes
     * can't stall a tick). Each plan also keeps its own {@link #BUDGET}.
     */
    static final class Budget {
        int left;

        Budget(int left) {
            this.left = left;
        }

        boolean spent() {
            return left <= 0;
        }
    }

    /** {@link #plan(CachedRecipe, Pools, int, Predicate, int)} that spends from {@code shared} (null: only its own budget). */
    List<Step> plan(CachedRecipe r, Pools pools, int times, Predicate<CachedRecipe> allowed, int maxDepth, Budget shared) {
        Planner pl = new Planner(pools, allowed, maxDepth, shared);
        int start = pl.budget;
        boolean ok = pl.make(r, times, 0);
        if (shared != null) shared.left -= Math.max(1, start - Math.max(0, pl.budget));
        return ok ? pl.steps : null;
    }

    /** How many times the recipe can be made with ingredient crafting, at most {@code cap}. */
    int maxPlanned(CachedRecipe r, Pools pools, int cap, Predicate<CachedRecipe> allowed, int maxDepth) {
        return maxPlanned(r, pools, cap, allowed, maxDepth, 0, null);
    }

    /**
     * {@link #maxPlanned(CachedRecipe, Pools, int, Predicate, int)} when {@code known} crafts are known to work already,
     * spending from {@code shared}; once that is spent the best count found so far stands.
     */
    int maxPlanned(CachedRecipe r, Pools pools, int cap, Predicate<CachedRecipe> allowed, int maxDepth, int known, Budget shared) {
        if (cap <= 0) return 0;
        if (known >= cap) return cap;
        if (known <= 0 && plan(r, pools, 1, allowed, maxDepth, shared) == null) return 0;
        int lo = Math.max(1, known), hi = cap;
        // Usually Auto-Craft can't add a single craft: one failed plan settles it.
        if (lo < hi && plan(r, pools, lo + 1, allowed, maxDepth, shared) == null) return lo;
        while (lo < hi && (shared == null || !shared.spent())) {
            int mid = (int) (((long) lo + hi + 1) / 2);
            if (plan(r, pools, mid, allowed, maxDepth, shared) != null) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** "Oak Planks" or "Oak Planks (or any of 11)": the name of an ingredient. */
    Component choiceName(Ingredient c) {
        List<Holder<Item>> items = c.items().toList();
        if (items.isEmpty()) return Component.text("?");
        Component first = Adv.adventure(new ItemStack(items.getFirst()).getHoverName());
        return items.size() == 1 || c.requiresTesting() ? first
                : module.text().msg("storage.craft.any", Text.component("item", first), Text.unparsed("count", items.size()));
    }

    // ------------------------------------------------------------------ crafting

    /** What a craft did, for the chat line. {@code steps}: ingredient crafts done first (Auto-Craft). */
    record Outcome(int crafts, int items, String problem, int steps) {
        Outcome(int crafts, int items, String problem) {
            this(crafts, items, problem, 0);
        }
    }

    /**
     * {@link #craft}, but when the ingredients run short and {@code maxDepth} > 0, the missing ones that can be
     * crafted are crafted first (each step a normal, fully checked craft), then the recipe. A step that fails
     * stops the chain; what was made by then stays with the player (or in the network), nothing is lost.
     * {@code toNet}: the final result goes into the network first (the ingredient crafts never do).
     */
    Outcome craftDeep(ServerPlayer p, CachedRecipe r, int wanted, Network net, boolean networkFirst, boolean fitToSpace, int maxDepth, boolean toNet) {
        wanted = Math.max(1, wanted);
        // A list can be stale: the player must still be allowed the recipe, and it must still exist.
        if (!usableNow(p, r)) return new Outcome(0, 0, "blocked");
        if (maxDepth <= 0) return craft(p, r, wanted, net, networkFirst, fitToSpace, toNet);
        Pools pools = pools(p, net);
        int direct = maxCrafts(r, pools, wanted);
        if (direct >= wanted) return craft(p, r, wanted, net, networkFirst, fitToSpace, toNet);
        Predicate<CachedRecipe> ok = allowed(p);
        int times = maxPlanned(r, pools, wanted, ok, maxDepth);
        if (times <= direct) return craft(p, r, wanted, net, networkFirst, fitToSpace, toNet);
        long fits = space(p, r.result, net) / Math.max(1, r.result.getCount());
        if (fits <= 0) return new Outcome(0, 0, "no-room");
        if (fitToSpace) {
            times = (int) Math.min(times, fits);
            if (times <= direct) return craft(p, r, times, net, networkFirst, false, toNet);
        }
        List<Step> steps = plan(r, pools, times, ok, maxDepth);
        if (steps == null) return new Outcome(0, 0, "missing");
        int done = 0;
        for (int i = 0; i < steps.size() - 1; i++) {
            Step s = steps.get(i);
            Outcome o = craft(p, s.recipe(), s.times(), net, networkFirst, false, false);
            if (o.problem() != null || o.crafts() < s.times()) {
                boolean some = done > 0 || o.crafts() > 0;
                return new Outcome(0, 0, some ? "partial" : o.problem() != null ? o.problem() : "missing");
            }
            done++;
        }
        Outcome last = craft(p, r, times, net, networkFirst, false, toNet);
        if (last.problem() != null) return new Outcome(0, 0, done > 0 ? "partial" : last.problem());
        return new Outcome(last.crafts(), last.items(), null, done);
    }

    /**
     * Crafts {@code wanted} times (fewer if the ingredients run out). {@code net} may be null (inventory only).
     * Everything happens now, on the server thread, against the live inventories. {@code toNet}: the results go
     * into the network first, then the inventory (otherwise the other way round).
     */
    Outcome craft(ServerPlayer p, CachedRecipe r, int wanted, Network net, boolean networkFirst, boolean fitToSpace, boolean toNet) {
        // Checked at the moment of the craft (also each of Auto-Craft's ingredient steps), never taken from a list.
        if (!usableNow(p, r)) return new Outcome(0, 0, "blocked");
        Pools pools = pools(p, net);
        int times = maxCrafts(r, pools, Math.max(1, wanted));
        if (times <= 0) return new Outcome(0, 0, "missing");
        long fits = space(p, r.result, net) / Math.max(1, r.result.getCount());
        if (fits <= 0) return new Outcome(0, 0, "no-room");
        if (fitToSpace) times = (int) Math.min(times, fits);

        // 1. Choose the items for each craft (counts only, nothing moves yet).
        Map<Pool, Long> used = new IdentityHashMap<>();
        List<Pool[]> grids = new ArrayList<>(times);
        List<Need> order = ordered(r, pools);
        for (int n = 0; n < times; n++) {
            Pool[] grid = new Pool[9];
            for (Need need : order) {
                List<Pool> cands = pools.candidates(need.choice());
                for (int slot = 0; slot < 9; slot++) {
                    if (r.grid[slot] == null || !r.grid[slot].equals(need.choice())) continue;
                    Pool pick = null;
                    for (Pool c : cands) {
                        if (c.avail() - used.getOrDefault(c, 0L) > 0) {
                            pick = c;
                            break;
                        }
                    }
                    if (pick == null) return new Outcome(0, 0, "missing");
                    used.merge(pick, 1L, Long::sum);
                    grid[slot] = pick;
                }
            }
            grids.add(grid);
        }

        // 2. Ask the server what each distinct grid makes (the recipe a crafting table would pick) and leaves behind.
        Map<List<Pool>, ItemStack> results = new HashMap<>();
        Map<List<Pool>, List<ItemStack>> leftovers = new HashMap<>();
        ServerLevel level = p.level();
        MinecraftServer server = level.getServer();
        for (Pool[] grid : grids) {
            List<Pool> sig = Arrays.asList(grid);
            if (results.containsKey(sig)) continue;
            List<ItemStack> matrix = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) matrix.add(grid[i] == null ? ItemStack.EMPTY : grid[i].proto.copy());
            CraftingInput input = CraftingInput.of(3, 3, matrix);
            ItemStack out = null;
            List<ItemStack> rest = new ArrayList<>();
            try {
                Optional<RecipeHolder<CraftingRecipe>> found = server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
                if (found.isPresent()) {
                    out = found.get().value().assemble(input);
                    NonNullList<ItemStack> remaining = found.get().value().getRemainingItems(input);
                    for (ItemStack it : remaining) if (!it.isEmpty()) rest.add(it.copy());
                }
            } catch (RuntimeException e) {
                module.plugin().getLogger().warn("Quick Craft: the server couldn't check {}: {}", r.key.identifier(), e.toString());
                out = null;
            }
            if (out == null || out.isEmpty() || out.getItem() != r.result.getItem()) return new Outcome(0, 0, "blocked");
            results.put(sig, out.copy());
            leftovers.put(sig, rest);
        }

        // 3. Take the ingredients: the player's inventory and the network, in the player's order.
        List<Runnable> undo = new ArrayList<>();
        for (Map.Entry<Pool, Long> e : used.entrySet()) {
            Pool pool = e.getKey();
            long need = e.getValue();
            long fromNet = net == null ? 0 : networkFirst ? Math.min(need, pool.net) : Math.max(0, need - pool.inv);
            long fromInv = need - fromNet;
            if (fromInv > pool.inv || fromNet > pool.net) {
                rollback(undo);
                return new Outcome(0, 0, "changed");
            }
            int gotInv = takeFromInventory(p.getInventory(), pool.proto, (int) fromInv);
            if (gotInv > 0) undo.add(() -> giveBack(p, pool.proto, gotInv, null));
            int gotNet = fromNet > 0 ? net.extract(pool.proto, (int) fromNet) : 0;
            if (gotNet > 0) undo.add(() -> giveBack(p, pool.proto, gotNet, net));
            if (gotInv < fromInv || gotNet < fromNet) {
                rollback(undo);
                return new Outcome(0, 0, "changed");
            }
        }

        // 4. Results and remainders.
        List<ItemStack> give = new ArrayList<>();
        int made = 0;
        for (Pool[] grid : grids) {
            List<Pool> sig = Arrays.asList(grid);
            ItemStack out = results.get(sig).copy();
            made += out.getCount();
            give.add(out);
            for (ItemStack rest : leftovers.get(sig)) give.add(rest.copy());
        }
        deliver(p, merge(give), net, toNet);
        return new Outcome(times, made, null);
    }

    private static void rollback(List<Runnable> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
    }

    private static void giveBack(ServerPlayer p, ItemStack proto, int amount, Network net) {
        ItemStack back = proto.copyWithCount(amount);
        if (net != null) {
            ItemStack rest = net.insert(back);
            if (rest != null) deliver(p, List.of(rest), null);
        } else deliver(p, List.of(back), null);
    }

    /** Takes up to {@code amount} of the item from the 36 main slots; returns how many. */
    static int takeFromInventory(Inventory inv, ItemStack proto, int amount) {
        int left = amount;
        List<ItemStack> main = inv.getNonEquipmentItems();
        for (int slot = 0; slot < main.size() && left > 0; slot++) {
            ItemStack it = main.get(slot);
            if (it.isEmpty() || !Network.similar(it, proto)) continue;
            int take = Math.min(left, it.getCount());
            left -= take;
            inv.setItem(slot, take >= it.getCount() ? ItemStack.EMPTY : it.copyWithCount(it.getCount() - take));
        }
        return amount - left;
    }

    /** Joins equal stacks so delivery fills slots tidily. */
    private static List<ItemStack> merge(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : items) {
            ItemStack into = null;
            for (ItemStack o : out) {
                if (Network.similar(o, it)) {
                    into = o;
                    break;
                }
            }
            if (into == null) out.add(it.copy());
            else into.setCount(into.getCount() + it.getCount());
        }
        return out;
    }

    /** Free room for the item in the player's main inventory (plus the network). */
    static long space(ServerPlayer p, ItemStack item, Network net) {
        long n = 0;
        int max = Math.max(1, item.getMaxStackSize());
        for (ItemStack it : p.getInventory().getNonEquipmentItems()) {
            if (it.isEmpty()) n += max;
            else if (Network.similar(it, item) && it.getCount() < max) n += max - it.getCount();
        }
        if (net != null) n += net.capacity(item);
        return n;
    }

    /** Adds one stack (at most a stack's size) to the 36 main slots; returns what didn't fit (empty when all did). */
    static ItemStack addToInventory(Inventory inv, ItemStack stack) {
        ItemStack left = stack.copy();
        List<ItemStack> main = inv.getNonEquipmentItems();
        int max = Math.max(1, left.getMaxStackSize());
        for (int slot = 0; slot < main.size() && !left.isEmpty(); slot++) {
            ItemStack it = main.get(slot);
            if (it.isEmpty() || it.getCount() >= max || !Network.similar(it, left)) continue;
            int add = Math.min(left.getCount(), max - it.getCount());
            inv.setItem(slot, it.copyWithCount(it.getCount() + add));
            left.shrink(add);
        }
        for (int slot = 0; slot < main.size() && !left.isEmpty(); slot++) {
            if (!main.get(slot).isEmpty()) continue;
            int add = Math.min(left.getCount(), max);
            inv.setItem(slot, left.copyWithCount(add));
            left.shrink(add);
        }
        return left;
    }

    /**
     * Gives items (any amounts) to the player's inventory; what doesn't fit goes into the network, and what still
     * doesn't fit is dropped at their feet (only they can pick it up). Returns how many were dropped.
     */
    static int deliver(ServerPlayer p, List<ItemStack> items, Network net) {
        return deliver(p, items, net, false);
    }

    /** {@link #deliver(ServerPlayer, List, Network)}, but with {@code toNet} the network is tried before the inventory. */
    static int deliver(ServerPlayer p, List<ItemStack> items, Network net, boolean toNet) {
        int dropped = 0;
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) continue;
            if (toNet && net != null) {
                item = net.insert(item);
                if (item == null || item.isEmpty()) continue;
            }
            for (ItemStack part : split(item)) {
                ItemStack rest = addToInventory(p.getInventory(), part);
                if (rest.isEmpty()) continue;
                ItemStack left = net == null || toNet ? rest : net.insert(rest);
                if (left == null || left.isEmpty()) continue;
                for (ItemStack d : split(left)) {
                    ItemEntity e = new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), d);
                    e.setNoPickUpDelay();
                    e.setTarget(p.getUUID());
                    p.level().addFreshEntity(e);
                    dropped += d.getCount();
                }
            }
        }
        return dropped;
    }

    /** Splits an oversized stack into normal stacks. */
    static List<ItemStack> split(ItemStack item) {
        List<ItemStack> out = new ArrayList<>();
        int max = Math.max(1, item.getMaxStackSize());
        int left = item.getCount();
        while (left > 0) {
            int n = Math.min(max, left);
            out.add(item.copyWithCount(n));
            left -= n;
        }
        return out;
    }
}
