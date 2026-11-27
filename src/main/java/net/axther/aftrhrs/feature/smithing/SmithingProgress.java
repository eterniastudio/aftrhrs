package net.axther.aftrhrs.feature.smithing;

import net.axther.aftrhrs.core.Feather;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * What a smith did, for advancements and quests ({@link Feather#advance} "event" targets {@code smith_*}): first cast,
 * forged and carved parts (and 5-star ones), every alloy, assembling (a Masterwork), etching (Trophy-bound).
 */
public final class SmithingProgress {
    private SmithingProgress() {
    }

    private static void event(ServerPlayer p, String what) {
        Feather f = Feather.get();
        if (f != null) f.advance(p, "event", what);
    }

    public static void cast(ServerPlayer p) {
        event(p, "smith_cast");
    }

    public static void forged(ServerPlayer p, int stars) {
        event(p, "smith_forge");
        if (stars >= 5) event(p, "smith_forge_5");
    }

    public static void carved(ServerPlayer p, int stars) {
        event(p, "smith_carve");
        if (stars >= 5) event(p, "smith_carve_5");
    }

    public static void alloyed(ServerPlayer p, String alloy) {
        event(p, "smith_alloy");
        event(p, "smith_alloy_" + alloy);
    }

    public static void assembled(ServerPlayer p, ItemStack tool) {
        event(p, "smith_assemble");
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null) return;
        event(p, "smith_assemble_" + b.tool());
        if (b.masterwork()) event(p, "smith_masterwork");
        SmithingDefs.Material head = SmithingDefs.material(b.parts().isEmpty() ? null : b.parts().get(0).material());
        if (head != null && head.tier() >= 4) event(p, "smith_tier4");
    }

    public static void etched(ServerPlayer p, String mod, ItemStack tool) {
        event(p, "smith_etch");
        if (mod.equals("trophy_bound")) event(p, "smith_trophy_bound");
    }
}
