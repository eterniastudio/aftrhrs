package net.axther.aftrhrs.client.claims;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The moment a claim changes in front of you: a wall that rises out of the ground with a bright sweep running round
 * it (claimed, grown), or one that sinks and fades in the claim's color (unclaimed, abandoned). Sparks rise along the
 * outline, and your own claims get a chime and a HUD toast.
 */
public final class ClaimFx {
	static final long DURATION = 2600;

	private record Fx(int claimId, boolean grow, List<ClaimShapes.Edge> edges, int perimeter, int color, long start) {
	}

	private static final List<Fx> ACTIVE = new ArrayList<>();

	private ClaimFx() {
	}

	static void accept(JsonObject m) {
		Minecraft mc = Minecraft.getInstance();
		Player p = mc.player;
		if (p == null) return;
		String kind = Link.str(m, "kind");
		boolean grow = kind.equals("claim") || kind.equals("resize");
		List<ClaimState.Piece> pieces = ClaimState.pieces(m.get("areas"));
		if (pieces.isEmpty()) return;
		ClaimState s = ClaimState.INSTANCE;
		int color = grow ? s.colorOf(Link.str(m, "rel")) : 0xFF6F7F;
		List<ClaimShapes.Edge> edges = ClaimShapes.outline(pieces, p.getBlockX(), p.getBlockZ(), ClaimWalls.REACH + 32);
		int perimeter = 0;
		for (ClaimShapes.Edge e : edges) perimeter += e.length();
		ACTIVE.add(new Fx(Link.num(m, "id", 0), grow, edges, Math.max(1, perimeter), color, System.currentTimeMillis()));
		net.axther.aftrhrs.client.hud.HudConfig cfg = net.axther.aftrhrs.client.hud.HudConfig.get();
		if (cfg.claimFx) sparks(mc, edges, color, grow);
		if (Link.bool(m, "mine", false)) {
			if (cfg.claimSounds) p.playSound(grow ? SoundEvents.AMETHYST_BLOCK_RESONATE : SoundEvents.AMETHYST_BLOCK_BREAK, 1f, grow ? 1.2f : 0.8f);
			if (grow && cfg.claimSounds) mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.35f, 1.6f, false);
			ClaimHud.toast(grow, Link.str(m, "name"), m.has("left") ? Link.lng(m, "left", 0) : -1, pieces);
		}
	}

	static boolean any() {
		if (ACTIVE.isEmpty()) return false;
		long now = System.currentTimeMillis();
		ACTIVE.removeIf(f -> now - f.start > DURATION);
		return !ACTIVE.isEmpty();
	}

	/** While a claim's own wall is rising it replaces the claim's normal border. */
	static boolean hides(int claimId, long now) {
		for (Fx f : ACTIVE) if (f.grow && f.claimId == claimId && now - f.start < DURATION) return true;
		return false;
	}

	static void draw(VertexConsumer buf, PoseStack.Pose pose, Vec3 cam, float base, long now) {
		for (Iterator<Fx> it = ACTIVE.iterator(); it.hasNext(); ) {
			Fx f = it.next();
			float t = (now - f.start) / (float) DURATION;
			if (t >= 1f) {
				it.remove();
				continue;
			}
			float rise, alpha;
			if (f.grow) {
				float k = Math.min(1f, t / 0.45f);
				rise = 1f - (1f - k) * (1f - k) * (1f - k);           // ease out cubic
				alpha = 0.85f + 1.4f * (float) Math.exp(-t * 6.0);   // flash, then settle on the normal strength
			} else {
				rise = 1f - t * t;
				alpha = (1f - t) * 1.2f;
			}
			for (ClaimShapes.Edge e : f.edges) ClaimWalls.wall(buf, pose, e, cam, base, rise, f.color, alpha);
			if (f.grow) sweep(buf, pose, f, cam, base, t);
		}
	}

	/** A bright pillar that runs once round the new outline. */
	private static void sweep(VertexConsumer buf, PoseStack.Pose pose, Fx f, Vec3 cam, float base, float t) {
		if (t > 0.8f) return;
		float at = (t / 0.8f) * f.perimeter;
		float walked = 0;
		for (ClaimShapes.Edge e : f.edges) {
			int len = e.length();
			if (at <= walked + len) {
				float d = at - walked;
				int from = (int) Math.max(0, Math.floor(d) - 2), to = (int) Math.min(len, Math.floor(d) + 1);
				if (to <= from) return;
				boolean ax = e.alongX();
				int s0 = ax ? Math.min(e.x0(), e.x1()) : Math.min(e.z0(), e.z1());
				ClaimShapes.Edge bit = ax ? new ClaimShapes.Edge(s0 + from, e.z0(), s0 + to, e.z0()) : new ClaimShapes.Edge(e.x0(), s0 + from, e.x0(), s0 + to);
				ClaimWalls.wall(buf, pose, bit, cam, base, 1.3f, 0xFFFFFF, 2.2f);
				return;
			}
			walked += len;
		}
	}

	private static void sparks(Minecraft mc, List<ClaimShapes.Edge> edges, int color, boolean grow) {
		if (mc.level == null || mc.player == null) return;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		double y = mc.player.getY();
		int budget = 220;
		for (ClaimShapes.Edge e : edges) {
			boolean ax = e.alongX();
			int s0 = ax ? Math.min(e.x0(), e.x1()) : Math.min(e.z0(), e.z1());
			for (int i = 0; i < e.length() && budget > 0; i += 2, budget--) {
				double x = ax ? s0 + i + rnd.nextDouble() : e.x0();
				double z = ax ? e.z0() : s0 + i + rnd.nextDouble();
				if (mc.player.distanceToSqr(x, y, z) > 64 * 64) continue;
				mc.level.addParticle(grow ? ParticleTypes.END_ROD : ParticleTypes.SMOKE, x, y + rnd.nextDouble() * 1.5, z, 0, grow ? 0.06 + rnd.nextDouble() * 0.05 : 0.02, 0);
			}
		}
	}

	public static void clear() {
		ACTIVE.clear();
	}
}
