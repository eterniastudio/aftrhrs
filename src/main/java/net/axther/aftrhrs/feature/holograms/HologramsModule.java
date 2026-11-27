package net.axther.aftrhrs.feature.holograms;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Holograms (docs/holograms.md): floating text, items, heads, blocks and mobs built from display entities, with
 * pages per player, click actions and placeholders, made with {@code /holo} and saved in holograms.yml. Other modules
 * use the API below for temporary holograms (the bank chests). Also runs the damage and heal numbers.
 *
 * <p>Entities only exist while someone is near and the chunk is loaded; they are never saved with the world
 * ({@code FeatherEvents.TRANSIENT_TAG}) and are tagged {@code feather:hologram}, so a leftover one (or one a Paper
 * server left behind) is removed when its chunk loads.
 */
public final class HologramsModule implements FeatherModule {
    public static final String ADMIN = "feather.holograms.admin";
    /** Adding "console" click actions in game (they run as the server for whoever clicks). */
    public static final String CONSOLE = "feather.holograms.console";
    /** A plain Minecraft username: the only {player} value a console action will run with. */
    static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** Pdc key on every hologram entity (the hologram's name, or "damage-numbers"); Paper used the same key. */
    static final String TAG = "feather:hologram";
    /** Paper tagged MythicMobs mob lines with this too. */
    static final String MYTHIC_TAG = "feather:hologram_mythic";

    private static volatile HologramsModule active;
    private static boolean legacyHooked;

    final Feather plugin;
    final Dialogs dialogs;
    final HoloContent content;
    final HoloStore store;

    /** Every hologram by lower-case name (temporary ones are "owner:id"). */
    private final Map<String, Hologram> holograms = new LinkedHashMap<>();
    private final Map<Hologram, HoloRender> renders = new HashMap<>();
    /** World -> chunk -> holograms anchored there (for chunk unloads). */
    private final Map<String, Map<Long, List<Hologram>>> byChunk = new HashMap<>();
    /** Every entity a hologram spawned that is still around. */
    private final Set<UUID> owned = new HashSet<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    final Map<String, String> defaults = new LinkedHashMap<>();
    private final Map<HoloLine.Type, Double> heights = new EnumMap<>(HoloLine.Type.class);

    final DamageNumbers damage;
    final BankHolograms bank;
    final HoloEditor editor;
    private Tasks.Task ticker;
    private long tick;
    int trackTicks = 10;
    int spinTicks = 20;
    boolean itemsSpin = true;
    int maxLines = 40;
    int maxPages = 30;
    private Sound clickSound;

    /**
     * The {@code Modules} factory. Registers the Paper leftover cleanup right away (mod init, before any world loads),
     * so old hologram entities go even while the module is off.
     */
    public static Supplier<HologramsModule> factory(Feather feather) {
        registerLegacy();
        return () -> new HologramsModule(feather);
    }

    /** Paper's hologram entities were never meant to be saved; any that were (crash, old copy) are removed on load. */
    static synchronized void registerLegacy() {
        if (legacyHooked) return;
        legacyHooked = true;
        Legacy.converter((level, entity, bukkit) -> {
            if (!bukkit.contains(TAG) && !bukkit.contains(MYTHIC_TAG)) return false;
            discardLater(level, entity);
            return true;
        });
    }

    /** Removes an entity on the next tick (never inside the load or unload event that found it). */
    static void discardLater(ServerLevel level, Entity e) {
        level.getServer().execute(() -> {
            if (!e.isRemoved()) e.discard();
        });
    }

    public HologramsModule(Feather plugin) {
        this.plugin = plugin;
        this.dialogs = plugin.dialogs();
        this.content = new HoloContent(plugin);
        this.store = new HoloStore(plugin);
        this.damage = new DamageNumbers(this);
        this.bank = new BankHolograms(this);
        this.editor = new HoloEditor(this);
    }

    @Override
    public String name() {
        return "Holograms";
    }

    @Override
    public void enable() {
        active = this;
        registerLegacy();
        Visibility.hook();
        plugin.once("holograms.events", HologramsModule::hookEvents);
        readConfig();
        Map<String, Hologram> loaded = store.load(defaults);
        if (loaded != null) for (Hologram h : loaded.values()) put(h);
        plugin.bind("holo", new HoloCommand(this));
        plugin.hub().addToggle(new Hub.Toggle("damage-numbers", Hub.TOGGLE_DAMAGE_NUMBERS,
                p -> active != null && active.damageNumbersEnabled(),
                p -> active != null && active.damageNumbersOn(p),
                p -> {
                    HologramsModule m = active;
                    if (m != null) m.toggleDamageNumbers(p);
                }));
        ticker = Tasks.timer(1L, 1L, this::tickAll);
        bank.sync();
    }

    @Override
    public void reload() {
        // An edit still on its way to the disk must not be lost by reading the file back.
        if (store.pending()) store.saveNow(holograms.values());
        readConfig();
        Map<String, Hologram> loaded = store.load(defaults);
        if (loaded != null) {
            // Players keep the page they were on.
            Map<String, Map<UUID, Integer>> pages = new HashMap<>();
            for (Hologram h : new ArrayList<>(holograms.values())) {
                if (h.temporary()) continue;
                HoloRender r = renders.get(h);
                if (r != null) pages.put(h.name().toLowerCase(Locale.ROOT), new HashMap<>(r.pageOf));
                unput(h);
            }
            for (Hologram h : loaded.values()) {
                put(h);
                Map<UUID, Integer> kept = pages.get(h.name().toLowerCase(Locale.ROOT));
                if (kept != null) renders.get(h).pageOf.putAll(kept);
            }
        } else {
            plugin.getLogger().error("holograms.yml could not be read: the holograms already loaded stay, and nothing is saved until it is fixed.");
        }
        rebuildAll();
        bank.sync();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        for (HoloRender r : renders.values()) r.despawnAll();
        damage.clear();
        bank.clear();
        if (store.pending()) store.saveNow(holograms.values());
        owned.clear();
        if (active == this) active = null;
    }

    private void readConfig() {
        Yaml c = plugin.getConfig().getConfigurationSection("holograms");
        trackTicks = c == null ? 10 : Math.max(2, Math.min(100, c.getInt("track-ticks", 10)));
        spinTicks = c == null ? 20 : Math.max(2, Math.min(59, c.getInt("item-spin-ticks", 20)));
        itemsSpin = c == null || c.getBoolean("items-spin", true);
        maxLines = c == null ? 40 : Math.max(1, c.getInt("max-lines-per-page", 40));
        maxPages = c == null ? 30 : Math.max(1, c.getInt("max-pages", 30));
        defaults.clear();
        Yaml d = c == null ? null : c.getConfigurationSection("defaults");
        for (String key : HoloSettings.KEYS) {
            String raw = d == null ? null : d.getString(key);
            String v = raw == null ? null : HoloSettings.normalize(key, raw);
            if (raw != null && v == null) plugin.getLogger().warn("config.yml holograms.defaults.{}: bad value '{}'.", key, raw);
            defaults.put(key, v != null ? v : HoloSettings.BUILT_IN.get(key));
        }
        heights.clear();
        double[] builtIn = {0.3, 0.6, 0.6, 0.6, 1.0, 0.3};
        for (HoloLine.Type t : HoloLine.Type.values()) {
            String k = "line-heights." + t.name().toLowerCase(Locale.ROOT);
            heights.put(t, Math.max(0, c == null ? builtIn[t.ordinal()] : c.getDouble(k, builtIn[t.ordinal()])));
        }
        clickSound = parseSound(c == null ? null : c.getString("click-sound", "ui.button.click 0.4 1.6"));
        content.reload(c);
        damage.reload(c == null ? null : c.getConfigurationSection("damage-numbers"));
        bank.reload(c == null ? null : c.getConfigurationSection("bank"));
    }

    static Sound parseSound(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("none")) return null;
        String[] parts = spec.trim().split("\\s+");
        try {
            return Sound.sound(Key.key(parts[0].toLowerCase(Locale.ROOT)), Sound.Source.MASTER,
                    parts.length > 1 ? Float.parseFloat(parts[1]) : 1f, parts.length > 2 ? Float.parseFloat(parts[2]) : 1f);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ events (registered once per JVM)

    private static void hookEvents() {
        DamageNumbers.hook(() -> active);
        ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
            HologramsModule m = active;
            if (m != null) m.onEntityLoad(e, level);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((e, level) -> {
            HologramsModule m = active;
            if (m != null) m.onEntityUnload(e);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            HologramsModule m = active;
            if (m != null) m.onChunkUnload(level, chunk.getPos().x(), chunk.getPos().z());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            HologramsModule m = active;
            if (m != null) m.onQuit(handler.player.getUUID());
        });
        // Mob lines never convert (lightning pig, drowning zombie...): Paper's onTransform.
        FeatherEvents.MOB_CONVERT.register((mob, into) -> {
            HologramsModule m = active;
            return m == null || !m.owned.contains(mob.getUUID());
        });
        // Mob lines can't be hurt.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, amount) -> {
            HologramsModule m = active;
            return m == null || !m.owned.contains(e.getUUID());
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            HologramsModule m = active;
            if (m == null || world.isClientSide() || !m.owned.contains(entity.getUUID())) return InteractionResult.PASS;
            // interact-at and interact may both arrive for one click: the 250 ms click guard keeps it to one
            if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) m.click(sp, entity, false);
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            HologramsModule m = active;
            if (m == null || world.isClientSide() || !m.owned.contains(entity.getUUID())) return InteractionResult.PASS;
            if (player instanceof ServerPlayer sp) m.click(sp, entity, true);
            return InteractionResult.SUCCESS;
        });
    }

    // ------------------------------------------------------------------ helpers for the other classes

    Text text() {
        return plugin.text();
    }

    long tick() {
        return tick;
    }

    double lineHeight(HoloLine.Type t) {
        return heights.getOrDefault(t, 0.3);
    }

    boolean authBlocked(ServerPlayer p) {
        return plugin.authBlocked(p);
    }

    /** The page dots line: the current page bright, the others dim. */
    String pageDots(int page, int pages) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            if (i > 0) dots.append(' ');
            dots.append(text().raw(i == page ? "holograms.page-dots.current" : "holograms.page-dots.other"));
        }
        return text().raw("holograms.page-dots.line").replace("<dots>", dots);
    }

    void badLine(Hologram h, HoloLine l) {
        if (warned.add(h.name() + "\n" + l.content())) {
            plugin.getLogger().warn("Hologram {}: nothing to show for '{}' (unknown item, block or mob).", h.name(), l.content());
        }
    }

    static boolean tagged(Entity e) {
        return Pdc.has(e, TAG);
    }

    static String tagOf(Entity e) {
        return Pdc.getString(e, TAG);
    }

    boolean owns(Entity e) {
        return e != null && owned.contains(e.getUUID());
    }

    void adopt(Entity e) {
        if (e != null) owned.add(e.getUUID());
    }

    void removeEntity(Entity e) {
        if (e == null) return;
        owned.remove(e.getUUID());
        Visibility.forget(e);
        if (!e.isRemoved()) e.discard();
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
    }

    HoloRender render(Hologram h) {
        return renders.get(h);
    }

    // ------------------------------------------------------------------ the clock

    private void tickAll() {
        tick++;
        if (tick % trackTicks == 0) trackAll();
        boolean spinNow = tick % spinTicks == 0;
        boolean calmNow = tick % 20 == 0;
        for (HoloRender r : renders.values()) {
            if (r.viewers.isEmpty()) continue;
            try {
                r.refresh(tick);
                if (spinNow) r.spin(tick, spinTicks);
                if (calmNow) r.calm();
            } catch (RuntimeException e) {
                failed(r, e);
            }
        }
        damage.tick();
        if (tick % (Math.max(1, bank.checkSeconds) * 20L) == 0) bank.sync();
    }

    private void trackAll() {
        long now = System.currentTimeMillis();
        for (HoloRender r : new ArrayList<>(renders.values())) {
            ServerLevel level = r.h.level();
            if (level == null) {
                if (!r.viewers.isEmpty() || !r.live.isEmpty()) r.despawnAll();
                continue;
            }
            try {
                r.track(level, level.players(), now);
            } catch (RuntimeException e) {
                failed(r, e);
            }
        }
    }

    /** One hologram failing never stops the others; it is logged once. */
    private void failed(HoloRender r, RuntimeException e) {
        if (warned.add("fail:" + r.h.name())) plugin.getLogger().warn("Hologram {} failed", r.h.name(), e);
    }

    private void trackOne(Hologram h) {
        HoloRender r = renders.get(h);
        ServerLevel level = h.level();
        if (r != null && level != null) r.track(level, level.players(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ the registry

    private void put(Hologram h) {
        Hologram old = holograms.put(h.name().toLowerCase(Locale.ROOT), h);
        if (old != null) unrender(old);
        renders.put(h, new HoloRender(this, h));
        index(h);
    }

    private void unput(Hologram h) {
        holograms.remove(h.name().toLowerCase(Locale.ROOT));
        unrender(h);
    }

    private void unrender(Hologram h) {
        HoloRender r = renders.remove(h);
        if (r != null) r.despawnAll();
        unindex(h);
    }

    private void index(Hologram h) {
        if (h.world == null) return;
        byChunk.computeIfAbsent(h.world, k -> new HashMap<>()).computeIfAbsent(h.chunkKey(), k -> new ArrayList<>()).add(h);
    }

    private void unindex(Hologram h) {
        for (Map<Long, List<Hologram>> m : byChunk.values()) for (List<Hologram> l : m.values()) l.remove(h);
    }

    /** Rebuilds every hologram's entities (config or skin changes). */
    void rebuildAll() {
        for (HoloRender r : renders.values()) {
            r.despawnAll();
            r.replan();
        }
        trackAll();
    }

    /**
     * After any change to a hologram: its entities are rebuilt for the players near it right away, and a persistent
     * hologram is saved. False when holograms.yml is unreadable (the change then only lasts until a restart).
     */
    boolean changed(Hologram h) {
        HoloRender r = renders.get(h);
        if (r == null) return true;
        r.despawnAll();
        r.replan();
        unindex(h);
        index(h);
        trackOne(h);
        return h.temporary() || save();
    }

    boolean save() {
        return store.saveLater(holograms.values());
    }

    // ------------------------------------------------------------------ API for the rest of Feather

    /** A hologram by name ("spawn-sign", or "bank:..." for a temporary one), or null. */
    public Hologram hologram(String name) {
        return name == null ? null : holograms.get(name.toLowerCase(Locale.ROOT));
    }

    /** Every hologram, persistent and temporary. */
    public Collection<Hologram> holograms() {
        return Collections.unmodifiableCollection(holograms.values());
    }

    /**
     * Makes a hologram saved in holograms.yml (like /holo create). The location is the top of the first line.
     * Null when the name is taken or not valid.
     */
    public Hologram create(String name, Loc at, List<String> lines) {
        if (!validName(name) || hologram(name) != null || at == null || at.level() == null) return null;
        Hologram h = new Hologram(name, null, at, defaults);
        HoloPage page = new HoloPage();
        for (String s : lines) page.lines.add(new HoloLine(s));
        h.pages.add(page);
        put(h);
        changed(h);
        return h;
    }

    /**
     * Makes (or replaces) a temporary hologram owned by a module: never saved, gone on restart, not editable with
     * /holo. Its name is {@code owner:id}.
     */
    public Hologram createTemporary(String owner, String id, Loc at, List<String> lines) {
        String name = owner.toLowerCase(Locale.ROOT) + ":" + id;
        Hologram old = hologram(name);
        if (old != null) unput(old);
        Hologram h = new Hologram(name, owner.toLowerCase(Locale.ROOT), at, defaults);
        HoloPage page = new HoloPage();
        for (String s : lines) page.lines.add(new HoloLine(s));
        h.pages.add(page);
        put(h);
        changed(h);
        return h;
    }

    /** Replaces one page's lines (the page is added when it is the next one). */
    public void setLines(Hologram h, int page, List<String> lines) {
        while (h.pages.size() <= page) h.pages.add(new HoloPage());
        HoloPage p = h.pages.get(page);
        p.lines.clear();
        for (String s : lines) p.lines.add(new HoloLine(s));
        changed(h);
    }

    /** Replaces every page (click actions are dropped). */
    public void setPages(Hologram h, List<List<String>> pages) {
        h.pages.clear();
        for (List<String> lines : pages) {
            HoloPage p = new HoloPage();
            for (String s : lines) p.lines.add(new HoloLine(s));
            h.pages.add(p);
        }
        if (h.pages.isEmpty()) h.pages.add(new HoloPage());
        changed(h);
    }

    /** Changes a setting ({@link HoloSettings#KEYS}); null value = back to the default. False when not valid. */
    public boolean setSetting(Hologram h, String key, String value) {
        if (!HoloSettings.KEYS.contains(key)) return false;
        if (value == null || value.equalsIgnoreCase("default")) h.settings.remove(key);
        else {
            String v = HoloSettings.normalize(key, value);
            if (v == null) return false;
            h.settings.put(key, v);
        }
        changed(h);
        return true;
    }

    /**
     * Extra MiniMessage tags for a temporary hologram's text, asked per viewer (null = the shared text) each time
     * the text is redrawn ({@code update-interval}, or placeholder-refresh-ticks).
     */
    public void setResolver(Hologram h, Function<ServerPlayer, TagResolver> resolver) {
        h.resolver = resolver;
        changed(h);
    }

    public void move(Hologram h, Loc to) {
        if (to == null || to.level() == null) return;
        h.setLocation(to);
        changed(h);
    }

    public void delete(Hologram h) {
        if (h == null) return;
        unput(h);
        if (!h.temporary()) save();
    }

    /** Deletes every temporary hologram of a module. */
    public void deleteOwnedBy(String owner) {
        for (Hologram h : new ArrayList<>(holograms.values())) if (owner.equalsIgnoreCase(h.owner())) unput(h);
    }

    /** The page a player is on (0-based). */
    public int pageOf(Hologram h, ServerPlayer p) {
        HoloRender r = renders.get(h);
        return r == null ? 0 : r.page(p.getUUID());
    }

    /** Moves a player to a page of a hologram. */
    public void showPage(Hologram h, ServerPlayer p, int page) {
        HoloRender r = renders.get(h);
        if (r != null) r.setPage(p, page);
    }

    // ------------------------------------------------------------------ Damage Numbers setting (Settings menu)

    /** Whether this player sees damage and heal numbers (their Settings toggle). */
    public boolean damageNumbersOn(ServerPlayer p) {
        return damage.shows(p);
    }

    /** Whether the Damage Numbers setting is offered at all. */
    public boolean damageNumbersEnabled() {
        return damage.enabled;
    }

    public void toggleDamageNumbers(ServerPlayer p) {
        damage.toggle(p);
    }

    // ------------------------------------------------------------------ chunks and entities

    /** Hologram entities are never saved; any that turn up when a chunk loads are leftovers. */
    private void onEntityLoad(Entity e, ServerLevel level) {
        if (!owned.contains(e.getUUID()) && tagged(e)) discardLater(level, e);
    }

    /** A line unloading with its chunk: the hologram is rebuilt (minus that line) on the next check. */
    private void onEntityUnload(Entity e) {
        if (!owned.contains(e.getUUID())) return;
        Entity.RemovalReason why = e.getRemovalReason();
        if (why == null || why.shouldDestroy()) {
            // Killed or discarded by something else: heal() puts it back on the next check.
            owned.remove(e.getUUID());
            return;
        }
        owned.remove(e.getUUID());
        String tag = tagOf(e);
        Hologram h = tag == null ? null : hologram(tag);
        if (h != null) Tasks.later(1, () -> {
            HoloRender r = renders.get(h);
            if (r != null) r.despawnAll();
        });
    }

    private void onChunkUnload(ServerLevel level, int cx, int cz) {
        Map<Long, List<Hologram>> m = byChunk.get(net.axther.aftrhrs.core.Worlds.name(level));
        List<Hologram> list = m == null ? null : m.get(chunkKey(cx, cz));
        if (list == null || list.isEmpty()) return;
        List<Hologram> copy = new ArrayList<>(list);
        Tasks.later(1, () -> {
            for (Hologram h : copy) {
                HoloRender r = renders.get(h);
                if (r != null) r.despawnAll();
            }
        });
    }

    private void onQuit(UUID id) {
        for (HoloRender r : renders.values()) r.forget(id);
        lastClick.remove(id);
        damage.forget(id);
    }

    // ------------------------------------------------------------------ clicks

    private void click(ServerPlayer p, Entity e, boolean left) {
        if (authBlocked(p)) return;
        String tag = tagOf(e);
        Hologram h = tag == null ? null : hologram(tag);
        HoloRender r = h == null ? null : renders.get(h);
        if (r == null || !r.viewers.contains(p.getUUID())) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUUID());
        if (last != null && now - last < 250) return;
        lastClick.put(p.getUUID(), now);
        int page = r.page(p.getUUID());
        boolean acted = false;
        for (HoloPage.Action a : h.page(page).actions) {
            if (!a.matches(left)) continue;
            run(p, h, r, page, a);
            acted = true;
        }
        String nav = h.setting(left ? "left-click" : "right-click");
        if (r.pageCount() > 1 && !nav.equals("none") && r.page(p.getUUID()) == page) {
            int to = nav.equals("prev") ? (page - 1 + r.pageCount()) % r.pageCount() : (page + 1) % r.pageCount();
            r.setPage(p, to);
            acted = true;
        }
        if (acted && clickSound != null) Adv.sound(p, clickSound);
    }

    private void run(ServerPlayer p, Hologram h, HoloRender r, int page, HoloPage.Action a) {
        String name = p.getGameProfile().name();
        String v = a.value().replace("{uuid}", p.getUUID().toString()).replace("{player}", name);
        MinecraftServer server = Feather.server();
        switch (a.type()) {
            case "message" -> Adv.send(p, content.text(h, a.value(), page, r.pageCount(), p, tick));
            case "command" -> server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), v.startsWith("/") ? v.substring(1) : v);
            case "console" -> {
                // Runs with full rights: an offline-mode or proxy name could smuggle selector/command text into it.
                if (a.value().contains("{player}") && !SAFE_NAME.matcher(name).matches()) {
                    plugin.getLogger().warn("Hologram {}: console action skipped for {} (name '{}' isn't a plain username; use {uuid} instead of {player}).",
                            h.name(), p.getUUID(), name);
                    return;
                }
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), v.startsWith("/") ? v.substring(1) : v);
            }
            case "menu" -> {
                String menu = v.toLowerCase(Locale.ROOT);
                server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), menu.equals("hub") || menu.equals("main") ? "feather" : menu);
            }
            case "sound" -> {
                Sound s = parseSound(v);
                if (s != null) Adv.sound(p, s);
            }
            case "page" -> {
                String t = v.toLowerCase(Locale.ROOT);
                int cur = r.page(p.getUUID());
                int n = r.pageCount();
                int to;
                if (t.equals("next")) to = (cur + 1) % n;
                else if (t.equals("prev")) to = (cur - 1 + n) % n;
                else {
                    try {
                        to = Integer.parseInt(t) - 1;
                    } catch (NumberFormatException e) {
                        return;
                    }
                }
                r.setPage(p, to);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ for /holo view and the tests

    /** What a player sees of a hologram now: their page and each line's plain text (or its spec). */
    List<String> preview(Hologram h, ServerPlayer viewer) {
        HoloRender r = renders.get(h);
        int page = viewer == null || r == null ? 0 : r.page(viewer.getUUID());
        List<String> out = new ArrayList<>();
        out.add(String.valueOf(page + 1));
        for (HoloLine l : h.page(page).lines) {
            if (l.type() == HoloLine.Type.TEXT) out.add(Adv.plain(content.text(h, l.content(), page, h.pages.size(), viewer, tick)));
            else out.add(l.content());
        }
        return out;
    }

    /** How many entities this hologram has right now (all pages and viewers). */
    int entityCount(Hologram h) {
        HoloRender r = renders.get(h);
        return r == null ? 0 : r.entities().size();
    }
}
