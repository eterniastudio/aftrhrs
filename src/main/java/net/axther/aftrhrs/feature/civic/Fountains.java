package net.axther.aftrhrs.feature.civic;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.BlockIndex;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.feature.coast.SpotData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Fountains: a basin with any tiers stacked on its center column and a spout on top is "assembled" and splashes
 * (falling water from the spout and tier rims, splashes and a little mist on the basin, a quiet water sound) for the
 * players within range only. A basin linked to the server goal ({@code /civic fountain goal}) sprays higher and lights
 * its tiers at 25/50/75/100%, with a LIGHT block above the spout. Right-clicking a basin with a coin is a wish: the coin
 * goes into the bank's vault reserve and the player gets Lucky Lure ({@code feather:lucky_lure_until}), once an hour.
 * The basin's center block is the basin itself now, so the first tier sits on top of it.
 */
final class Fountains {
    enum Kind { BASIN, TIER, SPOUT, DRINKING }

    static final String LURE = "feather:lucky_lure_until", TOSS = "feather:civic_coin_toss_at",
            GOAL = "feather:civic_fountain_goal", LIGHT = "feather:civic_fountain_light";

    /** A loaded basin or drinking fountain. */
    private static final class Piece {
        final ServerLevel level;
        final BlockPos pos;
        final Kind kind;
        final List<BlockPos> tiers = new ArrayList<>();
        BlockPos spout;
        int goalTier;
        int soundTick;
        int cycle;

        Piece(ServerLevel level, BlockPos pos, Kind kind) {
            this.level = level;
            this.pos = pos;
            this.kind = kind;
        }
    }

    private final CivicModule m;
    private final BlockIndex index;
    /** Every tracked piece is also in {@link #index} (track adds it), so an unload drops exactly the chunk's set. */
    private final Map<ResourceKey<Level>, Map<BlockPos, Piece>> pieces = new HashMap<>();
    /** kindOf(Block) per furniture block ({@link #NONE}: not a fountain part); cleared when the globs reload. */
    private final IdentityHashMap<Block, Object> kindCache = new IdentityHashMap<>();
    private static final Object NONE = new Object();
    /** Set by {@link #scanAll}, cleared by {@link #clear}: a reload while running indexes the loaded chunks again. */
    private boolean scanned;
    /** The raw basins / tiers / spouts / drinking globs of the last load: a reload reindexes only when they change. */
    private List<List<String>> globKey;
    /** How many times the loaded chunks were reindexed for a reload (tests). */
    private int reindexes;
    private int now;

    private boolean enabled;
    private List<Pattern> basins = List.of(), tiers = List.of(), spouts = List.of(), drinking = List.of();
    private double basinWaterH, basinR, tierRimH, tierR, spoutTopH, sideJetH, sideJetR, drinkTopH, range;
    private int interval, soundInterval;
    private String sound;
    private float soundVolume;
    private SimpleParticleType stream, splash, mist, glow, sparkle;
    private final double[] spray = {0.35, 0.6, 0.85, 1.1, 1.4};
    private final int[] goalLight = {0, 7, 10, 13, 15};
    private boolean tossEnabled;
    private long lureMs, cooldownMs;
    private String tossSound, splashSound;

    Fountains(CivicModule m) {
        this.m = m;
        this.index = new BlockIndex(b -> {
            Kind k = kindOf(b);
            return k == Kind.BASIN || k == Kind.DRINKING;
        });
    }

