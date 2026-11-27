package net.axther.aftrhrs.content;

import net.minecraft.world.InteractionResult;

/**
 * Lets a module (curios, gadgets, civic, storage, power, farming, tools...) claim interactions with specific
 * furniture or content blocks. Register with {@link ArcanaContent#hook(String, FurnitureHook)} (one id),
 * {@link ArcanaContent#hook(java.util.function.Predicate, FurnitureHook)} (an id filter). Hooks run on the server
 * only, before the block's own behavior (rotate, open storage, sit, toggle).
 */
public interface FurnitureHook {
    /**
     * A right-click (any hand, any held item, sneaking or not) on any part of the piece. Return
     * {@link InteractionResult#PASS} to let the next hook / the block's own behavior run; anything else claims the
     * click (e.g. {@code InteractionResult.SUCCESS}).
     */
    default InteractionResult onUse(FurnitureContext ctx) {
        return InteractionResult.PASS;
    }

    /** A player is about to break the piece (any part). Return false to cancel the break. */
    default boolean onBreak(FurnitureContext ctx) {
        return true;
    }

    /** The piece was placed (all parts exist). {@code ctx.player()} may be null. */
    default void onPlaced(FurnitureContext ctx) {
    }

    /** The piece was removed (by a player, an explosion, a command...). The block is already gone. */
    default void onRemoved(FurnitureContext ctx) {
    }
}
