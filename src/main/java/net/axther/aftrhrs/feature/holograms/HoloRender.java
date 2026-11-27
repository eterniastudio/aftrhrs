package net.axther.aftrhrs.feature.holograms;

import com.mojang.math.Transformation;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The entities of one hologram. Nothing exists while nobody is near: when a player comes within view distance (and
 * may see it), the entities of the page they are on are spawned (never saved, hidden by default: {@link Visibility})
 * and shown to them. A line that looks the same for everyone is one entity shared by every viewer of that page; a line
 * that differs per player ({player}, {@code <name>} ...) gets an entity per viewer. When the last viewer leaves, or the
 * chunk unloads, everything is removed again.
 */
final class HoloRender {
    /**
     * Where one line sits and how it is drawn. {@code bottom} is the lowest point of the space the line takes.
     * {@code cache} holds the rendered frames of a {@link HoloContent#cacheable} line by expanded text (null for the
     * others); slots are rebuilt on every replan (edits, reloads), which drops it.
     */
    record Slot(HoloLine line, double x, double bottom, double z, double height, boolean perViewer, int refresh,
                Map<String, Rendered> cache) {
    }

    /** One rendered text: the Adventure component (to compare frames) and its vanilla form (null: convert on use). */
    record Rendered(Component adv, net.minecraft.network.chat.Component mc) {
        net.minecraft.network.chat.Component vanilla() {
            return mc != null ? mc : Adv.vanilla(adv);
        }
    }

    /** Bounds a line's frame cache (long typewriters): it is cleared when full. */
    private static final int CACHE_MAX = 128;

    /** One page's entities. */
    final class Live {
        final int page;
        final List<Slot> slots;
        final Entity[] shared;
        final Component[] sharedText;
        final Map<UUID, Entity[]> mine = new HashMap<>();
        final Map<UUID, Component[]> mineText = new HashMap<>();
        final Set<UUID> viewers = new HashSet<>();

        Live(int page) {
            this.page = page;
            this.slots = plan.get(page);
            this.shared = new Entity[slots.size()];
            this.sharedText = new Component[slots.size()];
        }
    }

    private final HologramsModule m;
    final Hologram h;
    private List<List<Slot>> plan = List.of();
    /** Each viewer's page (kept while they are online, so walking off and back keeps it). */
    final Map<UUID, Integer> pageOf = new HashMap<>();
    private final Map<UUID, Long> switchedAt = new HashMap<>();
    final Set<UUID> viewers = new HashSet<>();
    final Map<Integer, Live> live = new HashMap<>();
    private Interaction click;
    private double clickWidth, clickHeight;
    /** view-distance and permission, read once per replan (every settings write replans: changed(), reload). */
    private double viewDist, viewDistSq;
    private String permission;

    HoloRender(HologramsModule m, Hologram h) {
        this.m = m;
        this.h = h;
        replan();
    }

    static boolean alive(Entity e) {
        return e != null && !e.isRemoved();
    }

    private ServerPlayer player(UUID id) {
        return m.plugin.player(id);
    }

    // ------------------------------------------------------------------ layout

    /** Works out every page's line positions again (after an edit). Call {@link #despawnAll} first. */
    void replan() {
        List<List<Slot>> out = new ArrayList<>();
        double scale = h.number("scale", 1);
        int pages = h.pages.size();
        boolean dots = pages > 1 && h.flag("page-indicator");
        int forced = (int) h.number("update-interval", 0);
        viewDist = h.number("view-distance", 48);
        viewDistSq = viewDist * viewDist;
        String perm = h.setting("permission");
        permission = perm.isEmpty() || perm.equals("none") ? null : perm;
        clickWidth = 0.6;
        clickHeight = 0.3;
        for (int p = 0; p < pages; p++) {
            List<HoloLine> lines = new ArrayList<>(h.pages.get(p).lines);
            if (dots) lines.add(new HoloLine(m.pageDots(p, pages)));
            List<Slot> slots = new ArrayList<>();
            double top = h.y;
            double width = 0.6;
            for (HoloLine l : lines) {
                double ht = height(l, scale);
                boolean per = m.content.perViewer(l);
                int refresh = m.content.refreshTicks(h, l);
                if (forced > 0 && refresh > 0) refresh = forced;
                Map<String, Rendered> cache = m.content.cacheable(h, l) ? new HashMap<>() : null;
                slots.add(new Slot(l, h.x + l.ox, top - ht + l.oy, h.z + l.oz, ht, per, refresh, cache));
                top -= ht;
                if (l.type() == HoloLine.Type.TEXT) width = Math.max(width, m.content.width(l.content(), scale, (int) h.number("line-width", 200)));
            }
            clickWidth = Math.max(clickWidth, width);
            clickHeight = Math.max(clickHeight, h.y - top);
            out.add(slots);
        }
        plan = out;
    }

