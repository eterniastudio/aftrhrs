package net.axther.aftrhrs.feature.smithing;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A smithed tool ({@code aftrhrs:smithed_<tool>}): everything it is lives in its {@link ToolBuild}; its stats are
 * vanilla components written by {@link ToolStats#apply}. The same statics serve the bow, crossbow, rod and arrow
 * classes, which extend the vanilla item they behave like.
 */
public class SmithedToolItem extends Item {
    public final SmithingDefs.ToolType type;

    SmithedToolItem(SmithingDefs.ToolType type, Properties props) {
        super(props);
        this.type = type;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        tooltip(stack, out, flag);
    }

    @Override
    public float getAttackDamageBonus(Entity target, float damage, DamageSource source) {
        return Effects.attackBonus(this.type, target, damage, source);
    }

    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.hurtEnemy(stack, target, attacker);
        Effects.onHit(stack, target, attacker);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
        super.inventoryTick(stack, level, owner, slot);
        Effects.inventoryTick(stack, level, owner, slot);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        InteractionResult r = Effects.useOn(this.type, ctx);
        return r != InteractionResult.PASS ? r : super.useOn(ctx);
    }

    // ------------------------------------------------------------------ throwing (a spear with Recall)

    private boolean throwable(ItemStack stack) {
        ToolBuild b = stack.get(Smithing.BUILD);
        return type.has("charge_thrust") && b != null && b.level("recall") > 0 && !stack.has(Smithing.BROKEN);
    }

    @Override
    public InteractionResult use(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player p, net.minecraft.world.InteractionHand hand) {
        ItemStack s = p.getItemInHand(hand);
        if (!throwable(s)) return super.use(level, p, hand);
        p.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public net.minecraft.world.item.ItemUseAnimation getUseAnimation(ItemStack stack) {
        return throwable(stack) ? net.minecraft.world.item.ItemUseAnimation.TRIDENT : super.getUseAnimation(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return throwable(stack) ? 72000 : super.getUseDuration(stack, entity);
    }

    /** A spear with Recall is thrown like a trident and comes back (Loyalty). */
    @Override
    public boolean releaseUsing(ItemStack stack, net.minecraft.world.level.Level level, LivingEntity entity, int timeLeft) {
        if (!throwable(stack) || !(entity instanceof net.minecraft.world.entity.player.Player p) || getUseDuration(stack, entity) - timeLeft < 10) return false;
        if (level instanceof ServerLevel sl) {
            var spear = new net.axther.aftrhrs.feature.relics.ThrownRelicTrident(sl, p, stack);
            spear.shootFromRotation(p, p.getXRot(), p.getYRot(), 0.0f, 2.5f, 1.0f);
            if (p.hasInfiniteMaterials()) spear.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.CREATIVE_ONLY;
            sl.addFreshEntity(spear);
            sl.playSound(null, spear, net.minecraft.sounds.SoundEvents.TRIDENT_THROW.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.9f);
            if (!p.hasInfiniteMaterials()) p.getInventory().removeItem(stack);
        }
        return true;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return stack.has(Smithing.BROKEN) || super.isBarVisible(stack);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return stack.has(Smithing.BROKEN) ? 0x8A1C1C : super.getBarColor(stack);
    }

    // ------------------------------------------------------------------ tooltip (shared)

    /** Stars, parts, traits, modifiers, slots, then (Shift) the numbers. */
    public static void tooltip(ItemStack stack, Consumer<Component> out, TooltipFlag flag) {
        ToolBuild build = stack.get(Smithing.BUILD);
        if (build == null) {
            out.accept(Component.translatable("aftrhrs.smithing.unassembled").withStyle(ChatFormatting.GRAY));
            return;
        }
        SmithingDefs.ToolType type = SmithingDefs.tool(build.tool());
        if (stack.has(Smithing.BROKEN)) out.accept(Component.translatable("aftrhrs.smithing.broken").withStyle(ChatFormatting.RED));
        if (build.masterwork()) out.accept(Component.translatable("aftrhrs.smithing.masterwork").withStyle(ChatFormatting.GOLD));
        else out.accept(stars(Math.round(build.quality())));
        for (ToolBuild.PartEntry e : build.parts()) {
            MutableComponent line = Component.translatable("aftrhrs.smithing.part_line", Component.translatable("aftrhrs.smithing.part." + e.part()),
                    Component.translatable("aftrhrs.smithing.material." + e.material()));
            if (!e.extra().isEmpty()) line.append(Component.translatable("aftrhrs.smithing.extra", Component.translatable("aftrhrs.smithing.material." + e.extra())));
            line.append(" ").append(stars(e.stars()).withStyle(ChatFormatting.DARK_GRAY));
            out.accept(line.withStyle(ChatFormatting.GRAY));
        }
        for (Map.Entry<String, Integer> t : Mods.traits(build).entrySet()) {
            out.accept(Component.translatable("aftrhrs.smithing.trait." + t.getKey()).append(t.getValue() > 1 ? " " + roman(t.getValue()) : "")
                    .withStyle(ChatFormatting.GOLD));
        }
        for (ToolBuild.Mod m : build.mods()) {
            out.accept(Component.translatable("aftrhrs.smithing.modifier." + m.id()).append(m.level() > 1 ? " " + roman(m.level()) : "")
                    .withStyle(ChatFormatting.AQUA));
        }
        for (var p : build.progress().entrySet()) {
            if (!p.getKey().startsWith("mod:")) continue;
            SmithingDefs.Modifier def = SmithingDefs.modifier(p.getKey().substring(4));
            if (def == null || def.per() == null) continue;
            int next = build.level(def.id()) + 1;
            out.accept(Component.translatable("aftrhrs.smithing.modifier_progress", Component.translatable("aftrhrs.smithing.modifier." + def.id()),
                    roman(next), p.getValue(), def.per().count()).withStyle(ChatFormatting.DARK_AQUA));
        }
        if (type != null && !type.kind().equals("ammo")) {
            int[] cap = Mods.capacity(build), used = Mods.used(build);
            MutableComponent slots = Component.translatable("aftrhrs.smithing.slots.u", cap[0] - used[0]);
            if (cap[1] > 0) slots.append("  ").append(Component.translatable("aftrhrs.smithing.slots.a", cap[1] - used[1]));
            if (cap[2] > 0) slots.append("  ").append(Component.translatable("aftrhrs.smithing.slots.d", cap[2] - used[2]));
            out.accept(slots.withStyle(ChatFormatting.DARK_GRAY));
        }
        Integer edge = stack.get(Smithing.EDGE);
        if (edge != null && type != null && Mods.edged(type)) {
            out.accept(Component.translatable("aftrhrs.smithing.edge", edge).withStyle(edge > 60 ? ChatFormatting.WHITE : ChatFormatting.GRAY));
        }
        if (!build.smith().isEmpty()) out.accept(Component.translatable("aftrhrs.smithing.forged_by", build.smith()).withStyle(ChatFormatting.DARK_GRAY));
        if (type != null && SmithingClock.shiftDown()) {
            ToolStats.Stats s = ToolStats.compute(build);
            if (type.kind().equals("harvest") || type.kind().equals("melee")) {
                out.accept(Component.translatable("aftrhrs.smithing.stat.mining", fmt(s.miningSpeed()),
                        Component.translatable("aftrhrs.smithing.tier." + s.tier())).withStyle(ChatFormatting.DARK_GREEN));
            } else if (type.kind().equals("ranged")) {
                out.accept(Component.translatable("aftrhrs.smithing.stat.ranged", fmt(s.drawSpeed()), fmt(s.velocity()), fmt(s.accuracy()),
                        fmt(s.projectileDamage())).withStyle(ChatFormatting.DARK_GREEN));
            } else if (type.kind().equals("ammo")) {
                out.accept(Component.translatable("aftrhrs.smithing.stat.arrow", fmt(s.projectileDamage())).withStyle(ChatFormatting.DARK_GREEN));
            }
            if (!type.kind().equals("ammo")) {
                out.accept(Component.translatable("aftrhrs.smithing.stat.durability", stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage())
                        .withStyle(ChatFormatting.DARK_GREEN));
            }
        } else if (type != null) {
            out.accept(Component.translatable("aftrhrs.smithing.shift").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }

    static String fmt(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** "hot (cherry red, 712°)" for anything carrying heat. */
    static void heatLine(ItemStack stack, Consumer<Component> out) {
        Heat.Stamp st = stack.get(Smithing.HEAT);
        if (st == null) return;
        long now = SmithingClock.now();
        float t = st.at(now);
        if (t > 60) out.accept(Heat.describe(t));
    }

    static MutableComponent stars(int n) {
        int k = Math.max(0, Math.min(5, n));
        return Component.literal("★".repeat(k)).withStyle(ChatFormatting.GOLD).append(Component.literal("☆".repeat(5 - k)).withStyle(ChatFormatting.DARK_GRAY));
    }

    static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(n);
        };
    }
}
