package net.axther.aftrhrs.client.fairylights;

import net.axther.aftrhrs.feature.fairylights.FairyLights;
import net.axther.aftrhrs.feature.fairylights.FairyNet;
import net.axther.aftrhrs.feature.fairylights.Fastener;
import net.axther.aftrhrs.feature.fairylights.Strand;
import net.axther.aftrhrs.feature.fairylights.math.Curve;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Client side of the fairy lights: renderers, the letters screen, picking strands for clicks (from the
 * {@code MinecraftStrandMixin}) and the sway: a damped spring per strand pushed by the wind (stronger in rain and
 * storms) and by anything walking through it.
 */
public final class FairyLightsClient {
    /**
     * Client ticks seen, counted before any early return (so it runs on under /tick freeze, while paused and with no
     * strand near): per-tick render caches (strand light, knot colour) stamp themselves with it.
     */
    public static int renderTick;

    private FairyLightsClient() {
    }

    public static void init() {
        BlockEntityRenderers.register(FairyLights.HOOK_BE, HookRenderer::new);
        EntityRendererRegistry.register(FairyLights.KNOT, KnotRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(FairyNet.EditText.TYPE, (payload, ctx) -> ctx.client().gui.setScreen(new BuntingTextScreen(payload)));
        ClientTickEvents.END_CLIENT_TICK.register(FairyLightsClient::tick);
    }

    /** Punched a strand: send it to the server (true = handled, vanilla's attack is skipped). */
    public static boolean attack(Minecraft mc) {
        StrandPicker.Hit hit = StrandPicker.pick(mc);
        if (hit == null || mc.player == null) return false;
        ClientPlayNetworking.send(new FairyNet.Action(hit.origin().ref(), hit.strand().id, true, hit.feature(), hit.at()));
        mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), false);
        return true;
    }

    /** Right-clicked a strand. */
    public static boolean use(Minecraft mc) {
        StrandPicker.Hit hit = StrandPicker.pick(mc);
        if (hit == null || mc.player == null) return false;
        ClientPlayNetworking.send(new FairyNet.Action(hit.origin().ref(), hit.strand().id, false, hit.feature(), hit.at()));
        mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), false);
        return true;
    }

    private static void tick(Minecraft mc) {
        renderTick++;
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        StrandPicker.update(mc);
        Entity cam = mc.getCameraEntity();
        if (cam == null) return;
        Vec3 eye = cam.position();
        float time = level.getGameTime();
        float weather = level.isThundering() ? 2.4f : level.isRaining() ? 1.6f : 1f;
        for (Fastener f : List.copyOf(Fastener.CLIENT_LOADED)) {
            if (f.level() != level || f.outgoing().isEmpty()) continue;
            Vec3 from = f.point();
            if (from.distanceToSqr(eye) > 80 * 80) continue;
            for (Strand s : f.outgoing()) {
                s.swayPrev = s.swayPos;
                s.clientAge++;
                Fastener d = s.resolveDest(level);
                if (d == null || d.moving()) {
                    s.swayPos = s.swayVel = 0;
                    continue;
                }
                Vec3 to = d.point();
                Curve c = s.curve(from, to);
                float len = c.length();
                if (len < 1.5f) continue;
                float sag = c.sag();
                float phase = (s.id.hashCode() & 1023) / 163f;
                float amp = 0.07f * Math.min(1, sag / 1.2f) * Math.min(1, len / 8) * weather;
                float target = amp * (Mth.sin(time * 0.045f + phase) * 0.7f + Mth.sin(time * 0.13f + phase * 1.7f) * 0.3f);
                float k = 0.045f / Math.max(1, len / 8);
                s.swayVel += (target - s.swayPos) * k - s.swayVel * 0.07f;
                push(level, s, c, from, to);
                s.swayVel = Mth.clamp(s.swayVel, -0.15f, 0.15f);
                s.swayPos = Mth.clamp(s.swayPos + s.swayVel, -0.9f, 0.9f);
            }
        }
    }

    /** Players and mobs walking into a strand set it swinging. */
    private static void push(ClientLevel level, Strand s, Curve c, Vec3 from, Vec3 to) {
        AABB box = c.bounds().move(from).inflate(0.8);
        double hx = to.x - from.x, hz = to.z - from.z, hl = Math.sqrt(hx * hx + hz * hz);
        if (hl < 1e-3) return;
        double px = -hz / hl, pz = hx / hl;
        for (Entity e : level.getEntities((Entity) null, box, e -> e instanceof LivingEntity && !e.isSpectator())) {
            double vx = e.getX() - e.xo, vz = e.getZ() - e.zo;
            if (vx * vx + vz * vz < 0.0004) continue;
            for (double hy : new double[]{e.getBbHeight() * 0.85, e.getBbHeight() * 0.45}) {
                double[] near = c.nearest(new Vec3(e.getX() - from.x, e.getY() + hy - from.y, e.getZ() - from.z));
                if (near[0] < 0.55 * 0.55) {
                    float t = (float) near[1];
                    s.swayVel += (float) ((vx * px + vz * pz) * 0.22 * Mth.sin(t * Mth.PI));
                    break;
                }
            }
        }
    }
}
