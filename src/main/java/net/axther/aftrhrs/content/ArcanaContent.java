package net.axther.aftrhrs.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.food.Foods;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.PushReaction;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Every ex-Oraxen item, block and piece of furniture, registered from the generated content table
 * ({@code data/aftrhrs/content/content.json}, written by {@code mod/tools/gen_content.py}). Ids are the Oraxen ids
 * in the {@code aftrhrs} namespace ({@code aftrhrs:arcana_goob_coin}).
 *
 * <p>API for other modules: {@link #item}, {@link #stack}, {@link #block}, {@link #stateFor}, {@link #id(ItemStack)},
 * {@link #id(BlockState)}, {@link #isFurniture}, {@link #origin}, {@link #entry}, and {@link #hook} to claim
 * right-click / break of specific furniture.
 */
public final class ArcanaContent {
    public static final String TABLE = "/data/aftrhrs/content/content.json";
    /** Event phase after the default one (claims and other vetoes run first). */
    static final Identifier LATE = AftrHrs.id("content_late");

    /**
     * One registered entry: what got registered plus the few table fields read after registration (the rest of the
     * table is dropped once init is done; {@link #readTable()} parses it again for tools and tests).
     */
    public record Content(String id, String kind, @Nullable Item item, @Nullable Block block,
                          boolean hidden, @Nullable String tab, @Nullable String base,
                          @Nullable JsonObject cropStage, @Nullable JsonObject alias,
                          @Nullable JsonObject mechanics, @Nullable JsonElement glowing) {
        static Content of(String id, String kind, JsonObject e, @Nullable Item item, @Nullable Block block) {
            // child JsonObjects do not reference their parent: keeping them does not keep the table alive
            JsonElement t = e.get("tab");
            return new Content(id, kind, item, block,
                    e.has("hidden") && e.get("hidden").getAsBoolean(),
                    t == null || t.isJsonNull() ? null : t.getAsString(),
                    e.has("base") ? e.get("base").getAsString() : null,
                    e.has("crop_stage") ? e.getAsJsonObject("crop_stage") : null,
                    e.has("alias") ? e.getAsJsonObject("alias") : null,
                    e.has("mechanics") ? e.getAsJsonObject("mechanics") : null,
                    e.get("glowing"));
        }
    }

    /** A module block entity and the sides that tick it (see {@link #blockEntity}). */
    private record ModuleEntity(Predicate<String> ids,
            net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier<? extends net.minecraft.world.level.block.entity.BlockEntity> factory,
            boolean serverTicks, boolean clientTicks) {
    }

    private static final Map<String, Content> ENTRIES = new LinkedHashMap<>();
    private static final List<Map.Entry<Predicate<String>, FurnitureHook>> HOOKS = new ArrayList<>();
    private static final Map<String, ResourceKey<CreativeModeTab>> TABS = new LinkedHashMap<>();
    private static final List<ModuleEntity> MODULE_BE = new ArrayList<>();
    public static BlockEntityType<FurnitureStorageBlockEntity> STORAGE_BE;
    public static BlockEntityType<GramophoneBlockEntity> GRAMOPHONE_BE;
    private static boolean initialized;

    private ArcanaContent() {
    }

    // ================================================================================================== API
    /** The item registered for an Oraxen id ({@code arcana_oak_chair}, also accepts {@code oraxen:}/{@code aftrhrs:} prefixes). */
    public static @Nullable Item item(String oraxenId) {
        Content c = ENTRIES.get(strip(oraxenId));
        return c == null ? null : c.item();
    }

    /** A stack of the item for an Oraxen id, or EMPTY when unknown. */
    public static ItemStack stack(String oraxenId, int count) {
        Item i = item(oraxenId);
        return i == null ? ItemStack.EMPTY : new ItemStack(i, count);
    }

    /** The block for an id (furniture, blocks, crops), null for plain items. */
    public static @Nullable Block block(String oraxenId) {
        Content c = ENTRIES.get(strip(oraxenId));
        return c == null ? null : c.block();
    }

    /**
     * The block state an Oraxen id stands for: furniture/blocks -> default state, crop stage ids
     * ({@code arcana_crop_rice_stage2}) -> the crop block at that age, lit hearth ids -> the hearth with lit=true.
     */
    public static @Nullable BlockState stateFor(String oraxenId) {
        Content c = ENTRIES.get(strip(oraxenId));
        if (c == null) return null;
        if (c.cropStage() != null) {
            JsonObject cs = c.cropStage();
            Block b = block(cs.get("block").getAsString());
            if (b instanceof ArcanaCropBlock crop) return b.defaultBlockState().setValue(crop.age, Math.min(crop.stages - 1, cs.get("age").getAsInt()));
            return null;
        }
        if (c.alias() != null) {
            JsonObject a = c.alias();
            Block b = block(a.get("block").getAsString());
            if (b == null) return null;
            BlockState s = b.defaultBlockState();
            if (s.hasProperty(FurnitureBlock.LIT) && a.getAsJsonObject("state").has("lit")) {
                s = s.setValue(FurnitureBlock.LIT, a.getAsJsonObject("state").get("lit").getAsBoolean());
            }
            return s;
        }
        return c.block() == null ? null : c.block().defaultBlockState();
    }

    /** The Oraxen-style id of a stack ({@code arcana_oak_chair}), or null for anything that is not content. */
    public static @Nullable String id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return AftrHrs.ID.equals(key.getNamespace()) && ENTRIES.containsKey(key.getPath()) ? key.getPath() : null;
    }

    /** The Oraxen-style id of a placed content block (any part of a multi-block piece), or null. */
    public static @Nullable String id(BlockState state) {
        if (state == null) return null;
        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return AftrHrs.ID.equals(key.getNamespace()) && ENTRIES.containsKey(key.getPath()) ? key.getPath() : null;
    }

    public static boolean isFurniture(BlockState state) {
        return state != null && state.getBlock() instanceof FurnitureBlock;
    }

    /** The origin (part 0) of the furniture piece at pos, or pos itself. */
    public static BlockPos origin(BlockState state, BlockPos pos) {
        return state.getBlock() instanceof FurnitureBlock fb ? fb.origin(state, pos) : pos;
    }

    /** The furniture spec of an id, null if it is not furniture. */
    public static @Nullable FurnitureSpec spec(String oraxenId) {
        return block(oraxenId) instanceof FurnitureBlock fb ? fb.spec : null;
    }

    /** The raw content table entry for an id (kind, components, furniture data...). */
    public static @Nullable Content entry(String oraxenId) {
        return ENTRIES.get(strip(oraxenId));
    }

    public static Map<String, Content> entries() {
        return Collections.unmodifiableMap(ENTRIES);
    }

    /**
     * Places furniture {@code oraxenId} at origin facing {@code front} (plus its parts), like a player would.
     * Returns false if a part cell is not free. Used by migration and modules that spawn furniture.
     */
    public static boolean place(ServerLevel level, BlockPos origin, String oraxenId, net.minecraft.core.Direction front) {
        BlockState s = stateFor(oraxenId);
        if (s == null) return false;
        if (s.hasProperty(FurnitureBlock.FACING) && front.getAxis().isHorizontal()) s = s.setValue(FurnitureBlock.FACING, front);
        if (s.hasProperty(FurnitureBlock.FACING_ALL)) s = s.setValue(FurnitureBlock.FACING_ALL, front);
        if (s.hasProperty(FurnitureBlock.ROTATION) && front.getAxis().isHorizontal()) {
            s = s.setValue(FurnitureBlock.ROTATION, (int) (front.toYRot() / 22.5f + 8) & 15);
        }
        return placeState(level, origin, s);
    }

    /** Places a content block state at origin, plus its furniture parts. False if a part cell is not free. */
    public static boolean placeState(ServerLevel level, BlockPos origin, BlockState s) {
        if (s.getBlock() instanceof FurnitureBlock fb && fb.spec.multiPart()) {
            List<BlockPos> ps = fb.partPositions(s, origin);
            for (int i = 1; i < ps.size(); i++) if (!level.getBlockState(ps.get(i)).canBeReplaced()) return false;
            level.setBlock(origin, s, 3);
            for (int i = 1; i < ps.size(); i++) level.setBlock(ps.get(i), s.setValue(fb.part, i), 3);
            return true;
        }
        return level.setBlock(origin, s, 3);
    }

    /**
     * Swaps the furniture piece at pos (any part) for another id in the same spot and orientation, without drops
     * (copper waxing/scraping/aging, stage swaps). Keeps lit/open/waterlogged where both pieces have them.
     */
    public static boolean replace(ServerLevel level, BlockPos pos, String newId) {
        BlockState old = level.getBlockState(pos);
        if (!(old.getBlock() instanceof FurnitureBlock ofb)) return false;
        BlockState target = stateFor(newId);
        if (target == null) return false;
        BlockPos origin = ofb.origin(old, pos);
        BlockState os = level.getBlockState(origin);
        for (Property<?> p : List.<Property<?>>of(FurnitureBlock.FACING, FurnitureBlock.FACING_ALL, FurnitureBlock.ROTATION,
                FurnitureBlock.LIT, FurnitureBlock.OPEN, FurnitureBlock.WATERLOGGED)) {
            target = copy(os, target, p);
        }
        for (BlockPos p : ofb.partPositions(os, origin)) {
            BlockState cur = level.getBlockState(p);
            if (cur.is(ofb)) level.setBlock(p, FurnitureBlock.fluidOrAir(cur), 2 | 16 | 32);
        }
        return placeState(level, origin, target);
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> p) {
        return from.hasProperty(p) && to.hasProperty(p) ? to.setValue(p, from.getValue(p)) : to;
    }

    /** Claims interactions with one furniture/content id. */
    public static void hook(String oraxenId, FurnitureHook hook) {
        String id = strip(oraxenId);
        hook(id::equals, hook);
    }

    /** Claims interactions with every content id matching the filter. Hooks run in registration order. */
    public static synchronized void hook(Predicate<String> ids, FurnitureHook hook) {
        HOOKS.add(Map.entry(ids, hook));
    }

    /**
     * Gives the furniture pieces whose id matches {@code ids} a module block entity (curios, gadgets, power...): the
     * piece's origin gets {@code factory.create(pos, state)}. Must be called before {@link #init()} (blocks are built
     * there); register the matching {@code BlockEntityType} afterwards with {@link #blocks(Predicate)}. A block entity
     * implementing {@link FurnitureTicker} is ticked on both sides. Storage / gramophone pieces keep their own block
     * entity.
     */
    public static void blockEntity(Predicate<String> ids,
            net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier<? extends net.minecraft.world.level.block.entity.BlockEntity> factory) {
        blockEntity(ids, factory, true, true);
    }

    /**
     * Like {@link #blockEntity(Predicate, BlockEntityType.BlockEntitySupplier)}, with the sides that tick the block
     * entity: a side whose flag is false gets no ticker at all (one that only stores data, or only renders). A module
     * that later gives its block entity a tick must turn its flag on.
     */
    public static synchronized void blockEntity(Predicate<String> ids,
            net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier<? extends net.minecraft.world.level.block.entity.BlockEntity> factory,
            boolean serverTicks, boolean clientTicks) {
        if (initialized) throw new IllegalStateException("ArcanaContent.blockEntity after init");
        MODULE_BE.add(new ModuleEntity(ids, factory, serverTicks, clientTicks));
    }

    static net.minecraft.world.level.block.entity.BlockEntityType.@Nullable BlockEntitySupplier<? extends net.minecraft.world.level.block.entity.BlockEntity> moduleEntity(String id) {
        for (var e : MODULE_BE) if (e.ids().test(id)) return e.factory();
        return null;
    }

    /** The sides that tick the module block entity of an id: bit 1 server, bit 2 client (0 without one). */
    static byte moduleEntityTicks(String id) {
        for (var e : MODULE_BE) if (e.ids().test(id)) return (byte) ((e.serverTicks() ? 1 : 0) | (e.clientTicks() ? 2 : 0));
        return 0;
    }

    /** Every registered content block whose id matches (for a module's BlockEntityType). */
    public static Block[] blocks(Predicate<String> ids) {
        List<Block> out = new ArrayList<>();
        for (Content c : ENTRIES.values()) if (c.block() != null && !"alias".equals(c.kind()) && ids.test(c.id())) out.add(c.block());
        return out.toArray(new Block[0]);
    }

    public static @Nullable ResourceKey<CreativeModeTab> tab(String oraxenFile) {
        return TABS.get(oraxenFile);
    }

    private static String strip(String id) {
        if (id == null) return "";
        int i = id.indexOf(':');
        return (i >= 0 ? id.substring(i + 1) : id).toLowerCase(java.util.Locale.ROOT);
    }

    static String idOf(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b).getPath();
    }

    static InteractionResult fireUse(FurnitureContext ctx) {
        for (Map.Entry<Predicate<String>, FurnitureHook> h : HOOKS) {
            if (!h.getKey().test(ctx.id())) continue;
            try {
                InteractionResult r = h.getValue().onUse(ctx);
                if (r != null && r != InteractionResult.PASS) return r;
            } catch (RuntimeException ex) {
                AftrHrs.LOG.error("Furniture hook failed on use of {}", ctx.id(), ex);
            }
        }
        return InteractionResult.PASS;
    }

    static void firePlaced(FurnitureContext ctx) {
        for (Map.Entry<Predicate<String>, FurnitureHook> h : HOOKS) {
            if (!h.getKey().test(ctx.id())) continue;
            try {
                h.getValue().onPlaced(ctx);
            } catch (RuntimeException ex) {
                AftrHrs.LOG.error("Furniture hook failed on place of {}", ctx.id(), ex);
            }
        }
    }

    static void fireRemoved(FurnitureContext ctx) {
        for (Map.Entry<Predicate<String>, FurnitureHook> h : HOOKS) {
            if (!h.getKey().test(ctx.id())) continue;
            try {
                h.getValue().onRemoved(ctx);
            } catch (RuntimeException ex) {
                AftrHrs.LOG.error("Furniture hook failed on removal of {}", ctx.id(), ex);
            }
        }
    }

    private static boolean fireBreak(FurnitureContext ctx) {
        for (Map.Entry<Predicate<String>, FurnitureHook> h : HOOKS) {
            if (!h.getKey().test(ctx.id())) continue;
            try {
                if (!h.getValue().onBreak(ctx)) return false;
            } catch (RuntimeException ex) {
                AftrHrs.LOG.error("Furniture hook failed on break of {}", ctx.id(), ex);
            }
        }
        return true;
    }

    // ================================================================================================== init
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        JsonObject table = readTable();
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        List<JsonObject> aliases = new ArrayList<>();
        List<Block> storage = new ArrayList<>(), gramophones = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonElement el : table.getAsJsonArray("entries")) {
            JsonObject e = el.getAsJsonObject();
            String id = e.get("id").getAsString();
            String kind = e.get("kind").getAsString();
            if (kind.equals("alias")) {
                aliases.add(e);
                continue;
            }
            try {
                Content c = register(id, kind, e, ops);
                ENTRIES.put(id, c);
                counts.merge(kind, 1, Integer::sum);
                if (c.block() instanceof FurnitureBlock fb) {
                    if (fb.spec.jukebox) gramophones.add(fb);
                    else if (fb.spec.hasBlockEntity()) storage.add(fb);
                }
            } catch (RuntimeException ex) {
                AftrHrs.LOG.error("Content: could not register {} ({})", id, kind, ex);
            }
        }
        for (JsonObject e : aliases) {
            String id = e.get("id").getAsString();
            JsonObject a = e.getAsJsonObject("alias");
            Block base = block(a.get("block").getAsString());
            if (base == null) {
                AftrHrs.LOG.error("Content: alias {} points at unknown block {}", id, a.get("block").getAsString());
                continue;
            }
            Item.Properties p = itemProps(id, e, ops);
            BlockItemStateProperties bs = BlockItemStateProperties.EMPTY;
            if (base.defaultBlockState().hasProperty(FurnitureBlock.LIT) && a.getAsJsonObject("state").has("lit")) {
                bs = bs.with(FurnitureBlock.LIT, a.getAsJsonObject("state").get("lit").getAsBoolean());
            }
            p.component(DataComponents.BLOCK_STATE, bs);
            Item item = Registry.register(BuiltInRegistries.ITEM, itemKey(id), new ArcanaVanillaItems.ContentBlockItem(base, p, e));
            ENTRIES.put(id, Content.of(id, "alias", e, item, base));
            counts.merge("alias", 1, Integer::sum);
        }
        STORAGE_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("furniture_storage"),
                FabricBlockEntityTypeBuilder.<FurnitureStorageBlockEntity>create(FurnitureStorageBlockEntity::new, storage.toArray(new Block[0])).build());
        GRAMOPHONE_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("gramophone"),
                FabricBlockEntityTypeBuilder.<GramophoneBlockEntity>create(GramophoneBlockEntity::new, gramophones.toArray(new Block[0])).build());
        registerTabs(table.getAsJsonArray("tabs"));
        Seats.init();
        // Late phases: claims, locks, restraint... (default phase) decide first; content hooks only see allowed actions
        // (registered at mod init, the hooks used to run before the claim check, so a filled jar or crop could be
        // taken out of someone else's claim).
        PlayerBlockBreakEvents.BEFORE.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, LATE);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, LATE);
        // Sneak + item: vanilla skips the block entirely (suppressUsingBlock), so hooks that use it (doormat hide, jar
        // sneak-fill, pedestal) never ran. Offer the click to the hooks here; PASS lets vanilla place the held block.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(LATE, (player, level, hand, hit) -> {
            if (HOOKS.isEmpty() || !(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp) || player.isSpectator()) return InteractionResult.PASS;
            if (!player.isSecondaryUseActive() || (player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty())) return InteractionResult.PASS;
            BlockState state = level.getBlockState(hit.getBlockPos());
            if (!(state.getBlock() instanceof FurnitureBlock fb)) return InteractionResult.PASS;
            BlockPos origin = fb.origin(state, hit.getBlockPos());
            BlockState os = level.getBlockState(origin);
            if (!os.is(fb)) os = state;
            return fireUse(new FurnitureContext(idOf(fb), sl, origin, os, hit.getBlockPos(), sp, hand, player.getItemInHand(hand), hit));
        });
        PlayerBlockBreakEvents.BEFORE.register(LATE, (level, player, pos, state, be) -> {
            if (HOOKS.isEmpty() || !(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) return true;
            String id = id(state);
            if (id == null) return true;
            BlockPos origin = origin(state, pos);
            BlockState os = level.getBlockState(origin);
            return fireBreak(new FurnitureContext(id, sl, origin, os.is(state.getBlock()) ? os : state, pos, sp,
                    InteractionHand.MAIN_HAND, sp.getMainHandItem(), null));
        });
        AftrHrs.LOG.info("Content: registered {} entries {}", ENTRIES.size(), counts);
    }

    /**
     * The content table, parsed from the jar again on every call (init keeps only what it needs; tools and tests that
     * want the raw entries read it here).
     */
    public static JsonObject readTable() {
        try (InputStream in = ArcanaContent.class.getResourceAsStream(TABLE)) {
            if (in == null) throw new IllegalStateException("missing " + TABLE + " (run mod/tools/gen_content.py)");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + TABLE, e);
        }
    }

    private static ResourceKey<Item> itemKey(String id) {
        return ResourceKey.create(Registries.ITEM, AftrHrs.id(id));
    }

    private static ResourceKey<Block> blockKey(String id) {
        return ResourceKey.create(Registries.BLOCK, AftrHrs.id(id));
    }

    private static Content register(String id, String kind, JsonObject e, DynamicOps<JsonElement> ops) {
        switch (kind) {
            case "furniture" -> {
                FurnitureSpec spec = new FurnitureSpec(id, e.getAsJsonObject("furniture"));
                BlockBehaviour.Properties p = BlockBehaviour.Properties.of().setId(blockKey(id))
                        .noOcclusion().strength(0.8f, 1200f).pushReaction(PushReaction.IMMOVEABLE)
                        .sound(soundType(spec.sounds, SoundType.WOOD))
                        .lightLevel(s -> FurnitureBlock.lightOf(spec, s))
                        .isSuffocating((s, l, pos) -> false)
                        .isRedstoneConductor((s, l, pos) -> false);
                if (spec.stages != null) p.randomTicks();
                FurnitureBlock block = Registry.register(BuiltInRegistries.BLOCK, blockKey(id), FurnitureBlock.create(spec, p));
                return Content.of(id, kind, e, blockItem(id, block, e, ops), block);
            }
            case "block", "stairs", "slab", "wall" -> {
                JsonObject b = e.getAsJsonObject("block");
                float hardness = b.get("hardness").getAsFloat();
                int light = b.get("light").getAsInt();
                BlockBehaviour.Properties p = BlockBehaviour.Properties.of().setId(blockKey(id))
                        .strength(hardness, Math.max(hardness, 1f) * 3f)
                        .sound(soundType(b.getAsJsonObject("sounds"), SoundType.STONE))
                        .lightLevel(s -> light);
                if (b.get("requires_tool").getAsBoolean()) p.requiresCorrectToolForDrops();
                // Optional extras (in-repo block sets): slipperiness (ice is 0.98 / blue ice 0.989) and map color.
                if (b.has("friction")) p.friction(b.get("friction").getAsFloat());
                if (b.has("map_color")) p.mapColor(mapColor(b.get("map_color").getAsString()));
                Block block = switch (kind) {
                    case "stairs" -> new StairBlock(Blocks.STONE.defaultBlockState(), p);
                    case "slab" -> new SlabBlock(p);
                    case "wall" -> new net.minecraft.world.level.block.WallBlock(p.forceSolidOn());
                    default -> new Block(p);
                };
                block = Registry.register(BuiltInRegistries.BLOCK, blockKey(id), block);
                return Content.of(id, kind, e, blockItem(id, block, e, ops), block);
            }
            case "crop" -> {
                JsonObject c = e.getAsJsonObject("crop");
                BlockBehaviour.Properties p = BlockBehaviour.Properties.of().setId(blockKey(id)).noCollision().instabreak()
                        .noOcclusion().pushReaction(PushReaction.POPPED).sound(soundType(c.getAsJsonObject("sounds"), SoundType.CROP));
                String seeds = c.has("seeds") && !c.get("seeds").isJsonNull() ? c.get("seeds").getAsString() : null;
                Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey(id),
                        ArcanaCropBlock.create(c.get("stages").getAsInt(), c.getAsJsonArray("shapes"), seeds, p));
                return Content.of(id, kind, e, null, block);
            }
            default -> {
                Item.Properties p = itemProps(id, e, ops);
                String base = e.has("base") ? e.get("base").getAsString() : "PAPER";
                Item item = switch (base) {
                    case "FISHING_ROD" -> new ArcanaVanillaItems.RodItem(p, e);
                    case "TRIDENT" -> new ArcanaVanillaItems.SpearItem(p, e);
                    case "CARROT_ON_A_STICK" -> new ArcanaVanillaItems.StickItem(p, e);
                    default -> new ArcanaItem(p, e);
                };
                item = Registry.register(BuiltInRegistries.ITEM, itemKey(id), item);
                return Content.of(id, kind, e, item, null);
            }
        }
    }

    private static Item blockItem(String id, Block block, JsonObject e, DynamicOps<JsonElement> ops) {
        Item item = new ArcanaVanillaItems.ContentBlockItem(block, itemProps(id, e, ops), e);
        item = Registry.register(BuiltInRegistries.ITEM, itemKey(id), item);
        Item.BY_BLOCK.put(block, item);
        return item;
    }

    // ------------------------------------------------------------------------------------------------ items
    private static Item.Properties itemProps(String id, JsonObject e, DynamicOps<JsonElement> ops) {
        Item.Properties p = new Item.Properties().setId(itemKey(id));
        baseMaterial(p, e.has("base") ? e.get("base").getAsString() : "PAPER");
        if (e.has("components")) {
            for (Map.Entry<String, JsonElement> c : e.getAsJsonObject("components").entrySet()) {
                DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(c.getKey()));
                if (type == null || type.codec() == null) {
                    AftrHrs.LOG.warn("Content {}: unknown component {}", id, c.getKey());
                    continue;
                }
                put(p, type, c.getValue(), ops, id);
            }
        }
        if (e.has("fire_resistant")) p.fireResistant();
        // Like fireResistant, for a wider damage-type tag (Goob Coins: cactus, explosions, lightning...). Delayed like
        // fireResistant: the tag only exists once the registries load, long after items register.
        if (e.has("damage_resistant")) {
            var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, Identifier.parse(e.get("damage_resistant").getAsString()));
            p.delayedComponent(DataComponents.DAMAGE_RESISTANT, reg -> new net.minecraft.world.item.component.DamageResistant(reg.getOrThrow(tag)));
        }
        if (e.has("lore") && !e.getAsJsonArray("lore").isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (JsonElement l : e.getAsJsonArray("lore")) lines.add(Texts.of(l.getAsJsonObject()));
            p.component(DataComponents.LORE, new ItemLore(lines));
        }
        return p;
    }

    private static <T> void put(Item.Properties p, DataComponentType<T> type, JsonElement json, DynamicOps<JsonElement> ops, String id) {
        Codec<T> codec = type.codec();
        codec.parse(ops, json).resultOrPartial(err -> AftrHrs.LOG.warn("Content {}: component {}: {}", id, type, err))
                .ifPresent(v -> p.component(type, v));
    }

    /** Vanilla behavior of the Oraxen item's base material (tools, rods, fish...). */
    private static void baseMaterial(Item.Properties p, String base) {
        switch (base) {
            case "COD" -> p.food(Foods.COD);
            case "FISHING_ROD" -> p.durability(64).enchantable(1);
            case "CARROT_ON_A_STICK" -> p.durability(25);
            case "TRIDENT" -> p.rarity(Rarity.RARE).durability(250).attributes(TridentItem.createAttributes())
                    .component(DataComponents.TOOL, TridentItem.createToolProperties()).enchantable(1)
                    .component(DataComponents.WEAPON, new Weapon(1));
            default -> {
                int us = base.indexOf('_');
                if (us < 0) return;
                String tier = base.substring(0, us), kind = base.substring(us + 1);
                ToolMaterial m = switch (tier) {
                    case "WOODEN" -> ToolMaterial.WOOD;
                    case "STONE" -> ToolMaterial.STONE;
                    case "COPPER" -> ToolMaterial.COPPER;
                    case "IRON" -> ToolMaterial.IRON;
                    case "GOLDEN" -> ToolMaterial.GOLD;
                    case "DIAMOND" -> ToolMaterial.DIAMOND;
                    case "NETHERITE" -> ToolMaterial.NETHERITE;
                    default -> null;
                };
                if (m == null) return;
                switch (kind) {
                    case "SWORD" -> p.sword(m, 3f, -2.4f);
                    case "SHOVEL" -> p.shovel(m, 1.5f, -3f);
                    case "PICKAXE" -> p.pickaxe(m, 1f, -2.8f);
                    case "AXE" -> p.axe(m, switch (tier) {
                        case "COPPER" -> 7f;
                        case "IRON", "GOLDEN" -> 6f;
                        default -> 5f;
                    }, switch (tier) {
                        case "COPPER" -> -3.2f;
                        case "IRON" -> -3.1f;
                        default -> -3f;
                    });
                    case "HOE" -> p.hoe(m, switch (tier) {
                        case "COPPER" -> -1f;
                        case "IRON" -> -2f;
                        case "DIAMOND" -> -3f;
                        case "NETHERITE" -> -4f;
                        default -> 0f;
                    }, switch (tier) {
                        case "COPPER" -> -2f;
                        case "IRON" -> -1f;
                        case "DIAMOND", "NETHERITE" -> 0f;
                        default -> -3f;
                    });
                    default -> {
                        return;
                    }
                }
                if (m == ToolMaterial.NETHERITE) p.fireResistant();
            }
        }
    }

    /** Map color by name for content blocks (the table's {@code block.map_color}); unknown names keep the default. */
    static net.minecraft.world.level.material.MapColor mapColor(String name) {
        return switch (name) {
            case "snow" -> net.minecraft.world.level.material.MapColor.SNOW;
            case "ice" -> net.minecraft.world.level.material.MapColor.ICE;
            case "terracotta_white" -> net.minecraft.world.level.material.MapColor.TERRACOTTA_WHITE;
            case "quartz" -> net.minecraft.world.level.material.MapColor.QUARTZ;
            case "stone" -> net.minecraft.world.level.material.MapColor.STONE;
            default -> net.minecraft.world.level.material.MapColor.NONE;
        };
    }

    // ------------------------------------------------------------------------------------------------ sounds
    static SoundType soundType(@Nullable JsonObject s, SoundType fallback) {
        if (s == null || s.isEmpty()) return fallback;
        return new SoundType(1f, 1f, ev(s, "break", fallback.getBreakSound()), ev(s, "step", fallback.getStepSound()),
                ev(s, "place", fallback.getPlaceSound()), ev(s, "hit", fallback.getHitSound()), ev(s, "fall", fallback.getFallSound()));
    }

    private static SoundEvent ev(JsonObject s, String k, SoundEvent def) {
        if (!s.has(k)) return def;
        Identifier id = Identifier.tryParse(s.get(k).getAsString());
        if (id == null) return def;
        SoundEvent e = BuiltInRegistries.SOUND_EVENT.getValue(id);
        return e != null ? e : SoundEvent.createVariableRangeEvent(id);
    }

    // ------------------------------------------------------------------------------------------------ tabs
    private static void registerTabs(JsonArray tabs) {
        Map<String, List<Item>> byTab = new LinkedHashMap<>();
        for (Content c : ENTRIES.values()) {
            if (c.item() == null || c.hidden()) continue;
            String tab = c.tab() == null ? "misc" : c.tab();
            byTab.computeIfAbsent(tab, k -> new ArrayList<>()).add(c.item());
        }
        List<JsonObject> defs = new ArrayList<>();
        for (JsonElement t : tabs) defs.add(t.getAsJsonObject());
        if (byTab.containsKey("misc")) {
            JsonObject misc = new JsonObject();
            misc.addProperty("key", "misc");
            misc.addProperty("name", "Aftr Hrs");
            misc.addProperty("color", "#b57cff");
            defs.add(misc);
        }
        for (JsonObject t : defs) {
            String key = t.get("key").getAsString();
            List<Item> items = byTab.get(key);
            if (items == null || items.isEmpty()) continue;
            Item icon = t.has("icon") ? item(t.get("icon").getAsString()) : null;
            Item iconItem = icon != null ? icon : items.getFirst();
            Component title = Component.translatable("itemGroup." + AftrHrs.ID + "." + key);
            if (t.has("color")) {
                var col = net.minecraft.network.chat.TextColor.parseColor(t.get("color").getAsString()).result().orElse(null);
                if (col != null) title = title.copy().withStyle(st -> st.withColor(col));
            }
            CreativeModeTab tab = FabricCreativeModeTab.builder().title(title)
                    .icon(() -> new ItemStack(iconItem))
                    .displayItems((params, out) -> items.forEach(out::accept))
                    .build();
            ResourceKey<CreativeModeTab> rk = ResourceKey.create(Registries.CREATIVE_MODE_TAB, AftrHrs.id(key));
            Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, rk, tab);
            TABS.put(key, rk);
        }
    }
}
