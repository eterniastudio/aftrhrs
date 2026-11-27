package net.axther.aftrhrs.feature.fairylights;

import net.axther.aftrhrs.core.Yaml;

/** Server limits for fairy lights ({@code fairy-lights.yml}; these defaults hold until the module reads the file). */
public final class FairyLightsConfig {
    /** How far past the max length a held strand stretches before it snaps. */
    public static final float PULL_RANGE = 5;

    /** Longest strand, in blocks between its ends. */
    public static volatile float maxLength = 24;
    /** Strands at one hook / knot (both ends count). */
    public static volatile int maxPerFastener = 8;
    /** Strands starting in one chunk. */
    public static volatile int maxPerChunk = 48;
    /** Real light: light blocks per lit String of Lights (0 = none). */
    public static volatile int maxLightBlocksPerStrand = 6;
    public static volatile boolean realLight = true;

    private FairyLightsConfig() {
    }

    static void load(Yaml c) {
        maxLength = (float) Math.clamp(c.getDouble("max-length", 24), 4, 48);
        maxPerFastener = Math.clamp(c.getInt("max-per-fastener", 8), 1, 32);
        maxPerChunk = Math.clamp(c.getInt("max-per-chunk", 48), 1, 512);
        maxLightBlocksPerStrand = Math.clamp(c.getInt("light-blocks-per-strand", 6), 0, 32);
        realLight = c.getBoolean("real-light", true) && maxLightBlocksPerStrand > 0;
    }
}
