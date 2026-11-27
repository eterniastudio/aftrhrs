package net.axther.aftrhrs.mixin.smithing;

import net.axther.aftrhrs.feature.smithing.Effects;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Smeltforged and Ember smelt a smithed tool's drops; Warping sends them straight into the pack. */
@Mixin(Block.class)
public abstract class BlockDropsSmithMixin {
    @Inject(method = "getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemInstance;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private static void aftrhrs$smelt(BlockState state, ServerLevel level, BlockPos pos, @Nullable BlockEntity be, @Nullable Entity entity,
                                      ItemInstance tool, CallbackInfoReturnable<List<ItemStack>> cir) {
        if (Smithing.BUILD != null && tool instanceof ItemStack s && s.has(Smithing.BUILD)) cir.setReturnValue(Effects.drops(cir.getReturnValue(), level, s));
    }

    @Inject(method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true)
    private static void aftrhrs$warp(BlockState state, Level level, BlockPos pos, @Nullable BlockEntity be, @Nullable Entity entity, ItemStack tool,
                                     CallbackInfo ci) {
        if (Smithing.BUILD != null && level instanceof ServerLevel sl && tool.has(Smithing.BUILD) && Effects.warp(state, sl, pos, be, entity, tool)) ci.cancel();
    }
}
