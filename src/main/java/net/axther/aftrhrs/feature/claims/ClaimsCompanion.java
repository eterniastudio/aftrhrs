package net.axther.aftrhrs.feature.claims;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.net.Companion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Claims for the Companion client ({@code claims} feature, docs/companion-protocol.md). Modded players get the claims
 * around them as data instead of particles, plus their claim tool's state and a "just claimed" effect, and the client
 * draws borders, the selection, the HUD and the map (M) itself. Requests from the client ({@code claim_chunk} from the
 * claim map) go through the same {@link ClaimsModule#claim} checks as the shovel. Players without the feature see no
 * change.
 */
final class ClaimsCompanion {
    /** Claims within this many blocks are sent. */
    private static final int RADIUS = 192;
    /** Resend once the player has moved this far from where the last list was centered. */
    private static final int MOVE = 48;
    /** The claim map may claim chunks up to this far (in chunks) from the player. */
    private static final int MAP_REACH = 12;
    private static final long REQUEST_GAP_MS = 250;

    /** Where a player's current list is centered. Removed (see {@link #invalidate}/{@link #touch}) to resend it. */
    private record Sent(String world, int x, int z) {
    }

    private final ClaimsModule m;
    private final Map<UUID, Sent> sent = new HashMap<>();
    /** Players whose client has the current {@code look} (cleared on reload, refresh request and quit). */
    private final java.util.Set<UUID> looked = new java.util.HashSet<>();
    private final Map<UUID, String> toolSent = new HashMap<>();
    /** When each player last claimed or unclaimed from the map: each change saves and resends claims to everyone near. */
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    private Tasks.Task task;

    ClaimsCompanion(ClaimsModule m) {
        this.m = m;
    }

    void start() {
        stop();
        m.plugin.once("claims.companion", () -> {
            Companion.on("claim_chunk", "claims", (p, msg) -> {
                ClaimsModule a = ClaimsModule.active;
                if (a != null) a.bridge.claimChunk(p, msg);
            });
            Companion.on("unclaim_chunk", "claims", (p, msg) -> {
                ClaimsModule a = ClaimsModule.active;
                if (a != null) a.bridge.unclaimChunk(p, msg);
            });
            Companion.on("claims_refresh", "claims", (p, msg) -> {
                ClaimsModule a = ClaimsModule.active;
                if (a == null) return;
                a.bridge.sent.remove(p.getUUID());
                a.bridge.looked.remove(p.getUUID());
            });
            Companion.onQuit(p -> {
                ClaimsModule a = ClaimsModule.active;
                if (a == null) return;
                a.bridge.sent.remove(p.getUUID());
                a.bridge.looked.remove(p.getUUID());
                a.bridge.toolSent.remove(p.getUUID());
                a.bridge.lastRequest.remove(p.getUUID());
            });
        });
        task = Tasks.timer(20L, 10L, this::tick);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        sent.clear();
        looked.clear();
        toolSent.clear();
    }

    /** Whether this player gets data instead of particles and action-bar text. */
    boolean modded(ServerPlayer p) {
        return Companion.has(p, "claims");
    }

    /** This player's list or budget changed: they get a fresh list on the next tick (without {@code look}). */
    void invalidate(UUID id) {
        if (id != null) sent.remove(id);
    }

    /** Settings changed (reload): everyone gets a fresh list, {@code look} included. */
    void invalidateAll() {
        sent.clear();
        looked.clear();
    }

    /**
     * Claim {@code c} changed (name, trust, flags, shape, owner, ...): every player whose list may hold it gets a fresh
     * one, and so does its owner (budget and claim count). Call it before a change that shrinks or moves a claim and
     * after one that adds land, so both the old and the new shape are covered.
     */
    void touch(Claim c) {
        if (c == null) return;
        touch(c.world, c.areas);
        invalidate(c.owner);
    }

    /**
     * Drops the sent list of everyone in {@code world} whose list area reaches the box around {@code areas}. Matches
     * {@link ClaimRegistry#near}'s chunk-square scan (a claim is listed when any of its chunks is in the square of
     * chunks within {@link #RADIUS}), on the box over all areas, so it never misses a holder.
     */
    private void touch(String world, List<Area> areas) {
        if (areas == null || areas.isEmpty() || sent.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Area a : areas) {
            minX = Math.min(minX, a.minX());
            minZ = Math.min(minZ, a.minZ());
            maxX = Math.max(maxX, a.maxX());
            maxZ = Math.max(maxZ, a.maxZ());
        }
        int cx0 = minX >> 4, cz0 = minZ >> 4, cx1 = maxX >> 4, cz1 = maxZ >> 4;
        sent.values().removeIf(s -> s.world.equals(world)
                && cx1 >= (s.x - RADIUS) >> 4 && cx0 <= (s.x + RADIUS) >> 4
                && cz1 >= (s.z - RADIUS) >> 4 && cz0 <= (s.z + RADIUS) >> 4);
    }

    private void tick() {
        for (ServerPlayer p : m.plugin.onlinePlayers()) {
            if (!modded(p)) continue;
            Sent s = sent.get(p.getUUID());
            String w = Worlds.name(p.level());
            if (s == null || !s.world.equals(w) || Math.abs(s.x - p.getBlockX()) > MOVE || Math.abs(s.z - p.getBlockZ()) > MOVE) {
                sendClaims(p);
            }
            tool(p);
        }
    }

    private void sendClaims(ServerPlayer p) {
        String w = Worlds.name(p.level());
        UUID id = p.getUUID();
        JsonObject msg = Companion.message("claims");
        msg.addProperty("w", w);
        msg.addProperty("x", p.getBlockX());
        msg.addProperty("z", p.getBlockZ());
        msg.addProperty("r", RADIUS);
        msg.addProperty("allowed", m.settings.worldAllowed(w));
        JsonObject budget = new JsonObject();
        budget.addProperty("total", m.total(id));
        budget.addProperty("left", Math.max(0, m.remaining(id)));
        budget.addProperty("used", m.registry.used(id));
        budget.addProperty("claims", m.registry.ownedBy(id).size());
        budget.addProperty("max", m.settings.maxClaims);
        budget.addProperty("perHour", m.settings.chunksPerHour);
        msg.add("budget", budget);
        // The look (tool, colours, limits) only changes on a reload: send it with the first list after joining, a
        // reload or a refresh request only. The client keeps its look when the key is missing.
        if (looked.add(id)) msg.add("look", look());
        JsonArray list = new JsonArray();
        for (Claim c : m.registry.near(w, p.getBlockX(), p.getBlockZ(), RADIUS)) list.add(claim(c, p));
        msg.add("list", list);
        Companion.send(p, msg);
        sent.put(id, new Sent(w, p.getBlockX(), p.getBlockZ()));
    }

    private JsonObject look() {
        JsonObject look = new JsonObject();
        look.addProperty("tool", BuiltInRegistries.ITEM.getKey(m.settings.tool).toString());
        look.addProperty("view", BuiltInRegistries.ITEM.getKey(m.settings.viewItem).toString());
        look.addProperty("own", hex(m.settings.own));
        look.addProperty("trusted", hex(m.settings.trusted));
        look.addProperty("other", hex(m.settings.others));
        look.addProperty("admin", hex(m.settings.adminColor));
        look.addProperty("preview", hex(m.settings.preview));
        look.addProperty("grid", hex(m.settings.grid));
        look.addProperty("minWidth", m.settings.minWidth);
        look.addProperty("minRadius", m.settings.minRadius);
        look.addProperty("maxRadius", m.settings.maxRadius);
        look.addProperty("showSeconds", m.settings.showSeconds);
        return look;
    }

    private JsonObject claim(Claim c, ServerPlayer viewer) {
        UUID v = viewer.getUUID();
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id);
        o.addProperty("name", c.name);
        o.addProperty("owner", m.ownerLabel(c));
        o.addProperty("rel", relation(c, v));
        TrustLevel level = c.levelOf(v);
        if (level != null) o.addProperty("lvl", level.name().toLowerCase(Locale.ROOT));
        o.addProperty("enter", c.flag(ClaimFlag.ENTER_MESSAGE));
        if (c.admin() && c.greeting != null && !c.greeting.isEmpty()) o.addProperty("greet", c.greeting);
        o.addProperty("pvp", c.flag(ClaimFlag.PVP));
        o.add("areas", areas(c.areas));
        return o;
    }

    static String relation(Claim c, UUID v) {
        if (c.admin()) return "admin";
        if (c.isOwner(v)) return "own";
        return c.levelOf(v) != null ? "trusted" : "other";
    }

    /** {@code [minX, minZ, maxX, maxZ, shape]}: shape 0 rectangle, 1 chunk, 2 circle. */
    static JsonArray areas(List<Area> areas) {
        JsonArray arr = new JsonArray();
        for (Area a : areas) arr.add(area(a));
        return arr;
    }

    static JsonArray area(Area a) {
        JsonArray x = new JsonArray();
        x.add(a.minX());
        x.add(a.minZ());
        x.add(a.maxX());
        x.add(a.maxZ());
        x.add(a.round() ? 2 : a.chunk() ? 1 : 0);
        return x;
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ the claim tool

    /** Sends the player's tool state when it changed (mode, first corner / center, resizing). */
    void tool(ServerPlayer p) {
        if (!modded(p)) return;
        ClaimVisuals.Session s = m.visuals.session(p);
        JsonObject msg = Companion.message("claim_tool");
        msg.addProperty("mode", s.mode.name().toLowerCase(Locale.ROOT));
        BlockPos c = s.corner;
        if (c != null && s.cornerLevel == p.level()) {
            JsonArray corner = new JsonArray();
            corner.add(c.getX());
            corner.add(c.getY());
            corner.add(c.getZ());
            msg.add("corner", corner);
            if (s.resizing != null) {
                msg.addProperty("resize", s.resizing.name);
                if (s.mode != ClaimVisuals.Mode.CIRCLE) {
                    JsonArray fixed = new JsonArray();
                    fixed.add(s.fixedX);
                    fixed.add(s.fixedZ);
                    msg.add("fixed", fixed);
                }
            }
        }
        String key = msg.toString();
        if (key.equals(toolSent.get(p.getUUID()))) return;
        toolSent.put(p.getUUID(), key);
        Companion.send(p, msg);
    }

    /** "Show borders" for a while (menu button, /claim show, after claiming). */
    void show(ServerPlayer p) {
        if (!modded(p)) return;
        JsonObject msg = Companion.message("claim_show");
        msg.addProperty("ms", m.settings.showSeconds * 1000L);
        Companion.send(p, msg);
    }

    /**
     * A claim changed shape in front of people: everyone modded nearby sees the effect (a rising wall for a new or grown
     * claim, a falling one for an unclaimed piece).
     */
    void fx(String kind, Claim c, List<Area> areas, ServerPlayer by) {
        ServerLevel w = Worlds.resolve(c.world);
        // The claim as it is now plus the land the effect shows (an unclaimed piece is no longer in c.areas).
        touch(c);
        touch(c.world, areas);
        if (w == null) return;
        for (ServerPlayer p : w.players()) {
            if (!modded(p)) continue;
            Area first = areas.isEmpty() ? null : areas.get(0);
            if (first != null && Math.hypot(p.getX() - first.minX(), p.getZ() - first.minZ()) > RADIUS + 64) continue;
            JsonObject msg = Companion.message("claim_fx");
            msg.addProperty("kind", kind);
            msg.addProperty("id", c.id);
            msg.addProperty("name", c.name);
            msg.addProperty("owner", m.ownerLabel(c));
            msg.addProperty("rel", relation(c, p.getUUID()));
            msg.addProperty("mine", p == by);
            msg.add("areas", areas(areas));
            if (p == by && !c.admin()) msg.addProperty("left", Math.max(0, m.remaining(p.getUUID())));
            Companion.send(p, msg);
            sendClaims(p);
        }
    }

    // ------------------------------------------------------------------ requests from the claim map

    private int[] chunkOf(ServerPlayer p, JsonObject msg) {
        if (msg == null || !msg.has("cx") || !msg.has("cz")) return null;
        int cx, cz;
        try {
            cx = msg.get("cx").getAsInt();
            cz = msg.get("cz").getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
        if (Math.abs((p.getBlockX() >> 4) - cx) > MAP_REACH || Math.abs((p.getBlockZ() >> 4) - cz) > MAP_REACH) return null;
        return new int[]{cx, cz};
    }

    /** At most one claim change from the map per {@link #REQUEST_GAP_MS} per player (the rest are dropped). */
    private boolean tooSoon(ServerPlayer p) {
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(p.getUUID());
        if (last != null && now - last < REQUEST_GAP_MS) return true;
        lastRequest.put(p.getUUID(), now);
        return false;
    }

    private void claimChunk(ServerPlayer p, JsonObject msg) {
        if (tooSoon(p)) return;
        int[] c = chunkOf(p, msg);
        if (c == null) return;
        m.claim(p, p.level(), Area.ofChunk(c[0], c[1]));
    }

    /** No dialog here (the map is open): the reply in chat is the feedback. */
    private void unclaimChunk(ServerPlayer p, JsonObject msg) {
        if (tooSoon(p)) return;
        int[] c = chunkOf(p, msg);
        if (c == null) return;
        // Any claim with land in that chunk (not just its middle block), preferring one the player can unclaim.
        List<Claim> there = m.registry.overlapping(Worlds.name(p.level()), Area.ofChunk(c[0], c[1]), null);
        if (there.isEmpty()) {
            m.text().send(p, "claims.map-nothing-here");
            return;
        }
        Claim claim = there.get(0);
        for (Claim o : there) {
            if (m.isOwnerOrAdmin(p, o)) {
                claim = o;
                break;
            }
        }
        m.unclaimChunk(p, claim, c[0], c[1]);
    }
}
