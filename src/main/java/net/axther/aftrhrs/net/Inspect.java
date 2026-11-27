package net.axther.aftrhrs.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.ArcanaCropBlock;
import net.axther.aftrhrs.core.Adv;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The look-at card ({@code inspect} feature; client {@code client/hud/InspectHud}): the client asks about the crop,
 * crab pot or station under the crosshair ({@code inspect} with {@code x,y,z}) and gets back what a right-click would
 * tell ({@code inspect_info}). Port of Feather's {@code InspectBridge} + {@code InspectSource}.
 *
 * <p>Modules register a {@link Source} in {@code enable()} ({@code Inspect.register("farming", source)}) and
 * {@link #unregister} it in {@code disable()}; sources are asked in registration order, the first non-null answer wins.
 * Every request is checked here before any source sees it: at most one per 200 ms per player, same level, loaded chunk
 * (never loads one), a candidate block (an Arcana crop, farmland under one, or a furniture block), within the player's
 * block interaction range + 1 and in sight (ray-traced from the eye). Farmland stands for the crop on it. Spectators
 * and dead players get nothing. Anything that fails, or that no source knows, gets {@code none}.
 */
public final class Inspect {
    private static final long MIN_GAP_MS = 200;
    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST = new HashMap<>();
    private static boolean initialized;

    /** Hub art palette for the rows. */
    public static final String SKY = "#8FD3FF", GOOD = "#86E8B0", ROSE = "#FF8FA3", MUTED = "#A49DC2", MOON = "#FFE7A8", TEXT = "#F1EDFF";

    /**
     * Something the card can describe. Reach, sight and the loaded chunk are already checked; a source applies its own
     * checks (claims, as for the click) and says only what the click would say. Return null for "not mine" (the next
     * source is asked) or {@link #none()} for "mine, but nothing to show this player". Server thread; must not change
     * anything a click wouldn't.
     */
    public interface Source {
        /** A block the player looks at ({@code pos} is the block itself: a crop, or any part of a furniture piece). */
        @Nullable JsonObject block(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state);

        /** An entity the player looks at (nothing uses this since furniture became blocks). */
        default @Nullable JsonObject entity(ServerPlayer player, Entity entity) {
            return null;
        }
    }

    private Inspect() {
    }

    /** Called once by {@link Companion#init}: the {@code inspect} message handler. */
    static void init() {
        if (initialized) return;
        initialized = true;
        Companion.on("inspect", "inspect", Inspect::request);
        Companion.onQuit(p -> LAST.remove(p.getUUID()));
    }

    /** Adds (or replaces) a module's source. */
    public static void register(String id, Source source) {
        if (id != null && source != null) SOURCES.put(id, source);
    }

    public static void unregister(String id) {
        SOURCES.remove(id);
    }

    // ------------------------------------------------------------------ the request

    private static void request(ServerPlayer p, JsonObject m) {
        long now = System.currentTimeMillis();
        Long prev = LAST.get(p.getUUID());
        if (prev != null && now - prev < MIN_GAP_MS && now >= prev) return;
        LAST.put(p.getUUID(), now);

        String key;
        JsonObject answer = null;
        if (m.has("e")) {
            String raw = Wire.str(m, "e", "");
            if (raw.length() > 36) return;
            UUID id;
            try {
                id = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                return;
            }
            key = id.toString();
            if (allowed(p)) answer = entity(p, id);
        } else {
            JsonElement x = m.get("x"), y = m.get("y"), z = m.get("z");
            if (!isInt(x) || !isInt(y) || !isInt(z)) return;
            BlockPos pos = new BlockPos(x.getAsInt(), y.getAsInt(), z.getAsInt());
            key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            if (allowed(p)) answer = block(p, pos);
        }
        if (answer == null) answer = none();
        answer.addProperty("t", "inspect_info");
        answer.addProperty("key", key);
        if (!answer.has("none")) answer.addProperty("ttl", 2000);
        Companion.send(p, answer);
    }

    private static boolean isInt(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        double d = e.getAsDouble();
        return d == Math.rint(d) && Math.abs(d) < 30_000_000;
    }

    /** Spectators can't click anything, so they get nothing either. */
    private static boolean allowed(ServerPlayer p) {
        return !p.hasDisconnected() && p.isAlive() && !p.isSpectator();
    }

    private static boolean isCrop(BlockState s) {
        return s.getBlock() instanceof ArcanaCropBlock;
    }

    private static @Nullable JsonObject block(ServerPlayer p, BlockPos looked) {
        ServerLevel level = p.level();
        if (level.isOutsideBuildHeight(looked) || !level.hasChunkAt(looked)) return null;
        BlockState state = level.getBlockState(looked);
        boolean farmland = state.is(Blocks.FARMLAND);
        if (farmland) {
            // Farmland stands only for a crop on it, never for furniture standing there.
            BlockPos up = looked.above();
            if (level.isOutsideBuildHeight(up) || !isCrop(level.getBlockState(up))) return null;
        } else if (!isCrop(state) && !ArcanaContent.isFurniture(state)) {
            return null;
        }
        if (!inSight(p, level, looked, state, p.blockInteractionRange())) return null;
        BlockPos target = farmland ? looked.above() : looked;
        BlockState targetState = farmland ? level.getBlockState(target) : state;
        for (Map.Entry<String, Source> e : new java.util.ArrayList<>(SOURCES.entrySet())) {
            JsonObject o = ask(e.getKey(), () -> e.getValue().block(p, level, target, targetState));
            if (o != null) return o;
        }
        return null;
    }

    private static @Nullable JsonObject entity(ServerPlayer p, UUID id) {
        ServerLevel level = p.level();
        Entity e = level.getEntity(id);
        if (e == null || !e.isAlive() || e instanceof Player) return null;
        if (!level.hasChunkAt(e.blockPosition())) return null;
        double reach = p.entityInteractionRange() + 1;
        Vec3 eye = p.getEyePosition();
        AABB box = e.getBoundingBox();
        double cx = Math.clamp(eye.x, box.minX, box.maxX), cy = Math.clamp(eye.y, box.minY, box.maxY), cz = Math.clamp(eye.z, box.minZ, box.maxZ);
        if (eye.distanceToSqr(cx, cy, cz) > reach * reach) return null;
        if (!p.hasLineOfSight(e)) return null;
        for (Map.Entry<String, Source> s : new java.util.ArrayList<>(SOURCES.entrySet())) {
            JsonObject o = ask(s.getKey(), () -> s.getValue().entity(p, e));
            if (o != null) return o;
        }
        return null;
    }

    private static @Nullable JsonObject ask(String id, java.util.function.Supplier<JsonObject> call) {
        try {
            return call.get();
        } catch (RuntimeException | LinkageError ex) {
            AftrHrs.LOG.warn("Inspect source '{}' failed", id, ex);
            return null;
        }
    }

    /**
     * The block's aim point (the middle of its outline: a low crop near the bottom, the farmland's top) within reach + 1
     * of the eye, and a ray from the eye to it hits that block first (or its crop / farmland partner, or another part
     * of the same furniture piece), or passes it without touching anything else first.
     */
    private static boolean inSight(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state, double reach) {
        Vec3 eye = p.getEyePosition();
        VoxelShape shape = state.getShape(level, pos);
        Vec3 aim = shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
        if (state.is(Blocks.FARMLAND)) aim = new Vec3(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5);
        Vec3 dir = aim.subtract(eye);
        double dist = dir.length();
        if (dist > reach + 1) return false;
        if (dist < 1e-4) return true;
        Vec3 end = eye.add(dir.scale((dist + 0.5) / dist));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) return true;
        BlockPos h = hit.getBlockPos();
        if (h.equals(pos)) return true;
        // A crop and its farmland are one thing to look at.
        if (isCrop(state) && h.equals(pos.below())) return true;
        if (state.is(Blocks.FARMLAND) && h.equals(pos.above())) return true;
        // Another part of the same furniture piece.
        BlockState hs = level.getBlockState(h);
        if (ArcanaContent.isFurniture(state) && ArcanaContent.isFurniture(hs)
                && ArcanaContent.origin(hs, h).equals(ArcanaContent.origin(state, pos))) return true;
        // Something else was hit: fine only if it lies beyond the aim point.
        return hit.getLocation().distanceToSqr(eye) > dist * dist;
    }

    // ------------------------------------------------------------------ building a card

    /** "Mine, but don't show anything" (no claim access, nothing to report). */
    public static JsonObject none() {
        JsonObject o = new JsonObject();
        o.addProperty("none", true);
        return o;
    }

    /** A card: {@code kind} crop|crabpot|station, its title, an optional icon. Add rows with the helpers below. */
    public static JsonObject card(String kind, String title, @Nullable ItemStack icon) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("title", title);
        JsonObject item = icon == null || icon.isEmpty() ? null : Wire.item(icon, 1);
        if (item != null) o.add("icon", item);
        o.add("rows", new JsonArray());
        return o;
    }

    /** A progress bar, {@code v} 0..1, with its label ("Stage 2 of 3"). */
    public static void bar(JsonObject card, double v, String label) {
        bar(card, v, label, null);
    }

    /** A bar with a fill color ({@code #RRGGBB}; the client's default lavender when null). */
    public static void bar(JsonObject card, double v, String label, @Nullable String color) {
        JsonObject r = row(card, "bar");
        r.addProperty("v", Math.max(0, Math.min(1, v)));
        r.addProperty("label", label);
        if (color != null) r.addProperty("color", color);
    }

    /** A line of text in a {@code #RRGGBB} color. */
    public static void text(JsonObject card, String text, String color) {
        text(card, text, color, null);
    }

    /**
     * A line of text with a small glyph before it: {@code drop} (watered), {@code drop_empty} (not watered), {@code sun}
     * (growing), {@code moon} (too dark), {@code warn} (anything else in the way). Null: the client picks by color.
     */
    public static void text(JsonObject card, String text, String color, @Nullable String glyph) {
        JsonObject r = row(card, "text");
        r.addProperty("c", text);
        r.addProperty("color", color);
        if (glyph != null) r.addProperty("g", glyph);
    }

    /** "have of of": what something holds ("3 of 5 caught"). */
    public static void count(JsonObject card, int have, int of, String label) {
        JsonObject r = row(card, "count");
        r.addProperty("have", have);
        r.addProperty("of", of);
        r.addProperty("label", label);
    }

    /** A countdown in ms from now (the client counts it down itself). Only where the click shows a time. */
    public static void timer(JsonObject card, long ms, String label) {
        JsonObject r = row(card, "timer");
        r.addProperty("ms", Math.max(0, ms));
        r.addProperty("label", label);
    }

    private static JsonObject row(JsonObject card, String k) {
        JsonObject r = new JsonObject();
        r.addProperty("k", k);
        card.getAsJsonArray("rows").add(r);
        return r;
    }

    /** Plain text of a message, first letter capitalized ("too dark (light 9+)" becomes "Too dark (light 9+)"). */
    public static String plain(@Nullable Component c) {
        String s = c == null ? "" : Adv.plain(c).strip();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
