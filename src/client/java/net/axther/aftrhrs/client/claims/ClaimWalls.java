package net.axther.aftrhrs.client.claims;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.axther.aftrhrs.client.Companion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws claim borders in the world as glowing "energy curtains" (the {@code aftrhrs:core/claim_wall} shader), the
 * claim tool's live selection, and the claim/unclaim effects. Called once per frame from the level renderer with
 * camera-relative coordinates.
 */
public final class ClaimWalls {
	/** Walls are built and drawn this far from the player. */
	static final int REACH = 96;
	/** Wall height in blocks (the shader assumes the same). */
	static final float HEIGHT = 5f;

	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder()
		.withBindGroupLayout(BindGroupLayouts.GLOBALS)
		.withBindGroupLayout(BindGroupLayouts.PROJECTION)
		.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
		.withVertexShader(Identifier.fromNamespaceAndPath(Companion.ID, "core/claim_wall"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(Companion.ID, "core/claim_wall"))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.withCull(false)
		.buildSnippet();
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath(Companion.ID, "pipeline/claim_wall"))
		.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
		.build();
	private static final OitPipelineSet OIT = OitPipelineSet.builder("aftrhrs_claim_wall", RenderPipeline.builder(SNIPPET).withShaderDefine("OIT_ADDITIVE")).build();
	// No sortOnUpload: additive LIGHTNING blending with no depth writes (and OIT_ADDITIVE) gives the same picture in
	// any order, so sorting every quad each frame changed nothing.
	public static final RenderType WALL = net.axther.aftrhrs.client.mixin.RenderTypeAccessor.aftrhrs$create("aftrhrs_claim_wall", RenderSetup.builder(PIPELINE).setOitPipelines(OIT).createRenderSetup());

	private record Built(int color, List<ClaimShapes.Edge> edges) {
	}

	private static final Map<Integer, Built> cache = new HashMap<>();
	private static long builtVersion = -1;
	/** This frame draws with vanilla's lightning type (a shader pack is on): vertex colors only, no UVs. */
	private static boolean flat;
	/** This frame's culling frustum (world coordinates): walls out of view, behind the camera included, are skipped. */
	private static Frustum frustum;
	private static int builtX, builtZ;

	private ClaimWalls() {
	}

	/** Submits everything for this frame. {@code collector}/{@code poseStack} come from the level renderer (camera-relative). */
	public static void submit(SubmitNodeCollector collector, PoseStack poseStack) {
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		ClaimState s = ClaimState.INSTANCE;
		if (player == null || mc.level == null || !s.active()) return;
		// Photo mode (F1) and quiet mode hide the walls, unless the claim tool or view item is in hand: then the
		// player is working on claims and keeps the selection and the nearby walls.
		if (net.axther.aftrhrs.client.hud.HudConfig.hidden() && !s.holdingTool(player) && !s.holdingView(player)) return;
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		net.axther.aftrhrs.client.hud.HudConfig cfg = net.axther.aftrhrs.client.hud.HudConfig.get();
		boolean borders = cfg.claimWalls && s.bordersVisible();
		boolean fx = cfg.claimFx && ClaimFx.any();
		List<Preview> preview = borders || s.holdingTool(player) ? preview(mc, player, s) : List.of();
		if (!borders && !fx && preview.isEmpty()) return;
		if (borders) rebuild(player, s);
		// The wall stands on the player's feet (its bright seam at ground level) and rises above their head.
		float base = (float) (cam.y - player.getEyeHeight() - 0.05);
		long now = System.currentTimeMillis();
		// Under a shader pack our curtain shader is painted over: plain glowing gradients (vanilla's lightning type).
		flat = net.axther.aftrhrs.client.compat.IrisCompat.shaderPackInUse();
		frustum = mc.gameRenderer.mainCamera().getCullFrustum();
		collector.submitCustomGeometry(poseStack, flat ? net.minecraft.client.renderer.rendertype.RenderTypes.lightning() : WALL, (pose, buffer) -> {
			if (borders) {
				for (Map.Entry<Integer, Built> e : cache.entrySet()) {
					if (ClaimFx.hides(e.getKey(), now)) continue;
					for (ClaimShapes.Edge edge : e.getValue().edges) wall(buffer, pose, edge, cam, base, 1f, e.getValue().color, 0.85f);
				}
			}
			for (Preview p : preview) {
				float pulse = 0.75f + 0.25f * (float) Math.sin(now / 160.0);
				for (ClaimShapes.Edge edge : p.edges) wall(buffer, pose, edge, cam, base, p.height, p.color, p.alpha * pulse);
			}
			if (fx) ClaimFx.draw(buffer, pose, cam, base, now);
		});
	}

	private static void rebuild(Player p, ClaimState s) {
		int px = p.getBlockX(), pz = p.getBlockZ();
		if (builtVersion == s.version && Math.abs(px - builtX) < 16 && Math.abs(pz - builtZ) < 16) return;
		builtVersion = s.version;
		builtX = px;
		builtZ = pz;
		cache.clear();
		for (ClaimState.Claim c : s.claims) {
			if (!near(c.pieces(), px, pz, REACH + 16)) continue;
			cache.put(c.id(), new Built(s.colorOf(c.rel()), ClaimShapes.outline(c.pieces(), px, pz, REACH + 16)));
		}
	}

