package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@link FeatherEvents#RESPAWN_POSITION}: modules may move where a respawning player appears. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @WrapOperation(method = "respawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;findRespawnPositionAndUseSpawnBlock(ZLnet/minecraft/world/level/portal/TeleportTransition$PostTeleportTransition;)Lnet/minecraft/world/level/portal/TeleportTransition;"))
    private TeleportTransition aftrhrs$respawnAt(ServerPlayer player, boolean consume, TeleportTransition.PostTeleportTransition post,
                                                 Operation<TeleportTransition> original) {
        TeleportTransition vanilla = original.call(player, consume, post);
        boolean bed = player.getRespawnConfig() != null && !vanilla.missingRespawnBlock();
        TeleportTransition t = FeatherEvents.RESPAWN_POSITION.invoker().modify(player, vanilla, bed);
        return t == null ? vanilla : t;
    }
}
