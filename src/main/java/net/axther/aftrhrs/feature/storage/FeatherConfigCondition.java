package net.axther.aftrhrs.feature.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Yaml;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditionType;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.RegistryOps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Data-pack load condition {@code aftrhrs:feather_config}: true when every listed {@code config.yml} key is true
 * (a missing key or a missing file counts as true). Lets bundled recipes follow Feather's switches, e.g. the storage
 * recipes: {@code {"condition": "aftrhrs:feather_config", "keys": ["modules.storage", "storage.recipes"]}}.
 * Data packs load before Feather does, so this reads {@code <server>/aftrhrs/config.yml} itself; a change takes
 * effect on the next start or {@code /reload}.
 */
record FeatherConfigCondition(List<String> keys) implements ResourceCondition {
    static final MapCodec<FeatherConfigCondition> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.listOf().fieldOf("keys").forGetter(FeatherConfigCondition::keys)).apply(i, FeatherConfigCondition::new));
    static final ResourceConditionType<FeatherConfigCondition> TYPE = ResourceConditionType.create(AftrHrs.id("feather_config"), CODEC);

    static void register() {
        ResourceConditions.register(TYPE);
    }

    @Override
    public ResourceConditionType<?> getType() {
        return TYPE;
    }

    @Override
    public boolean test(RegistryOps.RegistryInfoLookup registryInfo) {
        Path file = FabricLoader.getInstance().getGameDir().resolve("aftrhrs").resolve("config.yml");
        if (!Files.isRegularFile(file)) return true;
        try {
            Yaml c = Yaml.load(file);
            for (String k : keys) if (!c.getBoolean(k, true)) return false;
            return true;
        } catch (RuntimeException e) {
            AftrHrs.LOG.warn("aftrhrs:feather_config could not read {}: {}", file, e.toString());
            return true;
        }
    }
}
