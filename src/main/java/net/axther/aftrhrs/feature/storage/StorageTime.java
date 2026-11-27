package net.axther.aftrhrs.feature.storage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How the storage "who changed what" times read (shared by the chest window, which uses the server's time zone, and
 * the Aftr Hrs terminal screen, which uses the player's own). The server only ever sends epoch milliseconds.
 */
public final class StorageTime {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a z", Locale.US);

    private StorageTime() {
    }

    /** "Nov 21, 3:04 AM EST": a moment in the given time zone, with the zone's short name. */
    public static String when(long epochMs, ZoneId zone) {
        return WHEN.format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    /** "just now", "5m ago", "2h ago", "3d ago". */
    public static String ago(long epochMs, long now) {
        long s = Math.max(0, (now - epochMs) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86_400) return (s / 3600) + "h ago";
        return (s / 86_400) + "d ago";
    }

    /** "+64" or "−3" (a real minus sign). */
    public static String signed(int delta) {
        return delta >= 0 ? "+" + delta : "−" + (-delta);
    }
}
