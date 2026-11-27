package net.axther.aftrhrs.feature.smithing;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Game time and the Shift key for code shared by both sides (tooltips show live heat; Shift shows a tool's numbers).
 * The server sets its time each tick; the client installs suppliers from its own level and keyboard.
 */
public final class SmithingClock {
    static volatile long serverTime;
    public static volatile LongSupplier client;
    public static volatile BooleanSupplier shift;

    private SmithingClock() {
    }

    public static long now() {
        LongSupplier c = client;
        return c != null ? c.getAsLong() : serverTime;
    }

    public static boolean shiftDown() {
        BooleanSupplier s = shift;
        return s != null && s.getAsBoolean();
    }
}
