package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** A piston that would push, pull or break a protected block (or push something into it) doesn't move. */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private List<BlockPos> toPush;
    @Shadow @Final private List<BlockPos> toDestroy;
    @Shadow @Final private Direction pushDirection;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void aftrhrs$protected(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        for (BlockPos p : toPush) {
            if (FeatherEvents.isProtected(level, p) || FeatherEvents.isProtected(level, p.relative(pushDirection))) {
                cir.setReturnValue(false);
                return;
            }
        }
        for (BlockPos p : toDestroy) {
            if (FeatherEvents.isProtected(level, p)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
