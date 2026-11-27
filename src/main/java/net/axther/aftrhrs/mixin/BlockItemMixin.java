package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#BLOCK_PLACE} before a player places a block, {@link FeatherEvents#BLOCK_PLACED} after. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean aftrhrs$place(BlockItem self, BlockPlaceContext ctx, BlockState state, Operation<Boolean> original) {
        if (!(ctx.getPlayer() instanceof ServerPlayer player) || !(ctx.getLevel() instanceof ServerLevel level)) {
            return original.call(self, ctx, state);
        }
        if (!FeatherEvents.BLOCK_PLACE.invoker().allow(player, level, ctx.getClickedPos(), state, ctx.getItemInHand())) {
            player.containerMenu.sendAllDataToRemote();
            return false;
        }
        boolean placed = original.call(self, ctx, state);
        if (placed) {
            FeatherEvents.BLOCK_PLACED.invoker().placed(player, level, ctx.getClickedPos(), level.getBlockState(ctx.getClickedPos()), ctx.getItemInHand());
        }
        return placed;
    }
}