    private double height(HoloLine l, double scale) {
        if (l.height() != null) return l.height();
        double base = m.lineHeight(l.type());
        return switch (l.type()) {
            case TEXT -> base * scale * l.rows();
            case ITEM, HEAD, BLOCK -> base * scale * l.scale();
            case ENTITY -> base * l.scale();
            case BLANK -> base * scale;
        };
    }

    /** Total height of a page (blocks). */
    double pageHeight(int page) {
        double total = 0;
        for (Slot s : plan.get(Math.max(0, Math.min(plan.size() - 1, page)))) total += s.height();
        return total;
    }

    int pageCount() {
        return plan.size();
    }

    // ------------------------------------------------------------------ who sees it

    boolean canView(ServerPlayer p, ServerLevel level) {
        if (m.authBlocked(p) || p.level() != level) return false;
        if (permission != null && !Perms.has(p, permission)) return false;
        double dx = p.getX() - h.x, dy = p.getY() - h.y, dz = p.getZ() - h.z;
        return dx * dx + dy * dy + dz * dz <= viewDistSq;
    }

    /** Shows and hides the hologram for the players of its world; spawns and removes entities to match. */
    void track(ServerLevel level, List<ServerPlayer> players, long now) {
        boolean loaded = level != null && level.hasChunk(h.chunkX(), h.chunkZ());
        Set<UUID> want = new HashSet<>();
        if (loaded) for (ServerPlayer p : players) if (canView(p, level)) want.add(p.getUUID());
        for (UUID id : new ArrayList<>(viewers)) if (!want.contains(id)) leave(id, player(id));
        for (ServerPlayer p : players) if (want.contains(p.getUUID()) && !viewers.contains(p.getUUID())) enter(p);
        if (viewers.isEmpty()) {
            despawnAll();
            return;
        }
        int cycle = (int) h.number("autocycle", 0);
        if (cycle > 0 && plan.size() > 1) {
            for (UUID id : new ArrayList<>(viewers)) {
                long since = switchedAt.getOrDefault(id, 0L);
                if (since == 0) switchedAt.put(id, now);
                else if (now - since >= cycle * 1000L) {
                    ServerPlayer p = player(id);
                    if (p != null) setPage(p, (page(id) + 1) % plan.size());
                }
            }
        }
        heal();
    }

    int page(UUID viewer) {
        int p = pageOf.getOrDefault(viewer, 0);
        return Math.max(0, Math.min(plan.size() - 1, p));
    }

    private void enter(ServerPlayer p) {
        UUID id = p.getUUID();
        viewers.add(id);
        switchedAt.put(id, System.currentTimeMillis());
        if (clickable()) {
            ensureClick();
            if (click != null) Visibility.show(p, click);
        }
        showPage(p, page(id));
    }

    /** A viewer left (walked off, lost permission, quit: then {@code p} may be null). */
    void leave(UUID id, ServerPlayer p) {
        hidePage(id, p, page(id));
        viewers.remove(id);
        if (p != null && alive(click)) Visibility.hide(p, click);
        if (viewers.isEmpty()) despawnAll();
    }

    /** Forgets a player who quit. */
    void forget(UUID id) {
        if (viewers.contains(id)) leave(id, null);
        pageOf.remove(id);
        switchedAt.remove(id);
    }

    /** Moves a viewer to a page (and remembers it for when they come back). */
    void setPage(ServerPlayer p, int page) {
        UUID id = p.getUUID();
        int to = Math.max(0, Math.min(plan.size() - 1, page));
        switchedAt.put(id, System.currentTimeMillis());
        if (!viewers.contains(id)) {
            pageOf.put(id, to);
            return;
        }
        int from = page(id);
        if (from == to) return;
        hidePage(id, p, from);
        pageOf.put(id, to);
        showPage(p, to);
    }

