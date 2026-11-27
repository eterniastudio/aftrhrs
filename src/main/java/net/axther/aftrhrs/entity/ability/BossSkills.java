package net.axther.aftrhrs.entity.ability;

import static net.axther.aftrhrs.entity.ability.BossKit.clip;
import static net.axther.aftrhrs.entity.ability.BossKit.damage;
import static net.axther.aftrhrs.entity.ability.BossKit.later;
import static net.axther.aftrhrs.entity.ability.BossKit.players;
import static net.axther.aftrhrs.entity.ability.BossKit.repeat;

import java.util.List;
import net.axther.aftrhrs.feature.bossfx.BossFx;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The four altar bosses (MythicMobs pack skills/bosses.yml): Cinderwing Phoenix, Tempest Roc, Glacial Wyrm and Runebound
 * Colossus. Each ability plays its clip and hits on the clip's impact frame (checked against the clips after the
 * rotation-sign fix; Seismic Slam and Worldbreaker got longer, shaking wind-ups: tools/clips/runebound_colossus.py); a
 * timed skill never cuts off another attack clip, it waits and retries once ({@link BossKit#waiting}). Beyond the YAML,
 * every attack clip the models have that no MythicMobs skill used is wired to an extra attack in the boss's style
 * ({@link Abilities#timer}): Phoenix Pyre Brand; Roc Charge, Eye of the Storm, Tempest Spiral; Wyrm Shatter Roar,
 * Burrow Strike, Absolute Winter; Colossus Faultline Sweep, Pillar Eruption, Worldbreaker.
 */
final class BossSkills {
    private BossSkills() {
    }

    private static BlockParticleOption block(net.minecraft.world.level.block.Block b) {
        return new BlockParticleOption(ParticleTypes.BLOCK, b.defaultBlockState());
    }

    static void register() {
        phoenix();
        roc();
        wyrm();
        colossus();
    }

    // ------------------------------------------------------------------ Cinderwing Phoenix

    private static void phoenix() {
        // glow bones are lit by the GeckoLib glowmask layer (ModelEngine needed brightness{} per bone)
        Abilities.register("ArcanaPhoenix_Glow", c -> {
        });

        Abilities.register("ArcanaPhoenix_EmberVolley", c -> {
            if (BossKit.waiting(c) || !c.cooldown("EmberVolley", 5)) return;
            Entity target = c.target;
            clip(c, "ember_volley");
            for (int at : new int[] {12, 18, 24}) {
                later(c, at, () -> {
                    if (target == null || !target.isAlive()) return;
                    Vec3 from = c.self.position().add(0, 1, 0);
                    Vec3 dir = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(from).normalize().scale(1.6);
                    LargeFireball ball = new LargeFireball(c.level(), c.self, dir, 1);
                    ball.setPos(from.x, from.y, from.z);
                    ball.setDeltaMovement(dir);
                    c.level().addFreshEntity(ball);
                    c.sound("entity.blaze.shoot", 1, 0.8);
                });
            }
        });

        Abilities.register("ArcanaPhoenix_SolarDive", c -> {
            if (BossKit.waiting(c) || !c.cooldown("SolarDive", 12)) return;
            Entity target = c.target;
            clip(c, "solar_dive");
            later(c, 16, () -> BossKit.lunge(c.self, target, 2, -0.6));
            later(c, 26, () -> {
                List<ServerPlayer> near = players(c.self, 4);
                damage(c, near, 10);
                for (ServerPlayer p : near) p.igniteForTicks(100);
                BossKit.ring(c.level(), c.self.position(), ParticleTypes.FLAME, 4, 24, 2, 0);
                c.sound("entity.blaze.shoot", 1.5, 0.6);
            });
        });

        Abilities.register("ArcanaPhoenix_CinderCyclone", c -> {
            if (BossKit.waiting(c) || !c.cooldown("CinderCyclone", 16)) return;
            clip(c, "cinder_cyclone");
            later(c, 10, () -> {
                repeat(c, 8, 4, () -> BossKit.ring(c.level(), c.self.position(), ParticleTypes.FLAME, 6, 36, 3, 0));
                repeat(c, 4, 8, () -> damage(c, players(c.self, 6), 4));
                for (ServerPlayer p : players(c.self, 6)) p.igniteForTicks(60);
            });
        });

        // Reborn once below half health (the YAML's 110 of 220; relative so party scaling keeps it at half): heals and flares.
        Abilities.register("ArcanaPhoenix_Rebirth", c -> {
            if (c.self.getHealth() >= c.self.getMaxHealth() * 0.5f || c.mob.var("reborn") != 0) return;
            c.mob.setVar("reborn", 1);
            clip(c, "rebirth");
            c.self.heal(80);
            // the flare on the clip's burst frame (it curls into its ashes first)
            later(c, 25, () -> {
                c.particles(ParticleTypes.FLAME, 80, 2, 2, 0);
                c.sound("entity.blaze.ambient", 2, 0.5);
            });
            BossKit.message(c.self, 48, "&6The Cinderwing Phoenix rises from its ashes!");
        });

        Abilities.register("ArcanaPhoenix_Fall", c -> {
            BossKit.summon(c.level(), "cinderwing_phoenix", c.self.position(), 1, 0);
            BossKit.message(c.self, 48, "&6The phoenix's last ember takes wing... &7Tame it with Blaze Rods.");
        });

        // Extra (clip pyre_brand): brands its target with a pillar of fire.
        Abilities.register("ArcanaPhoenix_PyreBrand", c -> {
            if (BossKit.waiting(c) || !c.cooldown("PyreBrand", 14) || !(c.target instanceof LivingEntity target)) return;
            if (BossKit.distance(c.self, target) > 24) return;
            clip(c, "pyre_brand");
            c.sound("item.firecharge.use", 1.6, 0.6);
            later(c, 18, () -> {
                if (!target.isAlive()) return;
                for (int i = 0; i < 6; i++) Fx.particles(c.level(), target.position().add(0, i * 0.5, 0), ParticleTypes.FLAME, 8, 0.3, 0.2, 0.02);
                BossKit.ring(c.level(), target.position(), ParticleTypes.FLAME, 2, 16, 1, 0.1);
                for (ServerPlayer p : players(target, 2.5)) {
                    damage(c, p, 6);
                    p.igniteForTicks(80);
                }
                c.sound(target, "entity.blaze.shoot", 1.5, 0.5);
            });
        });
        Abilities.timer("ArcanaCinderwingPhoenix", 200, "ArcanaPhoenix_PyreBrand", true);
    }

    // ------------------------------------------------------------------ Tempest Roc

    /** Wild Stormcall wisps one Roc keeps at once. */
    static final int STORM_WISP_CAP = 4;

    /** The tag on the wisps this Roc called. */
    static String stormTag(Ctx c) {
        return "aftrhrs.minion." + c.self.getUUID();
    }

    /** This Roc's own wild (untamed) Stormcall wisps within {@code r}. */
    static List<Mob> stormWisps(Ctx c, double r) {
        String tag = stormTag(c);
        return BossKit.mobs(c.self, "volt_wisp", r).stream()
                .filter(m -> m.entityTags().contains(tag) && !(m instanceof TamableAnimal t && t.isTame()))
                .toList();
    }

    private static void roc() {
        Abilities.register("ArcanaRoc_Glow", c -> {
        });

        Abilities.register("ArcanaRoc_ThunderDive", c -> {
            if (BossKit.waiting(c) || !c.cooldown("ThunderDive", 8)) return;
            Entity target = c.target;
            clip(c, "thunder_dive");
            later(c, 14, () -> {
                BossKit.lightning(c, target, 8);
                BossKit.lunge(c.self, target, 1.8, -0.5);
            });
        });

        Abilities.register("ArcanaRoc_ChainSquall", c -> {
            if (BossKit.waiting(c) || !c.cooldown("ChainSquall", 12)) return;
            clip(c, "chain_squall");
            later(c, 15, () -> {
                for (ServerPlayer p : players(c.self, 20, 3)) BossKit.lightning(c, p, 6);
            });
        });

        Abilities.register("ArcanaRoc_GaleBuffet", c -> {
            if (BossKit.waiting(c) || !c.cooldown("GaleBuffet", 14)) return;
            clip(c, "gale_buffet");
            later(c, 17, () -> {
                BossKit.throwAway(c.self, players(c.self, 6), 12, 4);
                c.particles(ParticleTypes.CLOUD, 60, 3, 1, 0);
                c.sound("entity.breeze.wind_burst", 1.5, 0.8);
            });
        });

        // at most STORM_WISP_CAP of its own wild wisps at a time: a capped cast doesn't spend the cooldown, a cast tops up
        Abilities.register("ArcanaRoc_Stormcall", c -> {
            if (BossKit.waiting(c) || stormWisps(c, 48).size() >= STORM_WISP_CAP || !c.cooldown("Stormcall", 30)) return;
            clip(c, "stormcall");
            later(c, 20, () -> {
                int n = Math.min(2, STORM_WISP_CAP - stormWisps(c, 48).size());
                if (n <= 0) return;
                String tag = stormTag(c);
                for (Mob m : BossKit.summon(c.level(), "volt_wisp", c.self.position(), n, 4)) m.addTag(tag);
            });
        });

        // removed (killed, timed out, abandoned, /altars end; never on chunk unload): the storm's wisps go with it. A wisp a
        // player tamed mid-fight stays. Abilities.onRemove runs <mythic id>_Cleanup, and the boss's id is ArcanaTempestRoc.
        Abilities.register("ArcanaTempestRoc_Cleanup", c -> {
            for (Mob m : stormWisps(c, 64)) m.discard();
        });

        Abilities.register("ArcanaRoc_Fall", c -> {
            BossKit.summon(c.level(), "tempest_roc", c.self.position(), 1, 0);
            BossKit.message(c.self, 48, "&9A young roc circles the storm's end... &7Tame it with Lightning Rods.");
        });

        // Extra (clip charge): a straight rush at its target that bowls over whoever it reaches.
        Abilities.register("ArcanaRoc_Charge", c -> {
            if (BossKit.waiting(c) || !c.cooldown("Charge", 10) || c.target == null || BossKit.distance(c.self, c.target) > 20) return;
            Entity target = c.target;
            clip(c, "charge");
            c.sound("entity.phantom.swoop", 2, 0.6);
            later(c, 10, () -> BossKit.lunge(c.self, target, 1.4, 0));
            later(c, 18, () -> {
                List<ServerPlayer> hit = players(c.self, 3);
                damage(c, hit, 6);
                BossKit.throwAway(c.self, hit, 8, 3);
                c.particles(ParticleTypes.CLOUD, 20, 1, 0.5, 0);
            });
        });
        Abilities.timer("ArcanaTempestRoc", 200, "ArcanaRoc_Charge", true);

        // Extra (clip eye_of_the_storm): calm at the center, a pull inward, then the storm wall bursts out.
        Abilities.register("ArcanaRoc_EyeOfTheStorm", c -> {
            if (BossKit.waiting(c) || !c.cooldown("EyeOfTheStorm", 25) || players(c.self, 14).isEmpty()) return;
            clip(c, "eye_of_the_storm");
            c.sound("item.trident.thunder", 1.6, 0.8);
            repeat(c, 4, 4, () -> {
                for (ServerPlayer p : players(c.self, 12)) BossKit.pull(c.self, p, 0.8);
                BossKit.ring(c.level(), c.self.position(), ParticleTypes.CLOUD, 10, 24, 1, 0.5);
            });
            later(c, 27, () -> {
                List<ServerPlayer> hit = players(c.self, 7);
                damage(c, hit, 6);
                BossKit.throwAway(c.self, hit, 10, 4);
                BossKit.ring(c.level(), c.self.position(), ParticleTypes.ELECTRIC_SPARK, 6, 30, 2, 0.5);
                c.sound("entity.lightning_bolt.thunder", 1.5, 1.4);
            });
        });
        Abilities.timer("ArcanaTempestRoc", 420, "ArcanaRoc_EyeOfTheStorm", false);

        // Extra (clip tempest_spiral): spinning gusts that buffet everyone close.
        Abilities.register("ArcanaRoc_TempestSpiral", c -> {
            if (BossKit.waiting(c) || !c.cooldown("TempestSpiral", 18) || players(c.self, 10).isEmpty()) return;
            clip(c, "tempest_spiral");
            c.sound("entity.breeze.whirl", 2, 0.7);
            later(c, 8, () -> repeat(c, 4, 6, () -> {
                List<ServerPlayer> hit = players(c.self, 6);
                damage(c, hit, 2);
                BossKit.throwAway(c.self, hit, 4, 2);
                BossKit.ring(c.level(), c.self.position(), ParticleTypes.GUST, 5, 10, 1, 1);
            }));
        });
        Abilities.timer("ArcanaTempestRoc", 360, "ArcanaRoc_TempestSpiral", false);
    }

    // ------------------------------------------------------------------ Glacial Wyrm

    private static void wyrm() {
        Abilities.register("ArcanaWyrm_Glow", c -> {
        });

        Abilities.register("ArcanaWyrm_GlacialBreath", c -> {
            if (BossKit.waiting(c) || !c.cooldown("GlacialBreath", 8)) return;
            Entity target = c.target;
            clip(c, "glacial_breath");
            later(c, 19, () -> {
                if (target != null) BossKit.line(c.level(), c.self, c.self.getEyeHeight(), target.position().add(0, 1, 0), ParticleTypes.SNOWFLAKE, 0.25);
                for (ServerPlayer p : players(c.self, 10)) {
                    damage(c, p, 7);
                    Fx.potion(p, "SLOWNESS", 100, 2, true, true);
                    p.setTicksFrozen(140);
                }
            });
        });

        Abilities.register("ArcanaWyrm_TailSweep", c -> {
            if (BossKit.waiting(c) || !c.cooldown("TailSweep", 11)) return;
            clip(c, "tail_sweep");
            later(c, 20, () -> {
                List<ServerPlayer> hit = players(c.self, 6);
                damage(c, hit, 8);
                BossKit.throwAway(c.self, hit, 10, 3);
            });
        });

        Abilities.register("ArcanaWyrm_RimeSpikes", c -> {
            if (BossKit.waiting(c) || !c.cooldown("RimeSpikes", 15)) return;
            Entity target = c.target;
            clip(c, "rime_spikes");
            later(c, 20, () -> {
                if (target == null || !target.isAlive()) return;
                Fx.particles(target, block(Blocks.PACKED_ICE), 40, 1, 1, 0, 0.02);
                damage(c, target, 9);
                if (target instanceof LivingEntity le) Fx.potion(le, "SLOWNESS", 80, 3, true, true);
            });
        });

        // Extra (clip shatter_roar): a freezing roar around it.
        Abilities.register("ArcanaWyrm_ShatterRoar", c -> {
            if (BossKit.waiting(c) || !c.cooldown("ShatterRoar", 18) || players(c.self, 10).isEmpty()) return;
            clip(c, "shatter_roar");
            c.sound("entity.ender_dragon.growl", 2, 0.8);
            later(c, 25, () -> {
                for (ServerPlayer p : players(c.self, 8)) {
                    damage(c, p, 5);
                    Fx.potion(p, "SLOWNESS", 60, 1, true, true);
                    p.setTicksFrozen(Math.max(p.getTicksFrozen(), 160));
                }
                BossKit.ring(c.level(), c.self.position(), block(Blocks.ICE), 6, 30, 3, 0.5);
                c.sound("block.glass.break", 2, 0.6);
            });
        });
        Abilities.timer("ArcanaGlacialWyrm", 380, "ArcanaWyrm_ShatterRoar", false);

        // Extra (clip burrow_strike): dives under and ice spikes erupt in a line at a distant target.
        Abilities.register("ArcanaWyrm_BurrowStrike", c -> {
            if (c.target == null || BossKit.distance(c.self, c.target) < 6 || BossKit.waiting(c) || !c.cooldown("BurrowStrike", 13)) return;
            Entity target = c.target;
            clip(c, "burrow_strike");
            c.sound("block.snow.break", 2, 0.5);
            later(c, 18, () -> BossFx.fissure(c.level(), c.self.position(), target.position(), c.self, 18, 1.2,
                    new BossFx.Hit(8, 1.1, 0.3, false), Blocks.PACKED_ICE.defaultBlockState()));
        });
        Abilities.timer("ArcanaGlacialWyrm", 260, "ArcanaWyrm_BurrowStrike", true);

        // Extra (clip absolute_winter): below half health, a frost wave freezes the arena.
        Abilities.register("ArcanaWyrm_AbsoluteWinter", c -> {
            if (c.self.getHealth() > c.self.getMaxHealth() * 0.5f || BossKit.waiting(c) || !c.cooldown("AbsoluteWinter", 30)) return;
            clip(c, "absolute_winter");
            c.sound("entity.elder_guardian.curse", 2, 0.6);
            repeat(c, 12, 3, () -> c.particles(ParticleTypes.SNOWFLAKE, 40, 6, 2, 1));
            later(c, 40, () -> {
                BossFx.quake(c.level(), c.self.position(), c.self, 12, 0.6, 0.5, 2, new BossFx.Hit(6, 0.6, 0.4, true));
                for (ServerPlayer p : players(c.self, 12)) {
                    p.setTicksFrozen(Math.max(p.getTicksFrozen(), 200));
                    Fx.potion(p, "SLOWNESS", 100, 1, true, true);
                }
            });
        });
        Abilities.timer("ArcanaGlacialWyrm", 600, "ArcanaWyrm_AbsoluteWinter", false);
    }

    // ------------------------------------------------------------------ Runebound Colossus

    private static void colossus() {
        Abilities.register("ArcanaColossus_Glow", c -> {
        });
        // the renderer darkens the glowmask of glow_out species while the death clip plays
        Abilities.register("ArcanaColossus_GlowOut", c -> {
        });

        Abilities.register("ArcanaColossus_SeismicSlam", c -> {
            if (BossKit.waiting(c) || !c.cooldown("SeismicSlam", 10)) return;
            clip(c, "seismic_slam");
            later(c, 27, () -> {
                List<ServerPlayer> hit = players(c.self, 7);
                damage(c, hit, 10);
                BossKit.throwAway(c.self, hit, 6, 6);
                BossKit.ring(c.level(), c.self.position(), block(Blocks.STONE), 6, 40, 4, 0);
                c.sound("entity.generic.explode", 1.2, 0.6);
            });
        });

        Abilities.register("ArcanaColossus_BoulderHurl", c -> {
            if (BossKit.waiting(c) || !c.cooldown("BoulderHurl", 8)) return;
            Entity target = c.target;
            clip(c, "boulder_hurl");
            later(c, 18, () -> {
                if (target == null || !target.isAlive()) return;
                for (int i = 0; i < 3; i++) BossKit.line(c.level(), c.self, c.self.getBbHeight() * 0.8, target.position().add(0, 1, 0), block(Blocks.COBBLESTONE), 0.4);
                damage(c, target, 8);
                BossKit.throwAway(c.self, target, 4, 3);
            });
        });

        Abilities.register("ArcanaColossus_RunicWard", c -> {
            if (BossKit.waiting(c) || !c.cooldown("RunicWard", 20)) return;
            clip(c, "runic_ward");
            Fx.potion(c.self, "RESISTANCE", 160, 1, true, true);
            later(c, 10, () -> c.particles(ParticleTypes.ENCHANT, 80, 1.5, 2, 0));   // the arms cross at 0.5 s
        });

        // Extra (clip faultline_sweep): rips a line of earth spikes toward its target.
        Abilities.register("ArcanaColossus_FaultlineSweep", c -> {
            if (c.target == null || BossKit.distance(c.self, c.target) > 18 || BossKit.waiting(c) || !c.cooldown("FaultlineSweep", 12)) return;
            Entity target = c.target;
            clip(c, "faultline_sweep");
            later(c, 16, () -> {
                BossFx.fissure(c.level(), c.self.position(), target.position(), c.self, 14, 1, new BossFx.Hit(8, 1.1, 0.3, false));
                c.sound("block.deepslate.break", 2, 0.5);
            });
        });
        Abilities.timer("ArcanaRuneboundColossus", 240, "ArcanaColossus_FaultlineSweep", true);

        // Extra (clip pillar_eruption): stone erupts under every nearby player after a rumble.
        Abilities.register("ArcanaColossus_PillarEruption", c -> {
            if (BossKit.waiting(c) || !c.cooldown("PillarEruption", 16) || players(c.self, 12).isEmpty()) return;
            clip(c, "pillar_eruption");
            List<ServerPlayer> marked = players(c.self, 12);
            for (ServerPlayer p : marked) Fx.particles(p, block(Blocks.STONE), 20, 0.6, 0.05, 0.1, 0.05);
            c.sound("block.deepslate.hit", 2, 0.5);
            later(c, 24, () -> {
                for (ServerPlayer p : marked) {
                    if (!p.isAlive()) continue;
                    Vec3 at = p.position();
                    BossFx.fissure(c.level(), at.add(-1.6, 0, 0), at.add(1, 0, 0), c.self, 2.6, 1, new BossFx.Hit(7, 1.2, 0.1, false));
                }
                c.sound("entity.generic.explode", 1.4, 0.7);
            });
        });
        Abilities.timer("ArcanaRuneboundColossus", 320, "ArcanaColossus_PillarEruption", false);

        // Extra (clip worldbreaker): below half health, a ground-shattering slam.
        Abilities.register("ArcanaColossus_Worldbreaker", c -> {
            if (c.self.getHealth() > c.self.getMaxHealth() * 0.5f || BossKit.waiting(c) || !c.cooldown("Worldbreaker", 26)) return;
            clip(c, "worldbreaker");
            c.sound("entity.iron_golem.repair", 2, 0.4);
            repeat(c, 6, 6, () -> Fx.particles(c.level(), c.self.position(), new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE.defaultBlockState()), 12, 2.5, 0.1, 0.05));
            later(c, 43, () -> {
                damage(c, players(c.self, 4), 12);
                BossFx.quake(c.level(), c.self.position(), c.self, 14, 0.7, 0.8, 3, new BossFx.Hit(9, 1.0, 0.6, true));
                c.sound("entity.generic.explode", 2, 0.5);
                Fx.particles(c.level(), c.self.position(), ParticleTypes.EXPLOSION, 3, 1, 0.2, 0);
            });
        });
        Abilities.timer("ArcanaRuneboundColossus", 520, "ArcanaColossus_Worldbreaker", false);
    }
}
