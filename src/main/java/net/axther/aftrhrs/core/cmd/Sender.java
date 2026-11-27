package net.axther.aftrhrs.core.cmd;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Perms;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/** Who ran a command: a player or the console. The CommandSender of Feather's ported command code. */
public final class Sender implements Audience {
    private final CommandSourceStack source;

    public Sender(CommandSourceStack source) {
        this.source = source;
    }

    public static Sender of(ServerPlayer player) {
        return new Sender(player.createCommandSourceStack());
    }

    public CommandSourceStack source() {
        return source;
    }

    public boolean isPlayer() {
        return source.getPlayer() != null;
    }

    /** The player, or null for the console. */
    public ServerPlayer player() {
        return source.getPlayer();
    }

    public String getName() {
        return source.getTextName();
    }

    public boolean hasPermission(String node) {
        return Perms.has(source, node);
    }

    public boolean isOp() {
        return !isPlayer() || Perms.isOp(player());
    }

    @Override
    public void sendMessage(@NotNull Component message) {
        if (isPlayer()) Adv.send(player(), message);
        else source.sendSystemMessage(Adv.vanilla(message));
    }

    public void sendMessage(String plain) {
        source.sendSystemMessage(net.minecraft.network.chat.Component.literal(plain));
    }

    @Override
    public void sendActionBar(@NotNull Component message) {
        if (isPlayer()) Adv.actionBar(player(), message);
    }
}
