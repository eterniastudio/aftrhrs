package net.axther.aftrhrs.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.UUID;

/**
 * Maps the world names Feather saved on Paper ("world", "world_nether", "world_the_end", Multiverse names,
 * "minecraft:overworld" keys, world UUIDs) onto this server's levels, and back.
 */
public final class Worlds {
    private Worlds() {
    }

    /** A level for an old world name or key, or null if none matches. */
    public static ServerLevel resolve(String nameOrKey) {
        MinecraftServer s = Feather.serverOrNull();
        if (s == null || nameOrKey == null || nameOrKey.isBlank()) return null;
        String n = nameOrKey.trim().toLowerCase(Locale.ROOT);
        switch (n) {
            case "world", "overworld" -> {
                return s.overworld();
            }
            case "world_nether", "nether", "the_nether", "dim-1" -> {
                return s.getLevel(Level.NETHER);
            }
            case "world_the_end", "the_end", "end", "dim1" -> {
                return s.getLevel(Level.END);
            }
            default -> {
            }
        }
        Identifier id = Identifier.tryParse(n.contains(":") ? n : "minecraft:" + n);
        if (id != null) {
            ServerLevel l = s.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (l != null) return l;
        }
        // Multiverse-style "world_x" names saved as levels with a namespace of our own
        Identifier own = Identifier.tryParse("aftrhrs:" + n.replaceAll("[^a-z0-9_./-]", "_"));
        if (own != null) return s.getLevel(ResourceKey.create(Registries.DIMENSION, own));
        return null;
    }

    /** Resolves by (uuid, key, name) as Feather stored them; the UUID is ignored (Paper-only). */
    public static ServerLevel resolve(UUID ignoredUuid, String key, String name) {
        ServerLevel l = resolve(key);
        return l != null ? l : resolve(name);
    }

    /** The key Feather writes for a level ("minecraft:overworld"). */
    public static String key(Level level) {
        return level.dimension().identifier().toString();
    }

    /** The Paper-style name ("world", "world_nether", "world_the_end", else the key path) for files that keep names. */
    public static String name(Level level) {
        if (level.dimension() == Level.OVERWORLD) return "world";
        if (level.dimension() == Level.NETHER) return "world_nether";
        if (level.dimension() == Level.END) return "world_the_end";
        return level.dimension().identifier().getPath();
    }
}
