package net.axther.aftrhrs.mixin.protect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/** {@link FeatherEvents#EXPLOSION}: the blocks an explosion will break (and light), before it touches any. */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
    @Shadow @Final private ServerLevel level;

    @WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerExplosion;calculateExplodedPositions()Ljava/util/List;"))
    private List<BlockPos> aftrhrs$filter(ServerExplosion self, Operation<List<BlockPos>> original) {
        List<BlockPos> blocks = original.call(self);
        if (blocks.isEmpty()) return blocks;
        List<BlockPos> list = new ArrayList<>(blocks);
        FeatherEvents.EXPLOSION.invoker().explode(level, self, list);
        return list.size() == blocks.size() ? blocks : new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(list);
    }
}
