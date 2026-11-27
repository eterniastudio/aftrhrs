package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.HandToolItem;
import net.axther.aftrhrs.feature.smithing.Hands;
import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.Molten;
import net.axther.aftrhrs.feature.smithing.MoldItem;
import net.axther.aftrhrs.feature.smithing.PartInfo;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.SmithingProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
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
 * The Casting Bench (SMITHING.md 3.3). Making molds: a molding box packed with 4 sand takes an imprint of any part (or
 * an ingot or nugget); 4 clay balls shaped round a part make an unfired clay mold for the forge. Casting: pour from a
 * ladle (or a spout) until the mold is full, let it cool (steam when ready), knock the piece out with a hammer: a rough
 * part (1 star) or ingots. Pour-over: molten metal over a finished part clads it (emberforged steel over bone makes
 * blazebone).
 */
public final class CastingBE extends StationBE {
    public static final int COOL_TICKS = 100;

    /** A mold (sand or fired clay) or a molding box, or a part to clad. */
    private ItemStack mold = ItemStack.EMPTY;
    private int sand, clay;
    private String fluid = "";
    private int units, cool;

    public CastingBE(BlockPos pos, BlockState state) {
        super(Station.CASTING_BENCH, pos, state);
    }

    public ItemStack mold() {
        return mold;
    }

    public int sand() {
        return sand;
    }

    public int clay() {
        return clay;
    }

    public String fluid() {
        return fluid;
    }

    public int units() {
        return units;
    }

    public int cool() {
        return cool;
    }

    /** Units the thing on the bench takes when full, 0 when nothing can be poured. */
    public int needed() {
        if (mold.getItem() instanceof MoldItem m && m.kind != MoldItem.Kind.CLAY_UNFIRED) return MoldItem.units(MoldItem.shape(mold));
        if (mold.getItem() instanceof PartItem pi && !mold.getOrDefault(Smithing.PART, PartInfo.DEFAULT).rough()) return pi.part.cost() * Smelt.INGOT;
        return 0;
    }

    public boolean full() {
        return needed() > 0 && units >= needed();
    }

    /** Can {@code f} go into what's on the bench? */
    private boolean fits(String f) {
        if (Smelt.FISH_OIL.equals(f)) return false;
        if (!fluid.isEmpty() && !fluid.equals(f)) return false;
        SmithingDefs.Material m = SmithingDefs.material(f);
        if (m == null) return false;
        if (mold.getItem() instanceof MoldItem) {
            String shape = MoldItem.shape(mold);
            if (shape.equals("ingot") || shape.equals("nugget")) return !Smelt.ingot(f, shape.equals("nugget")).isEmpty();
            SmithingDefs.Part p = SmithingDefs.part(shape);
            return p != null && m.fits(p.role()) && p.made().contains("cast");
        }
        if (mold.getItem() instanceof PartItem pi) return clad(pi.part, mold.get(Smithing.MATERIAL), f) != null;
        return false;
    }

    /** What pouring {@code metal} over a {@code base} part makes (a material id), or null. */
    static String clad(SmithingDefs.Part part, String base, String metal) {
        if (base == null || base.equals(metal)) return null;
        if (base.equals("bone") && metal.equals("emberforged_steel") && part.role().equals("head")) return "blazebone";
        SmithingDefs.Material b = SmithingDefs.material(base), m = SmithingDefs.material(metal);
        if (b == null || m == null || b.metal() || !m.metal() || !m.fits(part.role())) return null;
        return metal;
    }

    /** Pours in up to {@code m.units()}; returns how many units it took (spout and ladle). */
    public int accept(Molten m) {
        if (m == null || cool > 0 || full() || !fits(m.fluid())) return 0;
        int take = Math.min(m.units(), needed() - units);
        if (take <= 0) return 0;
        fluid = m.fluid();
        units += take;
        if (full()) cool = COOL_TICKS;
        sync();
        return take;
    }

