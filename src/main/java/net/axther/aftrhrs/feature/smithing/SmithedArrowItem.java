package net.axther.aftrhrs.feature.smithing;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/** Smithed arrows (made 4 at a time): damage from the arrowhead and shaft, Glide from down-feather fletching. */
public final class SmithedArrowItem extends ArrowItem {
    public final SmithingDefs.ToolType type;

    SmithedArrowItem(SmithingDefs.ToolType type, Properties p) {
        super(p);
        this.type = type;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        SmithedToolItem.tooltip(stack, out, flag);
    }

    @Override
    public AbstractArrow createArrow(Level level, ItemStack ammo, LivingEntity shooter, @Nullable ItemStack weapon) {
        AbstractArrow a = super.createArrow(level, ammo, shooter, weapon);
        ToolBuild b = ammo.get(Smithing.BUILD);
        if (b != null) {
            ToolStats.Stats s = ToolStats.compute(b);
            a.setBaseDamage(s.projectileDamage());
            if (s.velocity() > 1) a.addTag("aftrhrs.glide");
        }
        return a;
    }
}
