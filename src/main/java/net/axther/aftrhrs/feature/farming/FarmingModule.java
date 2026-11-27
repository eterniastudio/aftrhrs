package net.axther.aftrhrs.feature.farming;

import com.google.gson.JsonObject;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.ArcanaCropBlock;
import net.axther.aftrhrs.content.FurnitureContext;
import net.axther.aftrhrs.content.FurnitureHook;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.FeatherModule;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.FeatherCommand;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ability.Abilities;
import net.axther.aftrhrs.feature.coast.Coast;
import net.axther.aftrhrs.net.Inspect;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
 * Farming (Feather 1.4 Coast &amp; Harvest): Arcana crop blocks ({@code aftrhrs:arcana_<crop>_crop}, {@code age} =
 * stage) planted on farmland and grown by Feather on per-chunk timers ({@link CropField}), harvest by breaking or
 * right-click (which replants), quality (watering can, compost, Sporelings) with Prize weights for the weekly
 * {@link HarvestFair}, compost bins, seaweed lines ({@link SeaweedLines}), cooking at the kitchen furniture
 * ({@link Cooking}), seeds from grass, the Canopip (replants while following; the Work Here job {@code farmhand}) and
 * smoking weed ({@link Smoking}). Settings: farming.yml.
 *
 * <p>Item data (custom_data, Paper's item PDC names): {@code feather:crop_quality} normal/fine/prize (absent = normal),
 * {@code feather:crop_weight} grams (Prize only), {@code feather:meal_quality} on meals.
 */
public final class FarmingModule implements FeatherModule, FeatherCommand {
    public static final String PERM = "feather.farming", ADMIN = "feather.farming.admin";
    static final String[] QUALITIES = {"normal", "fine", "prize"};
    static final String QUALITY = "feather:crop_quality", WEIGHT = "feather:crop_weight", MEAL = "feather:meal_quality";

    private static volatile FarmingModule active;

    private final Feather plugin;
    private final FarmConfig cfg = new FarmConfig();
    private final CropField field;
    private final Set<String> warned = new HashSet<>();
    private final Set<String> scythes = new HashSet<>();
    private final Map<UUID, Map<Long, Long>> creditedSpots = new HashMap<>();
    private long cropSpotMs = 20 * 60_000L;
    private Tasks.Task growTask, minuteTask;
    private long clockOffset;
    private String forcedQuality;

    // scythe harvest in progress (see harvestByTool)
    private boolean scytheReplant, byScythe;

    final Cooking cooking;
    final SeaweedLines seaweed;
    final CompostBins bins;
    final HarvestFair fair;
    final Smoking smoking;

    public FarmingModule(Feather plugin) {
        this.plugin = plugin;
        this.field = new CropField(this);
        this.cooking = new Cooking(this);
        this.seaweed = new SeaweedLines(this);
        this.bins = new CompostBins(this);
        this.fair = new HarvestFair(this);
        this.smoking = new Smoking(this);
    }

    @Override
    public String name() {
        return "Farming";
    }

    @Override
    public void enable() {
        readConfig();
        active = this;
        net.axther.aftrhrs.net.RecipeExport.source("farming.cooking", FarmingModule::exportMeals);
        plugin.bind("farming", this);
        plugin.once("farming.events", FarmingModule::registerEvents);
        field.loadAll();
        seaweed.scanAll();
        fair.load();
        startTasks();
        plugin.provide(Hooks.Farmhand.class, new Hooks.Farmhand() {
            @Override
            public boolean ripe(ServerLevel level, BlockPos pos) {
                CropField.Crop c = live(level, pos);
                return c != null && c.ripe();
            }

            @Override
            public List<ItemStack> harvest(ServerLevel level, BlockPos pos) {
                return farmhandHarvest(level, pos);
            }

            @Override
            public int containerRadius() {
                return cfg.farmhandContainerRadius;
            }

            @Override
            public double seedFindChance() {
                return cfg.farmhandSeedChance;
            }

            @Override
            public ItemStack seedFind() {
                ItemStack s = randomSeeds(null, null);
                return s == null ? ItemStack.EMPTY : s;
            }

            @Override
            public void worked(Entity worker) {
                signal(worker, null, "CANOPIP_REPLANT");
            }
        });
        plugin.provide(Hooks.Crops.class, new Hooks.Crops() {
            @Override
            public boolean ripe(ServerLevel level, BlockPos pos) {
                CropField.Crop c = live(level, pos);
                return c != null && c.ripe();
            }

            @Override
            public boolean harvest(ServerPlayer player, ServerLevel level, BlockPos pos, boolean replant) {
                return harvestByTool(player, level, pos, replant);
            }
        });
        // The look-at card: what the empty-hand status click says about a crop.
        Inspect.register("farming", (p, level, pos, state) -> inspect(level, pos));
    }

    @Override
    public void reload() {
        field.saveAll();
        readConfig();
        // records point at crop definitions: bind them to the new settings (loaded chunks stay loaded)
        field.rebind();
        startTasks();
        seaweed.redrawAll();
        net.axther.aftrhrs.net.RecipeExport.changed();
    }

    @Override
    public void disable() {
        if (growTask != null) growTask.cancel();
        if (minuteTask != null) minuteTask.cancel();
        growTask = minuteTask = null;
        smoking.clear();
        field.saveAll();
        field.clear();
        seaweed.clearAll();
        fair.save();
        plugin.withdraw(Hooks.Farmhand.class);
        plugin.withdraw(Hooks.Crops.class);
        Inspect.unregister("farming");
        buffedMeals = Set.of();
        if (active == this) active = null;
        net.axther.aftrhrs.net.RecipeExport.changed();
    }

    private void readConfig() {
        Yaml yml = plugin.loadResourceConfig("farming.yml");
        cfg.load(yml, this::warnOnce);
        buffedMeals = Set.copyOf(cfg.mealsByItem.keySet());
        Yaml tools = plugin.loadResourceConfig("tools.yml");
        scythes.clear();
        for (String s : tools.getStringList("harvest.tools")) scythes.add(s.trim().toLowerCase(Locale.ROOT));
        cropSpotMs = Math.max(0, plugin.loadResourceConfig("quests.yml").getLong("settings.crop-spot-minutes", 20)) * 60_000L;
    }

    private void startTasks() {
        if (growTask != null) growTask.cancel();
        if (minuteTask != null) minuteTask.cancel();
        growTask = Tasks.timer(cfg.checkTicks, cfg.checkTicks, () -> {
            try {
                field.tick();
                seaweed.tick();
            } catch (Throwable t) {
                warnOnce("Farming timer failed: " + t);
            }
        });
        minuteTask = Tasks.timer(200L, 1200L, fair::tick);
    }

    // ================================================================== listeners (registered once; see active)

    private static void registerEvents() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            FarmingModule m = active;
            if (m == null) return;
            m.field.load(level, chunk);
            m.seaweed.chunkLoaded(level, chunk);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            FarmingModule m = active;
            if (m == null) return;
            m.field.unload(level, chunk);
            m.seaweed.chunkUnloaded(level, chunk);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            FarmingModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel w)) return InteractionResult.PASS;
            return m.onUseBlock(p, w, hand, hit);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            FarmingModule m = active;
            if (m == null || !(player instanceof ServerPlayer p) || !(level instanceof ServerLevel)) return InteractionResult.PASS;
            return m.onUseItem(p, hand);
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            FarmingModule m = active;
            if (m != null && player instanceof ServerPlayer p && level instanceof ServerLevel w) m.onBreak(p, w, pos, state);
        });
        FeatherEvents.PHYSICAL_INTERACT.register((entity, level, pos, state) -> {
            FarmingModule m = active;
            // crops keep their farmland: no trampling (players and mobs)
            return m == null || !state.is(Blocks.FARMLAND) || m.field.get(level, pos.above()) == null;
        });
        FeatherEvents.ITEM_CONSUMED.register((p, used, hand) -> {
            FarmingModule m = active;
            if (m == null) return;
            String id = Coast.id(used);
            if (id != null && m.cfg.mealsByItem.containsKey(id)) m.cooking.applyBuffs(p, used, id);
            m.smoking.consumed(p, used);
        });
        FeatherEvents.CRAFT_RESULT.register((p, input, result) -> {
            FarmingModule m = active;
            return m == null ? result : m.smoking.craftResult(input, result);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            FarmingModule m = active;
            if (m != null) m.onQuit(h.player.getUUID());
        });
        // Crop blocks: a player's break pays the harvest (and replants with a Canopip).
        ArcanaContent.hook(id -> active != null && active.cfg.cropOfBlock(id) != null, new FurnitureHook() {
            @Override
            public boolean onBreak(FurnitureContext ctx) {
                FarmingModule m = active;
                return m == null || m.cropBroken(ctx);
            }
        });
        // Compost bins, seaweed lines and cooking stations.
        ArcanaContent.hook(id -> active != null && active.furnitureKind(id) != null, new FurnitureHook() {
            @Override
            public InteractionResult onUse(FurnitureContext ctx) {
                FarmingModule m = active;
                if (m == null || ctx.player() == null || ctx.hand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
                return m.furnitureClick(ctx) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }

            @Override
            public void onPlaced(FurnitureContext ctx) {
                FarmingModule m = active;
                if (m != null && m.cfg.lineId.equals(ctx.id())) m.seaweed.placed(ctx.level(), ctx.origin());
            }

            @Override
            public void onRemoved(FurnitureContext ctx) {
                FarmingModule m = active;
                if (m == null) return;
                if (m.cfg.lineId.equals(ctx.id())) m.seaweed.broken(ctx.level(), ctx.origin());
                else if (Coast.matches(m.cfg.binIds, ctx.id())) m.bins.broken(ctx.level(), ctx.origin());
            }
        });
        // Paper-era compost bins and seaweed lines keep their state (base entity PDC -> SpotData).
        Legacy.onFurniture((level, origin, id, bv, yaw) -> {
            FarmingModule m = active;
            if (m == null) return;
            if (m.cfg.lineId.equals(id)) m.seaweed.migrate(level, origin, bv);
            else if (Coast.matches(m.cfg.binIds, id)) m.bins.migrate(level, origin, bv);
        });
        // Overlay displays a crash left behind (they are never meant to be saved).
        Legacy.converter((level, entity, bv) -> {
            if (!bv.contains("feather:seaweed_part")) return false;
            entity.discard();
            return true;
        });
    }

    /** "bin", "line" or "station" for a furniture id this module claims clicks on, or null. */
    String furnitureKind(String id) {
        if (Coast.matches(cfg.binIds, id)) return "bin";
        if (cfg.lineId.equals(id)) return "line";
        if (cfg.stationOf(id) != null) return "station";
        return null;
    }

    // ================================================================== shared helpers

    Feather plugin() {
        return plugin;
    }

    Dialogs dialogs() {
        return plugin.dialogs();
    }

    FarmConfig config() {
        return cfg;
    }

    CropField field() {
        return field;
    }

    /** Wall clock plus the debug offset (/farming debug clock). */
    long now() {
        return System.currentTimeMillis() + clockOffset;
    }

    void warnOnce(String message) {
        if (warned.add(message)) plugin.getLogger().warn("[Farming] {}", message);
    }

    Text text() {
        return plugin.text();
    }

    void bar(ServerPlayer p, String key, TagResolver... r) {
        plugin.text().actionBar(p, "farming." + key, r);
    }

    void send(ServerPlayer p, String key, TagResolver... r) {
        plugin.text().send(p, "farming." + key, r);
    }

    void send(Sender to, String key, TagResolver... r) {
        plugin.text().send(to, "farming." + key, r);
    }

    Component msg(String key, TagResolver... r) {
        return plugin.text().msg("farming." + key, r);
    }

    /** A fresh content item, or null (logged once) when there is no such item. */
    ItemStack build(String id, int amount) {
        ItemStack s = Coast.item(id, amount);
        if (s == null) warnOnce("Item '" + id + "' doesn't exist; that part of farming waits for it.");
        return s;
    }

    boolean allowed(ServerPlayer p, ServerLevel level, BlockPos at, ClaimAction action) {
        boolean ok = Coast.allowed(p, level, at, action);
        if (!ok) bar(p, "claim");
        return ok;
    }

    static String pretty(String id) {
        return Coast.pretty(id);
    }

    /**
     * The cooking meals as recipe-viewer recipes ({@code feather:cooking}): every item each ingredient rule takes, the
     * meal, and the first station piece that cooks it, with the stations' names as the note. Nothing while off.
     */
    private static void exportMeals(net.axther.aftrhrs.net.RecipeExport.Sink sink) {
        FarmingModule m = active;
        if (m == null) return;
        FarmConfig c = m.cfg;
        java.util.Set<String> ids = ArcanaContent.entries().keySet();
        for (FarmConfig.Meal meal : c.meals.values()) {
            ItemStack out = ids.contains(meal.itemId()) ? ArcanaContent.stack(meal.itemId(), 1) : null;
            if (out == null || out.isEmpty()) continue;
            List<List<ItemStack>> in = new ArrayList<>();
            for (FarmConfig.ItemRule r : meal.ingredients) in.add(r.examples());
            ItemStack station = null;
            List<String> where = new ArrayList<>();
            for (String st : meal.stations) {
                where.add(pretty(st));
                if (station != null) continue;
                for (java.util.regex.Pattern pat : c.stations.getOrDefault(st, List.of())) {
                    for (String id : ids) {
                        if (station == null && pat.matcher(id).matches()) station = ArcanaContent.stack(id, 1);
                    }
                }
            }
            sink.machine("feather:cooking/" + meal.id, "cooking", station, in, out, Component.text(String.join(" · ", where)));
        }
    }

    // ================================================================== growth rules (CropField calls these)

    /**
     * "ok" (the block is this crop; {@code c.stage} follows its age) or "gone". An old Oraxen crop (a tripwire block
     * with a record) becomes the crop block at the recorded stage here, so a migrated farm keeps its crops.
     */
    String stageState(ServerLevel level, BlockPos b, CropField.Crop c) {
        BlockState st = level.getBlockState(b);
        Block crop = c.def.block();
        if (crop == null) return "gone";
        if (st.is(Blocks.TRIPWIRE)) {
            BlockState want = ArcanaContent.stateFor(c.def.stageId(Math.max(0, Math.min(3, c.stage))));
            if (want == null) return "gone";
            level.setBlock(b, want, Block.UPDATE_CLIENTS);
            return "ok";
        }
        if (!st.is(crop) || !(crop instanceof ArcanaCropBlock acb)) return "gone";
        c.stage = st.getValue(acb.age);
        return "ok";
    }

    /** The crop record at this block, if the block still is that crop (a record whose block changed is dropped). */
    CropField.Crop live(ServerLevel level, BlockPos b) {
        CropField.Crop c = field.get(level, b);
        if (c == null) return null;
        if (stageState(level, b, c).equals("gone")) {
            field.remove(level, b);
            return null;
        }
        return c;
    }

    /** {@link #live}, or a record made now for a crop block that has none (placed by a command, a lost record). */
    CropField.Crop liveOrAdopt(ServerLevel level, BlockPos b) {
        CropField.Crop c = live(level, b);
        if (c != null) return c;
        BlockState st = level.getBlockState(b);
        FarmConfig.CropDef def = cfg.cropOfBlock(ArcanaContent.id(st));
        if (def == null || !(st.getBlock() instanceof ArcanaCropBlock acb)) return null;
        int stage = st.getValue(acb.age);
        return field.add(level, b, def, stage, stage >= 3 ? Long.MAX_VALUE : now() + stageMs(level, b, def));
    }

    /** Why this crop can't grow now (a farming.why.* key), or null. */
    String whyNot(ServerLevel level, BlockPos b, FarmConfig.CropDef def) {
        BlockPos soilPos = b.below();
        BlockState soil = level.getBlockState(soilPos);
        if (!soil.is(Blocks.FARMLAND)) return "soil";
        if (!CropField.hydrated(soil)) return "dry";
        if (Math.max(level.getBrightness(LightLayer.SKY, b), level.getBrightness(LightLayer.BLOCK, b)) < cfg.minLight) return "dark";
        if (def.needsWater && !CropField.waterBeside(level, soilPos)) return "water";
        return null;
    }

    /** One stage's time here: faster in an affinity biome, or (tomato, grape) next to a trellis. */
    long stageMs(ServerLevel level, BlockPos b, FarmConfig.CropDef def) {
        return (long) (def.stageMs / (boosted(level, b, def) ? cfg.affinityMultiplier : 1.0));
    }

    boolean boosted(ServerLevel level, BlockPos b, FarmConfig.CropDef def) {
        if (def.trellis) return trellisNear(level, b);
        return !def.affinity.isEmpty() && def.affinity.matches(Coast.biomeKey(level, b));
    }

    /** A trellis piece within trellis-radius of the crop (the old furniture-entity box: r, r + 0.5 up and down). */
    private boolean trellisNear(ServerLevel level, BlockPos b) {
        double r = cfg.trellisRadius;
        int ri = (int) Math.ceil(r), ry = (int) Math.ceil(r + 0.5);
        Vec3 c = Vec3.atCenterOf(b);
        for (BlockPos p : BlockPos.betweenClosed(b.offset(-ri, -ry, -ri), b.offset(ri, ry, ri))) {
            BlockState st = level.getBlockState(p);
            if (!ArcanaContent.isFurniture(st)) continue;
            String id = ArcanaContent.id(st);
            if (!Coast.matches(cfg.trellisIds, id)) continue;
            BlockPos o = ArcanaContent.origin(st, p);
            Vec3 at = new Vec3(o.getX() + 0.5, o.getY() + 0.5, o.getZ() + 0.5);
            if (Math.abs(at.x - c.x) <= r && Math.abs(at.y - c.y) <= r + 0.5 && Math.abs(at.z - c.z) <= r) return true;
        }
        return false;
    }

    /** Sets the crop block to this stage; false when there is no such stage block. */
    boolean setStage(ServerLevel level, BlockPos b, CropField.Crop c, int stage) {
        BlockState st = ArcanaContent.stateFor(c.def.stageId(stage));
        if (st == null) {
            warnOnce("Crop block for '" + c.def.stageId(stage) + "' doesn't exist; that crop waits.");
            return false;
        }
        level.setBlock(b, st, Block.UPDATE_CLIENTS);
        c.stage = stage;
        return true;
    }

    // ================================================================== items: produce, quality, watering can

    static int qualityOf(ItemStack s) {
        if (s == null || s.isEmpty()) return 0;
        String q = Pdc.itemString(s, QUALITY);
        if ("prize".equals(q)) return 2;
        if ("fine".equals(q)) return 1;
        return 0;
    }

    static int weightOf(ItemStack s) {
        return s == null || s.isEmpty() ? 0 : Coast.itemInt(s, WEIGHT, 0);
    }

    /** A produce item of this quality (0..2); Prize gets a weight. Null without the item. */
    ItemStack produce(String id, int quality, int amount, int baseGrams) {
        ItemStack s = build(id, amount);
        if (s == null || quality <= 0) return s;
        Pdc.itemPutString(s, QUALITY, QUALITIES[quality]);
        Coast.addLore(s, text().item(quality == 2 ? cfg.lorePrize : cfg.loreFine));
        if (quality == 2) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double f = cfg.prizeMin + r.nextDouble() * Math.max(0, cfg.prizeMax - cfg.prizeMin);
            f *= Math.exp(r.nextGaussian() * 0.08);
            int grams = Math.max(1, (int) Math.round(baseGrams * f));
            Pdc.itemPut(s, WEIGHT, IntTag.valueOf(grams));
            Coast.addLore(s, text().item(cfg.loreWeight, Text.unparsed("kg", kg(grams))));
        }
        return s;
    }

    static String kg(int grams) {
        return String.format(Locale.ROOT, "%.2f", grams / 1000.0);
    }

    private int rollQuality(CropField.Crop c, boolean compost) {
        if (forcedQuality != null) return Math.max(0, List.of(QUALITIES).indexOf(forcedQuality));
        int w = c.wateredStages();
        double fine = cfg.fineChance + w * cfg.waterFine + (compost ? cfg.compostFine : 0);
        double prize = cfg.prizeChance + w * cfg.waterPrize + (compost ? cfg.compostPrize : 0);
        double r = ThreadLocalRandom.current().nextDouble();
        return r < prize ? 2 : r < prize + fine ? 1 : 0;
    }

    private double sporeReach(Entity e) {
        int bond = e instanceof ArcanaCreature c ? c.bond() : 0;
        return cfg.sporelingRadius + cfg.sporelingPerBond * Math.max(0, Math.min(10, bond));
    }

    /** A loaded Sporeling (following someone, or working) whose spores reach this crop, or null. */
    Entity sporeling(ServerLevel level, BlockPos b) {
        Vec3 at = Vec3.atCenterOf(b);
        double far = cfg.sporelingRadius + cfg.sporelingPerBond * 10;
        List<Entity> list = new ArrayList<>();
        var type = Coast.creatureType(cfg.sporelingType);
        Hooks.PetWork work = plugin.service(Hooks.PetWork.class);
        if (type != null && work != null) list.addAll(work.workingEntities(Coast.speciesId(type)));
        for (ServerPlayer p : level.players()) {
            if (p.position().distanceToSqr(at) > (far + 64) * (far + 64)) continue;
            list.addAll(Coast.followingNearby(p, cfg.sporelingType, 64));
        }
        for (Entity e : list) {
            double r = sporeReach(e);
            if (e.level() == level && e.position().distanceToSqr(at) <= r * r) return e;
        }
        return null;
    }

    /** {@link #sporeling} or a working Sporeling whose own chunk isn't loaded right now (base reach). */
    boolean sporelingNear(ServerLevel level, BlockPos b) {
        if (sporeling(level, b) != null) return true;
        Hooks.PetWork work = plugin.service(Hooks.PetWork.class);
        var type = Coast.creatureType(cfg.sporelingType);
        if (work == null || type == null) return false;
        Vec3 at = Vec3.atCenterOf(b);
        double r = cfg.sporelingRadius;
        try {
            for (Loc l : work.workingAt(Coast.speciesId(type))) {
                if (l.level() == level && l.vec().distanceToSqr(at) <= r * r) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** The owner's Canopip following within canopip.radius, or null. */
    Entity canopip(ServerPlayer p) {
        if (p == null) return null;
        List<ArcanaCreature> list = Coast.followingNearby(p, cfg.canopipType, cfg.canopipRadius);
        return list.isEmpty() ? null : list.get(0);
    }

    /** A creature signal ({@code ~onSignal:X} lines play a clip). */
    void signal(Entity mob, Entity from, String signal) {
        if (mob instanceof net.axther.aftrhrs.entity.ArcanaMob am) {
            try {
                Abilities.signal(am, signal, from);
            } catch (RuntimeException t) {
                warnOnce("Signal " + signal + " failed: " + t);
            }
        }
    }

    /** What a ripe crop drops: produce with rolled quality (Prize ones one by one, with weights) and seeds. */
    List<ItemStack> harvestDrops(ServerLevel level, CropField.Crop c, BlockPos b) {
        BlockPos soil = b.below();
        int compost = level.getBlockState(soil).is(Blocks.FARMLAND) ? field.compost(level, soil) : 0;
        if (compost > 0) field.setCompost(level, soil, compost - 1);
        Entity sporeMob = sporeling(level, b);
        boolean spore = sporeMob != null || sporelingNear(level, b);
        int[] counts = new int[3];
        int n = c.def.produce.roll();
        boolean boosted = false;
        for (int i = 0; i < n; i++) {
            int q = rollQuality(c, compost > 0);
            if (spore && q < 2) {
                q++;
                boosted = true;
            }
            counts[q]++;
        }
        if (boosted && sporeMob != null) signal(sporeMob, null, "SPORELING_BOOST");
        List<ItemStack> out = new ArrayList<>();
        for (int q = 0; q < 2; q++) {
            if (counts[q] == 0) continue;
            ItemStack s = produce(c.def.produceId(), q, counts[q], c.def.weight);
            if (s != null) out.add(s);
        }
        for (int i = 0; i < counts[2]; i++) {
            ItemStack s = produce(c.def.produceId(), 2, 1, c.def.weight);
            if (s != null) out.add(s);
        }
        int seeds = c.def.seeds.roll();
        if (seeds > 0) {
            ItemStack s = build(c.def.seedsId(), seeds);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static boolean takeSeed(List<ItemStack> drops, String seedsId) {
        for (ItemStack s : drops) {
            if (!s.isEmpty() && seedsId.equals(Coast.id(s))) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Quest and goal credit for a ripe harvest (a spot counts once per quests.yml crop-spot-minutes). */
    private void credit(ServerPlayer p, CropField.Crop c, boolean scythe) {
        if (p == null) return;
        plugin.stat(p.getUUID(), "crops-harvested", 1);
        Hooks.Quests quests = plugin.service(Hooks.Quests.class);
        if (quests == null) return;
        long now = System.currentTimeMillis();
        Map<Long, Long> mine = creditedSpots.computeIfAbsent(p.getUUID(), k -> new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > 4096;
            }
        });
        long key = CropField.pos(c.x, c.y, c.z);
        Long t = mine.get(key);
        if (t != null && now - t < cropSpotMs) return;
        if (mine.size() >= 256) mine.values().removeIf(v -> now - v >= cropSpotMs);
        mine.put(key, now);
        quests.progress(p, "harvest", c.def.produceId(), 1);
        if (scythe) quests.progress(p, "scythe", c.def.produceId(), 1);
    }

    private void onQuit(UUID id) {
        Map<Long, Long> mine = creditedSpots.get(id);
        if (mine == null) return;
        long now = System.currentTimeMillis();
        mine.values().removeIf(v -> now - v >= cropSpotMs);
        if (mine.isEmpty()) creditedSpots.remove(id);
    }

    private boolean isScythe(ItemStack s) {
        String id = Coast.id(s);
        return id != null && scythes.contains(id.toLowerCase(Locale.ROOT));
    }

    /** A random crop's seeds; biome-matching crops only when {@code at} is given (null: any crop). */
    ItemStack randomSeeds(ServerLevel level, BlockPos at) {
        List<FarmConfig.CropDef> list = new ArrayList<>();
        String key = at == null || level == null ? null : Coast.biomeKey(level, at);
        for (FarmConfig.CropDef d : cfg.crops.values()) {
            if (key == null || d.seedBiomes.matches(key)) list.add(d);
        }
        if (list.isEmpty()) return null;
        FarmConfig.CropDef d = list.get(ThreadLocalRandom.current().nextInt(list.size()));
        return build(d.seedsId(), 1);
    }

    // ================================================================== planting, harvest, watering, compost

    private InteractionResult onUseBlock(ServerPlayer p, ServerLevel level, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        ItemStack held = p.getMainHandItem();
        String heldId = Coast.id(held);
        BlockPos clicked = hit.getBlockPos();
        BlockState st = level.getBlockState(clicked);
        boolean used = false;
        if (st.getBlock() instanceof ArcanaCropBlock && cfg.cropOfBlock(ArcanaContent.id(st)) != null) {
            CropField.Crop crop = liveOrAdopt(level, clicked);
            if (crop != null) {
                if (isScythe(held)) return InteractionResult.PASS;   // the scythe harvests the area (tools)
                used = cropClick(p, level, clicked, crop, held, heldId);
            }
        } else if (st.is(Blocks.FARMLAND)) {
            FarmConfig.CropDef seeds = cfg.cropOfSeeds(heldId);
            if (seeds != null) {
                used = true;   // never let a seed item do its vanilla thing
                if (hit.getDirection() == Direction.UP) plant(p, level, clicked, seeds);
            } else if (heldId != null && heldId.equals(cfg.compostId)) {
                used = true;
                compost(p, level, clicked);
            }
        }
        if (!used && heldId != null && heldId.equals(cfg.canId)) used = refill(p, held);
        return used ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private InteractionResult onUseItem(ServerPlayer p, InteractionHand hand) {
        ItemStack held = p.getItemInHand(hand);
        String heldId = Coast.id(held);
        if (heldId == null) return InteractionResult.PASS;
        InteractionResult smoke = smoking.use(p, hand, held, heldId);
        if (smoke != InteractionResult.PASS) return smoke;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (heldId.equals(cfg.canId) && refill(p, held)) return InteractionResult.SUCCESS;
        if (cfg.mealsByItem.containsKey(heldId) && cooking.eatFallback(p, held, heldId)) return InteractionResult.SUCCESS;
        return InteractionResult.PASS;
    }

    private void plant(ServerPlayer p, ServerLevel level, BlockPos farmland, FarmConfig.CropDef def) {
        BlockPos at = farmland.above();
        if (!level.getBlockState(at).isAir()) return;
        if (!allowed(p, level, at, ClaimAction.BUILD)) return;
        BlockState st = ArcanaContent.stateFor(def.stageId(0));
        if (st == null) {
            warnOnce("Crop block for '" + def.stageId(0) + "' doesn't exist; planting " + def.id + " waits for it.");
            bar(p, "not-ready");
            return;
        }
        level.setBlock(at, st, Block.UPDATE_ALL);
        field.add(level, at, def, 0, now() + stageMs(level, at, def));
        Coast.takeOne(p, InteractionHand.MAIN_HAND);
        Coast.swing(p);
        Coast.sound(level, Vec3.atCenterOf(at).add(0, -0.3, 0), "item.crop.plant", SoundSource.BLOCKS, 1f, 1f);
        String why = whyNot(level, at, def);
        if (why != null) bar(p, "planted-but", Text.component("why", msg("why." + why)));
    }

    private boolean cropClick(ServerPlayer p, ServerLevel level, BlockPos b, CropField.Crop c, ItemStack held, String heldId) {
        if (heldId != null && heldId.equals(cfg.canId)) {
            water(p, level, b, c, held);
            return true;
        }
        if (heldId != null && heldId.equals(cfg.compostId)) {
            compost(p, level, b.below());
            return true;
        }
        if (c.ripe()) {
            if (!allowed(p, level, b, ClaimAction.BUILD)) return true;
            harvestRightClick(p, level, b, c);
            return true;
        }
        status(p, level, b, c);
        return true;
    }

    private void status(ServerPlayer p, ServerLevel level, BlockPos b, CropField.Crop c) {
        String why = whyNot(level, b, c.def);
        Component state = why != null ? msg("why." + why) : msg(boosted(level, b, c.def) ? "status-growing-fast" : "status-growing");
        Component water = msg((c.watered & (1 << c.stage)) != 0 ? "status-watered" : "status-dry");
        bar(p, "status", Text.unparsed("crop", pretty(c.def.id)), Text.unparsed("stage", c.stage), Text.component("state", state),
                Text.component("water", water));
    }

    /**
     * The look-at card for a crop (Companion {@code inspect}): the same facts as {@link #status}, from the same
     * messages, without a claim check (the status click has none either). Null when the block isn't a crop.
     */
    private JsonObject inspect(ServerLevel level, BlockPos b) {
        CropField.Crop c = liveOrAdopt(level, b);
        if (c == null) return null;
        JsonObject card = Inspect.card("crop", pretty(c.def.id), Coast.item(c.def.produceId(), 1));
        if (c.ripe()) {
            Inspect.bar(card, 1, Inspect.plain(msg("status-ripe")), Inspect.GOOD);
            return card;
        }
        int stages = 3;
        Inspect.bar(card, (c.stage + 1) / (double) stages, Inspect.plain(
                msg("inspect-stage", Text.unparsed("stage", String.valueOf(c.stage + 1)), Text.unparsed("stages", String.valueOf(stages)))));
        boolean watered = (c.watered & (1 << c.stage)) != 0;
        Inspect.text(card, Inspect.plain(msg(watered ? "status-watered" : "status-dry")),
                watered ? Inspect.SKY : Inspect.MUTED, watered ? "drop" : "drop_empty");
        String why = whyNot(level, b, c.def);
        if (why != null) {
            Inspect.text(card, Inspect.plain(msg("why." + why)), Inspect.ROSE,
                    why.equals("dark") ? "moon" : "warn");
        } else {
            Inspect.text(card, Inspect.plain(msg(boosted(level, b, c.def) ? "status-growing-fast" : "status-growing")),
                    Inspect.GOOD, "sun");
        }
        return card;
    }

    private void harvestRightClick(ServerPlayer p, ServerLevel level, BlockPos b, CropField.Crop c) {
        List<ItemStack> drops = harvestDrops(level, c, b);
        boolean replanted = takeSeed(drops, c.def.seedsId()) || takeFromInventory(p, c.def.seedsId());
        if (replanted && setStage(level, b, c, 0)) {
            c.watered = 0;
            c.next = now() + stageMs(level, b, c.def);
            field.changed(level, b);
        } else {
            field.remove(level, b);
            level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Coast.dropAll(level, b, drops);
        Coast.swing(p);
        Coast.sound(level, Vec3.atCenterOf(b).add(0, -0.2, 0), "block.crop.break", SoundSource.BLOCKS, 1f, 1f);
        credit(p, c, false);
    }

    private static boolean takeFromInventory(ServerPlayer p, String id) {
        if (Coast.creative(p)) return true;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
            ItemStack s = inv.getItem(i);
            if (id.equals(Coast.id(s))) {
                s.shrink(1);
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /** The can's capacity: its own max durability when it has one (the Arcana can: 16), else watering-can.uses. */
    int canMax(ItemStack can) {
        return can.isDamageableItem() ? can.getMaxDamage() : cfg.canUses;
    }

    int canUses(ItemStack can) {
        if (can.isDamageableItem()) return Math.max(0, can.getMaxDamage() - can.getDamageValue());
        int u = Coast.itemInt(can, "feather:can_uses", cfg.canUses);
        return Math.max(0, Math.min(cfg.canUses, u));
    }

    void setCanUses(ItemStack can, int uses) {
        if (can.isDamageableItem()) {
            can.setDamageValue(Math.max(0, Math.min(can.getMaxDamage(), can.getMaxDamage() - uses)));
        } else {
            Pdc.itemPut(can, "feather:can_uses", IntTag.valueOf(uses));
        }
    }

    private void water(ServerPlayer p, ServerLevel level, BlockPos b, CropField.Crop c, ItemStack can) {
        if (!allowed(p, level, b, ClaimAction.BUILD)) return;
        if (c.ripe()) {
            bar(p, "water-ripe");
            return;
        }
        int bit = 1 << c.stage;
        if ((c.watered & bit) != 0) {
            bar(p, "water-already");
            return;
        }
        int uses = canUses(can);
        if (uses <= 0) {
            bar(p, "can-empty");
            return;
        }
        c.watered |= bit;
        field.changed(level, b);
        if (!Coast.creative(p)) setCanUses(can, uses - 1);
        Vec3 at = Vec3.atCenterOf(b).add(0, -0.1, 0);
        level.sendParticles(ParticleTypes.SPLASH, at.x, at.y, at.z, 14, 0.3, 0.2, 0.3, 0.1);
        Coast.sound(level, at, "item.bucket.empty", SoundSource.BLOCKS, 0.5f, 1.5f);
        Coast.swing(p);
        bar(p, "watered", Text.unparsed("uses", Coast.creative(p) ? uses : uses - 1), Text.unparsed("max", canMax(can)));
    }

    /** Right-click with the can while looking at water (within 5 blocks): full again. */
    private boolean refill(ServerPlayer p, ItemStack can) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(5));
        BlockHitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK || !p.level().getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) return false;
        int max = canMax(can);
        if (canUses(can) >= max) {
            bar(p, "can-full");
            return true;
        }
        setCanUses(can, max);
        Coast.sound(p.level(), Vec3.atCenterOf(hit.getBlockPos()), "item.bucket.fill", SoundSource.BLOCKS, 0.6f, 1.3f);
        Coast.swing(p);
        bar(p, "can-filled", Text.unparsed("max", max));
        return true;
    }

    private void compost(ServerPlayer p, ServerLevel level, BlockPos farmland) {
        if (!level.getBlockState(farmland).is(Blocks.FARMLAND)) return;
        if (!allowed(p, level, farmland, ClaimAction.BUILD)) return;
        int left = field.compost(level, farmland);
        if (left > 0) {
            bar(p, "compost-already", Text.unparsed("left", left));
            return;
        }
        field.setCompost(level, farmland, cfg.compostHarvests);
        Coast.takeOne(p, InteractionHand.MAIN_HAND);
        Coast.swing(p);
        Vec3 at = Vec3.atBottomCenterOf(farmland).add(0, 1.05, 0);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z, 10, 0.3, 0.05, 0.3, 0);
        Coast.sound(level, at, "block.composter.fill_success", SoundSource.BLOCKS, 0.7f, 1f);
        bar(p, "composted", Text.unparsed("harvests", cfg.compostHarvests));
    }

    /** The scythe hook: claims decide, then the crop breaks like a player break (counted as a scythe harvest). */
    private boolean harvestByTool(ServerPlayer p, ServerLevel level, BlockPos b, boolean replant) {
        CropField.Crop c = live(level, b);
        if (c == null || !c.ripe()) return false;
        if (!Coast.allowed(p, level, b, ClaimAction.BUILD)) return false;
        byScythe = true;
        scytheReplant = replant;
        try {
            BlockState st = level.getBlockState(b);
            String id = ArcanaContent.id(st);
            return id != null && !cropBroken(new FurnitureContext(id, level, b, st, b, p, InteractionHand.MAIN_HAND, p.getMainHandItem(), null));
        } finally {
            byScythe = false;
        }
    }

    /**
     * A player breaks a crop block (directly, a scythe): drops, credit, replant. Returns false when farming did the
     * break itself (the vanilla break and its loot table are cancelled).
     */
    private boolean cropBroken(FurnitureContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos b = ctx.origin();
        ServerPlayer p = ctx.player();
        // Content hooks run before the claims port's own break protection: check the claim here (scythe users too).
        if (p != null && !allowed(p, level, b, ClaimAction.BUILD)) return false;
        CropField.Crop c = liveOrAdopt(level, b);
        if (c == null) return true;
        field.remove(level, b);
        List<ItemStack> drops;
        boolean replant = false;
        Entity pip = null;
        if (c.ripe()) {
            drops = harvestDrops(level, c, b);
            credit(p, c, byScythe || (p != null && isScythe(p.getMainHandItem())));
            if (byScythe) replant = scytheReplant;
            else replant = (pip = canopip(p)) != null;
        } else {
            ItemStack seed = build(c.def.seedsId(), 1);
            drops = seed == null ? new ArrayList<>() : new ArrayList<>(List.of(seed));
        }
        level.destroyBlock(b, false, p);
        if (replant && (takeSeed(drops, c.def.seedsId()) || Coast.creative(p))) {
            FarmConfig.CropDef def = c.def;
            Tasks.later(1, () -> replantAt(level, b, def));
            if (pip != null) signal(pip, p, "CANOPIP_REPLANT");
        }
        if (!Coast.creative(p)) Coast.dropAll(level, b, drops);
        return false;
    }

    private void replantAt(ServerLevel level, BlockPos b, FarmConfig.CropDef def) {
        if (!level.getBlockState(b).isAir() || !level.getBlockState(b.below()).is(Blocks.FARMLAND)) return;
        BlockState st = ArcanaContent.stateFor(def.stageId(0));
        if (st == null) return;
        level.setBlock(b, st, Block.UPDATE_ALL);
        field.add(level, b, def, 0, now() + stageMs(level, b, def));
    }

    /** The farmhand job: harvest and replant in place; the drops go to the job's chest. */
    private List<ItemStack> farmhandHarvest(ServerLevel level, BlockPos b) {
        CropField.Crop c = live(level, b);
        if (c == null || !c.ripe()) return null;
        List<ItemStack> drops = harvestDrops(level, c, b);
        if (takeSeed(drops, c.def.seedsId()) && setStage(level, b, c, 0)) {
            c.watered = 0;
            c.next = now() + stageMs(level, b, c.def);
            field.changed(level, b);
        } else {
            field.remove(level, b);
            level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        drops.removeIf(ItemStack::isEmpty);
        return drops;
    }

    // ================================================================== grass seeds, the Canopip on vanilla crops

    private static final Map<Block, net.minecraft.world.item.Item> VANILLA_SEEDS = Map.of(Blocks.WHEAT, Items.WHEAT_SEEDS,
            Blocks.CARROTS, Items.CARROT, Blocks.POTATOES, Items.POTATO, Blocks.BEETROOTS, Items.BEETROOT_SEEDS);

    private void onBreak(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state) {
        if (Coast.creative(p)) return;
        if (cfg.seedBlocks.contains(state.getBlock()) && cfg.seedChance > 0 && ThreadLocalRandom.current().nextDouble() < cfg.seedChance) {
            ItemStack seeds = randomSeeds(level, pos);
            if (seeds != null) Coast.drop(level, Vec3.atCenterOf(pos).add(0, -0.2, 0), seeds);
            return;
        }
        BlockState young = null;
        net.minecraft.world.item.Item seed = null;
        if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state) && VANILLA_SEEDS.containsKey(state.getBlock())) {
            young = crop.getStateForAge(0);
            seed = VANILLA_SEEDS.get(state.getBlock());
        } else if (state.is(Blocks.NETHER_WART) && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE) {
            young = Blocks.NETHER_WART.defaultBlockState();
            seed = Items.NETHER_WART;
        }
        if (young == null || seed == null) return;
        Entity pip = canopip(p);
        if (pip == null) return;
        // The drops are on the ground now: one seed goes back in.
        boolean taken = false;
        for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(0.75))) {
            ItemStack s = it.getItem();
            if (!s.is(seed) || Coast.id(s) != null || it.tickCount > 1) continue;
            s.shrink(1);
            if (s.isEmpty()) it.discard();
            else it.setItem(s);
            taken = true;
            break;
        }
        if (!taken) return;
        signal(pip, p, "CANOPIP_REPLANT");
        BlockState plant = young;
        Tasks.later(1, () -> {
            if (!level.getBlockState(pos).isAir()) return;
            level.setBlock(pos, plant, Block.UPDATE_ALL);
            Vec3 at = Vec3.atCenterOf(pos).add(0, -0.2, 0);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z, 5, 0.2, 0.1, 0.2, 0);
        });
    }

    // ================================================================== meals

    /** Content ids of the meals the running farming module cooks and buffs (empty while it is off). */
    private static volatile Set<String> buffedMeals = Set.of();

    /** True when the farming module is on and gives this meal's buffs on eating (wildlife checks it). */
    public static boolean buffsMeal(String id) {
        return id != null && buffedMeals.contains(id);
    }

    void applyEffect(ServerPlayer p, MobEffectInstance e) {
        p.addEffect(e);
    }

    // ================================================================== furniture: compost bins, stations, seaweed lines

    private boolean furnitureClick(FurnitureContext ctx) {
        ServerPlayer p = ctx.player();
        String id = ctx.id();
        ItemStack held = p.getMainHandItem();
        if (Coast.matches(cfg.binIds, id)) return bins.click(p, ctx, held);
        if (id.equals(cfg.lineId)) return seaweed.click(p, ctx, held);
        String station = cfg.stationOf(id);
        if (station != null) return cooking.click(p, ctx, station);
        return false;
    }

    // ================================================================== Harvest Fair API

    /** Enters the Prize crop in the player's main hand in this week's Harvest Fair (grocer counter nearby). */
    public boolean submitFair(ServerPlayer player) {
        return fair.submit(player);
    }

    // ================================================================== /farming

    @Override
    public boolean onCommand(Sender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "fair" -> {
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (what.equals("submit")) {
                    if (sender.isPlayer()) submitFair(sender.player());
                    else plugin.text().send(sender, "general.players-only");
                } else if (what.equals("judge")) {
                    if (!sender.hasPermission(ADMIN)) {
                        plugin.text().send(sender, "general.no-permission");
                        return true;
                    }
                    fair.judge();
                } else {
                    fair.show(sender);
                }
            }
            case "debug" -> {
                if (!sender.hasPermission(ADMIN)) {
                    plugin.text().send(sender, "general.no-permission");
                    return true;
                }
                debug(sender, args);
            }
            default -> send(sender, "help");
        }
        return true;
    }

    private void debug(Sender sender, String[] args) {
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        ServerPlayer p = sender.isPlayer() ? sender.player() : null;
        ServerLevel level = p != null ? p.level() : Feather.server().overworld();
        BlockPos at = p != null ? p.blockPosition() : null;
        int radius = 16;
        switch (what) {
            case "grow", "ripen", "force" -> {
                if (args.length > 2) radius = parse(args[2], 16);
                if (args.length > 4) at = new BlockPos(parse(args[3], 0), at == null ? 64 : at.getY(), parse(args[4], 0));
                if (at == null) return;
                int n = field.forceGrow(level, at.getX(), at.getZ(), radius, !what.equals("grow"), what.equals("force"));
                n += seaweed.forceGrow(level, at, radius, !what.equals("grow"));
                sender.sendMessage("FARMING grew " + n);
            }
            case "skew" -> {
                if (at == null || args.length < 3) return;
                long ms = parse(args[2], 0) * 60_000L;
                if (args.length > 3) radius = parse(args[3], 16);
                sender.sendMessage("FARMING skewed " + field.skew(level, at.getX(), at.getZ(), radius, ms));
            }
            case "forget" -> {
                if (args.length > 2) radius = parse(args[2], 16);
                if (args.length > 4) at = new BlockPos(parse(args[3], 0), at == null ? 64 : at.getY(), parse(args[4], 0));
                if (at == null) return;
                sender.sendMessage("FARMING forgot " + field.forget(level, at.getX(), at.getZ(), radius));
            }
            case "clock" -> {
                clockOffset += (args.length > 2 ? parse(args[2], 0) : 0) * 60_000L;
                sender.sendMessage("FARMING clock +" + (clockOffset / 60_000L) + " min");
            }
            case "tick" -> {
                field.tick();
                seaweed.tick();
                sender.sendMessage("FARMING ticked");
            }
            case "quality" -> {
                forcedQuality = args.length > 2 && List.of(QUALITIES).contains(args[2].toLowerCase(Locale.ROOT)) ? args[2].toLowerCase(Locale.ROOT) : null;
                sender.sendMessage("FARMING quality " + (forcedQuality == null ? "random" : forcedQuality));
            }
            case "plant" -> {
                // plant <crop> <x> <y> <z> [stage]: test setup (farmland under it is made)
                if (args.length < 6) return;
                FarmConfig.CropDef def = cfg.crops.get(args[2].toLowerCase(Locale.ROOT));
                if (def == null) {
                    sender.sendMessage("FARMING unknown crop " + args[2]);
                    return;
                }
                BlockPos b = new BlockPos(parse(args[3], 0), parse(args[4], 0), parse(args[5], 0));
                int stage = args.length > 6 ? Math.max(0, Math.min(3, parse(args[6], 0))) : 0;
                level.setBlock(b.below(), Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmlandBlock.MOISTURE, 7), Block.UPDATE_ALL);
                BlockState st = ArcanaContent.stateFor(def.stageId(stage));
                if (st == null) {
                    sender.sendMessage("FARMING no crop block for " + def.id);
                    return;
                }
                level.setBlock(b, st, Block.UPDATE_ALL);
                field.add(level, b, def, stage, stage >= 3 ? Long.MAX_VALUE : now() + stageMs(level, b, def));
                sender.sendMessage("FARMING planted " + def.id + " stage=" + stage + " @" + b.toShortString());
            }
            case "info" -> {
                BlockPos b;
                if (args.length > 4) b = new BlockPos(parse(args[2], 0), parse(args[3], 0), parse(args[4], 0));
                else if (p != null) {
                    HitResult hr = p.pick(6, 1f, false);
                    b = hr instanceof BlockHitResult bh ? bh.getBlockPos() : null;
                } else b = null;
                if (b == null) return;
                CropField.Crop c = field.get(level, b);
                if (c == null && level.getBlockState(b).is(Blocks.FARMLAND)) c = field.get(level, b.above());
                if (c == null) {
                    sender.sendMessage("FARMING none at " + b.toShortString() + " block=" + level.getBlockState(b).getBlock()
                            + " id=" + ArcanaContent.id(level.getBlockState(b)) + " compost=" + field.compost(level, b));
                    return;
                }
                BlockPos cb = c.pos();
                stageState(level, cb, c);
                String why = whyNot(level, cb, c.def);
                sender.sendMessage("FARMING crop " + c.def.id + " stage=" + c.stage + " id=" + ArcanaContent.id(level.getBlockState(cb)) + " watered=" + c.watered
                        + " dueIn=" + (c.next == Long.MAX_VALUE ? "ripe" : ((c.next - now()) / 1000) + "s") + " why=" + (why == null ? "ok" : why)
                        + " boosted=" + boosted(level, cb, c.def) + " biome=" + Coast.biomeKey(level, cb) + " compost=" + field.compost(level, cb.below())
                        + " stageMs=" + stageMs(level, cb, c.def) + " sporeling=" + sporelingNear(level, cb));
            }
            case "count" -> sender.sendMessage("FARMING loaded crops=" + field.loadedCount() + " chunks=" + field.loadedChunks()
                    + " lines=" + seaweed.count());
            case "fair" -> sender.sendMessage("FARMING fair next=" + fair.nextJudge() + " in=" + ((fair.nextJudge() - System.currentTimeMillis()) / 60000) + "min");
            case "line" -> {
                if (p == null) return;
                sender.sendMessage("FARMING " + seaweed.describeNear(level, p.blockPosition()));
            }
            case "bin" -> {
                if (p == null) return;
                sender.sendMessage("FARMING " + bins.describeNear(level, p.blockPosition()));
            }
            case "place" -> {
                // place <furniture id> <x> <y> <z>: test setup (furniture placed by code)
                if (args.length < 6) return;
                BlockPos b = new BlockPos(parse(args[3], 0), parse(args[4], 0), parse(args[5], 0));
                boolean ok = ArcanaContent.place(level, b, args[2], Direction.NORTH);
                if (ok && cfg.lineId.equals(args[2])) seaweed.placed(level, b);
                sender.sendMessage("FARMING placed " + ok);
            }
            default -> sender.sendMessage("FARMING debug grow|ripen|force [r] | skew <min> [r] | clock <min> | tick | quality <q|random> | plant <crop> x y z [stage] | info [x y z] | count | fair | line | bin | place <id> x y z");
        }
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(Sender sender, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("fair");
            if (sender.hasPermission(ADMIN)) out.add("debug");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("fair")) {
            out.add("submit");
            if (sender.hasPermission(ADMIN)) out.add("judge");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission(ADMIN)) {
            out.addAll(List.of("grow", "ripen", "force", "skew", "clock", "tick", "quality", "plant", "info", "count", "fair", "line", "bin", "place"));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
