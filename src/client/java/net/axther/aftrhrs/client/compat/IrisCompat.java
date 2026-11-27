package net.axther.aftrhrs.client.compat;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.axther.aftrhrs.client.Companion;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Iris (shader packs). Every custom render pipeline drawn in the world needs a shader-pack program, or Iris logs
 * "Missing program ... in override list" and draws it with the vanilla shader into its own buffers: with Iris's
 * extended entity vertex format that came out as streaks (GeckoLib's glow layer), and the pack's composite passes
 * painted over our claim walls and waypoint beams so they vanished. Entity pipelines are assigned through Iris's
 * public API ({@code IrisApi.v0}, which picks the best matching program); the walls and beams switch to vanilla's
 * lightning render type while a pack is in use ({@link #shaderPackInUse()}). Reflection keeps Iris optional: without
 * it, or with an Iris that lacks the API, this does nothing.
 */
public final class IrisCompat {
	private static boolean warned;
	private static MethodHandle inUse;
	private static boolean looked;

	private IrisCompat() {
	}

	/** {@code -Daftrhrs.irisCompat=false}: leave Iris alone (to compare against the unpatched look). */
	private static boolean enabled() {
		return FabricLoader.getInstance().isModLoaded("iris") && !"false".equals(System.getProperty("aftrhrs.irisCompat"));
	}

	/**
	 * Assigns {@code pipeline} to the shader-pack program {@code program} (an {@code IrisProgram} name such as
	 * {@code EMISSIVE_ENTITIES}) and, when {@code shadow} is not null, to the shadow program {@code shadow} (an
	 * {@code IrisShadowProgram} name). Call during client init, before the pipeline is first drawn.
	 */
	public static void assign(RenderPipeline pipeline, String program, String shadow) {
		if (!enabled()) return;
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object iris = api.getMethod("getInstance").invoke(null);
			Class<?> prog = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
			Method assign = api.getMethod("assignPipeline", RenderPipeline.class, prog);
			assign.invoke(iris, pipeline, enumValue(prog, program));
			if (shadow != null) {
				Class<?> sprog = Class.forName("net.irisshaders.iris.api.v0.IrisShadowProgram");
				api.getMethod("assignPipelineShadow", RenderPipeline.class, sprog).invoke(iris, pipeline, enumValue(sprog, shadow));
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			warn(e);
		}
	}

	/**
	 * Whether a shader pack is drawing the world right now (Iris installed and a pack on). Custom shaders don't survive
	 * a pack's passes, so world effects then use a render type Iris knows.
	 */
	public static boolean shaderPackInUse() {
		if (!looked) {
			looked = true;
			if (enabled()) {
				try {
					Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
					Object iris = api.getMethod("getInstance").invoke(null);
					inUse = MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(iris);
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					warn(e);
				}
			}
		}
		if (inUse == null) return false;
		try {
			return (boolean) inUse.invokeExact();
		} catch (Throwable e) {
			inUse = null;
			warn(e);
			return false;
		}
	}

	private static void warn(Throwable e) {
		if (!warned) Companion.LOG.warn("Iris is installed but its API is not usable; shader packs may draw Aftr Hrs effects wrong", e);
		warned = true;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object enumValue(Class<?> type, String name) {
		return Enum.valueOf((Class) type, name);
	}
}
