package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.FurnitureBlock;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureSpec;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.coast.BlockIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hooks for placed Arcana furniture, all configured in tools.yml:
 * <ul>
 *   <li>{@code furniture-swap}: right-clicking a piece with a matching item turns it into another piece in the same
 *       spot and facing ({@link ArcanaContent#replace}: copper waxing / scraping). Rules naming hearths are skipped:
 *       lighting and dousing hearths is the hearth block's own {@code lit} state now.</li>
 *   <li>{@code furniture-aging}: loaded pieces slowly turn into their "next" id (copper weathering), on average once
 *       per {@code mean-minutes-per-stage}. The agable origins are kept in a {@link BlockIndex} (chunk loads, the
 *       placed/removed hooks, swaps); pieces placed without {@code setPlacedBy} (/setblock, debug placeState, the
 *       migration) start aging after their chunk loads again, or after a config reload that changes which pieces age
 *       while their chunk ticks.</li>
 *   <li>{@code planter-harvest}: right-clicking a fully grown planter drops its crop and sets it back to the first
 *       growth stage.</li>
 * </ul>
 */
final class FurnitureHooks {
    private final ToolsModule plugin;
    private final Set<String> warned = new HashSet<>();

    private boolean swapEnabled;
    private final List<Rule> rules = new ArrayList<>();
    private final Set<String> swapIds = new HashSet<>();
    private boolean agingEnabled;
    private final Map<String, String> agingPairs = new LinkedHashMap<>();
    private final Set<Block> agingBlocks = new HashSet<>();
    private double agingChance;
    private Tasks.Task agingTask;
    private boolean harvestEnabled;
    private String harvestSound;
    private final Map<String, List<CropDrop>> harvestDrops = new LinkedHashMap<>();

    /** Origins of loaded agable pieces (the predicate reads the live {@link #agingBlocks}). */
    private final BlockIndex agingIndex = new BlockIndex(b -> agingBlocks.contains(b));
    /** What {@link #agingIndex} was last built for (the agable blocks, or FALSE while aging is off); null: rebuild. */
    private Object agingKey;
    /** How many times the loaded chunks were rescanned for aging (tests). */
    private int agingRescans;

    FurnitureHooks(ToolsModule plugin) {
        this.plugin = plugin;
    }

    private record CropDrop(Item item, int min, int max, double chance) {
    }

    private static final class Rule {
        String name;
        final Set<Item> items = new HashSet<>();
        final List<TagKey<Item>> tags = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        String use = "none";
        int amount = 1;
        Item returnItem;
        String sound;
        float volume = 1f, pitch = 1f;
        SimpleParticleType particle;
        int particleCount = 12;
        final Map<String, String> pairs = new LinkedHashMap<>();

        boolean matches(ItemStack held) {
            if (held == null || held.isEmpty()) return false;
            String id = ArcanaContent.id(held);
            if (id != null && ids.contains(id)) return true;
            if (items.contains(held.getItem())) return true;
            for (TagKey<Item> t : tags) if (held.is(t)) return true;
            return false;
        }
    }

    void load(Yaml c) {
        rules.clear();
        swapIds.clear();
        swapEnabled = c.getBoolean("furniture-swap.enabled", true);
        for (Map<?, ?> raw : c.getMapList("furniture-swap.rules")) {
            Rule r = new Rule();
            r.name = String.valueOf(raw.containsKey("name") ? raw.get("name") : "rule" + rules.size());
            Object items = raw.get("items");
            if (items instanceof List<?> list) {
                for (Object o : list) addItemMatcher(r, String.valueOf(o));
            } else if (items != null) {
                addItemMatcher(r, String.valueOf(items));
            }
            if (raw.get("use") != null) r.use = String.valueOf(raw.get("use")).toLowerCase(Locale.ROOT);
            if (raw.get("amount") instanceof Number n) r.amount = Math.max(1, n.intValue());
            if (raw.get("return") != null) r.returnItem = item(String.valueOf(raw.get("return")));
            r.sound = raw.get("sound") == null ? null : String.valueOf(raw.get("sound"));
            if (raw.get("volume") instanceof Number n) r.volume = n.floatValue();
            if (raw.get("pitch") instanceof Number n) r.pitch = n.floatValue();
            r.particle = particle(raw.get("particle"), r.name);
            if (raw.get("particle-count") instanceof Number n) r.particleCount = Math.max(0, n.intValue());
            Map<String, String> pairs = new LinkedHashMap<>();
            expandPairs(raw.get("pairs"), raw.get("vars"), pairs);
            for (Map.Entry<String, String> p : pairs.entrySet()) {
                if (p.getKey().contains("_hearth") || p.getValue().contains("_hearth")) continue; // hearths: lit state
                r.pairs.put(p.getKey(), p.getValue());
            }
            if (r.pairs.isEmpty()) {
                if (pairs.isEmpty()) plugin.log().warn("furniture-swap rule '{}' has no pairs; ignored.", r.name);
                continue;
            }
            if (r.items.isEmpty() && r.tags.isEmpty() && r.ids.isEmpty()) {
                plugin.log().warn("furniture-swap rule '{}' has no items; ignored.", r.name);
                continue;
            }
            rules.add(r);
            swapIds.addAll(r.pairs.keySet());
        }

        agingPairs.clear();
        agingBlocks.clear();
        agingEnabled = c.getBoolean("furniture-aging.enabled", true);
        expandPairs(c.getList("furniture-aging.pairs"), c.get("furniture-aging.vars"), agingPairs);
        for (String from : agingPairs.keySet()) {
            Block b = ArcanaContent.block(from);
            if (b != null) agingBlocks.add(b);
        }
        int interval = Math.max(5, c.getInt("furniture-aging.check-interval-seconds", 60));
        double mean = Math.max(1.0, c.getDouble("furniture-aging.mean-minutes-per-stage", 30.0));
        agingChance = Math.min(1.0, interval / (mean * 60.0));
        cancelAging();   // keeps the index: rescanAging revalidates it
        if (agingEnabled && !agingPairs.isEmpty()) agingTask = Tasks.timer(interval * 20L, interval * 20L, this::ageTick);
        // The index only depends on which blocks age (and whether aging is on): a reload that leaves those as they were
        // keeps it (chunk loads and the placed/removed hooks keep it current), instead of rescanning every loaded chunk.
        Object key = agingActive() ? Set.copyOf(agingBlocks) : Boolean.FALSE;
        if (!key.equals(agingKey)) {
            rescanAging();
            agingKey = Feather.serverOrNull() == null ? null : key;   // no server yet: nothing was scanned
        }

        harvestDrops.clear();
        harvestEnabled = c.getBoolean("planter-harvest.enabled", true);
        harvestSound = c.getString("planter-harvest.sound", "block.crop.break");
        Yaml planters = c.getConfigurationSection("planter-harvest.planters");
        if (planters != null) {
            for (String id : planters.getKeys(false)) {
                List<CropDrop> drops = new ArrayList<>();
                for (String spec : planters.getStringList(id)) {
                    CropDrop d = parseDrop(spec);
                    if (d == null) plugin.log().warn("planter-harvest.{}: bad drop '{}'", id, spec);
                    else drops.add(d);
                }
                harvestDrops.put(id.toLowerCase(Locale.ROOT), drops);
            }
        }
    }

    void stop() {
        cancelAging();
        agingIndex.clear();
        agingKey = null;
    }

    private void cancelAging() {
        if (agingTask != null) {
            agingTask.cancel();
            agingTask = null;
        }
    }

    Set<String> configuredIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Rule r : rules) {
            ids.addAll(r.pairs.keySet());
            ids.addAll(r.pairs.values());
            ids.addAll(r.ids);
        }
        ids.addAll(agingPairs.keySet());
        ids.addAll(agingPairs.values());
        ids.addAll(harvestDrops.keySet());
        return ids;
    }

    boolean handles(String id) {
        return (swapEnabled && swapIds.contains(id)) || (harvestEnabled && harvestDrops.containsKey(id));
    }

    /** Whether pieces with this id age (the aging index's placed/removed hook). */
    boolean ages(String id) {
        return agingEnabled && agingPairs.containsKey(id);
    }

    static Item item(String s) {
        String k = s.trim().toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
        Item i = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return i == Items.AIR ? null : i;
    }

    private void addItemMatcher(Rule r, String spec) {
        String s = spec.trim();
        if (s.isEmpty()) return;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("oraxen:")) {
            r.ids.add(low.substring(7));
        } else if (s.startsWith("#")) {
            String t = low.substring(1);
            Identifier id = Identifier.tryParse(t.contains(":") ? t : "minecraft:" + t);
            if (id == null) plugin.log().warn("furniture-swap rule '{}': unknown item tag {}", r.name, s);
            else r.tags.add(TagKey.create(Registries.ITEM, id));
        } else {
            Item i = item(s);
            if (i == null) plugin.log().warn("furniture-swap rule '{}': unknown item {}", r.name, s);
            else r.items.add(i);
        }
    }

    private SimpleParticleType particle(Object o, String rule) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        String k = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
        ParticleType<?> t = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null);
        if (t instanceof SimpleParticleType s) return s;
        plugin.log().warn("furniture-swap rule '{}': particle {} is unknown or needs extra data; ignored.", rule, o);
        return null;
    }

    /** "from -> to" templates; every &lt;var&gt; is expanded over the values listed for it under vars. */
    private void expandPairs(Object pairs, Object vars, Map<String, String> out) {
        if (!(pairs instanceof List<?> list)) return;
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (vars instanceof Yaml y) vars = y.getValues(false);
        if (vars instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                List<String> vs = new ArrayList<>();
                if (e.getValue() instanceof List<?> l) for (Object o : l) vs.add(String.valueOf(o));
                else if (e.getValue() != null) vs.add(String.valueOf(e.getValue()));
                values.put(String.valueOf(e.getKey()), vs);
            }
        }
        for (Object o : list) {
            String[] parts = String.valueOf(o).split("->");
            if (parts.length != 2) {
                plugin.log().warn("Bad furniture pair (expected 'from -> to'): {}", o);
                continue;
            }
            List<String[]> expanded = new ArrayList<>();
            expanded.add(new String[]{parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim().toLowerCase(Locale.ROOT)});
            for (Map.Entry<String, List<String>> v : values.entrySet()) {
                String token = "<" + v.getKey() + ">";
                List<String[]> next = new ArrayList<>();
                for (String[] p : expanded) {
                    if (!p[0].contains(token) && !p[1].contains(token)) {
                        next.add(p);
                        continue;
                    }
                    for (String val : v.getValue()) {
                        String lv = val.trim().toLowerCase(Locale.ROOT);
                        next.add(new String[]{p[0].replace(token, lv), p[1].replace(token, lv)});
                    }
                }
                expanded = next;
            }
            for (String[] p : expanded) {
                if (p[0].contains("<") || p[1].contains("<")) {
                    plugin.log().warn("Furniture pair has an unknown <var>: {} -> {}", p[0], p[1]);
                    continue;
                }
                out.putIfAbsent(p[0], p[1]);
            }
        }
    }

    private static CropDrop parseDrop(String spec) {
        String[] p = spec.trim().split("\\s+");
        if (p.length == 0) return null;
        Item m = item(p[0]);
        if (m == null) return null;
        int min = 1, max = 1;
        double chance = 1.0;
        try {
            if (p.length > 1) {
                String[] r = p[1].split("\\.\\.");
                min = Integer.parseInt(r[0]);
                max = r.length > 1 ? Integer.parseInt(r[1]) : min;
            }
            if (p.length > 2) chance = Double.parseDouble(p[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new CropDrop(m, Math.max(0, Math.min(min, max)), Math.max(min, max), chance);
    }

    // ------------------------------------------------------------------ clicks

    InteractionResult onUse(FurnitureContext ctx) {
        ServerPlayer player = ctx.player();
        if (player == null) return InteractionResult.PASS;
        if (ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        String id = ctx.id();
        ItemStack held = player.getMainHandItem();
        if (swapEnabled) {
            for (Rule r : rules) {
                String to = r.pairs.get(id);
                if (to == null || !r.matches(held)) continue;
                if (!allowed(player, ctx.level(), ctx.origin())) return InteractionResult.FAIL;
                FurnitureSpec spec = ArcanaContent.spec(id);
                if (spec != null && spec.storage != null && spec.storage.type().ownInventory()) {
                    warnOnce("storage:" + id, "Not swapping '" + id + "': it stores items.");
                    return InteractionResult.PASS;
                }
                if (ArcanaContent.stateFor(to) == null) {
                    warnOnce("unknown:" + to, "Furniture swap target '" + to + "' is not native furniture.");
                    return InteractionResult.PASS;
                }
                if (!ArcanaContent.replace(ctx.level(), ctx.clicked(), to)) return InteractionResult.FAIL;
                // scraping wax off gives an agable piece again, waxing takes one out (replace never fires onPlaced)
                if (agingActive() && agingPairs.containsKey(to)) agingIndex.add(ctx.level(), ctx.origin());
                else agingIndex.remove(ctx.level(), ctx.origin());
                useHeldItem(player, held, r);
                player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
                effects(ctx.level(), ctx.origin(), r.sound, r.volume, r.pitch, r.particle, r.particleCount);
                return InteractionResult.SUCCESS;
            }
        }
        if (harvestEnabled && harvestDrops.containsKey(id) && ctx.state().getBlock() instanceof FurnitureBlock fb && fb.age != null) {
            if (held.is(Items.BONE_MEAL)) return InteractionResult.PASS; // the planter's own bone meal growth
            int max = fb.spec.stages.count() - 1;
            if (ctx.state().getValue(fb.age) < max) return InteractionResult.PASS;
            if (!allowed(player, ctx.level(), ctx.origin())) return InteractionResult.FAIL;
            harvest(player, ctx, fb, id);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private boolean allowed(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!ToolsModule.mayEditWorld(player)) return false;
        if (ToolsModule.inSpawnProtection(player, level, pos)) return false;
        return plugin.claimAllows(player, level, pos, ClaimAction.BUILD);
    }

    private void useHeldItem(ServerPlayer player, ItemStack held, Rule r) {
        if (ToolsModule.isCreative(player)) return;
        switch (r.use) {
            case "damage" -> held.hurtAndBreak(r.amount, player, EquipmentSlot.MAINHAND);
            case "consume" -> {
                ItemStack back = r.returnItem == null ? ItemStack.EMPTY : new ItemStack(r.returnItem);
                if (held.getCount() <= r.amount) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, back);
                } else {
                    held.shrink(r.amount);
                    if (!back.isEmpty() && !player.getInventory().add(back)) player.drop(back, false, net.minecraft.util.Prediction.SERVER_ONLY);
                }
            }
            default -> {
            }
        }
    }

    private static void effects(ServerLevel level, BlockPos pos, String sound, float volume, float pitch, SimpleParticleType particle, int count) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        ToolsModule.playSound(level, x, y, z, sound, SoundSource.BLOCKS, volume, pitch);
        if (particle != null && count > 0) level.sendParticles(particle, x, y, z, count, 0.35, 0.35, 0.35, 0.02);
    }

    private void harvest(ServerPlayer player, FurnitureContext ctx, FurnitureBlock fb, String id) {
        ServerLevel level = ctx.level();
        BlockPos at = ctx.origin();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (CropDrop d : harvestDrops.get(id)) {
            if (rnd.nextDouble() >= d.chance()) continue;
            int n = d.min() + (d.max() > d.min() ? rnd.nextInt(d.max() - d.min() + 1) : 0);
            if (n > 0) Block.popResource(level, at.above(), new ItemStack(d.item(), n));
        }
        int first = Math.max(0, Math.min(fb.spec.stages.initial(), fb.spec.stages.count() - 1));
        level.setBlock(at, ctx.state().setValue(fb.age, first), 3);
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        effects(level, at, harvestSound, 1f, 1f, null, 0);
    }

    // ------------------------------------------------------------------ aging

    private boolean agingActive() {
        return agingEnabled && !agingBlocks.isEmpty();
    }

    void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        if (agingActive()) agingIndex.chunkLoaded(level, chunk);
    }

    void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        agingIndex.chunkUnloaded(level, chunk);
    }

    void agingPlaced(ServerLevel level, BlockPos origin) {
        if (agingActive()) agingIndex.add(level, origin);
    }

    void agingRemoved(ServerLevel level, BlockPos origin) {
        agingIndex.remove(level, origin);
    }

    void clearAging() {
        agingIndex.clear();
        agingKey = null;
    }

    /**
     * Re-decides the indexed pieces and indexes the ready-to-send chunks again (the agable set may have changed). The
     * index is revalidated, not cleared: the rescan reaches only block-ticking chunks, and a loaded border chunk fires
     * no load event when it starts ticking, so its pieces would otherwise stop aging until the chunk reloads.
     */
    private void rescanAging() {
        agingRescans++;
        var server = Feather.serverOrNull();
        if (!agingActive() || server == null) {
            agingIndex.clear();
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            agingIndex.retainValid(level);
            List<LevelChunk> chunks = new ArrayList<>();
            level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunks::add);
            for (LevelChunk c : chunks) agingIndex.chunkLoaded(level, c);
        }
    }

    /**
     * One aging round: each indexed origin in a ticking chunk ages with {@link #agingChance}. The chance is rolled
     * before the block is read, so a round costs O(indexed pieces) and only the due ones read and swap blocks. Entries
     * gone stale (a /setblock, a piece broken without its hook) are revalidated here and pruned.
     */
    void ageTick() {
        if (!agingActive()) return;
        var server = Feather.serverOrNull();
        if (server == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) continue;
            for (BlockPos pos : agingIndex.all(level)) {            // a copy: the index may change below
                if (!level.shouldTickBlocksAt(ChunkPos.pack(pos))) continue;
                if (rnd.nextDouble() >= agingChance) continue;
                try {
                    BlockState st = level.getBlockState(pos);
                    String from = ArcanaContent.id(st);
                    String to = from == null ? null : agingPairs.get(from);
                    if (to == null || !ArcanaContent.origin(st, pos).equals(pos)) {
                        agingIndex.remove(level, pos);           // stale: /setblock, waxed, broken without its hook
                        continue;
                    }
                    // replace fires onRemoved for the old piece (dropping the entry), never onPlaced
                    if (ArcanaContent.replace(level, pos, to) && agingPairs.containsKey(to)) agingIndex.add(level, pos);
                    else if (!agingPairs.containsKey(to)) agingIndex.remove(level, pos);   // fully aged: stop visiting
                } catch (RuntimeException ex) {
                    warnOnce("aging", "Furniture aging failed: " + ex);
                }
            }
        }
    }

    /** Whether an origin is in the aging index (tests). */
    boolean agingIndexed(ServerLevel level, BlockPos origin) {
        return agingIndex.contains(level, origin);
    }

    /** Sets the per-round aging chance (tests; the next config load sets it back). */
    void agingChance(double chance) {
        agingChance = chance;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.log().warn(message);
    }
}
