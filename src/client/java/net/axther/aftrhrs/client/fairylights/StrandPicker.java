/*
 * Based on Fairy Lights by Paul Fulham (pau101), https://github.com/pau101/Fairy-Lights
 * Copyright (c) 2018 Paul Fulham. MIT License: see licenses/FairyLights-LICENSE.md in the mod jar.
 * (client/ClientEventHandler#getHitConnection + server/collision/*: the strand under the crosshair.)
 */
package net.axther.aftrhrs.client.fairylights;

import net.axther.aftrhrs.feature.fairylights.FairyLightsConfig;
import net.axther.aftrhrs.feature.fairylights.Fastener;
import net.axther.aftrhrs.feature.fairylights.Strand;
import net.axther.aftrhrs.feature.fairylights.StrandKind;
import net.axther.aftrhrs.feature.fairylights.math.Curve;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/** Which strand (and which light / pennant on it) the crosshair is on, when nothing nearer is. */
public final class StrandPicker {
    public record Hit(Fastener origin, Strand strand, int feature, Vec3 at, double distance) {
    }

    private static @Nullable Hit current;

    private StrandPicker() {
    }

    public static @Nullable Hit current() {
        return current;
    }

    public static void update(Minecraft mc) {
        current = pick(mc);
    }

    static @Nullable Hit pick(Minecraft mc) {
        Entity cam = mc.getCameraEntity();
        if (mc.level == null || mc.player == null || cam == null || mc.player.isSpectator()) return null;
        Vec3 eye = cam.getEyePosition(1);
        Vec3 look = cam.getViewVector(1);
        double reach = mc.player.blockInteractionRange();
        Vec3 end = eye.add(look.scale(reach));
        double range = FairyLightsConfig.maxLength + reach + 2;
        Hit best = null;
        double bestD = Double.MAX_VALUE;
        for (Fastener f : List.copyOf(Fastener.CLIENT_LOADED)) {
            if (f.level() != mc.level || f.outgoing().isEmpty()) continue;
            Vec3 from = f.point();
            if (from.distanceToSqr(eye) > range * range) continue;
            for (Strand s : f.outgoing()) {
                if (s.dest.isPlayer()) continue;
                Fastener d = s.resolveDest(mc.level);
                if (d == null) continue;
                Curve c = s.curve(from, d.point());
                for (Strand.Feature fe : s.features()) {
                    AABB b = s.kind == StrandKind.BUNTING ? new AABB(-0.22, -0.5, -0.22, 0.22, 0.02, 0.22) : bounds(s, fe);
                    if (b == null) continue;
                    AABB box = b.move(from.x + fe.x(), from.y + fe.y(), from.z + fe.z());
                    Optional<Vec3> h = box.contains(eye) ? Optional.of(eye) : box.clip(eye, end);
                    if (h.isPresent()) {
                        double dd = h.get().distanceTo(eye);
                        if (dd < bestD) {
                            bestD = dd;
                            best = new Hit(f, s, fe.index(), h.get(), dd);
                        }
                    }
                }
                double r = s.kind.radius + 0.02;
                for (int i = 0; i + 1 < c.count(); i++) {
                    AABB seg = new AABB(from.x + c.x(i), from.y + c.y(i), from.z + c.z(i), from.x + c.x(i + 1), from.y + c.y(i + 1), from.z + c.z(i + 1)).inflate(r);
                    Optional<Vec3> h = seg.clip(eye, end);
                    if (h.isPresent()) {
                        double dd = h.get().distanceTo(eye);
                        if (dd < bestD - 0.05) {
                            bestD = dd;
                            best = new Hit(f, s, -1, h.get(), dd);
                        }
                    }
                }
            }
        }
        if (best == null) return null;
        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS && vanilla.getLocation().distanceTo(eye) < best.distance()) return null;
        return best;
    }

    /** A light's box, widened so its turn on the cord doesn't matter. */
    private static @Nullable AABB bounds(Strand s, Strand.Feature fe) {
        var b = s.bulb(fe.index());
        if (b == null) return null;
        AABB a = b.style().bounds;
        double r = Math.max(Math.max(-a.minX, a.maxX), Math.max(-a.minZ, a.maxZ));
        return new AABB(-r, a.minY, -r, r, a.maxY, r);
    }
}
