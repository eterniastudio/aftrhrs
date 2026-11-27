package net.axther.aftrhrs.feature.smithing;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/** The smithed crossbow: vanilla loading (Quickdraw works through Quick Charge, Piercing through Piercing), our arrows' damage. */
public final class SmithedCrossbowItem extends CrossbowItem {
    public final SmithingDefs.ToolType type;

    SmithedCrossbowItem(SmithingDefs.ToolType type, Properties p) {
        super(p);
        this.type = type;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        SmithedToolItem.tooltip(stack, out, flag);
    }

    @Override
    public InteractionResult use(Level level, Player p, InteractionHand hand) {
        if (p.getItemInHand(hand).has(Smithing.BROKEN)) return InteractionResult.FAIL;
        return super.use(level, p, hand);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
        super.inventoryTick(stack, level, owner, slot);
        Effects.inventoryTick(stack, level, owner, slot);
    }

    @Override
    protected void shootProjectile(LivingEntity shooter, net.minecraft.world.entity.projectile.Projectile p, int index, float velocity, float inaccuracy,
                                   float angle, LivingEntity target) {
        super.shootProjectile(shooter, p, index, velocity, inaccuracy, angle, target);
        // Glide (down-feather fletching): the arrow flies 30% farther
        if (p.entityTags().contains("aftrhrs.glide")) p.setDeltaMovement(p.getDeltaMovement().scale(1.3));
    }

    @Override
    protected Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean crit) {
        Projectile p = super.createProjectile(level, shooter, weapon, ammo, crit);
        SmithedBowItem.boostArrow(p, weapon, ammo);
        return p;
    }
}
