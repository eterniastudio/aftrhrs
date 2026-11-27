package net.axther.aftrhrs.feature.storage;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Storage network + Quick Craft (docs/storage.md). Storage Terminals, Crafting Terminals and Inventory Connectors
 * are real blocks ({@link StorageContent}); a terminal shows every chest, barrel and shulker box touching it (or
 * touching connectors and other storage that touches it) as one storage view ({@link StorageView}, or the Companion
 * screen through {@link StorageBridge}), and every terminal has Quick Craft ({@link CraftView}, {@link CraftList},
 * {@link QuickCraft}), also on {@code /quickcraft} (and {@code /craft} for players with Quick Craft) from the player's
 * inventory alone. No data file: blocks are blocks, player choices live on the player.
 */
public final class StorageModule implements FeatherModule, FeatherCommand {
    static final String USE = "feather.storage.use";
    static final String ADMIN = "feather.storage.admin";
    static final String QUICKCRAFT = "feather.storage.quickcraft";
    private static final String SORT_KEY = "feather:storage_sort", AMOUNT_KEY = "feather:quickcraft_amount",
            SOURCE_KEY = "feather:quickcraft_network_first", SHOW_ALL_KEY = "feather:quickcraft_show_all",
            AUTO_KEY = "feather:quickcraft_recursive";
    private static final List<String> CRAFT_COMMANDS = List.of("craft", "workbench", "wb", "feather:craft", "feather:workbench", "feather:wb");
    private static final ResourceKey<Recipe<?>> R_TERMINAL = recipeKey("storage/terminal"), R_CONNECTOR = recipeKey("storage/connector"),
            R_CRAFTING = recipeKey("storage/crafting");

    private static volatile StorageModule active;

    private final Feather plugin;
    private final QuickCraft craft;
    private final StorageRemotes remotes;
    private final Map<UUID, View> open = new HashMap<>();
    private final List<Tasks.Task> tasks = new ArrayList<>();
    private boolean refreshQueued;
    /** Window that is closing for the search dialog (kept, so the dialog can reopen it). */
    private View suspended;
    /** Terminals for Companion players. */
    private StorageBridge bridge;

    private int maxBlocks = 512, maxDistance = 48, viewDistance = 8, refreshTicks = 20, fullRefreshTicks = 100, maxBatch = 256, recursiveDepth = 3;
    private boolean defaultNetworkFirst, defaultAutoCraft = true, craftInTerminals = true;

    private int cacheTick = -1;
    private final Map<String, Network> cache = new HashMap<>();
    /** Networks read for drawing or acting (game tests). */
    int scans;
    /** Refresh passes so far (staggers the idle windows' full redraws). */
    private long passes;
    /** Network blocks placed or broken by players since the last refresh pass (past the cap: everything is redrawn). */
    private final List<Shape> shapes = new ArrayList<>();
    private boolean shapesOverflow;

    public StorageModule(Feather plugin) {
        this.plugin = plugin;
        this.craft = new QuickCraft(this);
        this.remotes = new StorageRemotes(this);
    }

    /** The module factory for {@code Modules}: registers the blocks, items and migration now (mod init). */
    public static Supplier<StorageModule> factory(Feather feather) {
        StorageContent.init();
        return () -> new StorageModule(feather);
    }

    static StorageModule active() {
        return active;
    }

    private static ResourceKey<Recipe<?>> recipeKey(String path) {
        return ResourceKey.create(Registries.RECIPE, net.axther.aftrhrs.AftrHrs.id(path));
    }

    @Override
    public String name() {
        return "Storage";
    }

