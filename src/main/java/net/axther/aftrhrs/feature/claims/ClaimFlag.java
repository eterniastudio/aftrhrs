package net.axther.aftrhrs.feature.claims;

import java.util.Locale;

/** A per-claim switch the owner or a manager can flip in the Flags menu (or /claim flags). */
enum ClaimFlag {
    /** Players may hurt each other here. */
    PVP(false),
    /** Explosions may break blocks and hurt animals, item frames and armor stands here. */
    EXPLOSIONS(false),
    /** Endermen, ravagers, silverfish, withers, zombies (doors), foxes and rabbits may change blocks here. */
    MOB_GRIEFING(false),
    /** Hostile mobs may spawn naturally here (spawners, eggs and plugins always can). */
    MOB_SPAWNING(true),
    /** Fire may spread and burn blocks here. */
    FIRE_SPREAD(false),
    /** Visitors see "Entering Sunny's land" when they walk in (when enter messages are on in claims.yml). */
    ENTER_MESSAGE(true);

    final boolean def;

    ClaimFlag(boolean def) {
        this.def = def;
    }

    String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static ClaimFlag parse(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT).replace('_', '-');
        for (ClaimFlag f : values()) if (f.key().equals(k)) return f;
        return switch (k) {
            case "explosion", "tnt", "boom" -> EXPLOSIONS;
            case "griefing", "mobgriefing", "mob-grief" -> MOB_GRIEFING;
            case "spawning", "mobs", "mob-spawn", "mobspawning" -> MOB_SPAWNING;
            case "fire", "firespread" -> FIRE_SPREAD;
            default -> null;
        };
    }
}