    void load(Yaml c, Yaml toss) {
        kindCache.clear();
        enabled = c.getBoolean("enabled", true);
        List<List<String>> globKey = List.of(c.getStringList("basins"), c.getStringList("tiers"), c.getStringList("spouts"), c.getStringList("drinking"));
        basins = Coast.globs(globKey.get(0));
        tiers = Coast.globs(globKey.get(1));
        spouts = Coast.globs(globKey.get(2));
        drinking = Coast.globs(globKey.get(3));
        basinWaterH = c.getDouble("basin-water-height", 0.44);
        basinR = c.getDouble("basin-water-radius", 1.15);
        tierRimH = c.getDouble("tier-rim-height", 0.875);
        tierR = c.getDouble("tier-rim-radius", 0.55);
        spoutTopH = c.getDouble("spout-top-height", 0.9);
        sideJetH = c.getDouble("spout-side-jet-height", 0.44);
        sideJetR = c.getDouble("spout-side-jet-radius", 0.22);
        drinkTopH = c.getDouble("drinking-top-height", 0.95);
        range = Math.max(4, c.getDouble("range", 24));
        interval = Math.max(1, c.getInt("particle-interval-ticks", 4));
        sound = c.getString("sound", "block.water.ambient");
        soundVolume = (float) c.getDouble("sound-volume", 0.3);
        soundInterval = Math.max(10, c.getInt("sound-interval-ticks", 70));
        stream = particle(c.getString("particles.stream", "FALLING_WATER"));
        splash = particle(c.getString("particles.splash", "SPLASH"));
        mist = particle(c.getString("particles.mist", "CLOUD"));
        glow = particle(c.getString("particles.glow", "GLOW"));
        sparkle = particle(c.getString("particles.sparkle", "END_ROD"));
        List<Double> sh = c.getDoubleList("spray-height");
        if (sh.size() >= 5) for (int i = 0; i < 5; i++) spray[i] = sh.get(i);
        List<Integer> gl = c.getIntegerList("goal-light");
        if (gl.size() >= 5) for (int i = 0; i < 5; i++) goalLight[i] = Math.max(0, Math.min(15, gl.get(i)));
        tossEnabled = toss.getBoolean("enabled", true);
        lureMs = Math.max(1, toss.getLong("lucky-lure-minutes", 60)) * 60_000L;
        cooldownMs = Math.max(0, toss.getLong("cooldown-minutes", 60)) * 60_000L;
        tossSound = toss.getString("sound", "block.note_block.chime");
        splashSound = toss.getString("splash-sound", "entity.generic.splash");
        // Which pieces are indexed and tracked only depends on the globs: unchanged ones keep the index as it is
        // (chunk loads and the placed/removed hooks keep it current) instead of rescanning every loaded chunk.
        boolean globsChanged = !globKey.equals(this.globKey);
        this.globKey = globKey;
        if (scanned && globsChanged) reindex();
    }

    /**
     * The globs may have changed: re-decide the indexed pieces, index the ready-to-send chunks again, drop pieces that
     * no longer match, track new ones. The index is revalidated rather than cleared: the rescan reaches only
     * block-ticking chunks, and basins in loaded border chunks would otherwise drop out until their chunk reloads.
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
            Map<BlockPos, Piece> map = pieces.get(level.dimension());
            if (map != null) map.keySet().removeIf(p -> !index.contains(level, p));
            for (LevelChunk c : chunks) for (BlockPos p : index.inChunk(level, c.getPos().x(), c.getPos().z())) track(level, p);
        }
    }

    private SimpleParticleType particle(String name) {
        if (name == null || name.isBlank()) return null;
        SimpleParticleType p = Coast.particle(name);
        if (p == null) m.warnOnce("particle:" + name, "civic.yml: unknown (or not simple) particle " + name + ".");
        return p;
    }

    int interval() {
        return interval;
    }

    Kind kindOf(String id) {
        if (Coast.matches(basins, id)) return Kind.BASIN;
        if (Coast.matches(tiers, id)) return Kind.TIER;
        if (Coast.matches(spouts, id)) return Kind.SPOUT;
        if (Coast.matches(drinking, id)) return Kind.DRINKING;
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

    private Kind kindAt(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!ArcanaContent.isFurniture(st) || !ArcanaContent.origin(st, pos).equals(pos)) return null;
        return kindOf(st.getBlock());
    }

    boolean isBasin(String id) {
        return kindOf(id) == Kind.BASIN;
    }

    // ------------------------------------------------------------------ tracking + assembly

    void scanAll() {
        var server = Feather.serverOrNull();
        if (server == null) return;
        scanned = true;
        for (ServerLevel level : server.getAllLevels()) {
            List<LevelChunk> chunks = new ArrayList<>();
            level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunks::add);
            for (LevelChunk c : chunks) chunkLoaded(level, c);
        }
    }

    void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        if (!index.chunkLoaded(level, chunk)) return;
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        // read the index again when the task runs: a chunk that unloaded meanwhile has no entry left (track() has no
        // loaded-chunk guard of its own)
        Tasks.later(2, () -> {
            for (BlockPos p : index.inChunk(level, cx, cz)) track(level, p);
        });
    }

    void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        Set<BlockPos> gone = index.chunkUnloaded(level, chunk);
        Map<BlockPos, Piece> map = pieces.get(level.dimension());
        if (map != null) for (BlockPos p : gone) map.remove(p);
    }

    private Piece track(ServerLevel level, BlockPos pos) {
        Kind k = kindAt(level, pos);
        if (k != Kind.BASIN && k != Kind.DRINKING) return null;
        Map<BlockPos, Piece> map = pieces.computeIfAbsent(level.dimension(), x -> new HashMap<>());
        Piece p = map.get(pos);
        if (p != null && p.kind == k) return p;
        p = new Piece(level, pos.immutable(), k);
        map.put(p.pos, p);
        index.add(level, p.pos);   // tracked stays a subset of the index
        if (k == Kind.BASIN) {
            assemble(p);
            update(p);
        }
        return p;
    }

    void placed(ServerLevel level, BlockPos pos, String id) {
        Kind k = kindOf(id);
        if (k == Kind.BASIN || k == Kind.DRINKING) {
            index.add(level, pos);
            track(level, pos);
        }
        Tasks.later(1, () -> changedNear(level, pos));
    }

    void removed(ServerLevel level, BlockPos pos, String id) {
        Kind k = kindOf(id);
        if (k == null) return;
        if (k == Kind.BASIN || k == Kind.DRINKING) {
            Map<BlockPos, Piece> map = pieces.get(level.dimension());
            if (map != null) map.remove(pos);
            index.remove(level, pos);
            if (k == Kind.BASIN) {
                setLight(level, pos, null, 0);
                SpotData.put(level, pos, null);
            }
        }
        Tasks.later(1, () -> changedNear(level, pos));
    }

    void clear() {
        scanned = false;
        pieces.clear();
        index.clear();
    }

    /** Something was placed or broken here: assemble the basins close by again. */
    void changedNear(ServerLevel level, BlockPos at) {
        Map<BlockPos, Piece> map = pieces.get(level.dimension());
        if (map == null) return;
        for (Piece p : new ArrayList<>(map.values())) {
            if (p.kind != Kind.BASIN) continue;
            if (Math.abs(p.pos.getX() - at.getX()) <= 3 && Math.abs(p.pos.getZ() - at.getZ()) <= 3) {
                assemble(p);
                update(p);
            }
        }
    }

