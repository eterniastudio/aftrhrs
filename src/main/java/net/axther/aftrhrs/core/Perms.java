package net.axther.aftrhrs.core;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Permission checks with Feather's plugin.yml defaults. A permissions mod (LuckPerms for Fabric) answers first;
 * when it has no opinion the node's plugin.yml default applies: {@code true} = everyone, {@code op} = operators
 * (level 2+), {@code false} = nobody. Children of a granted parent node count as granted.
 */
public final class Perms {
    private static final Map<String, String> DEFAULTS = new HashMap<>();
    /** child -> parents that grant it. */
    private static final Map<String, List<String>> PARENTS = new HashMap<>();

    private Perms() {
    }

    static void load(Yaml pluginYml) {
        DEFAULTS.clear();
        PARENTS.clear();
        // Permission nodes are keys with dots ("feather.ban:"): read the raw map, a dotted path lookup would look for
        // "feather" → "ban" and find nothing (then every command node fell back to "true" below: /ban, /vanish, /gmc
        // for everyone, and every other default-true node such as feather.hotkey was op-only).
        Yaml perms = pluginYml.getConfigurationSection("permissions");
        if (perms != null) for (Map.Entry<String, Object> e : perms.raw().entrySet()) {
            String node = e.getKey().toLowerCase(Locale.ROOT);
            if (!(e.getValue() instanceof Map<?, ?> s)) continue;
            Object def = s.get("default");
            if (def != null) DEFAULTS.put(node, String.valueOf(def).toLowerCase(Locale.ROOT));
            if (s.get("children") instanceof Map<?, ?> children) for (Map.Entry<?, ?> c : children.entrySet()) {
                if (!Boolean.FALSE.equals(c.getValue()))
                    PARENTS.computeIfAbsent(String.valueOf(c.getKey()).toLowerCase(Locale.ROOT), k -> new java.util.ArrayList<>()).add(node);
            }
        }
        Yaml cmds = pluginYml.getConfigurationSection("commands");
        if (cmds != null) for (String c : cmds.getKeys(false)) {
            String p = cmds.getString(c + ".permission");
            if (p != null) DEFAULTS.putIfAbsent(p.toLowerCase(Locale.ROOT), "true");
        }
    }

    public static boolean has(ServerPlayer player, String node) {
        if (player == null) return true;
        return resolve(node, n -> Permissions.getPermissionValue(player, n), isOp(player), 0);
    }

    public static boolean has(CommandSourceStack source, String node) {
        if (source.getPlayer() == null) return true; // console / command blocks
        return has(source.getPlayer(), node);
    }

    public static boolean isOp(ServerPlayer p) {
        return p != null && p.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    private static boolean resolve(String node, java.util.function.Function<String, TriState> lookup, boolean op, int depth) {
        String n = node.toLowerCase(Locale.ROOT);
        // The node itself, else a parent group node set on the player (feather.staff → feather.vanish ...), at any
        // depth; like Bukkit, a parent set to false takes its children away too.
        TriState t = explicit(n, lookup, depth);
        if (t != TriState.DEFAULT) return t.get();
        // wildcard prefixes (feather.pets.cap.* style)
        int dot = n.lastIndexOf('.');
        while (dot > 0) {
            TriState w = lookup.apply(n.substring(0, dot) + ".*");
            if (w != TriState.DEFAULT) return w.get();
            dot = n.lastIndexOf('.', dot - 1);
        }
        String def = DEFAULTS.get(n);
        if (def == null) {
            for (String parent : PARENTS.getOrDefault(n, List.of())) {
                String pd = DEFAULTS.get(parent);
                if ("true".equals(pd)) return true;
                if ("op".equals(pd) && op) return true;
            }
            return op;
        }
        return switch (def) {
            case "true" -> true;
            case "false" -> false;
            case "not op", "!op" -> !op;
            default -> op;
        };
    }

    /** The value set for a node or, failing that, for one of its parents (TRUE wins over FALSE); DEFAULT if none. */
    private static TriState explicit(String n, java.util.function.Function<String, TriState> lookup, int depth) {
        TriState t = lookup.apply(n);
        if (t != TriState.DEFAULT || depth >= 6) return t;
        TriState found = TriState.DEFAULT;
        for (String parent : PARENTS.getOrDefault(n, List.of())) {
            TriState pt = explicit(parent, lookup, depth + 1);
            if (pt == TriState.TRUE) return TriState.TRUE;
            if (pt == TriState.FALSE) found = TriState.FALSE;
        }
        return found;
    }
}
