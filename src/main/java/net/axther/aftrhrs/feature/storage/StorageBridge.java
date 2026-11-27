package net.axther.aftrhrs.feature.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.net.Companion;
import net.axther.aftrhrs.net.Wire;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Storage terminals for Companion players (docs/companion-protocol.md, "Storage terminals"). A Storage Terminal
 * becomes the mod's network screen; a Crafting Terminal opens a real crafting table that the mod draws with the
 * network beside it, so JEI/REI can fill the grid from the network. Every request re-reads the chests and moves real
 * items, exactly like the chest window; nothing the client says about counts is trusted.
 */
final class StorageBridge {
    /** One open terminal: its item kinds keyed for the client, and the crafting window when there is one. */
    private static final class Session {
        final int id;
        final StorageBlock.Terminal.At terminal;
        final boolean crafting;
        /** Opened with a remote: its tier (0: at the terminal) and link. */
        int remote;
        String link;
        /** The "who changed what" key of each item kind, and the newest change last sent for it. */
        final Map<Integer, String> logKey = new HashMap<>();
        final Map<Integer, Long> logAt = new HashMap<>();
        /** Each kind's item as sent ({@link Wire#item}: encoding it is the costly part, and a key's item never changes). */
        final Map<Integer, JsonObject> itemJson = new HashMap<>();
        /**
         * The client agreed on {@code storage_lean}: an entry for a kind it already holds leaves out {@code "item"}.
         * {@code sent}: the kinds whose item went out and that the client still holds (a removed kind goes again).
         */
        boolean lean;
        final Set<Integer> sent = new HashSet<>();
        /** {@link StorageLog#version} and the network's storage count at the last send: unchanged, the log needs no read. */
        long logVersion = -1;
        int sourceCount = -1;
        /** The storage blocks the last send showed: changes there through another window send again. */
        StorageModule.Touched lastSeen;
        /** The server tick of the last full look at the network (idle sessions still look every full-refresh-ticks). */
        long lastFullSend;
        /** {@link View#inventoryStamp} and Quick Craft's recipe version at the last Quick Craft list: either moving looks again. */
        int invStamp;
        long recipeStamp = -1;
        AbstractContainerMenu view;
        final Map<Integer, Network.Entry> known = new LinkedHashMap<>();
        final Map<Item, List<Integer>> byType = new IdentityHashMap<>();
        int nextKey;
        long lastOp;
        /** Quick Craft in the terminal's grid (the panel's Craftable / All Recipes modes), or null while it shows items. */
        CraftList craft;
        int craftAmount = 1;
        /** When the player last crafted here: for {@link StorageView#HOLD_MS} the recipes keep their places. */
        long craftAction;
        /** The order last sent (null: a gap kept open by the hold), and whether that was a held order. */
        List<QuickCraft.CachedRecipe> craftShown = new ArrayList<>();
        boolean craftHeld;
        int craftVersion;
        long infoSecond;
        int infoCount;
        // Rebuilds are throttled: at most one list per CRAFT_PUSH_MS; changes in between are merged into one flush.
        long craftPushedAt;
        boolean craftForce, craftFlushQueued;
        long lastCraft;

        Session(int id, StorageBlock.Terminal.At terminal, boolean crafting) {
            this.id = id;
            this.terminal = terminal;
            this.crafting = crafting;
        }

        /** The key of an item kind, made on first sight. */
        int keyOf(ItemStack proto) {
            List<Integer> keys = byType.computeIfAbsent(proto.getItem(), k -> new ArrayList<>(2));
            for (int k : keys) if (Network.similar(known.get(k).proto, proto)) return k;
            int k = nextKey++;
            known.put(k, new Network.Entry(proto));
            keys.add(k);
            return k;
        }
    }

    private static volatile StorageBridge active;
    /** Quick Craft lists go out (and are rebuilt) at most this often per session. */
    static final long CRAFT_PUSH_MS = 300;
    /** At most one Quick Craft craft per session this often. */
    static final long CRAFT_OP_MS = 100;

    private final StorageModule module;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastFill = new HashMap<>();
    private int nextId;

    StorageBridge(StorageModule module) {
        this.module = module;
    }

    /** Wires the Companion messages to whichever bridge is running (handlers are global; registered once). */
    void start() {
        active = this;
        // Storage deltas without the item JSON the client already has (docs/companion-protocol.md).
        Companion.addFeature("storage_lean", "storage");
        Companion.on("st_take", "storage", (p, m) -> run(b -> b.take(p, m)));
        Companion.on("st_put", "storage", (p, m) -> run(b -> b.put(p, m)));
        Companion.on("st_deposit_all", "storage", (p, m) -> run(b -> b.depositAll(p, m)));
        Companion.on("st_fill", "storage", (p, m) -> run(b -> b.fill(p, m)));
        Companion.on("craft_fill", "craftfill", (p, m) -> run(b -> b.craftFill(p, m)));
        Companion.on("st_close", "storage", (p, m) -> run(b -> {
            Session s = b.session(p, m);
            if (s != null) b.end(p, s, false);
        }));
        Companion.on("qc_open", "storage", (p, m) -> run(b -> b.craftOpen(p, m)));
        Companion.on("qc_options", "storage", (p, m) -> run(b -> b.craftOptions(p, m)));
        Companion.on("qc_info", "storage", (p, m) -> run(b -> b.craftInfo(p, m)));
        Companion.on("qc_craft", "storage", (p, m) -> run(b -> b.craftRecipe(p, m)));
        Companion.on("qc_close", "storage", (p, m) -> run(b -> {
            Session s = b.session(p, m, false);
            if (s != null) s.craft = null;
        }));
        module.plugin().once("storage.companion-quit", () -> Companion.onQuit(p -> run(b -> {
            b.sessions.remove(p.getUUID());
            b.lastFill.remove(p.getUUID());
        })));
    }

