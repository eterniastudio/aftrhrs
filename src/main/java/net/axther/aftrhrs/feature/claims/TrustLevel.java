package net.axther.aftrhrs.feature.claims;

import net.axther.aftrhrs.core.ClaimAction;

import java.util.Locale;

/** How far a player is trusted in a claim. Each level includes the ones before it. */
enum TrustLevel {
    ACCESS(ClaimAction.ACCESS),
    CONTAINER(ClaimAction.CONTAINER),
    BUILD(ClaimAction.BUILD),
    MANAGER(ClaimAction.MANAGE);

    final ClaimAction action;

    TrustLevel(ClaimAction action) {
        this.action = action;
    }

    boolean allows(ClaimAction wanted) {
        return action.ordinal() >= wanted.ordinal();
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static TrustLevel parse(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "access", "use", "a" -> ACCESS;
            case "container", "containers", "chest", "chests", "c", "inventory" -> CONTAINER;
            case "build", "b", "builder" -> BUILD;
            case "manager", "manage", "m", "admin" -> MANAGER;
            default -> null;
        };
    }
}
