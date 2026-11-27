package net.axther.aftrhrs.feature.smithing;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Staff tools for smithing: {@code /smith give <tool> <material...> [stars]} (one material per part,
 * the last repeats), {@code /smith part <part> <material> [stars]}, {@code /smith kit} (every station and hand
 * tool), {@code /smith mod <modifier> [level]} (adds a modifier to the held tool), {@code /smith heat <degrees>}
 * (heats the held piece).
 */
public final class SmithingCommands {
    private SmithingCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> d.register(Commands.literal("smith")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("give")
                        .then(Commands.argument("tool", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(SmithingDefs.tools().keySet(), b))
                                .then(Commands.argument("materials", StringArgumentType.greedyString())
                                        .executes(c -> give(c, 3)))))
                .then(Commands.literal("part")
                        .then(Commands.argument("part", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(SmithingDefs.parts().keySet(), b))
                                .then(Commands.argument("material", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(SmithingDefs.materials().keySet(), b))
                                        .executes(c -> part(c, 3))
                                        .then(Commands.argument("stars", IntegerArgumentType.integer(1, 5))
                                                .executes(c -> part(c, IntegerArgumentType.getInteger(c, "stars")))))))
                .then(Commands.literal("kit").executes(SmithingCommands::kit))
                .then(Commands.literal("mod")
                        .then(Commands.argument("modifier", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(SmithingDefs.modifiers().keySet(), b))
                                .executes(c -> mod(c, 1))
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, 5))
                                        .executes(c -> mod(c, IntegerArgumentType.getInteger(c, "level"))))))
                .then(Commands.literal("heat")
                        .then(Commands.argument("degrees", IntegerArgumentType.integer(20, 1800)).executes(SmithingCommands::heat)))));
    }

    private static void giveTo(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    private static int give(CommandContext<CommandSourceStack> c, int stars) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        // "iron leather wood 4" or "iron,leather,wood": one material per part, an optional star count last
        List<String> mats = new ArrayList<>(Arrays.asList(StringArgumentType.getString(c, "materials").trim().split("[ ,]+")));
        if (mats.size() > 1 && mats.get(mats.size() - 1).matches("[1-5]")) stars = Integer.parseInt(mats.remove(mats.size() - 1));
        ItemStack s = Smithing.tool(StringArgumentType.getString(c, "tool"), mats, List.of(stars), p.level().registryAccess());
        if (s.isEmpty()) {
            c.getSource().sendFailure(Component.literal("Those materials can't make that tool."));
            return 0;
        }
        ToolBuild b = s.get(Smithing.BUILD);
        if (b != null) s.set(Smithing.BUILD, b.withSmith(p.getGameProfile().name()));
        ToolStats.apply(s, p.level().registryAccess());
        giveTo(p, s);
        return 1;
    }

    private static int part(CommandContext<CommandSourceStack> c, int stars) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack s = Smithing.part(StringArgumentType.getString(c, "part"), StringArgumentType.getString(c, "material"), stars);
        if (s.isEmpty()) {
            c.getSource().sendFailure(Component.literal("That material can't make that part."));
            return 0;
        }
        giveTo(p, s);
        return 1;
    }

    private static int kit(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        List<ItemStack> all = new ArrayList<>();
        for (Station s : Station.values()) all.add(new ItemStack(Smithing.block(s)));
        all.add(new ItemStack(Smithing.TONGS));
        all.add(new ItemStack(Smithing.KNIFE));
        all.add(new ItemStack(Smithing.CHISEL));
        all.add(new ItemStack(Smithing.STIRRING_ROD));
        all.add(new ItemStack(Smithing.LADLE_ITEM));
        all.add(new ItemStack(Smithing.MOLDING_BOX));
        all.add(new ItemStack(Smithing.ALMANAC));
        all.add(Smithing.tool("smithing_hammer", List.of("iron", "iron", "wood"), List.of(3), p.level().registryAccess()));
        all.forEach(s -> giveTo(p, s));
        return all.size();
    }

    private static int mod(CommandContext<CommandSourceStack> c, int level) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack s = p.getMainHandItem();
        ToolBuild b = s.get(Smithing.BUILD);
        String id = StringArgumentType.getString(c, "modifier");
        if (b == null || SmithingDefs.modifier(id) == null) {
            c.getSource().sendFailure(Component.literal("Hold a smithed tool and name a modifier."));
            return 0;
        }
        s.set(Smithing.BUILD, b.withMod(id, level));
        ToolStats.apply(s, p.level().registryAccess());
        return 1;
    }

    private static int heat(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack s = Hands.held(p, net.minecraft.world.InteractionHand.MAIN_HAND);
        if (s.isEmpty()) return 0;
        Heat.set(s, IntegerArgumentType.getInteger(c, "degrees"), p.level().getGameTime());
        if (Hands.tongs(p.getMainHandItem())) p.getMainHandItem().set(Smithing.HELD, s.copy());
        return 1;
    }
}
