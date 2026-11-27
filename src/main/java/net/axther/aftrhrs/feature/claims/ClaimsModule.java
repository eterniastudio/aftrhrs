package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.ClaimsHook;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Worlds;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Land claims (docs/claims.md). Players claim whole chunks ({@code /claim chunk}), rectangles (two corners with the
 * claim tool) or circles (the tool's center, then the edge) and can mix them: touching pieces join one claim with one
 * trust list and one set of flags. Claims cost claim chunks (every started 256 columns), earned by playing. Staff make
 * admin claims (spawn, the bank) that protect everything except what their allow list names. The module answers
 * {@link Feather#claimAllows} for the rest of Feather and enforces protection itself ({@link ClaimProtection}).
 */
public final class ClaimsModule implements FeatherModule, ClaimsHook {
    public static final String USE = "feather.claims";
    public static final String ADMIN = "feather.claims.admin";
    public static final String BYPASS = "feather.claims.bypass";
    public static final String BLOCKS_ADMIN = "feather.claims.blocks.admin";
    /** Most pieces (chunks, rectangles and circles) one claim may be made of; keeps the cost maths and lookups cheap. */
    static final int MAX_AREAS = 64;

    /** The running module (Fabric listeners can't be removed: they check this). */
    static volatile ClaimsModule active;

    final Feather plugin;
    final ClaimSettings settings = new ClaimSettings();
    final ClaimStore store;
    final ClaimRegistry registry;
    final ClaimProtection protection;
    final ClaimVisuals visuals;
    final ClaimMenu menu;
    final ClaimCommands commands;
    /** The Companion side (borders, tool and HUD drawn by the client). */
    final ClaimsCompanion bridge;
    /** Staff who turned "ignore claims" on (/claim bypass). Not saved: off after a relog. */
    final Set<UUID> bypass = new HashSet<>();
    private final Map<UUID, Long> lastDeny = new HashMap<>();
    private final Map<String, TagKey<Block>> tags = new HashMap<>();
    private Tasks.Task accrual;

    public ClaimsModule(Feather plugin) {
        this.plugin = plugin;
        this.store = new ClaimStore(plugin, plugin.dataFile("claims.yml"));
        this.registry = store.registry;
        this.protection = new ClaimProtection(this);
        this.visuals = new ClaimVisuals(this);
        this.menu = new ClaimMenu(this);
        this.commands = new ClaimCommands(this);
        this.bridge = new ClaimsCompanion(this);
    }

    @Override
    public String name() {
        return "Land claims";
    }

    @Override
    public void enable() {
        loadSettings();
        store.load();
        active = this;
        plugin.once("claims.events", () -> {
            ServerPlayConnectionEvents.JOIN.register((h, s, server) -> {
                ClaimsModule m = active;
                if (m != null) m.onJoin(h.player);
            });
            ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
                ClaimsModule m = active;
                if (m != null) m.onQuit(h.player);
            });
            // The tool first: it takes clicks before protection looks at them (old LOWEST vs LOW).
            ClaimVisuals.registerEvents();
            ClaimProtection.registerEvents();
        });
        // Bound per command name: the label may be an alias (/land, /cblocks ...), the old code went by the name.
        for (String cmd : List.of("claim", "unclaim", "trust", "untrust", "claimblocks")) plugin.bind(cmd, commands.handler(cmd));
        visuals.start();
        bridge.start();
        accrual = Tasks.timer(1200L, 1200L, this::accrue);
        plugin.claimsHook(this);
        plugin.provide(Hooks.HomeGuard.class, this::homeAllowed);
        Text text = plugin.text();
        plugin.hub().add(new Hub.Entry("claims", Hub.Group.MAIN, Hub.ORDER_CLAIMS, p -> Perms.has(p, USE),
                p -> text.msg("claims.hub.label"), p -> text.msg("claims.hub.tooltip"), "hub-homes", this::openMenu));
    }

    @Override
    public void reload() {
        loadSettings();
        visuals.start();
        protection.clearCaches();
        // A file that couldn't be read is read again (after it was fixed); otherwise memory is the truth.
        if (store.unreadable) {
            store.close();
            store.load();
        }
        // Look, limits and allowed worlds may have changed: everyone modded gets a full list (look included).
        bridge.invalidateAll();
    }

    @Override
    public void disable() {
        plugin.claimsHook(null);
        plugin.withdraw(Hooks.HomeGuard.class);
        plugin.hub().remove("claims");
        if (accrual != null) accrual.cancel();
        visuals.stop();
        bridge.stop();
        store.close();
        if (active == this) active = null;
    }

    private void loadSettings() {
        settings.load(plugin.loadResourceConfig("claims.yml"), plugin.getLogger());
        tags.clear();
    }

    Text text() {
        return plugin.text();
    }

    /** The Land menu (the hub's Land button, /claim). */
    public void openMenu(ServerPlayer p) {
        menu.open(p);
    }

    /** /sethome: inside someone else's claim it needs Access there ({@code homes-need-access}); tells the player when not. */
    public boolean homeAllowed(ServerPlayer p, Loc where) {
        if (!settings.homesNeedAccess) return true;
        Claim c = registry.at(where);
        if (c == null || bypassing(p) || permits(c, p.getUUID(), null, null, ClaimAction.ACCESS)) return true;
        text().send(p, "claims.home-blocked", Text.unparsed("owner", ownerLabel(c)));
        text().sound(p, "error");
        return false;
    }

    // ------------------------------------------------------------------ ClaimsHook

    @Override
    public boolean allows(ServerPlayer player, Loc where, ClaimAction action) {
        Claim c = registry.at(where);
        if (c == null || bypassing(player)) return true;
        return permits(c, player.getUUID(), where.level(), loadedPos(where), action);
    }

    @Override
    public boolean allows(UUID player, Loc where, ClaimAction action) {
        Claim c = registry.at(where);
        if (c == null) return true;
        ServerPlayer online = plugin.player(player);
        if (online != null && bypassing(online)) return true;
        return permits(c, player, where.level(), loadedPos(where), action);
    }

    @Override
    public boolean claimed(Loc where) {
        return registry.at(where) != null;
    }

    @Override
    public boolean sameClaim(Loc a, Loc b) {
        Claim c = registry.at(a);
        return c != null && c == registry.at(b);
    }

    /** The block position when its chunk is loaded (admin allow lists look at the block), else null. */
    private static BlockPos loadedPos(Loc l) {
        if (l.level() == null) return null;
        BlockPos pos = l.blockPos();
        return l.level().hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? pos : null;
    }

    // ------------------------------------------------------------------ rules

    boolean bypassing(ServerPlayer p) {
        return p != null && bypass.contains(p.getUUID()) && Perms.has(p, BYPASS);
    }

    /** Trust first, then (admin claims) the {@code use} allow list for this block. */
    boolean permits(Claim c, UUID who, Level level, BlockPos pos, ClaimAction action) {
        return permits(c, who, level, pos, action, false);
    }

    /**
     * Trust first, then (admin claims) the allow list for this block: {@code use} for ACCESS/CONTAINER, and
     * {@code break} only when {@code breaking} (a real block break), never for placing, buckets or anything else BUILD.
     */
    boolean permits(Claim c, UUID who, Level level, BlockPos pos, ClaimAction action, boolean breaking) {
        if (c.allows(who, action)) return true;
        return c.admin() && pos != null && level != null && adminAllows(c, level, pos, action, breaking);
    }

    boolean adminAllows(Claim c, Level level, BlockPos pos, ClaimAction action, boolean breaking) {
        Set<String> list = switch (action) {
            case ACCESS, CONTAINER -> c.allowUse;
            case BUILD -> breaking ? c.allowBreak : Set.of();
            default -> Set.of();
        };
        if (list.isEmpty()) return false;
        BlockState state = level.getBlockState(pos);
        String key = blockKey(state.getBlock());
        for (String entry : list) {
            if (entry.equals("bank-chest")) {
                if (level instanceof ServerLevel sl && plugin.isBankBlock(sl, pos)) return true;
            } else if (entry.startsWith("#")) {
                TagKey<Block> tag = tag(entry.substring(1));
                if (tag != null && state.is(tag)) return true;
            } else if (entry.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** A block's allow-list key: the path for vanilla blocks ("chest"), the full id for others ("aftrhrs:crate"). */
    static String blockKey(Block b) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(b);
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    private TagKey<Block> tag(String name) {
        return tags.computeIfAbsent(name, n -> {
            Identifier id = Identifier.tryParse(n.contains(":") ? n : "minecraft:" + n);
            return id == null ? null : TagKey.create(Registries.BLOCK, id);
        });
    }

    /** A player (online) may do this here: trust, bypass or the admin allow list. Optionally tells them why not. */
    boolean check(ServerPlayer p, Level level, BlockPos pos, ClaimAction action, boolean tell) {
        Claim c = registry.at(level, pos);
        if (c == null || bypassing(p)) return true;
        if (permits(c, p.getUUID(), level, pos, action)) return true;
        if (tell) deny(p, c);
        return false;
    }

    /** {@link #check} for breaking this block: an admin claim's {@code break} allow list counts. */
    boolean checkBreak(ServerPlayer p, Level level, BlockPos pos, boolean tell) {
        Claim c = registry.at(level, pos);
        if (c == null || bypassing(p)) return true;
        if (permits(c, p.getUUID(), level, pos, ClaimAction.BUILD, true)) return true;
        if (tell) deny(p, c);
        return false;
    }

    /** {@link #check} at an entity's spot (no block allow lists). */
    boolean check(ServerPlayer p, Entity at, ClaimAction action, boolean tell) {
        Claim c = registry.at(at.level(), at.getBlockX(), at.getBlockZ());
        if (c == null || bypassing(p)) return true;
        if (permits(c, p.getUUID(), null, null, action)) return true;
        if (tell) deny(p, c);
        return false;
    }

    /** "This land belongs to Sunny" on the action bar, at most once per cooldown. */
    void deny(ServerPlayer p, Claim c) {
        long now = System.currentTimeMillis();
        Long last = lastDeny.get(p.getUUID());
        if (last != null && now - last < settings.denyCooldownMs) return;
        lastDeny.put(p.getUUID(), now);
        if (c.admin()) text().actionBar(p, "claims.deny-admin", Text.unparsed("claim", c.name));
        else text().actionBar(p, "claims.deny", Text.unparsed("owner", c.ownerName));
        text().sound(p, "error");
    }

    void denyPvp(ServerPlayer p) {
        long now = System.currentTimeMillis();
        Long last = lastDeny.get(p.getUUID());
        if (last != null && now - last < settings.denyCooldownMs) return;
        lastDeny.put(p.getUUID(), now);
        text().actionBar(p, "claims.deny-pvp");
    }

    boolean isPolice(Entity e) {
        return Pdc.has(e, "feather:bank_police");
    }

    boolean isPet(Entity e) {
        return Pdc.has(e, "feather:pet");
    }

    /** Whether the player may change this claim (trust, flags, name, size): its managers, or admins for admin claims. */
    boolean canManage(ServerPlayer p, Claim c) {
        if (c.admin()) return Perms.has(p, ADMIN);
        return c.allows(p.getUUID(), ClaimAction.MANAGE) || Perms.has(p, ADMIN);
    }

    /** Owner-only things (abandon, unclaim, transfer, managers): the owner, or admins. */
    boolean isOwnerOrAdmin(ServerPlayer p, Claim c) {
        return c.isOwner(p.getUUID()) || Perms.has(p, ADMIN);
    }

    String ownerLabel(Claim c) {
        return c.admin() ? c.name : c.ownerName;
    }

    // ------------------------------------------------------------------ claim chunks

    /** Claim chunks this player has in all (start + earned, capped, + staff bonus). */
    long total(UUID id) {
        ClaimStore.Budget b = store.budgets.get(id);
        long bonus = b == null ? 0 : b.bonus;
        return Math.max(0, earned(id) + bonus);
    }

    /** Claim chunks from starting and playing alone (capped), without the staff bonus. */
    long earned(UUID id) {
        ClaimStore.Budget b = store.budgets.get(id);
        long minutes = b == null ? 0 : b.minutes;
        return Math.min(settings.maxChunks, settings.startChunks + minutes * settings.chunksPerHour / 60);
    }

    /** "1 chunk" / "12 chunks". */
    String chunks(long n) {
        return text().raw(Math.abs(n) == 1 ? "claims.unit-one" : "claims.unit").replace("<n>", String.valueOf(n));
    }

    /** "10 × 12" for a rectangle, "radius 8" for a circle. */
    String size(Area a) {
        if (a.round()) return text().raw("claims.radius").replace("<r>", String.valueOf(a.radius()));
        return a.width() + " × " + a.length();
    }

    /** Refuses a piece that is too small (or a circle that is too big); tells the player. Admin pieces only need to exist. */
    private boolean sizeOk(ServerPlayer p, Area a, boolean admin) {
        if (admin) {
            if (adminSizeOk(net.axther.aftrhrs.core.Adv.of(p), a)) return true;
            text().sound(p, "error");
            return false;
        }
        if (a.chunk()) return true;
        Text text = text();
        if (a.round()) {
            if (a.radius() < settings.minRadius) {
                text.send(p, "claims.too-small-circle", Text.unparsed("min", settings.minRadius), Text.unparsed("r", a.radius()));
            } else if (a.radius() > settings.maxRadius) {
                text.send(p, "claims.too-big-circle", Text.unparsed("max", settings.maxRadius), Text.unparsed("r", a.radius()));
            } else {
                return true;
            }
        } else if (a.width() < settings.minWidth || a.length() < settings.minWidth) {
            text.send(p, "claims.too-small", Text.unparsed("min", settings.minWidth), Text.unparsed("size", size(a)));
        } else {
            return true;
        }
        text.sound(p, "error");
        return false;
    }

    long remaining(UUID id) {
        return total(id) - registry.used(id);
    }

    /** Once a minute: every active (non-AFK) player earns their share of chunks-per-hour. */
    private void accrue() {
        if (store.unreadable || settings.chunksPerHour <= 0) return;
        boolean changed = false;
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (!Perms.has(p, USE) || plugin.isAfk(p) || plugin.authPending(p)) continue;
            UUID id = p.getUUID();
            ClaimStore.Budget b = store.budget(id);
            b.name = Names.name(p);
            if (settings.startChunks + b.minutes * settings.chunksPerHour / 60 >= settings.maxChunks) continue;
            long before = total(id);
            b.minutes++;
            changed = true;
            // Most minutes add a fraction of a chunk: only a player whose total moved gets a new list (budget).
            if (total(id) != before) bridge.invalidate(id);
        }
        if (changed) store.markDirty();
    }

    // ------------------------------------------------------------------ changing claims

    /** Refuses changes while claims.yml couldn't be read. */
    boolean closed(Audience s) {
        if (!store.unreadable) return false;
        text().send(s, "claims.closed");
        return true;
    }

    boolean closed(ServerPlayer p) {
        return closed(net.axther.aftrhrs.core.Adv.of(p));
    }

    String defaultName(ServerPlayer p) {
        String base = text().raw("claims.default-name").replace("<player>", Names.name(p));
        String name = base;
        int n = 2;
        Set<String> taken = new HashSet<>();
        for (Claim c : registry.ownedBy(p.getUUID())) taken.add(c.name.toLowerCase(Locale.ROOT));
        while (taken.contains(name.toLowerCase(Locale.ROOT))) name = base + " " + n++;
        return name;
    }

    /** Whether the whole area lies inside the world border. */
    static boolean insideBorder(ServerLevel world, Area a) {
        WorldBorder border = world.getWorldBorder();
        return a.minX() >= border.getMinX() && a.maxX() + 1 <= border.getMaxX()
                && a.minZ() >= border.getMinZ() && a.maxZ() + 1 <= border.getMaxZ();
    }

    /**
     * Refuses an area that alone costs more than the owner has in all (a corner set before a long trip), before
     * anything scans the land under it. Such an area would fail the budget check anyway.
     */
    private boolean affordable(ServerPlayer p, Area a, UUID owner) {
        long least = Area.chunks(a.blocks());
        if (least <= total(owner)) return true;
        text().send(p, "claims.not-enough", Text.unparsed("need", chunks(least)), Text.unparsed("left", Math.max(0, remaining(owner))));
        text().sound(p, "error");
        return false;
    }

    /** Problems with putting this area here for this owner (others' land, the gap rule); null when fine. */
    private String blocked(ServerPlayer p, String world, Area a, Claim self, UUID owner) {
        for (Claim o : registry.overlapping(world, a, self)) {
            if (owner == null || !owner.equals(o.owner)) {
                text().send(p, "claims.overlap", Text.unparsed("owner", ownerLabel(o)));
                return "overlap";
            }
        }
        if (settings.gap > 0 && owner != null) {
            for (Claim o : registry.overlapping(world, a.grow(settings.gap), self)) {
                if (o.owner != null && !owner.equals(o.owner)) {
                    text().send(p, "claims.too-close", Text.unparsed("owner", o.ownerName), Text.unparsed("gap", settings.gap));
                    return "gap";
                }
            }
        }
        return null;
    }

    /**
     * Claims an area for the player: joins (and merges) the player's claims it touches, or starts a new claim. Checks
     * the world, size, other claims, the gap, the claim count and the budget first. Returns the claim, or null.
     */
    Claim claim(ServerPlayer p, ServerLevel world, Area a) {
        Text text = text();
        if (closed(p) || plugin.authPending(p)) return null;
        if (!Perms.has(p, USE)) {
            text.send(p, "general.no-permission");
            return null;
        }
        String worldName = Worlds.name(world);
        if (!settings.worldAllowed(worldName)) {
            text.send(p, "claims.world-off");
            return null;
        }
        if (!sizeOk(p, a, false)) return null;
        if (!insideBorder(world, a)) {
            text.send(p, "claims.outside-border");
            text.sound(p, "error");
            return null;
        }
        UUID id = p.getUUID();
        if (!affordable(p, a, id)) return null;
        if (blocked(p, worldName, a, null, id) != null) {
            text.sound(p, "error");
            return null;
        }
        List<Claim> join = registry.touching(worldName, a, id);
        List<Area> merged = new ArrayList<>();
        long before = 0, areaBefore = 0;
        for (Claim c : join) {
            merged.addAll(c.areas);
            before += c.cost;
            areaBefore += c.area;
        }
        merged.add(a);
        if (merged.size() > MAX_AREAS) {
            text.send(p, "claims.too-many-pieces", Text.unparsed("max", MAX_AREAS));
            text.sound(p, "error");
            return null;
        }
        long covered = Area.union(merged);
        if (covered <= areaBefore) {
            text.send(p, "claims.already-yours");
            return null;
        }
        // Can be 0 (it fits in a chunk you already pay for) or less (merging claims saves their rounding).
        long delta = Area.chunks(covered) - before;
        if (join.isEmpty() && registry.ownedBy(id).size() >= settings.maxClaims) {
            text.send(p, "claims.too-many", Text.unparsed("max", settings.maxClaims));
            text.sound(p, "error");
            return null;
        }
        long left = remaining(id);
        if (delta > left) {
            text.send(p, "claims.not-enough", Text.unparsed("need", chunks(delta)), Text.unparsed("left", Math.max(0, left)));
            text.sound(p, "error");
            return null;
        }
        Claim target;
        Strict strict = new Strict();
        if (join.isEmpty()) {
            target = new Claim(registry.nextId++, id, Names.name(p), defaultName(p), worldName);
            target.setAreas(List.of(a));
            registry.add(target);
            text.send(p, a.chunk() ? "claims.claimed-chunk" : a.round() ? "claims.claimed-circle" : "claims.claimed-rect",
                    Text.unparsed("name", target.name), Text.unparsed("blocks", chunks(delta)), Text.unparsed("left", remaining(id)),
                    Text.unparsed("size", size(a)));
        } else {
            target = join.get(0);
            for (Claim c : join) bridge.touch(c);
            for (int i = 1; i < join.size(); i++) mergeInto(target, join.get(i), strict);
            List<Area> list = new ArrayList<>(target.areas);
            for (int i = 1; i < join.size(); i++) list.addAll(join.get(i).areas);
            list.add(a);
            for (int i = 1; i < join.size(); i++) registry.remove(join.get(i));
            registry.setAreas(target, list);
            text.send(p, join.size() > 1 ? "claims.joined-merged" : "claims.joined", Text.unparsed("name", target.name),
                    Text.unparsed("blocks", chunks(Math.max(0, delta))), Text.unparsed("left", remaining(id)));
            tellStrict(p, strict);
        }
        text.sound(p, "accepted");
        store.markDirty();
        plugin.advance(p, "event", "claim");
        visuals.flash(p);
        bridge.fx("claim", target, List.of(a), p);
        return target;
    }

    /** What a merge made stricter, to tell the player. */
    static final class Strict {
        final Set<String> names = new java.util.LinkedHashSet<>();
        final Set<ClaimFlag> flags = java.util.EnumSet.noneOf(ClaimFlag.class);
        boolean everyone;

        boolean isEmpty() {
            return names.isEmpty() && flags.isEmpty() && !everyone;
        }
    }

    private static TrustLevel lower(TrustLevel a, TrustLevel b) {
        if (a == null || b == null) return null;
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private static TrustLevel higher(TrustLevel a, TrustLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * {@code from} joins {@code into}, which keeps the stricter of the two: each player gets the lower of what they had
     * in either (everyone's trust too), and a flag stays on only when it was on in both. Notes what changed.
     */
    private static void mergeInto(Claim into, Claim from, Strict strict) {
        TrustLevel pub = lower(into.publicTrust, from.publicTrust);
        Set<UUID> players = new java.util.LinkedHashSet<>(into.trust.keySet());
        players.addAll(from.trust.keySet());
        for (UUID u : players) {
            TrustLevel a = higher(into.trust.get(u), into.publicTrust), b = higher(from.trust.get(u), from.publicTrust);
            TrustLevel keep = lower(a, b);
            String name = into.trustNames.getOrDefault(u, from.trustNames.getOrDefault(u, ""));
            if (a != b) strict.names.add(name.isEmpty() ? u.toString().substring(0, 8) : name);
            if (keep != null && (pub == null || keep.ordinal() > pub.ordinal())) {
                into.trust.put(u, keep);
                into.trustNames.put(u, name);
            } else {
                into.trust.remove(u);
                into.trustNames.remove(u);
            }
        }
        if (into.publicTrust != from.publicTrust) strict.everyone = true;
        into.publicTrust = pub;
        for (ClaimFlag f : ClaimFlag.values()) {
            // The enter message isn't protection: the claim that stays keeps its own.
            if (f == ClaimFlag.ENTER_MESSAGE || into.flag(f) == from.flag(f)) continue;
            if (f.def) into.flags.put(f, false);
            else into.flags.remove(f);
            strict.flags.add(f);
        }
    }

    /** "Merged claims keep the stricter settings: ..." when a merge took anything away. */
    void tellStrict(ServerPlayer p, Strict strict) {
        if (strict.isEmpty()) return;
        Text text = text();
        List<String> parts = new ArrayList<>(3);
        if (!strict.names.isEmpty()) parts.add(text.raw("claims.merge-part-trust").replace("<players>", String.join(", ", strict.names)));
        if (strict.everyone) parts.add(text.raw("claims.merge-part-everyone"));
        if (!strict.flags.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (ClaimFlag f : strict.flags) keys.add(f.key());
            parts.add(text.raw("claims.merge-part-flags").replace("<flags>", String.join(", ", keys)));
        }
        text.send(p, "claims.merge-strict", Text.unparsed("changes", String.join("; ", parts)));
    }

    /**
     * Admin pieces have no budget, but the chunk index files every chunk they touch: a 100,000-block corner (a teleport
     * between the two clicks) froze the server. {@value #ADMIN_MAX_SIDE} blocks a side is more than any spawn needs.
     */
    static final int ADMIN_MAX_SIDE = 8192;

    boolean adminSizeOk(Audience s, Area a) {
        int side = a.round() ? a.radius() * 2 + 1 : Math.max(a.width(), a.length());
        if (side <= ADMIN_MAX_SIDE) return true;
        text().send(s, "claims.admin.too-big", Text.unparsed("size", size(a)), Text.unparsed("max", ADMIN_MAX_SIDE));
        return false;
    }

    /** An admin claim over {@code a}: no owner, no budget. */
    Claim createAdmin(Audience s, ServerLevel world, Area a, String name) {
        if (closed(s)) return null;
        if (!adminSizeOk(s, a)) return null;
        if (registry.find(name) != null && registry.find(name).admin()) {
            text().send(s, "claims.admin.name-taken", Text.unparsed("name", name));
            return null;
        }
        String worldName = Worlds.name(world);
        List<Claim> clash = registry.overlapping(worldName, a, null);
        if (!clash.isEmpty()) {
            text().send(s, "claims.overlap", Text.unparsed("owner", ownerLabel(clash.get(0))));
            return null;
        }
        Claim c = new Claim(registry.nextId++, null, "", name, worldName);
        c.setAreas(List.of(a));
        registry.add(c);
        store.markDirty();
        bridge.touch(c);
        text().send(s, "claims.admin.created", Text.unparsed("name", name), Text.unparsed("size", size(a)), Text.unparsed("id", c.id));
        return c;
    }

    /**
     * Moves one corner of an area (or a circle's edge): {@code old} becomes {@code neu}. Keeps the claim in one piece,
     * checks others' land and the budget, and joins other claims of the owner it now touches.
     */
    boolean resize(ServerPlayer p, Claim c, Area old, Area neu) {
        Text text = text();
        if (closed(p) || c.removed) return false;
        if (!canManage(p, c)) {
            text.send(p, "claims.not-manager");
            return false;
        }
        if (!sizeOk(p, neu, c.admin())) return false;
        if (!c.admin()) {
            if (!settings.worldAllowed(c.world)) {
                text.send(p, "claims.world-off");
                return false;
            }
            ServerLevel world = Worlds.resolve(c.world);
            if (world != null && !insideBorder(world, neu)) {
                text.send(p, "claims.outside-border");
                text.sound(p, "error");
                return false;
            }
            if (!affordable(p, neu, c.owner)) return false;
        }
        if (blocked(p, c.world, neu, c, c.owner) != null) {
            text.sound(p, "error");
            return false;
        }
        List<Area> list = new ArrayList<>(c.areas);
        list.remove(old);
        list.add(neu);
        if (ClaimRegistry.components(list).size() > 1) {
            text.send(p, "claims.resize-split");
            text.sound(p, "error");
            return false;
        }
        List<Claim> join = new ArrayList<>();
        if (!c.admin()) for (Claim o : registry.touching(c.world, neu, c.owner)) if (o != c) join.add(o);
        List<Area> all = new ArrayList<>(list);
        long before = c.cost;
        for (Claim o : join) {
            all.addAll(o.areas);
            before += o.cost;
        }
        if (all.size() > MAX_AREAS) {
            text.send(p, "claims.too-many-pieces", Text.unparsed("max", MAX_AREAS));
            text.sound(p, "error");
            return false;
        }
        long delta = Area.chunks(Area.union(all)) - before;
        // Managers may only grow a piece within what the owner already pays for: shrinking, joining claims or
        // spending (or freeing) the owner's chunks is the owner's call.
        if (!isOwnerOrAdmin(p, c) && (!join.isEmpty() || delta != 0 || !neu.containsArea(old))) {
            text.send(p, "claims.resize-owner-only");
            text.sound(p, "error");
            return false;
        }
        if (!c.admin() && delta > 0 && delta > remaining(c.owner)) {
            text.send(p, "claims.not-enough", Text.unparsed("need", chunks(delta)), Text.unparsed("left", Math.max(0, remaining(c.owner))));
            text.sound(p, "error");
            return false;
        }
        Strict strict = new Strict();
        bridge.touch(c);
        for (Claim o : join) {
            bridge.touch(o);
            mergeInto(c, o, strict);
            registry.remove(o);
        }
        registry.setAreas(c, all);
        store.markDirty();
        text.send(p, "claims.resized", Text.unparsed("name", c.name), Text.unparsed("size", size(neu)),
                Text.unparsed("left", c.admin() ? "∞" : String.valueOf(remaining(c.owner))));
        tellStrict(p, strict);
        text.sound(p, "accepted");
        visuals.flash(p);
        bridge.fx("resize", c, List.of(neu), p);
        return true;
    }

    /** Takes a chunk out of a claim. What's left stays claimed; pieces that no longer touch become their own claims. */
    void unclaimChunk(ServerPlayer p, Claim c, int cx, int cz) {
        if (closed(p) || c.removed || plugin.authPending(p)) return;
        if (!isOwnerOrAdmin(p, c)) {
            text().send(p, "claims.not-owner");
            return;
        }
        Area cut = Area.ofChunk(cx, cz);
        List<Area> left = new ArrayList<>();
        boolean hit = false;
        for (Area a : c.areas) {
            if (a.overlaps(cut) && a.round()) {
                // A circle can't lose a square bite and stay a circle: shrink it with the tool instead.
                text().send(p, "claims.unclaim-circle");
                text().sound(p, "error");
                return;
            }
        }
        for (Area a : c.areas) {
            if (a.overlaps(cut)) {
                hit = true;
                left.addAll(a.minus(cut));
            } else {
                left.add(a);
            }
        }
        if (!hit) return;
        if (left.size() > MAX_AREAS) {
            text().send(p, "claims.too-many-pieces", Text.unparsed("max", MAX_AREAS));
            text().sound(p, "error");
            return;
        }
        long before = c.cost;
        if (left.isEmpty()) {
            bridge.touch(c);
            registry.remove(c);
            store.markDirty();
            text().send(p, "claims.unclaimed-last", Text.unparsed("name", c.name), Text.unparsed("blocks", chunks(before)));
            return;
        }
        List<List<Area>> parts = ClaimRegistry.components(left);
        if (!c.admin() && parts.size() > 1) {
            // Pieces are paid for one by one (every started chunk), so a cut can cost more than it frees.
            long after = 0;
            for (List<Area> part : parts) after += Area.chunks(Area.union(part));
            if (after > before) {
                text().send(p, "claims.unclaim-costs-more", Text.unparsed("name", c.name), Text.unparsed("blocks", chunks(after - before)));
                text().sound(p, "error");
                return;
            }
            if (registry.ownedBy(c.owner).size() - 1 + parts.size() > settings.maxClaims) {
                text().send(p, "claims.unclaim-too-many", Text.unparsed("max", settings.maxClaims), Text.unparsed("pieces", parts.size()));
                text().sound(p, "error");
                return;
            }
        }
        bridge.touch(c);
        registry.setAreas(c, parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            Claim piece = new Claim(registry.nextId++, c.owner, c.ownerName, c.name + " " + (i + 1), c.world);
            piece.trust.putAll(c.trust);
            piece.trustNames.putAll(c.trustNames);
            piece.publicTrust = c.publicTrust;
            piece.flags.putAll(c.flags);
            piece.allowUse.addAll(c.allowUse);
            piece.allowBreak.addAll(c.allowBreak);
            piece.greeting = c.greeting;
            piece.setAreas(parts.get(i));
            registry.add(piece);
            bridge.touch(piece);
        }
        store.markDirty();
        long freed = before - c.cost;
        for (int i = 1; i < parts.size(); i++) freed -= Area.chunks(Area.union(parts.get(i)));
        text().send(p, parts.size() > 1 ? "claims.unclaimed-split" : "claims.unclaimed", Text.unparsed("name", c.name),
                Text.unparsed("blocks", chunks(Math.max(0, freed))), Text.unparsed("pieces", parts.size()));
        text().sound(p, "deleted");
        bridge.fx("unclaim", c, List.of(cut), p);
    }

    void abandon(ServerPlayer p, Claim c) {
        if (closed(p) || c.removed) return;
        registry.remove(c);
        store.markDirty();
        bridge.fx("abandon", c, c.areas, p);
        text().send(p, "claims.abandoned", Text.unparsed("name", c.name), Text.unparsed("blocks", chunks(c.admin() ? 0 : c.cost)));
        text().sound(p, "deleted");
    }

    // ------------------------------------------------------------------ players

    private void onJoin(ServerPlayer p) {
        boolean changed = false;
        String name = Names.name(p);
        for (Claim c : registry.ownedBy(p.getUUID())) {
            if (!name.equals(c.ownerName)) {
                c.ownerName = name;
                changed = true;
                bridge.touch(c); // the owner label others see
            }
        }
        for (Claim c : registry.all()) {
            String known = c.trustNames.get(p.getUUID());
            if (known != null && !known.equals(name)) {
                c.trustNames.put(p.getUUID(), name);
                changed = true;
            }
        }
        if (changed) store.markDirty();
    }

    private void onQuit(ServerPlayer p) {
        UUID id = p.getUUID();
        bypass.remove(id);
        lastDeny.remove(id);
        visuals.forget(id);
    }

    /** Coordinates for lists: "x, z (World)". */
    static String where(Claim c) {
        int[] m = c.center();
        return m[0] + ", " + m[1] + " (" + prettyWorld(c.world) + ")";
    }

    static String prettyWorld(String w) {
        return switch (w) {
            case "world" -> "Overworld";
            case "world_nether" -> "Nether";
            case "world_the_end" -> "The End";
            default -> w;
        };
    }

    /** "2 chunks + 1 circle" style description (only the kinds the claim has). */
    String shape(Claim c) {
        int chunks = c.chunkAreas(), circles = c.roundAreas();
        int rects = c.areas.size() - chunks - circles;
        List<String> parts = new ArrayList<>(3);
        if (chunks > 0) parts.add(piece("chunk", chunks));
        if (rects > 0) parts.add(piece("rect", rects));
        if (circles > 0) parts.add(piece("circle", circles));
        return String.join(text().raw("claims.shape-join"), parts);
    }

    private String piece(String kind, int n) {
        return text().raw("claims.shape-" + kind + (n == 1 ? "-one" : "")).replace("<n>", String.valueOf(n));
    }

    TagResolver claimTags(Claim c, ServerPlayer viewer) {
        TrustLevel lv = c.levelOf(viewer.getUUID());
        String you = c.isOwner(viewer.getUUID()) ? text().raw("claims.level.owner")
                : lv == null ? text().raw("claims.level.none") : text().raw("claims.level." + lv.key());
        return TagResolver.resolver(Text.unparsed("name", c.name), Text.unparsed("owner", ownerLabel(c)),
                Text.unparsed("blocks", chunks(c.cost)), Text.unparsed("where", where(c)), Text.unparsed("shape", shape(c)),
                Text.unparsed("you", you), Text.unparsed("id", c.id), Text.unparsed("trusted", c.trust.size()),
                Text.unparsed("public", c.publicTrust == null ? text().raw("claims.level.none") : text().raw("claims.level." + c.publicTrust.key())));
    }
}
