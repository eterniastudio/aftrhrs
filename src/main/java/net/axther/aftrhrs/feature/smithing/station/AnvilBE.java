package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.Hands;
import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.PartInfo;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.SmithingProgress;
import net.axther.aftrhrs.feature.smithing.Strike;
import net.axther.aftrhrs.feature.smithing.ToolBuild;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Smithing Anvil (SMITHING.md 3.4). Lay a hot piece on it: a rough cast part (refined in its plan's moves), a
 * finished part (re-forged to better its stars), or ingots (forged from scratch: the plan twice; sneak-click with an
 * empty hand to choose which part). Iron with charcoal folded twice makes steel. Strike with the smithing hammer when
 * the meter's marker is in the sweet spot; sneak to strike heavy. Below the metal's working heat it won't move: reheat.
 */
public final class AnvilBE extends StationBE {
    public static final String STEEL = "steel";
    public static final int MAX_INGOTS = 8;
    /** Where the piece lies, model pixels (north-facing). */
    public static final float[] TOP = {8f, 12f, 8f};

    private ItemStack piece = ItemStack.EMPTY;
    private String target = "";
    private int carbon, move, strikes;
    private float score;
    private long meterStart, seed, lastStrike;
    private int lastResult = -1; // -1 none, 0 miss, 1 good, 2 perfect (for the client's flash)

    public AnvilBE(BlockPos pos, BlockState state) {
        super(Station.ANVIL, pos, state);
    }

    public ItemStack piece() {
        return piece;
    }

    public String target() {
        return target;
    }

    public int move() {
        return move;
    }

    public long meterStart() {
        return meterStart;
    }

    public long seed() {
        return seed;
    }

    public int carbon() {
        return carbon;
    }

    public long lastStrike() {
        return lastStrike;
    }

    public int lastResult() {
        return lastResult;
    }

    /** The moves of the current job (empty when nothing can be forged). */
    public List<String> plan() {
        if (piece.isEmpty()) return List.of();
        if (piece.getItem() instanceof PartItem pi) return pi.part.plan();
        if (target.equals(STEEL)) return List.of("fold", "fold");
        SmithingDefs.Part p = SmithingDefs.part(target);
        if (p == null || Smelt.ingotMetal(piece) == null) return List.of();
        List<String> out = new ArrayList<>(p.plan());
        out.addAll(p.plan());
        return out;
    }

    public String metal() {
        String m = piece.get(Smithing.MATERIAL);
        return m != null ? m : Smelt.ingotMetal(piece);
    }

    /** Parts these ingots can be forged into (and steel, with carbon). */
    public List<String> targets() {
        List<String> out = new ArrayList<>();
        String metal = Smelt.ingotMetal(piece);
        if (metal == null) return out;
        SmithingDefs.Material m = SmithingDefs.material(metal);
        if (metal.equals("iron") && carbon > 0) out.add(STEEL);
        for (SmithingDefs.Part p : SmithingDefs.parts().values()) {
            if (m != null && p.made().contains("anvil") && m.fits(p.role()) && p.cost() <= piece.getCount() && !p.plan().isEmpty()) out.add(p.id());
        }
        return out;
    }

    private void resetJob() {
        move = strikes = 0;
        score = 0;
        lastResult = -1;
        meterStart = now();
        seed = level == null ? 0 : level.getRandom().nextLong();
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack s = p.getItemInHand(hand);
        if (CastingBE.hammer(s)) {
            if (piece.isEmpty() || plan().isEmpty()) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            strike((ServerPlayer) p, hand, s);
            return InteractionResult.SUCCESS;
        }
        ItemStack held = Hands.held(p, hand);
        // carbon for steel
        if ((s.is(Items.CHARCOAL) || s.is(Items.COAL)) && "iron".equals(Smelt.ingotMetal(piece)) && carbon < piece.getCount()) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            if (!p.isCreative()) s.shrink(1);
            carbon++;
            target = STEEL;
            resetJob();
            sound(SoundEvents.GRAVEL_PLACE, 0.6f, 1.2f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // lay a piece down: a part, or ingots of one metal
        if (!held.isEmpty() && piece.isEmpty() && (held.getItem() instanceof PartItem && metalPart(held) || Smelt.ingotMetal(held) != null)) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            int n = held.getItem() instanceof PartItem ? 1 : p.isShiftKeyDown() ? Math.min(MAX_INGOTS, held.getCount()) : 1;
            piece = Hands.take(p, hand, n);
            carbon = 0;
            target = "";
            if (!(piece.getItem() instanceof PartItem)) {
                List<String> t = targets();
                target = t.isEmpty() ? "" : t.get(0);
            }
            resetJob();
            sound(SoundEvents.CHAIN_PLACE, 0.8f, 0.8f);
            sync();
            return InteractionResult.SUCCESS;
        }
        if (!held.isEmpty() && !piece.isEmpty() && Smelt.ingotMetal(held) != null && ItemStack.isSameItem(held, piece) && piece.getCount() < MAX_INGOTS) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            ItemStack one = Hands.take(p, hand, 1);
            float t = (Heat.of(piece, now()) * piece.getCount() + Heat.of(one, now())) / (piece.getCount() + 1);
            piece.grow(1);
            Heat.set(piece, t, now());
            if (target.isEmpty() || !targets().contains(target)) target = targets().isEmpty() ? "" : targets().get(0);
            resetJob();
            sync();
            return InteractionResult.SUCCESS;
        }
        // choose what to forge from ingots
        if (s.isEmpty() && p.isShiftKeyDown() && !piece.isEmpty() && !(piece.getItem() instanceof PartItem)) {
            List<String> t = targets();
            if (t.isEmpty()) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            int i = t.indexOf(target);
            target = t.get((i + 1) % t.size());
            resetJob();
            tell(p, "aftrhrs.smithing.anvil.target", target.equals(STEEL) ? Component.translatable("aftrhrs.smithing.material.steel")
                    : Component.translatable("aftrhrs.smithing.part." + target));
            sync();
            return InteractionResult.SUCCESS;
        }
        // take the piece
        if (Hands.free(p, hand) && !piece.isEmpty()) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            Hands.give(p, hand, piece);
            if (carbon > 0) p.getInventory().add(new ItemStack(Items.CHARCOAL, carbon));
            piece = ItemStack.EMPTY;
            carbon = 0;
            target = "";
            resetJob();
            sync();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static boolean metalPart(ItemStack s) {
        SmithingDefs.Material m = SmithingDefs.material(s.get(Smithing.MATERIAL));
        return m != null && m.metal() && s.getItem() instanceof PartItem pi && !pi.part.plan().isEmpty();
    }

    private void strike(ServerPlayer p, InteractionHand hand, ItemStack hammer) {
        long now = now();
        if (now - lastStrike < 6) return;
        lastStrike = now;
        List<String> plan = plan();
        String metal = metal();
        SmithingDefs.Material m = SmithingDefs.material(metal);
        int[] window = ForgeBE.windowOf(piece);
        float temp = Heat.of(piece, now);
        Vec3 top = net.axther.aftrhrs.feature.smithing.Workshop.local(worldPosition, facing(), TOP[0], TOP[1], TOP[2]);
        ServerLevel sl = (ServerLevel) level;
        if (m == null || window == null || temp < window[0]) {
            sound(SoundEvents.ANVIL_PLACE, 0.3f, 1.8f);
            tell(p, "aftrhrs.smithing.anvil.cold", Heat.describe(temp));
            lastResult = 0;
            sync();
            return;
        }
        boolean heavy = p.isShiftKeyDown();
        float frac = (temp - window[0]) / Math.max(1f, window[1] - window[0]);
        ToolBuild hb = hammer.get(Smithing.BUILD);
        float half = Strike.halfWidth(m.tier(), frac, heavy, Strike.hammerBonus(hb));
        float center = Strike.center(worldPosition, move, seed);
        int lat = Math.max(0, Math.min(10, p.connection.latency() / 50 + 1));
        float best = 0;
        for (int d = 0; d <= lat; d++) best = Math.max(best, Strike.score(Strike.marker(now - d, meterStart), center, half));
        strikes++;
        score += best;
        int adv = best > 0 ? heavy ? 2 : 1 : 0;
        // a careless smith still gets there: after three tries per move, misses count too
        if (adv == 0 && strikes > plan.size() * 3) adv = 1;
        move = Math.min(plan.size(), move + adv);
        lastResult = best >= 1 ? 2 : best > 0 ? 1 : 0;
        hammer.hurtAndBreak(1, p, hand);
        if (best >= 1) {
            sound(SoundEvents.ANVIL_USE, 0.6f, 1.5f);
            sl.sendParticles(ParticleTypes.LAVA, top.x, top.y + 0.05, top.z, 3, 0.05, 0.02, 0.05, 0);
            sl.sendParticles(ParticleTypes.FLAME, top.x, top.y + 0.05, top.z, 4, 0.1, 0.02, 0.1, 0.03);
        } else if (best > 0) {
            sound(SoundEvents.ANVIL_USE, 0.5f, 1.1f);
            sl.sendParticles(ParticleTypes.SMALL_FLAME, top.x, top.y + 0.05, top.z, 3, 0.08, 0.02, 0.08, 0.02);
        } else {
            sound(SoundEvents.ANVIL_LAND, 0.25f, 0.7f);
        }
        if (move >= plan.size()) finish(p, hand);
        sync();
    }

    private void finish(ServerPlayer p, InteractionHand hand) {
        long now = now();
        int stars = Strike.stars(strikes == 0 ? 0 : score / strikes);
        float temp = Heat.of(piece, now);
        String metal = metal();
        if (piece.getItem() instanceof PartItem) {
            PartInfo info = piece.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
            int s = info.rough() ? stars : Math.max(info.stars(), stars);
            piece.set(Smithing.PART, new PartInfo(s, "", info.extra(), false));
            SmithingProgress.forged(p, s);
        } else if (target.equals(STEEL)) {
            int n = Math.min(carbon, piece.getCount());
            ItemStack steel = Smelt.ingot("steel", false).copyWithCount(n);
            Heat.set(steel, temp, now);
            int left = piece.getCount() - n;
            if (left > 0) {
                ItemStack rest = piece.copyWithCount(left);
                Hands.give(p, hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, rest);
            }
            piece = steel;
            carbon = 0;
            target = "";
        } else {
            SmithingDefs.Part part = SmithingDefs.part(target);
            ItemStack made = Smithing.part(target, metal, stars);
            if (part == null || made.isEmpty()) return;
            made.setCount(part.makes());
            made.set(Smithing.PART, new PartInfo(stars, "", "", false));
            Heat.set(made, temp, now);
            int left = piece.getCount() - part.cost();
            if (left > 0) {
                ItemStack rest = piece.copyWithCount(left);
                Hands.give(p, hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, rest);
            }
            piece = made;
            target = "";
            SmithingProgress.forged(p, stars);
        }
        sound(SoundEvents.SMITHING_TABLE_USE, 1f, 1f);
        tell(p, "aftrhrs.smithing.anvil.done", stars);
        move = strikes = 0;
        score = 0;
    }

    // ------------------------------------------------------------------ save

    @Override
    protected void save(ValueOutput out) {
        writeStack(out, "Piece", piece);
        out.putString("Target", target);
        out.putInt("Carbon", carbon);
        out.putInt("Move", move);
        out.putInt("Strikes", strikes);
        out.putFloat("Score", score);
        out.putLong("Start", meterStart);
        out.putLong("Seed", seed);
        out.putLong("Last", lastStrike);
        out.putInt("Result", lastResult);
    }

    @Override
    protected void load(ValueInput in) {
        piece = readStack(in, "Piece");
        target = in.getStringOr("Target", "");
        carbon = in.getIntOr("Carbon", 0);
        move = in.getIntOr("Move", 0);
        strikes = in.getIntOr("Strikes", 0);
        score = in.getFloatOr("Score", 0);
        meterStart = in.getLongOr("Start", 0);
        seed = in.getLongOr("Seed", 0);
        lastStrike = in.getLongOr("Last", 0);
        lastResult = in.getIntOr("Result", -1);
    }

    @Override
    protected List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        out.add(piece);
        if (carbon > 0) out.add(new ItemStack(Items.CHARCOAL, carbon));
        return out;
    }
}
