/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (server/fastener/PlayerFastener: the loose end in the player's hand, resistSnap.)
 */
package net.axther.aftrhrs.feature.fairylights;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A player holding the loose end of a strand while stringing it. The strand belongs to the fastener it started at
 * (its {@code dest} is this player); here it's the one {@link #incoming} entry, the pending strand. Not saved: logging
 * out or putting the item away lets go of it.
 */
public final class PlayerFastener extends Fastener {
    private static final Map<Player, PlayerFastener> ALL = new WeakHashMap<>();
    private final Player player;

    private PlayerFastener(Player player) {
        this.player = player;
    }

    public static PlayerFastener of(Player p) {
        synchronized (ALL) {
            return ALL.computeIfAbsent(p, PlayerFastener::new);
        }
    }

    public static @Nullable PlayerFastener existing(Player p) {
        synchronized (ALL) {
            return ALL.get(p);
        }
    }

    /** The strand this player is stringing right now: [origin ref, strand id], or null. */
    public @Nullable Map.Entry<UUID, FastenerRef> pending() {
        return incoming.isEmpty() ? null : incoming.entrySet().iterator().next();
    }

    static void clearPending(UUID player, UUID strand) {
        synchronized (ALL) {
            for (PlayerFastener f : ALL.values()) {
                if (f.player.getUUID().equals(player) && !f.player.level().isClientSide()) f.incoming.remove(strand);
            }
        }
    }

    public Player player() {
        return player;
    }

    @Override
    public Level level() {
        return player.level();
    }

    @Override
    public FastenerRef ref() {
        return FastenerRef.player(player.getUUID());
    }

    @Override
    public BlockPos pos() {
        return player.blockPosition();
    }

    @Override
    public boolean moving() {
        return true;
    }

    @Override
    public Vec3 point() {
        return point(1);
    }

    /** In the hand holding the strand item, like Fairy Lights. */
    @Override
    public Vec3 point(float pt) {
        Vec3 base = player.getPosition(pt);
        if (player.isFallFlying()) return base;
        float body = Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
        double angle = Math.toRadians(body - 90);
        double perp = angle - Math.PI / 2;
        boolean main = StrandItem.isStrand(player.getItemInHand(InteractionHand.MAIN_HAND));
        boolean right = (player.getMainArm() == HumanoidArm.RIGHT) == main;
        double side = 0.4 * (right ? 1 : -1);
        boolean sneak = player.isCrouching();
        double fwd = sneak ? 0 : 0.2, dy = sneak ? 0.6 : 0.8;
        double dx = Math.cos(perp) * side - Math.cos(angle) * fwd;
        double dz = Math.sin(perp) * side - Math.sin(angle) * fwd;
        return base.add(dx, dy, dz);
    }

    @Override
    public void resistSnap(Vec3 from, double dist) {
        double max = FairyLightsConfig.maxLength;
        if (dist <= max) return;
        double dx = player.getX() - from.x, dy = player.getY() - from.y, dz = player.getZ() - from.z;
        double vx = dx / dist, vy = dy / dist, vz = dz / dist;
        double factor = Math.min((dist - max) / FairyLightsConfig.PULL_RANGE, FairyLightsConfig.PULL_RANGE) * 0.25;
        Vec3 m = player.getDeltaMovement();
        player.setDeltaMovement(m.x - vx * Math.abs(vx) * factor, m.y - vy * Math.abs(vy) * factor, m.z - vz * Math.abs(vz) * factor);
        player.fallDistance = 0;
        if (player instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetEntityMotionPacket(player));
    }

    @Override
    protected void sync() {
    }
}
