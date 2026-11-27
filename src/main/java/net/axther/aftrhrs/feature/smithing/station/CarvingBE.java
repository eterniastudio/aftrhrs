package net.axther.aftrhrs.feature.smithing.station;

import net.axther.aftrhrs.feature.smithing.HandToolItem;
import net.axther.aftrhrs.feature.smithing.Hands;
import net.axther.aftrhrs.feature.smithing.PartInfo;
import net.axther.aftrhrs.feature.smithing.PartItem;
import net.axther.aftrhrs.feature.smithing.Smithing;
import net.axther.aftrhrs.feature.smithing.SmithingDefs;
import net.axther.aftrhrs.feature.smithing.SmithingProgress;
import net.axther.aftrhrs.feature.smithing.Strike;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
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
 * The Carving Bench (SMITHING.md 3.6), the cutting board. Lay down wood, stone, flint, bone, bamboo, hide, string or
 * feathers (as many as the part costs), sneak-click with an empty hand to choose the part, then cut it in rhythm with
 * the knife (the chisel for stone and flint): strikes on the beat make better stars. Wrapping: lay a carved handle or
 * grip down and click with leather, deer hide, tufthorn hide or silk.
 */
public final class CarvingBE extends StationBE {
    public static final int BEAT = 12, MAX = 8;
    private ItemStack stock = ItemStack.EMPTY;
    private String target = "";
    private int strikes;
    private float score;
    private long beatStart, lastStrike;

    public CarvingBE(BlockPos pos, BlockState state) {
        super(Station.CARVING_BENCH, pos, state);
    }

    public ItemStack stock() {
        return stock;
    }

    public String target() {
        return target;
    }

    public int strikes() {
        return strikes;
    }

    public long beatStart() {
        return beatStart;
    }

    /** The material a carving stock is, or null. */
    public static SmithingDefs.Material materialOf(ItemStack s) {
        if (s.isEmpty() || s.getItem() instanceof PartItem) return null;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        for (SmithingDefs.Material m : SmithingDefs.materials().values()) {
            if (m.source() == null || !m.source().has("carve")) continue;
            for (var e : m.source().getAsJsonArray("carve")) {
                String src = e.getAsString();
                if (src.startsWith("#")) {
                    if (s.is(TagKey.create(Registries.ITEM, Identifier.parse(src.substring(1))))) return m;
                } else if (src.equals(id)) {
                    return m;
                }
            }
        }
        return null;
    }

    /** Parts this stock can be carved into. */
    public List<String> targets() {
        List<String> out = new ArrayList<>();
        SmithingDefs.Material m = materialOf(stock);
        if (m == null) return out;
        for (SmithingDefs.Part p : SmithingDefs.parts().values()) {
            if (p.made().contains("carve") && m.fits(p.role()) && p.cost() <= stock.getCount()) out.add(p.id());
        }
        return out;
    }

    /** The part that uses everything laid down (the first such), else the first that can be made. */
    private String defaultTarget() {
        List<String> t = targets();
        for (String id : t) if (SmithingDefs.part(id).cost() == stock.getCount()) return id;
        return t.isEmpty() ? "" : t.get(t.size() - 1).isEmpty() ? "" : best(t);
    }

    private String best(List<String> t) {
        String out = t.get(0);
        for (String id : t) if (SmithingDefs.part(id).cost() > SmithingDefs.part(out).cost()) out = id;
        return out;
    }

    public int needed() {
        SmithingDefs.Part p = SmithingDefs.part(target);
        return p == null ? 0 : p.cost() * 2 + 1;
    }

    private static boolean chiselWork(SmithingDefs.Material m) {
        return m.id().equals("stone") || m.id().equals("flint");
    }

