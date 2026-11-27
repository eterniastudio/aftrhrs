package net.axther.aftrhrs.feature.smithing;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** The Smith's Almanac: the guide to every station, material, tool, part, modifier and alloy (a client screen). */
public final class AlmanacItem extends Item {
    /** Opens the Almanac screen (installed by the client). */
    public static volatile Runnable opener;

    AlmanacItem(Properties p) {
        super(p);
    }

    @Override
    public InteractionResult use(Level level, Player p, InteractionHand hand) {
        if (level.isClientSide()) {
            Runnable r = opener;
            if (r != null) r.run();
        }
        return InteractionResult.SUCCESS;
    }
}