    private void showPage(ServerPlayer p, int page) {
        Live l = live.computeIfAbsent(page, Live::new);
        UUID id = p.getUUID();
        l.viewers.add(id);
        for (int i = 0; i < l.slots.size(); i++) {
            Slot s = l.slots.get(i);
            if (s.perViewer() || s.line().type() == HoloLine.Type.BLANK) continue;
            if (!alive(l.shared[i])) {
                l.sharedText[i] = null;
                l.shared[i] = spawn(s, l, null, i);
                if (l.shared[i] != null) for (UUID v : l.viewers) Visibility.show(player(v), l.shared[i]);
            } else Visibility.show(p, l.shared[i]);
        }
        Entity[] mine = new Entity[l.slots.size()];
        Component[] text = new Component[l.slots.size()];
        l.mine.put(id, mine);
        l.mineText.put(id, text);
        for (int i = 0; i < l.slots.size(); i++) {
            Slot s = l.slots.get(i);
            if (!s.perViewer() || s.line().type() == HoloLine.Type.BLANK) continue;
            mine[i] = spawn(s, l, p, i);
            if (mine[i] != null) Visibility.show(p, mine[i]);
        }
    }

    private void hidePage(UUID id, ServerPlayer p, int page) {
        Live l = live.get(page);
        if (l == null) return;
        l.viewers.remove(id);
        Entity[] mine = l.mine.remove(id);
        l.mineText.remove(id);
        if (mine != null) for (Entity e : mine) m.removeEntity(e);
        if (l.viewers.isEmpty()) {
            for (Entity e : l.shared) m.removeEntity(e);
            for (Entity[] es : l.mine.values()) for (Entity e : es) m.removeEntity(e);
            live.remove(page);
            return;
        }
        if (p != null) for (Entity e : l.shared) if (alive(e)) Visibility.hide(p, e);
    }

    /** Removes every entity (the hologram stays; it comes back when someone is near). */
    void despawnAll() {
        for (Live l : live.values()) {
            for (Entity e : l.shared) m.removeEntity(e);
            for (Entity[] es : l.mine.values()) for (Entity e : es) m.removeEntity(e);
        }
        live.clear();
        viewers.clear();
        m.removeEntity(click);
        click = null;
    }

    /** Respawns entities that went missing (a neighboring chunk unloaded, something killed a mob line ...). */
    private void heal() {
        for (Live l : live.values()) {
            for (int i = 0; i < l.slots.size(); i++) {
                Slot s = l.slots.get(i);
                if (s.line().type() == HoloLine.Type.BLANK) continue;
                if (!s.perViewer()) {
                    if (alive(l.shared[i])) continue;
                    l.sharedText[i] = null;
                    l.shared[i] = spawn(s, l, null, i);
                    if (l.shared[i] != null) for (UUID v : l.viewers) Visibility.show(player(v), l.shared[i]);
                } else {
                    for (Map.Entry<UUID, Entity[]> en : l.mine.entrySet()) {
                        Entity[] es = en.getValue();
                        if (alive(es[i])) continue;
                        ServerPlayer p = player(en.getKey());
                        if (p == null) continue;
                        Component[] t = l.mineText.get(en.getKey());
                        if (t != null) t[i] = null;
                        es[i] = spawn(s, l, p, i);
                        Visibility.show(p, es[i]);
                    }
                }
            }
        }
        if (clickable() && !viewers.isEmpty() && !alive(click)) {
            ensureClick();
            if (click != null) for (UUID v : viewers) Visibility.show(player(v), click);
        }
    }

    boolean clickable() {
        return plan.size() > 1 || h.hasActions();
    }

    private void ensureClick() {
        if (alive(click)) return;
        ServerLevel level = h.level();
        if (level == null) return;
        double y = h.y - clickHeight;
        if (!level.hasChunk((int) Math.floor(h.x) >> 4, (int) Math.floor(h.z) >> 4)) return;
        Interaction e = new Interaction(EntityTypes.INTERACTION, level);
        e.snapTo(h.x, y, h.z, 0, 0);
        e.setWidth((float) Math.min(8, clickWidth));
        e.setHeight((float) Math.min(16, clickHeight));
        e.setResponse(true);
        click = add(level, e) instanceof Interaction i ? i : null;
    }

