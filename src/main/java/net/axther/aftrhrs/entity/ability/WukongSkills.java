package net.axther.aftrhrs.entity.ability;

import static net.axther.aftrhrs.entity.ability.BossKit.clip;
import static net.axther.aftrhrs.entity.ability.BossKit.damage;
import static net.axther.aftrhrs.entity.ability.BossKit.distance;
import static net.axther.aftrhrs.entity.ability.BossKit.gcd;
import static net.axther.aftrhrs.entity.ability.BossKit.later;
import static net.axther.aftrhrs.entity.ability.BossKit.offGcd;
import static net.axther.aftrhrs.entity.ability.BossKit.players;
import static net.axther.aftrhrs.entity.ability.BossKit.repeat;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaMonster;
import net.axther.aftrhrs.entity.CreatureUtil;
import net.axther.aftrhrs.feature.bossfx.BossFx;
import net.axther.aftrhrs.feature.bossfx.SetPieces;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Sun Wukong and his hair clones: the Havoc in Heaven fight (mod/WUKONG.md). Four phases on the {@code phase} variable:
 * 1 Handsome Monkey King, 2 Great Sage Equal to Heaven (66 %), 3 Havoc in Heaven (33 %), 4 Buddha's Palm (12 %).
 * Each transition is an untouchable set piece; the brain picks a weighted move from the phase's table every second it
 * is free (never the same twice in a row). Set pieces (transitions, the shrine game, the cloud barrage, the giant form,
 * the furnace, the peach feast, the seal) hold the {@code setpiece} variable: no other move, no melee, phase changes
 * wait for them. Every clip timing matches tools/clips/wukong.py.
 *
 * <p>Engine variables (ArcanaMonster): {@code untouchable} shrugs hits off, {@code vulnerable} = extra damage taken in
 * percent, {@code floor} = per mille of max health he can't be pushed below (he can't die before the finale).
 */
final class WukongSkills {
    private static final String ID = "ArcanaWukong";
    private static final String CLONE = "wukong_clone";
    private static final String STAFF = "arcana_relic_ruyi_jingu_bang";
    private static final Identifier GIANT = AftrHrs.id("wukong_giant");
    private static final DustParticleOptions GOLD = new DustParticleOptions(0xF6C64A, 1.6f);
    private static final DustParticleOptions RED = new DustParticleOptions(0xE0402A, 1.6f);
    private static final DustParticleOptions WHITE = new DustParticleOptions(0xFFF4D6, 1.4f);

    /** Phase health thresholds (fractions of max health). */
    private static final float P2 = 0.66f, P3 = 0.33f, P4 = 0.12f;
    /** Ticks a player must stand at a golden finger to claim it. */
    private static final int CLAIM = 50;

    /** Non-variable fight state per Wukong (display pieces, set-piece bookkeeping). Lost on restart: everything resets. */
    static final class Fight {
        String last = "";
        final List<SetPieces.Piece> pieces = new ArrayList<>();
        // shrine game
        boolean hidden;
        long hideUntil;
        final List<SetPieces.Piece> shrines = new ArrayList<>();
        SetPieces.@Nullable Piece realShrine;
        // feast
        boolean feasting;
        float feastStart, feastHealed;
        long feastUntil;
        // cloud
        boolean riding;
        double rideY;
        @Nullable Entity rideTarget;
        // staff in flight
        boolean staffOut;
        // finale
        boolean finale;
        long finaleUntil;
        int fails;
        final SetPieces.Piece[] fingers = new SetPieces.Piece[5];
        final Vec3[] fingerAt = new Vec3[5];
        final int[] claim = new int[5];
        boolean sealed, sealedDone;
        SetPieces.@Nullable Piece mountain;
        long nextFinale;
        // deflect feedback
        long deflectAt;
    }

    /** Weak keys as a backstop: an unloaded or removed Wukong's fight is evicted explicitly ({@link #register}). */
    private static final Map<Mob, Fight> FIGHTS = new WeakHashMap<>();
    private static boolean hooked;

    private static Fight fight(Ctx c) {
        return FIGHTS.computeIfAbsent(c.self, k -> new Fight());
    }

    @FunctionalInterface
    interface Move {
        /** Starts the move if its conditions hold; false = try another. */
        boolean run(Ctx c, @Nullable Entity target);
    }

    private static final Map<String, Move> MOVES = new LinkedHashMap<>();
    private static final List<Map<String, Integer>> TABLES = new ArrayList<>();

    private WukongSkills() {
    }

    // ================================================================== small helpers

    private static int phase(Ctx c) {
        return Math.max(1, c.mob.var("phase"));
    }

    private static boolean setPiece(Ctx c) {
        return c.mob.var("setpiece") != 0;
    }

    private static void beginPiece(Ctx c) {
        c.mob.setVar("setpiece", 1);
        c.mob.setBusy(true);
    }

    /** Ends a set piece: AI back on, idle back to the phase stance, a phase threshold crossed meanwhile fires now. */
    private static void endPiece(Ctx c) {
        c.mob.setVar("setpiece", 0);
        c.mob.setBusy(false);
        if (c.mob instanceof ArcanaMonster m) {
            m.setIdleClip(c.species().baseIdle());
            m.setWalkClip(c.species().baseWalk());
        }
        later(c, 2, () -> Abilities.runSkill(c.mob, ID + "_PhaseCheck", null));
    }

    private static void self(Ctx c, String type, int ticks, int level) {
        Fx.potion(c.self, type, ticks, level, false, false);
    }

    private static void quake(Ctx c, Vec3 at, double radius, double speed, double rise, int width, double damage, double knockUp, double knockOut) {
        BossFx.quake(c.level(), at, c.self, radius, speed, rise, width, new BossFx.Hit(damage, knockUp, knockOut, true));
    }

    private static void quake(Ctx c, double radius, double speed, double rise, int width, double damage, double knockUp, double knockOut) {
        quake(c, c.self.position(), radius, speed, rise, width, damage, knockUp, knockOut);
    }

    private static void quake(Ctx c, double radius, double speed, double rise, int width, double damage) {
        quake(c, radius, speed, rise, width, damage, 0.9, 0.6);
    }

    private static void shake(Ctx c, Vec3 at, double radius, float strength, int ticks) {
        BossFx.shake(c.level(), at, radius, strength, ticks);
    }

    private static void shake(Ctx c, float strength, int ticks) {
        shake(c, c.self.position(), 40, strength, ticks);
    }

    /** The skill's target, the caster's current target when it has none. */
    private static @Nullable Entity target(Ctx c) {
        return c.target != null && c.target != c.self ? c.target : c.self.getTarget();
    }

    private static void face(Mob self, @Nullable Entity t) {
        if (t == null) return;
        face(self, t.position());
    }

    private static void face(Mob self, Vec3 at) {
        double dx = at.x - self.getX(), dz = at.z - self.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        self.setYRot(yaw);
        self.setYBodyRot(yaw);
        self.setYHeadRot(yaw);
    }

    /** Ground level (feet y) at x / z near {@code y}, or {@code y}. */
    private static double groundY(ServerLevel level, double x, double y, double z) {
        BlockPos g = BossFx.groundAt(level, (int) Math.floor(x), y, (int) Math.floor(z));
        return g == null ? y : g.getY() + 1;
    }

    /** {@link #groundY} remembered per column in {@code memo} (one cast at one height: the same answer each time). */
    private static double groundY(ServerLevel level, Long2DoubleOpenHashMap memo, double x, double y, double z) {
        long key = BlockPos.asLong((int) Math.floor(x), 0, (int) Math.floor(z));
        double g = memo.getOrDefault(key, Double.NaN);
        if (Double.isNaN(g)) {
            g = groundY(level, x, y, z);
            memo.put(key, g);
        }
        return g;
    }

    private static Vec3 onGround(ServerLevel level, Vec3 v) {
        return new Vec3(v.x, groundY(level, v.x, v.y, v.z), v.z);
    }

    /** The arena center: where he arrived (the altar), else where he stands. */
    private static Vec3 center(Ctx c) {
        if (c.mob.var("cset") == 0) return c.self.position();
        return new Vec3(c.mob.var("cx") + 0.5, c.mob.var("cy"), c.mob.var("cz") + 0.5);
    }

    private static List<ServerPlayer> fighters(Ctx c) {
        return players(c.self, 40);
    }

    private static @Nullable ServerPlayer randomFighter(Ctx c, double r) {
        List<ServerPlayer> ps = players(c.self, r);
        if (ps.isEmpty()) return null;
        return ps.get(c.self.getRandom().nextInt(ps.size()));
    }

