package net.axther.aftrhrs.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla adds a 1-tick UNKNOWN ticket on every main-thread getChunk cache miss, even for a chunk that is already
 * loaded at that status, and {@code TicketStorage.purgeStaleTickets} removes it again next tick (about 4.5% of the
 * soak's server thread, plus the ChunkTracker update it triggers). Skip it only when the holder is already at the
 * level AND the requested status future has completed: getChunk then returns without managedBlock, pollTask or
 * runDistanceManagerUpdates, so the ticket could not have protected anything. If the future is still pending the
 * vanilla ticket stays; without it, a pending level rise during managedBlock would fail the future and
 * getChunk(create=true) would throw. {@code require = 0}: chunk-engine mods (Moonrise, C2ME) replace this method,
 * which makes this a no-op instead of a startup crash.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheTicketMixin {
    @WrapWithCondition(method = "getChunkFutureMainThread",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V"),
            require = 0)
    private boolean aftrhrs$skipRedundantUnknownTicket(ServerChunkCache self, Ticket ticket, ChunkPos pos,
                                                       @Local ChunkHolder holder, @Local(argsOnly = true) ChunkStatus status) {
        return holder == null
                || holder.getTicketLevel() > ticket.getTicketLevel()
                || holder.getChunkIfPresent(status) == null; // true = run vanilla addTicket
    }
}
