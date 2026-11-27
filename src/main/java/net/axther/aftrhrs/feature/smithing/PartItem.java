package net.axther.aftrhrs.feature.smithing;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * A smithing part ({@code aftrhrs:part_<part>}): its material ({@code aftrhrs:smith_material}) and how it was made
 * ({@link PartInfo}) live in components; its model draws it in its material.
 */
public final class PartItem extends Item {
    public final SmithingDefs.Part part;

    PartItem(SmithingDefs.Part part, Properties props) {
        super(props);
        this.part = part;
    }

    @Override
    public Component getName(ItemStack stack) {
        String mat = stack.get(Smithing.MATERIAL);
        if (mat == null) return super.getName(stack);
        PartInfo info = stack.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
        MutableComponent n = Component.translatable("aftrhrs.smithing.named", Component.translatable("aftrhrs.smithing.material." + mat),
                Component.translatable("aftrhrs.smithing.part." + part.id()));
        return info.rough() ? Component.translatable("aftrhrs.smithing.rough", n) : n;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        PartInfo info = stack.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
        out.accept(SmithedToolItem.stars(info.rough() ? 1 : info.stars()));
        SmithedToolItem.heatLine(stack, out);
        SmithingDefs.Material m = SmithingDefs.material(stack.get(Smithing.MATERIAL));
        if (m == null) return;
        String trait = m.trait(part.role());
        if (trait != null) out.accept(Component.translatable("aftrhrs.smithing.trait." + trait).withStyle(ChatFormatting.GOLD));
        SmithingDefs.Material extra = SmithingDefs.material(info.extra());
        if (extra != null) {
            out.accept(Component.translatable("aftrhrs.smithing.extra", Component.translatable("aftrhrs.smithing.material." + extra.id()))
                    .withStyle(ChatFormatting.GRAY));
            String et = extra.trait(part.role());
            if (et != null) out.accept(Component.translatable("aftrhrs.smithing.trait." + et).withStyle(ChatFormatting.GOLD));
        }
        if (info.rough()) out.accept(Component.translatable("aftrhrs.smithing.rough.hint").withStyle(ChatFormatting.DARK_GRAY));
        else if (m.metal()) {
            out.accept((info.quench().isEmpty() ? Component.translatable("aftrhrs.smithing.quench.none")
                    : Component.translatable("aftrhrs.smithing.quenched." + info.quench())).withStyle(ChatFormatting.DARK_AQUA));
        }
        out.accept(Component.translatable("aftrhrs.smithing.role." + part.role(), part.cost()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
