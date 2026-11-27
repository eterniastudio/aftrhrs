package net.axther.aftrhrs.migrate;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Pdc;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Carrying a Paper world over. Paper kept plugin data in {@code BukkitValues} (entities), {@code PublicBukkitValues}
 * (block entities, items) and {@code ChunkBukkitValues} (chunks). Fabric would silently drop them, so mixins
 * ({@code mixin/migrate}) copy them into the {@link Pdc#LEGACY} attachment as objects load, and:
 * <ul>
 *   <li>MythicMobs mobs ({@code mythicmobs:type = ArcanaX}) load as the matching native entity type, same UUID;</li>
 *   <li>Oraxen items ({@code PublicBukkitValues["oraxen:id"]}) load as the native item with that id ({@link LegacyItems});</li>
 *   <li>every loaded entity still carrying a legacy tag is offered to the registered {@link EntityConverter}s once
 *       (pets adopt their records, Oraxen furniture displays become blocks, Feather holograms re-register ...).</li>
 * </ul>
 */
public final class Legacy {
    /** Converts or cleans up one legacy entity. Return true if it handled the entity (stop asking others). */
    public interface EntityConverter {
        boolean convert(ServerLevel level, Entity entity, CompoundTag bukkitValues);
    }

    /** An old Oraxen furniture entity became a block (fired by OraxenFurniture after placing it). */
    @FunctionalInterface
    public interface FurnitureListener {
        void converted(ServerLevel level, BlockPos origin, String oraxenId, CompoundTag bukkitValues, float yaw);
    }

    private static final Map<String, EntityType<?>> MYTHIC = new HashMap<>();
    private static final List<EntityConverter> CONVERTERS = new ArrayList<>();
    /** Who registered each converter (its class), for the migration summary. */
    private static final List<String> CONVERTER_NAMES = new ArrayList<>();
    /**
     * What the migration did so far, by fixed category ("entity via PetMigration", "item from Oraxen"...), each with a
     * few sample ids. Ids come from world data (a creative player can craft any custom_data), so categories are fixed
     * strings, and the number of categories, samples and their length are capped.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> COUNTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> SAMPLES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CATEGORIES = 256, MAX_SAMPLES = 20, MAX_SAMPLE_CHARS = 64;
    private static long countedAtLastLog;
    private static final List<FurnitureListener> FURNITURE = new ArrayList<>();
    private static volatile boolean ready;

    private Legacy() {
    }

    public static void init() {
        ServerEntityEvents.ENTITY_LOAD.register(Legacy::onLoad);
        // what got converted, every 30 s while something happens and once at stop: the log shows every converter path
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTickCount() % 600 == 0) logSummary(false);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> logSummary(true));
        OraxenFurniture.init();
        OraxenStorage.init();
        ready = true;
    }

    /**
     * Hears about every Oraxen furniture piece the migration turned into a block: {@code oraxenId} without namespace
     * ({@code arcana_crab_pot}), {@code bukkitValues} = the old base ItemDisplay's full BukkitValues (read your
     * {@code feather:*} state from it), {@code yaw} = the entity's yaw. Runs on the server thread right after the
     * piece (all parts) was placed, once per piece.
     */
    public static void onFurniture(FurnitureListener l) {
        FURNITURE.add(l);
    }

    /** Called by the furniture converter after it placed the block for an old furniture entity. */
    public static void furnitureConverted(ServerLevel level, BlockPos origin, String oraxenId, CompoundTag bukkitValues, float yaw) {
        for (FurnitureListener l : FURNITURE) {
            try {
                l.converted(level, origin, oraxenId, bukkitValues, yaw);
            } catch (Throwable t) {
                AftrHrs.LOG.warn("Legacy furniture listener failed on {} at {}", oraxenId, origin, t);
            }
        }
    }

    public static boolean ready() {
        return ready;
    }

    /** Maps a MythicMobs internal name (e.g. {@code ArcanaBlackCat}) to a native type; overrides the naming convention. */
    public static void mapMythic(String mythicId, EntityType<?> type) {
        MYTHIC.put(mythicId, type);
    }

    public static void converter(EntityConverter c) {
        Class<?> by = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        CONVERTERS.add(c);
        CONVERTER_NAMES.add(by == null ? "?" : by.getSimpleName());
    }

    /** Counts one migration step for the summary line (any thread). {@code category} must be a fixed string. */
    public static void count(String category) {
        count(category, null);
    }

    /** {@link #count(String)}, keeping an id from the data as one of the category's few samples. */
    public static void count(String category, @org.jspecify.annotations.Nullable String sample) {
        var n = COUNTS.get(category);
        if (n == null) {
            if (COUNTS.size() >= MAX_CATEGORIES) return;
            n = COUNTS.computeIfAbsent(category, k -> new java.util.concurrent.atomic.LongAdder());
        }
        n.increment();
        if (sample == null) return;
        var set = SAMPLES.computeIfAbsent(category, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (set.size() < MAX_SAMPLES) set.add(sample.length() > MAX_SAMPLE_CHARS ? sample.substring(0, MAX_SAMPLE_CHARS) + "..." : sample);
    }

    /** The sample ids kept for a category (tests). */
    public static java.util.Set<String> samples(String category) {
        return java.util.Set.copyOf(SAMPLES.getOrDefault(category, java.util.Set.of()));
    }

    /** A snapshot of the counts (tests). */
    public static Map<String, Long> counts() {
        Map<String, Long> out = new java.util.TreeMap<>();
        COUNTS.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    private static synchronized void logSummary(boolean stopping) {
        long total = 0;
        for (var v : COUNTS.values()) total += v.sum();
        if (total == countedAtLastLog) return;
        countedAtLastLog = total;
        StringBuilder sb = new StringBuilder();
        counts().forEach((k, v) -> {
            sb.append("\n  ").append(k).append(": ").append(v);
            var ids = SAMPLES.get(k);
            if (ids != null && !ids.isEmpty()) sb.append(" (e.g. ").append(String.join(", ", new java.util.TreeSet<>(ids))).append(')');
        });
        AftrHrs.LOG.info("Migration{}: {} Paper objects converted so far:{}", stopping ? " (server stopping)" : "", total, sb);
    }

    /**
     * The native type for a MythicMobs id: an explicit {@link #mapMythic} entry, else the convention
     * {@code ArcanaBlackCat → aftrhrs:black_cat}, {@code ArcanaTufthornYoung → aftrhrs:tufthorn_young}.
     */
    public static Optional<EntityType<?>> mythicType(String mythicId) {
        if (mythicId == null || mythicId.isEmpty()) return Optional.empty();
        EntityType<?> t = MYTHIC.get(mythicId);
        if (t != null) return Optional.of(t);
        t = net.axther.aftrhrs.entity.ArcanaEntities.byMythicId(mythicId);
        if (t != null) return Optional.of(t);
        String base = mythicId.startsWith("Arcana") ? mythicId.substring(6) : mythicId;
        String snake = base.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        Identifier id = AftrHrs.id(snake);
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(x -> (EntityType<?>) x);
    }

    private static void onLoad(Entity entity, ServerLevel level) {
        CompoundTag legacy = entity.getAttached(Pdc.LEGACY);
        if (legacy == null) return;
        entity.removeAttached(Pdc.LEGACY);
        // Plain Feather/other keys keep working through Pdc under their old names.
        CompoundTag data = Pdc.of(entity).copy();
        for (String k : legacy.keySet()) if (!data.contains(k)) data.put(k, legacy.get(k).copy());
        Pdc.changed(entity, data);
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        for (int i = 0; i < CONVERTERS.size(); i++) {
            try {
                if (CONVERTERS.get(i).convert(level, entity, legacy)) {
                    count("entity via " + CONVERTER_NAMES.get(i), type);
                    return;
                }
            } catch (Throwable t) {
                count("entity converter FAILED: " + CONVERTER_NAMES.get(i), type);
                AftrHrs.LOG.warn("Legacy converter failed on {} {}", entity.getType(), entity.getUUID(), t);
            }
        }
        count("entity with Paper tags, no converter (tags kept in Pdc)", type);
    }
}
