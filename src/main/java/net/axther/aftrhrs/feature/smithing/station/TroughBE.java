package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.HandToolItem;
import net.axther.aftrhrs.feature.smithing.Hands;
import net.axther.aftrhrs.feature.smithing.Heat;
import net.axther.aftrhrs.feature.smithing.Molten;
import net.axther.aftrhrs.feature.smithing.PartInfo;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smelt;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The Quench Trough (SMITHING.md 3.5). Water (bucket or bottles), fish oil (from a ladle, rendered in the crucible),
 * brine (water + dried kelp) or frost brine (water + a permafrost shard). Dunk a hot part to set it: water +10%
 * durability (but a tier-3+ part above its window can crack: -1 star), fish oil +5% attack speed and safe, brine +5%
 * mining speed and safe, frost brine gives steels Frost-tempered. Every few dunks boil a little away.
 */
public final class TroughBE extends StationBE {
    public static final int MAX = 3;
    private String liquid = "";
    private int level, dunks;

    public TroughBE(BlockPos pos, BlockState state) {
        super(Station.TROUGH, pos, state);
    }

    public String liquid() {
        return liquid;
    }

    public int fill() {
        return level;
    }

    /** The quench a liquid gives a part. */
    static String quenchOf(String liquid) {
        return switch (liquid) {
            case "fish_oil" -> "oil";
            case "brine" -> "brine";
            case "frost_brine" -> "frost";
            default -> "water";
        };
    }

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack s = p.getItemInHand(hand);
        // water
        if (s.is(Items.WATER_BUCKET) && (liquid.isEmpty() || liquid.equals("water") && level < MAX)) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            liquid = "water";
            level = MAX;
            if (!p.isCreative()) p.setItemInHand(hand, new ItemStack(Items.BUCKET));
            sound(SoundEvents.BUCKET_EMPTY, 1f, 1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        var pot = s.get(DataComponents.POTION_CONTENTS);
        if (s.is(Items.POTION) && pot != null && pot.is(Potions.WATER) && (liquid.isEmpty() || liquid.equals("water")) && level < MAX) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            liquid = "water";
            level++;
            if (!p.isCreative()) p.setItemInHand(hand, new ItemStack(Items.GLASS_BOTTLE));
            sound(SoundEvents.BOTTLE_EMPTY, 1f, 1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        if (s.is(Items.BUCKET) && liquid.equals("water") && level == MAX) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            liquid = "";
            level = 0;
            if (!p.isCreative()) p.setItemInHand(hand, new ItemStack(Items.WATER_BUCKET));
            sound(SoundEvents.BUCKET_FILL, 1f, 1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // fish oil from a ladle
        if (s.getItem() instanceof HandToolItem t && t.kind == HandToolItem.Kind.LADLE) {
            Molten load = HandToolItem.load(s);
            if (load == null || !Smelt.FISH_OIL.equals(load.fluid()) || load.units() < 90 || level >= MAX
                    || !liquid.isEmpty() && !liquid.equals("fish_oil")) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            int add = Math.min(MAX - level, load.units() / 90);
            liquid = "fish_oil";
            level += add;
            HandToolItem.setLoad(s, load.fluid(), load.units() - add * 90);
            sound(SoundEvents.HONEY_DRINK.value(), 0.8f, 0.8f);
            sync();
            return InteractionResult.SUCCESS;
        }
        if (s.is(Smithing.FISH_OIL) && level < MAX && (liquid.isEmpty() || liquid.equals("fish_oil"))) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            liquid = "fish_oil";
            level++;
            if (!p.isCreative()) p.setItemInHand(hand, new ItemStack(Items.GLASS_BOTTLE));
            sound(SoundEvents.BOTTLE_EMPTY, 1f, 0.8f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // brine and frost brine
        if (liquid.equals("water") && level > 0 && (s.is(Items.DRIED_KELP) || s.is(Smithing.item("arcana_permafrost_shard")))) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            liquid = s.is(Items.DRIED_KELP) ? "brine" : "frost_brine";
            if (!p.isCreative()) s.shrink(1);
            sound(SoundEvents.BREWING_STAND_BREW, 0.6f, 1.3f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // dunk a hot piece
        ItemStack held = Hands.held(p, hand);
        if (level > 0 && held.has(Smithing.HEAT) && Heat.of(held, now()) > 100) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            ItemStack piece = held.copy();
            quench(p, piece);
            Hands.replace(p, hand, piece);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private void quench(Player p, ItemStack piece) {
        long now = now();
        float t = Heat.of(piece, now);
        if (piece.getItem() instanceof PartItem) {
            SmithingDefs.Material m = SmithingDefs.material(piece.get(Smithing.MATERIAL));
            int[] w = m == null ? null : m.window();
            PartInfo info = piece.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
            if (m != null && m.metal() && w != null) {
                if (t >= w[0] - 150) {
                    String q = quenchOf(liquid);
                    if (q.equals("frost") && !m.id().contains("steel")) q = "water";
                    int stars = info.stars();
                    if (q.equals("water") && m.tier() >= 3 && t > w[1]) {
                        stars = Math.max(1, stars - 1);
                        tell(p, "aftrhrs.smithing.quench.cracked");
                        sound(SoundEvents.GLASS_BREAK, 0.6f, 1.6f);
                    } else {
                        tell(p, "aftrhrs.smithing.quench." + q);
                    }
                    piece.set(Smithing.PART, info.withQuench(q).withStars(stars));
                } else {
                    tell(p, "aftrhrs.smithing.quench.too_cool");
                }
            }
        }
        Heat.clear(piece);
        sound(SoundEvents.LAVA_EXTINGUISH, 0.8f, 1.2f);
        Vec3 c = Vec3.atLowerCornerOf(worldPosition).add(0.5, 0.6, 0.5);
        ((ServerLevel) super.level).sendParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 14, 0.25, 0.1, 0.25, 0.03);
        if (++dunks >= 4) {
            dunks = 0;
            level--;
            if (level <= 0) liquid = "";
        }
        sync();
    }

    @Override
    protected void save(ValueOutput out) {
        out.putString("Liquid", liquid);
        out.putInt("Level", level);
        out.putInt("Dunks", dunks);
    }

    @Override
    protected void load(ValueInput in) {
        liquid = in.getStringOr("Liquid", "");
        level = in.getIntOr("Level", 0);
        dunks = in.getIntOr("Dunks", 0);
    }

    @Override
    protected List<ItemStack> contents() {
        return List.of();
    }
}
