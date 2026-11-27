package net.axther.aftrhrs.feature.farming;

import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Every planted Feather crop, kept per chunk in the chunk's {@link Pdc} ({@code feather:farm_crops},
 * {@code feather:farm_soil}: the same strings Paper kept in the chunk PDC, so a migrated world keeps its farms) and in
 * memory only while the chunk is loaded. Each loaded chunk remembers the earliest time one of its crops is due; the
 * timer only looks at chunks whose time has come. A chunk that loads catches up on the time it was away (a little
 * later, so the blocks around it have loaded too). The stage is the crop block's {@code age}.
 */
final class CropField {
    /** One crop. {@code next}: when its next stage is due (Long.MAX_VALUE once ripe). {@code watered}: bit per stage. */
    static final class Crop {
        final int x, y, z;
        FarmConfig.CropDef def;
        int stage;
        long next;
        int watered;

        Crop(int x, int y, int z, FarmConfig.CropDef def, int stage, long next, int watered) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.def = def;
            this.stage = stage;
            this.next = next;
            this.watered = watered;
        }

        boolean ripe() {
            return stage >= 3;
        }

        int wateredStages() {
            return Integer.bitCount(watered & 0b111);
        }

        BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    static final class ChunkData {
        final ResourceKey<Level> world;
        final int cx, cz;
        final Map<Long, Crop> crops = new HashMap<>();
        final Map<Long, Integer> soil = new HashMap<>();
        long next = Long.MAX_VALUE;
        long readyAt;
        /** Blocks not looked at yet since the chunk loaded (old Oraxen tripwire crops are converted then). */
        boolean checked;

        ChunkData(ResourceKey<Level> world, int cx, int cz) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
        }

