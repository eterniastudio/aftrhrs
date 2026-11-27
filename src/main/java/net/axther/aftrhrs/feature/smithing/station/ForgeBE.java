package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.Hands;
import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.MoldItem;
import net.axther.aftrhrs.feature.smithing.PartInfo;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.Workshop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Forge Hearth (SMITHING.md 3.1): fuel in the coal bed (lit with flint and steel or a fire charge; lava lights
 * itself), heat in degrees that climbs to 70% of the fuel's cap on its own and to the full cap while the bellows work,
 * and up to three workpieces heating on the coals (parts, ingots, clay molds to fire). A part left far above its
 * working window scales (-1 star every 5 s). The crucible on top takes this heat.
 */
public final class ForgeBE extends StationBE {
    public static final int SLOTS = 3, MAX_FUEL = 16;
    /** Workpiece spots on the coal bed, model pixels (north-facing). */
    public static final float[][] SPOTS = {{5.5f, 5f, 6f}, {8f, 5f, 6f}, {10.5f, 5f, 6f}};

    private ItemStack fuel = ItemStack.EMPTY;
    private final ItemStack[] work = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    private final int[] scale = new int[SLOTS];
    private int burn, cap;
    private float heat = Heat.AIR, sentHeat = Heat.AIR;
    private long pumpedUntil;
    private boolean lit;

    public ForgeBE(BlockPos pos, BlockState state) {
        super(Station.FORGE, pos, state);
    }

    public float heat() {
        return heat;
    }

    public boolean lit() {
        return lit && burn > 0;
    }

    public int cap() {
        return cap;
    }

    public ItemStack fuel() {
        return fuel;
    }

    public ItemStack work(int i) {
        return work[i];
    }

    public boolean pumping() {
        return level != null && level.getGameTime() < pumpedUntil;
    }