	/** Whether any piece's bounding box reaches the square of {@code reach} around (px, pz) (else it has no edges there). */
	static boolean near(List<ClaimState.Piece> pieces, int px, int pz, int reach) {
		for (ClaimState.Piece p : pieces) {
			if (p.maxX() >= px - reach && p.minX() <= px + reach && p.maxZ() >= pz - reach && p.minZ() <= pz + reach) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ the claim tool's selection

	private record Preview(List<ClaimShapes.Edge> edges, int color, float alpha, float height) {
	}

	/** What the last preview was built for; the outlines are only rebuilt when any of it changes. */
	private record PreviewKey(ClaimState.Piece piece, String mode, int targetCx, int targetCz, int px, int pz, int colPreview, int colGrid) {
	}

	private static PreviewKey previewKey;
	private static List<Preview> previewCache = List.of();

	/** What the selection would claim, as Feather would size it (rectangle to the looked-at block, circle to the player). */
	private static List<Preview> preview(Minecraft mc, Player player, ClaimState s) {
		if (!s.holdingTool(player)) return List.of();
		ClaimState.Piece piece = selection(mc, player, s);
		int tcx = 0, tcz = 0;
		if (piece == null && s.mode.equals("chunk")) {
			BlockPos target = target(mc, player);
			tcx = target.getX() >> 4;
			tcz = target.getZ() >> 4;
		}
		PreviewKey key = new PreviewKey(piece, s.mode, tcx, tcz, player.getBlockX(), player.getBlockZ(), s.colPreview, s.colGrid);
		if (!key.equals(previewKey)) {
			previewKey = key;
			previewCache = buildPreview(player, s, piece, tcx, tcz);
		}
		return previewCache;
	}

	private static List<Preview> buildPreview(Player player, ClaimState s, ClaimState.Piece piece, int tcx, int tcz) {
		List<Preview> out = new ArrayList<>();
		if (piece != null) {
			out.add(new Preview(ClaimShapes.outline(List.of(piece), player.getBlockX(), player.getBlockZ(), REACH), s.colPreview, 1f, 1f));
		} else if (s.mode.equals("chunk")) {
			// The chunk a click would claim, and a faint grid of its neighbors.
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int cx = tcx + dx, cz = tcz + dz;
					ClaimState.Piece c = new ClaimState.Piece(cx << 4, cz << 4, (cx << 4) + 15, (cz << 4) + 15, ClaimState.Piece.CHUNK);
					boolean hot = dx == 0 && dz == 0;
					out.add(new Preview(ClaimShapes.outline(List.of(c), player.getBlockX(), player.getBlockZ(), REACH),
						hot ? s.colPreview : s.colGrid, hot ? 1f : 0.35f, hot ? 1f : 0.45f));
				}
			}
		}
		return out;
	}

	/** The piece the tool would claim right now, or null when nothing is being selected. */
	public static ClaimState.Piece selection(Minecraft mc, Player player, ClaimState s) {
		if (s.corner == null) return null;
		if (s.mode.equals("circle")) {
			double dx = player.getX() - (s.corner[0] + 0.5), dz = player.getZ() - (s.corner[2] + 0.5);
			int r = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
			return new ClaimState.Piece(s.corner[0] - r, s.corner[2] - r, s.corner[0] + r, s.corner[2] + r, ClaimState.Piece.CIRCLE);
		}
		BlockPos to = target(mc, player);
		int ax = s.fixed != null ? s.fixed[0] : s.corner[0], az = s.fixed != null ? s.fixed[1] : s.corner[2];
		return new ClaimState.Piece(Math.min(ax, to.getX()), Math.min(az, to.getZ()), Math.max(ax, to.getX()), Math.max(az, to.getZ()), ClaimState.Piece.RECT);
	}

