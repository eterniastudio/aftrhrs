package net.axther.aftrhrs.feature.display;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Feather;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tab-list names (Paper's {@code player.playerListName(...)}): {@code ServerPlayerTabNameMixin} answers
 * {@code getTabListDisplayName} from here, so every player-info packet vanilla sends carries it.
 */
public final class TabListNames {
    private static final Map<UUID, Component> NAMES = new ConcurrentHashMap<>();

    private static boolean hooked;

    private TabListNames() {
    }

    /** Forgets names as players leave (once per JVM; modules that set names call it in enable). */
    public static synchronized void init() {
        if (hooked) return;
        hooked = true;
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> NAMES.remove(h.player.getUUID()));
    }

    /** Clears every name (the tab module stopped). */
    public static void clearAll() {
        MinecraftServer server = Feather.serverOrNull();
        java.util.List<UUID> ids = new java.util.ArrayList<>(NAMES.keySet());
        NAMES.clear();
        if (server == null) return;
        for (UUID id : ids) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, p));
        }
    }

    public static Component get(UUID id) {
        return NAMES.get(id);
    }

    /** Sets (or with null, clears) a player's tab-list name and tells every client when it changed. */
    public static void set(ServerPlayer player, net.kyori.adventure.text.Component name) {
        Component c = name == null ? null : Adv.vanilla(name);
        Component before = c == null ? NAMES.remove(player.getUUID()) : NAMES.put(player.getUUID(), c);
        if (Objects.equals(before, c)) return;
        MinecraftServer server = Feather.serverOrNull();
        if (server != null && server.getPlayerList().getPlayer(player.getUUID()) == player) { // not before the player is listed
            var packet = new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player);
            Feather f = Feather.get();
            for (ServerPlayer v : server.getPlayerList().getPlayers()) if (f == null || f.canSee(v, player)) v.connection.send(packet);
        }
    }

    public static void forget(UUID id) {
        NAMES.remove(id);
    }
}
