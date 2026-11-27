package net.axther.aftrhrs.feature.wildlife;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ability.Fx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ByteTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Items used in the hand (wildlife SPEC section 5): soggy map scraps (4 -> a buried-treasure explorer map), the
 * Prospector's Pan in river water, bottling Duskflies, putting a Lanternsnail back from its bucket, and the buffs of
 * the five wildlife meals.
 */
final class Finds {
    record Loot(String item, double chance) {
    }

    record Effect(Holder<MobEffect> type, int seconds, int level) {
    }

    record Meal(List<Effect> effects, float saturation, int food) {
    }

    static final String BUCKET_SPECIES = "wild_bucket_species", BUCKET_KEPT = "wild_bucket_kept";

    private final WildlifeModule m;
    private final Map<UUID, Long> panAt = new HashMap<>(), mapAt = new HashMap<>();

    private String scrapId = "arcana_soggy_map_scrap";
    private int scrapsNeeded = 4, mapRadius = 50;
    private boolean unexplored = true;
    private String mapName = "";

    private String panId = "arcana_prospectors_pan";
    private long panCooldownMs = 2000;
    private Set<String> panBiomes = new HashSet<>();
    private final List<Loot> panLoot = new ArrayList<>();

    private String jarId = "arcana_firefly_jar";
    private double reach = 4.5, swarmRadius = 2;

    private String qualityKey = "meal_quality";
    private final Map<String, Double> quality = new HashMap<>();
    private final Map<String, Meal> meals = new LinkedHashMap<>();
    /** Meals the farming module's cooking owns (farming.yml cooking.meals): it buffs them, not this list. */
    private Set<String> farmingMeals = Set.of();

