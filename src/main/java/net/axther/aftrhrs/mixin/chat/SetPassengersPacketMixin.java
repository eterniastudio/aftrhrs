package net.axther.aftrhrs.mixin.chat;

import net.axther.aftrhrs.feature.display.NametagModule;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Player nametags are client-only text displays riding the player ({@link NametagModule}): every passenger list sent
 * for a player keeps its nametag on, so vanilla's own passenger updates never knock it off.
 */
@Mixin(ClientboundSetPassengersPacket.class)
abstract class SetPassengersPacketMixin {
    @Shadow
    @Final
    @Mutable
    private int[] passengers;

    @Inject(method = "<init>(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void aftrhrs$nametag(Entity vehicle, CallbackInfo ci) {
        int tag = NametagModule.passengerFor(vehicle);
        if (tag < 0) return;
        for (int id : passengers) if (id == tag) return;
        int[] out = Arrays.copyOf(passengers, passengers.length + 1);
        out[passengers.length] = tag;
        passengers = out;
    }
}
