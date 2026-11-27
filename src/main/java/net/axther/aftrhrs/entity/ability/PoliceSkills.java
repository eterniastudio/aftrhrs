package net.axther.aftrhrs.entity.ability;

import static net.axther.aftrhrs.entity.ability.BossKit.later;

import com.mojang.math.Transformation;
import net.axther.aftrhrs.entity.ArcanaMonster;
import net.axther.aftrhrs.entity.CreatureUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Aftr Hrs police officer (MythicMobs pack skills/police.yml): whistles on arrival, shouts at whoever the bank
 * robbery points it at (a speech bubble over the officer and a chat line to the target only, pointing at them), swings
 * its baton (the hit lands on the swing's frame), and shrugs, gives up and leaves after 90 seconds without a target.
 * Its scanning, radio and whistle idles and the chase run are {@code BossBrain} pools (tools/clips/police.py).
 */
final class PoliceSkills {
    private static final String[][] LINES = {
            {"Stop right there!", "2"},
            {"Halt! Police!", "1"},
            {"Hrmm! Stop, thief!", "1"},
            {"Drop the loot!", "1"},
            {"You can't outrun the law!", "1"},
    };

    private PoliceSkills() {
    }

    /**
     * MythicMobs {@code speak{m;o;d}}: a speech bubble (a text display riding the officer, {@code offset} above its head)
     * for {@code ticks}. Never saved.
     */
    static void speak(Ctx c, String text, String color, double offset, int ticks) {
        Display.TextDisplay bubble = new Display.TextDisplay(net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY, c.level()) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }
        };
        bubble.setPos(c.self.getX(), c.self.getY() + c.self.getBbHeight(), c.self.getZ());
        bubble.setText(CreatureUtil.legacy(color + text));
        bubble.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        bubble.setTransformation(new Transformation(new Vector3f(0, (float) offset, 0), new Quaternionf(), new Vector3f(1), new Quaternionf()));
        bubble.setViewRange(0.5f);
        if (!c.level().addFreshEntity(bubble)) return;
        bubble.startRiding(c.self, true, false);
        c.sched().after(ticks, bubble::discard);
        // the officer may die or leave first: the bubble must not outlive it
        net.axther.aftrhrs.core.Tasks.later(ticks + 2, () -> {
            if (!bubble.isRemoved()) bubble.discard();
        });
    }

    static void register() {
        Abilities.register("ArcanaPolice_Spawn", c -> {
            // MythicMobs played walk at 2.4x as the run; GeckoLib plays the clip at its own speed here
            later(c, 2, () -> c.play("alert"));
            later(c, 10, () -> Abilities.runSkill(c.mob, "ArcanaPolice_Whistle", null));
            later(c, 21, () -> Abilities.runSkill(c.mob, "ArcanaPolice_Whistle", null));
            later(c, 35, () -> Abilities.runSkill(c.mob, "ArcanaPolice_Shout", c.self.getTarget()));
        });

        Abilities.register("ArcanaPolice_Whistle", c -> {
            c.sound("block.note_block.flute", 2, 2);
            Fx.particles(c.level(), c.self.position().add(c.self.getLookAngle().multiply(0.4, 0, 0.4)).add(0, 1.55, 0), ParticleTypes.CLOUD, 3, 0.1, 0.05, 0.02);
            later(c, 2, () -> c.sound("block.note_block.flute", 2, 1.89));
            later(c, 4, () -> c.sound("block.note_block.flute", 2, 2));
            later(c, 6, () -> c.sound("block.note_block.flute", 2, 1.89));
        });

        // runs on the baton's hit frame (the melee waits for the attack clip's tag hit; its whoosh is a clip sound keyframe)
        Abilities.register("ArcanaPolice_Swing", c -> {
            if (!(c.mob instanceof ArcanaMonster m) || !"attack".equals(m.actionClip())) c.play("attack");
            c.sound("entity.iron_golem.attack", 0.8, 1.4);
            if (c.self.getRandom().nextDouble() < 0.35) later(c, 4, () -> c.sound("entity.villager.no", 0.9, 0.9));
        });

        // "Stop right there!" to its target: throttled to once every 8 s; the target must be a player within 24 blocks.
        Abilities.register("ArcanaPolice_Shout", c -> {
            Entity t = c.target != null && c.target != c.self ? c.target : c.self.getTarget();
            if (!(t instanceof ServerPlayer target) || c.self.distanceTo(target) >= 24 || !c.cooldown("Shout", 8)) return;
            int total = 0;
            for (String[] l : LINES) total += Integer.parseInt(l[1]);
            int r = c.self.getRandom().nextInt(total);
            String line = LINES[0][0];
            for (String[] l : LINES) {
                r -= Integer.parseInt(l[1]);
                if (r < 0) {
                    line = l[0];
                    break;
                }
            }
            if (c.mob instanceof ArcanaMonster m && !m.inAction()) c.play("point");   // arm thrust out at the robber
            speak(c, line, "&f", 0.7, line.startsWith("You can't") ? 60 : 50);
            CreatureUtil.message(target, "&9Police Officer&7: &f" + line);
            c.sound("entity.villager.no", 1, 0.8);
        });

        // Every second: an officer left without a target for 90 s gives up and leaves.
        Abilities.register("ArcanaPolice_Watch", c -> {
            if (c.mob.var("leaving") != 0) return;
            boolean chasing = c.self.getTarget() != null && c.self.getTarget().isAlive();
            c.mob.setVar("chasing", chasing ? 1 : 0);
            if (chasing) {
                c.mob.setVar("idle", 0);
                return;
            }
            c.mob.setVar("idle", c.mob.var("idle") + 1);
            if (c.mob.var("idle") > 89) Abilities.runSkill(c.mob, "ArcanaPolice_GiveUp", null);
        });

        Abilities.register("ArcanaPolice_GiveUp", c -> {
            c.mob.setVar("leaving", 1);
            speak(c, "Hrmm... lost them.", "&7", 0.7, 40);
            c.sound("entity.villager.ambient", 1, 0.8);
            c.play("shrug");
            c.mob.setBusy(true);
            later(c, 38, () -> {
                c.particles(ParticleTypes.POOF, 20, 0.3, 0.6, 1);
                c.self.discard();
            });
        });
    }
}