    void stop() {
        closeAll();
        if (active == this) active = null;
    }

    private static void run(java.util.function.Consumer<StorageBridge> r) {
        StorageBridge b = active;
        if (b != null) r.accept(b);
    }

    /** Opens the terminal the Companion way; false when the player should get the chest window. */
    boolean offer(ServerPlayer p, StorageBlock.Terminal.At t) {
        if (!Companion.has(p, "storage")) return false;
        Session old = sessions.remove(p.getUUID());
        if (old != null && old.view != null && p.containerMenu == old.view) p.closeContainer();
        boolean crafting = t.kind() == Kind.CRAFTING;
        Session s = new Session(++nextId, t, crafting);
        s.lean = Companion.has(p, "storage_lean");
        sessions.put(p.getUUID(), s);
        // The list goes first: the mod attaches it to the crafting screen that opens right after.
        Companion.send(p, full(p, s));
        if (crafting) {
            p.closeContainer();
            WorkbenchMenu view = module.openTerminalWorkbench(p, t);
            if (view == null) {
                sessions.remove(p.getUUID());
                closed(p, s);
                return false;
            }
            s.view = view;
        }
        return true;
    }

    /**
     * A Crafting Terminal Remote opens this terminal from afar: always as a Crafting Terminal (network panel, grid and
     * Quick Craft). False when the player should get the chest window.
     */
    boolean offerRemote(ServerPlayer p, StorageBlock.Terminal.At t, int tier, String link) {
        if (!Companion.has(p, "storage")) return false;
        Session old = sessions.remove(p.getUUID());
        if (old != null && old.view != null && p.containerMenu == old.view) p.closeContainer();
        Session s = new Session(++nextId, t, true);
        s.lean = Companion.has(p, "storage_lean");
        s.remote = tier;
        s.link = link;
        sessions.put(p.getUUID(), s);
        Companion.send(p, full(p, s));
        p.closeContainer();
        WorkbenchMenu view = module.openTerminalWorkbench(p, t, tier, link);
        if (view == null) {
            sessions.remove(p.getUUID());
            closed(p, s);
            return false;
        }
        s.view = view;
        return true;
    }

    /**
     * Whether {@code craft_fill} may fill this grid: the player's own 2x2 grid, a crafting table Feather opened, or a
     * real crafting table block in reach. Never another mod's screen that happens to be a crafting grid.
     */
    private static boolean fillable(ServerPlayer p, AbstractContainerMenu menu) {
        if (menu == p.inventoryMenu) return true;
        if (menu instanceof WorkbenchMenu || StorageModule.trustedWorkbench(menu)) return true;
        return menu.getClass() == CraftingMenu.class && menu.stillValid(p);
    }

    // ------------------------------------------------------------------ sending

    private JsonObject full(ServerPlayer p, Session s) {
        Network net = module.network(p, s.terminal, true);
        s.lastSeen = StorageModule.touched(s.terminal, net);
        s.lastFullSend = Tasks.now();
        s.logVersion = StorageLog.version();
        s.sourceCount = net.sources.size();
        JsonObject m = Companion.message("storage");
        m.addProperty("s", s.id);
        m.addProperty("kind", s.crafting ? "crafting" : "storage");
        JsonElement title = Wire.text(module.text().msg(s.remote > 0 ? "storage.view.title-remote" : s.crafting ? "storage.view.title-crafting" : "storage.view.title"));
        if (title != null) m.add("title", title);
        JsonArray items = new JsonArray();
        List<Network.Entry> all = net.aggregate();
        Set<Integer> present = new HashSet<>();
        Map<String, List<StorageLog.Change>> logs = StorageLog.collect(net, LOG_LINES);
        for (Network.Entry e : all) {
            int k = s.keyOf(e.proto);
            present.add(k);
            s.known.get(k).total = e.total;
            List<StorageLog.Change> log = logs.get(logKey(s, k, e.proto));
            s.logAt.put(k, log == null || log.isEmpty() ? 0L : log.getFirst().at());
            items.add(entry(s, k, e, log));
        }
        // Kinds that are no longer there.
        for (Map.Entry<Integer, Network.Entry> e : s.known.entrySet()) if (!present.contains(e.getKey())) e.getValue().total = 0;
        m.add("items", items);
        m.add("info", info(net, all));
        // Quick Craft lives in the terminal's own grid; the player's remembered choices come along. No field: off.
        if (!module.craftInTerminals()) return m;
        JsonObject qc = new JsonObject();
        qc.addProperty("depth", module.recursiveDepth());
        qc.addProperty("auto", module.autoCraftOf(p));
        qc.addProperty("all", module.showAllOf(p));
        qc.addProperty("amount", module.craftAmountOf(p));
        JsonArray amounts = new JsonArray();
        for (int a : CraftView.AMOUNTS) amounts.add(a);
        qc.add("amounts", amounts);
        m.add("quickcraft", qc);
        return m;
    }

