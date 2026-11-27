package net.axther.aftrhrs.core;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Answers claim questions for the rest of Feather; the claims module registers one with {@link Feather#claimsHook}. */
public interface ClaimsHook {
    /** Whether this online player may do this here (staff bypass included). */
    boolean allows(ServerPlayer player, Loc where, ClaimAction action);

    /** Whether this player, possibly offline (a pet working for its owner), may do this here. */
    boolean allows(UUID player, Loc where, ClaimAction action);

    /** Whether any claim covers this spot. */
    boolean claimed(Loc where);

    /** Whether one and the same claim covers both spots (false when either is unclaimed). */
    default boolean sameClaim(Loc a, Loc b) {
        return false;
    }
}
