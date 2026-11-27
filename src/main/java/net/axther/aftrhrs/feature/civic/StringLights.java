package net.axther.aftrhrs.feature.civic;

import com.mojang.math.Transformation;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.feature.coast.Parts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * String lights: holding the string-lights item, right-click one post and then another (up to max-length apart): a
 * sagging chain of bulbs hangs between them, with a LIGHT block in every few bulbs' spots (waterlogged under water).
 * Strings are saved in {@code data/civic-strings.yml} (same keys as Paper: the posts' ids and anchors; a post is now
 * the block under its anchor, so old strings find their posts again). The bulbs and wire are unsaved display entities
 * drawn while a player is near and both ends are loaded. Breaking (or losing) either post removes its strings, takes
 * their light blocks away and drops the string-lights item back.
 */
final class StringLights {
    static final String PART = "feather:civic_light_part";

    private static final class Strand {
        final UUID id;
        final String world;
        final UUID postA, postB;
        final Vec3 a, b;
        final List<String> lights = new ArrayList<>();
        final List<Entity> parts = new ArrayList<>();

        Strand(UUID id, String world, UUID postA, UUID postB, Vec3 a, Vec3 b) {
            this.id = id;
            this.world = world;
            this.postA = postA;
            this.postB = postB;
            this.a = a;
            this.b = b;
        }

        boolean rendered() {
            if (parts.isEmpty()) return false;
            for (Entity e : parts) if (e.isRemoved()) return false;
            return true;
        }
    }

    private record Selection(ResourceKey<Level> level, BlockPos post, long tick) {
    }

    private final CivicModule m;
    private final Map<UUID, Strand> strands = new LinkedHashMap<>();
    private final Map<UUID, Selection> selected = new HashMap<>();
    private final Map<String, ResourceKey<Level>> worldIds = new HashMap<>();
    private Path file;
    private boolean unreadable;
    private boolean dirty;

    private boolean enabled;
    private String itemId, bulbId;
    private List<Pattern> posts = List.of();
    private BlockState bulbFallback;
    private float bulbFallbackScale, bulbScale, wireThickness;
    private double anchorHeight, maxLength, spacing, sagPerBlock, maxSag, viewRange;
    private int maxPerPost, lightEvery, lightLevel, selectTicks;
    private BlockState wire;
    private String placeSound, breakSound;

    StringLights(CivicModule m) {
        this.m = m;
        Parts.track(PART);
    }

    private static BlockState block(String name) {
        if (name == null || name.isBlank()) return null;
        Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
        if (id == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(id).map(Block::defaultBlockState).orElse(null);
    }

    void load(Yaml c) {
        enabled = c.getBoolean("enabled", true);
        itemId = c.getString("item", "arcana_string_lights").toLowerCase(Locale.ROOT);
        bulbId = c.getString("bulb", "arcana_string_light_bulb_fx").toLowerCase(Locale.ROOT);
        posts = Coast.globs(c.contains("posts") ? c.getStringList("posts") : List.of("arcana_string_light_post"));
        bulbFallback = block(c.getString("bulb-fallback", "SHROOMLIGHT"));
        bulbFallbackScale = (float) c.getDouble("bulb-fallback-scale", 0.12);
        bulbScale = (float) c.getDouble("bulb-scale", 1.0);
        anchorHeight = c.getDouble("anchor-height", 1.0);
        maxLength = Math.max(1, c.getDouble("max-length", 12));
        maxPerPost = Math.max(1, c.getInt("max-per-post", 4));
        spacing = Math.max(0.25, c.getDouble("spacing", 1.0));
        sagPerBlock = Math.max(0, c.getDouble("sag-per-block", 0.06));
        maxSag = Math.max(0, c.getDouble("max-sag", 1.0));
        wire = block(c.getString("wire-block", "black_concrete"));
        wireThickness = (float) c.getDouble("wire-thickness", 0.025);
        lightEvery = Math.max(1, c.getInt("light-every", 3));
        lightLevel = Math.max(0, Math.min(15, c.getInt("light-level", 12)));
        viewRange = Math.max(8, c.getDouble("view-range", 64));
        selectTicks = Math.max(1, c.getInt("select-seconds", 30)) * 20;
        placeSound = c.getString("place-sound", "block.chain.place");
        breakSound = c.getString("break-sound", "block.chain.break");
    }

    boolean isPost(String id) {
        return enabled && Coast.matches(posts, id);
    }

    boolean isLightsItem(ItemStack held) {
        return enabled && itemId.equals(Coast.id(held));
    }

    void forget(UUID player) {
        selected.remove(player);
    }

    // ------------------------------------------------------------------ worlds (Paper saved world UUIDs)

    /** Paper world UUIDs (uid.dat) of this server's levels, so old strings find their level. */
    private void readWorldIds() {
        worldIds.clear();
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return;
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name = root.getFileName() == null ? "world" : root.getFileName().toString();
        Path parent = root.getParent() == null ? root : root.getParent();
        uid(root.resolve("uid.dat"), Level.OVERWORLD);
        uid(parent.resolve(name + "_nether").resolve("uid.dat"), Level.NETHER);
        uid(parent.resolve(name + "_the_end").resolve("uid.dat"), Level.END);
        for (ServerLevel l : server.getAllLevels()) worldIds.put(l.dimension().identifier().toString(), l.dimension());
    }

    private void uid(Path file, ResourceKey<Level> key) {
        if (!Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file); DataInputStream d = new DataInputStream(in)) {
            worldIds.put(new UUID(d.readLong(), d.readLong()).toString(), key);
        } catch (Exception ignored) {
        }
    }

