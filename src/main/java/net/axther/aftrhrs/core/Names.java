package net.axther.aftrhrs.core;

import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Display names: Paper's {@code player.displayName()} / {@code displayName(Component)}. The nickname module sets
 * them; everything that shows a player to others (chat, menus, placeholders, mentions) reads {@link #display}.
 * Without a nickname it is the plain username. Cleared when the player leaves.
 */
public final class Names {
    private static final Map<UUID, Component> DISPLAY = new ConcurrentHashMap<>();

    private Names() {
    }

    /** The player's display name (nickname, else username). */
    public static Component display(ServerPlayer player) {
        if (player == null) return Component.empty();
        Component c = DISPLAY.get(player.getUUID());
        return c != null ? c : Component.text(player.getGameProfile().name());
    }

    /** Sets (or with null, clears) the display name. Call {@link Feather#playerNamesChanged} afterwards. */
    public static void set(ServerPlayer player, Component name) {
        if (name == null) DISPLAY.remove(player.getUUID());
        else DISPLAY.put(player.getUUID(), name);
    }

    /** The display name as plain text. */
    public static String plain(ServerPlayer player) {
        return Adv.plain(display(player));
    }

    /** The username. */
    public static String name(ServerPlayer player) {
        return player.getGameProfile().name();
    }

    static void forget(UUID id) {
        DISPLAY.remove(id);
    }
}
