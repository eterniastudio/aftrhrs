package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Tags pearl teleports with {@link TeleportCause#ENDER_PEARL}. */
@Mixin(ThrownEnderpearl.class)
abstract class ThrownEnderpearlCauseMixin {
    @WrapMethod(method = "onHit")
    private void aftrhrs$pearlCause(HitResult hit, Operation<Void> original) {
        TeleportCause prev = TeleportCause.ENDER_PEARL.push();
        try {
            original.call(hit);
        } finally {
            TeleportCause.pop(prev);
        }
    }
}
