package net.axther.aftrhrs.client.places;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.axther.aftrhrs.client.Companion;
import net.axther.aftrhrs.client.mixin.RenderTypeAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Waypoint beams: a soft column of light over each place (shader {@code aftrhrs:core/place_beam}), with its name and
 * distance floating at eye height, readable through walls. Death points and graves always show; the rest while
 * {@link PlaceState#beams} is on.
 */
public final class PlaceBeams {
	private static final int RANGE = 1024;
	private static final float HEIGHT = 80f;

	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder()
		.withBindGroupLayout(BindGroupLayouts.GLOBALS)
		.withBindGroupLayout(BindGroupLayouts.PROJECTION)
		.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
		.withVertexShader(Identifier.fromNamespaceAndPath(Companion.ID, "core/claim_wall"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(Companion.ID, "core/place_beam"))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.withCull(false)
		.buildSnippet();
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath(Companion.ID, "pipeline/place_beam"))
		.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
		.build();
	private static final OitPipelineSet OIT = OitPipelineSet.builder("aftrhrs_place_beam", RenderPipeline.builder(SNIPPET).withShaderDefine("OIT_ADDITIVE")).build();
	// No sortOnUpload: additive LIGHTNING blending with no depth writes (and OIT_ADDITIVE) gives the same picture in
	// any order, so sorting the quads each frame changed nothing.
	private static final RenderType BEAM = RenderTypeAccessor.aftrhrs$create("aftrhrs_place_beam", RenderSetup.builder(PIPELINE).setOitPipelines(OIT).createRenderSetup());

	private PlaceBeams() {
	}

	/** A vertex with its position through {@code m} inline: the Pose overload of addVertex allocates a Vector3f per call. */
	private static com.mojang.blaze3d.vertex.VertexConsumer at(org.joml.Matrix4f m, com.mojang.blaze3d.vertex.VertexConsumer buf, float x, float y, float z) {
		return buf.addVertex(m.m00() * x + m.m10() * y + m.m20() * z + m.m30(), m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
				m.m02() * x + m.m12() * y + m.m22() * z + m.m32());
	}

	public static void submit(SubmitNodeCollector collector, PoseStack poseStack) {
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		PlaceState s = PlaceState.INSTANCE;
		if (player == null || !s.active() || s.places.isEmpty()) return;
		// Photo mode (F1) and quiet mode: no beams, no names.
		if (net.axther.aftrhrs.client.hud.HudConfig.hidden()) return;
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		List<PlaceState.Place> list = s.here();
		net.axther.aftrhrs.client.hud.HudConfig cfg = net.axther.aftrhrs.client.hud.HudConfig.get();
		if (cfg.beams == net.axther.aftrhrs.client.hud.HudConfig.Beams.OFF) return;
		boolean all = cfg.beams == net.axther.aftrhrs.client.hud.HudConfig.Beams.ALL;
		list.removeIf(p -> !(all || p.urgent()) || PlaceState.distance(player, p) > RANGE || PlaceState.distance(player, p) < 1.5);
		if (list.isEmpty()) return;
		// Under a shader pack our beam shader is painted over: a plain glowing column (vanilla's lightning type).
		boolean flat = net.axther.aftrhrs.client.compat.IrisCompat.shaderPackInUse();
		net.minecraft.client.renderer.culling.Frustum view = camera.getCullFrustum();
		collector.submitCustomGeometry(poseStack, flat ? net.minecraft.client.renderer.rendertype.RenderTypes.lightning() : BEAM, (pose, buf) -> {
			org.joml.Matrix4f m = pose.pose();
			for (PlaceState.Place p : list) {
				double dx = p.x() - cam.x, dz = p.z() - cam.z;
				double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
				float w = (float) Math.min(6, 0.7 + len / 90.0);
				// Out of view (behind the camera, say): the column's box, as wide as the beam ever gets.
				if (view != null && !view.isVisible(new net.minecraft.world.phys.AABB(p.x() - w, p.y() - 1, p.z() - w, p.x() + w, p.y() - 1 + HEIGHT, p.z() + w))) continue;
				float ox = (float) (-dz / len) * w, oz = (float) (dx / len) * w;
				float x = (float) dx, z = (float) dz, y0 = (float) (p.y() - 1 - cam.y), y1 = y0 + HEIGHT;
				int c = p.color();
				float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
				float a = p.urgent() ? 1f : 0.75f;
				if (flat) {
					// Vertex alpha only: bright down the middle, clear at the edges, fading in at the foot and out higher up.
					float ya = y0 + HEIGHT * 0.04f, yb = y0 + HEIGHT * 0.35f;
					float[] ys = {y0, ya, yb, y1}, as = {0, a, a, 0};
					for (int k = 0; k < 3; k++) {
						for (int side = -1; side <= 1; side += 2) {
							float ex = x + side * ox, ez = z + side * oz;
							at(m, buf, x, ys[k], z).setColor(r, g, b, as[k]);
							at(m, buf, ex, ys[k], ez).setColor(r, g, b, 0);
							at(m, buf, ex, ys[k + 1], ez).setColor(r, g, b, 0);
							at(m, buf, x, ys[k + 1], z).setColor(r, g, b, as[k + 1]);
						}
					}
					continue;
				}
				at(m, buf, x - ox, y0, z - oz).setUv(0, 0).setColor(r, g, b, a);
				at(m, buf, x + ox, y0, z + oz).setUv(1, 0).setColor(r, g, b, a);
				at(m, buf, x + ox, y1, z + oz).setUv(1, 1).setColor(r, g, b, a);
				at(m, buf, x - ox, y1, z - oz).setUv(0, 1).setColor(r, g, b, a);
			}
		});
		if (!cfg.beamLabels) return;
		Font font = mc.font;
		list.sort(java.util.Comparator.comparingDouble(p -> PlaceState.distance(player, p)));
		List<PlaceState.Place> drawn = new java.util.ArrayList<>();
		for (PlaceState.Place p : list) {
			// Places sharing a spot stack their labels instead of overprinting.
			int stack = 0;
			for (PlaceState.Place o : drawn) if (Math.abs(o.x() - p.x()) < 4 && Math.abs(o.z() - p.z()) < 4) stack++;
			drawn.add(p);
			double d = PlaceState.distance(player, p);
			if (d > 256 && !p.urgent()) continue;
			Vec3 at = new Vec3(p.x(), Math.min(p.y() + 2.2, cam.y + 1.5 + Math.min(d / 20.0, 6)), p.z()).subtract(cam);
			float scale = 0.025f * (float) Math.max(1, Math.min(18, d / 7.0));
			poseStack.pushPose();
			poseStack.translate(at.x, at.y, at.z);
			poseStack.mulPose(new org.joml.Matrix4f().rotation(camera.rotation()));
			poseStack.scale(scale, -scale, scale);
			Component label = Component.literal(p.symbol() + " " + (p.kind().equals("death") ? Component.translatable("aftrhrs.places.death").getString() : p.name()));
			Component dist = Component.literal(String.format(Locale.ROOT, "%,dm", Math.round(d)));
			float lw = font.width(label);
			float ly = -stack * 22f;
			collector.submitText(poseStack, -lw / 2f, ly, label.getVisualOrderText(), false, Font.DisplayMode.SEE_THROUGH, 0xF000F0,
				0xFF000000 | p.color(), 0x66000000, 0);
			collector.submitText(poseStack, -font.width(dist) / 2f, ly + 10, dist.getVisualOrderText(), false, Font.DisplayMode.SEE_THROUGH, 0xF000F0,
				0xFFE6E0F5, 0x44000000, 0);
			poseStack.popPose();
		}
	}
}
