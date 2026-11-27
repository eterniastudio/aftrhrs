package net.axther.aftrhrs.feature.quests;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The quest types that come from plain game events: mining (natural blocks only: what anyone placed within
 * {@code settings.placed-block-minutes} doesn't count, and a spot counts once a day), Arcana hammers/excavators,
 * lumber axes and scythes (by their item ids from tools.yml), ripe crops (a spot counts once per
 * {@code settings.crop-spot-minutes}), fishing, hostile mobs (not from spawners), walking and riding a mount (sampled
 * every 5 seconds, teleports skipped). Also join/quit for the quest notices and saving.
 *
 * <p>Fabric listeners are registered once by {@link QuestsModule} and call in here while the module runs.
 */
final class QuestEvents {
    /**
     * A block position (blocks are compared by spot, not by object): the level's shared dimension key and the packed
     * position, no string per entry (up to 130k of them).
     */
    private record Spot(ResourceKey<Level> world, long pos) {
        static Spot of(Level level, BlockPos pos) {
            return new Spot(level.dimension(), pos.asLong());
        }
    }

    private static final Set<Block> CROPS = Set.of(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
            Blocks.NETHER_WART, Blocks.COCOA, Blocks.TORCHFLOWER_CROP, Blocks.PITCHER_CROP);
    /** Grown blocks that count as a harvest when nobody placed them (melons and pumpkins grow from a stem). */
    private static final Set<Block> GROWN = Set.of(Blocks.MELON, Blocks.PUMPKIN);
    private static final int MAX_PLACED = 100_000;
    private static final int MAX_MINED = 30_000;
    static final String PET = "feather:pet";
    /** Set on mobs a monster spawner or trial spawner added (Paper's spawn reason). */
    static final String SPAWN_REASON = "feather:spawn_reason";

    private final QuestsModule quests;
    /** Placed spots, oldest first: the oldest drops off once it has expired or the map is full. */
    private final Map<Spot, Long> placed = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Spot, Long> eldest) {
            return size() > MAX_PLACED || System.currentTimeMillis() - eldest.getValue() >= placedMs;
        }
    };
    /** Per player: spots that counted today (mining) or recently (crops), with when. */
    private final Map<UUID, Map<Spot, Long>> mined = new HashMap<>();
    private final Map<UUID, Loc> lastPos = new HashMap<>();
    private final Map<UUID, double[]> carry = new HashMap<>();
    private Tasks.Task sampler;
    /** The quest day {@link #mined} was last tidied for. */
    private long minedDay = Long.MIN_VALUE;

    private long placedMs = 6 * 3_600_000L;
    private long cropSpotMs = 20 * 60_000L;
    private double minHardness = 0.3;
    private boolean spawnerMobs;
    double bossMinShare = 0.03;
    private final Set<String> hammers = new HashSet<>(), axes = new HashSet<>(), scythes = new HashSet<>();

    QuestEvents(QuestsModule quests) {
        this.quests = quests;
    }

    void reload(Yaml cfg) {
        placedMs = Math.max(0, cfg.getLong("settings.placed-block-minutes", 360)) * 60_000L;
        cropSpotMs = Math.max(0, cfg.getLong("settings.crop-spot-minutes", 20)) * 60_000L;
        minHardness = Math.max(0, cfg.getDouble("settings.mine-min-hardness", 0.3));
        spawnerMobs = cfg.getBoolean("settings.count-spawner-mobs", false);
        bossMinShare = Math.max(0, Math.min(1, cfg.getDouble("settings.boss-min-share", 0.03)));
        Yaml tools = quests.plugin().loadResourceConfig("tools.yml");
        hammers.clear();
        axes.clear();
        scythes.clear();
        for (String s : tools.getStringList("area-mining.tools")) hammers.add(s.trim().toLowerCase(Locale.ROOT));
        for (String s : tools.getStringList("tree-felling.tools")) axes.add(s.trim().toLowerCase(Locale.ROOT));
        for (String s : tools.getStringList("harvest.tools")) scythes.add(s.trim().toLowerCase(Locale.ROOT));
    }

    void start() {
        sampler = Tasks.timer(100L, 100L, this::sample);
        for (ServerPlayer p : quests.plugin().onlinePlayers()) lastPos.put(p.getUUID(), Loc.of(p));
    }

    void stop() {
        if (sampler != null) sampler.cancel();
        sampler = null;
    }

    /** A new day: the spots a player mined count again. */
    void forgetDay(UUID player) {
        mined.remove(player);
    }

    // ------------------------------------------------------------------ blocks

    /** A player placed a block, or one formed by itself (fluids, falling blocks, pistons): not natural for a while. */
    void mark(Level level, BlockPos pos) {
        if (placedMs <= 0) return;
        Spot s = Spot.of(level, pos);
        placed.remove(s);   // re-insert so the newest placements are the last to be dropped
        placed.put(s, System.currentTimeMillis());
    }

    private boolean placedRecently(Spot s, long now) {
        Long t = placed.get(s);
        return t != null && now - t < placedMs;
    }

    /** True the first time this player gets credit for this spot (within {@code window} ms; 0 = until the day ends). */
    private boolean fresh(UUID player, Spot s, long now, long window) {
        Map<Spot, Long> mine = mined.computeIfAbsent(player, k -> new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Spot, Long> eldest) {
                return size() > MAX_MINED;
            }
        });
        Long t = mine.get(s);
        if (t != null && (window <= 0 || now - t < window)) return false;
        mine.remove(s);
        mine.put(s, now);
        return true;
    }

    void broke(ServerPlayer p, Level level, BlockPos pos, BlockState state) {
        if (!quests.counts(p)) return;
        Block type = state.getBlock();
        long now = System.currentTimeMillis();
        Spot spot = Spot.of(level, pos);
        String tool = itemId(p.getMainHandItem());
        String id = BuiltInRegistries.BLOCK.getKey(type).getPath();
        if (CROPS.contains(type) || GROWN.contains(type)) {
            // Planting a crop is a placement too, so only melons and pumpkins (which can be placed full-grown) check it;
            // replanted crops count again once ripe, at most once per crop-spot-minutes per spot.
            if (!ripe(state) || (GROWN.contains(type) && placedRecently(spot, now))) return;
            if (!fresh(p.getUUID(), spot, now, cropSpotMs)) return;
            quests.progress(p, "harvest", id, 1);
            if (tool != null && scythes.contains(tool)) quests.progress(p, "scythe", id, 1);
            return;
        }
        if (type.defaultDestroyTime() < minHardness || placedRecently(spot, now)) return;
        if (!fresh(p.getUUID(), spot, now, 0)) return;
        quests.progress(p, "mine", id, 1);
        if (tool == null) return;
        if (hammers.contains(tool)) quests.progress(p, "hammer", id, 1);
        if (axes.contains(tool) && state.is(BlockTags.LOGS)) quests.progress(p, "fell", id, 1);
    }

    private static boolean ripe(BlockState state) {
        if (GROWN.contains(state.getBlock())) return true;
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof IntegerProperty age && prop.getName().equals("age")) {
                int max = age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                return state.getValue(age) >= max;
            }
        }
        return false;
    }

    /**
     * The id a quest or tools.yml knows an item by: the item's path for the mod's own items (the old Oraxen id, e.g.
     * {@code arcana_hammer}), else null for vanilla items (only custom tools count).
     */
    static String itemId(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        Identifier key = BuiltInRegistries.ITEM.getKey(item.getItem());
        if (key == null || key.getNamespace().equals("minecraft")) return null;
        return key.getPath().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ mobs and fish

    void spawnerSpawned(Entity e, boolean trial) {
        Pdc.putString(e, SPAWN_REASON, trial ? "TRIAL_SPAWNER" : "SPAWNER");
    }

    void died(LivingEntity dead) {
        if (dead instanceof Player || !(dead instanceof Enemy)) return;
        if (!(dead.getLastHurtByPlayer() instanceof ServerPlayer killer)) return;
        if (Pdc.has(dead, PET)) return;
        if (!spawnerMobs && Pdc.has(dead, SPAWN_REASON)) return;
        quests.progress(killer, "kill", BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType()).getPath(), 1);
    }

    void fished(ServerPlayer p, Iterable<ItemStack> loot) {
        for (ItemStack s : loot) {
            if (s == null || s.isEmpty()) continue;
            quests.progress(p, "fish", fishId(s), 1);
        }
    }

    /** What a catch counts as: a custom fish's id (arcana_fish_moonkoi, also for stand-ins), else the item id. */
    static String fishId(ItemStack s) {
        String sp = Pdc.itemString(s, "feather:fish_species");
        if (sp != null) return "arcana_fish_" + sp;
        String fb = Pdc.itemString(s, "feather:fish_item");
        if (fb != null) return fb;
        String own = itemId(s);
        if (own != null) return own;
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    // ------------------------------------------------------------------ walking and riding

    private void sample() {
        // A new day: players who left keep no mined spots (online ones are cleared when their quests roll over).
        long today = quests.today();
        if (today != minedDay) {
            minedDay = today;
            mined.keySet().removeIf(id -> quests.plugin().player(id) == null);
        }
        for (ServerPlayer p : quests.plugin().onlinePlayers()) {
            UUID id = p.getUUID();
            Loc now = Loc.of(p);
            Loc before = lastPos.put(id, now);
            if (before == null || before.level() != now.level() || p.isSpectator()) continue;
            if (!quests.counts(p)) continue;
            String type;
            double d;
            if (p.isPassenger()) {
                // Mounts only (horses, pets, striders ...): no boats or minecarts on a loop.
                if (!(p.getVehicle() instanceof LivingEntity)) continue;
                type = "ride";
                d = Math.sqrt(before.distanceSquared(now));
                if (d > 30.0 * 5) continue;
            } else {
                if (p.getAbilities().flying || p.isFallFlying()) continue;
                type = "walk";
                double dx = now.x() - before.x(), dz = now.z() - before.z();
                d = Math.sqrt(dx * dx + dz * dz);
                if (d > 12.0 * 5) continue;   // faster than this is a move we didn't see, not walking
            }
            if (d < 0.05) continue;
            double[] c = carry.computeIfAbsent(id, k -> new double[2]);
            int i = type.equals("walk") ? 0 : 1;
            c[i] += d;
            int whole = (int) c[i];
            if (whole <= 0) continue;
            c[i] -= whole;
            quests.progress(p, type, "", whole);
        }
    }

    void teleported(ServerPlayer p, Loc to) {
        if (to != null) lastPos.put(p.getUUID(), to);
    }

    // ------------------------------------------------------------------ join / quit

    /** The join notices go out with the join digest (QuestsModule.collectJoin). */
    void joined(ServerPlayer p) {
        lastPos.put(p.getUUID(), Loc.of(p));
    }

    void quit(ServerPlayer p) {
        UUID id = p.getUUID();
        lastPos.remove(id);
        carry.remove(id);
        // Not mined: rejoining mustn't make today's spots count again (forgetDay clears it at the reset).
        quests.quit(p);
    }
}
