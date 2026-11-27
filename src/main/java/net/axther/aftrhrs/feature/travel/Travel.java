package net.axther.aftrhrs.feature.travel;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.cmd.CommandBridge;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Bits the travel modules share: per-name command binding, world names, running a command as a player. */
final class Travel {
    private Travel() {
    }

    /** A command handler that knows which command of the table it was bound to (aliases arrive as the label). */
    interface Named {
        boolean run(String command, Sender sender, String[] args);

        default List<String> complete(String command, Sender sender, String[] args) {
            return List.of();
        }
    }

    /** Binds every command in {@code names} to {@code handler}, passing the command's own name (not the alias typed). */
    static void bind(Feather plugin, Named handler, String... names) {
        for (String name : names) {
            plugin.bind(name, new FeatherCommand() {
                @Override
                public boolean onCommand(Sender sender, String label, String[] args) {
                    return handler.run(name, sender, args);
                }

                @Override
                public List<String> onTabComplete(Sender sender, String label, String[] args) {
                    return handler.complete(name, sender, args);
                }
            });
        }
    }

    /**
     * The icon id a /homeicon or /warpicon argument asks for: "" for "clear" (back to the default), the id of the item in
     * the main hand when {@code arg} is null, else the named item ("minecraft:" optional). Null: empty hand or no such item.
     */
    static String iconId(ServerPlayer player, String arg) {
        if (arg != null && (arg.equalsIgnoreCase("clear") || arg.equalsIgnoreCase("reset") || arg.equalsIgnoreCase("none"))) return "";
        net.minecraft.world.item.Item item;
        if (arg == null) {
            net.minecraft.world.item.ItemStack held = player.getMainHandItem();
            item = held.isEmpty() ? null : held.getItem();
        } else {
            item = net.axther.aftrhrs.core.Profiles.Home.iconItem(arg);
        }
        if (item == null) return null;
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
    }

    /** Tab completion for an icon argument: "clear" and item ids. */
    static List<String> completeIcon(String typed) {
        List<String> out = new java.util.ArrayList<>(List.of("clear"));
        String t = typed.toLowerCase(java.util.Locale.ROOT);
        for (var key : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
            String id = key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
            if (id.startsWith(t) && out.size() < 60) out.add(id);
        }
        return out;
    }

    /** Bukkit's {@code player.performCommand}: runs a Feather command as the player (permissions apply). */
    static void perform(ServerPlayer player, String line) {
        CommandBridge.dispatch(Sender.of(player), line);
    }

    /** Puts the stack in the player's inventory; what doesn't fit drops at their feet. */
    static void giveOrDrop(ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return;
        player.getInventory().add(stack);
        if (!stack.isEmpty()) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    /** "Overworld", "Nether", "The End", or the name itself. */
    static String prettyWorld(String world) {
        return switch (world) {
            case "world" -> "Overworld";
            case "world_nether" -> "Nether";
            case "world_the_end" -> "The End";
            default -> world;
        };
    }

    /** "x, y, z" block coordinates of a saved point. */
    static String coords(double x, double y, double z) {
        return (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
    }
}
