package net.axther.aftrhrs.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code ChunkMap.TrackedEntity}: pair or unpair one player now (see {@link ChunkMapAccessor}). */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Invoker("updatePlayer")
    void aftrhrs$updatePlayer(ServerPlayer player);
}
