package net.axther.aftrhrs.feature.fairylights;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

/** A pennant for a Pennant Bunting ({@code aftrhrs:fairy_pennant_<shape>}), dyeable. */
public final class PennantItem extends Item {
    public final StrandSpec.Pennant shape;

    public PennantItem(StrandSpec.Pennant shape, Properties props) {
        super(props);
        this.shape = shape;
    }

    public static StrandSpec.Flag flag(ItemStack s, PennantItem item) {
        DyedItemColor dye = s.get(DataComponents.DYED_COLOR);
        return new StrandSpec.Flag(item.shape, dye == null ? -1 : dye.rgb() & 0xFFFFFF);
    }

    public static ItemStack stack(StrandSpec.Flag f) {
        ItemStack s = new ItemStack(FairyLights.pennantItem(f.shape()));
        if (f.color() >= 0) s.set(DataComponents.DYED_COLOR, new DyedItemColor(f.color()));
        return s;
    }
}
