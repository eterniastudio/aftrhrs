package net.axther.aftrhrs.client.entity;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fStack;
import org.jspecify.annotations.Nullable;

/**
 * Renders {@link CreatureView.State}s. Fabric hands the n-th creature of a frame to the n-th renderer of its pool (made
 * as needed, closed again when a frame draws fewer), so the textures are not the renderer's own: all renderers share
 * one pool of textures per size. A texture is bound to the key (and size) it was last drawn for, so a reuse state
 * blits it again without rendering, whichever renderer gets it; a key without one takes the size's texture idle the
 * longest (never one used this frame or the last two, which a reuse may still ask for), else a new one. Cards and
 * cells of one size share the same textures however a list scrolls; textures are only made when a size needs one more
 * than its pool holds (a resize, a new GUI scale or layout) and freed once a frame sweep finds them unused for more
 * than {@link #IDLE_FRAMES} frames, or when the last renderer closes (a resource reload makes new ones).
 */
final class CreatureRenderer extends PictureInPictureRenderer<CreatureView.State> {
	/** Free space around the model, as a share of the rectangle. */
	private static final float MARGIN = 0.06f;
	/** Frames a texture may go unused before the sweep closes it. */
	private static final int IDLE_FRAMES = 20;

	// Shared by every renderer (render thread only).
	/** Renderers open; when the last one closes the textures go with it. */
	private static int open;
	/** Textures by size ({@code w << 32 | h}). */
	private static final Map<Long, ArrayList<Target>> POOL = new HashMap<>();
	/** Textures by the key they hold a picture of, {@code key + "@" + size}. */
	private static final Map<String, Target> BOUND = new HashMap<>();
	/** The last frame any renderer prepared. */
	private static int lastFrame = Integer.MIN_VALUE;

	private boolean closed;
	private final Projection projection = new Projection();
	private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("PIP - Aftr Hrs creature");
	private final SubmitNodeStorage storage = new SubmitNodeStorage();

	CreatureRenderer() {
		open++;
	}

	/**
	 * Whether a reuse of {@code key} at {@code w} x {@code h} pixels can be blitted next frame: the texture drawn for it
	 * still exists and was used in one of the last two frames (so no other key has taken it over).
	 */
	static boolean ready(String key, int w, int h) {
		if (open <= 0) return false;
		Target t = BOUND.get(boundKey(key, size(w, h)));
		return t != null && t.color != null && t.w == w && t.h == h && t.frame >= lastFrame - 1;
	}

	private static long size(int w, int h) {
		return ((long) w << 32) | (h & 0xFFFFFFFFL);
	}

	private static String boundKey(String key, long size) {
		return key + "@" + size;
	}

	@Override
	public Class<CreatureView.State> getRenderStateClass() {
		return CreatureView.State.class;
	}

	@Override
	protected String getTextureLabel() {
		return "aftrhrs creature";
	}