	/** The block the player looks at (up to 48 blocks, like Feather), else the one under their feet. */
	static BlockPos target(Minecraft mc, Player player) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(48));
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos();
		return player.blockPosition().below();
	}

	// ------------------------------------------------------------------ quads

	/**
	 * One wall run as quads of at most 4 blocks (so the distance fade is smooth). {@code rise} is how much of the wall
	 * is up (0..1), {@code rgb} its color and {@code alpha} its strength.
	 */
	static void wall(VertexConsumer buf, PoseStack.Pose pose, ClaimShapes.Edge e, Vec3 cam, float base, float rise, int rgb, float alpha) {
		if (rise <= 0.01f || alpha <= 0.01f) return;
		int len = e.length();
		boolean ax = e.alongX();
		int start = ax ? Math.min(e.x0(), e.x1()) : Math.min(e.z0(), e.z1());
		float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
		float y0 = base - (float) cam.y, y1 = base + HEIGHT * rise - (float) cam.y;
		// Out of view (world coordinates, at its real rise: up to 1.3 for the sweep): skip the whole edge.
		float wy0 = base, wy1 = base + HEIGHT * rise;
		Frustum view = frustum;
		if (view != null && !visible(view, e.x0(), wy0, e.z0(), e.x1(), wy1, e.z1())) return;
		Matrix4f m = pose.pose();
		for (int i = 0; i < len; i += 4) {
			int a0 = start + i, a1 = start + Math.min(len, i + 4);
			float x0 = ax ? a0 : e.x0(), z0 = ax ? e.z0() : a0;
			float x1 = ax ? a1 : e.x0(), z1 = ax ? e.z0() : a1;
			float f0 = fade(x0, z0, cam) * alpha, f1 = fade(x1, z1, cam) * alpha;
			if (f0 <= 0 && f1 <= 0) continue;
			// A long edge only partly in view: each 4-block run on its own.
			if (view != null && len > 4 && !visible(view, x0, wy0, z0, x1, wy1, z1)) continue;
			float rx0 = (float) (x0 - cam.x), rz0 = (float) (z0 - cam.z), rx1 = (float) (x1 - cam.x), rz1 = (float) (z1 - cam.z);
			if (flat) {
				// No curtain shader (shader pack): the seam and the fade up the wall from vertex alpha alone.
				float ym = y0 + (y1 - y0) * 0.12f;
				float s0 = Math.min(1, f0 * 0.9f), s1 = Math.min(1, f1 * 0.9f);
				vertex(buf, m, rx0, y0, rz0).setColor(r, g, b, s0);
				vertex(buf, m, rx1, y0, rz1).setColor(r, g, b, s1);
				vertex(buf, m, rx1, ym, rz1).setColor(r, g, b, s1 * 0.4f);
				vertex(buf, m, rx0, ym, rz0).setColor(r, g, b, s0 * 0.4f);
				vertex(buf, m, rx0, ym, rz0).setColor(r, g, b, s0 * 0.4f);
				vertex(buf, m, rx1, ym, rz1).setColor(r, g, b, s1 * 0.4f);
				vertex(buf, m, rx1, y1, rz1).setColor(r, g, b, 0);
				vertex(buf, m, rx0, y1, rz0).setColor(r, g, b, 0);
				continue;
			}
			vertex(buf, m, rx0, y0, rz0).setUv(a0, 0f).setColor(r, g, b, f0);
			vertex(buf, m, rx1, y0, rz1).setUv(a1, 0f).setColor(r, g, b, f1);
			vertex(buf, m, rx1, y1, rz1).setUv(a1, rise).setColor(r, g, b, f1);
			vertex(buf, m, rx0, y1, rz0).setUv(a0, rise).setColor(r, g, b, f0);
		}
	}

	/** {@code addVertex(pose, x, y, z)} without its Vector3f: the same transform (JOML's transformPosition), inline. */
	private static VertexConsumer vertex(VertexConsumer buf, Matrix4f m, float x, float y, float z) {
		return buf.addVertex(Math.fma(m.m00(), x, Math.fma(m.m10(), y, Math.fma(m.m20(), z, m.m30()))),
			Math.fma(m.m01(), x, Math.fma(m.m11(), y, Math.fma(m.m21(), z, m.m31()))),
			Math.fma(m.m02(), x, Math.fma(m.m12(), y, Math.fma(m.m22(), z, m.m32()))));
	}

	/** Frustum's private box test (what isVisible(AABB) runs), so no AABB is made per edge and per run; null if unreachable. */
	private static final MethodHandle CUBE_IN_FRUSTUM = cubeInFrustum();

	private static MethodHandle cubeInFrustum() {
		try {
			Method m = Frustum.class.getDeclaredMethod("cubeInFrustum", double.class, double.class, double.class, double.class, double.class, double.class);
			m.setAccessible(true);
			return MethodHandles.lookup().unreflect(m);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** {@code view.isVisible(new AABB(x0, y0, z0, x1, y1, z1).inflate(0.01))}, allocation free. */
	private static boolean visible(Frustum view, double x0, double y0, double z0, double x1, double y1, double z1) {
		double minX = Math.min(x0, x1) - 0.01, minY = Math.min(y0, y1) - 0.01, minZ = Math.min(z0, z1) - 0.01;
		double maxX = Math.max(x0, x1) + 0.01, maxY = Math.max(y0, y1) + 0.01, maxZ = Math.max(z0, z1) + 0.01;
		MethodHandle h = CUBE_IN_FRUSTUM;
		if (h == null) return view.isVisible(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
		int r;
		try {
			r = (int) h.invokeExact(view, minX, minY, minZ, maxX, maxY, maxZ);
		} catch (Throwable t) {
			return true;
		}
		// FrustumIntersection.INSIDE (-2) or INTERSECT (-1), as isVisible
		return r == -2 || r == -1;
	}

	private static float fade(float x, float z, Vec3 cam) {
		double d = Math.hypot(x - cam.x, z - cam.z);
		return (float) Math.max(0, Math.min(1, (REACH - d) / 28.0));
	}
}
