package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.Mods;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.ToolBuild;
import net.axther.aftrhrs.feature.smithing.ToolStats;
import net.axther.aftrhrs.feature.smithing.Workshop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The Whetstone Wheel (SMITHING.md 3.8). Hold a bladed tool against it (keep right-click held) to crank the wheel and
 * restore Edge, sparks flying. Sneak-click to grind the newest modifier off a tool: the slot comes back but the
 * catalyst is lost, unless a Scribe's Crystal in the off hand keeps it as a modifier crystal.
 */
public final class WheelBE extends StationBE {
    private long lastUse;

    public WheelBE(BlockPos pos, BlockState state) {
        super(Station.WHEEL, pos, state);
    }

    /** Game time of the last crank (the renderer spins the wheel for a second after). */
    public long lastUse() {
        return lastUse;
    }

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack s = p.getItemInHand(hand);
        ToolBuild b = s.get(Smithing.BUILD);
        if (b == null || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        SmithingDefs.ToolType type = SmithingDefs.tool(b.tool());
        if (type == null) return InteractionResult.PASS;
        if (p.isShiftKeyDown()) {
            if (b.mods().isEmpty()) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            grindOff(p, s, b);
            return InteractionResult.SUCCESS;
        }
        if (!Mods.edged(type) || !Workshop.edgeEnabled()) return InteractionResult.PASS;
        if (client()) {
            lastUse = level.getGameTime();
            return InteractionResult.SUCCESS;
        }
        if (!allowed(p)) return InteractionResult.FAIL;
        int edge = Smithing.edge(s);
        lastUse = now();
        if (edge < 100) {
            Smithing.setEdge(s, Math.min(100, edge + 5));
            Vec3 c = Workshop.local(worldPosition, facing(), 8, 13.5f, 8);
            ((ServerLevel) level).sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 6, 0.1, 0.05, 0.1, 0.08);
            sound(SoundEvents.GRINDSTONE_USE, 0.5f, 1.3f + edge / 200f);
            if (Smithing.edge(s) >= 100) tell(p, "aftrhrs.smithing.wheel.sharp");
        }
        sync();
        return InteractionResult.SUCCESS;
    }

    private void grindOff(Player p, ItemStack tool, ToolBuild b) {
        ToolBuild.Mod last = b.mods().get(b.mods().size() - 1);
        ToolBuild nb = b.withMod(last.id(), last.level() - 1);
        if (last.id().equals("etched")) nb = nb.withProgress("etched_color", 0);
        tool.set(Smithing.BUILD, nb);
        ToolStats.apply(tool, level.registryAccess());
        ItemStack off = p.getOffhandItem();
        if (off.is(Smithing.SCRIBES_CRYSTAL)) {
            if (!p.isCreative()) off.shrink(1);
            ItemStack crystal = new ItemStack(Smithing.MODIFIER_CRYSTAL);
            crystal.set(Smithing.CRYSTAL, last.id());
            if (!p.getInventory().add(crystal)) p.drop(crystal, false, net.minecraft.util.Prediction.SERVER_ONLY);
            tell(p, "aftrhrs.smithing.wheel.kept", Component.translatable("aftrhrs.smithing.modifier." + last.id()));
        } else {
            tell(p, "aftrhrs.smithing.wheel.ground", Component.translatable("aftrhrs.smithing.modifier." + last.id()));
        }
        sound(SoundEvents.GRINDSTONE_USE, 1f, 0.8f);
        lastUse = now();
        sync();
    }

    @Override
    protected void save(ValueOutput out) {
        out.putLong("Last", lastUse);
    }

    @Override
    protected void load(ValueInput in) {
        lastUse = in.getLongOr("Last", 0);
    }

    @Override
    protected List<ItemStack> contents() {
        return List.of();
    }
}
