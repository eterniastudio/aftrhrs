package net.axther.aftrhrs.feature.storage;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Text;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What one Quick Craft viewer sees: the recipes listed with their options (Search, Show: Craftable / All Recipes,
 * Auto-Craft) and how each one can be made. Shared by the chest window ({@link CraftView}) and the Companion screen
 * ({@link StorageBridge}).
 *
 * <p>The list is worked out again only when the item counts, the recipes or the options changed
 * ({@link #update}); how often a recipe can be made and which ingredients Auto-Craft makes first are worked out
 * once per list, when they are first shown.
 */
final class CraftList {
    /** How a recipe can be made right now. */
    enum State {
        /** From what is there. */
        DIRECT,
        /** Only by crafting some ingredients first (Auto-Craft). */
        DEEP,
        /** Not at all (only listed with Show: All Recipes). */
        NONE
    }

    final StorageModule module;
    boolean showAll;
    boolean auto;
    String query;

    private List<Object> key;
    private List<QuickCraft.CachedRecipe> list = List.of();
    private final Map<QuickCraft.CachedRecipe, State> states = new HashMap<>();
    private final Map<QuickCraft.CachedRecipe, Integer> maxCache = new HashMap<>();
    private final Map<QuickCraft.CachedRecipe, List<QuickCraft.Step>> stepCache = new HashMap<>();
    private int ready;
    /** Planning work one list may spend (states, then the "how many" counts). */
    static final int LIST_BUDGET = 20_000;
    private QuickCraft.Budget budget = new QuickCraft.Budget(LIST_BUDGET);
    private boolean budgetWarned;
    /** The pools and recipe filter of the last {@link #update}, for the lazily worked out numbers. */
    private QuickCraft.Pools pools;
    private Predicate<QuickCraft.CachedRecipe> ok = r -> true;

    CraftList(StorageModule module, ServerPlayer viewer) {
        this.module = module;
        this.showAll = module.showAllOf(viewer);
        this.auto = module.autoCraftOf(viewer);
    }

    /** How deep Auto-Craft goes for this viewer (0: off). */
    int depth() {
        return auto ? module.recursiveDepth() : 0;
    }

    boolean searching() {
        return query != null && !query.isBlank();
    }

    /** Forget the list, so the next {@link #update} works it out again (after a craft, Refresh ...). */
    void invalidate() {
        key = null;
    }

    /** The listed recipes: what can be made first (by name), then with Show: All Recipes the rest. */
    List<QuickCraft.CachedRecipe> list() {
        return list;
    }

    /** How many of the listed recipes can be made. */
    int ready() {
        return ready;
    }

    State state(QuickCraft.CachedRecipe r) {
        return states.getOrDefault(r, State.NONE);
    }

    /** The listed recipe with this id, or null. */
    QuickCraft.CachedRecipe find(String id) {
        if (id == null) return null;
        for (QuickCraft.CachedRecipe r : list) if (r.id().equals(id)) return r;
        return null;
    }

    /**
     * Works out which recipes to list (and how each can be made), unless nothing changed since last time.
     * True when the list was worked out again.
     */
    boolean update(ServerPlayer p, QuickCraft.Pools pools) {
        Predicate<QuickCraft.CachedRecipe> ok = QuickCraft.allowed(p);
        int depth = depth();
        QuickCraft craft = module.craft();
        List<Object> k = Arrays.asList(QuickCraft.signature(pools), craft.version(), showAll, depth, searching() ? query.trim().toLowerCase(Locale.ROOT) : null);
        this.pools = pools;
        this.ok = ok;
        if (k.equals(key)) return false;
        key = k;
        budget = new QuickCraft.Budget(LIST_BUDGET);
        states.clear();
        maxCache.clear();
        stepCache.clear();
        String q = searching() ? query.toLowerCase(Locale.ROOT).trim() : null;
        this.reach = null;
        QuickCraft.Reach reach = depth > 0 ? reach() : null;
        List<QuickCraft.CachedRecipe> can = new ArrayList<>(), cannot = new ArrayList<>();
        for (QuickCraft.CachedRecipe r : craft.usable(p)) {
            if (q != null && !StorageView.matches(r.result, q)) continue;
            State st;
            if (QuickCraft.direct(r, pools)) st = State.DIRECT;
            else if (reach != null && !budget.spent() && reach.recipe(r, depth) && craft.plan(r, pools, 1, ok, depth, budget) != null) st = State.DEEP;
            else st = State.NONE;
            if (st == State.NONE && !showAll) continue;
            states.put(r, st);
            (st == State.NONE ? cannot : can).add(r);
        }
        ready = can.size();
        can.addAll(cannot);
        list = can;
        if (budget.spent() && !budgetWarned) {
            budgetWarned = true;
            module.plugin().getLogger().warn("Quick Craft: {}'s recipe list hit its planning limit; some Auto-Craft recipes may not show", p.getScoreboardName());
        }
        return true;
    }

    /** How many times the recipe can be made now (with Auto-Craft: counting ingredient crafts), at most max-batch. */
    int max(QuickCraft.CachedRecipe r) {
        State st = state(r);
        if (st == State.NONE) return 0;
        Integer cached = maxCache.get(r);
        if (cached != null) return cached;
        int cap = module.maxBatch();
        int direct = st == State.DIRECT ? QuickCraft.maxCrafts(r, pools, cap) : 0;
        int max = direct;
        // With Auto-Craft more may be possible, but only when some ingredient has a recipe within reach.
        // Once the list's planning budget is spent, the direct count stands (DEEP recipes show at least 1).
        if (depth() > 0 && direct < cap && !budget.spent() && (st == State.DEEP || reach().more(r, depth()))) {
            max = module.craft().maxPlanned(r, pools, cap, ok, depth(), direct, budget);
        }
        if (st == State.DEEP) max = Math.max(1, max);
        maxCache.put(r, max);
        return max;
    }

    private QuickCraft.Reach reach;

    private QuickCraft.Reach reach() {
        if (reach == null) reach = module.craft().reach(pools, ok);
        return reach;
    }

    /** The ingredient crafts Auto-Craft does before one craft of the recipe (empty when none are needed). */
    List<QuickCraft.Step> firstSteps(QuickCraft.CachedRecipe r) {
        if (state(r) != State.DEEP) return List.of();
        List<QuickCraft.Step> steps = stepCache.computeIfAbsent(r, k -> {
            List<QuickCraft.Step> s = module.craft().plan(k, pools, 1, ok, depth());
            return s == null ? List.of() : s;
        });
        return steps.size() > 1 ? steps.subList(0, steps.size() - 1) : List.of();
    }

    /**
     * The tooltip lines Quick Craft adds under a recipe's own tooltip: what it makes, the ingredients (missing ones in
     * red with what you have), the ingredient crafts that come first, and how often it can be made with the click
     * hints ({@code amount}: a left-click's crafts; {@code store}: there is a storage to shift-click into).
     */
    List<Component> lines(QuickCraft.CachedRecipe r, int amount, boolean store) {
        Text text = module.text();
        State st = state(r);
        List<Component> out = new ArrayList<>(text.lines("storage.craft.recipe-head", Text.unparsed("count", r.result.getCount())));
        for (QuickCraft.Need n : r.needs) {
            Component name = module.craft().choiceName(n.choice());
            long have = st == State.NONE ? QuickCraft.have(n.choice(), pools) : Long.MAX_VALUE;
            if (have < n.count()) {
                out.add(text.itemMsg("storage.craft.ingredient-missing", Text.unparsed("amount", n.count()), Text.component("item", name),
                        Text.unparsed("have", have)));
            } else {
                out.add(text.itemMsg("storage.craft.ingredient", Text.unparsed("amount", n.count()), Text.component("item", name)));
            }
        }
        if (st == State.NONE) {
            out.addAll(text.lines("storage.craft.recipe-foot-missing"));
            return out;
        }
        List<QuickCraft.Step> first = firstSteps(r);
        if (!first.isEmpty()) {
            out.addAll(text.lines("storage.craft.first-head"));
            for (QuickCraft.Step s : first) {
                out.add(text.itemMsg("storage.craft.first-step", Text.unparsed("amount", s.times() * s.recipe().result.getCount()),
                        Text.component("item", Adv.adventure(s.recipe().result.getHoverName()))));
            }
        }
        int max = max(r);
        out.addAll(text.lines("storage.craft.recipe-foot", Text.unparsed("max", max >= module.maxBatch() ? max + "+" : String.valueOf(max)),
                Text.unparsed("amount", amount)));
        if (store) out.addAll(text.lines("storage.craft.recipe-foot-store"));
        return out;
    }

    /**
     * Crafts a recipe for the viewer and tells them how it went (chat line and sound). {@code all}: as many as fit
     * (right-click), else {@code amount} times; {@code store}: the results go into the storage first (shift).
     */
    QuickCraft.Outcome craft(ServerPlayer p, QuickCraft.CachedRecipe r, Network net, int amount, boolean all, boolean store) {
        if (net == null) store = false;
        int times = all ? module.maxBatch() : amount;
        QuickCraft.Outcome o = module.craft().craftDeep(p, r, times, net, module.networkFirstOf(p), all, depth(), store);
        Text text = module.text();
        if (o.problem() != null) {
            text.send(p, "storage.craft.problem-" + o.problem());
            module.error(p);
        } else {
            String count = String.format(Locale.US, "%,d", o.items());
            Component item = Adv.adventure(r.result.getHoverName());
            if (o.steps() > 0) {
                text.send(p, store ? "storage.craft.crafted-auto-stored" : "storage.craft.crafted-auto", Text.unparsed("count", count),
                        Text.component("item", item), Text.unparsed("steps", o.steps()));
            } else {
                text.send(p, store ? "storage.craft.crafted-stored" : "storage.craft.crafted", Text.unparsed("count", count), Text.component("item", item));
            }
            module.crafted(p);
        }
        invalidate();
        return o;
    }
}