    private static @Nullable ServerPlayer nearest(Ctx c, double r) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : players(c.self, r)) {
            double d = p.distanceToSqr(c.self);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static void ring(Ctx c, Vec3 at, ParticleOptions p, double radius, int points) {
        BossKit.ring(c.level(), at, p, radius, points, 1, 0.15);
    }

    private static void say(Ctx c, String legacy) {
        BossKit.message(c.self, 48, legacy);
    }

    private static void title(Ctx c, String title, String sub) {
        BossKit.title(c.self, 48, title, sub, 50, 8, 16);
    }

    private static void bar(Ctx c, String name, BossEvent.BossBarColor color) {
        if (!(c.mob instanceof ArcanaMonster m) || m.bossBar() == null) return;
        if (!m.hasCustomName()) m.bossBar().setName(CreatureUtil.legacy(name));
        m.bossBar().setColor(color);
    }

    private static void hide(Ctx c, boolean hidden) {
        c.mob.setBoneVisible("root", !hidden);
        c.self.setInvisible(hidden);
    }

    private static void sfx(Ctx c, String event) {
        net.axther.aftrhrs.entity.CreatureSfx.play(c.mob, event);
    }

    private static ItemStack staffItem() {
        return ArcanaContent.stack(STAFF, 1);
    }

    private static boolean sight(ServerLevel level, Vec3 a, Entity b) {
        Vec3 to = b.position().add(0, b.getBbHeight() * 0.6, 0);
        return level.clip(new ClipContext(a, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b)).getType() == HitResult.Type.MISS;
    }

    // ================================================================== registration

    static void register() {
        // per-tick driver for the set pieces (feast, shrines, cloud, finale claims)
        Abilities.timer(ID, 1, ID + "_Tick", false);

        if (!hooked) {
            hooked = true;
            // unloaded with his chunk (or removed in any way): his fight state goes, and the display pieces with their timers
            // (the displays are never saved, so nothing else would stop those timers)
            ServerEntityEvents.ENTITY_UNLOAD.register((e, level) -> {
                if (FIGHTS.isEmpty() || !(e instanceof Mob m)) return;
                Fight f = FIGHTS.remove(m);
                if (f != null) Tasks.later(1, () -> dropPieces(f));   // deferred: may fire mid chunk-unload; never discard entities inline
            });
            ServerLifecycleEvents.SERVER_STOPPED.register(s -> FIGHTS.clear());
        }

        // spawn and load: a clean slate (a restart mid set piece must not leave him hidden, giant or untouchable)
        Abilities.register(ID + "_Glow", c -> {
            c.mob.setBoneVisible("peach", false);
            c.mob.setBoneVisible("staff", true);
            c.mob.setBoneVisible("root", true);
            c.self.setInvisible(false);
            if (c.mob instanceof ArcanaMonster m && m.species().id.equals("wukong")) {
                m.setBoneVisible("cloud", false);
                m.setVar("untouchable", 0);
                m.setVar("vulnerable", 0);
                m.setVar("setpiece", 0);
                m.setBusy(false);
                m.setNoGravity(false);
                int ph = m.var("phase");
                m.setVar("floor", ph >= 4 ? 0 : 1);
                AttributeInstance scale = m.getAttribute(Attributes.SCALE);
                if (scale != null) scale.removeModifier(GIANT);
                if (ph >= 2) restyle(c, ph);
            }
        });
        Abilities.register(ID + "_GlowOut", c -> {
        });

        registerSetPieces();
        registerMoves();
        registerClones();

        // ------------------------------------------------------------ the brain
        TABLES.add(table(   // 1: Handsome Monkey King: cocky staff play
                "Combo", 3, "GroundSlam", 2, "Stomp", 2, "Leap", 2, "Extend", 2, "Vault", 3, "StaffThrow", 2, "FireEyes", 2,
                "Somersault", 2, "Clones", 1, "Taunt", 1, "SkyCheck", 4));
        TABLES.add(table(   // 2: Great Sage Equal to Heaven
                "Combo", 2, "GroundSlam", 2, "Stomp", 1, "Leap", 1, "Extend", 2, "Vault", 2, "StaffThrow", 2, "EyeSweep", 3,
                "Somersault", 1, "Clones", 1, "Fissure", 2, "StaffSpin", 2, "Pillar", 2, "Transform", 2, "Cloud", 2, "Taunt", 1, "SkyCheck", 4));
        TABLES.add(table(   // 3: Havoc in Heaven
                "Combo", 2, "GroundSlam", 1, "Extend", 1, "Vault", 2, "StaffThrow", 2, "EyeSweep", 2, "Fissure", 1, "StaffSpin", 2,
                "Pillar", 2, "Transform", 2, "Cloud", 2, "Giant", 3, "Furnace", 3, "HairArmy", 2, "Feast", 1, "Somersault", 1, "SkyCheck", 4));
        TABLES.add(table(   // 4: Buddha's Palm, while the fingers stand: he dances out of reach and harries
                "Leap", 3, "StaffThrow", 3, "Vault", 3, "Somersault", 2, "EyeSweep", 2, "Extend", 2, "Clones", 1, "Taunt", 1, "SkyCheck", 4));
        TABLES.add(table(   // 4 after the seal / between attempts: everything he has left
                "Combo", 2, "Vault", 2, "StaffThrow", 2, "EyeSweep", 2, "StaffSpin", 2, "Pillar", 2, "Giant", 2, "Furnace", 2,
                "HairArmy", 1, "Cloud", 2, "Leap", 1, "SkyCheck", 4));

        Abilities.register(ID + "_Brain", c -> {
            if (!offGcd(c) || !c.self.isAlive() || setPiece(c)) return;
            Fight f = fight(c);
            if (f.staffOut || f.hidden) return;
            Entity target = target(c);
            if (target == null) return;
            int ph = phase(c);
            Map<String, Integer> t = TABLES.get(ph < 4 ? ph - 1 : f.finale ? 3 : 4);
            List<String> bag = new ArrayList<>();
            for (var e : t.entrySet()) if (!e.getKey().equals(f.last)) for (int i = 0; i < e.getValue(); i++) bag.add(e.getKey());
            Collections.shuffle(bag, new java.util.Random(c.self.getRandom().nextLong()));
            List<String> tried = new ArrayList<>();
            for (String name : bag) {
                if (tried.contains(name)) continue;
                tried.add(name);
                if (tried.size() > 6) break;
                if (MOVES.get(name).run(c, target)) {
                    f.last = name;
                    return;
                }
            }
        });
    }

    private static Map<String, Integer> table(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            String name = (String) kv[i];
            if (!MOVES.containsKey(name)) throw new IllegalStateException("Wukong: no move " + name);
            m.put(name, (Integer) kv[i + 1]);
        }
        return m;
    }

    private static void move(String name, Move m) {
        MOVES.put(name, m);
        Abilities.register(ID + "_" + name, c -> m.run(c, target(c)));
    }

    // ================================================================== arrival, phases, death

    private static void registerSetPieces() {
        Abilities.register(ID + "_Arrive", c -> {
            c.mob.setVar("phase", 1);
            c.mob.setVar("floor", 1);
            BlockPos at = c.self.blockPosition();
            c.mob.setVar("cx", at.getX());
            c.mob.setVar("cy", at.getY());
            c.mob.setVar("cz", at.getZ());
            c.mob.setVar("cset", 1);
            c.mob.setBoneVisible("cloud", true);
            clip(c, "spawn");
            gcd(c, 80);
            c.mob.setVar("untouchable", 1);
            self(c, "SLOWNESS", 64, 9);
            c.sound("block.beacon.activate", 2, 0.6);
            c.particles(ParticleTypes.CLOUD, 60, 2, 0.5, 3);
            title(c, "&6&lSun Wukong", "&eThe Handsome Monkey King of Flower-Fruit Mountain");
            bar(c, "Sun Wukong - Handsome Monkey King", BossEvent.BossBarColor.YELLOW);
            later(c, 40, () -> {
                quake(c, 10, 0.7, 0.6, 2, 0, 0.9, 0.6);
                shake(c, 1.6f, 14);
                c.sound("entity.generic.explode", 2, 0.7);
                c.sound("entity.fox.screech", 2.5, 0.5);
            });
            later(c, 52, () -> c.mob.setBoneVisible("cloud", false));
            later(c, 64, () -> {
                c.mob.setVar("untouchable", 0);
                say(c, "&6Wukong: &f\"Another challenger for old Sun? Come, come! Let's see what you've got!\"");
            });
        });

        // every hit: deflect feedback while untouchable, the shrine game, the feast, phase thresholds
        Abilities.register(ID + "_PhaseCheck", c -> {
            Fight f = fight(c);
            var src = Abilities.lastDamage();
            if (c.mob.var("untouchable") != 0) {
                if (src != null && src.getEntity() instanceof ServerPlayer p && c.level().getGameTime() > f.deflectAt) {
                    f.deflectAt = c.level().getGameTime() + 10;
                    c.sound("item.shield.block", 1.4, 1.3);
                    c.particles(ParticleTypes.ENCHANTED_HIT, 12, 0.6, 1, 1.4);
                    if (f.finale && !f.sealed) c.actionBar(p, "&6Buddha's palm shields him. &eClaim the five golden fingers!");
                }
                return;
            }
            if (f.hidden && src != null && src.getEntity() instanceof Player) {
                reveal(c, true);
                return;
            }
            advance(c);
        });

        // projectiles: he sometimes windmills the staff and shrugs the next hits off
        Abilities.register(ID + "_Parry", c -> {
            var src = Abilities.lastDamage();
            if (src == null || !src.is(DamageTypeTags.IS_PROJECTILE) || !offGcd(c) || setPiece(c) || fight(c).staffOut || !c.cooldown("Parry", 6)) return;
            clip(c, "parry");
            self(c, "RESISTANCE", 18, 2);
            BossKit.ring(c.level(), c.self.position(), ParticleTypes.SWEEP_ATTACK, 1.8, 6, 1, 2);
            c.sound("item.shield.block", 1.5, 0.7);
        });

        // removed without dying (altar timeout / abandon / /altars end, a vanish): clones and set pieces go with him
        Abilities.register(ID + "_Cleanup", c -> {
            for (var clone : BossKit.mobs(c.self, CLONE, 96)) clone.discard();
            clearPieces(c, false);
        });

        Abilities.register(ID + "_Fall", c -> {
            if (c.mob instanceof ArcanaMonster m) m.setTint(0x8C8C8C);
            hide(c, false);
            c.mob.setBoneVisible("cloud", false);
            c.mob.setBoneVisible("peach", false);
            c.mob.setBoneVisible("staff", true);
            for (var clone : BossKit.mobs(c.self, CLONE, 64)) clone.discard();
            clearPieces(c, true);
            c.sound("entity.ravager.death", 3, 0.5);
            c.sound("block.stone.break", 2, 0.5);
            sfx(c, "ability_gong");
            Fx.particles(c.self, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), 120, 1, 1.5, 1.5, 0.02);
            Fx.particles(c.self, ParticleTypes.WAX_OFF, 80, 1, 2, 2, 0.5);
            title(c, "&6&lThe Great Sage Falls", "&eHe turns back to the stone he was born from");
            say(c, "&6Wukong: &f\"Hah... five hundred years under a mountain... and still you beat me...\"");
            BossKit.message(c.self, 48, "&6The Great Sage kneels... and turns back to the stone he was born from. "
                    + "&eThe Ruyi Jingu Bang goes to the one who struck hardest.");
        });

        // ambient: golden motes and sparks off the plumes; harder phases burn hotter
        Abilities.register(ID + "_Aura", c -> {
            if (fight(c).hidden) return;
            int ph = phase(c);
            Fx.particles(c.self, ParticleTypes.WAX_OFF, 3, 0.6, 1.2, 2.2, 0.2);
            if (ph >= 2) {
                Fx.particles(c.self, ParticleTypes.SMALL_FLAME, 2, 0.5, 0.6, 2.6, 0.01);
                Fx.particles(c.self, GOLD, 2, 0.5, 1.2, 1.6, 0);
            }
            if (ph == 3) Fx.particles(c.self, ParticleTypes.FLAME, 3, 0.8, 1, 1.4, 0.02);
            if (ph == 4) {
                Fx.particles(c.self, ParticleTypes.END_ROD, 2, 0.7, 1.2, 1.6, 0.02);
                Fx.particles(c.self, ParticleTypes.SOUL_FIRE_FLAME, 2, 0.6, 0.8, 1.2, 0.01);
            }
        });

        Abilities.register(ID + "_Tick", WukongSkills::tick);
    }

    /** Boss bar, speed and strength for a phase (also after a restart). */
    private static void restyle(Ctx c, int ph) {
        switch (ph) {
            case 2 -> bar(c, "Sun Wukong - Great Sage Equal to Heaven", BossEvent.BossBarColor.YELLOW);
            case 3 -> bar(c, "Sun Wukong - Havoc in Heaven", BossEvent.BossBarColor.RED);
            case 4 -> bar(c, "Sun Wukong - In Buddha's Palm", BossEvent.BossBarColor.PURPLE);
            default -> bar(c, "Sun Wukong - Handsome Monkey King", BossEvent.BossBarColor.YELLOW);
        }
        if (ph >= 2) {
            self(c, "SPEED", 20 * 60 * 60, ph >= 3 ? 1 : 0);
            self(c, "STRENGTH", 20 * 60 * 60, 0);
        }
    }

    private static void clearPieces(Ctx c, boolean slowly) {
        Fight f = fight(c);
        if (!slowly) {
            dropPieces(f);
        } else {
            for (SetPieces.Piece p : f.shrines) p.remove();
            f.shrines.clear();
            for (SetPieces.Piece p : f.pieces) Tasks.later(80, () -> p.sink(40, 12));
            f.pieces.clear();
            for (int i = 0; i < 5; i++) {
                SetPieces.Piece p = f.fingers[i];
                if (p == null) continue;
                Tasks.later(80, () -> p.sink(40, 12));
                f.fingers[i] = null;
            }
            if (f.mountain != null) {
                SetPieces.Piece m = f.mountain;
                Tasks.later(100, () -> m.sink(60, 8));
                f.mountain = null;
            }
        }
        AttributeInstance scale = c.self.getAttribute(Attributes.SCALE);
        if (scale != null) scale.removeModifier(GIANT);
        f.hidden = f.riding = f.feasting = f.staffOut = false;
    }

    /** Removes every display piece of a fight at once (shrines, set pieces, palm fingers, the mountain) and their timers. */
    private static void dropPieces(Fight f) {
        for (SetPieces.Piece p : f.shrines) p.remove();
        f.shrines.clear();
        if (f.realShrine != null) f.realShrine.remove();
        f.realShrine = null;
        for (SetPieces.Piece p : f.pieces) p.remove();
        f.pieces.clear();
        for (int i = 0; i < 5; i++) {
            if (f.fingers[i] != null) f.fingers[i].remove();
            f.fingers[i] = null;
        }
        if (f.mountain != null) f.mountain.remove();
        f.mountain = null;
    }

    // ------------------------------------------------------------------ 1 -> 2: Great Sage Equal to Heaven

    private static void ascend(Ctx c) {
        beginPiece(c);
        c.mob.setVar("phase", 2);
        c.mob.setVar("untouchable", 1);
        gcd(c, 70);
        c.self.getNavigation().stop();
        clip(c, "ascend");
        c.sound("entity.fox.screech", 2.5, 0.6);
        say(c, "&6Wukong: &f\"The Jade Emperor named me a stable boy? I am the &6&lGREAT SAGE EQUAL TO HEAVEN&f!\"");
        repeat(c, 6, 4, () -> c.particles(ParticleTypes.CLOUD, 12, 0.8, 0.4, 0.4));
        later(c, 12, () -> c.mob.setBoneVisible("cloud", true));   // the clip puffs the cloud under his landing
        later(c, 26, () -> c.mob.setBoneVisible("cloud", false));
        later(c, 32, () -> {
            sfx(c, "ability_slam");
            c.sound("entity.generic.explode", 2.5, 0.6);
            c.particles(ParticleTypes.EXPLOSION_EMITTER, 1, 0, 0, 0);
            quake(c, 18, 0.9, 0.9, 3, 8, 1.3, 1.2);
            shake(c, 2.6f, 22);
        });
        later(c, 40, () -> {
            title(c, "&6&lGreat Sage Equal to Heaven", "&eThe golden circlet blazes");
            sfx(c, "ability_gong");
            restyle(c, 2);
            BossKit.ring(c.level(), c.self.position(), ParticleTypes.END_ROD, 3, 16, 1, 1);
            c.particles(ParticleTypes.TOTEM_OF_UNDYING, 120, 1, 2, 2);
        });
        later(c, 60, () -> {
            c.mob.setVar("untouchable", 0);
            endPiece(c);
        });
    }

    // ------------------------------------------------------------------ 2 -> 3: Havoc in Heaven + the Peach Feast

    private static void havoc(Ctx c) {
        beginPiece(c);
        c.mob.setVar("phase", 3);
        c.mob.setVar("untouchable", 1);
        gcd(c, 80);
        clip(c, "havoc");
        sfx(c, "ability_roar");
        title(c, "&c&lHAVOC IN HEAVEN", "&6The Monkey King storms the Celestial Palace");
        say(c, "&6Wukong: &f\"Heaven's soldiers, Heaven's generals... and not one of them could hold me! &c&lHAVOC IN HEAVEN!&f\"");
        Vec3 home = center(c);
        repeat(c, 8, 5, () -> {
            double a = c.self.getRandom().nextDouble() * Math.PI * 2, d = 6 + c.self.getRandom().nextDouble() * 10;
            Vec3 at = onGround(c.level(), home.add(Math.cos(a) * d, 0, Math.sin(a) * d));
            var bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(c.level(), net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
            if (bolt != null) {
                bolt.setPos(at.x, at.y, at.z);
                bolt.setVisualOnly(true);
                c.level().addFreshEntity(bolt);
            }
        });
        later(c, 48, () -> {
            sfx(c, "ability_slam");
            c.sound("entity.generic.explode", 3, 0.5);
            quake(c, 22, 1, 1, 3, 10, 1.4, 1.3);
            shake(c, 3f, 26);
            restyle(c, 3);
            c.particles(ParticleTypes.TOTEM_OF_UNDYING, 160, 1.5, 2, 2);
        });
        later(c, 64, () -> {
            c.mob.setVar("untouchable", 0);
            feast(c, true);
        });
    }

    /** Sits down to the Peaches of Immortality: heals unless enough damage makes him choke. */
    private static boolean feast(Ctx c, boolean transition) {
        Fight f = fight(c);
        if (!transition) beginPiece(c);
        f.feasting = true;
        f.feastStart = c.self.getHealth();
        f.feastHealed = 0;
        f.feastUntil = c.level().getGameTime() + 120;
        c.self.getNavigation().stop();
        c.mob.setBoneVisible("peach", true);
        if (c.mob instanceof ArcanaMonster m) m.setIdleClip("peach_eat");
        c.mob.play("peach_eat");
        gcd(c, 130);
        say(c, "&6Wukong sits down to a feast of &dPeaches of Immortality&6! &eHit him hard enough to make him choke!");
        return true;
    }

    private static void feastTick(Ctx c, Fight f) {
        long now = c.level().getGameTime();
        float max = c.self.getMaxHealth();
        float taken = f.feastStart + f.feastHealed - c.self.getHealth();
        if (taken >= max * 0.06f) {
            // he chokes on a peach stone
            f.feasting = false;
            c.mob.setBoneVisible("peach", false);
            clip(c, "peach_choke");
            c.sound("entity.player.hurt_drown", 1.6, 0.6);
            c.sound("entity.fox.hurt", 1.6, 0.5);
            say(c, "&eWukong chokes on a peach stone! &6Strike while he's reeling!");
            c.mob.setVar("vulnerable", 30);
            c.particles(new ItemParticleOption(ParticleTypes.ITEM, Items.APPLE), 20, 0.4, 0.4, 2.2);
            if (c.mob instanceof ArcanaMonster m) later(c, 24, () -> m.setIdleClip("dizzy"));
            later(c, 100, () -> {
                c.mob.setVar("vulnerable", 0);
                endPiece(c);
            });
            return;
        }
        long left = f.feastUntil - now;
        if (left > 0 && left % 20 == 0) {
            float heal = max * 0.02f;
            c.self.heal(heal);
            f.feastHealed += heal;
            sfx(c, "ability_chew");
            c.particles(ParticleTypes.HEART, 3, 0.5, 0.4, 3);
            c.particles(new ItemParticleOption(ParticleTypes.ITEM, Items.APPLE), 8, 0.3, 0.2, 2.2);
        }
        if (left <= 0) {
            f.feasting = false;
            c.mob.setBoneVisible("peach", false);
            clip(c, "laugh");
            sfx(c, "ability_laugh");
            say(c, "&6Wukong: &f\"Ahh, a thousand years of life in every bite! Delicious!\"");
            later(c, 40, () -> endPiece(c));
        }
    }

    // ------------------------------------------------------------------ 3 -> 4: Buddha's Palm

    private static void palm(Ctx c) {
        Fight f = fight(c);
        beginPiece(c);
        c.mob.setVar("phase", 4);
        c.mob.setVar("untouchable", 1);
        c.mob.setVar("floor", 0);
        gcd(c, 40);
        clip(c, "palm_look");
        sfx(c, "ability_gong");
        restyle(c, 4);
        title(c, "&e&lBuddha's Palm", "&6Claim the five golden fingers to seal the Monkey King");
        say(c, "&e&oA vast golden palm opens over the battlefield...");
        later(c, 20, () -> say(c, "&6Wukong: &f\"One somersault carries me a hundred and eight thousand li! I'll leap clean out of your hand, old Buddha!\""));
        raiseFingers(c, f);
        later(c, 34, () -> {
            c.mob.setVar("setpiece", 0);
            c.mob.setBusy(false);
        });
    }

    private static void raiseFingers(Ctx c, Fight f) {
        Vec3 home = center(c);
        double base = c.self.getRandom().nextDouble() * Math.PI * 2;
        for (int i = 0; i < 5; i++) {
            if (f.fingers[i] != null) f.fingers[i].remove();
            double a = base + i * 2 * Math.PI / 5;
            Vec3 at = onGround(c.level(), home.add(Math.cos(a) * 11, 0, Math.sin(a) * 11));
            f.fingerAt[i] = at;
            f.fingers[i] = SetPieces.finger(c.level(), at, i == 0 ? 7.5f : 9.5f - Math.abs(i - 2.5f), 30);
            f.claim[i] = 0;
        }
        f.finale = true;
        f.finaleUntil = c.level().getGameTime() + 34 + 760;
        f.sealed = false;
        shake(c, home, 40, 1.4f, 30);
    }

    private static void finaleTick(Ctx c, Fight f) {
        ServerLevel level = c.level();
        long now = level.getGameTime();
        if (f.sealed) return;
        int claimed = 0;
        for (int i = 0; i < 5; i++) {
            Vec3 at = f.fingerAt[i];
            if (at == null) continue;
            if (f.claim[i] >= CLAIM) {
                claimed++;
                if (now % 10 == 0) level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1, at.z, 3, 0.6, 1.5, 0.6, 0.02);
                continue;
            }
            boolean someone = false;
            for (ServerPlayer p : level.players()) {
                if (!BossFx.targetable(p)) continue;
                double dx = p.getX() - at.x, dz = p.getZ() - at.z;
                if (dx * dx + dz * dz <= 2.8 * 2.8 && Math.abs(p.getY() - at.y) < 3) {
                    someone = true;
                    break;
                }
            }
            if (someone) {
                f.claim[i]++;
                if (f.claim[i] % 10 == 0) level.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(),
                        net.minecraft.sounds.SoundSource.HOSTILE, 1f, 0.6f + f.claim[i] / (float) CLAIM);
                if (f.claim[i] >= CLAIM) {
                    level.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.BELL_BLOCK, net.minecraft.sounds.SoundSource.HOSTILE, 2f, 1.2f);
                    level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 4, at.z, 60, 0.6, 3, 0.6, 0.3);
                    int n = 0;
                    for (int k = 0; k < 5; k++) if (f.claim[k] >= CLAIM) n++;
                    say(c, "&eA golden finger is claimed &6(" + n + "/5)");
                }
            } else if (f.claim[i] > 0 && now % 4 == 0) {
                f.claim[i]--;
            }
            // the progress ring: a golden arc around the base, the rest dim (two arc payloads; the dust fades in ~12 ticks)
            if (now % 3 == 0) {
                int pts = 24;
                int lit = Math.round(pts * f.claim[i] / (float) CLAIM);
                Fx.arc(level, at, GOLD, 2.8, pts, 0, lit, 0.15);
                Fx.arc(level, at, WHITE, 2.8, pts, lit, pts - lit, 0.15);
            }
        }
        if (claimed == 5) {
            seal(c, f, false);
            return;
        }
        if (now >= f.finaleUntil) {
            if (f.fails >= 1) {
                say(c, "&e&oThe Buddha's patience runs out. The palm closes on its own.");
                seal(c, f, true);
            } else {
                escape(c, f);
            }
        }
    }

    /** The fingers were not claimed in time: he laughs, heals a little and the fingers crash down; they come back later. */
    private static void escape(Ctx c, Fight f) {
        f.fails++;
        f.finale = false;
        beginPiece(c);
        clip(c, "laugh");
        sfx(c, "ability_laugh");
        say(c, "&6Wukong: &f\"Ha! I wrote my name on the pillars at the end of the world! Not even the Buddha can hold me!\"");
        c.self.heal(c.self.getMaxHealth() * 0.06f);
        c.mob.setVar("untouchable", 0);
        c.mob.setVar("floor", 50);   // he can be worn down, but only the palm can finish him
        for (int i = 0; i < 5; i++) {
            SetPieces.Piece p = f.fingers[i];
            Vec3 at = f.fingerAt[i];
            if (p == null || at == null) continue;
            p.sink(14, 10);
            f.fingers[i] = null;
            for (ServerPlayer pl : players(c.self, 64)) {
                if (pl.position().distanceTo(at) < 4) SetPieces.hit(c.level(), pl, c.self, new BossFx.Hit(8, 0.8, 1.0, false), at);
            }
            c.level().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GOLD_BLOCK.defaultBlockState()), at.x, at.y + 1, at.z, 40, 1, 1, 1, 0.2);
        }
        shake(c, 1.6f, 16);
        f.nextFinale = c.level().getGameTime() + 400;
        later(c, 40, () -> endPiece(c));
        later(c, 400, () -> {
            if (f.sealedDone || f.finale) return;
            c.mob.setVar("untouchable", 1);
            title(c, "&e&lBuddha's Palm", "&6The fingers rise again. Claim all five!");
            raiseFingers(c, f);
        });
    }

    /** All five fingers claimed: the palm closes and the Five Elements Mountain falls on him. */
    private static void seal(Ctx c, Fight f, boolean forced) {
        f.sealed = true;
        f.finale = false;
        beginPiece(c);
        Vec3 home = onGround(c.level(), center(c));
        Fx.particles(c.self, ParticleTypes.POOF, 40, 0.6, 1, 1, 0.08);
        c.self.teleportTo(home.x, home.y, home.z);
        c.self.setDeltaMovement(Vec3.ZERO);
        c.mob.setBoneVisible("cloud", false);
        clip(c, "palm_look");
        if (!forced) title(c, "&6&lThe Palm Closes", "&eThe Monkey King never left Buddha's hand");
        say(c, "&e&oThe five fingers close... they were never pillars at the end of the world, but the Buddha's own hand.");
        for (int i = 0; i < 5; i++) SetPieces.close(f.fingers[i], home, 18);
        c.sound("block.beacon.deactivate", 2.5, 0.5);
        later(c, 18, () -> {
            f.mountain = SetPieces.mountain(c.level(), home, 8);
            sfx(c, "ability_gong");
        });
        later(c, 30, () -> {
            if (c.mob instanceof ArcanaMonster m) m.setIdleClip("sealed");
            c.mob.play("sealed");
        });
        later(c, 18 + 1 + 2 * (SetPieces.MOUNTAIN_ROCKS - 1) + 8, () -> {
            shake(c, home, 48, 3.2f, 30);
            c.sound("entity.generic.explode", 3, 0.5);
            c.mob.setVar("untouchable", 0);
            c.mob.setVar("floor", 0);
            c.mob.setVar("vulnerable", 100);
            title(c, "&6&lFive Elements Mountain", "&eHe's pinned! Strike now!");
            say(c, "&6Wukong is sealed beneath the &lFive Elements Mountain&6 and takes double damage!");
            for (int i = 0; i < 5; i++) {
                SetPieces.Piece p = f.fingers[i];
                if (p != null) Tasks.later(10, () -> p.sink(30, 12));
                f.fingers[i] = null;
            }
        });
        int free = 18 + 1 + 2 * (SetPieces.MOUNTAIN_ROCKS - 1) + 8 + 240;
        later(c, free - 240, () -> repeat(c, 12, 20, () -> {
            if (f.sealed) sfx(c, "ability_strain");
        }));
        later(c, free, () -> breakFree(c, f));
    }

    private static void breakFree(Ctx c, Fight f) {
        if (!c.self.isAlive()) return;
        f.sealed = false;
        f.sealedDone = true;
        clip(c, "break_free");
        sfx(c, "ability_roar");
        later(c, 12, () -> {
            SetPieces.shatter(c.level(), f.mountain);
            f.mountain = null;
            c.mob.setVar("vulnerable", 0);
            shake(c, 3f, 24);
            for (ServerPlayer p : players(c.self, 7)) {
                SetPieces.hit(c.level(), p, c.self, new BossFx.Hit(10, 1.0, 1.6, false), c.self.position());
            }
            say(c, "&6Wukong: &f\"Five hundred years? Not today!\"");
        });
        later(c, 32, () -> endPiece(c));
    }

    // ------------------------------------------------------------------ the per-tick driver

    private static void tick(Ctx c) {
        if (!c.self.isAlive()) return;
        Fight f = fight(c);
        if (f.feasting) feastTick(c, f);
        if (f.hidden) shrineTick(c, f);
        if (f.riding) rideTick(c, f);
        if (f.finale) finaleTick(c, f);
        else palmWatchdog(c, f);
        // Phase changes also run from here: at the health floor a hit deals 0, which isn't damage, so the damage
        // trigger never fires again and he would stay in his phase (unkillable) for good.
        if (c.self.tickCount % 10 == 0 && c.mob.var("untouchable") == 0 && !f.hidden) advance(c);
    }

    /** Crosses into the next phase when his health is below its threshold (no set piece running). */
    private static void advance(Ctx c) {
        if (setPiece(c)) return;
        float hp = c.self.getHealth() / c.self.getMaxHealth();
        int ph = phase(c);
        if (ph == 1 && hp < P2) ascend(c);
        else if (ph == 2 && hp < P3) havoc(c);
        else if (ph == 3 && hp < P4) palm(c);
    }

    /**
     * Phase 4 must always lead to the seal: while he still can't be finished (untouchable, or held at the floor after an
     * escape) and no palm is up, sealing or due, the fingers rise again. This covers a lost re-raise and a fight whose
     * state was lost (the Fight map is per entity object: an unload/reload or restart mid-finale makes a fresh one while
     * the mob's saved vars still say phase 4), which would otherwise leave him unkillable.
     */
    private static void palmWatchdog(Ctx c, Fight f) {
        if (phase(c) < 4 || f.sealed || f.sealedDone) return;
        if (c.mob.var("floor") <= 0 && c.mob.var("untouchable") == 0) return;
        long now = c.level().getGameTime();
        if (f.nextFinale == 0) {
            // fresh state: the scheduled steps of whatever was running went with the old entity, so a saved set-piece
            // flag would never clear; end it, give a moment, then the palm returns
            f.nextFinale = now + 100;
            if (setPiece(c)) endPiece(c);
        }
        if (setPiece(c) || now < f.nextFinale + 40) return;
        c.mob.setVar("untouchable", 1);
        title(c, "&e&lBuddha's Palm", "&6The fingers rise again. Claim all five!");
        raiseFingers(c, f);
    }

    // ================================================================== moves

    private static void registerMoves() {
        // ---------------------------------------------------------- melee (replaces the base hit)
        Abilities.registerMelee(ID + "_Melee", c -> {
            Fight f = fight(c);
            if (setPiece(c) || f.hidden || !c.cooldown("Melee", 1)) return;
            if (f.staffOut) {
                // no staff in hand: a flying kick
                clip(c, "attack3");
                later(c, 7, () -> {
                    List<ServerPlayer> hit = players(c.self, 3.5);
                    damage(c, hit, 9);
                    BossKit.throwAway(c.self, hit, 7, 3);
                });
                return;
            }
            String pick = switch (c.self.getRandom().nextInt(3)) {
                case 0 -> ID + "_Swing";
                case 1 -> ID + "_Smash";
                default -> ID + "_Thrust";
            };
            Abilities.runSkill(c.mob, pick, c.target);
        });

        Abilities.register(ID + "_Swing", c -> {
            clip(c, "attack");
            later(c, 8, () -> {
                c.particles(ParticleTypes.SWEEP_ATTACK, 4, 1.5, 0.3, 2);
                List<ServerPlayer> hit = players(c.self, 4.5);
                damage(c, hit, 14);
                BossKit.throwAway(c.self, hit, 6, 3);
            });
        });

        Abilities.register(ID + "_Smash", c -> {
            clip(c, "attack2");
            later(c, 11, () -> {
                c.sound("entity.iron_golem.attack", 1.6, 0.6);
                damage(c, players(c.self, 4), 18);
                quake(c, 4, 0.8, 0.4, 1, 0);
                shake(c, c.self.position(), 12, 0.8f, 8);
            });
        });

        Abilities.register(ID + "_Thrust", c -> {
            Entity target = target(c);
            clip(c, "attack3");
            later(c, 7, () -> {
                c.sound("item.trident.throw", 1.5, 0.6);
                if (target == null) return;
                Vec3 to = target.position().add(0, 1, 0);
                BossKit.line(c.level(), c.self, 2, to, ParticleTypes.CRIT, 0.5);
                for (ServerPlayer p : BossKit.playersInLine(c.self, to, 1)) damage(c, p, 16);
                BossKit.throwAway(c.self, target, 10, 2);
            });
        });

        // ---------------------------------------------------------- close range
        move("Combo", (c, t) -> {
            if (distance(c.self, t) >= 6 || !c.cooldown("Combo", 8)) return false;
            gcd(c, 58);
            clip(c, "combo");
            self(c, "SLOWNESS", 44, 4);
            later(c, 8, () -> damage(c, players(c.self, 5), 11));
            later(c, 15, () -> {
                List<ServerPlayer> hit = players(c.self, 5);
                damage(c, hit, 11);
                BossKit.throwAway(c.self, hit, 4, 2);
            });
            later(c, 32, () -> {
                c.sound("entity.iron_golem.attack", 2, 0.5);
                damage(c, players(c.self, 4.5), 20);
                quake(c, 7, 0.8, 0.6, 2, 6, 0.8, 0.6);
                shake(c, c.self.position(), 16, 1.2f, 10);
            });
            return true;
        });

        move("Stomp", (c, t) -> {
            if (distance(c.self, t) >= 10 || !c.cooldown("Stomp", 9)) return false;
            gcd(c, phase(c) > 1 ? 64 : 40);
            clip(c, "stomp");
            self(c, "SLOWNESS", 28, 9);
            later(c, 14, () -> {
                c.sound("entity.ravager.stunned", 2, 0.6);
                c.sound("entity.generic.explode", 1.4, 0.8);
                quake(c, 10, 0.75, 0.8, 2, 10, 1.2, 0.4);
                shake(c, c.self.position(), 16, 1.0f, 10);
                c.particles(ParticleTypes.DUST_PLUME, 40, 1.5, 0.2, 0);
            });
            // phase 2+: a second stomp right behind the first
            later(c, 24, () -> {
                if (phase(c) > 1) clip(c, "stomp");
            });
            later(c, 38, () -> {
                if (phase(c) <= 1) return;
                c.sound("entity.generic.explode", 1.6, 0.6);
                quake(c, 14, 0.9, 0.9, 2, 10, 1.3, 0.5);
                shake(c, c.self.position(), 20, 1.3f, 12);
            });
            return true;
        });

        move("StaffSpin", (c, t) -> {
            if (distance(c.self, t) >= 12 || !c.cooldown("StaffSpin", 14)) return false;
            gcd(c, 56);
            clip(c, "staff_spin");
            self(c, "SLOWNESS", 46, 9);
            c.sound("entity.breeze.whirl", 2, 0.6);
            repeat(c, 8, 4, () -> {
                for (ServerPlayer p : players(c.self, 10)) BossKit.pull(c.self, p, 1.2);
                damage(c, players(c.self, 4), 3);
                BossKit.ring(c.level(), c.self.position(), ParticleTypes.SWEEP_ATTACK, 3, 6, 1, 3.4);
                BossKit.ring(c.level(), c.self.position(), ParticleTypes.GUST, 6, 10, 1, 1);
            });
            later(c, 40, () -> {
                c.sound("entity.breeze.wind_burst", 2, 0.6);
                Fx.particles(c.self, ParticleTypes.GUST_EMITTER_LARGE, 1, 0, 0, 0, 0);
                List<ServerPlayer> hit = players(c.self, 6);
                damage(c, hit, 14);
                BossKit.throwAway(c.self, hit, 14, 6);
                shake(c, c.self.position(), 14, 1.2f, 10);
            });
            return true;
        });

        // ---------------------------------------------------------- any range
        move("GroundSlam", (c, t) -> {
            if (distance(c.self, t) >= 16 || !c.cooldown("GroundSlam", 10)) return false;
            gcd(c, 46);
            clip(c, "ground_slam");
            self(c, "SLOWNESS", 36, 9);
            later(c, 14, () -> {
                c.sound("entity.generic.explode", 2, 0.6);
                c.sound("block.anvil.land", 1.5, 0.5);
                c.particles(ParticleTypes.EXPLOSION, 3, 1, 0.2, 0);
                damage(c, players(c.self, 3.5), 16);
                quake(c, 12, 0.6, 0.7, 2, 12, 0.9, 0.5);
                shake(c, c.self.position(), 24, 1.8f, 14);
            });
            later(c, 24, () -> {
                if (phase(c) > 1) quake(c, 16, 0.7, 0.6, 2, 10, 0.8, 0.5);
            });
            later(c, 34, () -> {
                if (phase(c) >= 3) quake(c, 20, 0.8, 0.6, 2, 10, 0.8, 0.5);
            });
            return true;
        });

        move("Fissure", (c, t) -> {
            if (t == null || !c.cooldown("Fissure", 9)) return false;
            gcd(c, 40);
            clip(c, "attack2");
            self(c, "SLOWNESS", 20, 9);
            later(c, 11, () -> {
                c.sound("entity.iron_golem.attack", 2, 0.5);
                BossFx.fissure(c.level(), c.self.position(), t.position(), c.self, 18, 1.1, new BossFx.Hit(12, 1.2, 0.3, false));
                shake(c, c.self.position(), 18, 0.9f, 10);
            });
            later(c, 17, () -> {
                for (ServerPlayer p : players(c.self, 20, 3)) {
                    BossFx.fissure(c.level(), c.self.position(), p.position(), c.self, 14, 1.1, new BossFx.Hit(10, 1.1, 0.3, false));
                }
            });
            return true;
        });

        // ---------------------------------------------------------- gap closers
        move("Leap", (c, t) -> {
            double d = distance(c.self, t);
            if (d < 5 || d > 28 || !c.cooldown("Leap", 10)) return false;
            gcd(c, 52);
            clip(c, "leap");
            later(c, 8, () -> {
                c.sound("entity.goat.long_jump", 2, 0.6);
                BossKit.leap(c.self, t, 170, 22);
            });
            later(c, 33, () -> {
                c.sound("entity.generic.explode", 2.5, 0.5);
                c.particles(ParticleTypes.EXPLOSION_EMITTER, 1, 0, 0, 0);
                damage(c, players(c.self, 3.5), 18);
                quake(c, 11, 0.7, 0.8, 2, 11, 1, 0.6);
                shake(c, c.self.position(), 24, 1.8f, 14);
            });
            return true;
        });

        // plants the Ruyi Jingu Bang, vaults over it and lands feet first
        move("Vault", (c, t) -> {
            double d = distance(c.self, t);
            if (t == null || d < 5 || d > 16 || !c.cooldown("Vault", 9)) return false;
            gcd(c, 40);
            face(c.self, t);
            clip(c, "vault");
            later(c, 6, () -> {
                c.sound("block.bamboo.place", 2, 0.6);
                c.sound("entity.goat.long_jump", 1.6, 1.1);
                BossKit.leap(c.self, t, 120, 14);
            });
            later(c, 20, () -> {
                c.sound("entity.player.attack.knockback", 2, 0.6);
                List<ServerPlayer> hit = players(c.self, 3.2);
                damage(c, hit, 14);
                BossKit.throwAway(c.self, hit, 9, 4);
                c.particles(ParticleTypes.SWEEP_ATTACK, 3, 0.8, 0.3, 1);
            });
            later(c, 24, () -> {
                quake(c, 5, 0.7, 0.4, 1, 0, 0.6, 0.4);
                shake(c, c.self.position(), 10, 0.7f, 8);
            });
            return true;
        });

        // the staff shoots out ~12 blocks in a line
        move("Extend", (c, t) -> {
            double d = distance(c.self, t);
            if (d < 3 || d > 12 || !BossKit.sight(c.self, t) || !c.cooldown("Extend", 10)) return false;
            gcd(c, 48);
            clip(c, "extend");
            face(c.self, t);
            c.mob.setBusy(true);   // he stops turning while the staff is out
            self(c, "SLOWNESS", 40, 9);
            c.sound("block.beacon.power_select", 1.6, 1.6);
            later(c, 15, () -> {
                c.sound("item.trident.riptide_3", 2, 0.7);
                Vec3 end = BossKit.forward(c.self, 12);
                BossKit.line(c.level(), c.self, 1.8, end.add(0, 1, 0), ParticleTypes.WAX_OFF, 0.4);
                for (ServerPlayer p : BossKit.playersInLine(c.self, end.add(0, 1, 0), 1.4)) {
                    damage(c, p, 20);
                    BossKit.throwAway(c.self, p, 12, 3);
                }
                BossFx.fissure(c.level(), c.self.position(), end, c.self, 12, 3, null);
                shake(c, c.self.position(), 16, 1.0f, 8);
            });
            c.sched().after(35, () -> {
                if (!setPiece(c)) c.mob.setBusy(false);
            });
            return true;
        });

        // the staff spins out like a boomerang and comes back to his hand
        move("StaffThrow", (c, t) -> {
            double d = distance(c.self, t);
            if (t == null || d < 6 || d > 22 || !BossKit.sight(c.self, t) || !BossFx.enabled() || !c.cooldown("StaffThrow", 12)) return false;
            Fight f = fight(c);
            gcd(c, 60);
            face(c.self, t);
            clip(c, "staff_throw");
            c.mob.setBusy(true);
            later(c, 8, () -> {
                f.staffOut = true;
                c.mob.setBoneVisible("staff", false);
                c.sound("item.trident.throw", 2, 0.6);
                Vec3 from = c.self.position().add(0, 1.8, 0).add(BossKit.forward(c.self, 1).subtract(c.self.position()));
                Vec3 aim = t.position().add(0, 1, 0);
                Vec3 dir = aim.subtract(from).normalize();
                Vec3 to = from.add(dir.scale(Math.min(22, from.distanceTo(aim) + 4)));
                c.mob.setBusy(false);
                SetPieces.throwStaff(c.level(), from, to, () -> c.self.position().add(0, 1.6, 0), c.self, staffItem(), new BossFx.Hit(11, 0.4, 0.9, false),
                        () -> {
                            f.staffOut = false;
                            if (!c.self.isAlive()) return;
                            c.mob.setBoneVisible("staff", true);
                            if (!setPiece(c)) clip(c, "staff_catch");
                        });
            });
            return true;
        });

        // Jindou Yun: flips onto his cloud and lands behind the target, staff already coming down
        move("Somersault", (c, t) -> {
            if (distance(c.self, t) <= 7 || !c.cooldown("Somersault", 9)) return false;
            somersault(c, t);
            return true;
        });

        // ---------------------------------------------------------- Fiery Golden Eyes
        move("FireEyes", (c, t) -> {
            if (distance(c.self, t) >= 30 || !BossKit.sight(c.self, t) || !c.cooldown("FireEyes", 12)) return false;
            gcd(c, 44);
            clip(c, "fire_eyes");
            self(c, "SLOWNESS", 40, 9);
            c.sound("block.respawn_anchor.charge", 1.6, 0.7);
            later(c, 12, () -> {
                c.sound("entity.blaze.shoot", 2, 0.5);
                repeat(c, 10, 2, () -> {
                    if (!(t instanceof LivingEntity le) || !le.isAlive() || !BossKit.sight(c.self, le)) return;
                    Vec3 to = le.position().add(0, 1.2, 0);
                    BossKit.line(c.level(), c.self, 2.4, to, new DustParticleOptions(0xFFC23A, 1.6f), 0.35);
                    BossKit.line(c.level(), c.self, 2.4, to, ParticleTypes.SMALL_FLAME, 0.8);
                    damage(c, le, 2.5);
                    le.igniteForTicks(40);
                    Fx.potion(le, "SLOWNESS", 20, 1, true, true);
                });
            });
            return true;
        });

        // a low beam sweeps across the arena from his right to his left at knee height: jump it, or get behind cover
        move("EyeSweep", (c, t) -> {
            if (t == null || distance(c.self, t) >= 22 || !c.cooldown("EyeSweep", 16)) return false;
            gcd(c, 56);
            face(c.self, t);
            c.mob.setBusy(true);
            clip(c, "eye_sweep");
            c.sound("block.respawn_anchor.charge", 2, 0.6);
            float yaw = c.self.getYRot();
            Vec3 o = c.self.position();
            double beamY = o.y + 0.45;
            // telegraph: the arc he will sweep, drawn on the ground in red (one batch: 105 spots, one payload per player)
            Fx.PointBatch arc = new Fx.PointBatch(RED, o, 1, 0.1, 0, 0.1, 0);
            for (int k = -60; k <= 60; k += 6) {
                Vec3 dir = Vec3.directionFromRotation(0, yaw + k);
                for (int r = 4; r <= 20; r += 4) {
                    Vec3 p = o.add(dir.scale(r));
                    arc.add(p.x, groundY(c.level(), p.x, o.y, p.z) + 0.1, p.z);
                }
            }
            arc.send(c.level(), 20);
            java.util.Set<java.util.UUID> struck = new java.util.HashSet<>();
            for (int i = 0; i <= 28; i++) {
                final int k = i;
                later(c, 12 + i, () -> {
                    // his right is yaw + 90 in Minecraft's yaw: sweep from +60 to -60
                    float a = yaw + 60 - 120f * k / 28;
                    Vec3 dir = Vec3.directionFromRotation(0, a);
                    Vec3 eye = o.add(0, 2.5, 0).add(dir.scale(0.6));
                    Vec3 low = new Vec3(o.x, beamY, o.z).add(dir.scale(2.2));
                    Vec3 end = new Vec3(o.x, beamY, o.z).add(dir.scale(22));
                    Fx.line(c.level(), eye, low, new DustParticleOptions(0xFFC23A, 1.4f), 0.4);
                    Fx.line(c.level(), low, end, new DustParticleOptions(0xFFB020, 1.8f), 0.5);
                    if (k % 2 == 0) Fx.line(c.level(), low, end, ParticleTypes.SMALL_FLAME, 1.2);
                    if (k % 4 == 0) c.sound("entity.blaze.shoot", 1.4, 0.7 + k * 0.01);
                    for (ServerPlayer p : players(c.self, 24)) {
                        if (struck.contains(p.getUUID())) continue;
                        Vec3 rel = new Vec3(p.getX() - o.x, 0, p.getZ() - o.z);
                        double along = rel.dot(dir);
                        if (along < 1.5 || along > 22) continue;
                        double side = rel.subtract(dir.scale(along)).length();
                        if (side > 0.9) continue;
                        if (p.getY() > beamY + 0.25 || p.getY() + p.getBbHeight() < beamY - 0.3) continue;   // jumped over it
                        if (!sight(c.level(), new Vec3(o.x, beamY + 0.1, o.z), p)) continue;
                        struck.add(p.getUUID());
                        damage(c, p, 10);
                        p.igniteForTicks(60);
                        Fx.potion(p, "SLOWNESS", 30, 1, true, true);
                    }
                });
            }
            c.sched().after(46, () -> {
                if (!setPiece(c)) c.mob.setBusy(false);
            });
            return true;
        });

        // ---------------------------------------------------------- summons and set pieces
        move("Clones", (c, t) -> {
            int max = phase(c) >= 3 ? 4 : 3;
            if (BossKit.mobs(c.self, CLONE, 40).size() >= max || !c.cooldown("Clones", 25)) return false;
            gcd(c, 50);
            clip(c, "clone");
            self(c, "SLOWNESS", 40, 9);
            later(c, 26, () -> {
                c.sound("entity.breeze.idle_ground", 2, 1.2);
                c.particles(ParticleTypes.WAX_OFF, 40, 1.5, 0.5, 2.4);
                BossKit.summon(c.level(), CLONE, c.self.position(), Math.min(max, phase(c) + 1), 4);
            });
            return true;
        });

        // Heaven-propping pillar: the staff swells and copies drop on everyone
        move("Pillar", (c, t) -> {
            if (!c.cooldown("Pillar", 22)) return false;
            gcd(c, 80);
            clip(c, "pillar");
            self(c, "SLOWNESS", 70, 9);
            self(c, "RESISTANCE", 60, 1);
            c.sound("entity.warden.sonic_charge", 2, 0.6);
            say(c, "&6Wukong bellows &e&lGROW!&6 and his staff swells to the sky");
            ItemStack staff = staffItem();
            for (ServerPlayer p : players(c.self, 32)) {
                BossFx.pillar(c.level(), p.position(), c.self, staff.copy(), 6, 40, 6, 40, 3.5, new BossFx.Hit(18, 1.0, 1.2, false), 7);
                Tasks.later(46, () -> BossFx.shake(c.level(), p.position(), 16, 2.2f, 16));
            }
            later(c, 43, () -> {
                c.sound("entity.generic.explode", 3, 0.4);
                damage(c, players(c.self, 7), 22);
                quake(c, 18, 0.85, 1, 3, 12, 1.2, 0.8);
            });
            return true;
        });

        // anti-cheese: a target perched out of reach gets dragged back down
        move("SkyCheck", (c, t) -> {
            if (t == null || BossKit.altitude(t) <= 3 || !c.cooldown("SkyCheck", 6)) return false;
            gcd(c, 30);
            clip(c, "fire_eyes");
            later(c, 12, () -> {
                BossKit.line(c.level(), c.self, 2.4, t.position().add(0, 1, 0), ParticleTypes.END_ROD, 0.5);
                c.sound("entity.evoker.cast_spell", 1.6, 0.6);
                BossKit.pull(c.self, t, 4);
                damage(c, t, 6);
            });
            return true;
        });

        // mocks a far target, then somersaults onto them
        move("Taunt", (c, t) -> {
            if (distance(c.self, t) <= 14 || !c.cooldown("Taunt", 20)) return false;
            gcd(c, 44);
            boolean laugh = c.self.getRandom().nextBoolean();
            clip(c, laugh ? "laugh" : "taunt");
            if (laugh) sfx(c, "ability_laugh");
            else {
                c.sound("entity.fox.screech", 2, 0.8);
                later(c, 10, () -> c.sound("entity.fox.screech", 2, 1.1));
            }
            later(c, laugh ? 40 : 30, () -> somersault(c, t));
            return true;
        });

        move("Transform", (c, t) -> {
            if (!BossFx.enabled() || !c.cooldown("Transform", 35)) return false;
            transform(c);
            return true;
        });
        move("Cloud", (c, t) -> {
            if (t == null || !c.cooldown("Cloud", 30)) return false;
            cloud(c, t);
            return true;
        });
        move("Giant", (c, t) -> {
            if (t == null || !c.cooldown("Giant", 50)) return false;
            giant(c);
            return true;
        });
        move("Furnace", (c, t) -> {
            if (!BossFx.enabled() || !c.cooldown("Furnace", 45)) return false;
            furnace(c);
            return true;
        });
        move("HairArmy", (c, t) -> {
            if (t == null || BossKit.mobs(c.self, CLONE, 40).size() > 2 || !c.cooldown("HairArmy", 40)) return false;
            hairArmy(c, t);
            return true;
        });
        move("Feast", (c, t) -> {
            if (c.self.getHealth() > c.self.getMaxHealth() * 0.28f || !c.cooldown("Feast", 45)) return false;
            return feast(c, false);
        });
    }

    private static void somersault(Ctx c, @Nullable Entity target) {
        gcd(c, 60);
        clip(c, "somersault");
        later(c, 6, () -> {
            c.mob.setBoneVisible("cloud", true);
            c.particles(ParticleTypes.CLOUD, 40, 1.2, 0.3, 0);
            sfx(c, "ability_cloud");
        });
        later(c, 15, () -> {
            if (target == null || !target.isAlive() || target.level() != c.self.level()) return;
            double a = c.self.getRandom().nextDouble() * Math.PI * 2, s = 1 + c.self.getRandom().nextDouble() * 1.5;
            Vec3 to = onGround(c.level(), new Vec3(target.getX() + Math.cos(a) * s, target.getY(), target.getZ() + Math.sin(a) * s));
            c.self.teleportTo(to.x, to.y, to.z);
            face(c.self, target);
            c.particles(ParticleTypes.CLOUD, 40, 1.2, 0.3, 0);
            c.sound("entity.breeze.land", 2, 0.7);
        });
        later(c, 29, () -> c.mob.setBoneVisible("cloud", false));
        later(c, 31, () -> Abilities.runSkill(c.mob, ID + "_Smash", target));
    }

    // ------------------------------------------------------------------ Seventy-Two Transformations

    private static void transform(Ctx c) {
        Fight f = fight(c);
        beginPiece(c);
        c.mob.setVar("untouchable", 1);
        gcd(c, 40);
        clip(c, "transform");
        say(c, "&6Wukong: &f\"Seventy-two transformations! Which one is old Sun, hm?\"");
        later(c, 20, () -> sfx(c, "ability_poof"));
        later(c, 24, () -> {
            ServerLevel level = c.level();
            Vec3 home = center(c);
            Fx.particles(c.self, ParticleTypes.POOF, 50, 0.8, 1.2, 1.2, 0.08);
            Fx.particles(c.self, new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.WOOL.brown().defaultBlockState()), 30, 0.6, 1, 1.2, 0.02);
            int n = 5, real = c.self.getRandom().nextInt(n);
            boolean p3 = phase(c) >= 3;
            double base = c.self.getRandom().nextDouble() * Math.PI * 2;
            f.shrines.clear();
            for (int i = 0; i < n; i++) {
                double a = base + i * 2 * Math.PI / n;
                Vec3 at = onGround(level, home.add(Math.cos(a) * 7, 0, Math.sin(a) * 7));
                float yaw = (float) (Mth.atan2(home.z - at.z, home.x - at.x) * Mth.RAD_TO_DEG) - 90f;
                if (i == real) {
                    // the real one: his own hitbox, a flagpole that sways like a tail
                    SetPieces.Piece p = SetPieces.shrine(level, at, yaw, true, true, null);
                    f.realShrine = p;
                    if (p != null) f.shrines.add(p);
                    c.self.teleportTo(at.x, at.y, at.z);
                    c.self.setYRot(yaw);
                    c.self.setYBodyRot(yaw);
                } else {
                    final SetPieces.Piece[] me = new SetPieces.Piece[1];
                    me[0] = SetPieces.shrine(level, at, yaw, p3, false, player -> wrongShrine(c, me[0], player));
                    if (me[0] != null) f.shrines.add(me[0]);
                }
            }
            hide(c, true);
            f.hidden = true;
            f.hideUntil = level.getGameTime() + 280;
            c.mob.setVar("untouchable", 0);
            title(c, "", p3 ? "&eOnly one flagpole sways like a tail..." : "&eOne of these shrines has a tail...");
        });
    }

    private static void wrongShrine(Ctx c, SetPieces.@Nullable Piece shrine, ServerPlayer by) {
        Fight f = fight(c);
        if (shrine == null || !f.hidden) return;
        Vec3 at = shrine.at;
        SetPieces.burst(c.level(), shrine);
        f.shrines.remove(shrine);
        c.actionBar(by, "&cWrong shrine! &7Wukong's laughter echoes...");
        sfx(c, "ability_laugh");
        for (ServerPlayer p : players(c.self, 48)) {
            if (p.position().distanceTo(at) < 3.5) {
                SetPieces.hit(c.level(), p, c.self, new BossFx.Hit(8, 0.7, 1.0, false), at);
                p.igniteForTicks(40);
            }
        }
        if (phase(c) >= 3) BossKit.summon(c.level(), CLONE, at, 1, 0.5);
    }

    private static void shrineTick(Ctx c, Fight f) {
        long now = c.level().getGameTime();
        if (now % 20 == 0 && f.realShrine != null) {
            // a faint giggle from the real shrine now and then
            if (c.self.getRandom().nextInt(4) == 0) c.sound("entity.witch.celebrate", 0.6, 1.6);
        }
        if (now >= f.hideUntil) reveal(c, false);
    }

    /** Out of the shrine: found (dazed, takes more damage) or the time ran out (every fake blows up, he pounces). */
    private static void reveal(Ctx c, boolean found) {
        Fight f = fight(c);
        if (!f.hidden) return;
        f.hidden = false;
        ServerLevel level = c.level();
        for (SetPieces.Piece p : List.copyOf(f.shrines)) {
            if (p == f.realShrine) continue;
            if (!found) {
                for (ServerPlayer pl : players(c.self, 48)) {
                    if (pl.position().distanceTo(p.at) < 3.5) SetPieces.hit(level, pl, c.self, new BossFx.Hit(8, 0.8, 1.0, false), p.at);
                }
            }
            SetPieces.burst(level, p);
        }
        SetPieces.burst(level, f.realShrine);
        f.shrines.clear();
        f.realShrine = null;
        hide(c, false);
        clip(c, "reveal");
        Fx.particles(c.self, ParticleTypes.POOF, 50, 0.8, 1.2, 1.2, 0.08);
        sfx(c, "ability_poof");
        if (found) {
            say(c, "&eFound him! &6The Monkey King tumbles out of his disguise, dazed.");
            c.mob.setVar("vulnerable", 30);
            later(c, 14, () -> {
                if (c.mob instanceof ArcanaMonster m) m.setIdleClip("dizzy");
            });
            later(c, 74, () -> {
                c.mob.setVar("vulnerable", 0);
                endPiece(c);
            });
        } else {
            sfx(c, "ability_laugh");
            say(c, "&6Wukong: &f\"Too slow! Even Erlang Shen did better than that!\"");
            ServerPlayer near = nearest(c, 40);
            later(c, 14, () -> {
                endPiece(c);
                if (near != null) somersault(c, near);
            });
        }
    }

    // ------------------------------------------------------------------ Cloud Barrage

    private static void cloud(Ctx c, Entity target) {
        Fight f = fight(c);
        beginPiece(c);
        gcd(c, 40);
        c.mob.setBoneVisible("cloud", true);
        clip(c, "somersault");
        sfx(c, "ability_cloud");
        c.particles(ParticleTypes.CLOUD, 50, 1.4, 0.3, 0);
        say(c, "&6Wukong leaps onto his &fsomersault cloud&6!");
        f.rideTarget = target;
        f.rideY = c.self.getY() + 7;
        later(c, 6, () -> {
            f.riding = true;
            c.self.setNoGravity(true);
            if (c.mob instanceof ArcanaMonster m) {
                // he moves while hovering: the walk clip must be the ride too
                m.setIdleClip("cloud_ride");
                m.setWalkClip("cloud_ride");
            }
        });
        ItemStack staff = staffItem();
        int strikes = phase(c) >= 3 ? 6 : 5;
        for (int i = 0; i < strikes; i++) {
            int at = 30 + i * 22;
            later(c, at, () -> {
                ServerPlayer p = randomFighter(c, 32);
                if (p == null) return;
                f.rideTarget = p;
                Vec3 lead = p.position().add(p.getDeltaMovement().multiply(8, 0, 8));
                BossFx.pillar(c.level(), lead, c.self, staff.copy(), 2.2f, 14, 4, 8, 2.6, new BossFx.Hit(12, 0.9, 0.8, false), 4.5);
                Tasks.later(18, () -> BossFx.shake(c.level(), lead, 14, 1.4f, 10));
            });
            later(c, at + 11, () -> clip(c, "cloud_strike"));
        }
        int dive = 30 + strikes * 22 + 6;
        later(c, dive, () -> {
            Entity t = f.rideTarget != null && f.rideTarget.isAlive() ? f.rideTarget : nearest(c, 40);
            Vec3 land = t == null ? onGround(c.level(), c.self.position()) : onGround(c.level(), t.position());
            clip(c, "cloud_dive");
            sfx(c, "ability_kiai");
            f.riding = false;
            c.mob.setBoneVisible("cloud", false);
            c.particles(ParticleTypes.CLOUD, 40, 1.2, 0.3, 0);
            ring(c, land, RED, 4, 24);
            Vec3 from = c.self.position();
            for (int k = 1; k <= 18; k++) {
                final double u = k / 18.0;
                later(c, k, () -> {
                    double arc = Math.sin(u * Math.PI) * 2.5;
                    Vec3 p = from.add(land.subtract(from).scale(u)).add(0, arc, 0);
                    c.self.teleportTo(p.x, p.y, p.z);
                    c.self.setDeltaMovement(Vec3.ZERO);
                    if (u < 0.9) ring(c, land, RED, 4 * (1 - u) + 0.6, 16);
                });
            }
            later(c, 18, () -> {
                c.self.setNoGravity(false);
                sfx(c, "ability_slam");
                c.particles(ParticleTypes.EXPLOSION_EMITTER, 1, 0, 0, 0);
                damage(c, players(c.self, 4), 18);
                quake(c, 13, 0.8, 0.9, 2, 12, 1.1, 0.7);
                shake(c, c.self.position(), 28, 2.4f, 18);
            });
            later(c, 34, () -> endPiece(c));
        });
    }

    private static void rideTick(Ctx c, Fight f) {
        Entity t = f.rideTarget;
        Vec3 pos = c.self.position();
        double tx = t != null ? t.getX() : pos.x, tz = t != null ? t.getZ() : pos.z;
        double dx = tx - pos.x, dz = tz - pos.z, d = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(0.22, Math.max(0, d - 3));
        double nx = d > 1e-3 ? pos.x + dx / d * step : pos.x, nz = d > 1e-3 ? pos.z + dz / d * step : pos.z;
        double ny = pos.y + Mth.clamp(f.rideY - pos.y, -0.4, 0.4);
        c.self.teleportTo(nx, ny, nz);
        c.self.setDeltaMovement(Vec3.ZERO);
        if (t != null) face(c.self, t);
        if (c.self.tickCount % 2 == 0) c.level().sendParticles(ParticleTypes.CLOUD, nx, ny - 0.2, nz, 3, 0.8, 0.1, 0.8, 0.01);
    }

    // ------------------------------------------------------------------ Faxiang Tiandi

    private static void giant(Ctx c) {
        beginPiece(c);
        c.mob.setVar("untouchable", 1);
        gcd(c, 60);
        clip(c, "giant_grow");
        sfx(c, "ability_grow");
        title(c, "&6&lFaxiang Tiandi", "&eWukong grows to the size of heaven and earth");
        AttributeInstance scale = c.self.getAttribute(Attributes.SCALE);
        for (int k = 0; k <= 24; k++) {
            final double v = 1.4 * Mth.smoothstep(k / 24f);
            later(c, 6 + k, () -> {
                if (scale == null) return;
                scale.removeModifier(GIANT);
                scale.addTransientModifier(new AttributeModifier(GIANT, v, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            });
        }
        later(c, 20, () -> shake(c, 1.4f, 30));
        later(c, 36, () -> c.mob.setVar("untouchable", 0));
        for (int i = 0; i < 3; i++) {
            int at = 36 + i * 44;
            later(c, at, () -> {
                ServerPlayer p = randomFighter(c, 30);
                if (p == null) return;
                Vec3 o = c.self.position();
                Vec3 d = p.position().subtract(o).multiply(1, 0, 1);
                double len = d.length();
                Vec3 spot = onGround(c.level(), len > 9 ? o.add(d.scale(9 / len)) : p.position());
                face(c.self, spot);
                clip(c, "giant_slam");
                for (int k = 0; k < 26; k += 2) {
                    final double u = k / 26.0;
                    Tasks.later(k, () -> {
                        BossKit.ring(c.level(), spot, RED, 4.5 * (1 - u * 0.4), 32, 1, 0.15);
                        BossKit.ring(c.level(), spot, RED, 4.5, 32, 1, 0.15);
                    });
                }
                later(c, 26, () -> {
                    sfx(c, "ability_slam");
                    c.level().sendParticles(ParticleTypes.EXPLOSION_EMITTER, spot.x, spot.y, spot.z, 2, 1, 0, 1, 0);
                    for (ServerPlayer pl : players(c.self, 40)) {
                        if (pl.position().distanceTo(spot) < 4.5) SetPieces.hit(c.level(), pl, c.self, new BossFx.Hit(20, 1.3, 1.0, false), spot);
                    }
                    quake(c, spot, 16, 0.9, 1.1, 3, 8, 1.0, 0.6);
                    shake(c, spot, 40, 4f, 24);
                });
            });
        }
        int sweep = 36 + 3 * 44;
        later(c, sweep, () -> {
            clip(c, "giant_sweep");
            for (int k = 0; k < 18; k += 2) Tasks.later(k, () -> BossKit.ring(c.level(), c.self.position(), GOLD, 9, 48, 1, 0.15));
        });
        later(c, sweep + 18, () -> {
            sfx(c, "ability_whoosh");
            c.sound("entity.breeze.wind_burst", 3, 0.4);
            for (ServerPlayer p : players(c.self, 9.5)) {
                if (p.getY() > c.self.getY() + 4) continue;
                SetPieces.hit(c.level(), p, c.self, new BossFx.Hit(14, 0.7, 1.8, false), c.self.position());
            }
            BossKit.ring(c.level(), c.self.position(), ParticleTypes.SWEEP_ATTACK, 6, 24, 1, 2);
            shake(c, 2f, 14);
        });
        int shrink = sweep + 44;
        for (int k = 0; k <= 20; k++) {
            final double v = 1.4 * (1 - Mth.smoothstep(k / 20f));
            later(c, shrink + k, () -> {
                if (scale == null) return;
                scale.removeModifier(GIANT);
                if (v > 1e-3) scale.addTransientModifier(new AttributeModifier(GIANT, v, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            });
        }
        later(c, shrink + 22, () -> endPiece(c));
    }

    // ------------------------------------------------------------------ Eight Trigrams Furnace

    private static void furnace(Ctx c) {
        Fight f = fight(c);
        beginPiece(c);
        gcd(c, 40);
        Vec3 home = onGround(c.level(), center(c));
        Fx.particles(c.self, ParticleTypes.POOF, 40, 0.6, 1, 1, 0.08);
        c.self.teleportTo(home.x, home.y, home.z);
        if (c.mob instanceof ArcanaMonster m) m.setIdleClip("furnace");
        c.mob.play("furnace");
        title(c, "&c&lEight Trigrams Furnace", "&6Find the trigram that does not burn");
        say(c, "&6Wukong: &f\"Laozi cooked me in his furnace for forty-nine days, and all it gave me were these golden eyes! Now YOU burn!\"");
        SetPieces.Piece glyphs = SetPieces.trigrams(c.level(), home, 9);
        if (glyphs != null) f.pieces.add(glyphs);
        // the first safe sector is xun (wind): the smoky corner where he hid in the story
        int[] safe = new int[3];
        safe[0] = 4;
        for (int w = 1; w < 3; w++) {
            int s;
            do {
                s = c.self.getRandom().nextInt(8);
            } while (Math.abs(s - safe[w - 1]) <= 1 || Math.abs(s - safe[w - 1]) == 7);
            safe[w] = s;
        }
        // ground heights of the arena's columns for this cast (home.y is fixed; the same answer every time a spot repeats)
        Long2DoubleOpenHashMap ground = new Long2DoubleOpenHashMap();
        for (int w = 0; w < 3; w++) {
            final int wave = w;
            int start = 20 + w * 56;
            // telegraph 30 ticks: smoke and embers over the burning sectors, a cool white glow over the safe one; each
            // burst goes out as three point batches (one payload each per player) instead of a packet per spot
            for (int k = 0; k < 30; k += 3) {
                later(c, start + k, () -> {
                    ServerLevel level = c.level();
                    Fx.PointBatch white = new Fx.PointBatch(WHITE, home, 1, 0, 0.2, 0, 0);
                    Fx.PointBatch smoke = new Fx.PointBatch(ParticleTypes.SMOKE, home, 1, 0.1, 0.05, 0.1, 0.01);
                    Fx.PointBatch embers = new Fx.PointBatch(ParticleTypes.SMALL_FLAME, home, 1, 0.1, 0.05, 0.1, 0.01);
                    for (int s = 0; s < 8; s++) {
                        double a0 = SetPieces.sectorAngle(s) - Math.PI / 8, a1 = a0 + Math.PI / 4;
                        for (int n = 0; n < 6; n++) {
                            double a = a0 + c.self.getRandom().nextDouble() * (a1 - a0), r = 2 + c.self.getRandom().nextDouble() * 17;
                            double x = home.x + Math.cos(a) * r, z = home.z + Math.sin(a) * r, y = groundY(level, ground, x, home.y, z) + 0.1;
                            if (s == safe[wave]) white.add(x, y + 0.3, z);
                            else (n % 2 == 0 ? smoke : embers).add(x, y, z);
                        }
                    }
                    white.send(level, 20);
                    smoke.send(level, 20);
                    embers.send(level, 20);
                });
            }
            later(c, start, () -> {
                c.sound("block.fire.ambient", 3, 0.5);
                c.sound("block.respawn_anchor.charge", 2, 0.5 + wave * 0.15);
            });
            later(c, start + 30, () -> erupt(c, home, safe[wave], ground));
        }
        later(c, 20 + 3 * 56 + 4, () -> {
            if (glyphs != null) {
                glyphs.sink(20, 1);
                f.pieces.remove(glyphs);
            }
            clip(c, "laugh");
            endPiece(c);
        });
    }

    private static void erupt(Ctx c, Vec3 home, int safe, Long2DoubleOpenHashMap ground) {
        ServerLevel level = c.level();
        sfx(c, "ability_roar");
        c.sound("entity.blaze.shoot", 3, 0.5);
        c.sound("item.firecharge.use", 3, 0.6);
        shake(c, home, 30, 1.6f, 14);
        // the eruption: one flame and one lava batch (one payload each per player) instead of a packet per spot
        Fx.PointBatch flame = new Fx.PointBatch(ParticleTypes.FLAME, home, 8, 0.15, 1.0, 0.15, 0.04);
        Fx.PointBatch lava = new Fx.PointBatch(ParticleTypes.LAVA, home, 2, 0.2, 0.1, 0.2, 0);
        for (int s = 0; s < 8; s++) {
            if (s == safe) continue;
            double a0 = SetPieces.sectorAngle(s) - Math.PI / 8;
            for (int n = 0; n < 22; n++) {
                double a = a0 + c.self.getRandom().nextDouble() * Math.PI / 4, r = 1 + c.self.getRandom().nextDouble() * 19;
                double x = home.x + Math.cos(a) * r, z = home.z + Math.sin(a) * r, y = groundY(level, ground, x, home.y, z);
                flame.add(x, y + 1, z);
                if (n % 4 == 0) lava.add(x, y + 0.3, z);
            }
        }
        flame.send(level, 20);
        lava.send(level, 20);
        for (ServerPlayer p : players(c.self, 40)) {
            double dx = p.getX() - home.x, dz = p.getZ() - home.z;
            if (dx * dx + dz * dz > 20 * 20 || Math.abs(p.getY() - home.y) > 6) continue;
            if (SetPieces.sector(home, p.position()) == safe && dx * dx + dz * dz > 1.5 * 1.5) continue;
            damage(c, p, 9);
            p.igniteForTicks(80);
            p.setDeltaMovement(p.getDeltaMovement().add(0, 0.5, 0));
            p.needsSync = true;
        }
    }

    // ------------------------------------------------------------------ Hair Army

    private static void hairArmy(Ctx c, Entity target) {
        beginPiece(c);
        gcd(c, 40);
        clip(c, "clone");
        say(c, "&6Wukong plucks a whole handful of hairs... &e\"Change!\"");
        List<Mob> army = new ArrayList<>();
        later(c, 26, () -> {
            sfx(c, "ability_poof");
            c.particles(ParticleTypes.WAX_OFF, 60, 2, 0.6, 2.4);
            Vec3 home = c.self.position();
            for (int i = 0; i < 5; i++) {
                double a = i * 2 * Math.PI / 5;
                Vec3 at = onGround(c.level(), home.add(Math.cos(a) * 8, 0, Math.sin(a) * 8));
                army.addAll(BossKit.summon(c.level(), CLONE, at, 1, 0));
            }
        });
        for (int i = 0; i < 5; i++) {
            final int k = i;
            later(c, 50 + i * 12, () -> {
                if (k >= army.size()) return;
                Mob clone = army.get(k);
                ServerPlayer p = randomFighter(c, 32);
                if (!clone.isAlive() || p == null) return;
                ring(c, p.position(), RED, 1.6, 12);
                face(clone, p);
                Fx.sound(clone, "entity.fox.screech", 1.4f, 1.4f);
                BossKit.lunge(clone, p, 1.5, 0.25);
                for (int tt = 2; tt <= 10; tt += 2) {
                    Tasks.later(tt, () -> {
                        if (!clone.isAlive()) return;
                        c.level().sendParticles(GOLD, clone.getX(), clone.getY() + 1, clone.getZ(), 3, 0.3, 0.4, 0.3, 0);
                        for (ServerPlayer pl : players(clone, 2)) {
                            SetPieces.hit(c.level(), pl, clone, new BossFx.Hit(7, 0.5, 0.9, false), clone.position());
                        }
                    });
                }
            });
        }
        later(c, 50 + 5 * 12 + 6, () -> endPiece(c));
    }

    // ================================================================== hair clones

    private static void registerClones() {
        Abilities.registerMelee("ArcanaWukongClone_Strike", c -> {
            if (!c.cooldown("Strike", 1)) return;
            clip(c, "attack");
            later(c, 8, () -> damage(c, players(c.self, 3.5), 7));
        });

        Abilities.register("ArcanaWukongClone_Stomp", c -> {
            if (distance(c.self, target(c)) >= 8) return;
            clip(c, "stomp");
            later(c, 14, () -> quake(c, 6, 0.7, 0.5, 1, 5, 0.8, 0.6));
        });

        Abilities.register("ArcanaWukongClone_Poof", c -> {
            Fx.particles(c.self, ParticleTypes.POOF, 40, 0.6, 1, 1, 0.08);
            Fx.particles(c.self, new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.WOOL.brown().defaultBlockState()), 25, 0.6, 1, 1.2, 0.02);
            c.sound("entity.fox.teleport", 1.4, 1.2);
        });
    }

    /** True while this mob is a Wukong in one of his scripted set pieces (tests, debug). */
    static boolean inSetPiece(ArcanaMob mob) {
        return mob.var("setpiece") != 0;
    }
}
