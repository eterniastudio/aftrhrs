/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/connection/Connection, HangingFeatureConnection, HangingLightsConnection, GarlandTinselConnection,
 * PennantBuntingConnection; reworked into one class for Aftr Hrs.)
 */
package net.axther.aftrhrs.feature.fairylights;

import net.axther.aftrhrs.feature.fairylights.math.Catenary;
import net.axther.aftrhrs.feature.fairylights.math.Curve;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One strand: owned by the fastener it starts at, hanging to {@link #dest}. Its shape is a {@link Catenary} worked out
 * from both ends whenever they move; the lights, pennants... are spaced along it. A lit String of Lights keeps real
 * light ({@code minecraft:light} blocks) at some of its bulbs while it hangs still.
 */
public final class Strand {
    public static final float MAX_SLACK = 3;
    /** Bunting flag spacing (Fairy Lights' value). */
    public static final float FLAG_SPACING = 0.6875f;

    public enum Mode {
        /** Always lit. */
        ON,
        /** Lit while it is dark outside (dusk to dawn). */
        NIGHT,
        OFF;

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A light or pennant on the strand: position relative to the strand's start, the cord's yaw/pitch there, t = 0..1. */
    public record Feature(int index, float x, float y, float z, float yaw, float pitch, float t) {
    }

    public final UUID id;
    public final StrandKind kind;
    public StrandSpec spec;
    public FastenerRef dest;
    public float slack = 1;
    public Mode mode = Mode.ON;
    /** Synced: the server decided the lights are on right now. */
    public boolean lit = true;
    /** Whether it drops its item when it comes down (false while the player still holds the loose end). */
    public boolean drop = true;
    /** Server: light blocks this strand placed. */
    public final Set<BlockPos> litBlocks = new LinkedHashSet<>();
    boolean removed;
    boolean lightsDirty = true;

    // shape cache (both sides)
    private @Nullable Curve curve;
    private double cx = Double.NaN, cy, cz;
    private float cSlack = -1;
    private List<Feature> features = List.of();
    private int stretchStage;

    // far end lookup cache (both sides, never saved): the last fastener dest resolved to, while it is still valid
    private @Nullable FastenerRef resolvedFor;
    private @Nullable Fastener resolved;
    private @Nullable Level resolvedIn;

    /** Client only, never saved or synced: the renderer's baked decorations for the current shape. */
    public @Nullable Object renderCache;

    // client: gentle wind sway and pushes (blocks of sideways offset at the middle), see FairyLightsClient
    public float swayPos, swayPrev, swayVel;
    public int clientAge;

    public Strand(UUID id, StrandKind kind, StrandSpec spec, FastenerRef dest) {
        this.id = id;
        this.kind = kind;
        this.spec = spec;
        this.dest = dest;
    }

    public boolean removed() {
        return removed;
    }

    /**
     * {@code dest.resolve(level)}, remembered while that fastener stays {@link Fastener#valid() valid} at the same place
     * (no block-entity or entity lookup every tick). Keyed by the identity of {@link #dest}, so a new dest misses; a
     * miss is never cached, so an end that is not loaded yet is looked up again next time. Player ends are not cached.
     */
    public @Nullable Fastener resolveDest(Level level) {
        FastenerRef d = dest;
        if (d.isPlayer()) return d.resolve(level);
        Fastener f = resolved;
        if (f != null && resolvedFor == d && resolvedIn == level && f.valid() && f.pos().equals(d.pos())) return f;
        f = d.resolve(level);
        resolved = f;
        resolvedFor = f == null ? null : d;
        resolvedIn = f == null ? null : level;
        return f;
    }

    // ------------------------------------------------------------------ shape

    /** The shape from {@code from} to {@code to} (cached until an end moves or the slack changes). */
    public Curve curve(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        if (curve == null || dx != cx || dy != cy || dz != cz || slack != cSlack) {
            boolean vertical = dest.kind() == FastenerRef.Kind.KNOT;
            curve = Catenary.between(new Vec3(dx, dy, dz), slack, FairyLightsConfig.maxLength, vertical);
            cx = dx;
            cy = dy;
            cz = dz;
            cSlack = slack;
            features = computeFeatures(curve);
        }
        return curve;
    }

    /** The lights / pennants along the last computed shape. */
    public List<Feature> features() {
        return features;
    }

    /** Forget the shape (spec changed). */
    public void invalidate() {
        curve = null;
        lightsDirty = true;
    }

    private List<Feature> computeFeatures(Curve c) {
        float spacing = spacing();
        if (spacing <= 0 || c.length() > 2 * FairyLightsConfig.maxLength + 2) return List.of();
        List<Feature> out = new ArrayList<>();
        c.visit(spacing, true, (i, x, y, z, yaw, pitch, t) -> out.add(new Feature(i, x, y, z, yaw, pitch, t)));
        return out;
    }

    /** Distance between lights/pennants, 0 when nothing hangs on it. */
    public float spacing() {
        return switch (kind) {
            case LIGHTS, GARLAND -> {
                float s = 0;
                for (StrandSpec.Bulb b : spec.bulbs()) s = Math.max(s, b.style().spacing);
                yield s;
            }
            case BUNTING -> spec.flags().isEmpty() ? 0 : FLAG_SPACING;
            default -> 0;
        };
    }

    public @Nullable StrandSpec.Bulb bulb(int index) {
        List<StrandSpec.Bulb> b = spec.bulbs();
        return b.isEmpty() ? null : b.get(Math.floorMod(index, b.size()));
    }

    public @Nullable StrandSpec.Flag flag(int index) {
        List<StrandSpec.Flag> f = spec.flags();
        return f.isEmpty() ? null : f.get(Math.floorMod(index, f.size()));
    }

    /** Brightest light level of its bulbs (0: no bulbs). */
    public int lightLevel() {
        int l = 0;
        for (StrandSpec.Bulb b : spec.bulbs()) l = Math.max(l, b.style().light);
        return l;
    }

    public ItemStack toItem() {
        ItemStack s = FairyLights.strandItem(kind);
        FairyLights.setSpec(s, spec);
        return s;
    }

    // ------------------------------------------------------------------ server

    /** One server tick, called by the owning fastener with both ends loaded. */
    void serverTick(ServerLevel level, Fastener owner, Fastener other, int salt) {
        Vec3 from = owner.point(), to = other.point();
        curve(from, to);
        boolean dynamic = owner.moving() || other.moving();
        double dist = from.distanceTo(to);
        if (other.moving()) {
            double max = FairyLightsConfig.maxLength;
            double pull = dist - max + FairyLightsConfig.PULL_RANGE;
            if (pull > 0) {
                int stage = (int) (pull + 0.1);
                if (stage > stretchStage) level.playSound(null, to.x, to.y, to.z, FairyLights.SOUND_STRETCH, SoundSource.BLOCKS, 0.4f, 0.5f + stage / 8f);
                stretchStage = stage;
            } else {
                stretchStage = 0;
            }
            if (dist > max + FairyLightsConfig.PULL_RANGE) {
                level.playSound(null, to.x, to.y, to.z, FairyLights.SOUND_SNAP, SoundSource.BLOCKS, 0.75f, 0.8f + level.getRandom().nextFloat() * 0.3f);
                removed = true;
                return;
            }
            if (dist > max) other.resistSnap(from, dist);
        }
        if (spec.bulbs().isEmpty() && litBlocks.isEmpty()) return;
        long time = level.getGameTime() + salt;
        if (time % 20 == 0 || lightsDirty) {
            boolean want = !dynamic && !spec.bulbs().isEmpty() && switch (mode) {
                case ON -> true;
                case OFF -> false;
                case NIGHT -> level.isDarkOutside();
            };
            if (want != lit) {
                lit = want;
                lightsDirty = true;
                owner.markDirty();
            }
        }
        if (lightsDirty || time % 200 == 0) {
            lightsDirty = false;
            if (updateLightBlocks(level, from)) owner.markDirty();
        }
    }

    /** Places / takes away the light blocks of a lit strand; true when the set changed (saved with the fastener). */
    boolean updateLightBlocks(ServerLevel level, Vec3 from) {
        Set<BlockPos> want = new LinkedHashSet<>();
        int level15 = Math.min(15, lightLevel());
        if (lit && FairyLightsConfig.realLight && level15 > 0 && !features.isEmpty()) {
            int max = FairyLightsConfig.maxLightBlocksPerStrand;
            int step = Math.max(2, (int) Math.ceil(features.size() / (double) Math.max(1, max)));
            for (int i = step / 2; i < features.size() && want.size() < max; i += step) {
                Feature f = features.get(i);
                want.add(BlockPos.containing(from.x + f.x(), from.y + f.y() - 0.15, from.z + f.z()));
            }
        }
        boolean changed = false;
        for (BlockPos p : List.copyOf(litBlocks)) {
            if (!want.contains(p)) {
                clearLight(level, p);
                litBlocks.remove(p);
                changed = true;
            }
        }
        BlockState light = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level15);
        for (BlockPos p : want) {
            if (!level.isLoaded(p)) continue;
            BlockState s = level.getBlockState(p);
            if (s.isAir()) {
                level.setBlock(p, light, 3);
            } else if (!s.is(Blocks.LIGHT)) {
                continue;
            }
            if (litBlocks.add(p)) changed = true;
        }
        return changed;
    }

    static void clearLight(Level level, BlockPos p) {
        if (level.isLoaded(p) && level.getBlockState(p).is(Blocks.LIGHT)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
    }

    /** Takes its light blocks away (it came down). */
    void onRemoved(ServerLevel level) {
        removed = true;
        resolved = null;
        resolvedFor = null;
        resolvedIn = null;
        for (BlockPos p : litBlocks) clearLight(level, p);
        litBlocks.clear();
    }

    // ------------------------------------------------------------------ saving

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.store("id", UUIDUtil.CODEC, id);
        t.putString("kind", kind.key());
        t.put("dest", dest.save());
        StrandSpec.CODEC.encodeStart(NbtOps.INSTANCE, spec).result().ifPresent(s -> t.put("spec", s));
        if (slack != 1) t.putFloat("slack", slack);
        if (mode != Mode.ON) t.putString("mode", mode.key());
        if (!lit) t.putBoolean("lit", false);
        if (!drop) t.putBoolean("drop", false);
        if (!litBlocks.isEmpty()) {
            ListTag l = new ListTag();
            for (BlockPos p : litBlocks) l.add(BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, p).getOrThrow());
            t.put("lights", l);
        }
        return t;
    }

    public static @Nullable Strand load(CompoundTag t) {
        UUID id = t.read("id", UUIDUtil.CODEC).orElse(null);
        StrandKind kind = StrandKind.byKey(t.getStringOr("kind", ""));
        FastenerRef dest = t.getCompound("dest").map(FastenerRef::load).orElse(null);
        if (id == null || kind == null || dest == null) return null;
        Strand s = new Strand(id, kind, StrandSpec.EMPTY, dest);
        s.read(t);
        return s;
    }

    /** Updates this strand from saved / synced data (keeps the client's sway). */
    void read(CompoundTag t) {
        StrandSpec sp = t.get("spec") == null ? StrandSpec.EMPTY
                : StrandSpec.CODEC.parse(NbtOps.INSTANCE, t.get("spec")).result().orElse(StrandSpec.EMPTY);
        FastenerRef d = t.getCompound("dest").map(FastenerRef::load).orElse(dest);
        float sl = Math.clamp(t.getFloatOr("slack", 1), 0, MAX_SLACK);
        if (!sp.equals(spec) || !d.equals(dest)) invalidate();
        spec = sp;
        dest = d;
        slack = sl;
        Mode m = Mode.ON;
        try {
            m = Mode.valueOf(t.getStringOr("mode", "on").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        mode = m;
        lit = t.getBooleanOr("lit", true);
        drop = t.getBooleanOr("drop", true);
        litBlocks.clear();
        for (Tag e : t.getListOrEmpty("lights")) BlockPos.CODEC.parse(NbtOps.INSTANCE, e).result().ifPresent(litBlocks::add);
    }
}
