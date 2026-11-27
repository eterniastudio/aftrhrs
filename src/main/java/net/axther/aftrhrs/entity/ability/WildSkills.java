package net.axther.aftrhrs.entity.ability;

import com.google.gson.JsonArray;
import java.util.List;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.ArcanaWildlife;
import net.axther.aftrhrs.entity.Conditions;
import net.axther.aftrhrs.entity.Species;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Wildlife / livestock / undead species skills (tools/wild_specs/*.skills.yml) and the Feather hooks of the Wildlife
 * &amp; Undead SPEC (section 4): Harvest, Root, Shorn, Regrow, AntlersOff/On, HoldFish, DropFish, Dive, Lay, Spin, Hide.
 */
final class WildSkills {
    private WildSkills() {
    }

    private static BlockParticleOption block(Block b) {
        return new BlockParticleOption(ParticleTypes.BLOCK, b.defaultBlockState());
    }

    /** setvariable busy=1 + setai false, a held one-shot, then GetUp after {@code ticks}. */
    private static void still(Ctx c, String clip, int ticks, Runnable after) {
        c.mob.setBusy(true);
        c.play(clip);
        c.sched().after(ticks, () -> {
            after.run();
            c.mob.free();
        });
    }

    static void register(List<Species> all) {
        // generic harvest hook for every livestock spec with a "harvest" block
        for (Species s : all) {
            if (!s.json.has("harvest")) continue;
            JsonArray lines = s.json.getAsJsonArray("harvest");
            Abilities.register(s.mythicId + "_Harvest", c -> {
                if (!c.cooldown("harvest", 1)) return;
                c.mob.setBusy(true);
                c.play("harvest");
                Fx.lines(c.mob, lines);
                c.sched().after(Math.max(10, s.clipTicks("harvest", 30)), c.mob::free);
            });
        }

        for (String id : new String[] {"ArcanaBristleboar", "ArcanaBristleboarYoung"}) {
            Abilities.register(id + "_Root", c -> {
                if (!c.cooldown("root", 1)) return;
                c.sound("entity.pig.ambient", 0.7, 1.1);
                Fx.particles(c.level(), c.forward(0.9).add(0, 0.1, 0), block(Blocks.ROOTED_DIRT), 12, 0.35, 0.15, 0.02);
                c.sched().after(10, () -> {
                    Fx.particles(c.level(), c.forward(0.9).add(0, 0.1, 0), block(Blocks.PODZOL), 10, 0.35, 0.15, 0.02);
                    c.sound("block.rooted_dirt.break", 0.6, 0.9);
                });
                still(c, "root", 10 + Math.max(10, c.species().clipTicks("root", 40)), () -> {
                });
            });
        }
        for (String id : new String[] {"ArcanaLanternsnail", "ArcanaLanternsnailYoung"}) {
            boolean young = id.endsWith("Young");
            Abilities.register(id + "_Hide", c -> {
                if (!c.cooldown("hide", 2)) return;
                c.sound("entity.shulker.close", young ? 0.4 : 0.5, young ? 1.9 : 1.6);
                c.mob.setBusy(true);
                c.mob.hold("hide");
                c.sched().after(Math.max(10, c.species().clipTicks("hide", 64)), () -> {
                    c.sound("entity.shulker.open", young ? 0.3 : 0.4, young ? 2.0 : 1.7);
                    c.mob.free();
                });
            });
        }
        for (String id : new String[] {"ArcanaLoomworm", "ArcanaLoomwormYoung"}) {
            Abilities.register(id + "_Spin", c -> {
                if (!c.cooldown("spin", 1)) return;
                c.sound("block.wool.hit", 0.5, 1.6);
                c.particles(block(Blocks.COBWEB), 6, 0.2, 0.4, 0.6);
                c.sched().after(20, () -> {
                    c.particles(block(Blocks.WOOL.white()), 6, 0.2, 0.4, 0.7);
                    c.sound("block.wool.place", 0.5, 1.5);
                });
                still(c, "spin", 20 + Math.max(10, c.species().clipTicks("spin", 40)), () -> {
                });
            });
        }
        Abilities.register("ArcanaPondwaddle_Lay", c -> {
            if (!c.cooldown("lay", 1)) return;
            c.sound("entity.chicken.ambient", 0.7, 0.9);
            still(c, "lay", Math.max(10, c.species().clipTicks("lay", 40)), () -> c.sound("entity.chicken.egg", 1, 0.9));
        });
        for (String id : new String[] {"ArcanaWartgrazer", "ArcanaWartgrazerYoung"}) {
            Abilities.register(id + "_Shorn", c -> {
                c.mob.setVar("shorn", 1);
                c.play("harvest");
                c.mob.setBoneVisible("fleece", false);
                c.sound("entity.sheep.shear", 1, 0.9);
                c.particles(block(Blocks.WARPED_WART_BLOCK), 16, 0.5, 0.4, 0.9);
            });
            Abilities.register(id + "_Regrow", c -> {
                c.mob.setVar("shorn", 0);
                c.mob.setBoneVisible("fleece", true);
                c.particles(ParticleTypes.WARPED_SPORE, 20, 0.5, 0.4, 0.9);
                c.sound("block.wool.place", 0.6, 0.8);
            });
        }
        Abilities.register("ArcanaVelvetstag_AntlersOff", c -> {
            c.mob.setVar("antlers_off", 1);
            c.mob.setBoneVisible("antlers", false);
            c.particles(block(Blocks.STRIPPED_BIRCH_LOG), 12, 0.4, 0.3, 1.8);
            c.sound("block.wood.break", 0.6, 1.3);
        });
        Abilities.register("ArcanaVelvetstag_AntlersOn", c -> {
            c.mob.setVar("antlers_off", 0);
            c.mob.setBoneVisible("antlers", true);
            c.particles(ParticleTypes.HAPPY_VILLAGER, 8, 0.4, 0.3, 1.8);
        });
        Abilities.register("ArcanaVelvetstag_Spook", c -> {
            if (c.self instanceof ArcanaWildlife w) w.spook(200);
        });
        Abilities.register("ArcanaVelvetstag_Watch", c -> {
            if (!(c.self instanceof ArcanaWildlife w) || w.spooked()) return;
            c.around(c.self, 16, Player.class, p -> !p.isSpectator() && !p.isShiftKeyDown()).stream().findFirst().ifPresent(p -> {
                if (!c.cooldown("alert", 10)) return;
                if (c.busy()) c.mob.free();
                c.self.getLookControl().setLookAt(p, 8, 8);
                c.play("alert");
                c.sound("entity.horse.breathe", 0.5, 1.4);
            });
        });

        // Squallgull: glide, perch, circle riders, fish (Feather drives HoldFish / DropFish / Dive)
        Abilities.register("ArcanaSquallgull_Glide", c -> {
            if (c.busy() || !c.moving()) return;
            c.mob.setVar("gliding", 1);
            c.mob.setWalkClip("glide");
            c.sched().after(60, () -> {
                c.mob.setVar("gliding", 0);
                if (!c.busy()) c.mob.setWalkClip("fly");
            });
        });
        Abilities.register("ArcanaSquallgull_Call", c -> c.sound("entity.parrot.hurt", 0.7, 0.55));
        Abilities.register("ArcanaSquallgull_Perch", c -> {
            if (c.busy() || c.mob.var("holding") == 1 || c.level().getNearestPlayer(c.self, 6) != null) return;
            int ground = c.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, c.self.getBlockX(), c.self.getBlockZ());
            if (c.self.getY() - ground > 10) return;
            c.mob.setBusy(true);
            c.mob.setVar("perched", 1);
            c.self.setNoGravity(false);
            c.mob.setIdleClip("idle");
            c.mob.setWalkClip("idle");
            c.sched().after(30, () -> {
                if (Conditions.inWater(c.self)) perchEnd(c);
            });
            c.sched().after(90, () -> perchFidget(c));
            c.sched().after(170, () -> perchFidget(c));
            c.sched().after(270, () -> perchEnd(c));
        });
        Abilities.register("ArcanaSquallgull_PerchEnd", WildSkills::perchEnd);
        Abilities.register("ArcanaSquallgull_Circle", c -> {
            if (c.busy()) return;
            c.around(c.self, 24, Player.class, p -> !p.isSpectator() && p.isPassenger()).stream().findFirst().ifPresent(p -> {
                c.self.getNavigation().moveTo(p.getX() + (c.self.getRandom().nextDouble() - 0.5) * 10, p.getY() + 2 + c.self.getRandom().nextDouble() * 2,
                        p.getZ() + (c.self.getRandom().nextDouble() - 0.5) * 10, 1.25);
                if (c.self.getRandom().nextFloat() < 0.15f) c.sound("entity.parrot.hurt", 0.7, 0.55);
            });
        });
        Abilities.register("ArcanaSquallgull_HoldFish", c -> {
            perchEnd(c);
            c.mob.setVar("holding", 1);
            c.play("peck");
            c.sched().after(4, () -> {
                c.mob.setBoneVisible("held_fish", true);
                c.sound("entity.parrot.ambient", 0.9, 0.7);
            });
        });
        Abilities.register("ArcanaSquallgull_DropFish", c -> {
            c.mob.setVar("holding", 0);
            c.mob.setBoneVisible("held_fish", false);
            c.sound("entity.parrot.hurt", 0.9, 0.55);
        });
        Abilities.register("ArcanaSquallgull_Dive", c -> {
            if (!c.cooldown("dive", 2)) return;
            perchEnd(c);
            c.mob.setBusy(true);
            c.play("dive");
            c.sound("entity.parrot.fly", 0.8, 1.4);
            if (c.target != null) {
                Vec3 d = c.target.position().subtract(c.self.position()).normalize().scale(1.1);
                c.self.setDeltaMovement(d);
            } else {
                c.self.setDeltaMovement(0, -0.9, 0);
            }
            c.self.needsSync = true;
            c.sched().after(15, () -> {
                c.particles(ParticleTypes.SPLASH, 25, 0.5, 0.2, 0.1);
                c.sound("entity.player.splash", 0.5, 1.6);
            });
            c.sched().after(20, () -> {
                c.self.setDeltaMovement(0, 0.55, 0);
                c.self.needsSync = true;
            });
            c.sched().after(28, () -> c.mob.setBusy(false));
        });

        // Vinebound: lies dormant as a bush until someone comes close
        Abilities.register("ArcanaVinebound_Dormant", c -> {
            c.mob.setVar("dormant", 1);
            c.mob.setBusy(true);
            c.mob.setIdleClip("dormant");
            c.mob.setWalkClip("dormant");
        });
        Abilities.register("ArcanaVinebound_Watch", c -> {
            if (c.mob.var("dormant") == 1 && c.level().getNearestPlayer(c.self, 4) != null) emerge(c);
        });
        Abilities.register("ArcanaVinebound_Emerge", WildSkills::emerge);

        // Duskflies: gone at sunrise
        Abilities.register("ArcanaDuskflies_Sunrise", c -> {
            if (Conditions.timeIn(c.level(), 13000, 23000)) return;
            c.particles(ParticleTypes.FIREFLY, 12, 1.5, 0.8, 0.5);
            c.self.discard();
        });
    }

    private static void perchFidget(Ctx c) {
        if (c.mob.var("perched") != 1) return;
        if (c.self.getRandom().nextInt(3) < 2) {
            c.play("peck");
            Fx.particles(c.level(), c.forward(0.4).add(0, 0.05, 0), block(Blocks.SAND), 4, 0.15, 0.05, 0.02);
        } else {
            c.play("squawk");
            c.sound("entity.parrot.hurt", 0.7, 0.55);
        }
    }

    private static void perchEnd(Ctx c) {
        if (c.mob.var("perched") != 1) return;
        c.mob.setVar("perched", 0);
        c.mob.setBusy(false);
        c.mob.setIdleClip("fly");
        c.mob.setWalkClip("fly");
        c.self.setDeltaMovement(0, 0.35, 0);
        c.self.needsSync = true;
        c.sound("entity.parrot.fly", 0.6, 0.9);
    }

    private static void emerge(Ctx c) {
        if (c.mob.var("dormant") != 1) return;
        c.mob.setVar("dormant", 0);
        c.mob.setIdleClip("idle");
        c.mob.setWalkClip("walk");
        c.play("emerge");
        c.particles(block(Blocks.JUNGLE_LEAVES), 30, 0.6, 0.6, 0.6);
        c.sound("block.azalea_leaves.break", 1, 0.8);
        c.sound("entity.zombie.ambient", 1, 1.1);
        c.sched().after(Math.max(10, c.species().clipTicks("emerge", 30)), () -> {
            c.self.setDeltaMovement(Vec3.ZERO);
            c.mob.setBusy(false);
        });
    }

    static boolean isMob(Object o) {
        return o instanceof ArcanaMob;
    }
}