	@Override
	public void prepare(CreatureView.State state, GuiRenderState gui, FeatureRenderDispatcher features, int guiScale) {
		int w = (state.x1() - state.x0()) * guiScale, h = (state.y1() - state.y0()) * guiScale;
		int now = state.frame();
		if (now != lastFrame) {
			// the first creature of a new frame: free what the last frames left unused
			sweep(now);
			lastFrame = now;
		}
		long size = size(w, h);
		String key = boundKey(state.key(), size);
		Target target = BOUND.get(key);
		if (state.reuse()) {
			if (target == null || target.color == null) {
				// only after a renderer reload raced the check: nothing this frame, a fresh render the next
				CreatureView.markDirty(state.key());
				return;
			}
			target.frame = now;
			blit(state, gui, target);
			return;
		}
		// Already drawn into this frame (the same key twice): keep that picture, draw this one into another texture.
		if (target == null || target.color == null || target.frame == now) target = take(size, w, h, key, now);
		target.frame = now;
		projection.setupOrtho(-1000, 1000, w, h, true);
		RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		PoseStack poseStack = new PoseStack();
		fit(state, poseStack, w, h);
		renderToTexture(state, poseStack, storage);
		try (FeatureRenderDispatcher.PreparedFrame frame = features.prepareFrame(storage);
			RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Aftr Hrs creature", target.view,
				Optional.of(GuiRenderer.CLEAR_COLOR), target.depthView, OptionalDouble.of(0))) {
			RenderSystem.bindDefaultUniforms(pass);
			FeatureRenderDispatcher.renderAllFeatures(pass, frame);
		}
		modelView.popMatrix();
		blit(state, gui, target);
	}

	private static void blit(CreatureView.State state, GuiRenderState gui, Target target) {
		gui.addBlitToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
			TextureSetup.singleTexture(target.view, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)), state.pose(),
			state.x0(), state.y0(), state.x1(), state.y1(), 0, 1, 1, 0, -1, state.scissorArea(), null));
	}

	/**
	 * A texture of this size for {@code key}: the one idle the longest, if it sat out more than the last two frames (a
	 * reuse checked by {@link #ready} may still ask for one used then), else a new one. It is bound to {@code key}.
	 */
	private static Target take(long size, int w, int h, String key, int now) {
		ArrayList<Target> list = POOL.computeIfAbsent(size, k -> new ArrayList<>());
		Target best = null;
		for (Target t : list) if (now - t.frame > 2 && (best == null || t.frame < best.frame)) best = t;
		if (best == null) {
			best = new Target(w, h);
			list.add(best);
		}
		if (best.key != null) BOUND.remove(best.key, best);
		Target old = BOUND.put(key, best);
		if (old != null && old != best) old.key = null;
		best.key = key;
		return best;
	}

	/** Closes (and unbinds) textures not drawn into for more than {@link #IDLE_FRAMES} frames. */
	private static void sweep(int now) {
		for (Iterator<ArrayList<Target>> it = POOL.values().iterator(); it.hasNext(); ) {
			ArrayList<Target> list = it.next();
			for (Iterator<Target> ts = list.iterator(); ts.hasNext(); ) {
				Target t = ts.next();
				if (now - t.frame <= IDLE_FRAMES) continue;
				if (t.key != null) BOUND.remove(t.key, t);
				t.close();
				ts.remove();
			}
			if (list.isEmpty()) it.remove();
		}
	}

	/**
	 * Scales the creature to fit {@code w} x {@code h} at any yaw (by its horizontal radius) and stands its lowest point
	 * on the bottom margin. Ends in entity space (blocks, y up), the creature's front toward the viewer.
	 */
	private static void fit(CreatureView.State state, PoseStack poseStack, int w, int h) {
		float[] b = state.modelBounds();
		float cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
		float radius = Math.max(0.05f, (float) Math.hypot((b[3] - b[0]) / 2, (b[5] - b[2]) / 2));
		float height = Math.max(0.05f, b[4] - b[1]);
		float pitch = (float) Math.toRadians(state.pitch());
		float sin = Math.abs((float) Math.sin(pitch)), cos = (float) Math.cos(pitch);
		float tall = height * cos + 2 * radius * sin;
		float scale = Math.min(w * (1 - 2 * MARGIN) / (2 * radius), h * (1 - 2 * MARGIN) / tall);

		// As vanilla's GUI entities: x and y flipped about z, z mirrored (the GUI's y runs down).
		poseStack.translate(w / 2f, h - h * MARGIN - radius * sin * scale, 0);
		poseStack.scale(scale, scale, -scale);
		poseStack.rotateDegrees(Axis.ZP, 180);
		poseStack.rotateDegrees(Axis.XP, -state.pitch());
		poseStack.rotateDegrees(Axis.YP, state.yaw());
		poseStack.translate(-cx, -b[1], -cz);
	}

	@Override
	protected void renderToTexture(CreatureView.State state, PoseStack poseStack, SubmitNodeCollector collector) {
		Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
		Minecraft.getInstance().getEntityRenderDispatcher().submit(state.renderState(), new CameraRenderState(), 0, 0, 0, poseStack, collector);
	}

	@Override
	public void close() {
		// Fabric closes the renderers a frame didn't need; the shared textures go with the last one (a resource reload).
		if (!closed && --open <= 0) {
			open = 0;
			POOL.values().forEach(list -> list.forEach(Target::close));
			POOL.clear();
			BOUND.clear();
		}
		closed = true;
		projectionBuffer.close();
		super.close();
	}

	/** A color and depth texture pair of one size, and the key whose picture it holds. */
	private static final class Target implements AutoCloseable {
		final int w, h;
		GpuTexture color, depth;
		GpuTextureView view, depthView;
		int frame;
		@Nullable String key;

		Target(int w, int h) {
			this.w = w;
			this.h = h;
			GpuDevice device = RenderSystem.getDevice();
			GpuFormat depthFormat = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture().getFormat();
			color = device.createTexture(() -> "UI Aftr Hrs creature texture",
				GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			view = device.createTextureView(color);
			depth = device.createTexture(() -> "UI Aftr Hrs creature depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, depthFormat, w, h, 1, 1);
			depthView = device.createTextureView(depth);
		}

		@Override
		public void close() {
			if (color == null) return;
			view.close();
			color.close();
			depthView.close();
			depth.close();
			color = depth = null;
			view = depthView = null;
		}
	}
}