    @Override
    public void enable() {
        readConfig();
        plugin.bind("storage", this);
        plugin.bind("quickcraft", (sender, label, args) -> quickcraft(sender));
        bridge = new StorageBridge(this);
        bridge.start();
        remotes.load();
        active = this;
        plugin.once("storage.events", StorageModule::registerEvents);
        // Quick Craft reads the recipes when first asked (and again whenever the recipe list changes); warm it up once
        // every module is on (their own recipes are in by then), not during the start.
        Tasks.later(1, () -> {
            if (active == this) craft.version();
        });
        startTasks();
        // Players already online (a reload) learn the recipes too.
        for (ServerPlayer p : plugin.onlinePlayers()) discoverRecipes(p);
    }

    private static void registerEvents() {
        FeatherEvents.BLOCK_PLACE.register((p, level, pos, state, stack) -> {
            StorageModule m = active;
            if (m == null) return true;
            // Before the block goes in; the remotes read the network again on their next pass (refused placements cost one extra read).
            if (networkBlock(state)) {
                m.remotes.blockChanged(level, pos);
                m.shapeChanged(level, pos);
            }
            return !(state.getBlock() instanceof StorageBlock) || m.mayPlace(p, level, pos);
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            StorageModule m = active;
            if (m == null || !(state.getBlock() instanceof StorageBlock) || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return true;
            return m.mayBreak(p, sl, pos);
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            StorageModule m = active;
            if (m != null && level instanceof ServerLevel sl && networkBlock(state)) {
                m.remotes.blockChanged(sl, pos);
                m.shapeChanged(sl, pos);
            }
        });
        FeatherEvents.COMMAND.register((p, typed) -> {
            StorageModule m = active;
            return m == null || !m.craftCommand(p, typed);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            StorageModule m = active;
            if (m != null) m.discoverRecipes(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            StorageModule m = active;
            if (m != null) m.open.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, ok) -> {
            StorageModule m = active;
            // A reload builds a new recipe manager, so Quick Craft rebuilds anyway; this only does it now, not on first use.
            if (m != null && ok) m.craft.version();
        });
    }

    /** A block that can be part of a network (storage, terminals, connectors): placing or breaking one reshapes it. */
    static boolean networkBlock(BlockState state) {
        return state.getBlock() instanceof StorageBlock || Network.isStorage(state);
    }

    private void startTasks() {
        tasks.add(Tasks.timer(40L, 10L, this::validateViews));
        tasks.add(Tasks.timer(refreshTicks, refreshTicks, this::renderAll));
        tasks.add(Tasks.timer(20L, 40L, () -> {
            try {
                remotes.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().warn("Storage remotes refresh failed", e);
            }
        }));
    }

    private void readConfig() {
        Yaml c = plugin.getConfig().getConfigurationSection("storage");
        if (c == null) c = plugin.getConfig().createSection("storage");
        maxBlocks = Math.max(1, c.getInt("max-blocks", 512));
        maxDistance = Math.max(1, c.getInt("max-distance", 48));
        viewDistance = Math.max(2, c.getInt("view-distance", 8));
        refreshTicks = Math.max(5, c.getInt("refresh-ticks", 20));
        fullRefreshTicks = Math.max(refreshTicks, c.getInt("full-refresh-ticks", 100));
        maxBatch = Math.max(1, c.getInt("quick-craft.max-batch", 256));
        defaultNetworkFirst = "network".equalsIgnoreCase(c.getString("quick-craft.ingredients-first", "inventory"));
        recursiveDepth = Math.max(0, Math.min(6, c.getInt("quick-craft.recursive-depth", 3)));
        defaultAutoCraft = c.getBoolean("quick-craft.auto-craft", true);
        craftInTerminals = c.getBoolean("quick-craft.in-terminals", true);
        remotes.readConfig(c);
    }

    @Override
    public void reload() {
        closeAll("storage.view.reloaded");
        remotes.stop();
        readConfig();
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        startTasks();
    }

    @Override
    public void disable() {
        closeAll("storage.view.stopped");
        for (Tasks.Task t : tasks) t.cancel();
        tasks.clear();
        if (bridge != null) bridge.stop();
        bridge = null;
        remotes.stop();
        if (active == this) active = null;
    }

    // ------------------------------------------------------------------ shared bits

    Feather plugin() {
        return plugin;
    }

    MinecraftServer server() {
        return Feather.serverOrNull();
    }

    Text text() {
        return plugin.text();
    }

    QuickCraft craft() {
        return craft;
    }

    StorageRemotes remotes() {
        return remotes;
    }

    int maxBlocks() {
        return maxBlocks;
    }

    int maxDistance() {
        return maxDistance;
    }

    int maxBatch() {
        return maxBatch;
    }

    /** Whether terminals have Quick Craft (the Craft button, the Companion screen's Craftable view). */
    boolean craftInTerminals() {
        return craftInTerminals;
    }

    /** How many crafting steps deep Auto-Craft goes (0: Auto-Craft is off for everyone). */
    int recursiveDepth() {
        return recursiveDepth;
    }

    void click(ServerPlayer p) {
        text().sound(p, "click");
    }

    void page(ServerPlayer p) {
        text().sound(p, "page");
    }

    void error(ServerPlayer p) {
        text().sound(p, "error");
    }

    void crafted(ServerPlayer p) {
        Adv.sound(p, "ui.cartography_table.take_result", 0.5f, 1.4f);
    }

    /**
     * The network of a terminal as this player sees it. {@code fresh}: read the world now (every action does);
     * otherwise a read from this same tick may be reused (drawing only).
     */
    Network network(ServerPlayer p, StorageBlock.Terminal.At terminal, boolean fresh) {
        int tick = server() == null ? 0 : server().getTickCount();
        if (tick != cacheTick) {
            cache.clear();
            cacheTick = tick;
        }
        String key = p.getUUID() + ":" + terminal.level().dimension().identifier() + ":" + terminal.pos().asLong();
        if (!fresh) {
            Network n = cache.get(key);
            if (n != null) return n;
        }
        Network n = Network.scan(this, p, terminal);
        scans++;
        cache.put(key, n);
        return n;
    }

    // ------------------------------------------------------------------ per-player choices (kept on the player)

    StorageView.Sort sortOf(ServerPlayer p) {
        String s = Pdc.getString(p, SORT_KEY);
        try {
            return s == null ? StorageView.Sort.AMOUNT : StorageView.Sort.valueOf(s);
        } catch (IllegalArgumentException e) {
            return StorageView.Sort.AMOUNT;
        }
    }

    void sortOf(ServerPlayer p, StorageView.Sort sort) {
        Pdc.putString(p, SORT_KEY, sort.name());
    }

    int craftAmountOf(ServerPlayer p) {
        int v = Pdc.getInt(p, AMOUNT_KEY, 1);
        for (int a : CraftView.AMOUNTS) if (a == v) return a;
        return 1;
    }

    void craftAmountOf(ServerPlayer p, int amount) {
        Pdc.putInt(p, AMOUNT_KEY, amount);
    }

    boolean networkFirstOf(ServerPlayer p) {
        return Pdc.has(p, SOURCE_KEY) ? Pdc.of(p).getByteOr(SOURCE_KEY, (byte) 0) != 0 : defaultNetworkFirst;
    }

    void networkFirstOf(ServerPlayer p, boolean first) {
        Pdc.putTag(p, SOURCE_KEY, ByteTag.valueOf(first));
    }

    private static boolean flag(ServerPlayer p, String key, boolean def) {
        return Pdc.has(p, key) ? Pdc.of(p).getByteOr(key, (byte) 0) != 0 : def;
    }

    /** Quick Craft lists every recipe (true) or only what can be made (false). */
    boolean showAllOf(ServerPlayer p) {
        return flag(p, SHOW_ALL_KEY, false);
    }

    void showAllOf(ServerPlayer p, boolean all) {
        Pdc.putTag(p, SHOW_ALL_KEY, ByteTag.valueOf(all));
    }

    /** Auto-Craft: missing ingredients that can be crafted are crafted first (never while the depth is 0). */
    boolean autoCraftOf(ServerPlayer p) {
        return recursiveDepth > 0 && flag(p, AUTO_KEY, defaultAutoCraft);
    }

    void autoCraftOf(ServerPlayer p, boolean on) {
        Pdc.putTag(p, AUTO_KEY, ByteTag.valueOf(on));
    }

    // ------------------------------------------------------------------ blocks

    private boolean mayPlace(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (!Perms.has(p, USE)) {
            text().send(p, "general.no-permission");
            return false;
        }
        if (!plugin.claimAllows(p, Loc.of(level, pos), ClaimAction.BUILD)) {
            text().send(p, "storage.place.claim");
            error(p);
            return false;
        }
        return true;
    }

    private boolean mayBreak(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (!plugin.claimAllows(p, Loc.of(level, pos), ClaimAction.BUILD)) {
            text().send(p, "storage.break.claim");
            error(p);
            return false;
        }
        return true;
    }

    /** A player placed a storage block: terminals say how much storage they reach. */
    void placed(ServerPlayer p, ServerLevel level, BlockPos pos, Kind kind) {
        if (!kind.terminal()) return;
        int size = Network.scan(this, p, new StorageBlock.Terminal.At(level, pos.immutable(), kind)).containers;
        text().send(p, "storage.place.placed-" + kind.id, Text.unparsed("count", size));
    }

    // ------------------------------------------------------------------ windows

    /** Right-click on a terminal. */
    void openTerminal(ServerPlayer p, StorageBlock.Terminal.At t) {
        if (!mayOpen(p, t)) return;
        if (bridge != null && bridge.offer(p, t)) {
            text().sound(p, "menu-open");
            return;
        }
        show(p, new StorageView(this, p, t));
        text().sound(p, "menu-open");
    }

    /**
     * Whether the player may open (or link a remote to) this terminal: the storage permission and claim container
     * trust there; says why not. Remotes check this again on every use.
     */
    boolean mayOpen(ServerPlayer p, StorageBlock.Terminal.At t) {
        if (!Perms.has(p, USE)) {
            text().send(p, "general.no-permission");
            return false;
        }
        if (!plugin.claimAllows(p, Loc.of(t.level(), t.pos()), ClaimAction.CONTAINER)) {
            text().send(p, "storage.view.claim");
            error(p);
            return false;
        }
        return true;
    }

    /** A remote opens its terminal from afar (always the Crafting Terminal: network, grid and Quick Craft). */
    void openRemote(ServerPlayer p, StorageBlock.Terminal.At t, int tier, String link) {
        if (bridge != null && bridge.offerRemote(p, t, tier, link)) {
            text().sound(p, "menu-open");
            return;
        }
        StorageView v = new StorageView(this, p, t);
        v.remote = tier;
        v.link = link;
        show(p, v);
        text().sound(p, "menu-open");
    }

    /** A terminal's Craft button (or /quickcraft with {@code terminal} null). */
    void openCraft(ServerPlayer p, StorageBlock.Terminal.At terminal, StorageView from) {
        int remote = from == null ? 0 : from.remote;
        String link = from == null ? null : from.link;
        if (terminal != null && (!craftInTerminals || !valid(p, terminal, remote, link))) return;
        CraftView v = new CraftView(this, p, terminal, from);
        v.remote = remote;
        v.link = link;
        show(p, v);
    }

    private void show(ServerPlayer p, View v) {
        v.render(p);
        open.put(p.getUUID(), v);
        v.open(p);
        if (!v.openFor(p)) open.remove(p.getUUID(), v);
    }

    /** Back from the search dialog or Quick Craft: the same window again, if the terminal is still there and near. */
    void reopen(ServerPlayer p, View v) {
        if (suspended == v) suspended = null;
        if (v.dead || (v.terminal != null && !valid(p, v.terminal, v.remote, v.link))) return;
        show(p, v);
    }

    void suspend(View v) {
        suspended = v;
    }

    /** A window closed (by the player, another window or the server). */
    void closed(ServerPlayer p, View v) {
        open.remove(p.getUUID(), v);
    }

    /** Crafting tables other Feather code opened itself (e.g. staff /craft): JEI/REI "+" may fill them. */
    private static final Set<AbstractContainerMenu> TRUSTED_TABLES = Collections.newSetFromMap(new WeakHashMap<>());

    /** Marks a crafting table window opened by code (not a block) as one JEI/REI "+" may fill. */
    public static void trustWorkbench(AbstractContainerMenu menu) {
        if (menu != null) TRUSTED_TABLES.add(menu);
    }

    static boolean trustedWorkbench(AbstractContainerMenu menu) {
        return menu != null && TRUSTED_TABLES.contains(menu);
    }

    /** Quick Craft's Crafting Grid: a plain crafting table where the player stands. */
    void openWorkbench(ServerPlayer p) {
        p.closeContainer();
        WorkbenchMenu.open(p, p.level(), p.blockPosition(), pl -> pl.isAlive());
    }

    /** The Crafting Terminal's table for Companion players: open while the terminal is there and near. */
    WorkbenchMenu openTerminalWorkbench(ServerPlayer p, StorageBlock.Terminal.At t) {
        return openTerminalWorkbench(p, t, 0, null);
    }

    WorkbenchMenu openTerminalWorkbench(ServerPlayer p, StorageBlock.Terminal.At t, int remote, String link) {
        return WorkbenchMenu.open(p, t.level(), t.pos(), pl -> pl instanceof ServerPlayer sp && problem(sp, t, remote, link) == null);
    }

    /** Why this player can't keep using this terminal (a messages.yml key), or null when they can. */
    String problem(ServerPlayer p, StorageBlock.Terminal.At t) {
        return problem(p, t, 0, null);
    }

    /**
     * The same for a window opened with a remote ({@code remote}: its tier, 0 = standing at the terminal): instead of
     * the distance, the remote's reach, that the player still carries it, and (every time) the permission and claim
     * trust at the terminal.
     */
    String problem(ServerPlayer p, StorageBlock.Terminal.At t, int remote, String link) {
        if (!t.present()) return remote > 0 ? "storage.remote.gone" : "storage.view.gone";
        if (remote <= 0) {
            if (t.distanceSqr(p) > (double) viewDistance * viewDistance) return "storage.view.too-far";
            return null;
        }
        if (link == null || remotes.isDead(link)) return "storage.remote.broken";
        int tier = StorageRemotes.heldTier(p, link);
        if (tier <= 0) return "storage.remote.not-held";
        String why = remotes.reach(p, t, tier);
        if (why != null) return why;
        if (!Perms.has(p, USE) || !plugin.claimAllows(p, Loc.of(t.level(), t.pos()), ClaimAction.CONTAINER)) return "storage.view.claim";
        return null;
    }

    /** Whether the player can keep using this terminal's window: it's there, same world, close enough. */
    private boolean valid(ServerPlayer p, StorageBlock.Terminal.At t) {
        return valid(p, t, 0, null);
    }

    private boolean valid(ServerPlayer p, StorageBlock.Terminal.At t, int remote, String link) {
        String why = problem(p, t, remote, link);
        if (why != null) text().send(p, why);
        return why == null;
    }

    /** A storage block was broken or replaced: windows on it close (next tick, never inside a block change). */
    void terminalGone(ServerLevel level, BlockPos pos) {
        remotes.terminalGone(level, pos);
        StorageBlock.Terminal.At at = new StorageBlock.Terminal.At(level, pos, Kind.TERMINAL);
        for (View v : new ArrayList<>(open.values())) {
            if (!v.on(at)) continue;
            v.dead = true;
            Tasks.sync(() -> {
                ServerPlayer p = plugin.player(v.viewer);
                if (p != null && v.openFor(p)) {
                    p.closeContainer();
                    text().send(p, "storage.view.gone");
                }
            });
        }
    }

    private void closeAll(String why) {
        if (bridge != null) bridge.closeAll();
        for (View v : new ArrayList<>(open.values())) {
            v.dead = true;
            ServerPlayer p = plugin.player(v.viewer);
            if (p != null && v.openFor(p)) {
                p.closeContainer();
                text().send(p, why);
            }
        }
        open.clear();
        pending.clear();
        shapes.clear();
        shapesOverflow = false;
        suspended = null;
    }

    private void validateViews() {
        for (View v : new ArrayList<>(open.values())) {
            ServerPlayer p = plugin.player(v.viewer);
            if (p == null || !v.openFor(p)) {
                open.remove(v.viewer, v);
                continue;
            }
            if (v.terminal != null && !v.dead) {
                String why = problem(p, v.terminal, v.remote, v.link);
                if (why != null) {
                    v.dead = true;
                    p.closeContainer();
                    text().send(p, why);
                }
            }
        }
    }

    /** A network block a player placed or broke: windows on terminals within reach of it redraw on the next pass. */
    record Shape(ResourceKey<Level> dim, BlockPos pos) {
    }

    private void shapeChanged(ServerLevel level, BlockPos pos) {
        if (shapesOverflow) return;
        if (shapes.size() >= StorageLog.DIRTY_MAX) {
            shapesOverflow = true;
            shapes.clear();
            return;
        }
        shapes.add(new Shape(level.dimension(), pos.immutable()));
    }

    /**
     * What changed since the last refresh pass: storage blocks that wrote a change down ({@link StorageLog#drainDirty})
     * and network blocks players placed or broke. {@code all}: too many to tell, everything counts as changed.
     */
    record Changes(StorageLog.Dirty dirty, List<Shape> shapes, boolean all, int reach) {
        /** Whether a window or session that showed {@code seen} from {@code terminal} may show something else now. */
        boolean concern(Touched seen, StorageBlock.Terminal.At terminal) {
            if (all || dirty.overflow() || seen == null) return true;
            LongOpenHashSet d = dirty.pos().get(seen.dim());
            if (d != null && !d.isEmpty()) {
                LongOpenHashSet small = d.size() <= seen.pos().size() ? d : seen.pos(), big = small == d ? seen.pos() : d;
                for (LongIterator it = small.iterator(); it.hasNext(); ) if (big.contains(it.nextLong())) return true;
            }
            if (terminal == null || shapes.isEmpty()) return false;
            ResourceKey<Level> dim = terminal.level().dimension();
            BlockPos t = terminal.pos();
            for (Shape s : shapes) {
                if (s.dim() != dim) continue;
                BlockPos b = s.pos();
                if (Math.max(Math.abs(b.getX() - t.getX()), Math.max(Math.abs(b.getY() - t.getY()), Math.abs(b.getZ() - t.getZ()))) <= reach) return true;
            }
            return false;
        }
    }

    /**
     * Whether an idle window or session is due its full redraw ({@code full-refresh-ticks}: lock or claim changes, the
     * "ago" lines, other mods' containers that never report a change ...). Each viewer has its own pass in the cycle,
     * so many open windows don't all redraw on the same tick.
     */
    boolean safetyDue(java.util.UUID viewer, long lastFull) {
        long cycle = Math.max(1, fullRefreshTicks / refreshTicks);
        long slot = (viewer.hashCode() & 0x7fffffff) % cycle;
        return (passes + slot) % cycle == 0 && Tasks.now() - lastFull >= fullRefreshTicks - refreshTicks / 2;
    }

    /**
     * The refresh pass: open windows (and Companion sessions, {@link StorageBridge#tick}) redraw only when something
     * they show may have changed; see {@link Changes}, {@link #safetyDue}.
     */
    private void renderAll() {
        passes++;
        Changes changes = new Changes(StorageLog.drainDirty(), shapes.isEmpty() ? List.of() : List.copyOf(shapes), shapesOverflow, maxDistance + 1);
        shapes.clear();
        shapesOverflow = false;
        if (bridge != null) bridge.tick(changes);
        long now = System.currentTimeMillis();
        for (View v : new ArrayList<>(open.values())) {
            ServerPlayer p = plugin.player(v.viewer);
            if (p == null || v.dead || !v.openFor(p)) continue;
            if (!due(v, p, changes, now)) continue;
            try {
                v.render(p);
            } catch (RuntimeException e) {
                plugin.getLogger().warn("Storage window refresh failed", e);
            }
        }
    }

    private boolean due(View v, ServerPlayer p, Changes changes, long now) {
        if (v.lastSeen == null || changes.concern(v.lastSeen, v.terminal)) return true;
        if (v.holdPending && now - v.lastAction >= StorageView.HOLD_MS) return true;
        if (v instanceof CraftView c && (c.invStamp != View.inventoryStamp(p) || c.recipeStamp != craft.version())) return true;
        return safetyDue(v.viewer, v.lastFullRender);
    }

    /** The storage blocks a network had when it was read: which windows a change in them concerns. */
    record Touched(ResourceKey<Level> dim, LongOpenHashSet pos) {
        boolean meets(Touched o) {
            if (o == null || o.dim != dim) return false;
            LongOpenHashSet small = pos.size() <= o.pos.size() ? pos : o.pos, big = small == pos ? o.pos : pos;
            for (LongIterator it = small.iterator(); it.hasNext(); ) if (big.contains(it.nextLong())) return true;
            return false;
        }
    }

    /** The storage blocks of this terminal's network (null without a terminal: an inventory-only Quick Craft). */
    static Touched touched(StorageBlock.Terminal.At t, Network net) {
        if (t == null || net == null) return null;
        LongOpenHashSet pos = new LongOpenHashSet(Math.max(4, net.sources.size()));
        for (Network.Source s : net.sources) pos.add(s.pos().asLong());
        return new Touched(t.level().dimension(), pos);
    }

    /** One change waiting for the redraw: the storage it touched, and the window or session that already redrew itself. */
    record Pending(Touched changed, Object origin) {
    }

    private final List<Pending> pending = new ArrayList<>();

    /**
     * After a change through one window ({@code origin}, which redrew itself): next tick, every other open window and
     * Companion session whose network shares a storage block with {@code changed} redraws (once, however many changes
     * came in). Windows on other networks don't; the {@code refresh-ticks} timer catches everything else (hoppers,
     * chests used by hand).
     */
    void refreshSoon(Touched changed, Object origin) {
        if (changed == null || changed.pos().isEmpty()) return;
        pending.add(new Pending(changed, origin));
        if (refreshQueued) return;
        refreshQueued = true;
        Tasks.sync(this::flushRefresh);
    }

    /** Redraws what {@link #refreshSoon} queued (game tests call it straight away). */
    void flushRefresh() {
        refreshQueued = false;
        if (pending.isEmpty()) return;
        List<Pending> todo = new ArrayList<>(pending);
        pending.clear();
        for (View v : new ArrayList<>(open.values())) {
            ServerPlayer p = plugin.player(v.viewer);
            if (p == null || v.dead || !v.openFor(p) || !concerns(todo, v.lastSeen, v)) continue;
            try {
                v.render(p);
            } catch (RuntimeException e) {
                plugin.getLogger().warn("Storage window refresh failed", e);
            }
        }
        if (bridge != null) bridge.refreshTouched(todo);
    }

    /** Whether any of these changes, made through something other than {@code self}, touched what {@code seen} shows. */
    static boolean concerns(List<Pending> pending, Touched seen, Object self) {
        if (seen == null) return false;
        for (Pending c : pending) if (c.origin() != self && c.changed().meets(seen)) return true;
        return false;
    }

    /** The cursor of a closing window goes back into the inventory, else into the network, else to the player's feet. */
    void returnCursor(ServerPlayer p, View v, ItemStack item) {
        if (p.isDeadOrDying() || p.hasDisconnected()) {
            p.drop(item, false, net.minecraft.util.Prediction.SERVER_ONLY);
            return;
        }
        Network net = v.terminal != null && !v.dead && v.terminal.present() ? Network.scan(this, p, v.terminal) : null;
        QuickCraft.deliver(p, List.of(item), net);
    }

    // ------------------------------------------------------------------ recipes

    private void discoverRecipes(ServerPlayer p) {
        MinecraftServer s = server();
        if (s == null) return;
        List<ResourceKey<Recipe<?>>> keys = new ArrayList<>();
        for (ResourceKey<Recipe<?>> k : List.of(R_TERMINAL, R_CONNECTOR, R_CRAFTING)) if (s.getRecipeManager().byKey(k).isPresent()) keys.add(k);
        if (!keys.isEmpty()) p.awardRecipesByKey(keys);
    }

    /** Players with Quick Craft get it on /craft too (it has a Crafting Grid button for the plain table). */
    private boolean craftCommand(ServerPlayer p, String typed) {
        String label = typed.trim().toLowerCase(Locale.ROOT).split(" ", 2)[0];
        if (label.startsWith("/")) label = label.substring(1);
        if (!CRAFT_COMMANDS.contains(label) || !Perms.has(p, QUICKCRAFT)) return false;
        Tasks.sync(() -> openCraft(p, null, null));
        return true;
    }

    // ------------------------------------------------------------------ commands

    /** /quickcraft: Quick Craft from the player's inventory. */
    private boolean quickcraft(Sender sender) {
        ServerPlayer p = sender.player();
        if (p == null) {
            text().send(sender, "general.players-only");
            return true;
        }
        if (!Perms.has(p, QUICKCRAFT)) {
            text().send(p, "general.no-permission");
            return true;
        }
        openCraft(p, null, null);
        return true;
    }

    /** /storage [give ...]. */
    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission(ADMIN)) {
                text().send(sender, "general.no-permission");
                return true;
            }
            if (args.length < 3) {
                text().send(sender, "storage.command.give-usage");
                return true;
            }
            ServerPlayer target = plugin.findPlayer(sender, args[1]);
            if (target == null) {
                plugin.notFound(sender, args[1]);
                return true;
            }
            Kind kind = Kind.parse(args[2]);
            if (kind == null) {
                text().send(sender, "storage.command.give-usage");
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    amount = -1;
                }
                if (amount < 1 || amount > 2304) {
                    text().send(sender, "storage.command.give-usage");
                    return true;
                }
            }
            ItemStack stack = StorageItems.make(kind, 1);
            stack.setCount(amount);
            int dropped = QuickCraft.deliver(target, QuickCraft.split(stack), null);
            text().send(sender, "storage.command.given", Text.unparsed("amount", amount),
                    Text.component("item", text().msg("storage.items." + kind.id + ".name")), Text.component("player", net.axther.aftrhrs.core.Names.display(target)));
            if (dropped > 0) text().send(target, "storage.command.given-dropped");
            return true;
        }
        text().send(sender, sender.hasPermission(ADMIN) ? "storage.command.help-admin" : "storage.command.help");
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) return List.of();
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) return "give".startsWith(last) ? List.of("give") : List.of();
        if (!args[0].equalsIgnoreCase("give")) return List.of();
        if (args.length == 2) return plugin.completePlayers(sender, args[1]);
        if (args.length == 3) {
            List<String> out = new ArrayList<>();
            for (Kind k : Kind.values()) if (k.id.startsWith(last)) out.add(k.id);
            return out;
        }
        if (args.length == 4) return List.of("1", "16", "64");
        return List.of();
    }
}
