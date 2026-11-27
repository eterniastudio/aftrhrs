package net.axther.aftrhrs.feature.fairylights;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** One light for a String of Lights ({@code aftrhrs:fairy_<style>}): dyeable ones take any dye, glowstone makes it twinkle. */
public final class BulbItem extends Item {
    public final BulbStyle style;

    public BulbItem(BulbStyle style, Properties props) {
        super(props);
        this.style = style;
    }

    /** The bulb this item puts on a strand. */
    public static StrandSpec.Bulb bulb(ItemStack s) {
        BulbStyle style = ((BulbItem) s.getItem()).style;
        DyedItemColor dye = s.get(DataComponents.DYED_COLOR);
        int color = style.dyeable && dye != null ? dye.rgb() & 0xFFFFFF : -1;
        return new StrandSpec.Bulb(style, color, Boolean.TRUE.equals(s.get(FairyLights.TWINKLE)));
    }

    /** The item for a bulb (used when a bulb is swapped off a hung strand). */
    public static ItemStack stack(StrandSpec.Bulb b) {
        ItemStack s = new ItemStack(FairyLights.bulbItem(b.style()));
        if (b.style().dyeable && b.color() >= 0) s.set(DataComponents.DYED_COLOR, new DyedItemColor(b.color()));
        if (b.twinkle()) s.set(FairyLights.TWINKLE, true);
        return s;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        if (Boolean.TRUE.equals(stack.get(FairyLights.TWINKLE))) {
            out.accept(Component.translatableWithFallback("aftrhrs.fairy_lights.tip.twinkles", "Twinkles").withStyle(ChatFormatting.YELLOW));
        }
        out.accept(Component.translatableWithFallback("aftrhrs.fairy_lights.tip.bulb", "Goes on a String of Lights").withStyle(ChatFormatting.DARK_GRAY));
    }
}
