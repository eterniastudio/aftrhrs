package net.axther.aftrhrs.gametest.creatures;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMonster;
import net.axther.aftrhrs.entity.Species;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.axther.aftrhrs.feature.bossfx.BossFx;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Sun Wukong's redesigned fight (mod/WUKONG.md): every set piece runs start to finish without errors and leaves him
 * whole (visible, normal size, no set-piece lock, no stray displays); the Buddha's Palm finale can be won by claiming
 * the five fingers, after which the Five Elements Mountain pins him (double damage) and he can be killed.
 */
public class WukongTests {
    private static final String[] PIECES = {"Transform", "Cloud", "Giant", "Furnace", "HairArmy", "StaffThrow", "Vault", "EyeSweep", "Pillar"};

    @GameTest(maxTicks = 3600, padding = 96, skyAccess = true)
    public void wukongSetPieces(GameTestHelper h) {
        CT.arena(h, 40, 40);
        ServerLevel level = h.getLevel();
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(20, 1, 10));
        p.setPermanentlyInvulnerable(true);
        Species wk = ArcanaEntities.species(ArcanaEntities.type("wukong"));
        ArcanaMonster boss = CT.spawn(h, wk, new Vec3(20, 1, 22));
        boss.setPersistenceRequired();
        List<String> problems = new ArrayList<>();
        Deque<String> todo = new ArrayDeque<>(List.of(PIECES));
        String[] running = {null};
        long[] startedAt = {0};
        boolean[] done = {false}, hitShrine = {false};
        h.assertTrue(BossFx.enabled(), "boss-fx module is off in the test server");
        h.onEachTick(() -> {
            long t = h.getTick();
            if (done[0] || !boss.isAlive()) return;
            boss.setTarget(p);
            // keep the player at a fair distance (moves check range) and in front of him
            if (t % 20 == 0 && running[0] == null) p.teleportTo(level, boss.getX(), boss.getY(), boss.getZ() - 10, java.util.Set.of(), 0, 0, false);
            if (t < 100) return;
            if (running[0] != null) {
                // the shrine game: punch the real Wukong after a moment, he must pop out
                if (running[0].equals("Transform") && !hitShrine[0] && t > startedAt[0] + 60 && boss.isInvisible()) {
                    hitShrine[0] = true;
                    CT.set(boss, "invulnerableTime", 0);
                    boss.hurtServer(level, level.damageSources().playerAttack(p), 4f);
                }
                boolean idle = boss.var("setpiece") == 0 && !boss.busy() && !boss.isInvisible() && t > startedAt[0] + 20;
                if (idle && t > startedAt[0] + 140) {
                    check(boss, running[0], problems);
                    running[0] = null;
                }
                if (t > startedAt[0] + 500) {
                    problems.add(running[0] + " never finished (setpiece " + boss.var("setpiece") + ", busy " + boss.busy() + ")");
                    running[0] = null;
                }
                return;
            }
            if (todo.isEmpty()) {
                done[0] = true;
                return;
            }
            String next = todo.poll();
            // fresh cooldowns and a clear global cooldown, as if the brain picked it
            boss.scheduler().clearCooldown("gcd");
            boss.scheduler().clearCooldown(next);
            if (next.equals("Pillar") || next.equals("Giant") || next.equals("Furnace") || next.equals("HairArmy")) boss.setVar("phase", 3);
            else if (!next.equals("Vault") && !next.equals("StaffThrow")) boss.setVar("phase", 2);
            running[0] = next;
            startedAt[0] = t;
            if (!Abilities.runSkill(boss, "ArcanaWukong_" + next, p)) problems.add("no skill ArcanaWukong_" + next);
            AftrHrs.LOG.info("[wukong] {} at tick {}: setpiece {}", next, t, boss.var("setpiece"));
        });
        h.succeedWhen(() -> {
            h.assertTrue(done[0], "set pieces still running: " + running[0] + " left " + todo);
            h.assertTrue(hitShrine[0], "the shrine game never hid him");
            int stray = level.getEntitiesOfClass(Display.class, new AABB(boss.blockPosition()).inflate(48), e -> e.entityTags().contains(BossFx.TAG)).size();
            if (stray > 0) problems.add(stray + " boss-fx display(s) left over");
            CT.disconnect(p);
            boss.discard();
            CT.report(h, "Wukong set pieces", problems);
        });
    }

    private static void check(ArcanaMonster boss, String piece, List<String> problems) {
        if (boss.getScale() > 1.01f) problems.add(piece + ": left him giant (scale " + boss.getScale() + ")");
        if (boss.isInvisible() || boss.hiddenBones().contains("root")) problems.add(piece + ": left him hidden");
        if (boss.hiddenBones().contains("staff")) problems.add(piece + ": left his staff hidden");
        if (boss.isNoGravity()) problems.add(piece + ": left him floating");
        if (boss.var("untouchable") != 0) problems.add(piece + ": left him untouchable");
    }

    /** Buddha's Palm: claim the five fingers, the mountain pins him with double damage, then he dies and it all clears. */
    @GameTest(maxTicks = 2400, padding = 96, skyAccess = true)
    public void wukongBuddhasPalm(GameTestHelper h) {
        CT.arena(h, 40, 40);
        ServerLevel level = h.getLevel();
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(20, 1, 12));
        p.setPermanentlyInvulnerable(true);
        Species wk = ArcanaEntities.species(ArcanaEntities.type("wukong"));
        ArcanaMonster boss = CT.spawn(h, wk, new Vec3(20, 1, 20));
        boss.setPersistenceRequired();
        List<String> problems = new ArrayList<>();
        int[] stage = {0};
        long[] at = {0};
        List<Vec3> fingers = new ArrayList<>();
        int[] finger = {0};
        float[] before = {0};
        h.onEachTick(() -> {
            long t = h.getTick();
            if (!boss.isAlive()) return;
            boss.setTarget(p);
            switch (stage[0]) {
                case 0 -> {
                    if (t < 100) return;
                    // straight to the threshold of the finale
                    boss.setVar("phase", 3);
                    boss.setHealth(boss.getMaxHealth() * 0.13f);
                    CT.set(boss, "invulnerableTime", 0);
                    boss.hurtServer(level, level.damageSources().playerAttack(p), boss.getMaxHealth() * 0.03f);
                    stage[0] = 1;
                    at[0] = t;
                }
                case 1 -> {
                    if (t < at[0] + 40) return;
                    if (boss.var("phase") != 4) problems.add("phase " + boss.var("phase") + " after crossing 12 %");
                    if (boss.var("untouchable") == 0) problems.add("not untouchable while the fingers stand");
                    // each finger has one quartz nail display at its base
                    for (Display.BlockDisplay d : level.getEntitiesOfClass(Display.BlockDisplay.class, new AABB(boss.blockPosition()).inflate(32),
                            e -> e.entityTags().contains(BossFx.TAG) && e.getBlockState().is(Blocks.QUARTZ_BLOCK))) {
                        fingers.add(d.position());
                    }
                    if (fingers.size() != 5) {
                        problems.add(fingers.size() + " fingers instead of 5");
                        stage[0] = 9;
                        return;
                    }
                    stage[0] = 2;
                    at[0] = t;
                }
                case 2 -> {
                    // stand at each finger long enough to claim it
                    Vec3 f = fingers.get(finger[0]);
                    p.teleportTo(level, f.x + 0.5, f.y, f.z, java.util.Set.of(), 0, 0, false);
                    if (t >= at[0] + 70) {
                        finger[0]++;
                        at[0] = t;
                        if (finger[0] == 5) stage[0] = 3;
                    }
                }
                case 3 -> {
                    if (boss.var("vulnerable") == 100) {
                        before[0] = boss.getHealth();
                        CT.set(boss, "invulnerableTime", 0);
                        boss.hurtServer(level, level.damageSources().playerAttack(p), 20f);
                        float dealt = before[0] - boss.getHealth();
                        // 20 doubled = 40 through 16 armor = ~34.9; undoubled it would be ~15.2
                        if (dealt < 25f) problems.add("sealed: a 20 damage hit took only " + dealt + " (double damage expected)");
                        stage[0] = 4;
                    } else if (t > at[0] + 200) {
                        problems.add("the mountain never sealed him (vulnerable " + boss.var("vulnerable") + ", untouchable " + boss.var("untouchable") + ")");
                        stage[0] = 4;
                    }
                }
                case 4 -> {
                    if (t % 6 != 0) return;
                    CT.set(boss, "invulnerableTime", 0);
                    boss.hurtServer(level, level.damageSources().playerAttack(p), boss.getMaxHealth() / 20f);
                }
                default -> {
                }
            }
        });
        h.succeedWhen(() -> {
            if (stage[0] == 9) {
                CT.disconnect(p);
                boss.discard();
                CT.report(h, "Buddha's Palm", problems);
                return;
            }
            h.assertTrue(!boss.isAlive(), "Wukong still alive at stage " + stage[0] + " (" + boss.getHealth() + "/" + boss.getMaxHealth() + ")");
            h.assertTrue(boss.isRemoved(), "waiting for the death clip");
            CT.disconnect(p);
            CT.report(h, "Buddha's Palm", problems);
        });
    }

    /**
     * A Wukong in phase 4 held at the floor with no palm running (his fight state was lost: unload/reload or restart
     * mid-finale, or a missed re-raise) must get the fingers back, or he can never be finished.
     */
    @GameTest(maxTicks = 600, padding = 96, skyAccess = true)
    public void wukongPalmComesBackAfterLostState(GameTestHelper h) {
        CT.arena(h, 40, 40);
        ServerLevel level = h.getLevel();
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(20, 1, 12));
        p.setPermanentlyInvulnerable(true);
        Species wk = ArcanaEntities.species(ArcanaEntities.type("wukong"));
        ArcanaMonster boss = CT.spawn(h, wk, new Vec3(20, 1, 20));
        boss.setPersistenceRequired();
        // what the saved vars look like after an escape, with a brand-new (empty) fight state and a stuck set piece
        boss.setVar("phase", 4);
        boss.setVar("floor", 50);
        boss.setVar("untouchable", 0);
        boss.setVar("setpiece", 1);
        boss.setHealth(boss.getMaxHealth() * 0.05f);
        h.onEachTick(() -> {
            if (boss.isAlive()) boss.setTarget(p);
        });
        h.succeedWhen(() -> {
            int fingers = level.getEntitiesOfClass(Display.BlockDisplay.class, new AABB(boss.blockPosition()).inflate(32),
                    e -> e.entityTags().contains(BossFx.TAG) && e.getBlockState().is(Blocks.QUARTZ_BLOCK)).size();
            h.assertTrue(fingers == 5, "fingers up: " + fingers + " (untouchable " + boss.var("untouchable") + ", setpiece " + boss.var("setpiece") + ")");
            h.assertTrue(boss.var("untouchable") == 1, "not untouchable while the fingers stand");
            h.assertTrue(boss.var("setpiece") == 0, "the stuck set piece was not cleared");
            CT.disconnect(p);
            boss.discard();
        });
    }

    /**
     * A big quake (radius 22, as Worldbreaker's) with a player who has no heave channel (a test player): clients with the
     * channel draw the heaving ground themselves, so the server makes at most the capped fallback for this one, never
     * pushes the set pieces' display budget over, and still hits a grounded player as the ring passes.
     */
    @GameTest(maxTicks = 200, padding = 96, skyAccess = true)
    public void wukongQuakeIsDrawnByClients(GameTestHelper h) {
        CT.arena(h, 40, 40);
        ServerLevel level = h.getLevel();
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(30, 1, 20));
        p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket()); // takes damage
        Vec3 origin = h.absoluteVec(new Vec3(20, 1, 20));
        h.assertTrue(BossFx.enabled(), "boss-fx module is off in the test server");
        int[] most = {0};
        boolean[] full = {false};
        float before = p.getHealth();
        long[] start = {-1};
        h.onEachTick(() -> {
            long t = h.getTick();
            if (start[0] < 0 && t >= 5) {
                start[0] = t;
                BossFx.quake(level, origin, null, 22, 0.6, 0.55, 2, new BossFx.Hit(4, 0.3, 0.3, true));
            }
            if (start[0] < 0) return;
            int n = level.getEntitiesOfClass(Display.BlockDisplay.class, new AABB(net.minecraft.core.BlockPos.containing(origin)).inflate(32),
                    e -> e.entityTags().contains(BossFx.TAG)).size();
            most[0] = Math.max(most[0], n);
            if (Boolean.TRUE.equals(CT.call(net.axther.aftrhrs.feature.bossfx.SetPieces.class, "full"))) full[0] = true;
        });
        h.succeedWhen(() -> {
            // 22 blocks at 0.6 a tick: the ring is out after ~37 ticks, its last heaves gone 10 later
            h.assertTrue(start[0] >= 0 && h.getTick() > start[0] + 55, "quake still running");
            List<String> problems = new ArrayList<>();
            if (most[0] > 300) problems.add(most[0] + " server quake displays at once (fallback cap is 300)");
            if (most[0] == 0) problems.add("no fallback displays for a player without the heave channel");
            if (full[0]) problems.add("the quake filled the set pieces' display budget");
            if (p.getHealth() >= before) problems.add("the grounded player in the ring was not hit (" + p.getHealth() + "/" + before + ")");
            CT.disconnect(p);
            CT.report(h, "Client-drawn quake", problems);
        });
    }

    /** Held at the pre-finale floor (hits deal 0 there, so no damage trigger fires), he still moves on to the palm. */
    @GameTest(maxTicks = 600, padding = 96, skyAccess = true)
    public void wukongReachesThePalmFromTheFloor(GameTestHelper h) {
        CT.arena(h, 40, 40);
        ServerLevel level = h.getLevel();
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(20, 1, 12));
        p.setPermanentlyInvulnerable(true);
        Species wk = ArcanaEntities.species(ArcanaEntities.type("wukong"));
        ArcanaMonster boss = CT.spawn(h, wk, new Vec3(20, 1, 20));
        boss.setPersistenceRequired();
        boss.setVar("phase", 3);
        boss.setVar("floor", 1);
        boss.setHealth(boss.getMaxHealth() * 0.001f);
        h.onEachTick(() -> {
            if (boss.isAlive()) boss.setTarget(p);
        });
        h.succeedWhen(() -> {
            h.assertTrue(boss.var("phase") == 4, "still in phase " + boss.var("phase") + " at the floor (setpiece " + boss.var("setpiece") + ")");
            CT.disconnect(p);
            boss.discard();
        });
    }

    /**
     * Wukong unloaded mid shrine game (his chunk goes, nothing destroys him): his fight state is evicted and every shrine
     * (display pieces and the swaying flag's timer) is removed a tick later, leaving no boss-fx display behind.
     */
    @GameTest(maxTicks = 400, padding = 96, skyAccess = true)
    @SuppressWarnings("unchecked")
    public void wukongUnloadDropsShrines(GameTestHelper h) {
        CT.arena(h, 40, 40);
        ServerLevel level = h.getLevel();
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(20, 1, 10));
        p.setPermanentlyInvulnerable(true);
        Species wk = ArcanaEntities.species(ArcanaEntities.type("wukong"));
        ArcanaMonster boss = CT.spawn(h, wk, new Vec3(20, 1, 22));
        boss.setPersistenceRequired();
        h.assertTrue(BossFx.enabled(), "boss-fx module is off in the test server");
        List<String> problems = new ArrayList<>();
        AABB around = new AABB(boss.blockPosition()).inflate(48);
        java.util.function.IntSupplier displays = () -> level.getEntitiesOfClass(Display.class, around,
                e -> e.isAlive() && e.entityTags().contains(BossFx.TAG)).size();
        int before = displays.getAsInt();
        h.runAfterDelay(60, () -> {
            boss.setTarget(p);
            boss.scheduler().clearCooldown("gcd");
            boss.scheduler().clearCooldown("Transform");
            boss.setVar("phase", 2);
            if (!Abilities.runSkill(boss, "ArcanaWukong_Transform", p)) problems.add("no skill ArcanaWukong_Transform");
        });
        h.runAfterDelay(130, () -> {
            List<net.axther.aftrhrs.feature.bossfx.SetPieces.Piece> shrines = new ArrayList<>();
            try {
                java.lang.reflect.Field fights = Class.forName("net.axther.aftrhrs.entity.ability.WukongSkills").getDeclaredField("FIGHTS");
                fights.setAccessible(true);
                Object f = ((java.util.Map<?, ?>) fights.get(null)).get(boss);
                if (f != null) {
                    java.lang.reflect.Field fl = f.getClass().getDeclaredField("shrines");
                    fl.setAccessible(true);
                    shrines.addAll((List<net.axther.aftrhrs.feature.bossfx.SetPieces.Piece>) fl.get(f));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            if (shrines.isEmpty()) problems.add("the shrine game made no shrines");
            if (displays.getAsInt() <= before) problems.add("no shrine displays stood before the unload");
            boss.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.runAfterDelay(3, () -> {
                int up = 0;
                for (var s : shrines) if (!s.removed()) up++;
                if (up > 0) problems.add(up + " of " + shrines.size() + " shrines still up after Wukong unloaded");
                int left = displays.getAsInt() - before;
                if (left > 0) problems.add(left + " boss-fx display(s) left after Wukong unloaded");
                CT.disconnect(p);
                CT.report(h, "Wukong unload", problems);
                h.succeed();
            });
        });
    }
}
