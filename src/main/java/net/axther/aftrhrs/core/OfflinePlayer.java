package net.axther.aftrhrs.core;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * A player who may be offline (Bukkit's {@code OfflinePlayer}): id and last known name. {@link #online()} gives the
 * {@link ServerPlayer} while they are on.
 */
public record OfflinePlayer(UUID id, String name) {
    public static OfflinePlayer of(ServerPlayer p) {
        return new OfflinePlayer(p.getUUID(), p.getGameProfile().name());
    }

    /** The online player, or null. */
    public ServerPlayer online() {
        Feather f = Feather.get();
        return f == null ? null : f.player(id);
    }

    public boolean isOnline() {
        return online() != null;
    }

    public UUID getUniqueId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Bukkit's {@code hasPlayedBefore}: a Feather profile or vanilla player data exists. */
    public boolean hasPlayedBefore() {
        Feather f = Feather.get();
        return f != null && f.profiles().hasPlayedBefore(id);
    }
}