    boolean owns(Entity e) {
        if (e == null) return false;
        if (e == click) return true;
        for (Live l : live.values()) {
            for (Entity s : l.shared) if (e == s) return true;
            for (Entity[] es : l.mine.values()) for (Entity s : es) if (e == s) return true;
        }
        return false;
    }

    /** Every entity this hologram has right now. */
    List<Entity> entities() {
        List<Entity> out = new ArrayList<>();
        if (alive(click)) out.add(click);
        for (Live l : live.values()) {
            for (Entity e : l.shared) if (alive(e)) out.add(e);
            for (Entity[] es : l.mine.values()) for (Entity e : es) if (alive(e)) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ updates

    /** Redraws the text lines due this tick (placeholders, animations). */
    void refresh(long tick) {
        for (Live l : live.values()) {
            for (int i = 0; i < l.slots.size(); i++) {
                Slot s = l.slots.get(i);
                if (s.refresh() <= 0 || tick % s.refresh() != 0 || s.line().type() != HoloLine.Type.TEXT) continue;
                if (!s.perViewer()) setText(l.shared[i], l.sharedText, i, rendered(s, l, null, tick));
                else {
                    for (Map.Entry<UUID, Entity[]> en : l.mine.entrySet()) {
                        ServerPlayer p = player(en.getKey());
                        if (p != null) setText(en.getValue()[i], l.mineText.get(en.getKey()), i, rendered(s, l, p, tick));
                    }
                }
            }
        }
    }

    private static void setText(Entity e, Component[] last, int i, Rendered now) {
        if (!(e instanceof Display.TextDisplay td) || td.isRemoved() || last == null) return;
        if (now.adv() == last[i] || now.adv().equals(last[i])) return;
        last[i] = now.adv();
        td.setText(now.vanilla());
    }

    /** Turns spinning items, heads and blocks a step (the client smooths it over the step). */
    void spin(long tick, int stepTicks) {
        float yaw = (float) (((tick / stepTicks) * 90L) % 360L) + (float) h.number("yaw", 0);
        for (Live l : live.values()) {
            for (int i = 0; i < l.slots.size(); i++) {
                if (!spins(l.slots.get(i).line())) continue;
                turn(l.shared[i], yaw);
                for (Entity[] es : l.mine.values()) turn(es[i], yaw);
            }
        }
    }

    private static void turn(Entity e, float yaw) {
        if (alive(e)) e.setYRot(yaw);
    }

    /** Mob lines never burn (undead in daylight). */
    void calm() {
        for (Live l : live.values()) {
            for (Entity e : l.shared) if (alive(e) && e.isOnFire()) e.clearFire();
            for (Entity[] es : l.mine.values()) for (Entity e : es) if (alive(e) && e.isOnFire()) e.clearFire();
        }
    }

    private boolean spins(HoloLine l) {
        if (l.type() != HoloLine.Type.ITEM && l.type() != HoloLine.Type.HEAD && l.type() != HoloLine.Type.BLOCK) return false;
        return l.spin() != null ? l.spin() : l.type() == HoloLine.Type.ITEM && m.itemsSpin;
    }

    // ------------------------------------------------------------------ spawning

    /**
     * A text line's current frame. A cacheable line (see {@link Slot#cache}) costs only the curly expansion and a map
     * lookup for a frame it has drawn before: no MiniMessage parse, no conversion to a vanilla component. Sharing one
     * vanilla component across entities and frames is fine: nothing changes it after setText.
     */
    private Rendered rendered(Slot s, Live l, ServerPlayer viewer, long tick) {
        Map<String, Rendered> cache = s.cache();
        if (cache == null || viewer != null || h.resolver != null) {
            return new Rendered(m.content.text(h, s.line().content(), l.page, plan.size(), viewer, tick), null);
        }
        String key = m.content.expand(s.line().content(), l.page, plan.size(), tick);
        Rendered r = cache.get(key);
        if (r != null) return r;
        Component adv = m.content.render(h, key, null);
        r = new Rendered(adv, Adv.vanilla(adv));
        if (HoloContent.staticFrame(key)) {
            if (cache.size() >= CACHE_MAX) cache.clear();
            cache.put(key, r);
        }
        return r;
    }

    /** Spawns one line's entity at its spot (null for an unloaded chunk or a spec that doesn't resolve). */
    private Entity spawn(Slot s, Live l, ServerPlayer viewer, int index) {
        ServerLevel level = h.level();
        if (level == null) return null;
        HoloLine line = s.line();
        double scale = h.number("scale", 1);
        float yaw = (float) h.number("yaw", 0);
        Display.BillboardConstraints billboard = HoloSettings.billboard(h.setting("billboard"));
        try {
            switch (line.type()) {
                case TEXT -> {
                    if (!loaded(level, s.x(), s.z())) return null;
                    Rendered r = rendered(s, l, viewer, m.tick());
                    Component t = r.adv();
                    Display.TextDisplay e = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
                    e.snapTo(s.x(), s.bottom(), s.z(), yaw, 0);
                    look(e, billboard);
                    e.setText(r.vanilla());
                    e.setLineWidth((int) h.number("line-width", 200));
                    String bg = h.setting("background");
                    byte flags = 0;
                    if (bg.equals("default")) flags |= Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND;
                    else {
                        Integer c = bg.equals("none") ? null : HoloSettings.color(bg);
                        e.setBackgroundColor(c != null ? c : 0);
                    }
                    if (h.flag("shadow")) flags |= Display.TextDisplay.FLAG_SHADOW;
                    if (h.flag("see-through")) flags |= Display.TextDisplay.FLAG_SEE_THROUGH;
                    flags |= HoloSettings.alignment(h.setting("alignment"));
                    e.setFlags(flags);
                    if (scale != 1) e.setTransformation(scaled((float) scale, 0));
                    if (viewer == null) l.sharedText[index] = t;
                    else {
                        Component[] mt = l.mineText.get(viewer.getUUID());
                        if (mt != null) mt[index] = t;
                    }
                    return add(level, e);
                }
                case ITEM, HEAD -> {
                    ItemStack item = line.type() == HoloLine.Type.ITEM ? m.content.item(line.id(), viewer) : m.content.head(line.id(), viewer);
                    if (item == null) {
                        m.badLine(h, line);
                        return null;
                    }
                    if (!loaded(level, s.x(), s.z())) return null;
                    float size = (float) (scale * line.scale());
                    boolean spin = spins(line);
                    Display.ItemDisplay e = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
                    e.snapTo(s.x(), s.bottom() + s.height() / 2, s.z(), yaw, 0);
                    look(e, spin ? Display.BillboardConstraints.FIXED : billboard);
                    e.setItemStack(item);
                    e.setItemTransform(ItemDisplayContext.FIXED);
                    e.setTransformation(scaled(size, 0));
                    if (spin) e.setPosRotInterpolationDuration(Math.min(59, m.spinTicks));
                    return add(level, e);
                }
                case BLOCK -> {
                    BlockState data = HoloContent.block(line.id());
                    if (data == null) {
                        m.badLine(h, line);
                        return null;
                    }
                    if (!loaded(level, s.x(), s.z())) return null;
                    float size = (float) (0.5 * scale * line.scale());
                    double lift = Math.max(0, (s.height() - size) / 2);
                    boolean spin = spins(line);
                    Display.BlockDisplay e = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
                    e.snapTo(s.x(), s.bottom() + lift, s.z(), yaw, 0);
                    look(e, spin ? Display.BillboardConstraints.FIXED
                            : (billboard == Display.BillboardConstraints.CENTER ? Display.BillboardConstraints.VERTICAL : billboard));
                    e.setBlockState(data);
                    e.setTransformation(new Transformation(new Vector3f(-size / 2, 0, -size / 2), null, new Vector3f(size, size, size), null));
                    if (spin) e.setPosRotInterpolationDuration(Math.min(59, m.spinTicks));
                    return add(level, e);
                }
                case ENTITY -> {
                    if (!loaded(level, s.x(), s.z())) return null;
                    return mob(level, line, s.x(), s.bottom(), s.z(), yaw);
                }
                default -> {
                    return null;
                }
            }
        } catch (RuntimeException e) {
            m.plugin.getLogger().warn("Hologram {}: could not show line '{}'", h.name(), line.content(), e);
            return null;
        }
    }

    private Entity mob(ServerLevel level, HoloLine line, double x, double y, double z, float yaw) {
        String id = line.id();
        EntityType<?> type = id.toLowerCase(Locale.ROOT).startsWith("mythic:") ? ArcanaEntities.byMythicId(id.substring(7)) : entityType(id);
        if (type == null) {
            m.badLine(h, line);
            return null;
        }
        Entity e = type.create(level, EntitySpawnReason.COMMAND);
        if (e == null) {
            m.badLine(h, line);
            return null;
        }
        e.snapTo(x, y, z, yaw, 0);
        if (e instanceof Mob mob) {
            // Zombie kinds would otherwise turn into chicken jockeys: grabbing a real chicken nearby or adding a new,
            // untracked one that is saved with the world.
            net.minecraft.world.entity.SpawnGroupData group = mob instanceof net.minecraft.world.entity.monster.zombie.Zombie
                    ? new net.minecraft.world.entity.monster.zombie.Zombie.ZombieGroupData(false, false) : null;
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), EntitySpawnReason.COMMAND, group);
            // Anything else that came riding along (spider jockeys) stays out of the hologram.
            if (mob.isPassenger()) mob.stopRiding();
            for (Entity rider : new java.util.ArrayList<>(mob.getPassengers())) {
                rider.stopRiding();
                rider.discard();
            }
        }
        calm(e, yaw);
        return add(level, e);
    }

