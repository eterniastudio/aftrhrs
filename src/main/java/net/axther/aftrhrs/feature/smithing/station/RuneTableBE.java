package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.HandToolItem;
import net.axther.aftrhrs.feature.smithing.Hands;
import net.axther.aftrhrs.feature.smithing.Mods;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.SmithingProgress;
import net.axther.aftrhrs.feature.smithing.ToolBuild;
import net.axther.aftrhrs.feature.smithing.ToolStats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Runesmith Table (SMITHING.md 3.9). The tool goes in the middle, up to four catalyst stacks around it. Etch with
 * the chisel: four strikes along the grooves and the modifier the catalysts make goes into its slot. Incremental
 * modifiers take what's there toward their next level and remember the rest ("Keen Edge II: 21/36 quartz").
 */
public final class RuneTableBE extends StationBE {
    public static final int CORNERS = 4, ETCH = 4;
    /** Catalyst spots, model pixels (north-facing). */
    public static final float[][] SPOTS = {{3.5f, 14f, 3.5f}, {12.5f, 14f, 3.5f}, {3.5f, 14f, 12.5f}, {12.5f, 14f, 12.5f}};

    private ItemStack tool = ItemStack.EMPTY;
    private final ItemStack[] corners = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    private int etch;
    private long lastEtch;

    public RuneTableBE(BlockPos pos, BlockState state) {
        super(Station.RUNE_TABLE, pos, state);
    }

    public ItemStack tool() {
        return tool;
    }

    public ItemStack corner(int i) {
        return corners[i];
    }

    public int etch() {
        return etch;
    }

    /** What the catalysts on the table make (and whether it can go on), for the recipe note. */
    public SmithingDefs.Modifier match() {
        ToolBuild b = tool.get(Smithing.BUILD);
        if (b == null) return null;
        // a modifier crystal names its modifier
        for (ItemStack c : corners) {
            String id = c.get(Smithing.CRYSTAL);
            if (id != null) return SmithingDefs.modifier(id);
        }
        SmithingDefs.Modifier best = null;
        for (SmithingDefs.Modifier m : SmithingDefs.modifiers().values()) {
            if (m.incremental()) {
                boolean has = false;
                for (ItemStack c : corners) if (m.per().test(c)) has = true;
                if (has && others(m.per()) && (best == null || !best.incremental())) best = m;
            } else if (!m.needs().isEmpty() && hasAll(m.needs())) {
                if (best == null || best.incremental() || m.needs().size() > best.needs().size()) best = m;
            }
        }
        return best;
    }

    /** True when every corner holds only the incremental catalyst (or nothing). */
    private boolean others(SmithingDefs.Need per) {
        for (ItemStack c : corners) if (!c.isEmpty() && !per.test(c)) return false;
        return true;
    }

