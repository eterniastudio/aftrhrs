package net.axther.aftrhrs.feature.smithing;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

/**
 * Smithed shortbow and longbow: draw speed from the limbs (and Springy, Quickdraw), arrow speed from the limbs and the
 * string (Taut), spread from limbs and grip (Steady, Trueshot), bonus arrow damage from limbs, grip and Power, plus
 * Piercing and Heavy Arrows.
 */
public final class SmithedBowItem extends BowItem {
    public final SmithingDefs.ToolType type;

    SmithedBowItem(SmithingDefs.ToolType type, Properties p) {
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
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player) || stack.has(Smithing.BROKEN)) return false;
        ItemStack ammo = player.getProjectile(stack);
        if (ammo.isEmpty()) return false;
        ToolBuild b = stack.get(Smithing.BUILD);
        ToolStats.Stats s = b == null ? null : ToolStats.compute(b);
        float draw = s == null ? 1 : s.drawSpeed();
        int charge = (int) ((getUseDuration(stack, entity) - timeLeft) * draw);
        float power = getPowerForTime(charge);
        if (power < 0.1) return false;
        List<ItemStack> shots = draw(stack, ammo, player);
        if (level instanceof ServerLevel sl && !shots.isEmpty()) {
            float vel = s == null ? 1 : s.velocity();
            float spread = s == null ? 1 : 1f / Math.max(0.25f, s.accuracy());
            shoot(sl, player, player.getUsedItemHand(), stack, shots, power * 3.0f * vel, spread, power == 1.0f, null);
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0f,
                1.0f / (level.getRandom().nextFloat() * 0.4f + 1.2f) + power * 0.5f);
        player.awardStat(Stats.ITEM_USED.get(this));
        return true;
    }

    @Override
    protected Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean crit) {
        Projectile p = super.createProjectile(level, shooter, weapon, ammo, crit);
        boostArrow(p, weapon, ammo);
        return p;
    }

    @Override
    protected void shootProjectile(LivingEntity shooter, net.minecraft.world.entity.projectile.Projectile p, int index, float velocity, float inaccuracy,
                                   float angle, LivingEntity target) {
        super.shootProjectile(shooter, p, index, velocity, inaccuracy, angle, target);
        // Glide (down-feather fletching): the arrow flies 30% farther
        if (p.entityTags().contains("aftrhrs.glide")) p.setDeltaMovement(p.getDeltaMovement().scale(1.3));
    }

    /** Limb, grip and Power damage, Piercing and Heavy Arrows on an arrow a smithed bow or crossbow shoots. */
    static void boostArrow(Projectile p, ItemStack weapon, ItemStack ammo) {
        ToolBuild b = weapon.get(Smithing.BUILD);
        if (b == null || !(p instanceof AbstractArrow arrow)) return;
        ToolStats.Stats s = ToolStats.compute(b);
        ToolBuild ab = ammo.get(Smithing.BUILD);
        double base = ab == null ? 2.0 : ToolStats.compute(ab).projectileDamage();
        arrow.setBaseDamage((base + s.projectileDamage() * 0.5) * (1 + 0.1 * b.level("power")));
        int pierce = b.level("piercing");
        if (pierce > 0 && arrow.getPierceLevel() < pierce) arrow.setPierceLevel((byte) pierce);
        if (b.level("heavy_arrows") > 0) arrow.addTag("aftrhrs.heavy." + b.level("heavy_arrows"));
    }
}