    private ServerLevel level(Strand s) {
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return null;
        ResourceKey<Level> key = worldIds.get(s.world);
        if (key == null) {
            ServerLevel l = net.axther.aftrhrs.core.Worlds.resolve(s.world);
            if (l != null) return l;
            m.warnOnce("strand-world:" + s.world, "civic-strings.yml: unknown world " + s.world + "; using the overworld.");
            return server.overworld();
        }
        return server.getLevel(key);
    }

    private String worldId(ServerLevel level) {
        for (Map.Entry<String, ResourceKey<Level>> e : worldIds.entrySet()) {
            if (e.getValue() == level.dimension() && !e.getKey().contains(":")) return e.getKey();   // keep Paper's UUID where known
        }
        return level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------------ data

    void loadData() {
        file = m.plugin.dataFile("civic-strings.yml");
        readWorldIds();
        strands.clear();
        Yaml y = SafeYaml.read(file, m.plugin.getLogger(), "string lights");
        unreadable = y == null;
        if (y == null) return;
        Yaml s = y.getConfigurationSection("strings");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            Yaml c = s.getConfigurationSection(k);
            if (c == null) continue;
            try {
                Strand st = new Strand(UUID.fromString(k), c.getString("world"), UUID.fromString(c.getString("post-a")),
                        UUID.fromString(c.getString("post-b")), vec(c.getString("a")), vec(c.getString("b")));
                st.lights.addAll(c.getStringList("lights"));
                strands.put(st.id, st);
            } catch (RuntimeException e) {
                m.plugin.getLogger().warn("civic-strings.yml: skipped a broken string {}: {}", k, e.toString());
            }
        }
    }

    private static Vec3 vec(String s) {
        String[] p = s.split(",");
        return new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
    }

