package net.axther.aftrhrs.feature.smithing;

import net.minecraft.core.BlockPos;

/**
 * The anvil's strike meter, shared by server (which judges every strike) and client (which only draws it): a marker
 * sweeping 0..1..0 over {@link #PERIOD} ticks from the meter's start, and a sweet spot for the current move whose
 * center is fixed per anvil and move, and whose width narrows with the metal's tier and as the piece cools toward the
 * bottom of its working window. A heavy strike (sneak) counts for two moves but its spot is narrower.
 */
public final class Strike {
    public static final int PERIOD = 30;
    public static final float PERFECT = 0.35f;

    private Strike() {
    }

    /** Marker position 0..1 at {@code tick}. */
    public static float marker(long tick, long start) {
        long t = Math.floorMod(tick - start, 2L * PERIOD);
        return t < PERIOD ? t / (float) PERIOD : 2f - t / (float) PERIOD;
    }

    /** Sweet-spot center (0.2..0.8) for move {@code move} on the anvil at {@code pos}. */
    public static float center(BlockPos pos, int move, long seed) {
        long h = pos.asLong() * 0x9E3779B97F4A7C15L + move * 0xC2B2AE3D27D4EB4FL + seed;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return 0.2f + (h & 0xFFFF) / 65535f * 0.6f;
    }

    /**
     * Half width of the sweet spot. {@code tier} 1-4 of the metal; {@code heatFrac} where the piece's heat sits in its
     * window (0 = bottom, 1 = top); {@code bonus} from the hammer (its head material and quality).
     */
    public static float halfWidth(int tier, float heatFrac, boolean heavy, float bonus) {
        float base = switch (tier) {
            case 1 -> 0.15f;
            case 2 -> 0.12f;
            case 3 -> 0.10f;
            default -> 0.08f;
        };
        float heat = 0.6f + 0.4f * Math.max(0, Math.min(1, heatFrac));
        return (base * heat + bonus) * (heavy ? 0.6f : 1f);
    }

    /** Score of a strike at marker {@code m}: 1 perfect, 0.6 good, 0 a miss. */
    public static float score(float m, float center, float half) {
        float d = Math.abs(m - center);
        if (d <= half * PERFECT) return 1f;
        if (d <= half) return 0.6f;
        return 0f;
    }

    /** Stars from the average strike score (all perfect 5, all good 3). */
    public static int stars(float avg) {
        return Math.max(1, Math.min(5, Math.round(1 + 4 * avg)));
    }

    /** The hammer's forgiveness: +0.01 per tier of its head, +0.005 per star of quality above 3. */
    public static float hammerBonus(ToolBuild hammer) {
        if (hammer == null || hammer.parts().isEmpty()) return 0;
        SmithingDefs.Material m = SmithingDefs.material(hammer.parts().get(0).material());
        float b = m == null ? 0 : 0.01f * m.tier();
        return b + 0.005f * (hammer.quality() - 3);
    }
}
