package net.axther.aftrhrs.feature.smithing;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The smith's simple hand tools (SMITHING.md 3.10): tongs (hold one hot piece), knife and chisel (carving and
 * etching), stirring rod (alloys), ladle (carries up to 4 ingots of one molten fluid; tipped into water it sets into
 * ingots).
 */
public final class HandToolItem extends Item {
    public enum Kind {TONGS, KNIFE, CHISEL, STIRRING_ROD, LADLE}

    public static final int LADLE_UNITS = 4 * Smelt.INGOT;

    public final Kind kind;

    HandToolItem(Kind kind, Properties p) {
        super(p);
        this.kind = kind;
    }

    public static Molten load(ItemStack ladle) {
        return ladle.get(Smithing.LADLE);
    }

    public static void setLoad(ItemStack ladle, String fluid, int units) {
        if (units <= 0 || fluid == null) {
            ladle.remove(Smithing.LADLE);
            ladle.remove(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
        } else {
            ladle.set(Smithing.LADLE, new Molten(fluid, units));
            // the item model shows what's in it
            ladle.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                    new net.minecraft.world.item.component.CustomModelData(java.util.List.of(), java.util.List.of(), java.util.List.of(fluid), java.util.List.of()));
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        if (kind == Kind.TONGS) {
            ItemStack in = stack.get(Smithing.HELD);
            if (in != null && !in.isEmpty()) return Component.translatable("item.aftrhrs.smith_tongs.holding", in.getHoverName());
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        switch (kind) {
            case TONGS -> {
                ItemStack in = stack.get(Smithing.HELD);
                if (in != null && !in.isEmpty()) SmithedToolItem.heatLine(in, out);
                out.accept(Component.translatable("aftrhrs.smithing.tongs.hint").withStyle(ChatFormatting.DARK_GRAY));
            }
            case LADLE -> {
                Molten m = load(stack);
                if (m != null) out.accept(Component.translatable("aftrhrs.smithing.ladle.load", m.units(),
                        Component.translatable("aftrhrs.smithing.fluid." + m.fluid())).withStyle(ChatFormatting.GOLD));
                else out.accept(Component.translatable("aftrhrs.smithing.ladle.empty").withStyle(ChatFormatting.GRAY));
            }
            default -> out.accept(Component.translatable("aftrhrs.smithing.hand." + kind.name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** A full ladle tipped into water sets into ingots (and nuggets for the rest). */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (kind != Kind.LADLE) return InteractionResult.PASS;
        ItemStack ladle = ctx.getItemInHand();
        Molten m = load(ladle);
        Level level = ctx.getLevel();
        BlockPos at = ctx.getClickedPos();
        if (m == null || Smelt.FISH_OIL.equals(m.fluid())) return InteractionResult.PASS;
        if (!level.getFluidState(at).is(Fluids.WATER) && !level.getFluidState(at.relative(ctx.getClickedFace())).is(Fluids.WATER)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        int ingots = m.units() / Smelt.INGOT, nuggets = (m.units() % Smelt.INGOT) / Smelt.NUGGET;
        ItemStack ingot = Smelt.ingot(m.fluid(), false), nugget = Smelt.ingot(m.fluid(), true);
        var p = ctx.getPlayer();
        if (!ingot.isEmpty() && ingots > 0 && p != null) Hands.give(p, ctx.getHand() == null ? net.minecraft.world.InteractionHand.MAIN_HAND : ctx.getHand(),
                ingot.copyWithCount(ingots));
        if (!nugget.isEmpty() && nuggets > 0 && p != null && !p.getInventory().add(nugget.copyWithCount(nuggets))) p.drop(nugget.copyWithCount(nuggets), false, net.minecraft.util.Prediction.SERVER_ONLY);
        setLoad(ladle, null, 0);
        level.playSound(null, at, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f);
        if (level instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 12, 0.3, 0.2, 0.3, 0.02);
        return InteractionResult.SUCCESS;
    }
}
