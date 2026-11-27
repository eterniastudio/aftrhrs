package net.axther.aftrhrs.feature.travel;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Where a carried mob sits: in front of the carrier at chest height, facing the way the carrier's body faces. Shared
 * by server and client (both place passengers every tick), so it needs no syncing: a mob riding a player can only be a
 * carried one (players ride on the head instead, vanilla's place). Only mobs: nametags are text displays riding every
 * player, and they stay where they are.
 */
public final class CarryPose {
    private CarryPose() {
    }

    /** Whether {@code passenger} riding {@code vehicle} is a mob held in a player's arms. */
    public static boolean inArms(Entity vehicle, Entity passenger) {
        return vehicle instanceof Player && passenger instanceof Mob;
    }

    /** The point the held mob's feet go to. */
    public static Vec3 held(Player carrier, Entity mob) {
        float yaw = carrier.yBodyRot * Mth.DEG_TO_RAD;
        double forward = 0.32 + mob.getBbWidth() * 0.5;
        // Its middle at the carrier's chest, never below their knees.
        double up = Math.max(0.2, (carrier.isCrouching() ? 0.85 : 1.05) - mob.getBbHeight() * 0.5);
        return new Vec3(carrier.getX() - Mth.sin(yaw) * forward, carrier.getY() + up, carrier.getZ() + Mth.cos(yaw) * forward);
    }

    /** The held mob faces where the carrier's body faces. */
    public static void face(Player carrier, Entity mob) {
        float yaw = carrier.yBodyRot;
        mob.setYRot(yaw);
        mob.setXRot(0);
        if (mob instanceof LivingEntity le) {
            le.yBodyRot = yaw;
            le.setYHeadRot(yaw);
        }
    }
}
