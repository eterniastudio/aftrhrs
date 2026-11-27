package net.axther.aftrhrs.feature.smithing.station;

import com.mojang.serialization.Codec;
import net.axther.aftrhrs.feature.smithing.HandToolItem;
import net.axther.aftrhrs.feature.smithing.Molten;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A melting pot (SMITHING.md 3.2), the Crucible or the Great Crucible: solid pieces sink and melt once it is hot
 * enough; alloy additives (amethyst, blaze powder...) wait in the melt; three stirs with the stirring rod turn the
 * right mix at the right heat into its alloy; a ladle (or a spout) takes metal out.
 */
public abstract class MeltBE extends StationBE implements net.minecraft.world.Container {
    protected final Map<String, Integer> fluids = new LinkedHashMap<>();
    protected final List<ItemStack> solids = new ArrayList<>();
    protected int melting, stirs;
    protected float shownHeat;

    protected MeltBE(Station station, BlockPos pos, BlockState state) {
        super(station, pos, state);
    }

    /** Most molten units it holds. */
    public abstract int capacity();

    /** Heat of the melt right now (from what's under it). */
    public abstract float heat();

    /** The Great Crucible melts tier-4 metal and makes the great alloys. */
    protected abstract boolean great();

    /** Where solids fall in (and particles rise from), world space. */
    protected abstract Vec3 mouth();

    public Map<String, Integer> fluids() {
        return fluids;
    }

    public List<ItemStack> solids() {
        return solids;
    }

    public int total() {
        int t = 0;
        for (int u : fluids.values()) t += u;
        return t;
    }

    /** Units the pending solids will add once melted. */
    private int pending() {
        int t = 0;
        for (ItemStack s : solids) for (Smelt.Melt m : Smelt.melt(s)) t += m.units() * s.getCount();
        return t;
    }

