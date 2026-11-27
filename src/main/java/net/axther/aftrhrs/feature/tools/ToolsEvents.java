package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The tools module's Fabric listeners (registered once per JVM; each returns early while the module is off).
 */
final class ToolsEvents {
    private ToolsEvents() {
    }

    static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            ToolsModule m = ToolsModule.active;
            if (m == null || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return true;
            return m.scythes.beforeBreak(sp, sl, pos);
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            ToolsModule m = ToolsModule.active;
            if (m == null || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return;
            m.areaTools.onBroken(sp, sl, pos, state);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ToolsModule m = ToolsModule.active;
            if (m == null || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;
            if (sp.isSpectator()) return InteractionResult.PASS;
            if (m.scythes.useOn(sp, sl, hit.getBlockPos(), hand)) return InteractionResult.SUCCESS;
            if (m.wand.useOn(sp, sl, hit.getBlockPos(), hit.getDirection(), hand)) return InteractionResult.SUCCESS;
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ToolsModule m = ToolsModule.active;
            if (m == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (sp.isSpectator()) return InteractionResult.PASS;
            if (m.hats.use(sp, hand)) return InteractionResult.SUCCESS;
            if (m.magnet.use(sp, hand)) return InteractionResult.SUCCESS;
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            ToolsModule m = ToolsModule.active;
            if (m != null) m.drops.onDeath(entity, source);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            ToolsModule m = ToolsModule.active;
            if (m != null) m.drops.onDamaged(entity, source, taken);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ToolsModule m = ToolsModule.active;
            if (m == null) return;
            m.areaTools.forget(handler.player.getUUID());
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            ToolsModule m = ToolsModule.active;
            if (m != null && chunk instanceof LevelChunk lc) m.furniture.chunkLoaded(level, lc);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            ToolsModule m = ToolsModule.active;
            if (m != null && chunk instanceof LevelChunk lc) m.furniture.chunkUnloaded(level, lc);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ToolsModule m = ToolsModule.active;
            if (m != null) m.furniture.clearAging();
        });
        // the aging index: kept apart from the click hook below, so which hook claims a click does not change
        ArcanaContent.hook(id -> {
            ToolsModule m = ToolsModule.active;
            return m != null && m.furniture.ages(id);
        }, new FurnitureHook() {
            @Override
            public void onPlaced(FurnitureContext ctx) {
                ToolsModule m = ToolsModule.active;
                if (m != null) m.furniture.agingPlaced(ctx.level(), ctx.origin());
            }

            @Override
            public void onRemoved(FurnitureContext ctx) {
                ToolsModule m = ToolsModule.active;
                if (m != null) m.furniture.agingRemoved(ctx.level(), ctx.origin());
            }
        });
        ArcanaContent.hook(id -> {
            ToolsModule m = ToolsModule.active;
            return m != null && m.furniture.handles(id);
        }, new FurnitureHook() {
            @Override
            public InteractionResult onUse(FurnitureContext ctx) {
                ToolsModule m = ToolsModule.active;
                return m == null ? InteractionResult.PASS : m.furniture.onUse(ctx);
            }
        });
    }
}
