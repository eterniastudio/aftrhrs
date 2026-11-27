package net.axther.aftrhrs.feature.smithing;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * Casting molds (SMITHING.md 3.3). The shape ({@code aftrhrs:smith_mold}) is a part id, {@code ingot} or
 * {@code nugget}. A sand mold is used once; clay is shaped, then fired in the forge, and lasts 32 pours.
 */
public final class MoldItem extends Item {
    public enum Kind {SAND, CLAY_UNFIRED, CLAY}

    public final Kind kind;

    MoldItem(Kind kind, Properties p) {
        super(p);
        this.kind = kind;
    }

    public static ItemStack of(Kind kind, String shape) {
        MoldItem item = switch (kind) {
            case SAND -> Smithing.SAND_MOLD;
            case CLAY_UNFIRED -> Smithing.CLAY_MOLD_UNFIRED;
            case CLAY -> Smithing.CLAY_MOLD;
        };
        ItemStack s = new ItemStack(item);
        if (shape != null && !shape.isEmpty()) s.set(Smithing.MOLD, shape);
        return s;
    }

    public static String shape(ItemStack s) {
        return s.getOrDefault(Smithing.MOLD, "");
    }

    /** Molten units a shape takes: a part's cost in ingots, an ingot 90, a nugget 10. */
    public static int units(String shape) {
        if (shape.equals("ingot")) return Smelt.INGOT;
        if (shape.equals("nugget")) return Smelt.NUGGET;
        SmithingDefs.Part p = SmithingDefs.part(shape);
        return p == null ? 0 : p.cost() * Smelt.INGOT;
    }

    @Override
    public Component getName(ItemStack stack) {
        String shape = shape(stack);
        if (shape.isEmpty()) return super.getName(stack);
        Component what = shape.equals("ingot") || shape.equals("nugget") ? Component.translatable("aftrhrs.smithing.shape." + shape)
                : Component.translatable("aftrhrs.smithing.part." + shape);
        return Component.translatable("item.aftrhrs." + (kind == Kind.SAND ? "sand_mold" : kind == Kind.CLAY ? "clay_mold" : "clay_mold_unfired") + ".of", what);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        String shape = shape(stack);
        if (!shape.isEmpty()) out.accept(Component.translatable("aftrhrs.smithing.mold.units", units(shape)).withStyle(ChatFormatting.GRAY));
        if (kind == Kind.CLAY_UNFIRED) out.accept(Component.translatable("aftrhrs.smithing.mold.fire").withStyle(ChatFormatting.DARK_GRAY));
        SmithedToolItem.heatLine(stack, out);
    }
}
