package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One wild creature from wildlife.yml {@code species.<MythicMobsId>}: what it breeds on and which harvests it has. */
final class WildSpecies {
    /** A product: item (content id or vanilla id), amount min..max. */
    record Drop(String item, int min, int max) {
        static Drop of(Yaml c, String defItem) {
            String item = c.getString("item", defItem);
            int min = Math.max(0, c.getInt("min", 1));
            int max = Math.max(min, c.getInt("max", min));
            return item == null || item.isBlank() ? null : new Drop(item.trim(), min, max);
        }
    }

    record Timed(Drop drop, long cooldownMs) {
    }

    record Shear(Drop drop, long regrowMs, Set<Block> regrowOn) {
    }

    record Lay(Drop drop, long minMs, long maxMs, double nestRadius) {
    }

    record Root(Drop drop, long cooldownMs, long retryMs, double chance, boolean leashed, Set<Block> blocks) {
    }

    record Fed(Match food, long durationMs) {
    }

    record Silk(Drop drop, double radius, long everyMs, int max) {
    }

    record Drip(Drop drop, long everyMs) {
    }

    record Bucket(String item, boolean placeWater) {
    }

    record Antlers(Drop drop, long regrowMs, double chance, double playerWithin) {
    }

    final String id;
    final String name;
    final String young;
    final Match breedFood;
    final long milkCooldownMs;
    final Timed brush;
    final Shear shear;
    final Lay lay;
    final Root root;
    final Fed fed;
    final Silk silk;
    final Drip drip;
    final Bucket bucket;
    final Antlers antlers;
    /** Glass bottle -> item on a cooldown (the Voidpuff's breath). */
    final Timed bottle;

    WildSpecies(String id, Yaml c) {
        this.id = id;
        this.name = c.getString("name", id.startsWith("Arcana") ? id.substring("Arcana".length()) : id);
        String y = c.getString("young", "");
        this.young = y == null || y.isBlank() ? null : y.trim();
        List<String> food = c.getStringList("breed-food");
        this.breedFood = food.isEmpty() ? null : new Match(food);
        Yaml s;
        s = c.getConfigurationSection("milk");
        milkCooldownMs = s == null ? 0 : minutes(s.getDouble("cooldown-minutes", 5));
        s = c.getConfigurationSection("brush");
        Drop d = s == null ? null : Drop.of(s, null);
        brush = d == null ? null : new Timed(d, minutes(s.getDouble("cooldown-minutes", 10)));
        s = c.getConfigurationSection("shear");
        d = s == null ? null : Drop.of(s, null);
        shear = d == null ? null : new Shear(d, minutes(s.getDouble("regrow-minutes", 10)), blocks(s.getStringList("regrow-on")));
        s = c.getConfigurationSection("lay");
        d = s == null ? null : Drop.of(s, null);
        if (d != null) {
            long min = minutes(s.getDouble("min-minutes", 6)), max = Math.max(min, minutes(s.getDouble("max-minutes", 10)));
            lay = new Lay(d, min, max, Math.max(0, s.getDouble("nest-radius", 4)));
        } else lay = null;
        s = c.getConfigurationSection("root");
        d = s == null ? null : Drop.of(s, null);
        root = d == null ? null : new Root(d, minutes(s.getDouble("cooldown-minutes", 8)), seconds(s.getDouble("retry-seconds", 60)),
                clamp01(s.getDouble("chance", 0.35)), s.getBoolean("leashed", true), blocks(s.getStringList("blocks")));
        s = c.getConfigurationSection("fed");
        fed = s == null || s.getStringList("food").isEmpty() ? null : new Fed(new Match(s.getStringList("food")), minutes(s.getDouble("minutes", 20)));
        s = c.getConfigurationSection("silk");
        d = s == null ? null : Drop.of(s, null);
        silk = d == null ? null : new Silk(d, Math.max(1, s.getDouble("radius", 3)), minutes(s.getDouble("every-minutes", 6)), Math.max(1, s.getInt("max", 8)));
        s = c.getConfigurationSection("drip");
        d = s == null ? null : Drop.of(s, null);
        drip = d == null ? null : new Drip(d, minutes(s.getDouble("every-minutes", 5)));
        s = c.getConfigurationSection("bucket");
        bucket = s == null || s.getString("item", "").isBlank() ? null : new Bucket(s.getString("item").trim(), s.getBoolean("place-water", true));
        s = c.getConfigurationSection("antlers");
        d = s == null ? null : Drop.of(s, null);
        antlers = d == null ? null : new Antlers(d, hours(s.getDouble("regrow-hours", 24)), clamp01(s.getDouble("chance", 0.02)),
                Math.max(0, s.getDouble("player-within", 32)));
        s = c.getConfigurationSection("bottle");
        d = s == null ? null : Drop.of(s, null);
        bottle = d == null ? null : new Timed(d, minutes(s.getDouble("cooldown-minutes", 15)));
    }

    boolean breedable() {
        return breedFood != null && young != null;
    }

    static long seconds(double s) {
        return (long) (Math.max(0, s) * 1000);
    }

    static long minutes(double m) {
        return (long) (Math.max(0, m) * 60_000);
    }

    static long hours(double h) {
        return (long) (Math.max(0, h) * 3_600_000);
    }

    static double clamp01(double d) {
        return Math.max(0, Math.min(1, d));
    }

    static Set<Block> blocks(List<String> names) {
        Set<Block> out = new HashSet<>();
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            String k = n.trim().toLowerCase(Locale.ROOT);
            Identifier id = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.getValue(id);
            if (b != Blocks.AIR) out.add(b);
        }
        return out;
    }
}
