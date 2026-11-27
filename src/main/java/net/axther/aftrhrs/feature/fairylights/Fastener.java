/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/fastener/Fastener + AbstractFastener: outgoing connections owned here, incoming ones remembered.)
 */
package net.axther.aftrhrs.feature.fairylights;

import net.axther.aftrhrs.AftrHrs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A point strands hang from. It owns the strands that start here ({@link #outgoing}) and remembers the ones that end
 * here ({@link #incoming}: the owner's ref, so breaking this end can take them down too).
 */
public abstract class Fastener {
    /** Client: fasteners loaded in the client's level (for picking and the sway). */
    public static final Set<Fastener> CLIENT_LOADED = Collections.newSetFromMap(new java.util.WeakHashMap<>());

    protected final Map<UUID, Strand> outgoing = new LinkedHashMap<>();
    protected final Map<UUID, FastenerRef> incoming = new LinkedHashMap<>();
    private boolean dirty;

    public abstract @Nullable Level level();

    public abstract FastenerRef ref();

    /** Where strands attach (world position). */
    public abstract Vec3 point();

    /** {@link #point()} between ticks, for drawing. */
    public Vec3 point(float partialTick) {
        return point();
    }

    public abstract BlockPos pos();

    /** The face a hook sits on (UP for knots and players). */
    public Direction facing() {
        return Direction.UP;
    }

    /** Players: the end moves, so the shape is worked out every tick and the lights stay dark. */
    public boolean moving() {
        return false;
    }

    /**
     * Whether this fastener is still the live one at its place (a broken hook or a discarded knot is not), so a strand
     * may keep using it as its far end ({@link Strand#resolveDest}). Players' ends are never cached.
     */
    public boolean valid() {
        return true;
    }

    /** A player at the end of a too long strand gets pulled back a little. */
    public void resistSnap(Vec3 from, double dist) {
    }

    /** Server: save and send the new state. */
    protected abstract void sync();

    public Collection<Strand> outgoing() {
        return outgoing.values();
    }

    public Map<UUID, FastenerRef> incoming() {
        return incoming;
    }

    public @Nullable Strand strand(UUID id) {
        return outgoing.get(id);
    }

    public int count() {
        return outgoing.size() + incoming.size();
    }

    public boolean empty() {
        return outgoing.isEmpty() && incoming.isEmpty();
    }

    public void markDirty() {
        dirty = true;
    }

    /** Server: saves and sends a pending change now (before the entity is written to disk, for example). */
    public void flush() {
        if (dirty) {
            dirty = false;
            sync();
        }
    }

    /** Whether a strand already joins these two fasteners (either direction). */
    public boolean joinedTo(FastenerRef other) {
        for (Strand s : outgoing.values()) if (s.dest.equals(other)) return true;
        return incoming.containsValue(other);
    }

    /** Adds a strand that starts here (the caller tells the other end). */
    public void addOutgoing(Strand s) {
        outgoing.put(s.id, s);
        markDirty();
    }

    public void addIncoming(UUID id, FastenerRef from) {
        incoming.put(id, from);
        markDirty();
    }

    public void forgetIncoming(UUID id) {
        if (incoming.remove(id) != null) markDirty();
    }

    /** Bounds of everything hanging from here (world space), for culling. */
    public AABB renderBounds() {
        Vec3 p = point();
        AABB box = new AABB(p, p).inflate(0.5);
        Level level = level();
        if (level == null) return box;
        for (Strand s : outgoing.values()) {
            Fastener d = s.resolveDest(level);
            if (d == null) continue;
            box = box.minmax(s.curve(p, d.point()).bounds().move(p).inflate(1.2));
        }
        return box;
    }

    // ------------------------------------------------------------------ server

    /** One server tick: strand upkeep, ends that went away, snapping, light blocks; sends changes. */
    public void serverTick(ServerLevel level) {
        if (!outgoing.isEmpty()) {
            int salt = pos().hashCode();
            // no copy of the map: nothing below changes this fastener's strands, removals wait for the end of the loop
            List<Strand> drop = null;
            List<Vec3> dropAt = null;
            for (Strand s : outgoing.values()) {
                Fastener other = s.resolveDest(level);
                Vec3 at;
                if (other == null) {
                    if (!s.dest.gone(level)) continue;
                    at = Vec3.atCenterOf(pos());
                } else {
                    s.serverTick(level, this, other, salt);
                    if (!s.removed()) continue;
                    at = other.point();
                }
                if (drop == null) {
                    drop = new ArrayList<>(2);
                    dropAt = new ArrayList<>(2);
                }
                drop.add(s);
                dropAt.add(at);
            }
            if (drop != null) for (int i = 0; i < drop.size(); i++) removeStrand(level, drop.get(i), dropAt.get(i));
        }
        if (!incoming.isEmpty() && level.getGameTime() % 20 == Math.floorMod(pos().hashCode(), 20)) {
            for (Map.Entry<UUID, FastenerRef> e : List.copyOf(incoming.entrySet())) {
                FastenerRef from = e.getValue();
                Fastener origin = from.resolve(level);
                if (origin == null ? from.gone(level) : origin.strand(e.getKey()) == null) forgetIncoming(e.getKey());
            }
        }
        flush();
    }

    /** Server: takes one strand down (light blocks away, the other end told, the item dropped at {@code dropAt} when it drops). */
    public void removeStrand(ServerLevel level, Strand s, @Nullable Vec3 dropAt) {
        removeStrand(level, s, dropAt, s.drop);
    }

    public void removeStrand(ServerLevel level, Strand s, @Nullable Vec3 dropAt, boolean drop) {
        if (outgoing.remove(s.id) == null) return;
        Fastener other = s.resolveDest(level);
        s.onRemoved(level);
        if (other != null) other.forgetIncoming(s.id);
        if (s.dest.isPlayer()) PlayerFastener.clearPending(s.dest.player(), s.id);
        if (drop && dropAt != null) dropItem(level, dropAt, s.toItem());
        markDirty();
    }

    /** Server: everything attached here comes down (this fastener is going away). */
    public void removeAll(ServerLevel level, Vec3 dropAt, boolean drop) {
        boolean any = !empty();
        for (Strand s : List.copyOf(outgoing.values())) removeStrand(level, s, dropAt, drop && s.drop);
        for (Map.Entry<UUID, FastenerRef> e : List.copyOf(incoming.entrySet())) {
            Fastener origin = e.getValue().resolve(level);
            Strand s = origin == null ? null : origin.strand(e.getKey());
            if (s != null) origin.removeStrand(level, s, dropAt, drop && s.drop);
        }
        incoming.clear();
        markDirty();
        if (any) level.playSound(null, dropAt.x, dropAt.y, dropAt.z, FairyLights.SOUND_DISCONNECT, SoundSource.BLOCKS, 0.8f, 1);
    }

    public static void dropItem(ServerLevel level, Vec3 at, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemEntity e = new ItemEntity(level, at.x, at.y, at.z, stack);
        e.setDeltaMovement(level.getRandom().nextGaussian() * 0.05, level.getRandom().nextGaussian() * 0.05 + 0.2, level.getRandom().nextGaussian() * 0.05);
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
    }

    // ------------------------------------------------------------------ saving / syncing

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag out = new ListTag();
        for (Strand s : outgoing.values()) out.add(s.save());
        t.put("outgoing", out);
        ListTag in = new ListTag();
        for (Map.Entry<UUID, FastenerRef> e : incoming.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.store("id", UUIDUtil.CODEC, e.getKey());
            c.put("from", e.getValue().save());
            in.add(c);
        }
        t.put("incoming", in);
        return t;
    }

    /** Loads saved or synced data, keeping strands that are still there (their cached shape and sway). */
    public void load(CompoundTag t) {
        Set<UUID> seen = new HashSet<>();
        for (Tag e : t.getListOrEmpty("outgoing")) {
            if (!(e instanceof CompoundTag c)) continue;
            UUID id = c.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null) continue;
            Strand have = outgoing.get(id);
            if (have != null) {
                have.read(c);
            } else {
                Strand s = Strand.load(c);
                if (s == null) {
                    AftrHrs.LOG.warn("Fairy lights: dropped an unreadable strand at {}", pos());
                    continue;
                }
                outgoing.put(id, s);
            }
            seen.add(id);
        }
        outgoing.keySet().retainAll(seen);
        incoming.clear();
        for (Tag e : t.getListOrEmpty("incoming")) {
            if (!(e instanceof CompoundTag c)) continue;
            UUID id = c.read("id", UUIDUtil.CODEC).orElse(null);
            FastenerRef from = c.getCompound("from").map(FastenerRef::load).orElse(null);
            if (id != null && from != null) incoming.put(id, from);
        }
    }

    /** Strand count in a list, for limits. */
    public static int strands(Collection<? extends Fastener> fasteners) {
        int n = 0;
        for (Fastener f : fasteners) n += f.outgoing.size();
        return n;
    }
}
