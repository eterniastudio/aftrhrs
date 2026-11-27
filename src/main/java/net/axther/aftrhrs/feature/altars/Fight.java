package net.axther.aftrhrs.feature.altars;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Worlds;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A boss summoned at an altar that has not died yet. Saved in altars-data.yml so restarts keep tracking it. */
final class Fight {
    final UUID boss;
    final String mobType;
    final UUID summoner;
    final String summonerName;
    final String itemId;
    final String altarKey;
    /** Paper-style world name ("world", "world_nether", ...), as altars-data.yml always stored it. */
    final String world;
    final double x, y, z;
    final long startedAt;
    /** Seconds in a row with no player near the boss (or its arena, while unloaded). Not saved. */
    int lonelySeconds;
    /** Seconds in a row the arena was loaded and watched but the boss was gone. Not saved. */
    int missingSeconds;
    /** Party scaling: the number of players the boss is scaled for (0 = not scaled yet). */
    int scaledFor;
    /** Party scaling: multiplier on the damage the boss deals. */
    double damageMult = 1.0;
    /**
     * Party scaling beyond the max-health cap: damage taken by the boss is divided by this, so the boss is as tough as
     * the curve asks even when its max health could not go that high.
     */
    double toughness = 1.0;
    /** Damage dealt to the boss per player (and the names they had), for the boss board. */
    final Map<UUID, Double> damage = new LinkedHashMap<>();
    final Map<UUID, String> names = new LinkedHashMap<>();

    Fight(UUID boss, String mobType, UUID summoner, String summonerName, String itemId, String altarKey, Loc home, long startedAt) {
        this(boss, mobType, summoner, summonerName, itemId, altarKey, Worlds.name(home.level()), home.x(), home.y(), home.z(), startedAt);
    }

    private Fight(UUID boss, String mobType, UUID summoner, String summonerName, String itemId, String altarKey,
                  String world, double x, double y, double z, long startedAt) {
        this.boss = boss;
        this.mobType = mobType;
        this.summoner = summoner;
        this.summonerName = summonerName;
        this.itemId = itemId;
        this.altarKey = altarKey;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.startedAt = startedAt;
    }

    /** Where the boss appeared (its leash anchor), or null when that world is not loaded. */
    Loc home() {
        ServerLevel l = Worlds.resolve(world);
        return l == null ? null : new Loc(l, x, y, z);
    }

    void save(Yaml s) {
        s.set("boss", boss.toString());
        s.set("mob", mobType);
        s.set("summoner", summoner.toString());
        s.set("summoner-name", summonerName);
        s.set("item", itemId);
        s.set("altar", altarKey);
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("started", startedAt);
        if (scaledFor > 0) {
            s.set("scaled-for", scaledFor);
            s.set("damage-mult", damageMult);
            s.set("toughness", toughness);
        }
        for (Map.Entry<UUID, Double> e : damage.entrySet()) {
            s.set("damage." + e.getKey() + ".amount", e.getValue());
            s.set("damage." + e.getKey() + ".name", names.getOrDefault(e.getKey(), "?"));
        }
    }

    static Fight load(Yaml s) {
        try {
            Fight f = new Fight(UUID.fromString(s.getString("boss")), s.getString("mob"),
                    UUID.fromString(s.getString("summoner")), s.getString("summoner-name", "?"), s.getString("item"),
                    s.getString("altar", ""), s.getString("world"), s.getDouble("x"), s.getDouble("y"),
                    s.getDouble("z"), s.getLong("started"));
            f.scaledFor = Math.max(0, s.getInt("scaled-for", 0));
            f.damageMult = Math.max(0.0, s.getDouble("damage-mult", 1.0));
            f.toughness = Math.max(1.0, s.getDouble("toughness", 1.0));
            Yaml d = s.getConfigurationSection("damage");
            if (d != null) {
                for (String k : d.getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(k);
                        f.damage.put(id, d.getDouble(k + ".amount"));
                        f.names.put(id, d.getString(k + ".name", "?"));
                    } catch (IllegalArgumentException ignored) {
                        // not a player id
                    }
                }
            }
            return f;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
