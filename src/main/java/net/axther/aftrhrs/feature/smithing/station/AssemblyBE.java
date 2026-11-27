package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.Hands;
import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.Mods;
import net.axther.aftrhrs.feature.smithing.PartInfo;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.SmithingProgress;
import net.axther.aftrhrs.feature.smithing.ToolBuild;
import net.axther.aftrhrs.feature.smithing.ToolStats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The Assembly Bench (SMITHING.md 3.7), the burger stack. Lay parts in order (head, then binding, then handle); a part
 * that no tool takes next won't go down. When the parts make a tool, rivet with the hammer. Sneak-click with an empty
 * hand to switch between tools the parts could make (a shortbow or a crossbow). A tool on the bench: click with a part
 * to swap it in (modifiers stay), or with its head material and then three hammer taps to repair it.
 */
public final class AssemblyBE extends StationBE {
    private final List<ItemStack> parts = new ArrayList<>();
    private ItemStack tool = ItemStack.EMPTY;
    private int choice, repairUnits, taps;

    public AssemblyBE(BlockPos pos, BlockState state) {
        super(Station.ASSEMBLY_BENCH, pos, state);
    }

    public List<ItemStack> parts() {
        return parts;
    }

    public ItemStack tool() {
        return tool;
    }

    public int repairUnits() {
        return repairUnits;
    }

    /** Tool types whose part list starts with the parts laid so far. */
    public List<SmithingDefs.ToolType> candidates() {
        List<SmithingDefs.ToolType> out = new ArrayList<>();
        for (SmithingDefs.ToolType t : SmithingDefs.tools().values()) {
            if (t.parts().size() < parts.size()) continue;
            boolean ok = true;
            for (int i = 0; i < parts.size() && ok; i++) {
                ok = parts.get(i).getItem() instanceof PartItem pi && pi.part.id().equals(t.parts().get(i).part());
            }
            if (ok) out.add(t);
        }
        return out;
    }

    /** The tool the laid parts complete (the chosen one when several), or null. */
    public SmithingDefs.ToolType complete() {
        List<SmithingDefs.ToolType> done = new ArrayList<>();
        for (SmithingDefs.ToolType t : candidates()) if (t.parts().size() == parts.size()) done.add(t);
        if (done.isEmpty()) return null;
        return done.get(Math.floorMod(choice, done.size()));
    }

    /** The tool the bench is heading for (for the chalk outline): the chosen candidate. */
    public SmithingDefs.ToolType heading() {
        SmithingDefs.ToolType c = complete();
        if (c != null) return c;
        List<SmithingDefs.ToolType> all = candidates();
        return all.isEmpty() || parts.isEmpty() ? null : all.get(Math.floorMod(choice, all.size()));
    }