    /** The fluid a ladle or spout takes: the most plentiful. */
    public String topFluid() {
        String best = null;
        int bu = 0;
        for (var e : fluids.entrySet()) {
            if (e.getValue() > bu) {
                bu = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** Takes up to {@code max} units of {@code fluid} (null: the top fluid). */
    public Molten drain(String fluid, int max) {
        String f = fluid != null ? fluid : topFluid();
        if (f == null) return null;
        int have = fluids.getOrDefault(f, 0);
        int take = Math.min(have, max);
        if (take <= 0) return null;
        if (have - take <= 0) fluids.remove(f);
        else fluids.put(f, have - take);
        stirs = 0;
        sync();
        return new Molten(f, take);
    }

    /** Can this pot take {@code s} (one item) now? Meltable within capacity, or an additive some alloy uses. */
    protected boolean accepts(ItemStack s) {
        if (s.isEmpty() || solids.size() >= 8) return false;
        List<Smelt.Melt> melt = Smelt.melt(s);
        if (!melt.isEmpty()) {
            for (Smelt.Melt m : melt) if (Smelt.needsGreat(m.fluid()) && !great()) return false;
            int units = 0;
            for (Smelt.Melt m : melt) units += m.units();
            return total() + pending() + units <= capacity();
        }
        for (SmithingDefs.Alloy a : SmithingDefs.alloys().values()) {
            if (a.great() && !great()) continue;
            for (SmithingDefs.Additive add : a.additives()) if (add.test(s)) return true;
        }
        return false;
    }

    protected void addSolid(ItemStack one) {
        for (ItemStack s : solids) {
            if (ItemStack.isSameItemSameComponents(s, one) && s.getCount() < s.getMaxStackSize()) {
                s.grow(one.getCount());
                return;
            }
        }
        solids.add(one);
    }

    @Override
    public void serverTick(ServerLevel level) {
        float h = heat();
        if (Math.abs(h - shownHeat) > 10) {
            shownHeat = h;
            sync();
        }
        // melt the first solid that can melt at this heat
        for (int i = 0; i < solids.size(); i++) {
            ItemStack s = solids.get(i);
            List<Smelt.Melt> melt = Smelt.melt(s);
            if (melt.isEmpty()) continue; // an additive: waits for the stir
            int need = 0;
            for (Smelt.Melt m : melt) need = Math.max(need, Smelt.meltHeat(m.fluid()));
            if (h < need) continue;
            melting += 1 + (int) ((h - need) / 40);
            if (melting >= 60) {
                melting = 0;
                for (Smelt.Melt m : melt) fluids.merge(m.fluid(), m.units(), Integer::sum);
                s.shrink(1);
                if (s.isEmpty()) solids.remove(i);
                stirs = 0;
                sound(SoundEvents.LAVA_POP, 0.5f, 1.2f);
                sync();
            }
            break;
        }
        if (level.getGameTime() % 40 == 0 && total() > 0 && h > 400) {
            Vec3 m = mouth();
            level.sendParticles(ParticleTypes.SMOKE, m.x, m.y, m.z, 1, 0.15, 0.02, 0.15, 0.005);
        }
    }

    // ------------------------------------------------------------------ alloys

    /** The alloy the melt is ready to make (heat, inputs, additives all there), or null. */
    public SmithingDefs.Alloy readyAlloy() {
        float h = heat();
        for (SmithingDefs.Alloy a : SmithingDefs.alloys().values()) {
            if (a.great() && !great() || h < a.heat()) continue;
            if (batches(a) > 0) return a;
        }
        return null;
    }

    private int batches(SmithingDefs.Alloy a) {
        int n = Integer.MAX_VALUE;
        for (var in : a.inputs().entrySet()) n = Math.min(n, fluids.getOrDefault(in.getKey(), 0) / in.getValue());
        for (SmithingDefs.Additive add : a.additives()) {
            int have = 0;
            for (ItemStack s : solids) if (add.test(s)) have += s.getCount();
            n = Math.min(n, have / add.count());
        }
        return n == Integer.MAX_VALUE ? 0 : n;
    }

    private void makeAlloy(SmithingDefs.Alloy a) {
        int n = batches(a);
        if (n <= 0) return;
        for (var in : a.inputs().entrySet()) {
            int left = fluids.get(in.getKey()) - in.getValue() * n;
            if (left <= 0) fluids.remove(in.getKey());
            else fluids.put(in.getKey(), left);
        }
        for (SmithingDefs.Additive add : a.additives()) {
            int need = add.count() * n;
            for (ItemStack s : solids) {
                if (need <= 0) break;
                if (!add.test(s)) continue;
                int take = Math.min(need, s.getCount());
                s.shrink(take);
                need -= take;
            }
            solids.removeIf(ItemStack::isEmpty);
        }
        fluids.merge(a.id(), a.units() * n, Integer::sum);
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack s = p.getItemInHand(hand);
        if (s.getItem() instanceof HandToolItem tool) {
            switch (tool.kind) {
                case STIRRING_ROD -> {
                    if (client()) return InteractionResult.SUCCESS;
                    if (!allowed(p)) return InteractionResult.FAIL;
                    SmithingDefs.Alloy a = readyAlloy();
                    sound(SoundEvents.BREWING_STAND_BREW, 0.4f, 0.6f + stirs * 0.2f);
                    Vec3 m = mouth();
                    ((ServerLevel) level).sendParticles(ParticleTypes.LAVA, m.x, m.y, m.z, 2, 0.15, 0, 0.15, 0);
                    if (a == null) {
                        stirs = 0;
                        tell(p, total() == 0 ? "aftrhrs.smithing.crucible.empty" : "aftrhrs.smithing.crucible.no_alloy");
                        return InteractionResult.SUCCESS;
                    }
                    stirs++;
                    if (stirs >= 3) {
                        stirs = 0;
                        makeAlloy(a);
                        sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 0.8f);
                        tell(p, "aftrhrs.smithing.crucible.alloyed", net.minecraft.network.chat.Component.translatable("aftrhrs.smithing.material." + a.id()));
                        if (p instanceof net.minecraft.server.level.ServerPlayer sp) net.axther.aftrhrs.feature.smithing.SmithingProgress.alloyed(sp, a.id());
                    } else {
                        tell(p, "aftrhrs.smithing.crucible.stir", stirs);
                    }
                    sync();
                    return InteractionResult.SUCCESS;
                }
                case LADLE -> {
                    Molten load = HandToolItem.load(s);
                    if (load == null) {
                        if (total() == 0) return InteractionResult.PASS;
                        if (client()) return InteractionResult.SUCCESS;
                        if (!allowed(p)) return InteractionResult.FAIL;
                        Molten got = drain(null, HandToolItem.LADLE_UNITS);
                        if (got != null) {
                            HandToolItem.setLoad(s, got.fluid(), got.units());
                            sound(SoundEvents.BUCKET_FILL_LAVA, 0.7f, 1.2f);
                        }
                        return InteractionResult.SUCCESS;
                    }
                    // pour back in
                    if (total() + load.units() > capacity()) return InteractionResult.PASS;
                    if (client()) return InteractionResult.SUCCESS;
                    if (!allowed(p)) return InteractionResult.FAIL;
                    fluids.merge(load.fluid(), load.units(), Integer::sum);
                    HandToolItem.setLoad(s, null, 0);
                    sound(SoundEvents.BUCKET_EMPTY_LAVA, 0.7f, 1.2f);
                    sync();
                    return InteractionResult.SUCCESS;
                }
                default -> {
                    return InteractionResult.PASS;
                }
            }
        }
        ItemStack held = net.axther.aftrhrs.feature.smithing.Hands.held(p, hand);
        if (accepts(held.copyWithCount(1))) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            int n = p.isShiftKeyDown() ? held.getCount() : 1;
            for (int i = 0; i < n && accepts(held.copyWithCount(1)); i++) {
                ItemStack one = net.axther.aftrhrs.feature.smithing.Hands.take(p, hand, 1);
                one.remove(Smithing.HEAT);
                addSolid(one);
                if (p.isCreative() && !net.axther.aftrhrs.feature.smithing.Hands.tongs(s)) break;
            }
            sound(SoundEvents.LAVA_POP, 0.6f, 0.8f);
            stirs = 0;
            sync();
            return InteractionResult.SUCCESS;
        }
        // an empty hand takes back what hasn't melted
        if (p.getItemInHand(hand).isEmpty() && p.isShiftKeyDown() && !solids.isEmpty()) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            ItemStack back = solids.remove(solids.size() - 1);
            net.axther.aftrhrs.feature.smithing.Hands.give(p, hand, back);
            sync();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public int comparator() {
        return Math.round(15f * total() / Math.max(1, capacity()));
    }

    // ------------------------------------------------------------------ save

    @Override
    protected void save(ValueOutput out) {
        out.store("Fluids", Codec.unboundedMap(Codec.STRING, Codec.INT), new LinkedHashMap<>(fluids));
        out.store("Solids", ItemStack.OPTIONAL_CODEC.listOf(), List.copyOf(solids));
        out.putInt("Melting", melting);
        out.putInt("Stirs", stirs);
        out.putFloat("Shown", shownHeat);
    }

    @Override
    protected void load(ValueInput in) {
        fluids.clear();
        in.read("Fluids", Codec.unboundedMap(Codec.STRING, Codec.INT)).ifPresent(fluids::putAll);
        solids.clear();
        in.read("Solids", ItemStack.OPTIONAL_CODEC.listOf()).ifPresent(l -> l.forEach(s -> {
            if (!s.isEmpty()) solids.add(s.copy());
        }));
        melting = in.getIntOr("Melting", 0);
        stirs = in.getIntOr("Stirs", 0);
        shownHeat = in.getFloatOr("Shown", 20);
    }

    @Override
    protected List<ItemStack> contents() {
        // molten metal spills and is lost; what hasn't melted drops
        return new ArrayList<>(solids);
    }

    // ------------------------------------------------------------------ hoppers feed it (one input, nothing to take)

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(stack.copyWithCount(1));
    }

    @Override
    public boolean canTakeItem(net.minecraft.world.Container into, int slot, ItemStack stack) {
        return false;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (stack.isEmpty() || level == null || level.isClientSide()) return;
        for (int i = 0; i < stack.getCount(); i++) {
            ItemStack one = stack.copyWithCount(1);
            if (!accepts(one)) break;
            one.remove(net.axther.aftrhrs.feature.smithing.Smithing.HEAT);
            addSolid(one);
        }
        stirs = 0;
        sync();
    }

    @Override
    public boolean stillValid(Player p) {
        return false;
    }

    @Override
    public void clearContent() {
    }

    public float shownHeat() {
        return shownHeat;
    }

    public int stirs() {
        return stirs;
    }
}
