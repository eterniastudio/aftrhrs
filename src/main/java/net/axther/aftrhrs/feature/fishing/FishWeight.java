package net.axther.aftrhrs.feature.fishing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * Fish weights as players see them, and how big a fish of a weight is drawn. Weights are stored in grams everywhere
 * (item {@code feather:fish_weight}, journals, records, tournaments: the format the Paper plugin wrote), only shown in
 * pounds: "3.2 lb", two decimals under 1 lb ("0.45 lb"), two significant digits under 0.01 lb ("0.0022 lb") and
 * thousands separators for the big ones ("26,455.5 lb"). The client's Angler's Journal formats with the same method.
 *
 * <p>Size: the length of a fish goes with the cube root of its weight, so {@link #lengthScale} is
 * {@code (grams / 1500 g)^(1/3)}, eased (tanh in log space, slope 1 around a 1.5 kg fish) into {@code [0.4, 2.2]} so a
 * 1 g guppy is still visible and a whale shark doesn't swallow the screen. It is strictly increasing in the weight. The
 * held/dropped item gets it through its custom model data (float 0, read by the item model's range dispatch written by
 * {@code tools/gen_content.py} with the same constants); plaques and tanks scale their fish display with it, inside
 * their own limits (fishing.yml {@code plaque.size-range}, {@code tank.size-range}).
 */
public final class FishWeight {
    public static final double GRAMS_PER_POUND = 453.59237;
    /** The weight drawn at the model's own size (scale 1). */
    public static final double SIZE_REF_GRAMS = 1500;
    public static final double SIZE_MIN = 0.4, SIZE_MAX = 2.2;
    /** Size steps of the item model's range dispatch (each step 8% longer); gen_content.py uses the same. */
    public static final double SIZE_STEP = 1.08;

    private FishWeight() {
    }

    public static double pounds(long grams) {
        return grams / GRAMS_PER_POUND;
    }

    /** "3.2 lb", "0.45 lb", "0.0022 lb", "2,425.1 lb"; "0 lb" for no weight. */
    public static String format(long grams) {
        if (grams <= 0) return "0 lb";
        double lb = pounds(grams);
        if (lb >= 0.995) return String.format(Locale.ROOT, "%,.1f lb", lb);
        if (lb >= 0.0095) return String.format(Locale.ROOT, "%.2f lb", lb);
        return new BigDecimal(lb).round(new MathContext(2)).stripTrailingZeros().toPlainString() + " lb";
    }

    /** How many times the model's own size a fish of this weight is drawn: in [SIZE_MIN, SIZE_MAX], increasing. */
    public static double lengthScale(double grams) {
        double g = Math.max(0.01, grams);
        double x = Math.log(g / SIZE_REF_GRAMS) / 3.0;          // log of the length ratio
        double hi = Math.log(SIZE_MAX), lo = Math.log(SIZE_MIN);
        double y = x >= 0 ? hi * Math.tanh(x / hi) : lo * Math.tanh(x / lo);
        return Math.exp(y);
    }

    /** The scale rounded the way it is written into the item (custom model data float 0). */
    public static float itemScale(double grams) {
        return Math.round(lengthScale(grams) * 1000) / 1000f;
    }

    public enum SizeClass {
        JUVENILE, ADULT, TROPHY;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Juvenile under 60% of the typical weight; trophy from 60% of the way (in log terms) from typical to max. */
    public static SizeClass sizeClass(int grams, int min, int typical, int max) {
        if (grams <= 0 || typical <= 0) return SizeClass.ADULT;
        if (grams < typical * 0.6 && grams < max) return SizeClass.JUVENILE;
        if (max > typical && grams >= trophyGrams(typical, max)) return SizeClass.TROPHY;
        return SizeClass.ADULT;
    }

    public static double trophyGrams(int typical, int max) {
        return typical * Math.pow((double) max / typical, 0.6);
    }
}
