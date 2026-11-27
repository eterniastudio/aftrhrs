package net.axther.aftrhrs.feature.farming;

import com.mojang.math.Transformation;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.feature.coast.BlockIndex;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.feature.coast.Parts;
import net.axther.aftrhrs.feature.coast.SpotData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seaweed lines: {@code arcana_seaweed_line} furniture on the sea floor. Seeded with spores, a line grows 4 stages
 * (0..3) while it is under water; each stage is an overlay item display ({@code arcana_seaweed_line_fx_<n>}) at the
 * block's center turned like the piece (display transform NONE, identity transformation, like the Oraxen base
 * entity it replaces). Overlays are never saved; they are drawn while the line's chunk is loaded. State in
 * {@link SpotData} at the line: {@code feather:seaweed_stage} (absent = not seeded) and {@code feather:seaweed_next}.
 * Only loaded lines are tracked; a line that loads catches up on its missed time.
 */
final class SeaweedLines {
    static final String STAGE = "feather:seaweed_stage", NEXT = "feather:seaweed_next", PART = "feather:seaweed_part";

    private static final class Line {
        final ServerLevel level;
        final BlockPos pos;
        Display.ItemDisplay overlay;
        int shown = -2;
        float shownYaw;

        Line(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos;
        }
    }

    private final FarmingModule m;
    private final BlockIndex index;
    /** Every tracked line is also in {@link #index} (attach adds it), so an unload drops exactly the chunk's set. */
    private final Map<ResourceKey<Level>, Map<BlockPos, Line>> lines = new HashMap<>();
    /** The line block, resolved once per {@code lineId} (the config object keeps the id; a reload may swap it). */
    private String lineKey;
    private Block lineBlock;
    /** The line block the index was built for (a reload that changes the line id indexes again). */
    private Block indexedFor;

    SeaweedLines(FarmingModule module) {
        this.m = module;
        this.index = new BlockIndex(b -> isLine(b));
        Parts.track(PART);
    }

    private boolean isLine(Block b) {
        Block line = lineBlock();
        return line != null && b == line;
    }

    private Block lineBlock() {
        String id = m.config().lineId;
        if (id != lineKey || lineBlock == null) {
            lineBlock = ArcanaContent.block(id);
            lineKey = id;
        }
        return lineBlock;
    }

    int count() {
        int n = 0;
        for (Map<BlockPos, Line> map : lines.values()) n += map.size();
        return n;
    }

    // ------------------------------------------------------------------ tracking

    void scanAll() {
        var server = net.axther.aftrhrs.core.Feather.serverOrNull();
        if (server == null) return;
        indexedFor = lineBlock();
        for (ServerLevel level : server.getAllLevels()) {
            for (LevelChunk chunk : CropField.loadedChunks(level)) chunkLoaded(level, chunk);
        }
    }

