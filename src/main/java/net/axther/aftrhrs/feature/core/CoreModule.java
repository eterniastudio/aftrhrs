package net.axther.aftrhrs.feature.core;

import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hub;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * Feather's own bits that aren't a service: {@code /feather} (alias /menu: the hub, {@code /feather reload}, or
 * {@code /feather update ...}, the dedicated server's updater: {@code ServerUpdates}) and the
 * Sneak + swap-hands hotkey (Shift+F) that opens {@code hotkey.opens} (hub, teleport or homes) instead of swapping.
 */
public final class CoreModule implements FeatherModule, FeatherCommand {
    private final Feather plugin;
    private static volatile CoreModule active;

    public CoreModule(Feather plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Core";
    }

    @Override
    public void enable() {
        active = this;
        plugin.bind("feather", this);
        plugin.once("core.hotkey", () -> FeatherEvents.SWAP_HANDS.register(p -> {
            CoreModule m = active;
            return m == null || !m.hotkey(p);
        }));
    }

    @Override
    public void disable() {
        if (active == this) active = null;
    }

    /** Sneak + swap-hands opens a menu instead of swapping. True when it did (the swap is cancelled). */
    private boolean hotkey(ServerPlayer player) {
        if (!player.isShiftKeyDown() || !plugin.getConfig().getBoolean("hotkey.enabled", true)) return false;
        if (!Perms.has(player, "feather.hotkey")) return false;
        String target = plugin.getConfig().getString("hotkey.opens", "hub").toLowerCase(Locale.ROOT);
        Hub hub = plugin.hub();
        switch (target) {
            case "teleport", "tpa" -> hub.run("visit", player);
            case "homes" -> hub.run("homes", player);
            default -> hub.openHub(player);
        }
        return true;
    }

    // ------------------------------------------------------------------ /feather

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        if (net.axther.aftrhrs.update.ServerUpdates.command(sender, args)) return true;
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("feather.admin")) {
                text.send(sender, "general.no-permission");
                return true;
            }
            try {
                plugin.reload();
                text.send(sender, "general.reloaded");
            } catch (Exception e) {
                plugin.getLogger().error("Reload failed", e);
                text.send(sender, "general.reload-failed", Text.unparsed("error", String.valueOf(e.getMessage())));
            }
            return true;
        }
        ServerPlayer player = sender.player();
        if (player == null) {
            String version = FabricLoader.getInstance().getModContainer("aftrhrs")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
            sender.sendMessage("Feather " + version + " - /" + label + " reload | update");
            return true;
        }
        plugin.hub().openHub(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        if (!sender.hasPermission("feather.admin")) return List.of();
        List<String> out = new java.util.ArrayList<>(net.axther.aftrhrs.update.ServerUpdates.complete(args));
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(0, "reload");
        return out;
    }
}
