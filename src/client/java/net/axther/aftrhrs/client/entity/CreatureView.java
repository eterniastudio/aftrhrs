package net.axther.aftrhrs.client.entity;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.constant.DataTickets;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.Species;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * Draws an Arcana creature into a GUI rectangle with the mod's own GeckoLib renderer: a client-only instance of the
 * creature's {@link EntityType} (never added to the world) wearing the pet's look (variant tint, shiny, name) and
 * playing a held clip (idle, sit, sleep) with one-shots such as {@code happy} on top. The model is scaled so the whole
 * creature fits at any yaw and stands on the rectangle's bottom edge. Yaw 0 faces the viewer; positive yaw turns it to
 * its left (the viewer's right). Pitch tilts the view down onto it.
 *
 * <p>Works offline: nothing is asked of the server. Instances are kept per {@code key} (a pet id, a codex species) so
 * their animation state carries from frame to frame; they belong to the client world they were made in and are dropped
 * by {@link #worldChanged()} when the world changes (a dimension change, a respawn into another dimension) and by
 * {@link #clear()} on disconnect. The drawing is a picture-in-picture render state ({@link CreatureRenderer}, a pool of
 * textures per size so any number of creatures can be drawn in one frame).
 *
 * <p>A caller may let a drawing be reused for a while ({@code maxAgeMs}): while nothing about it changed, the texture
 * last drawn under that key is blitted again instead of posing and rendering the model, so a page of idle previews
 * renders at about 30 Hz rather than every frame. At most {@link #STALE_PER_FRAME} aged-out drawings re-render per
 * frame, which spreads a page's re-renders over several frames.
 */
public final class CreatureView {
	/** A pleasant three-quarter view. */
	public static final float DEFAULT_YAW = 30, DEFAULT_PITCH = 12;
	/** Instances kept (least recently drawn go first). */
	private static final int KEEP = 96;
	/** Aged-out (but otherwise unchanged) drawings re-rendered per frame; the rest reuse their texture a little longer. */
	private static final int STALE_PER_FRAME = 4;

	/** How a creature should look: tint (0xRRGGBB, 0xFFFFFF none), shiny, custom name (may be null). */
	public record Look(int tint, boolean shiny, @Nullable String name) {
		public static final Look PLAIN = new Look(0xFFFFFF, false, null);
	}

	private static final Map<String, Instance> INSTANCES = new LinkedHashMap<>(16, 0.75f, true);
	/** Rest-pose bounds per model (x0, y0, z0, x1, y1, z1 in blocks, before render scale). */
	private static final Map<Identifier, float[]> BOUNDS = new HashMap<>();
	/** Types that failed once (logged once, not every frame). */
	private static final java.util.Set<EntityType<?>> WARNED = new java.util.HashSet<>();

	/** The extractor of the frame being built, and that frame's number (drives the renderer's texture sweep). */
	private static @Nullable GuiGraphicsExtractor frameOwner;
	private static int frame;
	/** Aged-out drawings re-rendered in {@link #frame}. */
	private static int stale;
	private static int nextId = -1_000_000;

	private static final class Instance {
		final Entity entity;
		String held = "";
		Look look;
		/** What was rendered last (null: nothing yet). */
		@Nullable Drawn last;
		/** Something changed the entity since (a one-shot, a new look or held clip): the next draw renders. */
		boolean dirty = true;

		Instance(Entity entity) {
			this.entity = entity;
		}
	}

	/** The inputs of an instance's last render: pixel size, view, held clip, look, color, type, and when (ms). */
	private record Drawn(int w, int h, float yaw, float pitch, String hold, Look look, int colorArgb, EntityType<?> type, long renderedAt) {
		boolean same(int w, int h, float yaw, float pitch, String hold, Look look, int colorArgb, EntityType<?> type) {
			return this.w == w && this.h == h && this.yaw == yaw && this.pitch == pitch && this.hold.equals(hold) && this.look.equals(look)
				&& this.colorArgb == colorArgb && this.type == type;
		}
	}

	private CreatureView() {
	}

	/** A new renderer for Fabric's picture-in-picture registry. */
	public static PictureInPictureRenderer<State> newRenderer() {
		return new CreatureRenderer();
	}

	/**
	 * The creature type for an id: a registry id ({@code aftrhrs:black_cat}), a bare Arcana id ({@code black_cat}), a
	 * model id ({@code arcana_black_cat}, what older servers sent) or a MythicMobs id ({@code ArcanaBlackCat}). Null if
	 * none matches.
	 */
	public static @Nullable EntityType<?> type(@Nullable String id) {
		if (id == null || id.isBlank()) return null;
		Identifier rl = Identifier.tryParse(id.contains(":") ? id : AftrHrs.ID + ":" + id);
		if (rl != null) {
			EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(rl);
			if (ArcanaEntities.all().containsKey(t)) return t;
		}
		EntityType<?> t = ArcanaEntities.type(id.startsWith("arcana_") ? id.substring("arcana_".length()) : id);
		return t != null ? t : ArcanaEntities.byMythicId(id);
	}

	/** Plays a one-shot (by role, e.g. {@code happy}, else by clip name) on the creature drawn under {@code key}. */
	public static void play(String key, String roleOrClip) {
		Instance in = INSTANCES.get(key);
		if (in == null || !(in.entity instanceof ArcanaMob mob)) return;
		String clip = mob.species().anim(roleOrClip);
		mob.play(clip != null ? clip : roleOrClip);
		in.dirty = true;
	}

	/** The next draw under {@code key} renders (its texture went missing). */
	static void markDirty(String key) {
		Instance in = INSTANCES.get(key);
		if (in != null) in.dirty = true;
	}

	/** The first of these clips the creature's model has, or "" (for picking a pose: sleep, lie down, sit ...). */
	public static String firstClip(@Nullable EntityType<?> type, String... clips) {
		if (type == null || !ArcanaEntities.all().containsKey(type)) return "";
		Species s = ArcanaEntities.species(type);
		for (String c : clips) {
			String byRole = s.anim(c);
			if (byRole != null && s.has(byRole)) return byRole;
			if (s.has(c)) return c;
		}
		return "";
	}

	/**
	 * Draws the creature. {@code hold}: a clip held over locomotion ("" idles); {@code colorArgb}: multiplied over
	 * everything (-1 for none; dark tints draw a silhouette, its glow put out). False when nothing was drawn (no world,
	 * not an Arcana creature): the caller shows its fallback.
	 */
	public static boolean draw(GuiGraphicsExtractor g, String key, @Nullable EntityType<?> type, Look look, int x0, int y0, int x1, int y1,
		float yawDegrees, float pitchDegrees, String hold, int colorArgb) {
		return draw(g, key, type, look, x0, y0, x1, y1, yawDegrees, pitchDegrees, hold, colorArgb, 0);
	}

	/**
	 * {@link #draw(GuiGraphicsExtractor, String, EntityType, Look, int, int, int, int, float, float, String, int) draw},
	 * but the picture last rendered under {@code key} may be shown again for up to {@code maxAgeMs} (0: always render)
	 * while its pixel size, view, held clip, look, color and type are unchanged and nothing was played on it.
	 */
	public static boolean draw(GuiGraphicsExtractor g, String key, @Nullable EntityType<?> type, Look look, int x0, int y0, int x1, int y1,
		float yawDegrees, float pitchDegrees, String hold, int colorArgb, int maxAgeMs) {
		if (x1 <= x0 || y1 <= y0 || type == null) return false;
		Minecraft mc = Minecraft.getInstance();
		Instance in = instance(mc, key, type);
		if (in == null || !(in.entity instanceof ArcanaMob mob)) return false;
		if (!look.equals(in.look)) {
			dress(in, look);
			in.dirty = true;
		}
		if (!hold.equals(in.held)) {
			in.held = hold;
			mob.setHoldClip(hold != null && mob.species().has(hold) ? hold : "");
			in.dirty = true;
		}
		if (g != frameOwner) {
			frameOwner = g;
			frame++;
			stale = 0;
		}
		long ms = Util.getMillis();
		int scale = mc.getWindow().getGuiScale();
		int pw = (x1 - x0) * scale, ph = (y1 - y0) * scale;
		Drawn last = in.last;
		if (maxAgeMs > 0 && !in.dirty && last != null && last.same(pw, ph, yawDegrees, pitchDegrees, hold, look, colorArgb, type)
			&& CreatureRenderer.ready(key, pw, ph)) {
			// Only the age is against a reuse: re-render, unless this frame already re-rendered its share.
			if (ms - last.renderedAt() < maxAgeMs || stale >= STALE_PER_FRAME) {
				submit(g, key, null, null, yawDegrees, pitchDegrees, x0, y0, x1, y1);
				return true;
			}
			stale++;
		}
		// Animation time is the entity's age (GeckoLib reads tickCount + partial tick); the instance never ticks.
		in.entity.tickCount = (int) (ms / 50 % 1_000_000_000L);
		float partial = (ms % 50) / 50f;
		EntityRenderState state;
		try {
			state = mc.getEntityRenderDispatcher().extractEntity(in.entity, partial);
		} catch (RuntimeException e) {
			if (WARNED.add(type)) AftrHrs.LOG.warn("Could not draw {} in a menu", type, e);
			return false;
		}
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		state.lightCoords = 0xF000F0;
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180;
			living.yRot = 0;
			living.xRot = 0;
		}
		boolean silhouette = colorArgb != -1 && ARGB.red(colorArgb) + ARGB.green(colorArgb) + ARGB.blue(colorArgb) < 200;
		if (state instanceof GeoRenderState geo) {
			if (colorArgb != -1) {
				int base = geo.getOrDefaultGeckolibData(DataTickets.RENDER_COLOR, -1);
				geo.addGeckolibData(DataTickets.RENDER_COLOR, ARGB.multiply(base, colorArgb));
			}
			if (silhouette) geo.addGeckolibData(ArcanaGeoRenderer.GLOW_OFF, true);
		}
		float[] bounds = bounds(mc, in.entity, state);
		submit(g, key, state, bounds, yawDegrees, pitchDegrees, x0, y0, x1, y1);
		in.last = new Drawn(pw, ph, yawDegrees, pitchDegrees, hold, look, colorArgb, type, ms);
		in.dirty = false;
		return true;
	}

	/** Submits a render ({@code state} set) or a reuse of the key's last texture ({@code state} null). */
	private static void submit(GuiGraphicsExtractor g, String key, @Nullable EntityRenderState state, float @Nullable [] bounds, float yaw, float pitch,
		int x0, int y0, int x1, int y1) {
		Sink sink = (Sink) g;
		Matrix3x2f gui = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = sink.aftrhrs$scissor();
		sink.aftrhrs$submitPip(new State(key, state, bounds, yaw, pitch, frame, gui, x0, y0, x1, y1, scissor,
			PictureInPictureRenderState.getBounds(x0, y0, x1, y1, gui, scissor)));
	}

	/** The client world changed (dimension change, respawn into another dimension): instances belong to the old level. */
	public static void worldChanged() {
		INSTANCES.clear();
	}

	/** Forgets every instance and the cached model bounds (leaving the server). */
	public static void clear() {
		INSTANCES.clear();
		BOUNDS.clear();
	}

	private static @Nullable Instance instance(Minecraft mc, String key, EntityType<?> type) {
		ClientLevel level = mc.level;
		if (level == null) return null;
		Instance in = INSTANCES.get(key);
		if (in != null && in.entity.level() == level && in.entity.getType() == type) return in;
		Entity e;
		try {
			// Checks ignored: on a Peaceful server a monster can't spawn, but its picture still belongs in the menu.
			e = type.create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
		} catch (RuntimeException ex) {
			if (WARNED.add(type)) AftrHrs.LOG.warn("Could not make a {} for a menu", type, ex);
			return null;
		}
		if (e == null) return null;
		// Never in the world, but GeckoLib keys its animation state by entity id: a private range far from real ids.
		e.setId(nextId--);
		e.setPos(0, 0, 0);
		e.setYRot(0);
		e.setXRot(0);
		e.setYBodyRot(0);
		e.setYHeadRot(0);
		in = new Instance(e);
		INSTANCES.put(key, in);
		while (INSTANCES.size() > KEEP) {
			var eldest = INSTANCES.keySet().iterator().next();
			INSTANCES.remove(eldest);
		}
		return in;
	}

	private static void dress(Instance in, Look look) {
		in.look = look;
		if (in.entity instanceof ArcanaCreature c) {
			c.setTint(look.tint());
			c.setShiny(look.shiny());
		}
		in.entity.setCustomName(look.name() == null || look.name().isEmpty() ? null : Component.literal(look.name()));
	}

	/** The model's rest-pose bounds in blocks, scaled as the renderer will scale it. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static float[] bounds(Minecraft mc, Entity entity, EntityRenderState state) {
		EntityRenderer<?, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
		float[] fallback = {-entity.getBbWidth() / 2, 0, -entity.getBbWidth() / 2, entity.getBbWidth() / 2, entity.getBbHeight(), entity.getBbWidth() / 2};
		if (!(renderer instanceof GeoEntityRenderer geo) || !(state instanceof GeoRenderState gs)) return fallback;
		Identifier model;
		try {
			model = geo.getGeoModel().getModelResource(gs);
		} catch (RuntimeException e) {
			return fallback;
		}
		float[] b = BOUNDS.computeIfAbsent(model, id -> {
			try {
				BakedGeoModel baked = geo.getGeoModel().getBakedModel(id);
				float[] out = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
				for (GeoBone bone : baked.topLevelBones()) collect(bone, out);
				return out[0] > out[3] ? null : out;
			} catch (RuntimeException e) {
				return null;
			}
		});
		if (b == null) return fallback;
		float s = (renderer instanceof ArcanaGeoRenderer<?> a ? a.modelScale() : 1f) * (state instanceof LivingEntityRenderState l ? l.scale : 1f);
		return new float[]{b[0] * s, b[1] * s, b[2] * s, b[3] * s, b[4] * s, b[5] * s};
	}

	private static void collect(GeoBone bone, float[] out) {
		if (bone instanceof CuboidGeoBone cb) {
			for (GeoCube cube : cb.cubes) {
				for (GeoQuad quad : cube.quads()) {
					if (quad == null) continue;
					for (GeoVertex v : quad.vertices()) {
						out[0] = Math.min(out[0], v.posX());
						out[1] = Math.min(out[1], v.posY());
						out[2] = Math.min(out[2], v.posZ());
						out[3] = Math.max(out[3], v.posX());
						out[4] = Math.max(out[4], v.posY());
						out[5] = Math.max(out[5], v.posZ());
					}
				}
			}
		}
		for (GeoBone child : bone.children()) collect(child, out);
	}

	/**
	 * One creature drawing under {@code key}; {@code frame} counts GUI frames (the renderer hands out its textures per
	 * frame). {@code renderState} and {@code modelBounds} are null for a reuse: the key's last texture is blitted again.
	 */
	public record State(String key, @Nullable EntityRenderState renderState, float @Nullable [] modelBounds, float yaw, float pitch, int frame,
		Matrix3x2fc pose, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
		implements PictureInPictureRenderState {
		/** Whether this blits the last texture drawn under {@link #key} instead of rendering. */
		public boolean reuse() {
			return renderState == null;
		}

		@Override
		public float scale() {
			return 1;
		}
	}

	/** Added to {@link GuiGraphicsExtractor} by a mixin: submits a picture-in-picture state, reads the scissor. */
	public interface Sink {
		void aftrhrs$submitPip(PictureInPictureRenderState state);

		@Nullable ScreenRectangle aftrhrs$scissor();
	}
}
