package net.axther.aftrhrs.feature.smithing;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** An ingot or nugget of one of our alloys ({@code aftrhrs:ingot_steel}, {@code aftrhrs:nugget_rose_gold}...). */
public final class MetalItem extends Item {
    public final String metal;
    public final boolean nugget;

    MetalItem(String metal, boolean nugget, Properties p) {
        super(p);
        this.metal = metal;
        this.nugget = nugget;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        SmithedToolItem.heatLine(stack, out);
    }
}
