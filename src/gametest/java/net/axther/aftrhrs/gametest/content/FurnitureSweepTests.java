package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every furniture piece (and the lit-hearth alias items), placed the way a survival player places it, on every face
 * its rules allow and in every orientation; then broken from each of its parts in turn. Checks: the piece lands with
 * the right facing / rotation, all its parts exist, the item is used up, breaking any part removes every part and
 * drops exactly the piece's loot (once), and nothing (seats, display parts) is left behind.
 */
public class FurnitureSweepTests {
    static final int PER_TICK = 40;

    /** One placement to try. */
    record Case(String id, String mode, Direction face, float yaw, float pitch, int index) {
    }

    static List<Case> cases() {
        List<Case> out = new ArrayList<>();
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (!(c.block() instanceof FurnitureBlock fb) || c.item() == null) continue;
            if (!c.kind().equals("furniture") && !c.kind().equals("alias")) continue;
            if (c.hidden()) continue;
            if (c.id().equals("arcana_rope")) continue; // ropes only hang (gadgets): GadgetTests
            FurnitureSpec s = fb.spec;
            int n = 0;
            for (String mode : new String[]{"floor", "roof"}) {
                if (mode.equals("floor") ? !s.floor : !s.roof) continue;
                Direction face = mode.equals("floor") ? Direction.UP : Direction.DOWN;
                if (s.facing == FurnitureSpec.Facing.ROT16) {
                    for (int r = 0; r < 16; r++) out.add(new Case(c.id(), mode, face, r * 22.5f, 30, n++));
                } else if (s.facing == FurnitureSpec.Facing.ALL6) {
                    for (float[] yp : new float[][]{{0, 0}, {90, 0}, {180, 0}, {270, 0}, {0, 89}, {0, -89}}) {
                        out.add(new Case(c.id(), mode, face, yp[0], yp[1], n++));
                    }
                } else {
                    for (int r = 0; r < 4; r++) out.add(new Case(c.id(), mode, face, r * 90f, 30, n++));
                }
            }
            if (s.wall) {
                for (Direction d : Direction.Plane.HORIZONTAL) out.add(new Case(c.id(), "wall", d, d.getOpposite().toYRot(), 0, n++));
            }
        }
        return out;
    }

    /** Runs {@code work} on every item of {@code all}, PER_TICK per tick, then {@code done}. */
    static <T> void stepped(GameTestHelper h, List<T> all, int perTick, Consumer<T> work, Runnable done) {
        stepped(h, all, perTick, 1, work, done);
    }

    /** Same, waiting {@code delay} ticks between batches (for cooldown-gated actions). */
    static <T> void stepped(GameTestHelper h, List<T> all, int perTick, int delay, Consumer<T> work, Runnable done) {
        int[] at = {0};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            int end = Math.min(all.size(), at[0] + perTick);
            for (; at[0] < end; at[0]++) work.accept(all.get(at[0]));
            if (at[0] >= all.size()) done.run();
            else h.runAfterDelay(delay, () -> step[0].run()); // a new Runnable: the tick map is keyed by it and drops it after running
        };
        h.runAfterDelay(1, step[0]);
    }

    static Direction expectedH4(Case k) {
        if (k.mode.equals("wall")) return k.face;
        return Direction.fromYRot(k.yaw).getOpposite();
    }

    @GameTest(maxTicks = 12000, padding = 16)
    public void everyPieceEveryFaceAndRotation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Bot bot = CT.join(h, "btPlace", true);
        ServerPlayer p = bot.p;
        BlockPos center = h.absolutePos(new BlockPos(4, 6, 4));
        CT.Fails f = new CT.Fails("furniture_place_break");
        List<Case> all = cases();
        Map<String, Integer> perId = new LinkedHashMap<>();
        stepped(h, all, PER_TICK, k -> {
            try {
                run(level, p, center, k, f);
            } catch (RuntimeException ex) {
                f.add(k.id + " " + k.mode + " yaw " + k.yaw + ": exception " + ex);
                net.axther.aftrhrs.AftrHrs.LOG.error("[bt-content] {} crashed", k.id, ex);
            }
            perId.merge(k.id, 1, Integer::sum);
            bot.drain();
        }, () -> {
            CT.clear(level, center, 5);
            bot.leave();
            f.check(perId.size() > 900, "only " + perId.size() + " pieces swept");
            f.finish(h);
        });
    }

    static void run(ServerLevel level, ServerPlayer p, BlockPos center, Case k, CT.Fails f) {
        ArcanaContent.Content c = ArcanaContent.entry(k.id);
        FurnitureBlock fb = (FurnitureBlock) c.block();
        Item item = c.item();
        String tag = k.id + " " + k.mode + (k.mode.equals("wall") ? " " + k.face : " yaw " + k.yaw + (k.pitch != 30 ? " pitch " + k.pitch : ""));
        CT.clear(level, center, 5);
        p.getInventory().clearContent();
        if (p.isPassenger()) p.stopRiding();
        BlockPos origin = center;
        BlockPos support = switch (k.mode) {
            case "floor" -> origin.below();
            case "roof" -> origin.above();
            default -> origin.relative(k.face.getOpposite());
        };
        level.setBlock(support, Blocks.STONE.defaultBlockState(), 2 | 16);
        // stand away from the piece's cells, looking the way the case says
        CT.pose(p, Vec3.atBottomCenterOf(center).add(0, -4, 0), k.yaw, k.pitch);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        CT.click(p, support, k.face);
        // a multi-block piece put against a ceiling hangs from it: its top part sits under the ceiling
        if (k.mode.equals("roof")) {
            int up = 0;
            for (BlockPos c2 : fb.spec.parts) up = Math.max(up, c2.getY());
            origin = origin.below(up);
        }
        BlockState s = level.getBlockState(origin);
        if (!s.is(fb)) {
            f.add(tag + ": not placed (found " + CT.id(s) + ")");
            return;
        }
        f.check(p.getMainHandItem().isEmpty(), tag + ": item not used up (" + p.getMainHandItem() + ")");
        // orientation
        FurnitureSpec spec = fb.spec;
        switch (spec.facing) {
            case H4 -> f.check(s.getValue(FurnitureBlock.FACING) == expectedH4(k), tag + ": facing " + s.getValue(FurnitureBlock.FACING) + " expected " + expectedH4(k));
            case ROT16 -> {
                int raw = Math.floorMod(Math.round(k.yaw / 22.5f), 16);
                int step = Math.max(1, spec.rotStep);
                int want = Math.floorMod(Math.round(raw / (float) step) * step, 16);
                f.check(s.getValue(FurnitureBlock.ROTATION) == want, tag + ": rotation " + s.getValue(FurnitureBlock.ROTATION) + " expected " + want);
                // the piece's front faces the placer: rotation*22.5 is the player's yaw, model north faces back at them
                f.check(Math.abs(Math.floorMod((int) (FurnitureBlock.yawDegrees(s) - k.yaw + 180), 360) - 180) <= 360 / 16 * step / 2 + 1,
                        tag + ": yaw " + FurnitureBlock.yawDegrees(s) + " far from placer yaw");
            }
            case ALL6 -> {
                // the encased fan (the only 6-way piece) blows the way the placer looks (power module)
                Direction look = Direction.getApproximateNearest(p.getViewVector(1f));
                f.check(s.getValue(FurnitureBlock.FACING_ALL) == look, tag + ": facing " + s.getValue(FurnitureBlock.FACING_ALL) + " expected " + look);
            }
        }
        if (c.kind().equals("alias")) f.check(s.getValue(FurnitureBlock.LIT), tag + ": alias item placed unlit");
        // parts
        List<BlockPos> parts = fb.partPositions(s, origin);
        for (int i = 0; i < parts.size(); i++) {
            BlockState ps = level.getBlockState(parts.get(i));
            f.check(ps.is(fb) && fb.partIndex(ps) == i, tag + ": part " + i + " missing at " + parts.get(i).subtract(origin).toShortString() + " (" + CT.id(ps) + ")");
            if (ps.is(fb)) f.check(fb.origin(ps, parts.get(i)).equals(origin), tag + ": part " + i + " points at the wrong origin");
        }
        // loot that breaking should give (deterministic tables)
        Map<String, Integer> want = new LinkedHashMap<>();
        for (ItemStack d : Block.getDrops(s, level, origin, level.getBlockEntity(origin), p, ItemStack.EMPTY)) {
            want.merge(BuiltInRegistries.ITEM.getKey(d.getItem()).toString(), d.getCount(), Integer::sum);
        }
        String self = BuiltInRegistries.ITEM.getKey(item).toString();
        if (!c.kind().equals("alias") && spec.stages == null && !k.id.contains("rope")) {
            f.check(want.equals(Map.of(self, 1)), tag + ": loot table gives " + want + " instead of the piece");
        }
        // break from part (index mod parts)
        int bi = k.index % parts.size();
        BlockPos bp = parts.get(bi);
        boolean broke = CT.breakBlock(p, bp);
        f.check(broke, tag + ": break of part " + bi + " refused");
        for (int i = 0; i < parts.size(); i++) {
            BlockState after = level.getBlockState(parts.get(i));
            f.check(!after.is(fb), tag + ": part " + i + " survived breaking part " + bi);
        }
        Map<String, Integer> got = CT.collectDrops(level, origin, 6);
        f.check(got.equals(want), tag + ": broke part " + bi + ", dropped " + got + " expected " + want);
        List<Entity> left = CT.leftovers(level, origin, 5);
        f.check(left.isEmpty(), tag + ": left entities " + left);
    }

    /**
     * Module block entities only get a ticker on the sides that tick: presents and fir trees store their contents and
     * render, so the server gives them none; mistletoe and gadgets tick on the server only.
     */
    @GameTest
    public void dataOnlyPiecesHaveNoServerTicker(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("furniture_tickers");
        int presents = 0, trees = 0, mistletoe = 0;
        for (ArcanaContent.Content c : ArcanaContent.entries().values()) {
            if (!(c.block() instanceof net.axther.aftrhrs.content.FurnitureEntityBlock b) || "alias".equals(c.kind())) continue;
            BlockState st = b.defaultBlockState();
            if (net.axther.aftrhrs.feature.christmas.ChristmasContent.isPresent(c.id())) {
                presents++;
                f.check(b.getTicker(level, st, net.axther.aftrhrs.feature.christmas.ChristmasContent.PRESENT) == null, c.id() + ": present has a server ticker");
            } else if (net.axther.aftrhrs.feature.christmas.ChristmasContent.isFirTree(c.id())) {
                trees++;
                f.check(b.getTicker(level, st, net.axther.aftrhrs.feature.christmas.ChristmasContent.TREE) == null, c.id() + ": fir tree has a server ticker");
            } else if (net.axther.aftrhrs.feature.christmas.ChristmasContent.isMistletoe(c.id())) {
                mistletoe++;
                f.check(b.getTicker(level, st, net.axther.aftrhrs.feature.christmas.ChristmasContent.MISTLETOE_BE) != null, c.id() + ": mistletoe lost its server ticker");
            }
        }
        f.check(presents > 0 && trees > 0 && mistletoe > 0, "christmas pieces missing: presents " + presents + ", trees " + trees + ", mistletoe " + mistletoe);
        f.finish(h);
    }

    /**
     * Furniture states share vanilla's shape Cache by part and quadrant (FurnitureStateCacheMixin): every state still
     * answers collision, full-block, face-sturdy and large-shape queries exactly as computed fresh from its spec, and
     * the sharing really happens.
     */
    @GameTest(maxTicks = 200)
    public void sharedStateCachesMatchFreshShapes(GameTestHelper h) {
        CT.Fails f = new CT.Fails("furniture_state_caches");
        var empty = net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
        java.lang.reflect.Field cacheField;
        try {
            cacheField = net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        int states = 0, shared = 0;
        java.util.Set<Object> caches = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Block b : BuiltInRegistries.BLOCK) {
            if (!(b instanceof FurnitureBlock fb)) continue;
            String id = BuiltInRegistries.BLOCK.getKey(b).toString();
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                states++;
                var want = fb.spec.collision[fb.partIndex(st)][FurnitureBlock.quadrant(st)];
                var got = st.getCollisionShape(empty, BlockPos.ZERO);
                if (net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(want, got, net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME)) {
                    f.add(id + " " + st + ": collision shape " + got + " != " + want);
                    continue;
                }
                f.check(st.isCollisionShapeFullBlock(empty, BlockPos.ZERO) == Block.isShapeFullBlock(want), id + " " + st + ": full-block flag");
                boolean large = false;
                for (Direction.Axis a : Direction.Axis.values()) large |= want.min(a) < 0.0 || want.max(a) > 1.0;
                f.check(st.hasLargeCollisionShape() == large, id + " " + st + ": large-shape flag");
                for (Direction d : Direction.values()) {
                    for (net.minecraft.world.level.block.SupportType t : net.minecraft.world.level.block.SupportType.values()) {
                        f.check(st.isFaceSturdy(empty, BlockPos.ZERO, d, t) == t.isSupporting(st, empty, BlockPos.ZERO, d),
                                id + " " + st + ": face " + d + " " + t);
                    }
                }
                try {
                    Object c = cacheField.get(st);
                    if (c != null && !caches.add(c)) shared++;
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        f.check(states > 1000, "only " + states + " furniture states");
        f.check(shared > 0, "no two furniture states share a shape cache (FurnitureStateCacheMixin did not apply)");
        net.axther.aftrhrs.AftrHrs.LOG.info("furniture state caches: {} states, {} caches", states, caches.size());
        f.finish(h);
    }
}
