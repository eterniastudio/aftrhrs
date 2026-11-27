package net.axther.aftrhrs.feature.bossfx;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.axther.aftrhrs.core.Tasks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Display-entity set pieces for Sun Wukong's fight (WUKONG.md): the Seventy-Two Transformations shrines, Buddha's
 * fingers, the Five Elements Mountain, the Eight Trigrams glyphs and the boomerang staff. Like the rest of
 * {@link BossFx}: nothing touches real blocks, nothing is saved, everything is tagged and capped, and every piece can
 * be removed at once ({@link Piece#remove}) when the boss dies or leaves.
 */
public final class SetPieces {
    private SetPieces() {
    }

    /** A group of display / interaction entities and the timers animating them. */
    public static final class Piece {
        final List<Entity> parts = new ArrayList<>();
        final List<Tasks.Task> tasks = new ArrayList<>();
        /** Each display's current transformation (Display keeps its own private). */
        final Map<Display, Transformation> tf = new HashMap<>();
        public final Vec3 at;
        boolean removed;

        Piece(Vec3 at) {
            this.at = at;
        }

        public boolean removed() {
            return removed;
        }

        public void remove() {
            if (removed) return;
            removed = true;
            for (Tasks.Task t : tasks) t.cancel();
            for (Entity e : parts) BossFx.remove(e);
        }

        /** Sinks every part into the ground over {@code ticks}, then removes them. */
        public void sink(int ticks, double depth) {
            if (removed) return;
            for (Tasks.Task t : tasks) t.cancel();
            for (Entity e : parts) {
                if (e instanceof Display d && !e.isRemoved() && tf.containsKey(d)) {
                    Transformation t = tf.get(d);
                    move(this, d, new Vector3f(t.translation()).add(0, (float) -depth, 0), new Vector3f(t.scale()), new Quaternionf(t.leftRotation()), Math.max(1, ticks));
                }
            }
            Tasks.later(ticks + 2, this::remove);
        }
    }

    // ------------------------------------------------------------------ building blocks

    private static Display.BlockDisplay block(ServerLevel level, Piece piece, Vec3 at, BlockState state, Vector3f offset, Vector3f size,
                                              Quaternionf rot) {
        Display.BlockDisplay d = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }
        };
        d.setPos(at.x, at.y, at.z);
        d.setBlockState(state);
        Transformation t = new Transformation(offset, rot, size, new Quaternionf());
        d.setTransformation(t);
        piece.tf.put(d, t);
        d.setViewRange(3f);
        BossFx.add(level, d);
        piece.parts.add(d);
        return d;
    }

    private static Display.BlockDisplay block(ServerLevel level, Piece piece, Vec3 at, BlockState state, float x, float y, float z, float w, float h,
                                              float dz) {
        return block(level, piece, at, state, new Vector3f(x, y, z), new Vector3f(w, h, dz), new Quaternionf());
    }

    private static void move(Piece p, @Nullable Display d, Vector3f offset, Vector3f size, Quaternionf rot, int ticks) {
        if (d == null || d.isRemoved()) return;
        Transformation t = new Transformation(offset, rot, size, new Quaternionf());
        d.setTransformationInterpolationDelay(0);
        d.setTransformationInterpolationDuration(ticks);
        d.setTransformation(t);
        p.tf.put(d, t);
    }

    static boolean full() {
        return BossFx.liveCount() > 1300;
    }

    // ------------------------------------------------------------------ shrine (Seventy-Two Transformations)

    /**
     * A little roadside shrine (stone base, red walls, a dark door, a tiled roof with a gold finial), door toward
     * {@code yaw} (an entity yaw). {@code pole}: a flagpole with a red banner behind it (Wukong's tail, the tell from the
     * Erlang Shen story); {@code sway} makes it sway like a living tail. {@code onHit}: a player punched the shrine
     * (fakes get an invisible hitbox; the real one is Wukong's own, so pass null for it).
     */
    public static @Nullable Piece shrine(ServerLevel level, Vec3 at, float yaw, boolean pole, boolean sway, @Nullable Consumer<ServerPlayer> onHit) {
        if (!BossFx.enabled() || full()) return null;
        Piece p = new Piece(at);
        // local -z is the door side; an entity with yaw 0 faces +z
        Quaternionf facing = new Quaternionf().rotateY((float) Math.toRadians(180 - yaw));
        BlockState base = Blocks.POLISHED_ANDESITE.defaultBlockState(), wall = Blocks.DYED_TERRACOTTA.red().defaultBlockState();
        BlockState roof = Blocks.DEEPSLATE_TILES.defaultBlockState(), trim = Blocks.STRIPPED_DARK_OAK_WOOD.defaultBlockState();
        BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
        Object[][] spec = {
                {base, -0.9f, 0f, -0.9f, 1.8f, 0.3f, 1.8f},
                {wall, -0.65f, 0.3f, -0.65f, 1.3f, 1.3f, 1.3f},
                {Blocks.CONCRETE.black().defaultBlockState(), -0.28f, 0.3f, -0.68f, 0.56f, 0.9f, 0.05f},
                {gold, -0.36f, 1.22f, -0.69f, 0.72f, 0.08f, 0.04f},
                {trim, -0.75f, 0.3f, -0.75f, 0.18f, 1.35f, 0.18f},
                {trim, 0.57f, 0.3f, -0.75f, 0.18f, 1.35f, 0.18f},
                {roof, -1.05f, 1.6f, -1.05f, 2.1f, 0.22f, 2.1f},
                {roof, -0.75f, 1.82f, -0.75f, 1.5f, 0.22f, 1.5f},
                {roof, -0.42f, 2.04f, -0.42f, 0.84f, 0.2f, 0.84f},
                {gold, -0.12f, 2.24f, -0.12f, 0.24f, 0.36f, 0.24f},
        };
        List<Object[]> parts = new ArrayList<>();   // {display, local offset, size}
        float sunk = -2.6f;
        for (Object[] e : spec) {
            Vector3f off = new Vector3f((float) e[1], (float) e[2], (float) e[3]), size = new Vector3f((float) e[4], (float) e[5], (float) e[6]);
            Display.BlockDisplay d = block(level, p, at, (BlockState) e[0], facing.transform(new Vector3f(off)).add(0, sunk, 0), size, facing);
            parts.add(new Object[]{d, off, size});
        }
        Display.BlockDisplay poleD = null, flag = null;
        Vector3f poleOff = new Vector3f(-0.08f, 0, 0.95f), poleSize = new Vector3f(0.16f, 3.4f, 0.16f);
        Vector3f flagOff = new Vector3f(0.08f, 2.1f, 0.95f), flagSize = new Vector3f(0.04f, 1.1f, 0.75f);
        if (pole) {
            poleD = block(level, p, at, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), facing.transform(new Vector3f(poleOff)).add(0, sunk, 0), poleSize, facing);
            flag = block(level, p, at, Blocks.WOOL.red().defaultBlockState(), facing.transform(new Vector3f(flagOff)).add(0, sunk, 0), flagSize, facing);
            parts.add(new Object[]{poleD, poleOff, poleSize});
            parts.add(new Object[]{flag, flagOff, flagSize});
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, wall), at.x, at.y + 0.3, at.z, 30, 0.7, 0.2, 0.7, 0.1);
        level.sendParticles(ParticleTypes.POOF, at.x, at.y + 1, at.z, 20, 0.6, 0.6, 0.6, 0.05);
        // the shrine pops up out of the ground
        p.tasks.add(Tasks.later(1, () -> {
            for (Object[] e : parts) move(p, (Display) e[0], facing.transform(new Vector3f((Vector3f) e[1])), (Vector3f) e[2], facing, 6);
        }));
        if (pole && sway) {
            final Display.BlockDisplay fp = poleD, ff = flag;
            final int[] n = {0};
            p.tasks.add(Tasks.timer(10, 6, () -> {
                // the pole or flag went (unloaded with its chunk, discarded): the whole shrine goes and this timer stops
                if (fp.isRemoved() || ff.isRemoved()) {
                    p.remove();
                    return;
                }
                float a = (float) Math.toRadians(11 * Math.sin(n[0]++ * 0.9));
                // sways about the pole's foot, the flag with it
                Quaternionf r = new Quaternionf(facing).rotateZ(a).rotateX(a * 0.5f);
                Vector3f foot = facing.transform(new Vector3f(poleOff));
                move(p, fp, foot, poleSize, r, 6);
                Vector3f rel = new Vector3f(flagOff).sub(poleOff);
                move(p, ff, new Vector3f(foot).add(r.transform(rel)), flagSize, r, 6);
            }));
        }
        if (onHit != null) {
            Interaction box = new Interaction(net.minecraft.world.entity.EntityTypes.INTERACTION, level) {
                boolean hit;

                @Override
                public boolean shouldBeSaved() {
                    return false;
                }

                @Override
                public boolean skipAttackInteraction(Entity source) {
                    if (!hit && source instanceof ServerPlayer sp && BossFx.targetable(sp)) {
                        hit = true;
                        onHit.accept(sp);
                    }
                    return true;
                }
            };
            box.setPos(at.x, at.y, at.z);
            box.setWidth(1.9f);
            box.setHeight(2.5f);
            box.setResponse(true);
            BossFx.add(level, box);
            p.parts.add(box);
        }
        return p;
    }

    /** A shrine bursts: wood and tile shards, golden fire, and it is gone. */
    public static void burst(ServerLevel level, @Nullable Piece p) {
        if (p == null || p.removed) return;
        Vec3 at = p.at;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DYED_TERRACOTTA.red().defaultBlockState()), at.x, at.y + 1, at.z, 60, 0.7, 0.7, 0.7, 0.25);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE_TILES.defaultBlockState()), at.x, at.y + 1.8, at.z, 40, 0.8, 0.3, 0.8, 0.25);
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 1, at.z, 50, 0.8, 0.8, 0.8, 0.08);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 1, at.z, 2, 0.3, 0.3, 0.3, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.4f, 1.2f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.WOOD_BREAK, SoundSource.HOSTILE, 1.6f, 0.7f);
        p.remove();
    }

    // ------------------------------------------------------------------ Buddha's fingers / palm / mountain

    /** A golden finger rising out of the ground over {@code rise} ticks: a 1.6-wide jointed column with a rounded tip. */
    public static @Nullable Piece finger(ServerLevel level, Vec3 base, float height, int rise) {
        if (!BossFx.enabled() || full()) return null;
        Piece p = new Piece(base);
        BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState(), crease = Blocks.RAW_GOLD_BLOCK.defaultBlockState();
        float h = height;
        float a = h * 0.45f, b = h * 0.33f, c = h - a - b - 0.44f;
        List<Display.BlockDisplay> parts = new ArrayList<>();
        parts.add(block(level, p, base, gold, -0.8f, -h, -0.8f, 1.6f, a, 1.6f));
        parts.add(block(level, p, base, crease, -0.84f, -h + a, -0.84f, 1.68f, 0.22f, 1.68f));             // knuckle crease
        parts.add(block(level, p, base, gold, -0.76f, -h + a + 0.22f, -0.76f, 1.52f, b, 1.52f));
        parts.add(block(level, p, base, crease, -0.8f, -h + a + b + 0.22f, -0.8f, 1.6f, 0.22f, 1.6f));
        parts.add(block(level, p, base, gold, -0.68f, -h + a + b + 0.44f, -0.68f, 1.36f, c, 1.36f));
        parts.add(block(level, p, base, gold, -0.5f, -0.02f - h + h, -0.5f, 1.0f, 0.4f, 1.0f));
        parts.add(block(level, p, base, Blocks.QUARTZ_BLOCK.defaultBlockState(), -0.45f, -h + a + b + 0.44f + c * 0.35f, -0.73f, 0.9f, c * 0.6f, 0.06f));  // nail
        // the tip sits on top of the last segment
        Display.BlockDisplay tip = parts.get(5);
        move(p, tip, new Vector3f(-0.5f, -h + a + b + 0.44f + c - 0.02f, -0.5f), new Vector3f(1.0f, 0.4f, 1.0f), new Quaternionf(), 0);
        for (Display.BlockDisplay d : parts) {
            d.setBrightnessOverride(new Brightness(15, 15));
            d.setGlowingTag(true);
            d.setGlowColorOverride(0xF6C64A);
        }
        p.tasks.add(Tasks.later(1, () -> {
            for (Display.BlockDisplay d : parts) {
                Transformation t = p.tf.get(d);
                move(p, d, new Vector3f(t.translation()).add(0, h, 0), new Vector3f(t.scale()), new Quaternionf(t.leftRotation()), rise);
            }
        }));
        level.playSound(null, base.x, base.y, base.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2f, 0.6f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(BlockPos.containing(base).below())), base.x, base.y, base.z,
                40, 1, 0.2, 1, 0.2);
        return p;
    }

    /** The finger bends in toward {@code center} about its base (the palm closing on the Monkey King). */
    public static void close(@Nullable Piece finger, Vec3 center, int ticks) {
        if (finger == null || finger.removed) return;
        Vec3 d = center.subtract(finger.at);
        float yaw = (float) Math.atan2(d.x, d.z);
        Quaternionf bend = new Quaternionf().rotateY(yaw).rotateX((float) Math.toRadians(55)).rotateY(-yaw);
        for (Entity e : finger.parts) {
            if (e instanceof Display.BlockDisplay b && finger.tf.containsKey(b)) {
                Transformation t = finger.tf.get(b);
                move(finger, b, bend.transform(new Vector3f(t.translation())), new Vector3f(t.scale()), new Quaternionf(bend).mul(t.leftRotation()), ticks);
            }
        }
    }

    /**
     * The Five Elements Mountain: boulders crash down from the sky and pile up into a mossy peak over {@code center}.
     * Returns the pile; {@link #shatter} blows it apart.
     */
    public static @Nullable Piece mountain(ServerLevel level, Vec3 center, int fall) {
        if (!BossFx.enabled() || full()) return null;
        Piece p = new Piece(center);
        BlockState[] rocks = {Blocks.STONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
                Blocks.COBBLESTONE.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // {ring radius, count, boulder size, height}: bigger and lower outside, a mossy peak on top
        float[][] rings = {{2.6f, 7, 2.2f, 0f}, {1.5f, 5, 1.9f, 1.2f}, {0.6f, 3, 1.6f, 2.4f}, {0f, 1, 1.5f, 3.4f}};
        int i = 0;
        for (float[] ring : rings) {
            for (int k = 0; k < (int) ring[1]; k++) {
                double a = 2 * Math.PI * k / ring[1] + r.nextDouble(0.4);
                float s = ring[2] * (float) r.nextDouble(0.85, 1.15);
                float x = (float) (Math.cos(a) * ring[0]) - s / 2, z = (float) (Math.sin(a) * ring[0]) - s / 2;
                Quaternionf tilt = new Quaternionf().rotateY((float) r.nextDouble(Math.PI)).rotateX((float) Math.toRadians(r.nextDouble(-10, 10)));
                BlockState st = ring[0] == 0 ? Blocks.MOSS_BLOCK.defaultBlockState() : rocks[r.nextInt(rocks.length)];
                Display.BlockDisplay d = block(level, p, center, st, new Vector3f(x, ring[3] + 30, z), new Vector3f(s), tilt);
                final float fy = ring[3];
                final int delay = 1 + i * 2;
                p.tasks.add(Tasks.later(delay, () -> move(p, d, new Vector3f(x, fy - 0.6f, z), new Vector3f(s), tilt, fall)));
                p.tasks.add(Tasks.later(delay + fall, () -> {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), center.x + x + s / 2, center.y + fy, center.z + z + s / 2, 20, s / 2, 0.3,
                            s / 2, 0.2);
                    level.playSound(null, center.x, center.y, center.z, SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 2f, 0.5f);
                }));
                i++;
            }
        }
        return p;
    }

    /** Number of boulders {@link #mountain} drops (its last one lands at {@code 1 + 2 * (n - 1) + fall} ticks). */
    public static final int MOUNTAIN_ROCKS = 16;

    /** The mountain blows apart: every boulder flies outward and up, then is gone. */
    public static void shatter(ServerLevel level, @Nullable Piece p) {
        if (p == null || p.removed) return;
        for (Tasks.Task t : p.tasks) t.cancel();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Entity e : p.parts) {
            if (!(e instanceof Display.BlockDisplay b) || !p.tf.containsKey(b)) continue;
            Transformation t = p.tf.get(b);
            Vector3f out = new Vector3f(t.translation()).mul(3.5f).add((float) r.nextDouble(-2, 2), 6 + (float) r.nextDouble(4), (float) r.nextDouble(-2, 2));
            move(p, b, out, new Vector3f(t.scale()).mul(0.4f), new Quaternionf(t.leftRotation()).rotateX(2f), 10);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, b.getBlockState()), p.at.x, p.at.y + 1.5, p.at.z, 25, 2, 1.5, 2, 0.4);
        }
        level.playSound(null, p.at.x, p.at.y, p.at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3f, 0.6f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, p.at.x, p.at.y + 1.5, p.at.z, 1, 0, 0, 0, 0);
        Tasks.later(11, p::remove);
    }

    // ------------------------------------------------------------------ Eight Trigrams

    /** The eight trigrams (three bars each, broken = yin) laid flat on the ground, one per 45 deg sector at {@code radius}. */
    public static @Nullable Piece trigrams(ServerLevel level, Vec3 center, double radius) {
        if (!BossFx.enabled() || full()) return null;
        Piece p = new Piece(center);
        int[] signs = {0b111, 0b110, 0b101, 0b100, 0b011, 0b010, 0b001, 0b000};   // qian, dui, li, zhen, xun, kan, gen, kun
        BlockState ink = Blocks.CONCRETE.black().defaultBlockState(), gold = Blocks.GOLD_BLOCK.defaultBlockState();
        for (int s = 0; s < 8; s++) {
            double a = sectorAngle(s);
            Vec3 at = center.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            BlockPos g = BossFx.groundAt(level, (int) Math.floor(at.x), center.y, (int) Math.floor(at.z));
            Vec3 on = new Vec3(at.x, g == null ? center.y : g.getY() + 1, at.z);
            // bars run tangentially (local z), stacked outward (local x); the innermost bar is the trigram's bottom line
            Quaternionf rot = new Quaternionf().rotateY((float) -a);
            BlockState st = s == 0 || s == 7 ? gold : ink;
            for (int bar = 0; bar < 3; bar++) {
                boolean yang = (signs[s] >> bar & 1) == 1;
                float x = -0.77f + bar * 0.6f;
                if (yang) {
                    block(level, p, on, st, rot.transform(new Vector3f(x, 0.02f, -1.1f)), new Vector3f(0.34f, 0.04f, 2.2f), rot);
                } else {
                    block(level, p, on, st, rot.transform(new Vector3f(x, 0.02f, -1.1f)), new Vector3f(0.34f, 0.04f, 0.85f), rot);
                    block(level, p, on, st, rot.transform(new Vector3f(x, 0.02f, 0.25f)), new Vector3f(0.34f, 0.04f, 0.85f), rot);
                }
            }
        }
        return p;
    }

    /** The middle angle (radians, from +x toward +z) of Eight Trigrams sector {@code s}. */
    public static double sectorAngle(int s) {
        return 2 * Math.PI * (s + 0.5) / 8;
    }

    /** Which of the 8 sectors around {@code center} {@code at} is in. */
    public static int sector(Vec3 center, Vec3 at) {
        double a = Math.atan2(at.z - center.z, at.x - center.x);
        if (a < 0) a += 2 * Math.PI;
        return Math.min(7, (int) (a / (2 * Math.PI / 8)));
    }

    // ------------------------------------------------------------------ the boomerang staff

    /**
     * The staff thrown like a boomerang: it spins out from {@code from} to {@code to}, hangs there a moment, then flies back
     * to wherever {@code home} is at the time. Players near its path get {@code hit} once on the way out and once on the
     * way back. {@code done} runs when it is back (at once if the effects are off).
     */
    public static void throwStaff(ServerLevel level, Vec3 from, Vec3 to, Supplier<Vec3> home, @Nullable Entity source, @Nullable ItemStack item,
                                  BossFx.Hit hit, Runnable done) {
        if (!BossFx.enabled() || full()) {
            done.run();
            return;
        }
        ItemStack shown = item == null || item.isEmpty() ? new ItemStack(Items.END_ROD) : item;
        Display.ItemDisplay staff = new Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }
        };
        staff.setPos(from.x, from.y, from.z);
        staff.setItemStack(shown);
        staff.setItemTransform(ItemDisplayContext.NONE);
        staff.setBrightnessOverride(new Brightness(15, 15));
        staff.setGlowingTag(true);
        staff.setGlowColorOverride(0xF6C64A);
        staff.setViewRange(4f);
        BossFx.add(level, staff);
        Set<UUID> out = new HashSet<>(), back = new HashSet<>();
        int outT = Math.max(8, (int) Math.round(from.distanceTo(to) / 1.3));
        final int[] t = {0};
        final Vec3[] last = {from};
        final Tasks.Task[] task = new Tasks.Task[1];
        DustParticleOptions gold = new DustParticleOptions(0xF6C64A, 1.4f);
        task[0] = Tasks.timer(1, 1, () -> {
            int n = t[0]++;
            if (staff.isRemoved() || !BossFx.enabled()) {
                task[0].cancel();
                BossFx.remove(staff);
                done.run();
                return;
            }
            Vec3 pos;
            boolean returning = n > outT + 6;
            if (n <= outT) {
                double u = (double) n / outT;
                u = 1 - (1 - u) * (1 - u);
                pos = from.add(to.subtract(from).scale(u));
            } else if (!returning) {
                pos = to;
            } else {
                Vec3 h = home.get();
                Vec3 d = h.subtract(last[0]);
                double len = d.length();
                if (len < 1.6 || n > outT + 60) {
                    task[0].cancel();
                    BossFx.remove(staff);
                    level.playSound(null, h.x, h.y, h.z, SoundEvents.TRIDENT_RETURN, SoundSource.HOSTILE, 1.6f, 0.7f);
                    done.run();
                    return;
                }
                pos = last[0].add(d.scale(Math.min(1, 1.5 / len)));
            }
            float spin = (float) Math.toRadians(n * 47 % 360);
            staff.setTransformationInterpolationDelay(0);
            staff.setTransformationInterpolationDuration(1);
            staff.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(spin).rotateX((float) Math.PI / 2), new Vector3f(1f),
                    new Quaternionf()));
            staff.setPosRotInterpolationDuration(2);
            staff.teleportTo(pos.x, pos.y, pos.z);
            level.sendParticles(gold, pos.x, pos.y, pos.z, 3, 0.4, 0.1, 0.4, 0);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            if (n % 3 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.2f, 1.5f);
            Set<UUID> struck = returning ? back : out;
            for (ServerPlayer p : level.players()) {
                if (!BossFx.targetable(p) || struck.contains(p.getUUID())) continue;
                if (p.position().add(0, 1, 0).distanceTo(pos) > 1.9) continue;
                struck.add(p.getUUID());
                BossFx.strike(level, p, source, hit, p.position().subtract(pos));
            }
            last[0] = pos;
        });
    }

    // ------------------------------------------------------------------ small helpers

    /** {@link BossFx#strike} for the boss skills: damage plus a knock away from {@code from}. */
    public static void hit(ServerLevel level, ServerPlayer p, @Nullable Entity source, BossFx.Hit hit, Vec3 from) {
        BossFx.strike(level, p, source, hit, p.position().subtract(from));
    }

    /** Players standing on the ground (for hazards that a jump dodges). */
    public static boolean grounded(ServerPlayer p) {
        return BossFx.grounded(p);
    }
}
