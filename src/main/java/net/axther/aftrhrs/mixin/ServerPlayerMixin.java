package net.axther.aftrhrs.mixin;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.event.TeleportCause;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.util.Prediction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/** Teleport, death, drop and menu-open events for players (see {@link FeatherEvents}). */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Unique
    private Loc aftrhrs$teleportFrom;
    @Unique
    private TeleportCause aftrhrs$teleportCause;

    @Unique
    private ServerPlayer aftrhrs$self() {
        return (ServerPlayer) (Object) this;
    }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At("HEAD"), cancellable = true)
    private void aftrhrs$beforeTeleport(TeleportTransition t, CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer self = aftrhrs$self();
        if (self.isRemoved() || self.connection == null) return;
        PositionMoveRotation dest = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(self), PositionMoveRotation.of(t), t.relatives());
        Loc from = Loc.of(self);
        Loc to = new Loc(t.newLevel(), dest.position().x, dest.position().y, dest.position().z, dest.yRot(), dest.xRot());
        TeleportCause cause = TeleportCause.current();
        if (!FeatherEvents.PLAYER_TELEPORT.invoker().allow(self, from, to, cause)) {
            cir.setReturnValue(null);
            return;
        }
        aftrhrs$teleportFrom = from;
        aftrhrs$teleportCause = cause;
    }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At("RETURN"))
    private void aftrhrs$afterTeleport(TeleportTransition t, CallbackInfoReturnable<ServerPlayer> cir) {
        Loc from = aftrhrs$teleportFrom;
        TeleportCause cause = aftrhrs$teleportCause;
        aftrhrs$teleportFrom = null;
        aftrhrs$teleportCause = null;
        if (from == null || cir.getReturnValue() == null) return;
        ServerPlayer moved = cir.getReturnValue();
        FeatherEvents.PLAYER_TELEPORTED.invoker().after(moved, from, Loc.of(moved), cause);
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$teleportTo(double x, double y, double z, CallbackInfo ci) {
        ServerPlayer self = aftrhrs$self();
        if (self.connection == null) return;
        Loc from = Loc.of(self);
        Loc to = new Loc((ServerLevel) self.level(), x, y, z, self.getYRot(), self.getXRot());
        TeleportCause cause = TeleportCause.current();
        if (!FeatherEvents.PLAYER_TELEPORT.invoker().allow(self, from, to, cause)) {
            ci.cancel();
            return;
        }
        aftrhrs$teleportFrom = from;
        aftrhrs$teleportCause = cause;
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("RETURN"))
    private void aftrhrs$teleportedTo(double x, double y, double z, CallbackInfo ci) {
        Loc from = aftrhrs$teleportFrom;
        TeleportCause cause = aftrhrs$teleportCause;
        aftrhrs$teleportFrom = null;
        aftrhrs$teleportCause = null;
        if (from != null) FeatherEvents.PLAYER_TELEPORTED.invoker().after(aftrhrs$self(), from, Loc.of(aftrhrs$self()), cause);
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void aftrhrs$die(DamageSource source, CallbackInfo ci) {
        FeatherEvents.PLAYER_DEATH.invoker().onDeath(aftrhrs$self(), source);
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void aftrhrs$drop(ItemStack stack, boolean thrownFromHand, Prediction prediction, CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayer self = aftrhrs$self();
        if (!thrownFromHand || stack.isEmpty() || self.isDeadOrDying() || self.hasDisconnected()) return;
        if (!FeatherEvents.ITEM_DROP.invoker().allow(self, stack)) {
            self.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
            self.containerMenu.sendAllDataToRemote();
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
    private void aftrhrs$openMenu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (provider != null && !FeatherEvents.CONTAINER_OPEN.invoker().allow(aftrhrs$self(), provider)) cir.setReturnValue(OptionalInt.empty());
    }
}
