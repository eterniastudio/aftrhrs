package net.axther.aftrhrs.feature.chat;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.PlayerLookup;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Nicknames for commands Feather doesn't own (vanilla {@code /tp}, {@code /give} ...): for the commands in
 * {@code chat.nickname-commands}, an argument that is exactly someone's nickname is swapped for their username before
 * the command runs, and nicknames are added to those commands' tab completion. A word that is anyone's real username,
 * or an item, effect, enchantment or game mode name, is never touched.
 *
 * <p>The rewrite listens to {@link FeatherEvents#COMMAND} in a late phase ({@link #LATE}), so the auth gate, freeze and
 * mute checks see the command first; a rewritten command is cancelled and run again with the usernames in.
 */
public final class NickCommands implements FeatherModule {
    /** Runs after every default-phase command listener. */
    public static final Identifier LATE = AftrHrs.id("late");

    private static volatile NickCommands active;

    private final Feather plugin;
    private Set<String> commands = Set.of();

    public NickCommands(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Nickname commands";
    }

    @Override
    public void enable() {
        reload();
        active = this;
        plugin.once("nickcommands.events", () -> {
            FeatherEvents.COMMAND.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, LATE);
            FeatherEvents.COMMAND.register(LATE, (p, cmd) -> {
                NickCommands m = active;
                return m == null || m.onCommand(p, cmd);
            });
            FeatherEvents.COMMAND_SUGGESTIONS.register((p, cmd, current) -> {
                NickCommands m = active;
                return m == null ? current : m.suggestions(p, cmd, current);
            });
        });
    }

    @Override
    public void disable() {
        if (active == this) active = null;
    }

    @Override
    public void reload() {
        Set<String> s = new HashSet<>();
        for (String c : plugin.getConfig().getStringList("chat.nickname-commands")) s.add(c.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        commands = s;
    }

    /** True when the label (with or without its namespace, e.g. minecraft:tp) is one of the configured commands. */
    private boolean covered(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (commands.contains(l)) return true;
        int colon = l.indexOf(':');
        return colon >= 0 && commands.contains(l.substring(colon + 1));
    }

    // ------------------------------------------------------------------ rewriting

    private boolean onCommand(ServerPlayer player, String line) {
        if (commands.isEmpty() || plugin.authPending(player)) return true;
        String out = rewrite(player, line);
        if (out == null) return true;
        // Console, command blocks and functions are never rewritten: their commands must mean the same thing no
        // matter what nicknames players pick. Only what a player typed is.
        MinecraftServer server = Feather.server();
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), out);
        return false;
    }

    /** The command line with nicknames swapped for usernames, or null when nothing changed. */
    String rewrite(ServerPlayer viewer, String line) {
        String[] parts = line.split(" ", -1);
        if (parts.length < 2 || !covered(parts[0])) return null;
        boolean changed = false;
        for (int i = 1; i < parts.length; i++) {
            String name = username(viewer, parts[i]);
            if (name != null) {
                parts[i] = name;
                changed = true;
            }
        }
        return changed ? String.join(" ", parts) : null;
    }

    /** The username for a word that is exactly one visible player's nickname, else null. */
    private String username(ServerPlayer viewer, String token) {
        if (token.length() < 2 || token.startsWith("@") || token.indexOf(':') >= 0 || token.indexOf('~') >= 0
                || token.indexOf('^') >= 0 || !token.matches("[\\p{L}\\p{N}_]+")) return null;
        // Someone's real name (online or not) always wins.
        if (plugin.lookup().byName(token) != null) return null;
        if (vanillaWord(token)) return null;
        ServerPlayer owner = null;
        String key = PlayerLookup.key(token);
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (!plugin.canSee(viewer, p) || !PlayerLookup.key(PlayerLookup.nickPlain(p)).equals(key)) continue;
            if (owner != null) return null; // two players share it: leave it alone
            owner = p;
        }
        return owner == null || Names.name(owner).equalsIgnoreCase(token) ? null : Names.name(owner);
    }

    /** Words the vanilla commands use themselves: item, effect, enchantment and game mode names. */
    private static boolean vanillaWord(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (GameType.byName(lower, null) != null) return true;
        Identifier id = Identifier.tryParse(lower);
        if (id == null) return false;
        if (BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.MOB_EFFECT.containsKey(id)) return true;
        MinecraftServer server = Feather.serverOrNull();
        if (server == null) return false;
        return server.registryAccess().lookup(Registries.ENCHANTMENT).map(r -> r.containsKey(id)).orElse(false);
    }

    // ------------------------------------------------------------------ tab completion

    /** Nicknames ({@code _} for spaces) starting with the last word, merged into a covered command's suggestions. */
    private Suggestions suggestions(ServerPlayer viewer, String buffer, Suggestions current) {
        if (commands.isEmpty() || buffer == null || plugin.authPending(viewer)) return current;
        int space = buffer.indexOf(' ');
        if (space < 0 || !covered(buffer.substring(0, space))) return current;
        String last = buffer.substring(buffer.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
        List<String> extra = new ArrayList<>();
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (!plugin.canSee(viewer, p)) continue;
            String nick = PlayerLookup.nickToken(p);
            if (nick != null && nick.toLowerCase(Locale.ROOT).startsWith(last)) extra.add(nick);
        }
        if (extra.isEmpty()) return current;
        // The client's buffer starts with the slash; ranges count it.
        int start = buffer.lastIndexOf(' ') + 2;
        StringRange range = new StringRange(start, buffer.length() + 1);
        List<Suggestion> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Suggestion s : current.getList()) {
            list.add(s);
            seen.add(s.getText());
        }
        for (String s : extra) if (seen.add(s)) list.add(new Suggestion(range, s));
        StringRange all = current.getList().isEmpty() ? range : StringRange.encompassing(current.getRange(), range);
        return new Suggestions(all, list);
    }
}
