package net.axther.aftrhrs.core;

/**
 * What someone wants to do at a spot, in the order of the claim trust levels: each level allows everything before
 * it. Other modules ask {@link Feather#claimAllows} before acting for a player (pets working, storage networks, graves).
 */
public enum ClaimAction {
    /** Doors, gates, trapdoors, buttons, levers, pressure plates. */
    ACCESS,
    /** Chests, barrels, furnaces, hoppers and other containers (plus ACCESS). */
    CONTAINER,
    /** Placing and breaking blocks, buckets, item frames, animals (plus CONTAINER). */
    BUILD,
    /** Changing the claim itself: trust, flags, resizing (plus BUILD). */
    MANAGE
}
