package net.axther.aftrhrs.core.event;

import java.util.function.Supplier;

/**
 * Why a player is being moved (Bukkit's {@code TeleportCause}), passed to {@link FeatherEvents#PLAYER_TELEPORT}.
 * Feather's own moves are {@link #COMMAND} ({@code Feather.movePlayer}, warm-up teleports) or {@link #PLUGIN};
 * vanilla's are tagged by small mixins (ender pearls, portals, /tp). Anything else is {@link #UNKNOWN}.
 */
public enum TeleportCause {
    COMMAND, PLUGIN, ENDER_PEARL, NETHER_PORTAL, END_PORTAL, END_GATEWAY, CHORUS_FRUIT, SPECTATE, DISMOUNT, UNKNOWN;

    private static final ThreadLocal<TeleportCause> CURRENT = new ThreadLocal<>();

    /** The cause set by the code running now (see {@link #with}), or {@link #UNKNOWN}. */
    public static TeleportCause current() {
        TeleportCause c = CURRENT.get();
        return c == null ? UNKNOWN : c;
    }

    /** Runs {@code action} with this cause for every teleport it makes. */
    public <T> T with(Supplier<T> action) {
        TeleportCause before = CURRENT.get();
        CURRENT.set(this);
        try {
            return action.get();
        } finally {
            CURRENT.set(before);
        }
    }

    public void run(Runnable action) {
        with(() -> {
            action.run();
            return null;
        });
    }

    /** For mixins: set at HEAD, {@link #pop} at RETURN. Returns the previous value to restore. */
    public TeleportCause push() {
        TeleportCause before = CURRENT.get();
        CURRENT.set(this);
        return before;
    }

    public static void pop(TeleportCause before) {
        CURRENT.set(before);
    }
}
