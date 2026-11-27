package net.axther.aftrhrs.core.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link FeatherEvents#ENTITY_CHANGE_BLOCK} for the {@code mixin/protect/Grief*} mixins (endermen, door breakers, foxes,
 * rabbits, silverfish, falling blocks, fireballs, lightning). Lives outside the mixin package on purpose: Mixin refuses
 * to load a plain class from a mixin package at runtime, which crashed the server the first time sand or gravel landed.
 */
public final class EntityChangeBlocks {
    private EntityChangeBlocks() {
    }

    public static boolean allow(Entity e, Level level, BlockPos pos, BlockState state) {
        return !(level instanceof ServerLevel sl) || FeatherEvents.ENTITY_CHANGE_BLOCK.invoker().allow(e, sl, pos.immutable(), state);
    }
}
