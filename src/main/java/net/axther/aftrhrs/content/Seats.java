package net.axther.aftrhrs.content;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Furniture seats: right-click sits the player on a tiny invisible seat entity (an empty item display tagged
 * {@value #TAG}) at the Oraxen seat height. The seat is removed as soon as it is empty; the player is put back on
 * top of the furniture (or in front of it) so they never end up inside its collision.
 */
public final class Seats {
    public static final String TAG = "aftrhrs_seat";

    private record SeatRef(ResourceKey<Level> dim, BlockPos origin, UUID rider) {
    }

    private static final Map<UUID, SeatRef> ACTIVE = new HashMap<>();

    private Seats() {
    }

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Seats::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.entityTags().contains(TAG) && !ACTIVE.containsKey(entity.getUUID())) {
                if (entity.getPassengers().isEmpty()) entity.discard();
                else ACTIVE.put(entity.getUUID(), new SeatRef(level.dimension(), entity.blockPosition(), entity.getPassengers().getFirst().getUUID()));
            }
        });
    }

    /** Sits the player on the free seat of this piece nearest to where they clicked. */
    static boolean sit(ServerPlayer player, ServerLevel level, BlockPos origin, BlockState state, FurnitureBlock block, Vec3 hit) {
        if (player.isPassenger() || player.isSpectator()) return false;
        float yaw = FurnitureBlock.yawDegrees(state);
        double rad = Math.toRadians(yaw), cos = Math.cos(rad), sin = Math.sin(rad);
        List<double[]> spots = new ArrayList<>();
        for (FurnitureSpec.Seat s : block.spec.seats) {
            double dx = s.x() - 0.5, dz = s.z() - 0.5;
            double rx = dx * cos - dz * sin, rz = dx * sin + dz * cos;
            spots.add(new double[]{origin.getX() + 0.5 + rx, origin.getY() + s.y(), origin.getZ() + 0.5 + rz,
                    180f + yaw + s.yaw()});
        }
        spots.sort(Comparator.comparingDouble(p -> hit.distanceToSqr(p[0], p[1], p[2])));
        for (double[] p : spots) {
            AABB around = new AABB(p[0] - 0.3, p[1] - 0.3, p[2] - 0.3, p[0] + 0.3, p[1] + 0.3, p[2] + 0.3);
            boolean taken = !level.getEntities((Entity) null, around, e -> e.entityTags().contains(TAG) && !e.getPassengers().isEmpty()).isEmpty();
            if (taken) continue;
            Display.ItemDisplay seat = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
            seat.setPos(p[0], p[1], p[2]);
            seat.setYRot((float) p[3]);
            seat.addTag(TAG);
            seat.setNoGravity(true);
            // registered before it is added: ENTITY_LOAD (fired by addFreshEntity) discards unknown empty seats, which
            // used to delete every new seat at once (the player rode a removed entity and was dropped next tick)
            ACTIVE.put(seat.getUUID(), new SeatRef(level.dimension(), origin, player.getUUID()));
            if (!level.addFreshEntity(seat)) {
                ACTIVE.remove(seat.getUUID());
                return false;
            }
            if (!player.startRiding(seat, true, true)) {
                ACTIVE.remove(seat.getUUID());
                seat.discard();
                return false;
            }
            player.setYRot((float) p[3]);
            player.setYHeadRot((float) p[3]);
            return true;
        }
        return false;
    }

    /** Removes the seats of a piece that was just broken. */
    static void release(ServerLevel level, BlockPos origin) {
        Iterator<Map.Entry<UUID, SeatRef>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SeatRef> e = it.next();
            if (!e.getValue().origin().equals(origin) || e.getValue().dim() != level.dimension()) continue;
            Entity seat = level.getEntity(e.getKey());
            if (seat != null) {
                seat.ejectPassengers();
                seat.discard();
            }
            it.remove();
        }
    }

    private static int clock;

    private static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty() || (++clock % 5) != 0) return;
        Iterator<Map.Entry<UUID, SeatRef>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SeatRef> e = it.next();
            SeatRef ref = e.getValue();
            ServerLevel level = server.getLevel(ref.dim());
            Entity seat = level == null ? null : level.getEntity(e.getKey());
            if (seat == null) {
                it.remove();
                continue;
            }
            BlockState st = level.getBlockState(ref.origin());
            if (seat.getPassengers().isEmpty() || !(st.getBlock() instanceof FurnitureBlock)) {
                seat.ejectPassengers();
                seat.discard();
                it.remove();
                ServerPlayer rider = server.getPlayerList().getPlayer(ref.rider());
                if (rider != null && rider.level() == level && !rider.isPassenger()) standUp(rider, level, ref.origin(), st);
            }
        }
    }

    /** Puts a player who left a seat somewhere free: on top of the piece, else in front of it. */
    private static void standUp(ServerPlayer p, ServerLevel level, BlockPos origin, BlockState st) {
        if (!(st.getBlock() instanceof FurnitureBlock fb)) return;
        if (level.noCollision(p, p.getBoundingBox())) return;
        VoxelShape col = st.getCollisionShape(level, origin);
        double top = col.isEmpty() ? origin.getY() : origin.getY() + col.max(net.minecraft.core.Direction.Axis.Y);
        Vec3 above = new Vec3(p.getX(), top + 0.01, p.getZ());
        if (level.noCollision(p, p.getBoundingBox().move(above.subtract(p.position())))) {
            p.teleportTo(above.x, above.y, above.z);
            return;
        }
        BlockPos front = origin.relative(FurnitureBlock.front(st));
        Vec3 out = new Vec3(front.getX() + 0.5, front.getY(), front.getZ() + 0.5);
        if (level.noCollision(p, p.getBoundingBox().move(out.subtract(p.position())))) p.teleportTo(out.x, out.y, out.z);
    }
}