    /** Every 5 s: forget removed pieces, assemble again, follow the goal. */
    void sweep() {
        for (Map<BlockPos, Piece> map : pieces.values()) {
            map.values().removeIf(p -> {
                if (kindAt(p.level, p.pos) == p.kind) return false;
                // a basin removed without its removal hook (/setblock, /fill): its goal light and link go too
                if (p.kind == Kind.BASIN && !ArcanaContent.isFurniture(p.level.getBlockState(p.pos))) {
                    index.remove(p.level, p.pos);
                    setLight(p.level, p.pos, null, 0);
                    SpotData.put(p.level, p.pos, null);
                }
                return true;
            });
            for (Piece p : new ArrayList<>(map.values())) {
                if (p.kind != Kind.BASIN) continue;
                assemble(p);
                update(p);
            }
        }
    }

    /** Tiers and a spout stacked on the basin's center column (no gaps). */
    private void assemble(Piece basin) {
        basin.tiers.clear();
        basin.spout = null;
        int top = basin.pos.getY();
        for (int y = basin.pos.getY() + 1; y <= basin.pos.getY() + 8; y++) {
            BlockPos at = new BlockPos(basin.pos.getX(), y, basin.pos.getZ());
            Kind k = kindAt(basin.level, at);
            if (k != Kind.TIER && k != Kind.SPOUT) {
                if (y > top + 1) break;
                continue;
            }
            if (y > top + 1) break;
            if (k == Kind.SPOUT) {
                basin.spout = at;
                break;
            }
            basin.tiers.add(at);
            top = y;
        }
    }

    boolean linked(ServerLevel level, BlockPos basin) {
        return SpotData.get(level, basin).getByte(GOAL).orElse((byte) 0) == 1;
    }

    private int goalTier(ServerLevel level, BlockPos basin) {
        Hooks.Quests q = m.plugin.service(Hooks.Quests.class);
        if (!linked(level, basin) || q == null) return 0;
        int pct = q.goalPercent();
        if (pct >= 100) return 4;
        if (pct >= 75) return 3;
        if (pct >= 50) return 2;
        if (pct >= 25) return 1;
        return 0;
    }

    private void update(Piece basin) {
        basin.goalTier = goalTier(basin.level, basin.pos);
        int level = goalLight[basin.goalTier];
        if (basin.spout == null || level <= 0 || !linked(basin.level, basin.pos)) setLight(basin.level, basin.pos, null, 0);
        else setLight(basin.level, basin.pos, basin.spout.above(), level);
    }

