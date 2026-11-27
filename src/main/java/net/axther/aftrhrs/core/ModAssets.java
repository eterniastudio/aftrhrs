package net.axther.aftrhrs.core;

import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Whether the mod jar ships an asset (client and server run the same jar), cached. */
final class ModAssets {
    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private ModAssets() {
    }

    /** True when {@code assets/<namespace>/<path>} is in the mod. */
    static boolean has(String namespace, String path) {
        return CACHE.computeIfAbsent(namespace + ":" + path, k -> FabricLoader.getInstance().getModContainer(AftrHrs.ID)
                .flatMap(c -> c.findPath("assets/" + namespace + "/" + path))
                .map(Files::exists)
                .orElse(false));
    }
}