    /** A mob line: no AI, no gravity, silent, can't be hurt, despawned or saved. */
    private static void calm(Entity e, float yaw) {
        e.setSilent(true);
        e.setPermanentlyInvulnerable(true);
        e.setNoGravity(true);
        e.setYRot(yaw);
        if (e instanceof LivingEntity le) {
            le.setYHeadRot(yaw);
            le.setYBodyRot(yaw);
        }
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            mob.setCanPickUpLoot(false);
        }
    }

    private static final Map<EntityType<?>, Boolean> LIVING = new HashMap<>();

    /** A living, summonable mob type ({@code zombie}, {@code minecraft:zombie}, {@code aftrhrs:emberkit}), or null. */
    static EntityType<?> entityType(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (!key.matches("([a-z0-9_.-]+:)?[a-z0-9_/.-]+")) return null;
        Identifier rl = Identifier.tryParse(key);
        if (rl == null) return null;
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
        if (t == null || !t.canSummon()) return null;
        return living(t) ? t : null;
    }

    /** Whether instances of the type are mobs (not players, armor stands, items, displays ...). Cached. */
    private static boolean living(EntityType<?> t) {
        Boolean known = LIVING.get(t);
        if (known != null) return known;
        boolean ok = false;
        try {
            ServerLevel level = Feather.server().overworld();
            Entity probe = t.create(level, EntitySpawnReason.COMMAND);
            ok = probe instanceof LivingEntity && !(probe instanceof Player) && !(probe instanceof ArmorStand);
            if (probe != null) probe.discard();
        } catch (RuntimeException ignored) {
            // a type that can't be made outside its usual spawn
        }
        LIVING.put(t, ok);
        return ok;
    }

    private static boolean loaded(ServerLevel level, double x, double z) {
        return level.hasChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    /**
     * Every hologram entity: not saved ({@link FeatherEvents#TRANSIENT_TAG}), hidden until shown to a viewer, tagged
     * with its hologram, owned before it enters the level (so the load listener never mistakes it for a leftover).
     */
    private Entity add(ServerLevel level, Entity e) {
        e.addTag(FeatherEvents.TRANSIENT_TAG);
        Pdc.putString(e, HologramsModule.TAG, h.name());
        Visibility.hideByDefault(e);
        m.adopt(e);
        if (!level.addFreshEntity(e)) {
            m.removeEntity(e);
            return null;
        }
        return e;
    }

    private void look(Display d, Display.BillboardConstraints billboard) {
        d.setBillboardConstraints(billboard);
        d.setViewRange((float) Math.max(0.05, viewDist / 64.0));
        String b = h.setting("brightness");
        if (!b.equals("natural")) {
            int v = (int) Math.max(0, Math.min(15, h.number("brightness", 15)));
            d.setBrightnessOverride(new Brightness(v, v));
        }
        d.setShadowRadius(0);
    }

    private static Transformation scaled(float scale, float lift) {
        return new Transformation(new Vector3f(0, lift, 0), null, new Vector3f(scale, scale, scale), null);
    }
}