    Finds(WildlifeModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        scrapId = cfg.getString("map-scraps.item", scrapId);
        scrapsNeeded = Math.max(1, cfg.getInt("map-scraps.needed", 4));
        mapRadius = Math.max(1, Math.min(200, cfg.getInt("map-scraps.search-radius-chunks", 50)));
        unexplored = cfg.getBoolean("map-scraps.unexplored-only", true);
        mapName = cfg.getString("map-scraps.map-name", "");
        panId = cfg.getString("pan.item", panId);
        panCooldownMs = WildSpecies.seconds(cfg.getDouble("pan.cooldown-seconds", 2));
        panBiomes = new HashSet<>();
        for (String b : cfg.getStringList("pan.biomes")) panBiomes.add(b.trim().toLowerCase(Locale.ROOT));
        panLoot.clear();
        for (Map<?, ?> row : cfg.getMapList("pan.loot")) {
            Object item = row.get("item"), chance = row.get("chance");
            if (item == null || !(chance instanceof Number n)) continue;
            panLoot.add(new Loot(String.valueOf(item), Math.max(0, n.doubleValue())));
        }
        jarId = cfg.getString("duskflies.item", jarId);
        reach = Math.max(1, cfg.getDouble("duskflies.reach", 4.5));
        swarmRadius = Math.max(0.5, cfg.getDouble("duskflies.swarm-radius", 2));
        qualityKey = cfg.getString("meals.quality-key", "meal_quality");
        quality.clear();
        Yaml q = m.section("meals.quality");
        if (q != null) for (String k : q.getKeys(false)) quality.put(k.toLowerCase(Locale.ROOT), Math.max(0, q.getDouble(k, 1)));
        meals.clear();
        Yaml items = m.section("meals.items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                Yaml c = items.getConfigurationSection(id);
                if (c == null) continue;
                List<Effect> fx = new ArrayList<>();
                for (String spec : c.getStringList("effects")) {
                    Effect e = effect(spec);
                    if (e != null) fx.add(e);
                    else m.feather().getLogger().warn("wildlife.yml meals.items.{}: bad effect '{}' (use: <effect> <seconds> [level]).", id, spec);
                }
                meals.put(id, new Meal(fx, (float) Math.max(0, c.getDouble("saturation", 0)), Math.max(0, c.getInt("food", 0))));
            }
        }
        Set<String> owned = new HashSet<>();
        if (m.feather().getConfig().getBoolean("modules.farming", true)) {
            Yaml farming = net.axther.aftrhrs.core.Feather.bundled("farming.yml");
            java.nio.file.Path live = m.feather().getDataFolder().resolve("farming.yml");
            if (java.nio.file.Files.exists(live)) farming = Yaml.load(live);
            Yaml fm = farming.getConfigurationSection("cooking.meals");
            if (fm != null) {
                for (String id : fm.getKeys(false)) owned.add(fm.getString(id + ".item", "arcana_meal_" + id).toLowerCase(Locale.ROOT));
            }
        }
        farmingMeals = owned;
    }

    private static Effect effect(String spec) {
        if (spec == null) return null;
        String[] p = spec.trim().split("\\s+");
        if (p.length < 2) return null;
        Holder<MobEffect> t = Fx.effect(p[0]);
        if (t == null) return null;
        try {
            return new Effect(t, Integer.parseInt(p[1]), p.length > 2 ? Math.max(1, Integer.parseInt(p[2])) : 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ dispatch

    /** Blocks whose own right-click comes first unless sneaking (a chest, a door ...). */
    private static boolean interactable(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof BaseEntityBlock || state.getMenuProvider(level, pos) != null || ArcanaContent.isFurniture(state)
                || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof LeverBlock;
    }

    /** A right-click with something in the main hand (hit: the clicked block, null in the air). True when it was ours. */
    boolean onUse(ServerPlayer p, BlockHitResult hit) {
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) return false;
        String oid = ArcanaContent.id(held);
        ServerLevel level = (ServerLevel) p.level();
        boolean onBlock = hit != null;
        boolean blockFirst = onBlock && !p.isSecondaryUseActive() && interactable(level, hit.getBlockPos(), level.getBlockState(hit.getBlockPos()));
        if (oid != null) {
            if (oid.equals(scrapId)) return !blockFirst && maps(p, held);
            if (oid.equals(panId)) return !blockFirst && pan(p);
        }
        if (onBlock && !blockFirst && isSnailBucket(held)) return placeSnail(p, held, oid, hit);
        if (oid == null && held.is(Items.GLASS_BOTTLE)) {
            Entity swarm = aimedSwarm(p);
            return swarm != null && bottle(p, swarm, InteractionHand.MAIN_HAND);
        }
        return false;
    }

    // ------------------------------------------------------------------ map scraps

    private boolean maps(ServerPlayer p, ItemStack held) {
        if (held.getCount() < scrapsNeeded) {
            m.bar(p, "maps.need", WildlifeModule.tag("count", held.getCount()), WildlifeModule.tag("need", scrapsNeeded));
            return true;
        }
        ServerLevel w = (ServerLevel) p.level();
        if (w.dimension() != Level.OVERWORLD) {
            m.bar(p, "maps.wrong-world");
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = mapAt.get(p.getUUID());
        if (last != null && now - last < 3000) return true;
        mapAt.put(p.getUUID(), now);
        ItemStack map = null;
        try {
            BlockPos found = w.findNearestMapStructure(StructureTags.ON_TREASURE_MAPS, p.blockPosition(), mapRadius, unexplored);
            if (found != null) {
                map = new ItemStack(Items.FILLED_MAP);
                MapItem.applyNewSavedData(w, map, found.getX(), found.getZ(), (byte) 1, true, true);
                MapItem.renderBiomePreviewMap(w, map);
                MapItemSavedData.addTargetDecoration(map, found, "+", MapDecorationTypes.RED_X);
            }
        } catch (RuntimeException e) {
            map = null;
        }
        if (map == null || map.isEmpty()) {
            m.bar(p, "maps.none");
            m.sound(p, "error");
            return true;
        }
        if (mapName != null && !mapName.isBlank()) map.set(DataComponents.ITEM_NAME, Adv.vanilla(m.feather().text().item(mapName)));
        if (!WildlifeModule.creative(p)) held.shrink(scrapsNeeded);
        WildlifeModule.give(p, map);
        m.sound(p, "map");
        m.send(p, "maps.made");
        return true;
    }

    // ------------------------------------------------------------------ prospector's pan

    private boolean pan(ServerPlayer p) {
        long now = System.currentTimeMillis();
        Long last = panAt.get(p.getUUID());
        if (last != null && now - last < panCooldownMs) return true;
        ServerLevel level = (ServerLevel) p.level();
        BlockPos feet = p.blockPosition();
        boolean water = level.getFluidState(feet).is(FluidTags.WATER);
        String biome = level.getBiome(feet).unwrapKey().map(k -> k.identifier().getPath()).orElse("").toLowerCase(Locale.ROOT);
        if (!water || (!panBiomes.isEmpty() && !panBiomes.contains(biome))) {
            m.bar(p, "pan.not-river");
            return true;
        }
        if (!m.mayUse(p, level, feet, ClaimAction.BUILD)) return true;
        panAt.put(p.getUUID(), now);
        WildlifeModule.wear(p, InteractionHand.MAIN_HAND);
        double r = ThreadLocalRandom.current().nextDouble(), acc = 0;
        ItemStack found = null;
        for (Loot l : panLoot) {
            acc += l.chance();
            if (r < acc) {
                found = m.item(l.item(), 1);
                break;
            }
        }
        level.sendParticles(ParticleTypes.SPLASH, feet.getX() + 0.5, feet.getY() + 0.4, feet.getZ() + 0.5, 12, 0.3, 0.1, 0.3, 0);
        m.sound(p, "pan");
        if (found == null) {
            m.bar(p, "pan.nothing");
            return true;
        }
        String name = found.getHoverName().getString();
        WildlifeModule.give(p, found);
        m.bar(p, "pan.found", WildlifeModule.tag("item", name));
        m.feather().stat(p.getUUID(), "pan-finds", 1);
        return true;
    }

    // ------------------------------------------------------------------ duskflies

    /** The Duskflies swarm the player looks at within reach, or null. */
    private Entity aimedSwarm(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getLookAngle();
        Entity best = null;
        double bestT = Double.MAX_VALUE;
        for (Entity e : m.trackedEntities()) {
            if (!m.duskfliesId.equals(m.tracked.get(e.getUUID())) || e.level() != p.level()) continue;
            Vec3 to = e.position().add(0, Math.max(0.5, e.getBbHeight() / 2), 0).subtract(eye);
            double t = to.dot(dir);
            if (t < 0 || t > reach + swarmRadius) continue;
            double off = to.subtract(dir.scale(t)).length();
            if (off <= swarmRadius && t < bestT) {
                best = e;
                bestT = t;
            }
        }
        return best;
    }

    boolean bottle(ServerPlayer p, Entity swarm, InteractionHand hand) {
        if (!m.mayUse(p, (ServerLevel) swarm.level(), swarm.blockPosition(), ClaimAction.BUILD)) return true;
        ItemStack jar = m.item(jarId, 1);
        if (jar == null) {
            m.bar(p, "harvest.missing-item");
            return true;
        }
        WildlifeModule.useOne(p, hand, jar);
        ((ServerLevel) swarm.level()).sendParticles(ParticleTypes.GLOW, swarm.getX(), swarm.getY() + 0.5, swarm.getZ(), 20, 0.6, 0.6, 0.6, 0);
        m.sound(p, "bottle");
        m.bar(p, "duskflies.bottled");
        m.bestiary.see(p, m.duskfliesId);
        m.bestiary.harvested(p, m.duskfliesId);
        swarm.discard();
        return true;
    }

    // ------------------------------------------------------------------ lanternsnail bucket

    void markBucket(ItemStack bucket, String species, boolean kept) {
        Pdc.itemPutString(bucket, WildlifeModule.NS + BUCKET_SPECIES, species);
        if (kept) Pdc.itemPut(bucket, WildlifeModule.NS + BUCKET_KEPT, ByteTag.valueOf((byte) 1));
    }

    boolean isSnailBucket(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        if (Pdc.itemString(s, WildlifeModule.NS + BUCKET_SPECIES) != null) return true;
        String oid = ArcanaContent.id(s);
        return oid != null && speciesForBucket(oid) != null;
    }

    private WildSpecies speciesForBucket(String oid) {
        for (WildSpecies sp : m.species.values()) if (sp.bucket != null && sp.bucket.item().equals(oid)) return sp;
        return null;
    }

    private boolean placeSnail(ServerPlayer p, ItemStack held, String oid, BlockHitResult hit) {
        String id = Pdc.itemString(held, WildlifeModule.NS + BUCKET_SPECIES);
        WildSpecies s = id != null ? m.species.get(id) : oid == null ? null : speciesForBucket(oid);
        if (s == null || s.bucket == null) return false;
        ServerLevel level = (ServerLevel) p.level();
        BlockPos clicked = hit.getBlockPos();
        BlockPos target = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
        BlockState ts = level.getBlockState(target);
        boolean isWater = ts.is(Blocks.WATER);
        if (!ts.getCollisionShape(level, target).isEmpty() && !isWater) {
            m.bar(p, "snail.blocked");
            return true;
        }
        if (!m.mayUse(p, level, target, ClaimAction.BUILD)) return true;
        if (s.bucket.placeWater() && level.dimension() != Level.NETHER && !isWater && (ts.isAir() || ts.canBeReplaced())) {
            level.setBlockAndUpdate(target, Blocks.WATER.defaultBlockState());
        }
        Entity snail = m.breeding.spawn(level, s.id, new Vec3(target.getX() + 0.5, target.getY() + 0.05, target.getZ() + 0.5));
        if (snail == null) {
            m.bar(p, "harvest.missing-item");
            return true;
        }
        // a snail a player let out is theirs to keep
        m.keep(snail);
        WildlifeModule.useOne(p, InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
        m.sound(p, "bucket");
        return true;
    }

    // ------------------------------------------------------------------ meals

    /** A player finished eating something ({@code FeatherEvents.ITEM_CONSUMED}): a wildlife meal buffs, a tick later. */
    void consumed(ServerPlayer p, ItemStack used) {
        String oid = ArcanaContent.id(used);
        if (oid == null || !meals.containsKey(oid)) return;
        String quality = Pdc.itemString(used, WildlifeModule.NS + qualityKey);
        // after the vanilla food component has done its part
        Tasks.later(1, () -> {
            if (!p.isRemoved() && p.isAlive()) consumed(p, oid, quality);
        });
    }

    private void consumed(ServerPlayer p, String oid, String q) {
        Meal meal = meals.get(oid);
        if (meal == null) return;
        // the farming module's cooking owns the meals it lists (buffs and quality): never buff twice
        if (farmingMeals.contains(oid) && farmingRunning()) return;
        double mult = quality.getOrDefault(q == null ? "normal" : q.toLowerCase(Locale.ROOT), 1.0);
        for (Effect e : meal.effects()) {
            int ticks = (int) Math.max(1, Math.round(e.seconds() * 20 * mult));
            p.addEffect(new MobEffectInstance(e.type(), ticks, e.level() - 1, true, true, true));
        }
        var food = p.getFoodData();
        if (meal.food() > 0) food.setFoodLevel(Math.min(20, food.getFoodLevel() + meal.food()));
        if (meal.saturation() > 0) food.setSaturation(Math.min(food.getFoodLevel(), food.getSaturationLevel() + meal.saturation()));
        m.bar(p, "meal.buff", WildlifeModule.tag("item", Stations.pretty(oid)));
    }

    private boolean farmingRunning() {
        for (FeatherModule fm : m.feather().modules()) if (fm.getClass().getSimpleName().equals("FarmingModule")) return true;
        return false;
    }

    void forget(UUID player) {
        panAt.remove(player);
        mapAt.remove(player);
    }
}