    private static String str(Vec3 v) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", v.x, v.y, v.z);
    }

    /** Writes the strings now and waits for it, when anything changed (shutdown); true when nothing is left unsaved. */
    boolean saveNow() {
        if (!dirty || unreadable || file == null) return !dirty;
        boolean ok = SafeYaml.writeSync(file, null, snapshot(), m.plugin.getLogger());
        if (ok) dirty = false;
        return ok;
    }

    /** A change: snapshot now, written on the data thread (marked dirty again if that fails). */
    void saveIfDirtyAsync() {
        if (!dirty || unreadable || file == null) return;
        dirty = false;
        SafeYaml.writeAsync(file, null, snapshot(), m.plugin.getLogger()).thenAccept(ok -> {
            if (!ok) Tasks.sync(() -> dirty = true);
        });
    }

    /** The whole file as plain maps (server thread; the light lists are copied, nothing is shared). */
    private Object snapshot() {
        Yaml y = new Yaml();
        for (Strand s : strands.values()) {
            String k = "strings." + s.id;
            y.set(k + ".world", s.world);
            y.set(k + ".post-a", s.postA.toString());
            y.set(k + ".post-b", s.postB.toString());
            y.set(k + ".a", str(s.a));
            y.set(k + ".b", str(s.b));
            y.set(k + ".lights", s.lights);   // the live list: snapshot() copies it
        }
        return y.snapshot();
    }

    private void changed() {
        dirty = true;
        saveIfDirtyAsync();
    }

    // ------------------------------------------------------------------ linking

    private Vec3 anchor(BlockPos post) {
        return new Vec3(post.getX() + 0.5, post.getY() + anchorHeight, post.getZ() + 0.5);
    }

    /** The post block a string end hangs from (the block under its anchor). */
    private BlockPos postOf(Vec3 anchor) {
        return BlockPos.containing(anchor.x, anchor.y - anchorHeight + 0.01, anchor.z);
    }

    private static UUID postId(ServerLevel level, BlockPos post) {
        return UUID.nameUUIDFromBytes((level.dimension().identifier() + "@" + post.getX() + "," + post.getY() + "," + post.getZ())
                .getBytes(StandardCharsets.UTF_8));
    }

    private boolean touches(Strand s, ServerLevel level, BlockPos post) {
        ServerLevel l = level(s);
        return l == level && (postOf(s.a).equals(post) || postOf(s.b).equals(post));
    }

    private int count(ServerLevel level, BlockPos post) {
        int n = 0;
        for (Strand s : strands.values()) if (touches(s, level, post)) n++;
        return n;
    }

    boolean click(ServerPlayer p, ServerLevel level, BlockPos post) {
        if (unreadable) {
            m.bar(p, "lights.closed");
            return true;
        }
        long now = level.getGameTime();
        Selection sel = selected.get(p.getUUID());
        BlockPos first = sel == null || now - sel.tick() > selectTicks || sel.level() != level.dimension() ? null : sel.post();
        if (first != null && !isPost(ArcanaContent.id(level.getBlockState(first)))) first = null;
        if (first == null) {
            if (!m.mayBuild(p, level, post, true)) return true;
            if (count(level, post) >= maxPerPost) {
                m.bar(p, "lights.post-full", CivicModule.tag("max", maxPerPost));
                return true;
            }
            selected.put(p.getUUID(), new Selection(level.dimension(), post.immutable(), now));
            m.bar(p, "lights.first", CivicModule.tag("max", (int) maxLength));
            Coast.sound(level, post, "block.chain.hit", 0.6f, 1.4f);
            return true;
        }
        if (first.equals(post)) {
            selected.remove(p.getUUID());
            m.bar(p, "lights.cancelled");
            return true;
        }
        selected.remove(p.getUUID());
        Vec3 a = anchor(first), b = anchor(post);
        if (a.distanceTo(b) > maxLength + 1e-6) {
            m.bar(p, "lights.too-far", CivicModule.tag("max", (int) maxLength));
            return true;
        }
        for (Strand s : strands.values()) {
            if (touches(s, level, first) && touches(s, level, post)) {
                m.bar(p, "lights.already");
                return true;
            }
        }
        if (count(level, first) >= maxPerPost || count(level, post) >= maxPerPost) {
            m.bar(p, "lights.post-full", CivicModule.tag("max", maxPerPost));
            return true;
        }
        BlockPos mid = BlockPos.containing(a.add(b).scale(0.5));
        if (!m.mayBuild(p, level, first, true) || !m.mayBuild(p, level, post, true) || !m.mayBuild(p, level, mid, true)) return true;
        if (!p.isCreative()) {
            ItemStack held = p.getMainHandItem();
            if (!itemId.equals(Coast.id(held))) return true;
            held.shrink(1);
        }
        Strand s = new Strand(UUID.randomUUID(), worldId(level), postId(level, first), postId(level, post), a, b);
        placeLights(s, level, p);
        strands.put(s.id, s);
        changed();
        render(s);
        Coast.sound(level, mid, placeSound, 0.8f, 1.1f);
        m.bar(p, "lights.strung");
        return true;
    }

    // ------------------------------------------------------------------ geometry

    private List<Vec3> points(Strand s) {
        double len = s.a.distanceTo(s.b);
        int n = Math.max(1, (int) Math.round(len / spacing));
        double sag = Math.min(maxSag, sagPerBlock * len);
        List<Vec3> out = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            Vec3 v = s.a.scale(1 - t).add(s.b.scale(t));
            out.add(v.add(0, -sag * 4 * t * (1 - t), 0));
        }
        return out;
    }

    private void placeLights(Strand s, ServerLevel level, ServerPlayer by) {
        if (lightLevel <= 0) return;
        List<Vec3> pts = points(s);
        Set<BlockPos> done = new HashSet<>();
        int segments = pts.size() - 1;
        for (int i = 1; i < segments - 1; i++) {   // not next to the posts
            if ((i - 1) % lightEvery != (lightEvery / 2) % lightEvery) continue;
            BlockPos b = BlockPos.containing(pts.get(i).add(pts.get(i + 1)).scale(0.5));
            if (!done.add(b)) continue;
            if (by != null && !Coast.allowed(by, level, b, ClaimAction.BUILD)) continue;
            Boolean wet = putLight(level, b, null);
            if (wet != null) s.lights.add(b.getX() + "," + b.getY() + "," + b.getZ() + "," + wet);
        }
    }

    /** A LIGHT block at {@code b} if it is air or still water (waterlogged then). Returns whether it is wet, or null. */
    private Boolean putLight(ServerLevel level, BlockPos b, Boolean wantWet) {
        BlockState st = level.getBlockState(b);
        boolean wet;
        if (st.isAir()) wet = Boolean.TRUE.equals(wantWet);
        else if (st.is(Blocks.WATER) && st.getFluidState().isSource()) wet = true;
        else return null;
        level.setBlock(b, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel)
                .setValue(BlockStateProperties.WATERLOGGED, wet), Block.UPDATE_CLIENTS);
        return wet;
    }

    private void removeLights(Strand s, ServerLevel level) {
        if (level == null) return;
        for (String l : s.lights) {
            String[] p = l.split(",");
            BlockPos b = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            BlockState st = level.getBlockState(b);
            if (!st.is(Blocks.LIGHT)) continue;
            boolean wet = st.getValue(BlockStateProperties.WATERLOGGED);
            level.setBlock(b, wet ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        s.lights.clear();
    }

    private void restoreLights(Strand s, ServerLevel level) {
        for (String l : s.lights) {
            String[] p = l.split(",");
            BlockPos b = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            if (level.getBlockState(b).isAir()) putLight(level, b, Boolean.parseBoolean(p[3]));
        }
    }

    // ------------------------------------------------------------------ drawing

    private static boolean loaded(ServerLevel level, Vec3 v) {
        return level.getChunkSource().getChunkNow(BlockPos.containing(v).getX() >> 4, BlockPos.containing(v).getZ() >> 4) != null;
    }

    private boolean loaded(Strand s, ServerLevel level) {
        return loaded(level, s.a) && loaded(level, s.b) && loaded(level, s.a.add(s.b).scale(0.5));
    }

    private void render(Strand s) {
        clear(s);
        ServerLevel level = level(s);
        if (level == null || !loaded(s, level)) return;
        restoreLights(s, level);
        List<Vec3> pts = points(s);
        ItemStack bulb = Coast.item(bulbId, 1);
        String tag = s.id.toString();
        // One bulb per segment, at the middle of its chord. The bulb model hangs from its own (8,8,8) and carries a
        // 1-block piece of wire along model x: turned along the chord and scaled to its length, the pieces join up.
        for (int i = 0; i + 1 < pts.size(); i++) {
            Vec3 p = pts.get(i), q = pts.get(i + 1);
            Vec3 d = q.subtract(p);
            double h = Math.hypot(d.x, d.z);
            float len = (float) d.length();
            Vec3 at = p.add(q).scale(0.5);
            if (bulb != null) {
                float yaw = h < 1e-6 ? 0f : (float) Math.toDegrees(Math.atan2(-d.z, -d.x));
                float tilt = (float) Math.atan2(d.y, Math.max(h, 1e-6));
                float sc = len * bulbScale;
                Display.ItemDisplay e = Parts.item(level, at, yaw, bulb, ItemDisplayContext.NONE,
                        new Transformation(new Vector3f(), new Quaternionf().rotationZ(tilt), new Vector3f(sc, sc, sc), new Quaternionf()), PART, tag);
                init(e);
                s.parts.add(e);
            } else {
                if (wire != null) s.parts.add(wire(level, p, q, tag));
                if (bulbFallback != null) {
                    float sc = bulbFallbackScale;
                    Display.BlockDisplay e = blockDisplay(level, at, bulbFallback,
                            new Transformation(new Vector3f(-sc / 2, -sc, -sc / 2), new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()), tag);
                    s.parts.add(e);
                }
            }
        }
    }

    private void init(Display d) {
        d.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
        d.setViewRange((float) (viewRange / 64.0));
    }

    private Display.BlockDisplay blockDisplay(ServerLevel level, Vec3 at, BlockState state, Transformation tf, String tag) {
        Display.BlockDisplay d = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level);
        d.setPos(at.x, at.y, at.z);
        d.setBlockState(state);
        d.setTransformation(tf);
        init(d);
        d.addTag("feather_civic_light");
        // Through Parts, so the leftover sweep knows it is one of this session's (it removed every fallback bulb and
        // wire the moment it spawned, and the strand was drawn again every second).
        Parts.spawn(level, d, PART, tag);
        return d;
    }

    /** A thin box from p to q. */
    private Entity wire(ServerLevel level, Vec3 p, Vec3 q, String tag) {
        Vec3 d = q.subtract(p);
        float len = (float) d.length();
        Vector3f dir = new Vector3f((float) d.x, (float) d.y, (float) d.z).normalize();
        Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(1, 0, 0), dir);
        float t = wireThickness;
        Matrix4f mat = new Matrix4f().rotate(rot).translate(0, -t / 2, -t / 2).scale(len, t, t);
        Display.BlockDisplay e = blockDisplay(level, p, wire, new Transformation(mat), tag);
        e.setBrightnessOverride(null);
        return e;
    }

    private void clear(Strand s) {
        for (Entity e : s.parts) Parts.remove(e);
        s.parts.clear();
    }

    void clearAllParts() {
        for (Strand s : strands.values()) clear(s);
    }

    /** Every second: draw strings players are near, drop the rest, notice posts that are gone. */
    void sweep() {
        if (strands.isEmpty()) return;
        double r2 = viewRange * viewRange;
        for (Strand s : new ArrayList<>(strands.values())) {
            ServerLevel level = level(s);
            if (level == null || !loaded(s, level)) {
                clear(s);
                continue;
            }
            boolean aOk = isPost(ArcanaContent.id(level.getBlockState(postOf(s.a))));
            boolean bOk = isPost(ArcanaContent.id(level.getBlockState(postOf(s.b))));
            if (!aOk || !bOk) {
                // both ends are loaded, yet a post is missing: it was removed while we didn't see it
                remove(s, level, aOk ? s.b : s.a);
                continue;
            }
            Vec3 mid = s.a.add(s.b).scale(0.5);
            boolean near = false;
            for (ServerPlayer p : level.players()) {
                if (p.position().distanceToSqr(mid) <= r2) {
                    near = true;
                    break;
                }
            }
            if (!near) clear(s);
            else if (!s.rendered()) render(s);
        }
    }

    // ------------------------------------------------------------------ removal

    /** A post went away: its strings come down and drop their item back where it stood. */
    void postGone(ServerLevel level, BlockPos post, String id) {
        if (!isPost(id) || strands.isEmpty()) return;
        for (Strand s : new ArrayList<>(strands.values())) {
            if (touches(s, level, post)) remove(s, level, Vec3.atCenterOf(post));
        }
        selected.values().removeIf(sel -> sel.level() == level.dimension() && sel.post().equals(post));
    }

    private void remove(Strand s, ServerLevel level, Vec3 refundAt) {
        strands.remove(s.id);
        clear(s);
        removeLights(s, level);
        changed();
        if (refundAt != null && level != null) {
            ItemStack item = Coast.item(itemId, 1);
            if (item != null) Tasks.later(1, () -> Coast.drop(level, refundAt, item));
            else m.plugin.getLogger().warn("Could not refund a string of lights at {}: item {} is missing.", refundAt, itemId);
            Coast.sound(level, BlockPos.containing(refundAt), breakSound, 0.7f, 1f);
        }
    }

    // ------------------------------------------------------------------ /civic info

    List<String> describe(ServerLevel level, Vec3 at, double r) {
        List<String> out = new ArrayList<>();
        for (Strand s : strands.values()) {
            if (level(s) != level) continue;
            Vec3 mid = s.a.add(s.b).scale(0.5);
            if (mid.distanceToSqr(at) > r * r) continue;
            out.add("STRING " + s.id.toString().substring(0, 8) + " a=" + str(s.a) + " b=" + str(s.b) + " parts=" + s.parts.size()
                    + " lights=" + String.join(";", s.lights));
        }
        out.add("STRINGS " + strands.size());
        return out;
    }
}
