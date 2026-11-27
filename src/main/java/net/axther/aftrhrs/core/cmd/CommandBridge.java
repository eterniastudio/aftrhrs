package net.axther.aftrhrs.core.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Registers every command of Feather's command table as a Brigadier literal with a greedy "args" argument and
 * dispatches to whatever module {@link #bind bound} it. Unbound commands (module off) are hidden from players.
 * Feather commands win over vanilla ones with the same name (as Feather's take-over did over EssentialsX).
 */
public final class CommandBridge {
    private record Spec(String name, List<String> aliases, String permission, String usage) {
    }

    private static final Map<String, Spec> SPECS = new HashMap<>();
    private static final Map<String, FeatherCommand> BOUND = new HashMap<>();

    private CommandBridge() {
    }

    public static void load(Yaml pluginYml) {
        SPECS.clear();
        Yaml cmds = pluginYml.getConfigurationSection("commands");
        if (cmds == null) return;
        for (String name : cmds.getKeys(false)) {
            Yaml c = cmds.section(name);
            SPECS.put(name.toLowerCase(Locale.ROOT), new Spec(name.toLowerCase(Locale.ROOT),
                    c.getStringList("aliases").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList(),
                    c.getString("permission"), c.getString("usage", "/" + name)));
        }
    }

    /** Hooks a command from the table up to a handler (Feather's {@code bind}). */
    public static void bind(String name, FeatherCommand handler) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!SPECS.containsKey(n)) AftrHrs.LOG.warn("Command /{} is not in the command table (feather/plugin.yml).", name);
        BOUND.put(n, handler);
    }

    public static void unbindAll() {
        BOUND.clear();
    }

    public static boolean isBound(String name) {
        return BOUND.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** Runs a Feather command as the given sender (menus use this for their buttons). */
    public static boolean dispatch(Sender sender, String line) {
        String trimmed = line.startsWith("/") ? line.substring(1) : line;
        String[] parts = trimmed.split(" ", -1);
        String label = parts[0].toLowerCase(Locale.ROOT);
        String name = resolve(label);
        if (name == null) return false;
        String[] args = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
        run(name, label, sender, args);
        return true;
    }

    /** The command-table name for a typed label or alias ({@code tell} → {@code msg}); the label itself if unknown. */
    public static String nameOf(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        int colon = l.indexOf(':');
        if (colon >= 0) l = l.substring(colon + 1);
        String n = resolve(l);
        return n == null ? l : n;
    }

    private static String resolve(String label) {
        if (SPECS.containsKey(label)) return label;
        for (Spec s : SPECS.values()) if (s.aliases.contains(label)) return s.name;
        return null;
    }

    /** Vanilla commands whose name Feather also has, kept until a module binds Feather's (see {@link #takeOver}). */
    private static final List<Spec> SHADOWED_SPECS = new ArrayList<>();
    private static final List<String> SHADOWED_LABELS = new ArrayList<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        SHADOWED_SPECS.clear();
        SHADOWED_LABELS.clear();
        for (Spec spec : SPECS.values()) {
            List<String> labels = new ArrayList<>();
            labels.add(spec.name);
            labels.addAll(spec.aliases);
            for (String label : labels) {
                if (dispatcher.getRoot().getChild(label) != null && !BOUND.containsKey(spec.name)) {
                    // A vanilla command with this name (/msg, /list, /kick, /ban ...): it stays until a Feather module
                    // binds ours, so turning a module off falls back to vanilla instead of losing the command.
                    SHADOWED_SPECS.add(spec);
                    SHADOWED_LABELS.add(label);
                    continue;
                }
                replace(dispatcher, node(spec, label));
            }
        }
    }

    /**
     * After the modules have bound their commands (server started, or a /reload re-registering): Feather's version
     * replaces each same-named vanilla command whose module is on; players get the new command tree.
     */
    public static void takeOver(net.minecraft.server.MinecraftServer server) {
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        boolean changed = false;
        for (int i = 0; i < SHADOWED_SPECS.size(); i++) {
            Spec spec = SHADOWED_SPECS.get(i);
            if (!BOUND.containsKey(spec.name)) continue;
            replace(dispatcher, node(spec, SHADOWED_LABELS.get(i)));
            changed = true;
        }
        SHADOWED_SPECS.clear();
        SHADOWED_LABELS.clear();
        if (changed) for (var p : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(p);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> node(Spec spec, String label) {
        return Commands.literal(label)
                .requires(src -> BOUND.containsKey(spec.name) && (spec.permission == null || Perms.has(src, spec.permission)))
                .executes(ctx -> exec(ctx, spec, label, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> suggest(ctx, b, spec, label))
                        .executes(ctx -> exec(ctx, spec, label, StringArgumentType.getString(ctx, "args"))));
    }

    private static void replace(CommandDispatcher<CommandSourceStack> dispatcher, LiteralArgumentBuilder<CommandSourceStack> node) {
        var existing = dispatcher.getRoot().getChild(node.getLiteral());
        // children.remove drops it from the tree; register() then overwrites the literal index entry too.
        if (existing != null) dispatcher.getRoot().getChildren().remove(existing);
        dispatcher.register(node);
    }

    private static int exec(CommandContext<CommandSourceStack> ctx, Spec spec, String label, String rawArgs) {
        String[] args = rawArgs.isEmpty() ? new String[0] : rawArgs.split(" ", -1);
        return run(spec.name, label, new Sender(ctx.getSource()), args) ? 1 : 0;
    }

    private static boolean run(String name, String label, Sender sender, String[] args) {
        FeatherCommand h = BOUND.get(name);
        Spec spec = SPECS.get(name);
        if (h == null) return false;
        if (spec != null && spec.permission != null && !sender.hasPermission(spec.permission)) {
            sender.source().sendFailure(Component.literal("You don't have permission to do that."));
            return false;
        }
        try {
            boolean ok = h.onCommand(sender, label, args);
            if (!ok && spec != null) sender.source().sendFailure(Component.literal(spec.usage.replace("<command>", label)));
            return ok;
        } catch (Throwable t) {
            AftrHrs.LOG.error("Command /{} failed", label, t);
            sender.source().sendFailure(Component.literal("Something went wrong running that command."));
            return false;
        }
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b, Spec spec, String label) {
        FeatherCommand h = BOUND.get(spec.name);
        if (h == null) return b.buildFuture();
        String remaining = b.getRemaining();
        String[] args = remaining.split(" ", -1);
        int lastStart = b.getStart() + remaining.lastIndexOf(' ') + 1;
        List<String> out;
        try {
            out = h.onTabComplete(new Sender(ctx.getSource()), label, args);
        } catch (Throwable t) {
            out = List.of();
        }
        SuggestionsBuilder sb = b.createOffset(lastStart);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (out != null) for (String s : out) if (s.toLowerCase(Locale.ROOT).startsWith(last)) sb.suggest(s);
        return sb.buildFuture();
    }
}
