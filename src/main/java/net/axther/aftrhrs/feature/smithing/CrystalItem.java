package net.axther.aftrhrs.feature.smithing;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** A modifier ground off with a Scribe's Crystal: one level of that modifier, etched back on at the Runesmith Table. */
public final class CrystalItem extends Item {
    CrystalItem(Properties p) {
        super(p);
    }

    @Override
    public Component getName(ItemStack stack) {
        String id = stack.get(Smithing.CRYSTAL);
        if (id == null) return super.getName(stack);
        return Component.translatable("item.aftrhrs.modifier_crystal.of", Component.translatable("aftrhrs.smithing.modifier." + id));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(Smithing.CRYSTAL);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        out.accept(Component.translatable("aftrhrs.smithing.crystal.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
