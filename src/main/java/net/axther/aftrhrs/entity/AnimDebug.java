package net.axther.aftrhrs.entity;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /aftrhrs debug anim ...} (op): drive and inspect the animation state machine.
 * <ul>
 *   <li>{@code trace <targets> <ticks>}: logs the server intent every tick ({@code [animsrv]}: speed, hold / idle /
 *       walk clip, sitting, napping, last one-shot and whether it may still play). Run the client with
 *       {@code -Daftrhrs.animTrace=true} for the clip the client actually plays ({@code [animtrace]}).</li>
 *   <li>{@code walk <targets> <pos> [speed]}: path to a position (also stands a staying pet up first).</li>
 *   <li>{@code stay <targets> <bool>}, {@code nap <targets> <bool>}, {@code play <targets> <clip>},
 *       {@code tame <targets> <player>}.</li>
 *   <li>{@code info <target>}: the species' states, transitions and idle pools.</li>
 * </ul>
 */
public final class AnimDebug {
    private record Trace(Mob mob, int until) {
    }

    private static final List<Trace> TRACES = new ArrayList<>();
    private static final Map<Integer, double[]> LAST = new HashMap<>();
    private static int tick;

    private AnimDebug() {
    }

    static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("aftrhrs")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("debug").then(Commands.literal("anim")
                        .then(Commands.literal("trace").then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 12000)).executes(AnimDebug::trace))))
                        .then(Commands.literal("walk").then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("pos", Vec3Argument.vec3()).executes(c -> walk(c, 1.0))
                                        .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.1, 3))
                                                .executes(c -> walk(c, DoubleArgumentType.getDouble(c, "speed")))))))
                        .then(Commands.literal("stay").then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("on", BoolArgumentType.bool()).executes(AnimDebug::stay))))
                        .then(Commands.literal("nap").then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("on", BoolArgumentType.bool()).executes(AnimDebug::nap))))
                        .then(Commands.literal("play").then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("clip", StringArgumentType.word()).executes(AnimDebug::play))))
                        .then(Commands.literal("skill").then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("skill", StringArgumentType.word()).executes(AnimDebug::skill))))
                        .then(Commands.literal("tame").then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("owner", EntityArgument.player()).executes(AnimDebug::tame))))
                        .then(Commands.literal("info").then(Commands.argument("targets", EntityArgument.entities()).executes(AnimDebug::info)))))));
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static List<Mob> mobs(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<? extends Entity> es = EntityArgument.getEntities(c, "targets");
        List<Mob> out = new ArrayList<>();
        for (Entity e : es) if (e instanceof Mob m && m instanceof ArcanaMob) out.add(m);
        return out;
    }

    private static int trace(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        int ticks = IntegerArgumentType.getInteger(c, "ticks");
        List<Mob> ms = mobs(c);
        for (Mob m : ms) TRACES.add(new Trace(m, tick + ticks));
        c.getSource().sendSuccess(() -> Component.literal("Tracing " + ms.size() + " creature(s) for " + ticks + " ticks (server log [animsrv])."), false);
        return ms.size();
    }

    private static int walk(CommandContext<CommandSourceStack> c, double speed) throws CommandSyntaxException {
        Vec3 to = Vec3Argument.getVec3(c, "pos");
        List<Mob> ms = mobs(c);
        for (Mob m : ms) {
            if (m instanceof ArcanaCreature a && a.staying()) a.setStaying(false, null);
            m.getNavigation().moveTo(to.x, to.y, to.z, speed);
        }
        return ms.size();
    }

    private static int stay(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        boolean on = BoolArgumentType.getBool(c, "on");
        int n = 0;
        for (Mob m : mobs(c)) {
            if (m instanceof ArcanaCreature a) {
                a.setStaying(on, null);
                n++;
            }
        }
        return n;
    }

    private static int nap(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        boolean on = BoolArgumentType.getBool(c, "on");
        int n = 0;
        for (Mob m : mobs(c)) {
            if (m instanceof ArcanaCreature a) {
                if (on && !a.staying()) a.setStaying(true, null);
                a.setNapping(on);
                n++;
            }
        }
        return n;
    }

    private static int tame(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer owner = EntityArgument.getPlayer(c, "owner");
        int n = 0;
        for (Mob m : mobs(c)) {
            if (m instanceof ArcanaCreature a && !a.species().untameable()) {
                a.tamedBy(owner);
                n++;
            }
        }
        return n;
    }

    private static int play(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String clip = StringArgumentType.getString(c, "clip");
        List<Mob> ms = mobs(c);
        for (Mob m : ms) ((ArcanaMob) m).play(clip);
        return ms.size();
    }

    /** Casts a registered ability (boss attacks: its MythicMobs skill id) at the creature's current target. */
    private static int skill(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String id = StringArgumentType.getString(c, "skill");
        List<Mob> ms = mobs(c);
        for (Mob m : ms) net.axther.aftrhrs.entity.ability.Abilities.runSkill((ArcanaMob) m, id, m.getTarget());
        return ms.size();
    }

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        for (Mob m : mobs(c)) {
            Species s = ((ArcanaMob) m).species();
            AnimSpec a = s.anim;
            c.getSource().sendSuccess(() -> Component.literal(s.id + " states " + a.states + "\ntransitions " + a.transitions
                    + "\nidle " + a.idle.keySet() + " gait on/off " + a.walkOn + "/" + a.walkOff + " run " + a.runOn + "/" + a.runOff), false);
        }
        return 1;
    }

    private static void tick() {
        tick++;
        for (Iterator<Trace> it = TRACES.iterator(); it.hasNext(); ) {
            Trace t = it.next();
            Mob m = t.mob();
            if (tick > t.until() || m.isRemoved()) {
                it.remove();
                LAST.remove(m.getId());
                continue;
            }
            double[] last = LAST.get(m.getId());
            double hs = last == null ? 0 : Math.hypot(m.getX() - last[0], m.getZ() - last[1]);
            LAST.put(m.getId(), new double[] {m.getX(), m.getZ()});
            ArcanaMob a = (ArcanaMob) m;
            String extra = "";
            if (m instanceof ArcanaCreature c) {
                AnimSpec.Tag lt = a.species().anim.tag(c.lastPlayed());
                int age = (int) (m.level().getGameTime() - c.lastPlayedAt());
                boolean live = !c.lastPlayed().isEmpty() && age < lt.ticks();
                extra = " sitting=" + c.isInSittingPose() + " napping=" + c.napping() + " lingering=" + c.lingering()
                        + " oneShot=" + (live ? c.lastPlayed() + "(" + lt.layer() + (lt.move() ? ",move" : ",still") + ")" : "-");
                if (live && hs > 0.05 && lt.base() && !lt.move()) extra += " (client cancels it: moving)";
            }
            AftrHrs.LOG.info("[animsrv] t={} id={} {} hs={} hold={} idle={} walk={}{}", tick, m.getId(), a.species().id,
                    String.format("%.3f", hs), a.holdClip(), a.idleClip(), a.walkClip(), extra);
        }
    }
}
