package net.axther.aftrhrs.feature.fishing;

import com.mojang.math.Transformation;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.BlockIndex;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.feature.coast.Parts;
import net.axther.aftrhrs.feature.coast.SpotData;
import net.axther.aftrhrs.migrate.Legacy;
import net.axther.aftrhrs.migrate.PaperItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fishing furniture: the fish scale (weighs a held fish and submits it for the records; a records board floats above
 * it), trophy plaques (one mounted fish + a label) and aquarium tanks (up to three fish that bob and turn slowly).
 * Clicks on crab pots are passed to {@link CrabPots}. Mounted fish live in {@link SpotData} at the block
 * ({@code feather:fish_display}: a list of item byte arrays, the format Paper kept in the base entity's PDC); the fish
 * displays and labels are never saved and are drawn while the block's chunk is loaded, placed by the furniture's model
 * coordinates (px, 0..16) from the block center turned like the piece.
 */
final class FishFurniture {
    enum Kind { SCALE, PLAQUE, TANK, POT }

    static final String CONTENTS = "feather:fish_display", PART = "feather:fish_part";

    static final class Tracked {
        final ServerLevel level;
        final BlockPos pos;
        final Kind kind;
        final List<Display> parts = new ArrayList<>();
        /** Tank fish sizes (relative to tank.fish-scale), by slot. */
        final List<Float> sizes = new ArrayList<>();
        Display.TextDisplay board;
        List<net.minecraft.network.chat.Component> pages = List.of();
        int page;
        int phase;

        Tracked(ServerLevel level, BlockPos pos, Kind kind) {
            this.level = level;
            this.pos = pos;
            this.kind = kind;
        }
    }

    private final FishingModule m;
    private final BlockIndex index;
    /** Every tracked piece is also in {@link #index} (attach adds it), so an unload drops exactly the chunk's set. */
    private final Map<ResourceKey<Level>, Map<BlockPos, Tracked>> tracked = new HashMap<>();
    /** kindOf(Block) per furniture block ({@link #NONE}: not fishing furniture); cleared when the globs reload. */
    private final IdentityHashMap<Block, Object> kindCache = new IdentityHashMap<>();
    private static final Object NONE = new Object();
    private Tasks.Task task;
    private long ticks;
    /** The raw furniture globs of the last reload: a reload reindexes only when they change. */
    private List<List<String>> globKey;
    /** How many times the loaded chunks were reindexed for a reload (tests). */
    private int reindexes;

    private final List<Pattern> scaleIds = new ArrayList<>(), plaqueIds = new ArrayList<>(), tankIds = new ArrayList<>(), potIds = new ArrayList<>();
    private double boardHeight = 2.2;
    private int boardPerPage = 6, boardCycle = 6, boardView = 24;
    private Vector3f plaquePoint = new Vector3f(8, 9.5f, 13), plaqueLabel = new Vector3f(8, 17, 12);
    private float plaqueScale = 0.7f, plaqueYaw = 0f, plaqueRoll = 0f;
    private double plaqueSizeMin = 0.55, plaqueSizeMax = 1.3, tankSizeMin = 0.55, tankSizeMax = 1.2;
    private boolean plaqueLabels = true;
    private List<Vector3f> tankPoints = List.of(new Vector3f(4.5f, 9, 6), new Vector3f(11.5f, 11, 9), new Vector3f(7.5f, 6.5f, 11));
    private float tankScale = 0.42f, tankBob = 0.04f, tankTurnDeg = 9f;
    private int tankEvery = 20, tankView = 32;
    private final List<Item> tankVanilla = new ArrayList<>();

    FishFurniture(FishingModule m) {
        this.m = m;
        this.index = new BlockIndex(b -> kindOf(b) != null);
        Parts.track(PART);
    }

    private static List<Pattern> globs(List<String> raw, List<String> def) {
        return Coast.globs(raw.isEmpty() ? def : raw);
    }

    private static Vector3f point(List<?> list, Vector3f def) {
        if (list == null || list.size() < 3) return def;
        try {
            return new Vector3f(((Number) list.get(0)).floatValue(), ((Number) list.get(1)).floatValue(), ((Number) list.get(2)).floatValue());
        } catch (RuntimeException e) {
            return def;
        }
    }

    void reload(Yaml y) {
        kindCache.clear();
        scaleIds.clear();
        plaqueIds.clear();
        tankIds.clear();
        potIds.clear();
        List<List<String>> globKey = List.of(y.getStringList("furniture.scales"), y.getStringList("furniture.plaques"),
                y.getStringList("furniture.tanks"), y.getStringList("furniture.crab-pots"));
        scaleIds.addAll(globs(globKey.get(0), List.of("arcana_fish_scale")));
        plaqueIds.addAll(globs(globKey.get(1), List.of("arcana_*_trophy_plaque", "arcana_trophy_plaque_*")));
        tankIds.addAll(globs(globKey.get(2), List.of("arcana_aquarium_tank")));
        potIds.addAll(globs(globKey.get(3), List.of("arcana_crab_pot")));
        boolean globsChanged = !globKey.equals(this.globKey);
        this.globKey = globKey;
        boardHeight = y.getDouble("records-board.height", 2.2);
        boardPerPage = Math.max(1, y.getInt("records-board.per-page", 6));
        boardCycle = Math.max(0, y.getInt("records-board.page-seconds", 6));
        boardView = Math.max(4, y.getInt("records-board.view-distance", 24));
        plaquePoint = point(y.getList("plaque.fish-point"), new Vector3f(8, 9.5f, 13));
        plaqueLabel = point(y.getList("plaque.label-point"), new Vector3f(8, 17, 12));
        plaqueScale = (float) y.getDouble("plaque.fish-scale", 0.7);
        plaqueYaw = (float) y.getDouble("plaque.yaw-offset", 0);
        plaqueRoll = (float) y.getDouble("plaque.roll", 0);
        plaqueLabels = y.getBoolean("plaque.label", true);
        double[] pr = range(y.getList("plaque.size-range"), 0.55, 1.3);
        plaqueSizeMin = pr[0];
        plaqueSizeMax = pr[1];
        double[] tr = range(y.getList("tank.size-range"), 0.55, 1.2);
        tankSizeMin = tr[0];
        tankSizeMax = tr[1];
        List<Vector3f> pts = new ArrayList<>();
        for (Object o : y.getList("tank.points", List.of())) if (o instanceof List<?> l) pts.add(point(l, new Vector3f(8, 8, 8)));
        if (!pts.isEmpty()) tankPoints = List.copyOf(pts);
        tankScale = (float) y.getDouble("tank.fish-scale", 0.42);
        tankBob = (float) y.getDouble("tank.bob", 0.04);
        tankTurnDeg = (float) y.getDouble("tank.turn-degrees", 9);
        tankEvery = Math.max(5, y.getInt("tank.animate-ticks", 20));
        tankView = Math.max(4, y.getInt("tank.animate-distance", 32));
        tankVanilla.clear();
        for (String s : y.getStringList("tank.vanilla-fish")) {
            Item mat = Coast.vanillaItem(s);
            if (mat != null) tankVanilla.add(mat);
        }
        if (task != null) {
            // which pieces are indexed only depends on the furniture globs: unchanged ones keep the index (chunk loads
            // and the placed/removed hooks keep it current) instead of rescanning every loaded chunk
            if (globsChanged) reindex();
            redrawAll();   // sizes, points, species and message texts may have changed
        }
    }

    /**
     * The globs may have changed: re-decide the indexed pieces, index the ready-to-send chunks again, drop pieces that
     * no longer match, attach new ones. The index is revalidated rather than cleared: the rescan reaches only
     * block-ticking chunks, and pieces in loaded border chunks would otherwise drop out until their chunk reloads.
     */
    private void reindex() {
        reindexes++;
        var server = Feather.serverOrNull();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            index.retainValid(level);
            List<LevelChunk> chunks = new ArrayList<>();
            level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunks::add);
            for (LevelChunk c : chunks) index.chunkLoaded(level, c);
            Map<BlockPos, Tracked> map = tracked.get(level.dimension());
            if (map != null) {
                map.values().removeIf(t -> {
                    if (index.contains(level, t.pos)) return false;
                    clear(t);
                    return true;
                });
            }
            for (LevelChunk c : chunks) for (BlockPos p : index.inChunk(level, c.getPos().x(), c.getPos().z())) attach(level, p);
        }
    }

    private static double[] range(List<?> list, double lo, double hi) {
        try {
            if (list != null && list.size() >= 2) {
                lo = ((Number) list.get(0)).doubleValue();
                hi = ((Number) list.get(1)).doubleValue();
            }
        } catch (RuntimeException ignored) {
        }
        lo = Math.max(0.05, lo);
        return new double[]{lo, Math.max(lo, hi)};
    }

    /**
     * The size a mounted fish is drawn at, relative to the furniture's fish-scale: its length scale (weight, or the
     * species' typical weight for an unweighed fish; vanilla fish 1) kept in [lo, hi].
     */
    double displaySize(ItemStack stack, double lo, double hi) {
        FishItems.Fish f = m.items.read(stack);
        if (f == null) return Math.max(lo, Math.min(hi, 1.0));
        FishTable.Species s = m.table.species.get(f.species());
        double grams = f.grams() > 0 ? f.grams() : s != null ? s.typicalGrams() : FishWeight.SIZE_REF_GRAMS;
        return Math.max(lo, Math.min(hi, FishWeight.lengthScale(grams)));
    }

    static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.furniture.chunkLoaded(level, chunk);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.furniture.chunkUnloaded(level, chunk);
        });
        ArcanaContent.hook(id -> FishingModule.active != null && FishingModule.active.furniture.kindOf(id) != null, new FurnitureHook() {
            @Override
            public InteractionResult onUse(FurnitureContext ctx) {
                FishingModule m = FishingModule.active;
                if (m == null || ctx.player() == null || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
                return m.furniture.handle(ctx) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }

            @Override
            public void onPlaced(FurnitureContext ctx) {
                FishingModule m = FishingModule.active;
                if (m != null) m.furniture.placed(ctx);
            }

            @Override
            public void onRemoved(FurnitureContext ctx) {
                FishingModule m = FishingModule.active;
                if (m != null) m.furniture.removed(ctx.level(), ctx.origin(), ctx.id());
            }
        });
        Legacy.onFurniture((level, origin, id, bv, yaw) -> {
            FishingModule m = FishingModule.active;
            if (m != null) m.furniture.migrate(level, origin, id, bv);
        });
        Legacy.converter((level, entity, bv) -> {
            if (!bv.contains(PART)) return false;
            entity.discard();
            return true;
        });
    }

    void start() {
        var server = Feather.serverOrNull();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                List<LevelChunk> chunks = new ArrayList<>();
                level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunks::add);
                for (LevelChunk c : chunks) chunkLoaded(level, c);
            }
        }
        task = Tasks.timer(5L, 5L, this::tick);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Map<BlockPos, Tracked> map : tracked.values()) for (Tracked t : map.values()) clear(t);
        tracked.clear();
        index.clear();
    }

    // ------------------------------------------------------------------ tracking

    Kind kindOf(String id) {
        if (id == null) return null;
        String s = id.toLowerCase(Locale.ROOT);
        if (Coast.matches(scaleIds, s)) return Kind.SCALE;
        if (Coast.matches(plaqueIds, s)) return Kind.PLAQUE;
        if (Coast.matches(tankIds, s)) return Kind.TANK;
        if (Coast.matches(potIds, s)) return Kind.POT;
        return null;
    }

    private Kind kindOf(Block b) {
        if (!(b instanceof FurnitureBlock)) return null;
        Object k = kindCache.get(b);
        if (k == null) {
            Kind v = kindOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath());
            kindCache.put(b, v == null ? NONE : v);
            return v;
        }
        return k == NONE ? null : (Kind) k;
    }

    private void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        if (!index.chunkLoaded(level, chunk)) return;
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        // read the index again when the task runs: a chunk that unloaded meanwhile has no entry left
        Tasks.later(2, () -> {
            for (BlockPos p : index.inChunk(level, cx, cz)) attach(level, p);
        });
    }

    private void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        Set<BlockPos> gone = index.chunkUnloaded(level, chunk);
        Map<BlockPos, Tracked> map = tracked.get(level.dimension());
        if (map == null) return;
        for (BlockPos p : gone) {
            Tracked t = map.remove(p);
            if (t != null) clear(t);
        }
    }

    /** Whether a piece is tracked (tests). */
    boolean isTracked(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Tracked> map = tracked.get(level.dimension());
        return map != null && map.containsKey(pos);
    }

    Tracked attach(ServerLevel level, BlockPos pos) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return null;
        BlockState st = level.getBlockState(pos);
        Kind kind = kindOf(st.getBlock());
        if (kind == null) return null;
        Map<BlockPos, Tracked> map = tracked.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Tracked t = map.get(pos);
        if (t != null && t.kind == kind) return t;
        if (t != null) clear(t);
        t = new Tracked(level, pos.immutable(), kind);
        map.put(t.pos, t);
        index.add(level, t.pos);   // tracked stays a subset of the index (clicks and debug attach unindexed pieces)
        render(t);
        // a pot placed some other way (a command, the migration) gets a record, without owner
        if (kind == Kind.POT && m.crabPots.at(level, pos) == null) m.crabPots.placed(level, pos, null);
        return t;
    }

    private void detach(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Tracked> map = tracked.get(level.dimension());
        Tracked t = map == null ? null : map.remove(pos);
        if (t != null) clear(t);
        index.remove(level, pos);
    }

    private void placed(FurnitureContext ctx) {
        index.add(ctx.level(), ctx.origin());
        Tracked t = attach(ctx.level(), ctx.origin());
        if (t != null && t.kind == Kind.POT) m.crabPots.placed(ctx.level(), ctx.origin(), ctx.player());
    }

    private void removed(ServerLevel level, BlockPos origin, String id) {
        Kind kind = kindOf(id);
        detach(level, origin);
        if (kind == Kind.POT) {
            m.crabPots.broken(level, origin);
            return;
        }
        for (ItemStack s : takeContents(level, origin)) Coast.drop(level, Vec3.atCenterOf(origin), s);
    }

    private void tick() {
        ticks += 5;
        if (ticks % 20 == 0) {
            for (Map<BlockPos, Tracked> map : tracked.values()) {
                for (Tracked t : new ArrayList<>(map.values())) {
                    BlockState now = t.level.getBlockState(t.pos);
                    if (kindOf(now.getBlock()) != t.kind) {
                        clear(t);
                        map.remove(t.pos);
                        // The piece went without its removal hook (a /setblock or /fill: no neighbor update): what it
                        // held drops now instead of waiting in the spot for the next piece placed there.
                        if (!(now.getBlock() instanceof FurnitureBlock)) {
                            index.remove(t.level, t.pos);
                            if (t.kind == Kind.POT) m.crabPots.broken(t.level, t.pos);
                            else for (ItemStack s : takeContents(t.level, t.pos)) Coast.drop(t.level, Vec3.atCenterOf(t.pos), s);
                        }
                        continue;
                    }
                    boolean broken = false;
                    for (Display d : t.parts) if (d.isRemoved()) broken = true;
                    if (broken) render(t);
                    if (t.kind == Kind.SCALE && t.board != null && t.pages.size() > 1 && boardCycle > 0 && ticks % (boardCycle * 20L) == 0) {
                        t.page = (t.page + 1) % t.pages.size();
                        t.board.setText(t.pages.get(t.page));
                    }
                }
            }
        }
        if (ticks % tankEvery == 0) animateTanks();
    }

    // ------------------------------------------------------------------ contents

    List<ItemStack> contents(ServerLevel level, BlockPos pos) {
        return read(SpotData.get(level, pos));
    }

    private static List<ItemStack> read(CompoundTag t) {
        List<ItemStack> out = new ArrayList<>();
        var server = Feather.serverOrNull();
        if (server == null) return out;
        ListTag list = t.getListOrEmpty(CONTENTS);
        for (Tag e : list) {
            if (!(e instanceof ByteArrayTag b)) continue;
            ItemStack s = PaperItems.fromBytes(server, b.getAsByteArray());
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }

    private void setContents(ServerLevel level, BlockPos pos, List<ItemStack> list) {
        CompoundTag t = SpotData.get(level, pos);
        if (list.isEmpty()) t.remove(CONTENTS);
        else {
            ListTag raw = new ListTag();
            for (ItemStack s : list) raw.add(new ByteArrayTag(PaperItems.toBytes(level.getServer(), s)));
            t.put(CONTENTS, raw);
        }
        SpotData.put(level, pos, t);
    }

    private List<ItemStack> takeContents(ServerLevel level, BlockPos pos) {
        CompoundTag t = SpotData.take(level, pos);
        return read(t);
    }

    /** A Paper-era plaque or tank became a block: its mounted fish move along (or drop when it couldn't be placed). */
    private void migrate(ServerLevel level, BlockPos origin, String id, CompoundTag bv) {
        Kind kind = kindOf(id);
        if (kind == null || kind == Kind.POT || kind == Kind.SCALE || !bv.contains(CONTENTS)) return;
        CompoundTag t = new CompoundTag();
        t.put(CONTENTS, bv.get(CONTENTS).copy());
        if (kindOf(level.getBlockState(origin).getBlock()) != kind) {
            for (ItemStack s : read(t)) Coast.drop(level, Vec3.atCenterOf(origin), s);
            return;
        }
        CompoundTag spot = SpotData.get(level, origin);
        spot.put(CONTENTS, t.get(CONTENTS));
        SpotData.put(level, origin, spot);
        index.add(level, origin);
        Tracked tr = attach(level, origin);
        if (tr != null) render(tr);
    }

    // ------------------------------------------------------------------ drawing

    private static float yaw(BlockState st) {
        return st.getBlock() instanceof FurnitureBlock ? FurnitureBlock.yawDegrees(st) : 0f;
    }

    /** World position of a furniture model point (px, 0..16) from the block center, per the old overlay rule. */
    static Vec3 modelPoint(BlockPos pos, float yawDeg, Vector3f px) {
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double a = (px.x - 8) / 16.0, up = (px.y - 8) / 16.0, b = (8 - px.z) / 16.0;
        return new Vec3(pos.getX() + 0.5 + rx * a + fx * b, pos.getY() + 0.5 + up, pos.getZ() + 0.5 + rz * a + fz * b);
    }

    private void clear(Tracked t) {
        for (Display d : t.parts) Parts.remove(d);
        t.parts.clear();
        t.sizes.clear();
        t.board = null;
        t.pages = List.of();
    }

    private void redrawAll() {
        for (Map<BlockPos, Tracked> map : tracked.values()) for (Tracked t : map.values()) render(t);
    }

    void refreshBoards() {
        for (Map<BlockPos, Tracked> map : tracked.values()) for (Tracked t : map.values()) if (t.kind == Kind.SCALE) render(t);
    }

    void render(Tracked t) {
        clear(t);
        switch (t.kind) {
            case SCALE -> renderBoard(t);
            case PLAQUE -> renderPlaque(t);
            case TANK -> renderTank(t);
            case POT -> {
            }
        }
    }

    private Display.ItemDisplay spawnFish(Tracked t, Vec3 at, float yaw, ItemStack stack, Transformation tf) {
        Display.ItemDisplay d = Parts.item(t.level, at, yaw, stack, ItemDisplayContext.FIXED, tf, PART, t.pos.toShortString());
        d.setPosRotInterpolationDuration(0);
        d.setTransformationInterpolationDuration(0);
        t.parts.add(d);
        return d;
    }

    private void renderPlaque(Tracked t) {
        List<ItemStack> list = contents(t.level, t.pos);
        if (list.isEmpty()) return;
        ItemStack fish = list.get(0);
        float yaw = yaw(t.level.getBlockState(t.pos));
        Vec3 at = modelPoint(t.pos, yaw, plaquePoint);
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.toRadians(plaqueRoll));
        float size = (float) (plaqueScale * displaySize(fish, plaqueSizeMin, plaqueSizeMax));
        spawnFish(t, at, yaw + plaqueYaw, fish, new Transformation(new Vector3f(), rot, new Vector3f(size), new Quaternionf()));
        if (!plaqueLabels) return;
        FishItems.Fish f = m.items.read(fish);
        if (f == null) return;
        FishTable.Species s = m.table.species.get(f.species());
        String name = s == null ? FishTable.pretty(f.species()) : s.name();
        String line = m.plugin().text().raw("fishing.plaque.label").replace("<fish>", m.items.color(f.rarity()) + name.replace("<", "\\<"))
                .replace("<weight>", f.grams() > 0 ? FishItems.lb(f.grams()) : "?").replace("<name>", FishItems.nameOf(f.catcher()).replace("<", "\\<"));
        Display.TextDisplay label = Parts.text(t.level, modelPoint(t.pos, yaw, plaqueLabel), Adv.vanilla(m.plugin().text().parse(line)),
                0.5f, 16 / 64f, PART, t.pos.toShortString());
        t.parts.add(label);
    }

    private void renderTank(Tracked t) {
        List<ItemStack> list = contents(t.level, t.pos);
        float yaw = yaw(t.level.getBlockState(t.pos));
        for (int i = 0; i < list.size() && i < tankPoints.size(); i++) {
            t.sizes.add((float) displaySize(list.get(i), tankSizeMin, tankSizeMax));
            spawnFish(t, modelPoint(t.pos, yaw, tankPoints.get(i)), yaw + i * 120f, list.get(i), tankPose(t, i, t.phase));
        }
    }

    private Transformation tankPose(Tracked t, int i, int phase) {
        double a = (phase + i * 7) * 0.35;
        float bob = (float) (Math.sin(a) * tankBob);
        float size = tankScale * (i < t.sizes.size() ? t.sizes.get(i) : 1f);
        // a bigger fish turns less, so its nose and tail stay inside the glass
        float turn = (float) Math.toRadians(Math.sin(a * 0.5 + i) * tankTurnDeg * 3 / Math.max(1f, size / tankScale));
        return new Transformation(new Vector3f(0, bob, 0), new Quaternionf().rotateY(turn), new Vector3f(size), new Quaternionf());
    }

    private void animateTanks() {
        for (Map<BlockPos, Tracked> map : tracked.values()) {
            for (Tracked t : map.values()) {
                if (t.kind != Kind.TANK || t.parts.isEmpty()) continue;
                if (!playerNear(t.level, Vec3.atCenterOf(t.pos), tankView)) continue;
                t.phase++;
                for (int i = 0; i < t.parts.size(); i++) {
                    Display d = t.parts.get(i);
                    if (d.isRemoved()) continue;
                    d.setTransformationInterpolationDelay(0);
                    d.setTransformationInterpolationDuration(tankEvery);
                    d.setTransformation(tankPose(t, i, t.phase));
                }
            }
        }
    }

    private static boolean playerNear(ServerLevel level, Vec3 at, double r) {
        double r2 = r * r;
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(at) <= r2) return true;
        return false;
    }

    private void renderBoard(Tracked t) {
        Text text = m.plugin().text();
        List<net.minecraft.network.chat.Component> pages = new ArrayList<>();
        var list = m.screens.sortedRecords();
        String title = text.raw("fishing.records.board-title");
        if (list.isEmpty()) pages.add(Adv.vanilla(text.parse(title + "\n" + text.raw("fishing.records.board-empty"))));
        for (int i = 0; i < list.size(); i += boardPerPage) {
            StringBuilder b = new StringBuilder(title);
            for (int j = i; j < Math.min(list.size(), i + boardPerPage); j++) b.append('\n').append(m.screens.recordLine(list.get(j).getKey(), list.get(j).getValue()));
            pages.add(Adv.vanilla(text.parse(b.toString())));
        }
        t.pages = pages;
        t.page = 0;
        t.board = Parts.text(t.level, Vec3.atBottomCenterOf(t.pos).add(0, boardHeight, 0), pages.get(0), 1f, boardView / 64f, PART, t.pos.toShortString());
        t.parts.add(t.board);
    }

    // ------------------------------------------------------------------ clicks

    /** True when the click was used. */
    boolean handle(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        Kind kind = kindOf(ctx.id());
        if (kind == null) return false;
        Tracked t = attach(ctx.level(), ctx.origin());
        if (t == null) return false;
        ItemStack held = p.getMainHandItem();
        boolean used = switch (kind) {
            case SCALE -> scaleClick(p, held);
            case PLAQUE -> plaqueClick(p, t, held);
            case TANK -> tankClick(p, t, held);
            case POT -> m.crabPots.click(p, ctx.level(), ctx.origin(), ctx.id());
        };
        if (used) Coast.swing(p);
        return used;
    }

    /** Claims: may this player change what this furniture holds? Tells them when not. */
    boolean allowed(ServerPlayer p, ServerLevel level, BlockPos pos) {
        boolean ok = Coast.allowed(p, level, pos, ClaimAction.CONTAINER);
        if (!ok) {
            m.plugin().text().actionBar(p, "fishing.claimed");
            m.plugin().text().sound(p, "error");
        }
        return ok;
    }

    private boolean scaleClick(ServerPlayer p, ItemStack held) {
        FishItems.Fish f = m.items.read(held);
        if (f == null) {
            m.openRecords(p);
            return true;
        }
        m.items.refresh(held);
        Text text = m.plugin().text();
        FishTable.Species s = m.table.species.get(f.species());
        if (s == null || f.grams() <= 0) {
            text.send(p, "fishing.scale.no-weight");
            return true;
        }
        var fish = Text.component("fish", text.parse(m.items.color(s.rarity()) + s.name().replace("<", "\\<")));
        var weight = Text.unparsed("weight", FishItems.lb(f.grams()));
        m.playSound(p.level(), p.position(), "block.chain.place 0.8 1.4");
        text.send(p, "fishing.scale.weighed", fish, weight);
        FishData.Record r = m.data.records.get(s.id());
        UUID holder = f.catcher() != null ? f.catcher() : p.getUUID();
        String name = holder.equals(p.getUUID()) ? net.axther.aftrhrs.core.Names.name(p) : FishItems.nameOf(holder);
        if (r == null || f.grams() > r.grams()) {
            m.data.records.put(s.id(), new FishData.Record(f.grams(), holder, name, System.currentTimeMillis()));
            m.recordsChanged();
            m.data.save();
            for (ServerPlayer o : m.plugin().onlinePlayers()) {
                if (!m.plugin().authBlocked(o)) text.send(o, "fishing.scale.new-record", fish, weight, Text.unparsed("player", name));
            }
            m.playSound(p.level(), p.position(), "ui.toast.challenge_complete 0.7 1.5");
        } else {
            text.send(p, "fishing.scale.not-record", Text.unparsed("record", FishItems.lb(r.grams())), Text.unparsed("player", r.name()));
        }
        return true;
    }

    private boolean plaqueClick(ServerPlayer p, Tracked t, ItemStack held) {
        List<ItemStack> list = contents(t.level, t.pos);
        Text text = m.plugin().text();
        if (!list.isEmpty()) {
            if (p.isSecondaryUseActive() && held.isEmpty()) {
                if (!allowed(p, t.level, t.pos)) return true;
                setContents(t.level, t.pos, List.of());
                give(p, list.get(0));
                m.playSound(t.level, Vec3.atCenterOf(t.pos), "entity.item_frame.remove_item 0.8 1.0");
                render(t);
                return true;
            }
            if (m.items.read(held) != null) {
                text.actionBar(p, "fishing.plaque.taken");
                return true;
            }
            return false;
        }
        if (m.items.read(held) == null) return false;
        if (!allowed(p, t.level, t.pos)) return true;
        setContents(t.level, t.pos, List.of(takeOne(p, held)));
        m.playSound(t.level, Vec3.atCenterOf(t.pos), "entity.item_frame.add_item 0.8 1.0");
        render(t);
        return true;
    }

    private boolean tankClick(ServerPlayer p, Tracked t, ItemStack held) {
        List<ItemStack> list = contents(t.level, t.pos);
        Text text = m.plugin().text();
        boolean fish = m.items.read(held) != null || tankVanilla.contains(held.getItem()) && m.items.idOf(held) == null;
        if (p.isSecondaryUseActive() && held.isEmpty()) {
            if (list.isEmpty()) return false;
            if (!allowed(p, t.level, t.pos)) return true;
            ItemStack back = list.remove(list.size() - 1);
            setContents(t.level, t.pos, list);
            give(p, back);
            m.playSound(t.level, Vec3.atCenterOf(t.pos), "entity.fish.swim 0.8 1.2");
            render(t);
            return true;
        }
        if (!fish) return false;
        if (list.size() >= tankPoints.size()) {
            text.actionBar(p, "fishing.tank.full", Text.unparsed("max", tankPoints.size()));
            return true;
        }
        if (!allowed(p, t.level, t.pos)) return true;
        list.add(takeOne(p, held));
        setContents(t.level, t.pos, list);
        m.playSound(t.level, Vec3.atCenterOf(t.pos), "entity.player.splash 0.5 1.6");
        render(t);
        return true;
    }

    private static ItemStack takeOne(ServerPlayer p, ItemStack held) {
        ItemStack one = held.copyWithCount(1);
        if (!p.isCreative()) held.shrink(1);
        return one;
    }

    static void give(ServerPlayer p, ItemStack s) {
        if (p.getMainHandItem().isEmpty()) {
            p.setItemInHand(InteractionHand.MAIN_HAND, s);
            return;
        }
        Coast.give(p, s);
    }

    List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Map<BlockPos, Tracked> map : tracked.values()) {
            for (Tracked t : map.values()) {
                out.add(t.kind + " " + ArcanaContent.id(t.level.getBlockState(t.pos)) + " @" + t.pos.getX() + "," + t.pos.getY() + "," + t.pos.getZ()
                        + " fish=" + contents(t.level, t.pos).size() + " displays=" + t.parts.size());
            }
        }
        return out;
    }
}