    private boolean fitsNext(ItemStack part) {
        if (!(part.getItem() instanceof PartItem pi)) return false;
        int i = parts.size();
        for (SmithingDefs.ToolType t : candidates()) {
            if (t.parts().size() > i && t.parts().get(i).part().equals(pi.part.id())) return true;
        }
        return false;
    }

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack s = p.getItemInHand(hand);
        ItemStack held = Hands.held(p, hand);
        boolean hammer = CastingBE.hammer(s);
        if (!tool.isEmpty()) return useTool(p, hand, s, held, hammer);
        // rivet
        if (hammer) {
            SmithingDefs.ToolType t = complete();
            if (t == null) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            rivet((ServerPlayer) p, hand, t);
            s.hurtAndBreak(1, p, hand);
            return InteractionResult.SUCCESS;
        }
        // a part onto the stack
        if (held.getItem() instanceof PartItem) {
            if (Heat.of(held, now()) > 150) {
                if (!client()) tell(p, "aftrhrs.smithing.assembly.hot");
                return InteractionResult.SUCCESS;
            }
            if (!fitsNext(held)) {
                if (!client()) tell(p, "aftrhrs.smithing.assembly.no_fit");
                return InteractionResult.SUCCESS;
            }
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            parts.add(Hands.take(p, hand, 1));
            sound(SoundEvents.CHAIN_PLACE, 0.8f, 1.2f + 0.1f * parts.size());
            sync();
            return InteractionResult.SUCCESS;
        }
        // a finished tool onto the bench (to swap a part or repair)
        if (parts.isEmpty() && held.has(Smithing.BUILD) && !(held.getItem() instanceof PartItem) && held.getCount() == 1) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            tool = Hands.take(p, hand, 1);
            repairUnits = taps = 0;
            sound(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.8f, 1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // choose between tools the same parts make
        if (s.isEmpty() && p.isShiftKeyDown() && !parts.isEmpty()) {
            if (client()) return InteractionResult.SUCCESS;
            choice++;
            SmithingDefs.ToolType h = heading();
            if (h != null) tell(p, "aftrhrs.smithing.assembly.heading", Component.translatable("aftrhrs.smithing.tool." + h.id()));
            sync();
            return InteractionResult.SUCCESS;
        }
        // take the top part back
        if (Hands.free(p, hand) && !parts.isEmpty()) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            Hands.give(p, hand, parts.remove(parts.size() - 1));
            sync();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private void rivet(ServerPlayer p, InteractionHand hand, SmithingDefs.ToolType t) {
        List<ToolBuild.PartEntry> entries = new ArrayList<>();
        for (ItemStack part : parts) {
            PartInfo info = part.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
            String mat = part.get(Smithing.MATERIAL);
            int stars = info.rough() ? 1 : info.stars();
            entries.add(new ToolBuild.PartEntry(((PartItem) part.getItem()).part.id(), mat == null ? "wood" : mat, stars, info.quench(), info.extra()));
        }
        ToolBuild build = new ToolBuild(t.id(), entries, List.of(), java.util.Map.of(), p.getGameProfile().name());
        if (!Smithing.valid(build)) {
            tell(p, "aftrhrs.smithing.assembly.no_fit");
            return;
        }
        boolean ammo = t.kind().equals("ammo");
        ItemStack out = new ItemStack(Smithing.TOOLS.get(t.id()), ammo ? t.makes() : 1);
        out.set(Smithing.BUILD, build);
        if (Mods.edged(t)) out.set(Smithing.EDGE, 100);
        ToolStats.apply(out, level.registryAccess());
        parts.clear();
        choice = 0;
        sound(SoundEvents.ANVIL_USE, 0.7f, 1.3f);
        sound(SoundEvents.SMITHING_TABLE_USE, 1f, 1.1f);
        Vec3 c = Vec3.atLowerCornerOf(worldPosition).add(0.5, 1.0, 0.5);
        ((ServerLevel) level).sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y, c.z, 8, 0.3, 0.1, 0.3, 0);
        Hands.give(p, hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, out);
        // a dagger's parts make two: the second is the same piece again
        for (int i = 1; !ammo && i < t.makes(); i++) {
            ItemStack more = out.copy();
            if (!p.getInventory().add(more)) p.drop(more, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        SmithingProgress.assembled(p, out);
        sync();
    }

    /** Head material a tool repairs with: units one item is worth, or 0. */
    static int repairWorth(ToolBuild b, ItemStack s) {
        if (b.parts().isEmpty()) return 0;
        String head = b.parts().get(0).material();
        for (Smelt.Melt m : Smelt.melt(s)) if (m.fluid().equals(head) && !(s.getItem() instanceof PartItem)) return m.units();
        SmithingDefs.Material carved = CarvingBE.materialOf(s);
        if (carved != null && carved.id().equals(head)) return Smelt.INGOT;
        return 0;
    }

    private InteractionResult useTool(Player p, InteractionHand hand, ItemStack s, ItemStack held, boolean hammer) {
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null) return InteractionResult.PASS;
        SmithingDefs.ToolType type = SmithingDefs.tool(b.tool());
        // repair: material first, then three taps
        int worth = repairWorth(b, s);
        if (worth > 0 && (tool.isDamaged() || tool.has(Smithing.BROKEN))) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            if (!p.isCreative()) s.shrink(1);
            repairUnits += worth;
            taps = 0;
            sound(SoundEvents.CHAIN_PLACE, 0.7f, 1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        if (hammer && repairUnits > 0) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            s.hurtAndBreak(1, p, hand);
            sound(SoundEvents.ANVIL_USE, 0.5f, 1.4f + 0.1f * taps);
            if (++taps >= 3) {
                SmithingDefs.Part head = SmithingDefs.part(b.parts().get(0).part());
                int cost = Math.max(1, head == null ? 1 : head.cost()) * Smelt.INGOT;
                int restore = (int) ((long) tool.getMaxDamage() * repairUnits / cost);
                tool.setDamageValue(Math.max(0, tool.getDamageValue() - restore));
                repairUnits = 0;
                taps = 0;
                if (tool.has(Smithing.BROKEN)) Smithing.repaired(tool, level);
                tell(p, "aftrhrs.smithing.assembly.repaired");
            }
            sync();
            return InteractionResult.SUCCESS;
        }
        // swap a part
        if (held.getItem() instanceof PartItem pi && type != null) {
            int idx = b.indexOf(pi.part.id());
            if (idx < 0) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            PartInfo info = held.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
            String mat = held.get(Smithing.MATERIAL);
            ToolBuild.PartEntry old = b.parts().get(idx);
            ToolBuild nb = b.withPart(idx, new ToolBuild.PartEntry(pi.part.id(), mat, info.rough() ? 1 : info.stars(), info.quench(), info.extra()));
            if (!Smithing.valid(nb)) {
                tell(p, "aftrhrs.smithing.assembly.no_fit");
                return InteractionResult.SUCCESS;
            }
            int[] cap = Mods.capacity(nb), used = Mods.used(nb);
            for (int i = 0; i < 3; i++) {
                if (used[i] > cap[i]) {
                    tell(p, "aftrhrs.smithing.assembly.slots");
                    return InteractionResult.SUCCESS;
                }
            }
            Hands.take(p, hand, 1);
            tool.set(Smithing.BUILD, nb);
            ToolStats.apply(tool, level.registryAccess());
            ItemStack back = Smithing.part(old.part(), old.material(), old.stars());
            if (!back.isEmpty()) {
                back.set(Smithing.PART, new PartInfo(old.stars(), old.quench(), old.extra(), false));
                if (!p.getInventory().add(back)) p.drop(back, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
            sound(SoundEvents.ANVIL_USE, 0.5f, 1.6f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // take the tool back
        if (Hands.free(p, hand)) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            Hands.give(p, hand, tool);
            tool = ItemStack.EMPTY;
            repairUnits = taps = 0;
            sync();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void save(ValueOutput out) {
        out.store("Parts", ItemStack.OPTIONAL_CODEC.listOf(), List.copyOf(parts));
        writeStack(out, "Tool", tool);
        out.putInt("Choice", choice);
        out.putInt("Repair", repairUnits);
        out.putInt("Taps", taps);
    }

    @Override
    protected void load(ValueInput in) {
        parts.clear();
        in.read("Parts", ItemStack.OPTIONAL_CODEC.listOf()).ifPresent(l -> l.forEach(s -> {
            if (!s.isEmpty()) parts.add(s.copy());
        }));
        tool = readStack(in, "Tool");
        choice = in.getIntOr("Choice", 0);
        repairUnits = in.getIntOr("Repair", 0);
        taps = in.getIntOr("Taps", 0);
    }

    @Override
    protected List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>(parts);
        out.add(tool);
        return out;
    }
}
