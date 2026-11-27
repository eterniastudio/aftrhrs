package net.axther.aftrhrs.client.perf;

import com.geckolib.loading.math.MathValue;
import com.geckolib.loading.math.baked.BakedImmutableMathValue;
import com.geckolib.loading.math.baked.BakedMathValue;
import com.geckolib.loading.math.value.Constant;
import java.util.concurrent.ConcurrentHashMap;
import net.axther.aftrhrs.AftrHrs;
import org.jspecify.annotations.Nullable;

/**
 * Shares GeckoLib's baked keyframe constants (written for GeckoLib 5.5.7). Every keyframe channel value is baked into its
 * own {@link BakedImmutableMathValue} (and {@link Constant}), and every keyframe triplet gets its own empty easing
 * argument array: about 2.3 million value objects and 1.2 million arrays at the title screen for only ~99k distinct
 * numbers (~43 MB of heap). {@code GeckoKeyframeInternMixin} hands the keyframe triplet the canonical baked value for
 * each number instead, so {@link BakedMathValue#create} returns it unchanged; mutable (Molang) values pass through.
 * The table is cleared after each resource reload ({@code GeckoLibResourcesMixin}), so a reload never keeps old values.
 *
 * <p>Upstream idea for GeckoLib: deduplicate immutable values in {@code BakedMathValue.create} (MathParser already has
 * a Deduplicator) and share one empty {@code MathValue[]} in {@code ActorBoneAnimationKeyframe.bakeTriplet}.
 */
public final class GeckoValueIntern {
	/** Boxed key on purpose: Double equality keeps -0.0 and 0.0 apart and folds NaN, like Constant's record equality. */
	static final ConcurrentHashMap<Double, BakedImmutableMathValue> VALUES = new ConcurrentHashMap<>();
	static final MathValue[] EMPTY = new MathValue[0];
	/** {@code -Daftrhrs.geckoIntern=false} turns interning off (GeckoLib bakes as without the mod; heap comparisons). */
	static final boolean ON = !"false".equals(System.getProperty("aftrhrs.geckoIntern"));
	private static volatile boolean hit, logged;

	private GeckoValueIntern() {
	}

	/** The canonical baked value of an immutable value (what {@link BakedMathValue#create} would bake), else {@code v}. */
	public static @Nullable MathValue intern(@Nullable MathValue v) {
		if (!ON || v == null || v instanceof BakedMathValue || v.isMutable()) return v;
		double d = v.get(null);
		if (!hit) hit = true;
		return VALUES.computeIfAbsent(d, k -> new BakedImmutableMathValue(v instanceof Constant c ? c : new Constant(k)));
	}

	/** One shared empty easing argument array (never written: Keyframe only reads its arguments). */
	public static MathValue[] internArgs(MathValue[] a) {
		return ON && a.length == 0 ? EMPTY : a;
	}

	/** After a reload: the baked animations hold their values, the table starts again for the next reload. */
	public static void clear() {
		if (hit && !logged) {
			logged = true;
			AftrHrs.LOG.info("GeckoLib keyframe values interned: {} distinct numbers", VALUES.size());
		}
		VALUES.clear();
	}
}
