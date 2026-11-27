package net.axther.aftrhrs.feature.altars;

import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ability.Fx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One summonable boss from altars.yml {@code bosses.<MythicMobType>} (the MythicMobs id maps to the native type). */
record BossSpec(String mobType, String itemId, String name, boolean flying, int width, int height,
                Set<Env> environments, List<String> biomeKeywords, Double minTemperature,
                Double maxTemperature, Integer minY, Integer maxY, boolean needsSky, String hint,
                ParticleOptions particle, String chargeSound, String arrivalSound) {

    /** Bukkit's World.Environment, as altars.yml names it. */
    enum Env {
        NORMAL, NETHER, THE_END;

        static Env of(Level level) {
            if (level.dimension() == Level.NETHER) return NETHER;
            if (level.dimension() == Level.END) return THE_END;
            if (level.dimension() == Level.OVERWORLD) return NORMAL;
            var type = level.dimensionType();
            return type.hasCeiling() ? NETHER : type.hasSkyLight() ? NORMAL : THE_END;
        }
    }

    static BossSpec parse(String mobType, Yaml s, Logger log) {
        String item = s.getString("item", "").trim().toLowerCase(Locale.ROOT);
        if (item.isEmpty()) {
            log.warn("altars.yml: bosses.{} has no item; skipped.", mobType);
            return null;
        }
        List<Integer> space = s.getIntegerList("space");
        int w = space.size() > 0 ? Math.max(1, space.get(0)) : 3;
        int h = space.size() > 1 ? Math.max(1, space.get(1)) : 3;
        Set<Env> envs = EnumSet.noneOf(Env.class);
        Yaml where = s.getConfigurationSection("where");
        List<String> keywords = new ArrayList<>();
        Double minT = null, maxT = null;
        Integer minY = null, maxY = null;
        boolean sky = false;
        String hint = "";
        if (where != null) {
            for (String e : where.getStringList("environments")) {
                try {
                    envs.add(Env.valueOf(e.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    log.warn("altars.yml: bosses.{}.where.environments: unknown {} (NORMAL, NETHER, THE_END)", mobType, e);
                }
            }
            for (String k : where.getStringList("biome-keywords")) {
                if (!k.isBlank()) keywords.add(k.trim().toLowerCase(Locale.ROOT));
            }
            if (where.isSet("min-temperature")) minT = where.getDouble("min-temperature");
            if (where.isSet("max-temperature")) maxT = where.getDouble("max-temperature");
            if (where.isSet("min-y")) minY = where.getInt("min-y");
            if (where.isSet("max-y")) maxY = where.getInt("max-y");
            sky = where.getBoolean("open-sky", false);
            hint = where.getString("hint", "");
        }
        ParticleOptions particle = ParticleTypes.ENCHANT;
        String p = s.getString("particle", "ENCHANT");
        ParticleOptions parsed = Fx.particle(p.trim().toLowerCase(Locale.ROOT), Map.of());
        // Particles that need extra data (dust color, block) would fall back to a default look: use ENCHANT, as before.
        if (parsed instanceof SimpleParticleType) particle = parsed;
        else log.warn("altars.yml: bosses.{}.particle: unknown or data particle {}; using ENCHANT.", mobType, p);
        return new BossSpec(mobType, item, s.getString("name", mobType), s.getBoolean("flying", false), w, h,
                envs, keywords, minT, maxT, minY, maxY, sky, hint, particle,
                s.getString("charge-sound", "block.beacon.ambient"), s.getString("arrival-sound", "entity.wither.spawn"));
    }

    /** Null when the altar's spot suits this boss, otherwise the reason ("environment", "terrain", "height", "sky"). */
    String placeProblem(ServerLevel level, BlockPos altar) {
        if (!environments.isEmpty() && !environments.contains(Env.of(level))) return "environment";
        boolean terrainRules = !biomeKeywords.isEmpty() || minTemperature != null || maxTemperature != null;
        if (terrainRules && !biomeMatches(level, altar) && !temperatureMatches(level, altar)) return "terrain";
        if (minY != null && altar.getY() < minY) return "height";
        if (maxY != null && altar.getY() > maxY) return "height";
        if (needsSky && level.getBrightness(LightLayer.SKY, altar.above()) < 15) return "sky";
        return null;
    }

    private boolean biomeMatches(ServerLevel level, BlockPos pos) {
        if (biomeKeywords.isEmpty()) return false;
        String key = level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString().toLowerCase(Locale.ROOT)).orElse("");
        for (String k : biomeKeywords) if (key.contains(k)) return true;
        return false;
    }

    private boolean temperatureMatches(ServerLevel level, BlockPos pos) {
        if (minTemperature == null && maxTemperature == null) return false;
        double t = level.getBiome(pos).value().getBaseTemperature();
        return (minTemperature == null || t >= minTemperature) && (maxTemperature == null || t <= maxTemperature);
    }
}