    /** Moves the basin's goal light (a LIGHT block it placed, remembered at the basin) to {@code to} (null: none). */
    private void setLight(ServerLevel level, BlockPos basin, BlockPos to, int lightLevel) {
        CompoundTag t = SpotData.get(level, basin);
        String old = t.getString(LIGHT).orElse(null);
        BlockPos was = old == null ? null : parse(old);
        if (was != null && !was.equals(to)) {
            clearLight(level, was);
            t.remove(LIGHT);
            SpotData.put(level, basin, t);
        }
        if (to == null) return;
        BlockState st = level.getBlockState(to);
        boolean light = st.is(Blocks.LIGHT);
        if (!st.isAir() && !light && !(st.is(Blocks.WATER) && st.getFluidState().isSource())) {
            if (t.contains(LIGHT)) {
                t.remove(LIGHT);
                SpotData.put(level, basin, t);
            }
            return;
        }
        if (light && st.getValue(LightBlock.LEVEL) == lightLevel && to.equals(was)) return;
        if (light && !to.equals(was)) return;   // someone else's light
        boolean wet = level.getFluidState(to).is(FluidTags.WATER);
        level.setBlock(to, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel)
                .setValue(BlockStateProperties.WATERLOGGED, wet), Block.UPDATE_CLIENTS);
        t.putString(LIGHT, to.getX() + "," + to.getY() + "," + to.getZ());
        SpotData.put(level, basin, t);
    }

    private static void clearLight(ServerLevel level, BlockPos b) {
        BlockState st = level.getBlockState(b);
        if (!st.is(Blocks.LIGHT)) return;
        boolean wet = st.getValue(BlockStateProperties.WATERLOGGED);
        level.setBlock(b, wet ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static BlockPos parse(String s) {
        String[] p = s.split(",");
        if (p.length < 3) return null;
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code /civic fountain goal}: flips the link; returns whether it is linked now. */
    boolean toggleGoal(ServerLevel level, BlockPos basin) {
        boolean now = !linked(level, basin);
        CompoundTag t = SpotData.get(level, basin);
        if (now) t.putByte(GOAL, (byte) 1);
        else t.remove(GOAL);
        SpotData.put(level, basin, t);
        index.add(level, basin);
        Piece p = track(level, basin);
        if (p != null) {
            assemble(p);
            update(p);
        }
        return now;
    }

    /** The basin a looked-at piece belongs to (tier/spout: the basin under it), else the nearest basin within 5 blocks. */
    BlockPos basinFor(ServerLevel level, BlockPos looked, BlockPos near) {
        if (looked != null) {
            Kind k = kindAt(level, looked);
            if (k == Kind.BASIN) return looked;
            if (k == Kind.TIER || k == Kind.SPOUT) {
                for (int y = looked.getY() - 1; y >= looked.getY() - 8; y--) {
                    BlockPos b = new BlockPos(looked.getX(), y, looked.getZ());
                    if (kindAt(level, b) == Kind.BASIN) return b;
                }
            }
        }
        if (near == null) return null;
        BlockPos best = null;
        double bd = 25;
        Map<BlockPos, Piece> map = pieces.getOrDefault(level.dimension(), Map.of());
        for (Piece p : map.values()) {
            if (p.kind != Kind.BASIN) continue;
            double d = p.pos.distSqr(near);
            if (d < bd) {
                bd = d;
                best = p.pos;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ stacking pieces onto a basin

    boolean isStackable(String id) {
        Kind k = kindOf(id);
        return k == Kind.TIER || k == Kind.SPOUT;
    }

    /**
     * Holding a tier or spout and right-clicking a fountain piece: the piece goes on top of the basin's column, facing
     * like the basin (it can also be placed by hand on top of the column like any block).
     */
    boolean stack(ServerPlayer p, FurnitureContext ctx, String heldId) {
        ServerLevel level = ctx.level();
        BlockPos basinPos = basinFor(level, ctx.origin(), null);
        if (basinPos == null) return false;
        index.add(level, basinPos);
        Piece b = track(level, basinPos);
        if (b == null) return false;
        assemble(b);
        if (b.spout != null) {
            m.bar(p, "fountain.has-spout");
            return true;
        }
        BlockPos target = (b.tiers.isEmpty() ? b.pos : b.tiers.get(b.tiers.size() - 1)).above();
        BlockState there = level.getBlockState(target);
        if (target.getY() > b.pos.getY() + 8 || (!there.isAir() && !there.is(Blocks.WATER))) {
            m.bar(p, "fountain.no-room");
            return true;
        }
        if (!m.mayBuild(p, level, target, true)) return true;
        BlockState basinState = level.getBlockState(b.pos);
        if (!ArcanaContent.place(level, target, heldId, FurnitureBlock.front(basinState))) {
            m.bar(p, "fountain.no-room");
            return true;
        }
        if (!p.isCreative()) p.getMainHandItem().shrink(1);
        assemble(b);
        update(b);
        Coast.sound(level, target, "block.stone.place", 1f, 0.9f);
        return true;
    }

    // ------------------------------------------------------------------ effects (only for players in range)

    void tick() {
        if (!enabled || pieces.isEmpty()) return;
        now += interval;
        double r2 = range * range;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Map<BlockPos, Piece> map : pieces.values()) {
            for (Piece p : map.values()) {
                if (p.kind == Kind.BASIN && p.spout == null) continue;
                Vec3 c = Vec3.atBottomCenterOf(p.pos);
                List<ServerPlayer> near = null;
                for (ServerPlayer pl : p.level.players()) {
                    if (pl.position().distanceToSqr(c) > r2) continue;
                    if (near == null) near = new ArrayList<>(2);
                    near.add(pl);
                }
                if (near == null) continue;
                p.cycle++;
                if (p.kind == Kind.DRINKING) drinking(p, c, near);
                else basin(p, c, near, rnd);
                if (sound != null && !sound.isBlank() && now - p.soundTick >= soundInterval) {
                    p.soundTick = now;
                    float vol = p.kind == Kind.DRINKING ? soundVolume * 0.6f : soundVolume;
                    float pitch = 0.9f + rnd.nextFloat() * 0.2f;
                    for (ServerPlayer pl : near) Coast.soundTo(pl, c, sound, SoundSource.BLOCKS, vol, pitch);
                }
            }
        }
    }

    private void basin(Piece b, Vec3 c, List<ServerPlayer> near, ThreadLocalRandom rnd) {
        double cx = c.x, cz = c.z;
        double top = b.spout.getY() + spoutTopH;
        double h = spray[b.goalTier];
        double ja = (b.cycle & 3) * Math.PI / 2;
        double jx = cx + Math.cos(ja) * sideJetR, jz = cz + Math.sin(ja) * sideJetR, jy = b.spout.getY() + sideJetH;
        for (ServerPlayer pl : near) {
            Coast.particlesTo(pl, stream, cx, top + rnd.nextDouble() * h, cz, 2, 0.06, 0.05, 0.06, 0);
            Coast.particlesTo(pl, splash, cx, top + h, cz, 2, 0.12, 0.05, 0.12, 0);
            Coast.particlesTo(pl, stream, jx, jy, jz, 1, 0.02, 0, 0.02, 0);
        }
        for (int i = 0; i < b.tiers.size(); i++) {
            double y = b.tiers.get(i).getY() + tierRimH;
            double a = rnd.nextDouble() * Math.PI * 2;
            double x = cx + Math.cos(a) * tierR, z = cz + Math.sin(a) * tierR;
            boolean lit = b.goalTier > i;
            for (ServerPlayer pl : near) {
                Coast.particlesTo(pl, stream, x, y, z, 1, 0.02, 0, 0.02, 0);
                if (lit && (b.cycle & 1) == 0) {
                    Coast.particlesTo(pl, glow, cx + Math.cos(a + Math.PI) * tierR, y, cz + Math.sin(a + Math.PI) * tierR, 1, 0.05, 0.05, 0.05, 0);
                }
            }
        }
        double wy = b.pos.getY() + basinWaterH;
        double a = rnd.nextDouble() * Math.PI * 2, rr = 0.35 + rnd.nextDouble() * (basinR - 0.35);
        for (ServerPlayer pl : near) {
            Coast.particlesTo(pl, splash, cx + Math.cos(a) * rr, wy, cz + Math.sin(a) * rr, 2, 0.1, 0, 0.1, 0);
            if (b.cycle % 4 == 0) Coast.particlesTo(pl, mist, cx, wy + 0.15, cz, 1, basinR * 0.5, 0.05, basinR * 0.5, 0.002);
            if (b.goalTier >= 4 && b.cycle % 2 == 0) Coast.particlesTo(pl, sparkle, cx, top + h, cz, 1, 0.25, 0.15, 0.25, 0.01);
        }
    }

    private void drinking(Piece d, Vec3 c, List<ServerPlayer> near) {
        if (d.cycle % 2 != 0) return;
        double y = d.pos.getY() + drinkTopH;
        for (ServerPlayer pl : near) {
            Coast.particlesTo(pl, stream, c.x, y, c.z, 1, 0.04, 0.02, 0.04, 0);
            Coast.particlesTo(pl, splash, c.x, y, c.z, 1, 0.06, 0, 0.06, 0);
        }
    }

    // ------------------------------------------------------------------ coin toss

    boolean isCoin(ItemStack held) {
        Hooks.Bank bank = m.plugin.service(Hooks.Bank.class);
        return tossEnabled && bank != null && held != null && !held.isEmpty() && bank.isCoin(held);
    }

    boolean toss(ServerPlayer p, ServerLevel level, BlockPos basin) {
        Hooks.Bank bank = m.plugin.service(Hooks.Bank.class);
        if (bank == null || !bank.isOpen()) {
            m.bar(p, "coin.closed");
            return true;
        }
        long now = System.currentTimeMillis();
        long last = Pdc.getLong(p, TOSS, 0L);
        if (last > 0 && now - last < cooldownMs) {
            long mins = Math.max(1, (cooldownMs - (now - last) + 59_999) / 60_000);
            m.bar(p, "coin.wait", CivicModule.tag("minutes", mins));
            return true;
        }
        ItemStack held = p.getMainHandItem();
        if (!bank.isCoin(held)) return false;
        ItemStack back = held.copy();
        held.shrink(1);
        if (!bank.depositToReserve(1, "FOUNTAIN", Names.name(p) + " " + p.getUUID())) {
            p.setItemInHand(InteractionHand.MAIN_HAND, back);
            m.bar(p, "coin.closed");
            return true;
        }
        Pdc.putLong(p, TOSS, now);
        long until = Math.max(now, Pdc.getLong(p, LURE, 0L));
        Pdc.putLong(p, LURE, Math.max(until, now + lureMs));
        Vec3 at = Vec3.atBottomCenterOf(basin).add(0, basinWaterH, 0);
        level.sendParticles(ParticleTypes.SPLASH, at.x, at.y, at.z, 24, 0.5, 0.05, 0.5, 0.1);
        Coast.sound(level, at, splashSound, SoundSource.BLOCKS, 0.6f, 1.3f);
        Coast.sound(level, at, tossSound, SoundSource.BLOCKS, 0.8f, 1.5f);
        m.send(p, "coin.tossed", CivicModule.tag("minutes", lureMs / 60_000));
        m.plugin.stat(p.getUUID(), "fountain_wishes", 1);
        return true;
    }

    // ------------------------------------------------------------------ migration

    /** A Paper-era basin keeps its goal link and the goal light it placed. */
    void migrate(ServerLevel level, BlockPos origin, String id, CompoundTag bv) {
        if (kindOf(id) != Kind.BASIN) return;
        CompoundTag t = new CompoundTag();
        if (bv.contains(GOAL)) t.put(GOAL, bv.get(GOAL).copy());
        if (bv.contains(LIGHT)) t.put(LIGHT, bv.get(LIGHT).copy());
        if (kindAt(level, origin) != Kind.BASIN) {
            String light = t.getString(LIGHT).orElse(null);
            BlockPos lp = light == null ? null : parse(light);
            if (lp != null) clearLight(level, lp);
            return;
        }
        if (!t.isEmpty()) SpotData.put(level, origin, t);
        index.add(level, origin);
        track(level, origin);
    }

    // ------------------------------------------------------------------ /civic info

    List<String> describe(ServerLevel level, BlockPos at, double r) {
        List<String> out = new ArrayList<>();
        for (Piece p : pieces.getOrDefault(level.dimension(), Map.of()).values()) {
            if (p.pos.distSqr(at) > r * r) continue;
            String pos = p.pos.getX() + "," + p.pos.getY() + "," + p.pos.getZ();
            String id = ArcanaContent.id(level.getBlockState(p.pos));
            if (p.kind == Kind.DRINKING) {
                out.add("DRINKING " + id + " @" + pos);
                continue;
            }
            String light = SpotData.get(level, p.pos).getString(LIGHT).orElse(null);
            out.add("FOUNTAIN " + id + " @" + pos + " assembled=" + (p.spout != null) + " tiers=" + p.tiers.size()
                    + " spout=" + (p.spout == null ? "-" : ArcanaContent.id(level.getBlockState(p.spout))) + " linked=" + linked(level, p.pos)
                    + " tier=" + p.goalTier + " light=" + (light == null ? "-" : light));
        }
        return out;
    }
}