    void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        if (!index.chunkLoaded(level, chunk)) return;
        // attach a little later: the chunk is still being set up. Read the index again then (capture the chunk key,
        // not the chunk): a chunk that unloaded meanwhile has no entry left.
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        net.axther.aftrhrs.core.Tasks.later(2, () -> {
            for (BlockPos p : index.inChunk(level, cx, cz)) attach(level, p);
        });
    }

    void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        Set<BlockPos> gone = index.chunkUnloaded(level, chunk);
        Map<BlockPos, Line> map = lines.get(level.dimension());
        if (map == null) return;
        for (BlockPos p : gone) {
            Line l = map.remove(p);
            if (l != null) clear(l);
        }
    }

    private Line attach(ServerLevel level, BlockPos pos) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return null;
        if (!isLine(level.getBlockState(pos).getBlock())) return null;
        Map<BlockPos, Line> map = lines.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Line l = map.get(pos);
        if (l != null) return l;
        l = new Line(level, pos.immutable());
        map.put(l.pos, l);
        index.add(level, l.pos);   // tracked stays a subset of the index (clicks attach unindexed lines)
        grow(l, m.now());
        render(l);
        return l;
    }

    void placed(ServerLevel level, BlockPos pos) {
        index.add(level, pos);
        attach(level, pos);
    }

    void clearAll() {
        for (Map<BlockPos, Line> map : lines.values()) for (Line l : map.values()) clear(l);
        lines.clear();
        index.clear();
    }

    void redrawAll() {
        if (lineBlock() != indexedFor) {
            // the line id changed: index and attach again for the new block
            clearAll();
            scanAll();
            return;
        }
        for (Map<BlockPos, Line> map : lines.values()) {
            for (Line l : map.values()) {
                l.shown = -2;
                render(l);
            }
        }
    }

    private void clear(Line l) {
        Parts.remove(l.overlay);
        l.overlay = null;
        l.shown = -2;
    }

    int stage(ServerLevel level, BlockPos pos) {
        return SpotData.get(level, pos).getInt(STAGE).orElse(-1);
    }

    private void setStage(ServerLevel level, BlockPos pos, int stage, long next) {
        CompoundTag t = SpotData.get(level, pos);
        if (stage < 0) {
            t.remove(STAGE);
            t.remove(NEXT);
        } else {
            t.putInt(STAGE, stage);
            t.putLong(NEXT, next);
        }
        SpotData.put(level, pos, t);
    }

    private static boolean underwater(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER) || level.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    /** Grows a line by the stages its time paid for (catch-up included), if it is under water. */
    private boolean grow(Line l, long now) {
        CompoundTag t = SpotData.get(l.level, l.pos);
        int s = t.getInt(STAGE).orElse(-1);
        if (s < 0 || s >= 3) return false;
        long next = t.getLong(NEXT).orElse(now);
        if (next > now) return false;
        if (!underwater(l.level, l.pos)) {
            setStage(l.level, l.pos, s, now + m.config().retryMs);
            return false;
        }
        long per = m.config().seaweedStageMs;
        long time = Math.max(next, now - m.config().maxCatchupMs);
        int to = s;
        while (to < 3 && time <= now) {
            to++;
            time += per;
        }
        setStage(l.level, l.pos, to, to >= 3 ? Long.MAX_VALUE : time);
        return to != s;
    }

    void tick() {
        long now = m.now();
        for (Map<BlockPos, Line> map : lines.values()) {
            for (Line l : new ArrayList<>(map.values())) {
                BlockState st = l.level.getBlockState(l.pos);
                if (!isLine(st.getBlock())) {
                    clear(l);
                    map.remove(l.pos);
                    // removed without its removal hook (a /setblock or /fill): give back what it held, forget its state
                    if (!(st.getBlock() instanceof FurnitureBlock)) {
                        index.remove(l.level, l.pos);
                        dropFor(l.level, l.pos, SpotData.take(l.level, l.pos).getInt(STAGE).orElse(-1));
                    }
                    continue;
                }
                boolean turned = l.overlay != null && FurnitureBlock.yawDegrees(st) != l.shownYaw;   // sneak-rotated
                if (grow(l, now) || turned || (l.shown >= 0 && (l.overlay == null || l.overlay.isRemoved()))) {
                    l.shown = -2;
                    render(l);
                }
            }
        }
    }

    int forceGrow(ServerLevel level, BlockPos at, int radius, boolean toRipe) {
        int n = 0;
        Map<BlockPos, Line> map = lines.get(level.dimension());
        if (map == null) return 0;
        for (Line l : map.values()) {
            if (l.pos.distSqr(at) > (double) radius * radius) continue;
            int s = stage(level, l.pos);
            if (s < 0 || s >= 3) continue;
            int to = toRipe ? 3 : s + 1;
            setStage(level, l.pos, to, to >= 3 ? Long.MAX_VALUE : m.now() + m.config().seaweedStageMs);
            render(l);
            n++;
        }
        return n;
    }

    /** The stage overlay at the block's center, turned like the piece. */
    private void render(Line l) {
        int s = stage(l.level, l.pos);
        if (s == l.shown && (s < 0 || l.overlay != null && !l.overlay.isRemoved())) return;
        clear(l);
        l.shown = s;
        if (s < 0) return;
        ItemStack item = m.build(m.config().overlayPrefix + s, 1);
        if (item == null) return;
        BlockState st = l.level.getBlockState(l.pos);
        float yaw = st.getBlock() instanceof FurnitureBlock ? FurnitureBlock.yawDegrees(st) : 0f;
        l.shownYaw = yaw;
        l.overlay = Parts.item(l.level, Vec3.atCenterOf(l.pos), yaw, item, ItemDisplayContext.NONE, Transformation.IDENTITY,
                PART, l.pos.toShortString());
    }

    /** True when used. Spores seed an empty line; a ripe line is harvested; anything else shows its state. */
    boolean click(ServerPlayer p, FurnitureContext ctx, ItemStack held) {
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.origin();
        Line l = attach(level, pos);
        if (l == null) return false;
        FarmConfig cfg = m.config();
        int s = stage(level, pos);
        String heldId = Coast.id(held);
        if (s < 0) {
            if (cfg.sporesId.equals(heldId)) {
                if (!m.allowed(p, level, pos, ClaimAction.BUILD)) return true;
                setStage(level, pos, 0, m.now() + cfg.seaweedStageMs);
                Coast.takeOne(p, InteractionHand.MAIN_HAND);
                Coast.swing(p);
                render(l);
                Vec3 at = Vec3.atBottomCenterOf(pos).add(0, 0.5, 0);
                level.sendParticles(ParticleTypes.BUBBLE_POP, at.x, at.y, at.z, 10, 0.3, 0.3, 0.3, 0.02);
                Coast.sound(level, pos, "block.bubble_column.upwards_inside", 0.7f, 1.2f);
                // one bar: a second one the same tick would overwrite "Seeded."
                m.bar(p, underwater(level, pos) ? "line-seeded" : "line-seeded-dry");
                return true;
            }
            if (p.isSecondaryUseActive() && held.isEmpty()) return false;
            m.bar(p, "line-empty");
            return true;
        }
        if (s >= 3) {
            if (!m.allowed(p, level, pos, ClaimAction.BUILD)) return true;
            List<ItemStack> drops = harvest();
            setStage(level, pos, 0, m.now() + cfg.seaweedStageMs);
            render(l);
            Coast.dropAll(level, pos, drops);
            Coast.swing(p);
            Coast.sound(level, Vec3.atCenterOf(pos), "block.wet_grass.break", SoundSource.BLOCKS, 1f, 1f);
            Hooks.Quests quests = m.plugin().service(Hooks.Quests.class);
            if (quests != null) quests.progress(p, "harvest", cfg.seaweedId, 1);
            m.plugin().stat(p.getUUID(), "crops-harvested", 1);
            return true;
        }
        if (p.isSecondaryUseActive() && held.isEmpty()) return false;
        m.bar(p, underwater(level, pos) ? "line-growing" : "line-dry", Text.unparsed("stage", s));
        return true;
    }

    private List<ItemStack> harvest() {
        FarmConfig cfg = m.config();
        List<ItemStack> out = new ArrayList<>();
        ItemStack produce = m.build(cfg.seaweedId, cfg.seaweedProduce.roll());
        if (produce != null) out.add(produce);
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < cfg.sporesChance) {
            ItemStack spores = m.build(cfg.sporesId, 1);
            if (spores != null) out.add(spores);
        }
        return out;
    }

    /** The line was removed: a seeded one gives its spores back, a ripe one its harvest. */
    void broken(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Line> map = lines.get(level.dimension());
        Line l = map == null ? null : map.remove(pos);
        if (l != null) clear(l);
        index.remove(level, pos);
        int s = SpotData.take(level, pos).getInt(STAGE).orElse(-1);
        dropFor(level, pos, s);
    }

    private void dropFor(ServerLevel level, BlockPos pos, int s) {
        if (s < 0) return;
        List<ItemStack> drops = s >= 3 ? harvest() : new ArrayList<>();
        if (s < 3) {
            ItemStack spores = m.build(m.config().sporesId, 1);
            if (spores != null) drops.add(spores);
        }
        Coast.dropAll(level, pos, drops);
    }

    /** A Paper-era line became a block: its base entity's stage moves to the block. */
    void migrate(ServerLevel level, BlockPos pos, CompoundTag bv) {
        if (!bv.contains(STAGE)) return;
        int s = bv.getInt(STAGE).orElse(-1);
        if (!isLine(level.getBlockState(pos).getBlock())) {
            dropFor(level, pos, s);
            return;
        }
        CompoundTag t = SpotData.get(level, pos);
        t.putInt(STAGE, s);
        t.putLong(NEXT, bv.getLong(NEXT).orElse(m.now()));
        SpotData.put(level, pos, t);
        placed(level, pos);
    }

    String describeNear(ServerLevel level, BlockPos at) {
        StringBuilder b = new StringBuilder("lines");
        Map<BlockPos, Line> map = lines.get(level.dimension());
        if (map == null) return b.toString();
        for (Line l : map.values()) {
            if (l.pos.distSqr(at) > 64) continue;
            b.append(" | ").append(l.pos.getX()).append(',').append(l.pos.getY()).append(',').append(l.pos.getZ())
                    .append(" stage=").append(stage(level, l.pos)).append(" overlay=")
                    .append(l.overlay != null && !l.overlay.isRemoved() ? Coast.id(l.overlay.getItemStack()) : "none")
                    .append(" water=").append(underwater(level, l.pos));
        }
        return b.toString();
    }
}