    @Override
    public InteractionResult use(Player p, InteractionHand hand, BlockHitResult hit) {
        ItemStack s = p.getItemInHand(hand);
        SmithingDefs.Material m = materialOf(stock);
        // cut
        if (s.getItem() instanceof HandToolItem t && (t.kind == HandToolItem.Kind.KNIFE || t.kind == HandToolItem.Kind.CHISEL)) {
            if (stock.isEmpty() || target.isEmpty() || m == null) return InteractionResult.PASS;
            if (chiselWork(m) != (t.kind == HandToolItem.Kind.CHISEL)) {
                if (!client()) tell(p, chiselWork(m) ? "aftrhrs.smithing.carve.need_chisel" : "aftrhrs.smithing.carve.need_knife");
                return InteractionResult.SUCCESS;
            }
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            cut((ServerPlayer) p, hand, s, m);
            return InteractionResult.SUCCESS;
        }
        // wrap a handle or grip lying on the bench
        if (stock.getItem() instanceof PartItem pi && (pi.part.role().equals("handle") || pi.part.role().equals("grip"))) {
            SmithingDefs.Material wrap = materialOf(s);
            if (wrap != null && (wrap.kind().equals("wrap") || wrap.id().equals("silk"))) {
                PartInfo info = stock.getOrDefault(Smithing.PART, PartInfo.DEFAULT);
                if (!info.extra().isEmpty() || wrap.id().equals(stock.get(Smithing.MATERIAL))) return InteractionResult.PASS;
                if (client()) return InteractionResult.SUCCESS;
                if (!allowed(p)) return InteractionResult.FAIL;
                if (!p.isCreative()) s.shrink(1);
                stock.set(Smithing.PART, info.withExtra(wrap.id()));
                sound(SoundEvents.WOOL_PLACE, 1f, 1.2f);
                tell(p, "aftrhrs.smithing.carve.wrapped", Component.translatable("aftrhrs.smithing.material." + wrap.id()));
                sync();
                return InteractionResult.SUCCESS;
            }
        }
        // choose the part
        if (s.isEmpty() && p.isShiftKeyDown() && m != null) {
            List<String> t = targets();
            if (t.isEmpty()) return InteractionResult.PASS;
            if (client()) return InteractionResult.SUCCESS;
            int i = t.indexOf(target);
            target = t.get((i + 1) % t.size());
            strikes = 0;
            score = 0;
            beatStart = now();
            tell(p, "aftrhrs.smithing.carve.target", Component.translatable("aftrhrs.smithing.part." + target));
            sync();
            return InteractionResult.SUCCESS;
        }
        // lay stock down (one more of the same, or a carved handle to wrap)
        ItemStack held = Hands.held(p, hand);
        SmithingDefs.Material hm = materialOf(held);
        boolean wrappable = held.getItem() instanceof PartItem pi && (pi.part.role().equals("handle") || pi.part.role().equals("grip"));
        if (stock.isEmpty() && (hm != null || wrappable) || hm != null && ItemStack.isSameItemSameComponents(held, stock) && stock.getCount() < MAX) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            int n = p.isShiftKeyDown() && !wrappable ? Math.min(held.getCount(), MAX - stock.getCount()) : 1;
            ItemStack in = Hands.take(p, hand, n);
            if (stock.isEmpty()) stock = in;
            else stock.grow(in.getCount());
            target = defaultTarget();
            strikes = 0;
            score = 0;
            beatStart = now();
            sound(SoundEvents.WOOD_PLACE, 0.6f, 1.1f);
            sync();
            return InteractionResult.SUCCESS;
        }
        // take it back
        if (Hands.free(p, hand) && !stock.isEmpty()) {
            if (client()) return InteractionResult.SUCCESS;
            if (!allowed(p)) return InteractionResult.FAIL;
            Hands.give(p, hand, stock);
            stock = ItemStack.EMPTY;
            target = "";
            strikes = 0;
            score = 0;
            sync();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private void cut(ServerPlayer p, InteractionHand hand, ItemStack tool, SmithingDefs.Material m) {
        long now = now();
        if (now - lastStrike < 4) return;
        lastStrike = now;
        if (strikes == 0) beatStart = now - BEAT; // the first cut sets the beat
        long off = Math.floorMod(now - beatStart, BEAT);
        long d = Math.min(off, BEAT - off);
        int lat = Math.max(0, Math.min(4, p.connection.latency() / 50));
        d = Math.max(0, d - lat);
        float s = d <= 1 ? 1f : d <= 3 ? 0.7f : 0.35f;
        strikes++;
        score += s;
        tool.hurtAndBreak(1, p, hand);
        Vec3 top = net.axther.aftrhrs.feature.smithing.Workshop.local(worldPosition, facing(), 8, 12.2f, 8);
        ((ServerLevel) level).sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stock.getItem()), top.x, top.y, top.z, 5, 0.1, 0.05, 0.1, 0.05);
        sound(chiselWork(m) ? SoundEvents.STONE_HIT : SoundEvents.WOOD_HIT, 0.8f, s >= 1 ? 1.4f : 1.0f);
        if (strikes >= needed()) {
            SmithingDefs.Part part = SmithingDefs.part(target);
            int stars = Strike.stars(score / strikes);
            ItemStack made = Smithing.part(target, m.id(), stars);
            if (part != null && !made.isEmpty()) {
                made.setCount(part.makes());
                made.set(Smithing.PART, new PartInfo(stars, "", "", false));
                stock.shrink(part.cost());
                if (stock.isEmpty()) stock = ItemStack.EMPTY;
                Hands.give(p, hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, made);
                sound(SoundEvents.UI_STONECUTTER_TAKE_RESULT, 1f, 1f);
                tell(p, "aftrhrs.smithing.carve.done", stars);
                SmithingProgress.carved(p, stars);
            }
            strikes = 0;
            score = 0;
            List<String> t = targets();
            if (!t.contains(target)) target = t.isEmpty() ? "" : t.get(0);
        }
        sync();
    }

    @Override
    protected void save(ValueOutput out) {
        writeStack(out, "Stock", stock);
        out.putString("Target", target);
        out.putInt("Strikes", strikes);
        out.putFloat("Score", score);
        out.putLong("Beat", beatStart);
    }

    @Override
    protected void load(ValueInput in) {
        stock = readStack(in, "Stock");
        target = in.getStringOr("Target", "");
        strikes = in.getIntOr("Strikes", 0);
        score = in.getFloatOr("Score", 0);
        beatStart = in.getLongOr("Beat", 0);
    }

    @Override
    protected List<ItemStack> contents() {
        return List.of(stock);
    }
}