    @Override
    public void serverTick(ServerLevel level) {
        if (cool > 0) {
            cool--;
            if (cool == 0) {
                sound(SoundEvents.LAVA_EXTINGUISH, 0.6f, 1.4f);
                Vec3 c = Vec3.atLowerCornerOf(worldPosition).add(0.5, 0.65, 0.5);
                level.sendParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 8, 0.2, 0.05, 0.2, 0.01);
                sync();
            } else if (cool % 20 == 0) {
                Vec3 c = Vec3.atLowerCornerOf(worldPosition).add(0.5, 0.65, 0.5);
                level.sendParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 1, 0.15, 0.02, 0.15, 0.005);
            }
        }
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack s = p.getItemInHand(hand);
        ItemStack held = Hands.held(p, hand);
        // pour from a ladle
        if (s.getItem() instanceof HandToolItem t && t.kind == HandToolItem.Kind.LADLE) {
            Molten load = HandToolItem.load(s);
            if (load == null || !fits(load.fluid()) || full() || cool > 0) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            int took = accept(load);
            HandToolItem.setLoad(s, load.fluid(), load.units() - took);
            sound(SoundEvents.BUCKET_EMPTY_LAVA, 0.7f, 1.3f);
            return InteractionResult.SUCCESS;
        }
        // knock a cooled casting out with a hammer
        if (full() && cool == 0 && hammer(s)) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            knockOut(p, hand);
            s.hurtAndBreak(1, p, hand);
            return InteractionResult.SUCCESS;
        }
        if (units > 0) return InteractionResult.PASS; // a pour in progress: hands off
        // molding box onto an empty bench, then sand into it, then press a template
        if (mold.isEmpty() && clay == 0 && s.is(Smithing.MOLDING_BOX)) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            mold = Hands.take(p, hand, 1);
            sand = 0;
            sound(SoundEvents.WOOD_PLACE, 0.8f, 1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        if (mold.is(Smithing.MOLDING_BOX) && sand < 4 && s.is(ItemTags.SAND)) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            if (!p.isCreative()) s.shrink(1);
            sand++;
            sound(SoundEvents.SAND_PLACE, 0.8f, 1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        String shape = templateShape(held);
        if (mold.is(Smithing.MOLDING_BOX) && sand >= 4 && shape != null) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            mold = MoldItem.of(MoldItem.Kind.SAND, shape);
            sand = 0;
            sound(SoundEvents.SAND_HIT, 1f, 0.8f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // clay: 4 balls, then a template, makes an unfired clay mold
        if (mold.isEmpty() && clay < 4 && s.is(Items.CLAY_BALL)) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            if (!p.isCreative()) s.shrink(1);
            clay++;
            sound(SoundEvents.MUD_PLACE, 0.8f, 1.1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        if (mold.isEmpty() && clay >= 4 && shape != null) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            mold = MoldItem.of(MoldItem.Kind.CLAY_UNFIRED, shape);
            clay = 0;
            sound(SoundEvents.MUD_HIT, 1f, 0.9f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // a ready mold, or a finished part to clad
        if (mold.isEmpty() && clay == 0 && (held.getItem() instanceof MoldItem mi && mi.kind != MoldItem.Kind.CLAY_UNFIRED && !MoldItem.shape(held).isEmpty()
                || held.getItem() instanceof PartItem && !held.getOrDefault(Smithing.PART, PartInfo.DEFAULT).rough())) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            mold = Hands.take(p, hand, 1);
            sound(SoundEvents.STONE_PLACE, 0.8f, 1.1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // take what's on the bench
        if (Hands.free(p, hand) && (!mold.isEmpty() || clay > 0)) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            if (!mold.isEmpty()) {
                Hands.give(p, hand, mold);
                if (mold.is(Smithing.MOLDING_BOX) && sand > 0) p.getInventory().add(new ItemStack(Items.SAND, sand));
            } else {
                Hands.give(p, hand, new ItemStack(Items.CLAY_BALL, clay));
            }
            mold = ItemStack.EMPTY;
            sand = clay = 0;
            sync();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    static boolean hammer(ItemStack s) {
        var b = s.get(Smithing.BUILD);
        if (b == null || s.has(Smithing.BROKEN)) return false;
        SmithingDefs.ToolType t = SmithingDefs.tool(b.tool());
        return t != null && t.has("forge");
    }

    /** The imprint a template makes: a part's shape, an ingot, a nugget; null if it can't be a template. */
    static String templateShape(ItemStack s) {
        if (s.getItem() instanceof PartItem pi) return pi.part.made().contains("cast") ? pi.part.id() : null;
        if (Smelt.ingotMetal(s) != null) return "ingot";
        if (s.is(Items.IRON_NUGGET) || s.is(Items.GOLD_NUGGET) || s.is(Items.COPPER_NUGGET)
                || s.getItem() instanceof net.axther.aftrhrs.feature.smithing.MetalItem mi && mi.nugget) return "nugget";
        return null;
    }

    private void knockOut(Player p, InteractionHand hand) {
        long now = now();
        List<ItemStack> out = new ArrayList<>();
        if (mold.getItem() instanceof PartItem pi) {
            // pour-over: the part takes the metal, and keeps its old material's trait
            String base = mold.get(Smithing.MATERIAL);
            String result = clad(pi.part, base, fluid);
            ItemStack clad = mold.copy();
            PartInfo info = clad.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
            clad.set(Smithing.MATERIAL, result);
            clad.set(Smithing.PART, result.equals("blazebone") ? info : info.withExtra(base).withQuench(""));
            Heat.set(clad, 500, now);
            out.add(clad);
            mold = ItemStack.EMPTY;
        } else {
            String shape = MoldItem.shape(mold);
            if (shape.equals("ingot") || shape.equals("nugget")) {
                out.add(Smelt.ingot(fluid, shape.equals("nugget")));
            } else {
                SmithingDefs.Part part = SmithingDefs.part(shape);
                ItemStack rough = Smithing.part(shape, fluid, 1);
                if (!rough.isEmpty()) {
                    rough.set(Smithing.PART, new PartInfo(1, "", "", true));
                    rough.setCount(part == null ? 1 : part.makes());
                    Heat.set(rough, 450, now);
                    out.add(rough);
                }
            }
            MoldItem mi = (MoldItem) mold.getItem();
            if (mi.kind == MoldItem.Kind.SAND) {
                out.add(new ItemStack(Smithing.MOLDING_BOX)); // the sand is spent; the box comes back
                mold = ItemStack.EMPTY;
            } else {
                int dmg = mold.getDamageValue() + 1;
                if (dmg >= mold.getMaxDamage()) {
                    mold = ItemStack.EMPTY;
                    sound(SoundEvents.DECORATED_POT_SHATTER, 1f, 1f);
                } else {
                    mold.setDamageValue(dmg);
                }
            }
        }
        fluid = "";
        units = 0;
        sound(SoundEvents.ANVIL_LAND, 0.4f, 1.6f);
        for (ItemStack o : out) {
            if (o.isEmpty()) continue;
            if (o.getItem() instanceof PartItem && p instanceof ServerPlayer sp) SmithingProgress.cast(sp);
            Hands.give(p, hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, o);
        }
        sync();
    }

    // ------------------------------------------------------------------ save

    @Override
    protected void save(ValueOutput out) {
        writeStack(out, "Mold", mold);
        out.putInt("Sand", sand);
        out.putInt("Clay", clay);
        out.putString("Fluid", fluid);
        out.putInt("Units", units);
        out.putInt("Cool", cool);
    }

    @Override
    protected void load(ValueInput in) {
        mold = readStack(in, "Mold");
        sand = in.getIntOr("Sand", 0);
        clay = in.getIntOr("Clay", 0);
        fluid = in.getStringOr("Fluid", "");
        units = in.getIntOr("Units", 0);
        cool = in.getIntOr("Cool", 0);
    }

    @Override
    protected List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        out.add(mold);
        if (sand > 0) out.add(new ItemStack(Items.SAND, sand));
        if (clay > 0) out.add(new ItemStack(Items.CLAY_BALL, clay));
        return out;
    }
}