    /** Changes per item kind sent with the items (the tooltip shows them). */
    static final int LOG_LINES = 3;

    private static String logKey(Session s, int k, ItemStack proto) {
        return s.logKey.computeIfAbsent(k, x -> StorageLog.key(proto));
    }

    private static JsonObject entry(Session s, int k, Network.Entry e, List<StorageLog.Change> log) {
        JsonObject o = new JsonObject();
        o.addProperty("k", k);
        // storage_lean: the client keeps each kind's item until the kind is removed, so it goes once.
        if (!s.lean || !s.sent.contains(k)) {
            JsonObject item = itemJson(s, k, e.proto);
            o.add("item", item);
            if (item != null) s.sent.add(k);
        }
        o.addProperty("total", e.total);
        if (log != null && !log.isEmpty()) {
            // Who changed it last: names, signed amounts and epoch-ms times (the client shows them in its own time zone).
            JsonArray arr = new JsonArray();
            for (StorageLog.Change c : log) {
                JsonObject l = new JsonObject();
                l.addProperty("who", c.who());
                l.addProperty("d", c.delta());
                l.addProperty("at", c.at());
                arr.add(l);
            }
            o.add("log", arr);
        }
        return o;
    }

    /**
     * The kind's item for the client, encoded once per session: a key's item never changes (keys are only ever made),
     * and nothing changes a sent JSON object. A patched item that could only be sent as its bare id is not kept.
     */
    private static JsonObject itemJson(Session s, int k, ItemStack proto) {
        JsonObject j = s.itemJson.get(k);
        if (j == null) {
            j = Wire.item(proto, 1);
            if (j != null && (proto.getComponentsPatch().isEmpty() || j.has("b"))) s.itemJson.put(k, j);
        }
        return j;
    }

    private JsonObject info(Network net, List<Network.Entry> all) {
        JsonObject o = new JsonObject();
        o.addProperty("containers", net.containers);
        o.addProperty("connectors", net.connectors);
        o.addProperty("used", net.usedSlots());
        o.addProperty("slots", net.totalSlots());
        o.addProperty("kinds", all.size());
        o.addProperty("truncated", net.truncated);
        o.addProperty("max", module.maxBlocks());
        return o;
    }

    /** Sends what changed since the last look (also called on the module's refresh timer). */
    private void delta(ServerPlayer p, Session s) {
        Network net = module.network(p, s.terminal, false);
        s.lastSeen = StorageModule.touched(s.terminal, net);
        s.lastFullSend = Tasks.now();
        List<Network.Entry> all = net.aggregate();
        Map<Integer, Long> now = new HashMap<>();
        for (Network.Entry e : all) now.merge(s.keyOf(e.proto), e.total, Long::sum);
        // Nothing moved, nothing was written down and the same storage is there: nothing to send (an idle open
        // terminal costs no log reading).
        long version = StorageLog.version();
        if (version == s.logVersion && net.sources.size() == s.sourceCount && sameTotals(s, now)) return;
        s.logVersion = version;
        s.sourceCount = net.sources.size();
        Map<String, List<StorageLog.Change>> logs = StorageLog.collect(net, LOG_LINES);
        JsonArray set = new JsonArray(), remove = new JsonArray();
        for (Map.Entry<Integer, Network.Entry> e : s.known.entrySet()) {
            long was = e.getValue().total;
            long is = now.getOrDefault(e.getKey(), 0L);
            List<StorageLog.Change> log = is > 0 ? logs.get(logKey(s, e.getKey(), e.getValue().proto)) : null;
            long logAt = log == null || log.isEmpty() ? 0L : log.getFirst().at();
            // A change can leave the total as it was (+1 and -1): a newer log line still goes out.
            if (was == is && logAt == s.logAt.getOrDefault(e.getKey(), 0L)) continue;
            e.getValue().total = is;
            s.logAt.put(e.getKey(), logAt);
            if (is <= 0) {
                remove.add(e.getKey());
                s.sent.remove(e.getKey());
            } else set.add(entry(s, e.getKey(), e.getValue(), log));
        }
        if (set.isEmpty() && remove.isEmpty()) return;
        JsonObject m = Companion.message("storage_delta");
        m.addProperty("s", s.id);
        m.add("set", set);
        m.add("remove", remove);
        m.add("info", info(net, all));
        Companion.send(p, m);
    }

    private static boolean sameTotals(Session s, Map<Integer, Long> now) {
        for (Map.Entry<Integer, Network.Entry> e : s.known.entrySet()) if (e.getValue().total != now.getOrDefault(e.getKey(), 0L)) return false;
        return true;
    }

    private void closed(ServerPlayer p, Session s) {
        JsonObject m = Companion.message("storage_closed");
        m.addProperty("s", s.id);
        Companion.send(p, m);
    }

