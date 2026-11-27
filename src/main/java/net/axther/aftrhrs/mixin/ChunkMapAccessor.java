package net.axther.aftrhrs.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The entity tracker's entries by entity id (to re-run tracking for one player after a visibility change). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("entityMap")
    Int2ObjectMap<?> aftrhrs$entityMap();
}
