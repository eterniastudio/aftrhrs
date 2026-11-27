package net.axther.aftrhrs.feature.moderation;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff chat: {@code /sc <message>} goes to everyone with {@code feather.staffchat} and the console; {@code /sc}
 * alone toggles a mode where everything the player says in chat goes there instead.
 *
 * <p>Toggled chat is taken in the moderation phase of the chat event, before mutes and the chat lock, and never
 * reaches the chat module or vanilla's broadcast.
 */
public final class StaffChat implements FeatherCommand {
    private static volatile StaffChat active;

    private final Feather plugin;
    /** Players whose normal chat goes to staff chat. Cleared when they leave, so a relog starts in public chat. */
    private final Set<UUID> toggled = ConcurrentHashMap.newKeySet();

    StaffChat(Feather plugin) {
        this.plugin = plugin;
    }

    void enable() {
        active = this;
        plugin.bind("staffchat", this);
        plugin.once("staffchat.events", () -> ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            StaffChat m = active;
            if (m != null) m.toggled.remove(h.player.getUUID());
        }));
    }

    void disable() {
        if (active == this) active = null;
    }

    public boolean toggled(ServerPlayer p) {
        return toggled.contains(p.getUUID());
    }

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        Text text = plugin.text();
        if (args.length == 0) {
            ServerPlayer p = sender.player();
            if (p == null) {
                text.send(sender, "mod.staffchat-usage");
                return true;
            }
            boolean on = toggled.add(p.getUUID());
            if (!on) toggled.remove(p.getUUID());
            text.send(p, on ? "mod.staffchat-on" : "mod.staffchat-off");
            text.sound(p, "click");
            return true;
        }
        send(sender.player(), String.join(" ", args));
        return true;
    }

    /** Sends a line to every online staff member and the console ({@code from} null = the console). */
    public void send(ServerPlayer from, String message) {
        Component name = from != null ? Names.display(from) : Component.text("Console");
        Component line = plugin.text().msg("mod.staffchat", Text.component("name", name),
                Text.unparsed("username", from != null ? Names.name(from) : "Console"),
                Text.component("message", Component.text(message)));
        for (ServerPlayer p : plugin.onlinePlayers()) {
            if (Perms.has(p, "feather.staffchat")) Adv.send(p, line);
        }
        Feather.server().sendSystemMessage(Adv.vanilla(line));
    }

    /** A chat line: false (taken) when it went to staff chat. */
    boolean onChat(ServerPlayer p, String message) {
        if (!toggled.contains(p.getUUID())) return true;
        if (!Perms.has(p, "feather.staffchat")) {
            toggled.remove(p.getUUID());
            return true;
        }
        send(p, message);
        return false;
    }
}
