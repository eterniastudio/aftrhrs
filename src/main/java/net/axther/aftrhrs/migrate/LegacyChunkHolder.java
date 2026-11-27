package net.axther.aftrhrs.migrate;

import net.minecraft.nbt.CompoundTag;

/** Implemented on SerializableChunkData by a mixin: carries ChunkBukkitValues from parse() to read(). */
public interface LegacyChunkHolder {
    void aftrhrs$setBukkit(CompoundTag tag);
}