    /** One pump of the bellows (or a fan's breath): full heat for 2 s, and fuel burns twice as fast. */
    public void pump() {
        if (level == null) return;
        pumpedUntil = level.getGameTime() + 40;
        if (level instanceof ServerLevel sl && lit()) {
            Vec3 c = front(0.35);
            sl.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 6, 0.2, 0.1, 0.2, 0.02);
        }
        sync();
    }

    private Vec3 front(double out) {
        Direction f = facing();
        return Vec3.atCenterOf(worldPosition).add(f.getStepX() * out, -0.2, f.getStepZ() * out);
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void serverTick(ServerLevel level) {
        long now = level.getGameTime();
        boolean pumped = now < pumpedUntil;
        if (lit && burn <= 0) {
            if (!fuel.isEmpty()) feed(level);
            else lit = false;
        }
        if (lit()) burn -= pumped ? 2 : 1;
        float target = lit() ? cap * (pumped ? 1f : 0.7f) : Heat.AIR;
        float k = target > heat ? 1f / 200f * Workshop.heatSpeed() : 1f / 600f;
        heat += (target - heat) * k;
        if (now % 5 == 0) heatWork(level, now);
        // the coal bed and light follow the fire
        BlockState s = getBlockState();
        StationBlock.Coal coal = lit() ? StationBlock.Coal.LIT : fuel.isEmpty() ? StationBlock.Coal.EMPTY : StationBlock.Coal.COLD;
        if (s.hasProperty(StationBlock.COAL) && s.getValue(StationBlock.COAL) != coal) level.setBlock(worldPosition, s.setValue(StationBlock.COAL, coal), 3);
        if (Math.abs(heat - sentHeat) > 8 || now % 100 == 0 && Math.abs(heat - sentHeat) > 0.5f) {
            sentHeat = heat;
            sync();
        }
    }

    private void feed(ServerLevel level) {
        Smelt.Fuel f = Smelt.fuel(fuel, level);
        if (f == null) {
            lit = false;
            return;
        }
        burn = f.ticks();
        cap = f.cap();
        fuel.shrink(1);
        if (f.remainder() != null) Block.popResource(level, worldPosition.above(), new ItemStack(f.remainder()));
        if (fuel.isEmpty()) fuel = ItemStack.EMPTY;
        sync();
    }

    private void heatWork(ServerLevel level, long now) {
        boolean changed = false;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack w = work[i];
            if (w.isEmpty()) continue;
            Heat.approach(w, heat, 5, now);
            float t = Heat.of(w, now);
            // clay molds fire at 600 degrees for 10 s
            if (w.getItem() instanceof MoldItem mi && mi.kind == MoldItem.Kind.CLAY_UNFIRED && t >= 600) {
                int fired = w.getOrDefault(Smithing.FIRING, 0) + 5;
                if (fired >= 200) {
                    ItemStack out = MoldItem.of(MoldItem.Kind.CLAY, w.getOrDefault(Smithing.MOLD, "ingot"));
                    Heat.set(out, t, now);
                    work[i] = out;
                    sound(SoundEvents.FIRECHARGE_USE, 0.4f, 1.6f);
                } else {
                    w.set(Smithing.FIRING, fired);
                }
                changed = true;
                continue;
            }
            // scale: far above its window a part loses a star every 5 s
            int[] window = windowOf(w);
            if (window != null && w.getItem() instanceof PartItem && t > window[1] + 60) {
                scale[i] += 5;
                if (scale[i] >= 100) {
                    scale[i] = 0;
                    PartInfo info = w.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
                    if (info.stars() > 1) {
                        w.set(Smithing.PART, info.withStars(info.stars() - 1));
                        Vec3 c = spot(i);
                        level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.1, c.z, 4, 0.05, 0.05, 0.05, 0.01);
                        sound(SoundEvents.FIRE_EXTINGUISH, 0.3f, 1.8f);
                    }
                }
            } else {
                scale[i] = 0;
            }
            changed = true;
        }
        if (changed && now % 20 == 0) sync();
    }

    /** The heat window of a workpiece's metal (parts and ingots), or null. */
    public static int[] windowOf(ItemStack s) {
        String metal = s.get(Smithing.MATERIAL);
        if (metal == null) metal = Smelt.ingotMetal(s);
        SmithingDefs.Material m = SmithingDefs.material(metal);
        return m == null ? null : m.window();
    }

    /** World position of a workpiece spot. */
    public Vec3 spot(int i) {
        float[] p = SPOTS[i];
        return Workshop.local(worldPosition, facing(), p[0], p[1], p[2]);
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack inHand = p.getItemInHand(hand);
        ItemStack held = Hands.held(p, hand);
        // light the fire
        if ((inHand.is(Items.FLINT_AND_STEEL) || inHand.is(Items.FIRE_CHARGE)) && !lit() && !fuel.isEmpty()) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            lit = true;
            burn = 0;
            feed((ServerLevel) level);
            lit = burn > 0;
            if (inHand.is(Items.FLINT_AND_STEEL)) inHand.hurtAndBreak(1, p, hand);
            else if (!p.isCreative()) inHand.shrink(1);
            sound(SoundEvents.FLINTANDSTEEL_USE, 1f, 1f);
            return InteractionResult.SUCCESS;
        }
        // fuel
        Smelt.Fuel f = inHand.isEmpty() || Hands.tongs(inHand) ? null : Smelt.fuel(inHand, level);
        if (f != null && !Workshop.workable(inHand)) {
            boolean lava = inHand.is(Items.LAVA_BUCKET);
            if (lava ? !fuel.isEmpty() : !fuel.isEmpty() && (!ItemStack.isSameItemSameComponents(fuel, inHand) || fuel.getCount() >= MAX_FUEL)) {
                return InteractionResult.PASS;
            }
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            if (lava) {
                // lava lights itself and burns hottest
                lit = true;
                burn = f.ticks();
                cap = f.cap();
                if (!p.isCreative()) p.setItemInHand(hand, new ItemStack(Items.BUCKET));
                sound(SoundEvents.BUCKET_EMPTY_LAVA, 1f, 1f);
            } else {
                int n = p.isShiftKeyDown() ? Math.min(inHand.getCount(), MAX_FUEL - fuel.getCount()) : 1;
                if (fuel.isEmpty()) fuel = inHand.copyWithCount(n);
                else fuel.grow(n);
                if (!p.isCreative()) inHand.shrink(n);
                sound(SoundEvents.STONE_PLACE, 0.6f, 0.8f);
            }
            sync();
            return InteractionResult.SUCCESS;
        }
        // a workpiece onto the coals
        if (!held.isEmpty() && Workshop.workable(held)) {
            int slot = freeSlot(hit);
            if (slot < 0) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            ItemStack piece = Hands.take(p, hand, 1);
            if (!piece.has(Smithing.HEAT)) Heat.set(piece, Heat.AIR + 41, now());
            work[slot] = piece;
            scale[slot] = 0;
            sound(SoundEvents.CHAIN_PLACE, 0.6f, 0.9f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // take a workpiece off (tongs, or a bare hand that burns)
        if (Hands.free(p, hand)) {
            int slot = takenSlot(hit);
            if (slot >= 0) {
                if (client()) return InteractionResult.SUCCESS;
                if (!allowed(p)) return InteractionResult.FAIL;
                ItemStack piece = work[slot];
                Heat.set(piece, Heat.of(piece, now()), now());
                work[slot] = ItemStack.EMPTY;
                Hands.give(p, hand, piece);
                sound(SoundEvents.CHAIN_HIT, 0.6f, 1.2f);
                sync();
                return InteractionResult.SUCCESS;
            }
            if (p.isShiftKeyDown() && !fuel.isEmpty() && !lit() && inHand.isEmpty()) {
                if (client()) return InteractionResult.SUCCESS;
                if (!allowed(p)) return InteractionResult.FAIL;
                Hands.give(p, hand, fuel);
                fuel = ItemStack.EMPTY;
                sync();
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    /** The empty spot closest to where the player clicked, or -1. */
    private int freeSlot(BlockHitResult hit) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < SLOTS; i++) {
            if (!work[i].isEmpty()) continue;
            double d = spot(i).distanceToSqr(hit.getLocation());
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private int takenSlot(BlockHitResult hit) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < SLOTS; i++) {
            if (work[i].isEmpty()) continue;
            double d = spot(i).distanceToSqr(hit.getLocation());
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public void animate(RandomSource r) {
        if (level == null || !lit()) return;
        Direction f = facing();
        Vec3 c = Workshop.local(worldPosition, f, 3 + r.nextFloat() * 10, 5.5f, 1 + r.nextFloat() * 6);
        if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.FLAME, c.x, c.y, c.z, 0, 0.01, 0);
        if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.SMALL_FLAME, c.x, c.y, c.z, 0, 0.02, 0);
        if (level.getBlockState(worldPosition.above()).isAir() && r.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, worldPosition.getX() + 0.3 + r.nextFloat() * 0.4, worldPosition.getY() + 1.05,
                    worldPosition.getZ() + 0.3 + r.nextFloat() * 0.4, 0, 0.05, 0);
        }
        if (pumping() && r.nextInt(2) == 0) {
            Vec3 m = Workshop.local(worldPosition, f, 8, 6, 2);
            level.addParticle(ParticleTypes.LAVA, m.x, m.y, m.z, 0, 0, 0);
        }
    }

    @Override
    public int comparator() {
        return Math.max(0, Math.min(15, Math.round(heat / 100f)));
    }

    // ------------------------------------------------------------------ save

    @Override
    protected void save(ValueOutput out) {
        writeStack(out, "Fuel", fuel);
        for (int i = 0; i < SLOTS; i++) writeStack(out, "Work" + i, work[i]);
        out.putInt("Burn", burn);
        out.putInt("Cap", cap);
        out.putFloat("Heat", heat);
        out.putBoolean("Lit", lit);
        out.putLong("Pumped", pumpedUntil);
    }

    @Override
    protected void load(ValueInput in) {
        fuel = readStack(in, "Fuel");
        for (int i = 0; i < SLOTS; i++) work[i] = readStack(in, "Work" + i);
        burn = in.getIntOr("Burn", 0);
        cap = in.getIntOr("Cap", 0);
        heat = in.getFloatOr("Heat", Heat.AIR);
        lit = in.getBooleanOr("Lit", false);
        pumpedUntil = in.getLongOr("Pumped", 0);
    }

    @Override
    protected List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        out.add(fuel);
        for (ItemStack w : work) out.add(w);
        return out;
    }
}