    private boolean hasAll(List<SmithingDefs.Need> needs) {
        for (SmithingDefs.Need n : needs) {
            int have = 0;
            for (ItemStack c : corners) if (n.test(c)) have += c.getCount();
            if (have < n.count()) return false;
        }
        // nothing else on the table
        for (ItemStack c : corners) {
            if (c.isEmpty()) continue;
            boolean used = false;
            for (SmithingDefs.Need n : needs) if (n.test(c)) used = true;
            if (!used) return false;
        }
        return true;
    }

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack s = p.getItemInHand(hand);
        // etch
        if (s.getItem() instanceof HandToolItem t && t.kind == HandToolItem.Kind.CHISEL && !tool.isEmpty()) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            SmithingDefs.Modifier m = match();
            ToolBuild b = tool.get(Smithing.BUILD);
            if (m == null) {
                tell(p, "aftrhrs.smithing.rune.nothing");
                return InteractionResult.SUCCESS;
            }
            String why = Mods.refuse(b, m);
            if (why != null) {
                tell(p, why, Component.translatable("aftrhrs.smithing.modifier." + m.id()));
                return InteractionResult.SUCCESS;
            }
            long now = now();
            if (now - lastEtch < 6) return InteractionResult.SUCCESS;
            lastEtch = now;
            etch++;
            s.hurtAndBreak(1, p, hand);
            sound(SoundEvents.AMETHYST_BLOCK_HIT, 0.8f, 0.8f + etch * 0.15f);
            Vec3 c = net.axther.aftrhrs.feature.smithing.Workshop.local(worldPosition, facing(), 8, 14.2f, 8);
            ((ServerLevel) level).sendParticles(ParticleTypes.ENCHANT, c.x, c.y, c.z, 12, 0.3, 0.05, 0.3, 0.2);
            setLit(true);
            if (etch >= ETCH) {
                etch = 0;
                apply((ServerPlayer) p, m, b);
                setLit(false);
            }
            sync();
            return InteractionResult.SUCCESS;
        }
        // the tool into the middle
        if (tool.isEmpty() && s.has(Smithing.BUILD) && s.getCount() == 1) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            tool = Hands.take(p, hand, 1);
            etch = 0;
            sound(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.8f, 0.9f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // catalysts round it
        if (!s.isEmpty() && !s.has(Smithing.BUILD)) {
            int slot = -1;
            for (int i = 0; i < CORNERS; i++) {
                if (ItemStack.isSameItemSameComponents(corners[i], s) && corners[i].getCount() < corners[i].getMaxStackSize()) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) for (int i = 0; i < CORNERS; i++) if (corners[i].isEmpty()) {
                slot = i;
                break;
            }
            if (slot < 0) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            int n = p.isShiftKeyDown() ? s.getCount() : 1;
            if (corners[slot].isEmpty()) {
                corners[slot] = s.copyWithCount(n);
            } else {
                n = Math.min(n, corners[slot].getMaxStackSize() - corners[slot].getCount());
                corners[slot].grow(n);
            }
            if (!p.isCreative()) s.shrink(n);
            etch = 0;
            sound(SoundEvents.ITEM_FRAME_ADD_ITEM, 0.6f, 1.2f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // empty hand: catalysts back first, then the tool
        if (s.isEmpty()) {
            for (int i = CORNERS - 1; i >= 0; i--) {
                if (corners[i].isEmpty()) continue;
                if (client()) return InteractionResult.SUCCESS;
                if (!allowed(p)) return InteractionResult.FAIL;
                Hands.give(p, hand, corners[i]);
                corners[i] = ItemStack.EMPTY;
                etch = 0;
                sync();
                return InteractionResult.SUCCESS;
            }
            if (!tool.isEmpty()) {
                if (client()) return InteractionResult.SUCCESS;
                if (!allowed(p)) return InteractionResult.FAIL;
                Hands.give(p, hand, tool);
                tool = ItemStack.EMPTY;
                etch = 0;
                setLit(false);
                sync();
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    private void setLit(boolean on) {
        BlockState st = getBlockState();
        if (st.hasProperty(StationBlock.ON) && st.getValue(StationBlock.ON) != on) level.setBlock(worldPosition, st.setValue(StationBlock.ON, on), 3);
    }

    private void apply(ServerPlayer p, SmithingDefs.Modifier m, ToolBuild b) {
        String key = "mod:" + m.id();
        int level = b.level(m.id());
        ToolBuild nb = b;
        boolean leveled = false;
        // a crystal is one whole level
        for (int i = 0; i < CORNERS; i++) {
            if (m.id().equals(corners[i].get(Smithing.CRYSTAL))) {
                corners[i].shrink(1);
                nb = nb.withMod(m.id(), level + 1).withProgress(key, 0);
                leveled = true;
                break;
            }
        }
        if (!leveled && m.incremental()) {
            int need = m.per().count() - b.progress(key);
            for (int i = 0; i < CORNERS && need > 0; i++) {
                if (!m.per().test(corners[i])) continue;
                int take = Math.min(need, corners[i].getCount());
                corners[i].shrink(take);
                need -= take;
            }
            int paid = m.per().count() - need;
            if (paid >= m.per().count()) {
                nb = nb.withMod(m.id(), level + 1).withProgress(key, 0);
                leveled = true;
            } else {
                nb = nb.withProgress(key, paid);
                tell(p, "aftrhrs.smithing.rune.progress", Component.translatable("aftrhrs.smithing.modifier." + m.id()), paid, m.per().count());
            }
        } else if (!leveled) {
            for (SmithingDefs.Need n : m.needs()) {
                int need = n.count();
                for (int i = 0; i < CORNERS && need > 0; i++) {
                    if (!n.test(corners[i])) continue;
                    int take = Math.min(need, corners[i].getCount());
                    // Engraved: the name tag's name becomes the tool's; Etched: the dye's color
                    if (m.id().equals("engraved") && corners[i].has(DataComponents.CUSTOM_NAME)) {
                        tool.set(DataComponents.CUSTOM_NAME, corners[i].get(DataComponents.CUSTOM_NAME));
                    }
                    var dye = corners[i].get(DataComponents.DYE);
                    if (m.id().equals("etched") && dye != null) {
                        nb = nb.withProgress("etched_color", dye.getTextureDiffuseColor() & 0xFFFFFF | 0x1000000);
                    }
                    corners[i].shrink(take);
                    need -= take;
                }
            }
            nb = nb.withMod(m.id(), level + 1);
            leveled = true;
        }
        for (int i = 0; i < CORNERS; i++) if (corners[i].isEmpty()) corners[i] = ItemStack.EMPTY;
        tool.set(Smithing.BUILD, nb);
        ToolStats.apply(tool, this.level.registryAccess());
        if (leveled) {
            sound(SoundEvents.ENCHANTMENT_TABLE_USE, 1f, 1f);
            tell(p, "aftrhrs.smithing.rune.done", Component.translatable("aftrhrs.smithing.modifier." + m.id()));
            SmithingProgress.etched(p, m.id(), tool);
        }
    }

    @Override
    protected void save(ValueOutput out) {
        writeStack(out, "Tool", tool);
        for (int i = 0; i < CORNERS; i++) writeStack(out, "Corner" + i, corners[i]);
        out.putInt("Etch", etch);
    }

    @Override
    protected void load(ValueInput in) {
        tool = readStack(in, "Tool");
        for (int i = 0; i < CORNERS; i++) corners[i] = readStack(in, "Corner" + i);
        etch = in.getIntOr("Etch", 0);
    }

    @Override
    protected List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        out.add(tool);
        for (ItemStack c : corners) out.add(c);
        return out;
    }
}
