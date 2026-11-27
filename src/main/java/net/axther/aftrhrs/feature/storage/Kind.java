package net.axther.aftrhrs.feature.storage;

import java.util.Locale;

/** The three storage blocks. {@code id} is used in config, messages, commands and item tags; {@code block} is the registry path. */
enum Kind {
    TERMINAL("terminal", "feather_storage_terminal"),
    CRAFTING("crafting", "feather_crafting_terminal"),
    CONNECTOR("connector", "feather_inventory_connector");

    final String id;
    final String block;

    Kind(String id, String block) {
        this.id = id;
        this.block = block;
    }

    /** Terminals open a window; connectors only join storage into a network. */
    boolean terminal() {
        return this != CONNECTOR;
    }

    static Kind parse(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (Kind kind : values()) if (kind.id.equals(k) || kind.block.equals(k)) return kind;
        return switch (k) {
            case "storage", "storage-terminal", "storage_terminal" -> TERMINAL;
            case "crafting-terminal", "crafting_terminal", "craft" -> CRAFTING;
            case "inventory-connector", "inventory_connector", "relay" -> CONNECTOR;
            default -> null;
        };
    }
}
