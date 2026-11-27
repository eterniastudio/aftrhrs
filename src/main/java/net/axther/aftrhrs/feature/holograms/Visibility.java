package net.axther.aftrhrs.feature.holograms;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.mixin.ChunkMapAccessor;
import net.axther.aftrhrs.mixin.TrackedEntityAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Paper's {@code setVisibleByDefault(false)} + {@code showEntity}/{@code hideEntity} for hologram entities: an entity
 * registered with {@link #hideByDefault} is sent only to the players it was {@link #show shown} to. The entity tracker
 * asks {@link FeatherEvents#ENTITY_VISIBLE}; a change re-runs the tracker for that one player at once.
 */
final class Visibility {
    /** Entity id → who may see it, for hidden-by-default entities only. */
    private static final Int2ObjectMap<Set<UUID>> SHOWN = new Int2ObjectOpenHashMap<>();
    private static boolean hooked;

    private Visibility() {
    }

    static void hook() {
        if (hooked) return;
        hooked = true;
        FeatherEvents.ENTITY_VISIBLE.register(Visibility::allows);
        ServerEntityEvents.ENTITY_UNLOAD.register((e, level) -> SHOWN.remove(e.getId()));
    }

    /** Call before the entity is added to the level, so nobody is paired with it first. */
    static void hideByDefault(Entity e) {
        SHOWN.putIfAbsent(e.getId(), new HashSet<>());
    }

    static void show(ServerPlayer p, Entity e) {
        if (p == null || e == null) return;
        Set<UUID> s = SHOWN.get(e.getId());
        if (s != null && s.add(p.getUUID())) refresh(e, p);
    }

    static void hide(ServerPlayer p, Entity e) {
        if (p == null || e == null) return;
        Set<UUID> s = SHOWN.get(e.getId());
        if (s != null && s.remove(p.getUUID())) refresh(e, p);
    }

    static void forget(Entity e) {
        if (e != null) SHOWN.remove(e.getId());
    }

    static void clear() {
        SHOWN.clear();
    }

    private static boolean allows(Entity e, ServerPlayer viewer) {
        if (SHOWN.isEmpty()) return true;
        Set<UUID> s = SHOWN.get(e.getId());
        return s == null || s.contains(viewer.getUUID());
    }

    /** Pairs or unpairs the player with the entity now (the tracker would only notice on the next section move). */
    private static void refresh(Entity e, ServerPlayer p) {
        if (e.isRemoved() || !(e.level() instanceof ServerLevel level) || p.level() != level) return;
        Object tracked = ((ChunkMapAccessor) level.getChunkSource().chunkMap).aftrhrs$entityMap().get(e.getId());
        if (tracked != null) ((TrackedEntityAccessor) tracked).aftrhrs$updatePlayer(p);
    }
}
