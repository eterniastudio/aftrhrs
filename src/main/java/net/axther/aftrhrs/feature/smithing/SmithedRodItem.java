package net.axther.aftrhrs.feature.smithing;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/**
 * The smithed fishing rod: a vanilla rod to the game (so the fishing module's bites, fights and tables all apply);
 * its limb and quality pull harder in fish fights, Braided Line holds more line tension, Lure Charm and Treasure Sense
 * work through Lure and Luck of the Sea, and Grapple pulls you to a hooked block.
 */
public final class SmithedRodItem extends FishingRodItem {
    public final SmithingDefs.ToolType type;

    SmithedRodItem(SmithingDefs.ToolType type, Properties p) {
        super(p);
        this.type = type;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        SmithedToolItem.tooltip(stack, out, flag);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
        super.inventoryTick(stack, level, owner, slot);
        Effects.inventoryTick(stack, level, owner, slot);
    }

    @Override
    public InteractionResult use(Level level, Player p, InteractionHand hand) {
        ItemStack rod = p.getItemInHand(hand);
        if (rod.has(Smithing.BROKEN)) return InteractionResult.FAIL;
        ToolBuild b = rod.get(Smithing.BUILD);
        FishingHook hook = p.fishing;
        // Grapple: reeling in a hook stuck in a block pulls you to it
        if (hook != null && b != null && b.level("grapple") > 0 && hook.getHookedIn() == null && hook.onGround()) {
            Vec3 to = hook.position().subtract(p.position());
            Vec3 pull = to.normalize().scale(Math.min(2.2, 0.3 + to.length() * 0.12));
            p.push(pull.x, Math.max(0.3, pull.y + 0.35), pull.z);
            p.syncVelocity = true;
            p.resetFallDistance();
        }
        return super.use(level, p, hand);
    }

    /** Extra reel power in fish fights (the fishing module asks). */
    public static double reelBonus(ItemStack rod) {
        ToolBuild b = rod.get(Smithing.BUILD);
        if (b == null) return 0;
        ToolStats.Stats s = ToolStats.compute(b);
        return Math.max(0, (s.velocity() - 1) * 0.5) + (ToolStats.quality(b.quality()) - 1);
    }

    /** Extra line strength in fish fights: Braided Line +20% per level, plus quality. */
    public static double lineBonus(ItemStack rod) {
        ToolBuild b = rod.get(Smithing.BUILD);
        if (b == null) return 0;
        return 0.2 * b.level("braided_line") + (ToolStats.quality(b.quality()) - 1);
    }
}
