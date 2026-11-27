package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.LookAt;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Set;

/** Tags vanilla /tp and /teleport with {@link TeleportCause#COMMAND}. */
@Mixin(TeleportCommand.class)
abstract class TeleportCommandCauseMixin {
    @WrapMethod(method = "performTeleport")
    private static void aftrhrs$commandCause(CommandSourceStack source, Entity victim, ServerLevel level, double x, double y, double z,
                                             Set<Relative> relatives, float yRot, float xRot, LookAt lookAt, Operation<Void> original) {
        TeleportCause.COMMAND.run(() -> original.call(source, victim, level, x, y, z, relatives, yRot, xRot, lookAt));
    }
}
