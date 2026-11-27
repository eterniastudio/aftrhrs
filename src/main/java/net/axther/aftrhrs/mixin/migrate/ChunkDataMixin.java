package net.axther.aftrhrs.mixin.migrate;

import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.migrate.LegacyChunkHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper's chunk PDC (ChunkBukkitValues: Feather storage networks and similar) → the chunk's Pdc attachment. */
@Mixin(SerializableChunkData.class)
public abstract class ChunkDataMixin implements LegacyChunkHolder {
    @Unique
    private CompoundTag aftrhrs$bukkit;

    @Override
    public void aftrhrs$setBukkit(CompoundTag tag) {
        aftrhrs$bukkit = tag;
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static void aftrhrs$parse(LevelHeightAccessor h, PalettedContainerFactory f, CompoundTag chunkData, CallbackInfoReturnable<SerializableChunkData> cir) {
        SerializableChunkData d = cir.getReturnValue();
        if (d == null) return;
        chunkData.getCompound("ChunkBukkitValues").ifPresent(t -> {
            if (!t.isEmpty()) ((LegacyChunkHolder) (Object) d).aftrhrs$setBukkit(t);
        });
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void aftrhrs$read(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CallbackInfoReturnable<ProtoChunk> cir) {
        if (aftrhrs$bukkit == null || cir.getReturnValue() == null) return;
        ProtoChunk chunk = cir.getReturnValue();
        CompoundTag data = chunk.getAttachedOrCreate(Pdc.DATA).copy();
        for (String k : aftrhrs$bukkit.keySet()) if (!data.contains(k)) data.put(k, aftrhrs$bukkit.get(k).copy());
        chunk.setAttached(Pdc.DATA, data);
        net.axther.aftrhrs.migrate.Legacy.count("chunk Paper data", String.join(",", new java.util.TreeSet<>(aftrhrs$bukkit.keySet())));
        aftrhrs$bukkit = null;
    }
}