        void recompute() {
            long n = Long.MAX_VALUE;
            for (Crop c : crops.values()) n = Math.min(n, c.next);
            next = checked ? n : Math.min(n, readyAt);
        }
    }

    static final String CROPS_KEY = "feather:farm_crops", SOIL_KEY = "feather:farm_soil";

    private final FarmingModule m;
    private final Map<ResourceKey<Level>, Map<Long, ChunkData>> worlds = new HashMap<>();

    CropField(FarmingModule module) {
        this.m = module;
    }

    /** x, z: 26 bits each, y: 12 bits (-2048..2047). */
    static long pos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    static long pos(BlockPos p) {
        return pos(p.getX(), p.getY(), p.getZ());
    }

    static int posX(long k) {
        return (int) (k >> 38);
    }

    static int posY(long k) {
        return (int) (k << 52 >> 52);
    }

    static int posZ(long k) {
        return (int) (k << 26 >> 38);
    }

    // ------------------------------------------------------------------ chunks

    void loadAll() {
        var server = net.axther.aftrhrs.core.Feather.serverOrNull();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (LevelChunk chunk : loadedChunks(level)) load(level, chunk);
        }
    }

    static List<LevelChunk> loadedChunks(ServerLevel level) {
        List<LevelChunk> out = new ArrayList<>();
        level.getChunkSource().chunkMap.forEachReadyToSendChunk(out::add);
        return out;
    }

    void saveAll() {
        var server = net.axther.aftrhrs.core.Feather.serverOrNull();
        if (server == null) return;
        for (Map<Long, ChunkData> map : worlds.values()) {
            for (ChunkData cd : map.values()) write(cd);
        }
    }

    void clear() {
        worlds.clear();
    }

    /** After a reload: every loaded record points at its crop's new definition (unknown ones keep a placeholder). */
    void rebind() {
        for (Map<Long, ChunkData> map : worlds.values()) {
            for (ChunkData cd : map.values()) {
                for (Crop c : cd.crops.values()) {
                    FarmConfig.CropDef def = m.config().crops.get(c.def.id);
                    c.def = def != null ? def : new FarmConfig.CropDef(c.def.id, new Yaml());
                }
            }
        }
    }

    void load(ServerLevel level, LevelChunk chunk) {
        String crops = Pdc.getString(chunk, CROPS_KEY);
        String soil = Pdc.getString(chunk, SOIL_KEY);
        if ((crops == null || crops.isEmpty()) && (soil == null || soil.isEmpty())) return;
        ChunkPos cp = chunk.getPos();
        ChunkData cd = new ChunkData(level.dimension(), cp.x(), cp.z());
        if (crops != null) {
            for (String rec : crops.split(";")) {
                String[] p = rec.split(",");
                if (p.length < 7) continue;
                try {
                    FarmConfig.CropDef def = m.config().crops.get(p[3]);
                    // a crop removed from farming.yml: keep the record so a typo doesn't wipe a farm
                    if (def == null) def = new FarmConfig.CropDef(p[3], new Yaml());
                    Crop c = new Crop(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), def,
                            Integer.parseInt(p[4]), Long.parseLong(p[5]), Integer.parseInt(p[6]));
                    cd.crops.put(pos(c.x, c.y, c.z), c);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (soil != null) {
            for (String rec : soil.split(";")) {
                String[] p = rec.split(",");
                if (p.length < 4) continue;
                try {
                    cd.soil.put(pos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), Integer.parseInt(p[3]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        cd.readyAt = m.now() + m.config().loadDelayTicks * 50L;
        cd.recompute();
        worlds.computeIfAbsent(cd.world, k -> new HashMap<>()).put(cp.pack(), cd);
    }

    void unload(ServerLevel level, LevelChunk chunk) {
        Map<Long, ChunkData> map = worlds.get(level.dimension());
        if (map == null) return;
        ChunkData cd = map.remove(chunk.getPos().pack());
        if (cd != null) write(cd, chunk);
    }

    private ServerLevel level(ResourceKey<Level> key) {
        var server = net.axther.aftrhrs.core.Feather.serverOrNull();
        return server == null ? null : server.getLevel(key);
    }

    private void write(ChunkData cd) {
        ServerLevel level = level(cd.world);
        if (level == null) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cd.cx, cd.cz);
        if (chunk != null) write(cd, chunk);
    }

    private void write(ChunkData cd, LevelChunk chunk) {
        if (cd.crops.isEmpty()) Pdc.remove(chunk, CROPS_KEY);
        else {
            StringBuilder b = new StringBuilder();
            for (Crop c : cd.crops.values()) {
                if (!b.isEmpty()) b.append(';');
                b.append(c.x).append(',').append(c.y).append(',').append(c.z).append(',').append(c.def.id).append(',')
                        .append(c.stage).append(',').append(c.next).append(',').append(c.watered);
            }
            Pdc.putString(chunk, CROPS_KEY, b.toString());
        }
        cd.soil.values().removeIf(v -> v <= 0);
        if (cd.soil.isEmpty()) Pdc.remove(chunk, SOIL_KEY);
        else {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<Long, Integer> e : cd.soil.entrySet()) {
                if (!b.isEmpty()) b.append(';');
                long k = e.getKey();
                b.append(posX(k)).append(',').append(posY(k)).append(',').append(posZ(k)).append(',').append(e.getValue());
            }
            Pdc.putString(chunk, SOIL_KEY, b.toString());
        }
        chunk.markUnsaved();
    }

    private ChunkData data(ServerLevel level, int x, int z, boolean create) {
        Map<Long, ChunkData> map = worlds.get(level.dimension());
        long key = ChunkPos.pack(x >> 4, z >> 4);
        ChunkData cd = map == null ? null : map.get(key);
        if (cd == null && create) {
            cd = new ChunkData(level.dimension(), x >> 4, z >> 4);
            cd.checked = true;
            worlds.computeIfAbsent(level.dimension(), k -> new HashMap<>()).put(key, cd);
        }
        return cd;
    }

    // ------------------------------------------------------------------ crops

    Crop get(ServerLevel level, BlockPos b) {
        ChunkData cd = data(level, b.getX(), b.getZ(), false);
        return cd == null ? null : cd.crops.get(pos(b));
    }

    Crop add(ServerLevel level, BlockPos b, FarmConfig.CropDef def, int stage, long next) {
        ChunkData cd = data(level, b.getX(), b.getZ(), true);
        Crop c = new Crop(b.getX(), b.getY(), b.getZ(), def, stage, next, 0);
        cd.crops.put(pos(b), c);
        changed(cd);
        return c;
    }

    void remove(ServerLevel level, BlockPos b) {
        ChunkData cd = data(level, b.getX(), b.getZ(), false);
        if (cd != null && cd.crops.remove(pos(b)) != null) changed(cd);
    }

    void changed(ServerLevel level, BlockPos b) {
        ChunkData cd = data(level, b.getX(), b.getZ(), false);
        if (cd != null) changed(cd);
    }

    private void changed(ChunkData cd) {
        cd.recompute();
        write(cd);
    }

    int compost(ServerLevel level, BlockPos farmland) {
        ChunkData cd = data(level, farmland.getX(), farmland.getZ(), false);
        Integer v = cd == null ? null : cd.soil.get(pos(farmland));
        return v == null ? 0 : v;
    }

    void setCompost(ServerLevel level, BlockPos farmland, int charges) {
        ChunkData cd = data(level, farmland.getX(), farmland.getZ(), charges > 0);
        if (cd == null) return;
        long k = pos(farmland);
        if (charges > 0) cd.soil.put(k, charges);
        else cd.soil.remove(k);
        changed(cd);
    }

    List<Crop> near(ServerLevel level, int x, int z, int radius) {
        List<Crop> out = new ArrayList<>();
        Map<Long, ChunkData> map = worlds.get(level.dimension());
        if (map == null) return out;
        for (ChunkData cd : map.values()) {
            for (Crop c : cd.crops.values()) {
                if (Math.abs(c.x - x) <= radius && Math.abs(c.z - z) <= radius) out.add(c);
            }
        }
        return out;
    }

    int loadedCount() {
        int n = 0;
        for (Map<Long, ChunkData> map : worlds.values()) for (ChunkData cd : map.values()) n += cd.crops.size();
        return n;
    }

    int loadedChunks() {
        int n = 0;
        for (Map<Long, ChunkData> map : worlds.values()) n += map.size();
        return n;
    }

    // ------------------------------------------------------------------ growth

    /** The timer: only chunks whose earliest crop is due (and whose catch-up delay is over) are looked at. */
    void tick() {
        long now = m.now();
        for (Map.Entry<ResourceKey<Level>, Map<Long, ChunkData>> we : worlds.entrySet()) {
            ServerLevel w = level(we.getKey());
            if (w == null) continue;
            for (ChunkData cd : new ArrayList<>(we.getValue().values())) {
                if (cd.next > now || now < cd.readyAt) continue;
                if (w.getChunkSource().getChunkNow(cd.cx, cd.cz) == null) continue;
                process(w, cd, now, false);
            }
        }
    }

    /** Grows every due crop of a chunk. {@code force}: debug, every unripe crop counts as due now. */
    void process(ServerLevel w, ChunkData cd, long now, boolean force) {
        boolean dirty = false;
        if (!cd.checked) {
            cd.checked = true;
            // A freshly loaded chunk: records whose block is gone are dropped; old Oraxen crops become crop blocks.
            for (Iterator<Crop> it = cd.crops.values().iterator(); it.hasNext(); ) {
                Crop c = it.next();
                if (m.stageState(w, c.pos(), c).equals("gone")) {
                    it.remove();
                    dirty = true;
                }
            }
        }
        for (Iterator<Crop> it = cd.crops.values().iterator(); it.hasNext(); ) {
            Crop c = it.next();
            if (c.ripe() || (!force && c.next > now)) continue;
            int r = grow(w, c, now, force);
            if (r < 0) it.remove();
            dirty = true;
        }
        if (dirty) changed(cd);
        else cd.recompute();
    }

    /** Debug: grows crops near a spot one step now (or to ripe), skipping the timer but not the rules. */
    int forceGrow(ServerLevel w, int x, int z, int radius, boolean toRipe, boolean ignoreRules) {
        int grown = 0;
        Map<Long, ChunkData> map = worlds.get(w.dimension());
        if (map == null) return 0;
        long now = m.now();
        for (ChunkData cd : map.values()) {
            boolean dirty = false;
            for (Iterator<Crop> it = cd.crops.values().iterator(); it.hasNext(); ) {
                Crop c = it.next();
                if (c.ripe() || Math.abs(c.x - x) > radius || Math.abs(c.z - z) > radius) continue;
                BlockPos b = c.pos();
                if (m.stageState(w, b, c).equals("gone")) {
                    it.remove();
                    dirty = true;
                    continue;
                }
                if (!ignoreRules && m.whyNot(w, b, c.def) != null) continue;
                int target = toRipe ? 3 : c.stage + 1;
                if (!m.setStage(w, b, c, target)) continue;
                c.next = c.ripe() ? Long.MAX_VALUE : now + m.stageMs(w, b, c.def);
                grown++;
                dirty = true;
            }
            if (dirty) changed(cd);
        }
        return grown;
    }

    int forget(ServerLevel w, int x, int z, int radius) {
        int n = 0;
        Map<Long, ChunkData> map = worlds.get(w.dimension());
        if (map == null) return 0;
        for (ChunkData cd : map.values()) {
            int before = cd.crops.size() + cd.soil.size();
            cd.crops.values().removeIf(c -> Math.abs(c.x - x) <= radius && Math.abs(c.z - z) <= radius);
            cd.soil.keySet().removeIf(k -> Math.abs(posX(k) - x) <= radius && Math.abs(posZ(k) - z) <= radius);
            int gone = before - cd.crops.size() - cd.soil.size();
            if (gone > 0) {
                n += gone;
                changed(cd);
            }
        }
        return n;
    }

    int skew(ServerLevel w, int x, int z, int radius, long ms) {
        int n = 0;
        Map<Long, ChunkData> map = worlds.get(w.dimension());
        if (map == null) return 0;
        for (ChunkData cd : map.values()) {
            boolean dirty = false;
            for (Crop c : cd.crops.values()) {
                if (c.ripe() || Math.abs(c.x - x) > radius || Math.abs(c.z - z) > radius) continue;
                c.next -= ms;
                n++;
                dirty = true;
            }
            if (dirty) changed(cd);
        }
        return n;
    }

    /**
     * One crop's due time has come: grows as many stages as the time since then pays for (one in normal play; more
     * when catching up), or waits {@code retry-minutes} when it can't grow now. Returns -1 when the crop is gone.
     */
    private int grow(ServerLevel w, Crop c, long now, boolean force) {
        BlockPos b = c.pos();
        if (w.getChunkSource().getChunkNow(c.x >> 4, c.z >> 4) == null) return 0;
        String state = m.stageState(w, b, c);
        if (state.equals("gone")) return -1;
        if (m.whyNot(w, b, c.def) != null) {
            c.next = now + m.config().retryMs;
            return 0;
        }
        long per = m.stageMs(w, b, c.def);
        long t = Math.max(c.next, now - m.config().maxCatchupMs);
        int s = c.stage;
        if (force) {
            s++;
            t = now + per;
        } else {
            while (s < 3 && t <= now) {
                s++;
                t += per;
            }
        }
        if (s != c.stage && !m.setStage(w, b, c, s)) {
            c.next = now + m.config().retryMs;
            return 0;
        }
        c.next = c.ripe() ? Long.MAX_VALUE : t;
        return 1;
    }

    // ------------------------------------------------------------------ rules

    static boolean hydrated(BlockState farmland) {
        return farmland.hasProperty(FarmlandBlock.MOISTURE) && farmland.getValue(FarmlandBlock.MOISTURE) >= FarmlandBlock.MAX_MOISTURE;
    }

    /** Water (or a waterlogged block) right next to the farmland, at its height; only loaded neighbors count. */
    static boolean waterBeside(ServerLevel level, BlockPos farmland) {
        for (Direction f : Direction.Plane.HORIZONTAL) {
            BlockPos n = farmland.relative(f);
            if (level.getChunkSource().getChunkNow(n.getX() >> 4, n.getZ() >> 4) == null) continue;
            if (level.getFluidState(n).is(FluidTags.WATER)) return true;
        }
        return false;
    }
}
