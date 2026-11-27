package net.axther.aftrhrs.core.cmd;

import java.util.List;

/**
 * A command bound to a name from Feather's command table ({@code feather/plugin.yml}), Bukkit-executor style:
 * the raw label and space-split args. Return false to show the usage line.
 */
public interface FeatherCommand {
    boolean onCommand(Sender sender, String label, String[] args);

    default List<String> onTabComplete(Sender sender, String label, String[] args) {
        return List.of();
    }
}
