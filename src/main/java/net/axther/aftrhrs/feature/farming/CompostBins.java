package net.axther.aftrhrs.feature.farming;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.feature.coast.SpotData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Compost bins (storage furniture): a right-click holding a plant puts one in; every {@code items-per-compost} plants
 * start a batch that is ready {@code minutes} later. A right-click with an empty hand takes the ready compost;
 * otherwise the bin's own storage opens as before. State: {@link SpotData} at the bin ({@code feather:compost_fill},
 * {@code feather:compost_batches} = ready times, {@code feather:compost_ready}, the old base-entity keys); time is only
 * read on a click, so idle bins cost nothing.
 */
final class CompostBins {
    static final String FILL = "feather:compost_fill", BATCHES = "feather:compost_batches", READY = "feather:compost_ready";

    private final FarmingModule m;

    CompostBins(FarmingModule module) {
        this.m = module;
    }

    private record State(int fill, List<Long> batches, int ready) {
    }

    private State read(CompoundTag t) {
        int fill = t.getInt(FILL).orElse(0);
        int ready = t.getInt(READY).orElse(0);
        List<Long> batches = new ArrayList<>();
        String raw = t.getString(BATCHES).orElse(null);
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(",")) {
                try {
                    batches.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        long now = m.now();
        int done = 0;
        for (int i = batches.size() - 1; i >= 0; i--) {
            if (batches.get(i) <= now) {
                batches.remove(i);
                done++;
            }
        }
        return new State(fill, batches, ready + done);
    }

    private State read(ServerLevel level, BlockPos pos) {
        return read(SpotData.get(level, pos));
    }

    private void write(ServerLevel level, BlockPos pos, State s) {
        CompoundTag t = SpotData.get(level, pos);
        if (s.fill() > 0) t.putInt(FILL, s.fill());
        else t.remove(FILL);
        if (s.ready() > 0) t.putInt(READY, s.ready());
        else t.remove(READY);
        if (s.batches().isEmpty()) t.remove(BATCHES);
        else {
            StringBuilder b = new StringBuilder();
            for (long x : s.batches()) {
                if (!b.isEmpty()) b.append(',');
                b.append(x);
            }
            t.putString(BATCHES, b.toString());
        }
        SpotData.put(level, pos, t);
    }

    private boolean accepts(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        String id = Coast.id(s);
        if (id != null && id.equals(m.config().compostId)) return false;
        if (FarmingModule.qualityOf(s) == 2) return false;   // never compost a Prize crop by accident
        for (FarmConfig.ItemRule r : m.config().binAccepts) if (r.matches(s, id)) return true;
        return false;
    }

    /** True when the click was used (the storage stays closed). */
    boolean click(ServerPlayer p, FurnitureContext ctx, ItemStack held) {
        if (p.isSecondaryUseActive() && held.isEmpty()) return false;   // turning it
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.origin();
        State s = read(level, pos);
        FarmConfig cfg = m.config();
        if (accepts(held)) {
            if (!m.allowed(p, level, pos, ClaimAction.CONTAINER)) return true;
            if (s.batches().size() + s.ready() >= cfg.binMaxBatches) {
                m.bar(p, "bin-full");
                return true;
            }
            int fill = s.fill() + 1;
            List<Long> batches = new ArrayList<>(s.batches());
            boolean started = false;
            if (fill >= cfg.binItems) {
                fill = 0;
                long start = batches.isEmpty() ? m.now() : Math.max(m.now(), batches.get(batches.size() - 1));
                batches.add(start + cfg.binMs);
                started = true;
            }
            write(level, pos, new State(fill, batches, s.ready()));
            Coast.takeOne(p, InteractionHand.MAIN_HAND);
            Coast.swing(p);
            Vec3 at = Vec3.atBottomCenterOf(pos).add(0, 0.6, 0);
            level.sendParticles(ParticleTypes.COMPOSTER, at.x, at.y, at.z, 8, 0.25, 0.2, 0.25, 0);
            Coast.sound(level, at, started ? "block.composter.fill_success" : "block.composter.fill", SoundSource.BLOCKS, 0.8f, 1f);
            if (started) m.bar(p, "bin-batch", Text.unparsed("minutes", Math.max(1, Math.round(cfg.binMs / 60000.0))));
            else m.bar(p, "bin-fill", Text.unparsed("fill", fill), Text.unparsed("need", cfg.binItems));
            return true;
        }
        if (held.isEmpty() && s.ready() > 0) {
            if (!m.allowed(p, level, pos, ClaimAction.CONTAINER)) return true;
            List<ItemStack> out = compost(s.ready());
            if (out == null) {
                m.bar(p, "not-ready");
                return true;
            }
            write(level, pos, new State(s.fill(), s.batches(), 0));
            for (ItemStack st : out) Coast.give(p, st);
            Coast.swing(p);
            Coast.sound(level, pos, "block.composter.empty", 0.8f, 1f);
            m.bar(p, "bin-taken", Text.unparsed("count", s.ready()));
            return true;
        }
        if (s.fill() > 0 || !s.batches().isEmpty()) {
            long left = s.batches().isEmpty() ? 0 : Math.max(0, s.batches().get(0) - m.now());
            m.bar(p, "bin-status", Text.unparsed("fill", s.fill()), Text.unparsed("need", cfg.binItems),
                    Text.unparsed("batches", s.batches().size()), Text.unparsed("seconds", (left + 999) / 1000));
        }
        write(level, pos, s);
        return false;
    }

    /** {@code n} compost as stacks (more than one stack when max-batches is set above the stack size); null without the item. */
    private List<ItemStack> compost(int n) {
        List<ItemStack> out = new ArrayList<>();
        while (n > 0) {
            ItemStack s = m.build(m.config().compostId, n);
            if (s == null) return out.isEmpty() ? null : out;
            out.add(s);
            n -= s.getCount();
        }
        return out;
    }

    /** A broken bin drops its ready compost (batches in progress are lost, like a composter). */
    void broken(ServerLevel level, BlockPos pos) {
        State s = read(SpotData.take(level, pos));
        if (s.ready() <= 0) return;
        List<ItemStack> out = compost(s.ready());
        if (out != null) for (ItemStack st : out) Coast.drop(level, Vec3.atCenterOf(pos), st);
    }

    /** A Paper-era bin became a block: its base entity's state moves to the block. */
    void migrate(ServerLevel level, BlockPos pos, CompoundTag bv) {
        CompoundTag t = new CompoundTag();
        for (String k : List.of(FILL, BATCHES, READY)) if (bv.contains(k)) t.put(k, bv.get(k).copy());
        if (t.isEmpty()) return;
        if (!Coast.matches(m.config().binIds, ArcanaContent.id(level.getBlockState(pos)))) {
            // the piece couldn't be placed: its ready compost drops where it stood
            State s = read(t);
            List<ItemStack> out = s.ready() > 0 ? compost(s.ready()) : null;
            if (out != null) for (ItemStack st : out) Coast.drop(level, Vec3.atCenterOf(pos), st);
            return;
        }
        SpotData.put(level, pos, t);
    }

    String describeNear(ServerLevel level, BlockPos at) {
        StringBuilder b = new StringBuilder("bins");
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-6, -4, -6), at.offset(6, 4, 6))) {
            String id = ArcanaContent.id(level.getBlockState(p));
            if (id == null || !Coast.matches(m.config().binIds, id) || !ArcanaContent.origin(level.getBlockState(p), p).equals(p)) continue;
            State s = read(level, p);
            b.append(" | ").append(id).append(" fill=").append(s.fill()).append(" batches=").append(s.batches().size()).append(" ready=").append(s.ready());
        }
        return b.toString();
    }
}
