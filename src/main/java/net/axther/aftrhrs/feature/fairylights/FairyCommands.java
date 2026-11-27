package net.axther.aftrhrs.feature.fairylights;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Staff tools: {@code /fairylights string <from> <to> <preset> [slack]} hangs a ready-made strand between two posts /
 * hooks (a hook is put on the nearest wall or ceiling when the spot is empty), {@code /fairylights give <preset>} hands out a
 * ready-made strand, {@code /fairylights clear <radius>}
 * takes every strand around down (no drops), {@code /fairylights count} counts them.
 */
public final class FairyCommands {
    private FairyCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> d.register(Commands.literal("fairylights")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("string")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("preset", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(FairyLights.PRESETS, b))
                                                .executes(c -> string(c, 1))
                                                .then(Commands.argument("slack", FloatArgumentType.floatArg(0, Strand.MAX_SLACK))
                                                        .executes(c -> string(c, FloatArgumentType.getFloat(c, "slack"))))))))
                .then(Commands.literal("give")
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(FairyLights.PRESETS, b))
                                .executes(FairyCommands::give)))
                .then(Commands.literal("clear")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128)).executes(FairyCommands::clear)))
                .then(Commands.literal("count").executes(FairyCommands::count))));
    }

    private static int string(CommandContext<CommandSourceStack> c, float slack) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        BlockPos a = BlockPosArgument.getBlockPos(c, "from"), b = BlockPosArgument.getBlockPos(c, "to");
        ItemStack item = FairyLights.preset(StringArgumentType.getString(c, "preset"));
        if (item.isEmpty()) {
            src.sendFailure(Component.literal("Unknown preset. Try: " + String.join(", ", FairyLights.PRESETS)));
            return 0;
        }
        Fastener fa = fastenerAt(level, a), fb = fastenerAt(level, b);
        if (fa == null || fb == null) {
            src.sendFailure(Component.literal("No fence, wall, hook or wall/ceiling to hang from at " + (fa == null ? a : b).toShortString()));
            return 0;
        }
        Strand s = string(fa, fb, item, slack);
        if (s == null) {
            src.sendFailure(Component.literal("Those two are already strung together."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Strung " + Math.round(fa.point().distanceTo(fb.point())) + " blocks of " + item.getHoverName().getString()), true);
        return 1;
    }

    /** Hangs a strand of this item between two fasteners (no limits; for staff, tests and setups). */
    public static @Nullable Strand string(Fastener from, Fastener to, ItemStack item, float slack) {
        if (from.joinedTo(to.ref()) || from == to) return null;
        Strand s = new Strand(UUID.randomUUID(), ((StrandItem) item.getItem()).kind, FairyLights.spec(item), to.ref());
        s.slack = slack;
        from.addOutgoing(s);
        to.addIncoming(s.id, from.ref());
        return s;
    }

    /** The post's knot, the hook there, or a new hook on the first solid face around an empty spot. */
    public static @Nullable Fastener fastenerAt(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (KnotEntity.isPost(s)) return KnotEntity.findOrCreate(level, pos).fastener();
        if (level.getBlockEntity(pos) instanceof HookBlockEntity h) return h.fastener();
        if (!s.canBeReplaced()) return null;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockState hook = FairyLights.HOOK.defaultBlockState().setValue(HookBlock.FACING, d).setValue(HookBlock.PLACED, true);
            if (hook.canSurvive(level, pos)) {
                level.setBlock(pos, hook, 3);
                return level.getBlockEntity(pos) instanceof HookBlockEntity h ? h.fastener() : null;
            }
        }
        return null;
    }

    private static List<Fastener> around(ServerLevel level, Vec3 c, int r) {
        List<Fastener> out = new ArrayList<>();
        BlockPos p = BlockPos.containing(c);
        for (int cx = (p.getX() - r) >> 4; cx <= (p.getX() + r) >> 4; cx++) {
            for (int cz = (p.getZ() - r) >> 4; cz <= (p.getZ() + r) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (var be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (be instanceof HookBlockEntity h && be.getBlockPos().closerToCenterThan(c, r)) out.add(h.fastener());
                }
            }
        }
        for (KnotEntity k : level.getEntitiesOfClass(KnotEntity.class, new AABB(p).inflate(r))) out.add(k.fastener());
        return out;
    }

    private static int give(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack item = FairyLights.preset(StringArgumentType.getString(c, "preset"));
        if (item.isEmpty()) {
            c.getSource().sendFailure(Component.literal("Unknown preset. Try: " + String.join(", ", FairyLights.PRESETS)));
            return 0;
        }
        var p = c.getSource().getPlayerOrException();
        if (!p.getInventory().add(item.copy())) p.drop(item, false, net.minecraft.util.Prediction.SERVER_ONLY);
        c.getSource().sendSuccess(() -> Component.literal("Gave " + item.getHoverName().getString()), true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        int n = 0;
        for (Fastener f : around(src.getLevel(), src.getPosition(), IntegerArgumentType.getInteger(c, "radius"))) {
            n += f.outgoing().size();
            for (Strand s : List.copyOf(f.outgoing())) f.removeStrand(src.getLevel(), s, null, false);
        }
        int count = n;
        src.sendSuccess(() -> Component.literal("Took down " + count + " strands."), true);
        return n;
    }

    private static int count(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        List<Fastener> list = around(src.getLevel(), src.getPosition(), 64);
        int strands = Fastener.strands(list);
        int lights = 0;
        for (Fastener f : list) {
            for (Strand s : f.outgoing()) {
                for (BlockPos q : s.litBlocks) if (src.getLevel().getBlockState(q).is(net.minecraft.world.level.block.Blocks.LIGHT)) lights++;
            }
        }
        int lit = lights;
        src.sendSuccess(() -> Component.literal(strands + " strands (" + lit + " light blocks) on " + list.size() + " hooks/knots within 64 blocks; "
                + StrandItem.strandsInChunk(src.getLevel(), BlockPos.containing(src.getPosition())) + " start in this chunk (limit "
                + FairyLightsConfig.maxPerChunk + ")."), true);
        return strands;
    }
}
