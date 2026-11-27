/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/fastener/accessor/*: block, fence and player accessors as one value type.)
 */
package net.axther.aftrhrs.feature.fairylights;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Where one end of a strand is: a hook block, a knot on a fence/wall, or a player holding the loose end. */
public record FastenerRef(Kind kind, BlockPos pos, UUID player) {
    public enum Kind {HOOK, KNOT, PLAYER}

    private static final UUID NIL = new UUID(0, 0);

    public static final StreamCodec<ByteBuf, FastenerRef> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, r -> r.kind.ordinal(), BlockPos.STREAM_CODEC, FastenerRef::pos, UUIDUtil.STREAM_CODEC, FastenerRef::player,
            (k, p, u) -> new FastenerRef(Kind.values()[Math.floorMod(k, Kind.values().length)], p, u));

    public static FastenerRef hook(BlockPos pos) {
        return new FastenerRef(Kind.HOOK, pos.immutable(), NIL);
    }

    public static FastenerRef knot(BlockPos pos) {
        return new FastenerRef(Kind.KNOT, pos.immutable(), NIL);
    }

    public static FastenerRef player(UUID id) {
        return new FastenerRef(Kind.PLAYER, BlockPos.ZERO, id);
    }

    public boolean isPlayer() {
        return kind == Kind.PLAYER;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
        if (kind == Kind.PLAYER) t.store("player", UUIDUtil.CODEC, player);
        else t.store("pos", BlockPos.CODEC, pos);
        return t;
    }

    public static @Nullable FastenerRef load(CompoundTag t) {
        String k = t.getStringOr("kind", "");
        try {
            Kind kind = Kind.valueOf(k.toUpperCase(java.util.Locale.ROOT));
            if (kind == Kind.PLAYER) return t.read("player", UUIDUtil.CODEC).map(FastenerRef::player).orElse(null);
            return t.read("pos", BlockPos.CODEC).map(p -> new FastenerRef(kind, p, NIL)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The fastener if it is loaded (never loads chunks). */
    public @Nullable Fastener resolve(Level level) {
        switch (kind) {
            case HOOK -> {
                if (!level.isLoaded(pos)) return null;
                BlockEntity be = level.getBlockEntity(pos);
                return be instanceof HookBlockEntity h ? h.fastener() : null;
            }
            case KNOT -> {
                if (!level.isLoaded(pos)) return null;
                KnotEntity k = KnotEntity.find(level, pos);
                return k == null ? null : k.fastener();
            }
            default -> {
                Player p = level.getPlayerByUUID(player);
                return p == null ? null : PlayerFastener.of(p);
            }
        }
    }

    /** Server: the fastener's chunk is loaded but the fastener isn't there any more (or the player left). */
    public boolean gone(Level level) {
        if (level.isClientSide()) return false;
        return switch (kind) {
            case HOOK -> level.isLoaded(pos) && !(level.getBlockEntity(pos) instanceof HookBlockEntity);
            // knots are entities: they may load a little after their chunk's blocks
            case KNOT -> level instanceof net.minecraft.server.level.ServerLevel sl && sl.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(pos))
                    && KnotEntity.find(level, pos) == null;
            case PLAYER -> level.getPlayerByUUID(player) == null;
        };
    }
}