    /** Ends a session; {@code tell}: the server ended it (the mod closes its screen). */
    private void end(ServerPlayer p, Session s, boolean tell) {
        sessions.remove(p.getUUID(), s);
        if (tell) closed(p, s);
        if (s.view != null && p.containerMenu == s.view) p.closeContainer();
    }

    /**
     * The module's refresh: drop sessions that went away, send changes to the rest. A session only looks at its network
     * again when something it shows may have changed ({@link StorageModule.Changes}), its Quick Craft list also when
     * the player's inventory or the recipes changed or a hold ended, and otherwise on its full-refresh-ticks pass.
     */
    void tick(StorageModule.Changes changes) {
        long ms = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            ServerPlayer p = module.plugin().player(e.getKey());
            Session s = e.getValue();
            if (p == null) {
                sessions.remove(e.getKey());
                continue;
            }
            if (s.crafting && (s.view == null || p.containerMenu != s.view)) {
                // The crafting window was closed (Escape, another window): the session ends with it.
                sessions.remove(e.getKey(), s);
                continue;
            }
            String why = module.problem(p, s.terminal, s.remote, s.link);
            if (why != null) {
                module.text().send(p, why);
                end(p, s, true);
                continue;
            }
            try {
                boolean due = changes.concern(s.lastSeen, s.terminal) || module.safetyDue(e.getKey(), s.lastFullSend);
                if (due) delta(p, s);
                if (due || (s.craft != null && (s.invStamp != View.inventoryStamp(p) || s.recipeStamp != module.craft().version()
                        || (s.craftHeld && ms - s.craftAction >= StorageView.HOLD_MS)))) pushCraftSoon(p, s, false);
            } catch (RuntimeException ex) {
                module.plugin().getLogger().warn("Companion storage refresh failed", ex);
            }
        }
    }

    /**
     * Changes made through other windows or sessions ({@link StorageModule#refreshSoon}): every session showing any of
     * that storage sends what changed. Sessions that went away are left to {@link #tick}.
     */
    void refreshTouched(List<StorageModule.Pending> changes) {
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            Session s = e.getValue();
            if (!StorageModule.concerns(changes, s.lastSeen, s)) continue;
            ServerPlayer p = module.plugin().player(e.getKey());
            if (p == null || (s.crafting && (s.view == null || p.containerMenu != s.view))) continue;
            try {
                delta(p, s);
                pushCraftSoon(p, s, false);
            } catch (RuntimeException ex) {
                module.plugin().getLogger().warn("Companion storage refresh failed", ex);
            }
        }
    }

    /** Closes every session (reload, shutdown). */
    void closeAll() {
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            ServerPlayer p = module.plugin().player(e.getKey());
            if (p != null) end(p, e.getValue(), true);
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------ requests

    /** The player's open session named by the message, still valid; null otherwise (the request is ignored). */
    private Session session(ServerPlayer p, JsonObject m) {
        return session(p, m, true);
    }

    /** {@code op}: the request moves items, so at most one per 25 ms counts (choices and tooltips don't). */
    private Session session(ServerPlayer p, JsonObject m, boolean op) {
        Session s = sessions.get(p.getUUID());
        if (s == null || s.id != Wire.num(m, "s", -1)) return null;
        if (s.crafting && (s.view == null || p.containerMenu != s.view)) return null;
        if (p.isDeadOrDying() || !Perms.has(p, StorageModule.USE)) return null;
        String why = module.problem(p, s.terminal, s.remote, s.link);
        if (why != null) {
            module.text().send(p, why);
            end(p, s, true);
            return null;
        }
        if (!op) return s;
        long now = System.currentTimeMillis();
        if (now - s.lastOp < 25) return null; // a flood of clicks: one per tick is plenty
        s.lastOp = now;
        return s;
    }

    /** After a request through {@code net}: this session sends what changed now, other windows on that storage next tick. */
    private void after(ServerPlayer p, Session s, boolean moved, Network net) {
        if (moved) module.click(p);
        delta(p, s);
        pushCraftSoon(p, s, false);
        if (moved) module.refreshSoon(StorageModule.touched(s.terminal, net), s);
        Tasks.sync(() -> p.containerMenu.broadcastChanges());
    }

    // ------------------------------------------------------------------ Quick Craft in the terminal

    /** {@code qc_open}: the panel switched to Craftable or All Recipes. */
    private void craftOpen(ServerPlayer p, JsonObject m) {
        Session s = session(p, m, false);
        if (s == null || !module.craftInTerminals()) return;
        if (s.craft == null) {
            s.craft = new CraftList(module, p);
            s.craftAmount = module.craftAmountOf(p);
            s.craftShown = new ArrayList<>();
            s.craftAction = 0;
        }
        applyOptions(p, s, m);
        pushCraftSoon(p, s, true);
    }

    /** {@code qc_options}: search text, Show, Auto-Craft or the craft amount changed. Bad values are ignored. */
    private void craftOptions(ServerPlayer p, JsonObject m) {
        Session s = session(p, m, false);
        if (s == null || s.craft == null) return;
        applyOptions(p, s, m);
        pushCraftSoon(p, s, true);
    }

    private void applyOptions(ServerPlayer p, Session s, JsonObject m) {
        CraftList c = s.craft;
        if (m.has("q")) {
            String q = Wire.str(m, "q", "").trim();
            if (q.length() > 50) q = q.substring(0, 50);
            c.query = q.isEmpty() ? null : q;
        }
        if (m.has("all") && m.get("all").isJsonPrimitive() && m.get("all").getAsJsonPrimitive().isBoolean()) {
            boolean all = Wire.bool(m, "all", c.showAll);
            if (all != c.showAll) module.showAllOf(p, all);
            c.showAll = all;
        }
        if (m.has("auto") && module.recursiveDepth() > 0 && m.get("auto").isJsonPrimitive() && m.get("auto").getAsJsonPrimitive().isBoolean()) {
            boolean auto = Wire.bool(m, "auto", c.auto);
            if (auto != c.auto) module.autoCraftOf(p, auto);
            c.auto = auto;
        }
        if (m.has("amount")) {
            int a = Wire.num(m, "amount", -1);
            for (int ok : CraftView.AMOUNTS) {
                if (ok == a) {
                    if (a != s.craftAmount) module.craftAmountOf(p, a);
                    s.craftAmount = a;
                }
            }
        }
        // A new choice is a new list: no holding the old places.
        s.craftAction = 0;
    }

    /**
     * {@link #pushCraft}, throttled: right away when the last list went out at least {@link #CRAFT_PUSH_MS} ago, else
     * once when that time is up (everything that changed meanwhile goes out together). A flood of option changes,
     * takes or crafts never rebuilds the list more than a few times a second.
     */
    private void pushCraftSoon(ServerPlayer p, Session s, boolean force) {
        if (s.craft == null) return;
        s.craftForce |= force;
        long wait = CRAFT_PUSH_MS - (System.currentTimeMillis() - s.craftPushedAt);
        if (wait <= 0) {
            boolean f = s.craftForce;
            s.craftForce = false;
            pushCraft(p, s, f);
            return;
        }
        if (s.craftFlushQueued) return;
        s.craftFlushQueued = true;
        Tasks.later(Math.max(1, (wait + 49) / 50), () -> {
            s.craftFlushQueued = false;
            if (sessions.get(p.getUUID()) != s || s.craft == null || p.hasDisconnected()) return;
            boolean f = s.craftForce;
            s.craftForce = false;
            try {
                pushCraft(p, s, f);
            } catch (RuntimeException e) {
                module.plugin().getLogger().warn("Quick Craft list refresh failed", e);
            }
        });
    }

    /** Rebuilds of the Quick Craft list this server did (game tests check the throttle with it). */
    static int craftRebuilds;

    /**
     * Sends the recipe list when it changed (or {@code force}). Right after the player's own craft the recipes keep
     * their places, with gaps for the ones that went, so a quick second click never lands on another recipe; once
     * the hold is over the proper order goes out.
     */
    private void pushCraft(ServerPlayer p, Session s, boolean force) {
        CraftList c = s.craft;
        if (c == null) return;
        s.invStamp = View.inventoryStamp(p);
        s.recipeStamp = module.craft().version();
        Network net = module.network(p, s.terminal, false);
        boolean changed = c.update(p, QuickCraft.pools(p, net));
        if (changed) craftRebuilds++;
        boolean holding = System.currentTimeMillis() - s.craftAction < StorageView.HOLD_MS && !s.craftShown.isEmpty();
        if (!changed && !force && !(s.craftHeld && !holding)) return;
        List<QuickCraft.CachedRecipe> order;
        if (holding) {
            order = new ArrayList<>(s.craftShown.size());
            for (QuickCraft.CachedRecipe old : s.craftShown) order.add(old == null ? null : c.find(old.id()));
        } else {
            order = new ArrayList<>(c.list());
        }
        s.craftShown = order;
        s.craftHeld = holding;
        s.craftPushedAt = System.currentTimeMillis();
        JsonObject m = Companion.message("quickcraft");
        m.addProperty("s", s.id);
        m.addProperty("v", ++s.craftVersion);
        m.addProperty("all", c.showAll);
        m.addProperty("auto", c.auto);
        m.addProperty("depth", module.recursiveDepth());
        m.addProperty("amount", s.craftAmount);
        m.addProperty("q", c.query == null ? "" : c.query);
        m.addProperty("ready", c.ready());
        m.addProperty("cap", module.maxBatch());
        m.addProperty("held", holding);
        JsonArray list = new JsonArray();
        for (QuickCraft.CachedRecipe r : order) {
            if (r == null) {
                list.add(com.google.gson.JsonNull.INSTANCE);
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("r", r.id());
            o.add("item", Wire.item(r.result));
            CraftList.State st = c.state(r);
            o.addProperty("st", st == CraftList.State.DIRECT ? "direct" : st == CraftList.State.DEEP ? "auto" : "none");
            // How many times it can be made right now (max-batch at most), so the grid can show it.
            if (st != CraftList.State.NONE) o.addProperty("n", c.max(r));
            list.add(o);
        }
        m.add("recipes", list);
        Companion.send(p, m);
    }

    /** {@code qc_info}: the tooltip lines of one listed recipe (what it uses, what is missing, what comes first, how often). */
    private void craftInfo(ServerPlayer p, JsonObject m) {
        Session s = session(p, m, false);
        if (s == null || s.craft == null) return;
        long sec = System.currentTimeMillis() / 1000;
        if (sec != s.infoSecond) {
            s.infoSecond = sec;
            s.infoCount = 0;
        }
        if (++s.infoCount > 20) return;
        pushCraftSoon(p, s, false);
        String id = Wire.str(m, "r", "");
        QuickCraft.CachedRecipe r = s.craft.find(id);
        JsonObject reply = Companion.message("qc_info");
        reply.addProperty("s", s.id);
        reply.addProperty("v", s.craftVersion);
        reply.addProperty("r", id);
        JsonArray lore = new JsonArray();
        if (r != null) {
            for (net.kyori.adventure.text.Component line : s.craft.lines(r, s.craftAmount, true)) {
                JsonElement t = Wire.text(line);
                if (t != null) lore.add(t);
            }
            reply.addProperty("st", s.craft.state(r) == CraftList.State.NONE ? "none" : "ok");
            reply.addProperty("max", s.craft.max(r));
        }
        reply.add("lore", lore);
        Companion.send(p, reply);
    }

    /**
     * {@code qc_craft}: crafts a listed recipe. {@code max}: as many as fit (right-click), else the chosen amount;
     * {@code store}: the results go into the storage first (shift). Only recipes in the player's current list count,
     * and the craft itself re-reads everything, exactly like the chest window.
     */
    private void craftRecipe(ServerPlayer p, JsonObject m) {
        Session s = session(p, m);
        if (s == null || s.craft == null) return;
        long now = System.currentTimeMillis();
        if (now - s.lastCraft < CRAFT_OP_MS) return;
        s.lastCraft = now;
        Network net = module.network(p, s.terminal, true);
        // Only a recipe in the list the player has (no rebuild here: the craft re-reads everything itself).
        String id = Wire.str(m, "r", "");
        QuickCraft.CachedRecipe r = s.craft.find(id);
        JsonObject reply = Companion.message("qc_result");
        reply.addProperty("s", s.id);
        reply.addProperty("r", id);
        if (r == null) {
            reply.addProperty("ok", false);
            reply.addProperty("problem", "unknown");
            Companion.send(p, reply);
            pushCraftSoon(p, s, true);
            return;
        }
        QuickCraft.Outcome o = s.craft.craft(p, r, net, s.craftAmount, Wire.bool(m, "max", false), Wire.bool(m, "store", false));
        s.craftAction = System.currentTimeMillis();
        reply.addProperty("ok", o.problem() == null);
        reply.addProperty("items", o.items());
        reply.addProperty("steps", o.steps());
        if (o.problem() != null) reply.addProperty("problem", o.problem());
        Companion.send(p, reply);
        delta(p, s);
        pushCraftSoon(p, s, true);
        module.refreshSoon(StorageModule.touched(s.terminal, net), s);
        Tasks.sync(() -> p.containerMenu.broadcastChanges());
    }

    private void take(ServerPlayer p, JsonObject m) {
        Session s = session(p, m);
        if (s == null) return;
        Network.Entry known = s.known.get(Wire.num(m, "k", -1));
        if (known == null) return;
        ItemStack proto = known.proto;
        String to = Wire.str(m, "to", "");
        Network net = module.network(p, s.terminal, true);
        long live = net.count(proto);
        int max = Math.max(1, proto.getMaxStackSize());
        int want = (int) Math.min(Math.min(max, Math.max(1, Wire.num(m, "amount", 1))), live);
        if (want <= 0) {
            after(p, s, false, net);
            return;
        }
        if (to.equals("cursor")) {
            if (!s.crafting || !p.containerMenu.getCarried().isEmpty()) return;
            int got = net.extract(proto, want);
            if (got > 0) p.containerMenu.setCarried(proto.copyWithCount(got));
            after(p, s, got > 0, net);
            return;
        }
        // Into the inventory.
        long room = QuickCraft.space(p, proto, null);
        want = (int) Math.min(want, room);
        if (want <= 0) {
            module.text().send(p, "storage.view.inventory-full");
            module.error(p);
            return;
        }
        int got = net.extract(proto, want);
        if (got > 0) {
            ItemStack rest = QuickCraft.addToInventory(p.getInventory(), proto.copyWithCount(got));
            if (!rest.isEmpty()) {
                ItemStack left = net.insert(rest);
                if (left != null) QuickCraft.deliver(p, List.of(left), null);
            }
        }
        after(p, s, got > 0, net);
    }

    private void put(ServerPlayer p, JsonObject m) {
        Session s = session(p, m);
        if (s == null) return;
        Network net = module.network(p, s.terminal, true);
        String from = Wire.str(m, "from", "");
        int amount = Wire.num(m, "amount", -1);
        if (from.equals("cursor")) {
            if (!s.crafting) return;
            ItemStack cursor = p.containerMenu.getCarried();
            if (cursor.isEmpty()) return;
            ItemStack put = cursor.copy();
            if (amount > 0 && amount < put.getCount()) put.setCount(amount);
            ItemStack rest = net.insert(put);
            int stored = put.getCount() - (rest == null ? 0 : rest.getCount());
            if (stored > 0) p.containerMenu.setCarried(cursor.getCount() - stored > 0 ? cursor.copyWithCount(cursor.getCount() - stored) : ItemStack.EMPTY);
            full(p, rest != null);
            after(p, s, stored > 0, net);
            return;
        }
        int slot = Wire.num(m, "slot", -1);
        Inventory inv = p.getInventory();
        if (slot < 0 || slot > 35) return;
        ItemStack it = inv.getItem(slot);
        if (it.isEmpty()) return;
        ItemStack put = it.copy();
        if (amount > 0 && amount < put.getCount()) put.setCount(amount);
        ItemStack rest = net.insert(put);
        int stored = put.getCount() - (rest == null ? 0 : rest.getCount());
        if (stored > 0) inv.setItem(slot, it.getCount() - stored > 0 ? it.copyWithCount(it.getCount() - stored) : ItemStack.EMPTY);
        full(p, rest != null);
        after(p, s, stored > 0, net);
    }

    private void full(ServerPlayer p, boolean full) {
        if (!full) return;
        module.text().send(p, "storage.view.network-full");
        module.error(p);
    }

    /** Deposit All: the main inventory except the hotbar, like the chest window's button. */
    private void depositAll(ServerPlayer p, JsonObject m) {
        Session s = session(p, m);
        if (s == null) return;
        Network net = module.network(p, s.terminal, true);
        Inventory pi = p.getInventory();
        int moved = 0;
        boolean full = false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack it = pi.getItem(slot);
            if (it.isEmpty()) continue;
            ItemStack rest = net.insert(it.copy());
            int stored = it.getCount() - (rest == null ? 0 : rest.getCount());
            if (stored > 0) {
                pi.setItem(slot, rest == null ? ItemStack.EMPTY : rest);
                moved += stored;
            }
            if (rest != null) full = true;
        }
        if (moved > 0) module.text().send(p, "storage.view.deposited", Text.unparsed("count", String.format(java.util.Locale.US, "%,d", moved)));
        full(p, full);
        after(p, s, moved > 0, net);
    }

    // ------------------------------------------------------------------ JEI / REI "move items"

    /** A kind of item that can go into the grid, and how many the inventory and the network hold. */
    private static final class Stock {
        final ItemStack proto;
        long inv, net;

        Stock(ItemStack proto) {
            this.proto = proto;
        }

        long total() {
            return inv + net;
        }
    }

    private static Recipe<?> recipe(ServerPlayer p, String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return null;
        return p.level().getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, key)).map(RecipeHolder::value).orElse(null);
    }

    private void fill(ServerPlayer p, JsonObject m) {
        Session s = session(p, m);
        if (s == null || !s.crafting || !(s.view instanceof AbstractCraftingMenu grid) || grid.getGridWidth() != 3) return;
        Ingredient[] layout = QuickCraft.layout(recipe(p, Wire.str(m, "recipe", "")), 3);
        JsonObject reply = Companion.message("st_fill_result");
        reply.addProperty("s", s.id);
        if (layout == null) {
            reply.addProperty("ok", false);
            Companion.send(p, reply);
            return;
        }
        Network net = module.network(p, s.terminal, true);
        fillGrid(p, grid, 3, layout, Math.max(1, Math.min(64, Wire.num(m, "times", 1))), net, module.networkFirstOf(p), reply);
        after(p, s, true, net);
    }

    /**
     * {@code craft_fill}: JEI/REI "+" at an ordinary crafting table or the inventory's 2x2 grid, from the player's own
     * inventory.
     */
    private void craftFill(ServerPlayer p, JsonObject m) {
        if (p.isDeadOrDying()) return;
        long now = System.currentTimeMillis();
        Long last = lastFill.get(p.getUUID());
        if (last != null && now - last < 50) return;
        lastFill.put(p.getUUID(), now);
        AbstractContainerMenu open = p.containerMenu;
        if (!(open instanceof AbstractCraftingMenu grid) || !fillable(p, open)) return;
        int size = grid.getGridWidth();
        if (size != 2 && size != 3) return;
        Ingredient[] layout = QuickCraft.layout(recipe(p, Wire.str(m, "recipe", "")), size);
        JsonObject reply = Companion.message("craft_fill_result");
        if (layout == null) {
            reply.addProperty("ok", false);
            Companion.send(p, reply);
            return;
        }
        fillGrid(p, grid, size, layout, Math.max(1, Math.min(64, Wire.num(m, "times", 1))), null, false, reply);
        Tasks.sync(() -> p.containerMenu.broadcastChanges());
    }

    /**
     * Empties the grid (back to the network, else the inventory, else the floor), then lays out {@code times} crafts
     * of the recipe from the inventory and {@code net} (null: inventory only), or as many as there are. With not even
     * one, one of each ingredient there is goes in and the reply lists what's missing.
     */
    private void fillGrid(ServerPlayer p, AbstractCraftingMenu menu, int size, Ingredient[] layout, int wanted, Network net,
                          boolean networkFirst, JsonObject reply) {
        List<Slot> grid = menu.getInputGridSlots();
        List<ItemStack> back = new ArrayList<>();
        for (Slot sl : grid) {
            if (!sl.getItem().isEmpty()) back.add(sl.getItem().copy());
            sl.set(ItemStack.EMPTY);
        }
        for (ItemStack it : back) {
            ItemStack rest = net == null ? it : net.insert(it);
            if (rest != null) QuickCraft.deliver(p, List.of(rest), null);
        }

        List<Stock> stock = stock(p, net);
        Stock[] pick = null;
        int times = wanted;
        for (; times >= 1; times--) {
            pick = allocate(layout, stock, times);
            if (pick != null) break;
        }
        List<String> missing = new ArrayList<>();
        if (pick == null) {
            times = 1;
            pick = allocatePartial(layout, stock, missing);
        }
        Inventory pi = p.getInventory();
        for (int i = 0; i < pick.length && i < grid.size(); i++) {
            if (pick[i] == null) continue;
            int need = times, got = 0;
            if (net != null && networkFirst) {
                got += net.extract(pick[i].proto, need);
                if (got < need) got += QuickCraft.takeFromInventory(pi, pick[i].proto, need - got);
            } else {
                got += QuickCraft.takeFromInventory(pi, pick[i].proto, need);
                if (got < need && net != null) got += net.extract(pick[i].proto, need - got);
            }
            if (got > 0) grid.get(i).set(pick[i].proto.copyWithCount(got));
        }
        menu.slotsChanged(grid.getFirst().container);
        reply.addProperty("ok", missing.isEmpty());
        JsonArray miss = new JsonArray();
        missing.forEach(miss::add);
        reply.add("missing", miss);
        Companion.send(p, reply);
    }

    /** Everything the player could put in the grid: their main inventory and the network, by kind. */
    private static List<Stock> stock(ServerPlayer p, Network net) {
        Map<Item, List<Stock>> byType = new IdentityHashMap<>();
        List<Stock> out = new ArrayList<>();
        for (ItemStack it : p.getInventory().getNonEquipmentItems()) {
            if (it.isEmpty()) continue;
            find(byType, out, it).inv += it.getCount();
        }
        if (net != null) for (Network.Entry e : net.aggregate()) find(byType, out, e.proto).net += e.total;
        return out;
    }

    private static Stock find(Map<Item, List<Stock>> byType, List<Stock> out, ItemStack it) {
        List<Stock> list = byType.computeIfAbsent(it.getItem(), k -> new ArrayList<>(2));
        for (Stock s : list) if (Network.similar(s.proto, it)) return s;
        Stock s = new Stock(it.copyWithCount(1));
        list.add(s);
        out.add(s);
        return s;
    }

    /** Picks an item kind for every slot so that {@code times} crafts fit; null when they don't. */
    private static Stock[] allocate(Ingredient[] layout, List<Stock> stock, int times) {
        Map<Stock, Long> used = new IdentityHashMap<>();
        Map<Ingredient, Stock> chosen = new HashMap<>();
        Stock[] pick = new Stock[layout.length];
        for (int i = 0; i < layout.length; i++) {
            Ingredient c = layout[i];
            if (c == null) continue;
            Stock best = null;
            Stock same = chosen.get(c);
            if (same != null && fits(same, used, times)) best = same;
            if (best == null) {
                for (Stock s : stock) {
                    if (!accepts(c, s.proto) || !fits(s, used, times)) continue;
                    if (best == null || s.total() - used.getOrDefault(s, 0L) > best.total() - used.getOrDefault(best, 0L)) best = s;
                }
            }
            if (best == null) return null;
            used.merge(best, (long) times, Long::sum);
            chosen.putIfAbsent(c, best);
            pick[i] = best;
        }
        return pick;
    }

    /** Like {@code c.test(it)}, except a plain item slot ("paper", "#planks") only takes untouched items, as Quick Craft does. */
    private static boolean accepts(Ingredient c, ItemStack it) {
        try {
            return c.test(it) && (c.requiresTesting() || it.getComponentsPatch().isEmpty());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean fits(Stock s, Map<Stock, Long> used, int times) {
        return times <= Math.max(1, s.proto.getMaxStackSize()) && s.total() - used.getOrDefault(s, 0L) >= times;
    }

    /** One of each ingredient there is; the ids of the ones there aren't go into {@code missing}. */
    private static Stock[] allocatePartial(Ingredient[] layout, List<Stock> stock, List<String> missing) {
        Map<Stock, Long> used = new IdentityHashMap<>();
        Stock[] pick = new Stock[layout.length];
        for (int i = 0; i < layout.length; i++) {
            Ingredient c = layout[i];
            if (c == null) continue;
            for (Stock s : stock) {
                if (accepts(c, s.proto) && fits(s, used, 1)) {
                    pick[i] = s;
                    used.merge(s, 1L, Long::sum);
                    break;
                }
            }
            if (pick[i] == null) {
                String id = c.items().findFirst().map(h -> BuiltInRegistries.ITEM.getKey(h.value()).toString()).orElse("?");
                if (!missing.contains(id)) missing.add(id);
            }
        }
        return pick;
    }
}
