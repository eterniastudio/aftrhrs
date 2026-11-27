package net.axther.aftrhrs.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * One interaction with a placed content block.
 *
 * @param id      the Oraxen-style id ({@code arcana_oak_chair}), without namespace
 * @param level   the level
 * @param origin  the piece's origin block (part 0); for single-block pieces the clicked block
 * @param state   the origin's block state
 * @param clicked the block that was actually clicked (any part)
 * @param player  the player, null for non-player events
 * @param hand    the hand used (MAIN_HAND for break/place)
 * @param held    the stack in that hand (EMPTY for none)
 * @param hit     the hit result of a click, null otherwise
 */
public record FurnitureContext(String id, ServerLevel level, BlockPos origin, BlockState state, BlockPos clicked,
                               @Nullable ServerPlayer player, InteractionHand hand, ItemStack held,
                               @Nullable BlockHitResult hit) {

    /** The piece's front direction (its model front faces the placer). */
    public Direction facing() {
        return FurnitureBlock.front(state);
    }

    /** The piece's yaw in degrees clockwise from north (0, 22.5, ... for finely rotated pieces). */
    public float yaw() {
        return FurnitureBlock.yawDegrees(state);
    }

    public boolean sneaking() {
        return player != null && player.isSecondaryUseActive();
    }
}
