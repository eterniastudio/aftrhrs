package net.axther.aftrhrs.feature.tools;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

/** The area tools' helpers other features reuse (smithing's sledge, excavator and Broadstrike). */
public final class AreaToolsAccess {
    private AreaToolsAccess() {
    }

    /** The face of {@code block} the player mined (works after the break). */
    public static Direction minedFace(ServerPlayer player, BlockPos block) {
        return AreaTools.minedFace(player, block);
    }
}
