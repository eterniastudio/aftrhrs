package net.axther.aftrhrs.feature.smithing;

import com.mojang.serialization.Codec;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.feature.smithing.station.Station;
import net.axther.aftrhrs.feature.smithing.station.StationBlock;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Unit;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Anvil & Ember: hands-on modular blacksmithing (design: {@code mod/SMITHING.md}). This class owns the registrations:
 * the data components, one item per part type ({@code aftrhrs:part_<part>}) and per tool type
 * ({@code aftrhrs:smithed_<tool>}), the alloy ingots and nuggets, the hand tools and molds, the station blocks and
 * their block entities, and the Smithing creative tab. Definitions come from {@link SmithingDefs}, stats from
 * {@link ToolStats}, use-time effects from {@link Effects}.
 */
public final class Smithing {
    /** Sprite layers a tool can have (a tool type's distinct part layers). */
    public static final int LOOK_LAYERS = 4;
    /** Our alloys (vanilla has no ingot for them). */
    public static final List<String> ALLOY_METALS = List.of("steel", "rose_gold", "chime_bronze", "tidebronze", "rimesteel",
            "emberforged_steel", "pearlsteel", "echo_steel", "voidgold", "starforged");

    public static DataComponentType<String> MATERIAL;
    public static DataComponentType<PartInfo> PART;
    public static DataComponentType<ToolBuild> BUILD;
    @SuppressWarnings("unchecked")
    public static final DataComponentType<String>[] LOOK = new DataComponentType[LOOK_LAYERS];
    public static DataComponentType<Heat.Stamp> HEAT;
    public static DataComponentType<ItemStack> HELD;
    public static DataComponentType<Molten> LADLE;
    public static DataComponentType<String> MOLD;
    public static DataComponentType<String> CRYSTAL;
    public static DataComponentType<Integer> EDGE;
    public static DataComponentType<Unit> BROKEN;
    public static DataComponentType<Integer> FIRING;

    public static final Map<String, PartItem> PARTS = new LinkedHashMap<>();
    public static final Map<String, Item> TOOLS = new LinkedHashMap<>();
    public static final Map<String, MetalItem> INGOTS = new LinkedHashMap<>();
    public static final Map<String, MetalItem> NUGGETS = new LinkedHashMap<>();
    public static Item TONGS, KNIFE, CHISEL, STIRRING_ROD, LADLE_ITEM, MOLDING_BOX, FISH_OIL, SCRIBES_CRYSTAL, MODIFIER_CRYSTAL, ALMANAC,
            FIRE_BRICK, FIRE_CLAY, WHETSTONE;
    public static MoldItem SAND_MOLD, CLAY_MOLD_UNFIRED, CLAY_MOLD;
    public static Block FIRE_BRICKS;
    private static final Map<Station, StationBlock> BLOCKS = new EnumMap<>(Station.class);
    private static final Map<Station, BlockEntityType<BlockEntity>> BE_TYPES = new EnumMap<>(Station.class);
    public static ResourceKey<CreativeModeTab> TAB;

    private static boolean initialized;

    private Smithing() {
    }

    public static Identifier id(String path) {
        return AftrHrs.id(path);
    }

    private static <T> DataComponentType<T> component(String name, DataComponentType.Builder<T> b) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id(name), b.build());
    }

    private static <T extends Item> T item(String name, Function<Item.Properties, T> make) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
        return Registry.register(BuiltInRegistries.ITEM, key, make.apply(new Item.Properties().setId(key)));
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        SmithingDefs.load();
        MATERIAL = component("smith_material", DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
        PART = component("smith_part", DataComponentType.<PartInfo>builder().persistent(PartInfo.CODEC).networkSynchronized(PartInfo.STREAM_CODEC));
        BUILD = component("smith_build", DataComponentType.<ToolBuild>builder().persistent(ToolBuild.CODEC).networkSynchronized(ToolBuild.STREAM_CODEC).cacheEncoding());
        for (int i = 0; i < LOOK_LAYERS; i++) {
            LOOK[i] = component("smith_look_" + i, DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
        }
        HEAT = component("smith_heat", DataComponentType.<Heat.Stamp>builder().persistent(Heat.Stamp.CODEC).networkSynchronized(Heat.Stamp.STREAM_CODEC));
        HELD = component("smith_held", DataComponentType.<ItemStack>builder().persistent(ItemStack.OPTIONAL_CODEC).networkSynchronized(ItemStack.OPTIONAL_STREAM_CODEC));
        LADLE = component("smith_ladle", DataComponentType.<Molten>builder().persistent(Molten.CODEC).networkSynchronized(Molten.STREAM_CODEC));
        MOLD = component("smith_mold", DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
        CRYSTAL = component("smith_crystal", DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
        EDGE = component("smith_edge", DataComponentType.<Integer>builder().persistent(Codec.intRange(0, 100)).networkSynchronized(ByteBufCodecs.VAR_INT));
        BROKEN = component("smith_broken", DataComponentType.<Unit>builder().persistent(Unit.CODEC).networkSynchronized(Unit.STREAM_CODEC));
        FIRING = component("smith_firing", DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

        for (SmithingDefs.Part p : SmithingDefs.parts().values()) {
            PARTS.put(p.id(), item("part_" + p.id(), props -> new PartItem(p, props)));
        }
        for (SmithingDefs.ToolType t : SmithingDefs.tools().values()) {
            TOOLS.put(t.id(), item("smithed_" + t.id(), props -> toolItem(t, props.stacksTo(t.kind().equals("ammo") ? 64 : 1))));
        }
        for (String metal : ALLOY_METALS) {
            INGOTS.put(metal, item("ingot_" + metal, props -> new MetalItem(metal, false, props)));
            NUGGETS.put(metal, item("nugget_" + metal, props -> new MetalItem(metal, true, props)));
        }
        TONGS = item("smith_tongs", p -> new HandToolItem(HandToolItem.Kind.TONGS, p.stacksTo(1)));
        KNIFE = item("smith_knife", p -> new HandToolItem(HandToolItem.Kind.KNIFE, p.durability(250)));
        CHISEL = item("smith_chisel", p -> new HandToolItem(HandToolItem.Kind.CHISEL, p.durability(250)));
        STIRRING_ROD = item("stirring_rod", p -> new HandToolItem(HandToolItem.Kind.STIRRING_ROD, p.stacksTo(1)));
        LADLE_ITEM = item("smith_ladle", p -> new HandToolItem(HandToolItem.Kind.LADLE, p.stacksTo(1)));
        MOLDING_BOX = item("molding_box", Item::new);
        SAND_MOLD = item("sand_mold", p -> new MoldItem(MoldItem.Kind.SAND, p.stacksTo(16)));
        CLAY_MOLD_UNFIRED = item("clay_mold_unfired", p -> new MoldItem(MoldItem.Kind.CLAY_UNFIRED, p.stacksTo(16)));
        CLAY_MOLD = item("clay_mold", p -> new MoldItem(MoldItem.Kind.CLAY, p.durability(32)));
        FISH_OIL = item("fish_oil", p -> new Item(p.stacksTo(16).craftRemainder(Items.GLASS_BOTTLE)));
        SCRIBES_CRYSTAL = item("scribes_crystal", Item::new);
        MODIFIER_CRYSTAL = item("modifier_crystal", p -> new CrystalItem(p.stacksTo(16)));
        ALMANAC = item("smiths_almanac", p -> new AlmanacItem(p.stacksTo(1)));
        FIRE_CLAY = item("fire_clay", Item::new);
        FIRE_BRICK = item("fire_brick", Item::new);
        WHETSTONE = item("whetstone", Item::new);

        for (Station s : Station.values()) {
            ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id(s.id));
            StationBlock b = Registry.register(BuiltInRegistries.BLOCK, key, StationBlock.create(s, blockProps(s).setId(key)));
            BLOCKS.put(s, b);
            item(s.id, p -> new BlockItem(b, p.useBlockDescriptionPrefix()));
            if (s.hasBlockEntity()) {
                BE_TYPES.put(s, Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(s.id),
                        FabricBlockEntityTypeBuilder.<BlockEntity>create(s::newBlockEntity, b).build()));
            }
        }
        FIRE_BRICKS = BLOCKS.get(Station.FIRE_BRICKS);

        TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("smithing"));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB, FabricCreativeModeTab.builder()
                .title(Component.translatableWithFallback("itemGroup.aftrhrs.smithing", "Anvil & Ember").copy().withStyle(s -> s.withColor(0xE2702A)))
                .icon(() -> new ItemStack(BLOCKS.get(Station.ANVIL)))
                .displayItems((params, out) -> creativeItems(params.holders()).forEach(out::accept))
                .build());
        Effects.init();
        // vanilla skips a block's own click when the player sneaks holding something: stations still want those
        // (heavy strikes, whole stacks, choosing a part), so they get the click first
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!player.isSecondaryUseActive() || player.isSpectator()) return net.minecraft.world.InteractionResult.PASS;
            var state = level.getBlockState(hit.getBlockPos());
            if (!(state.getBlock() instanceof StationBlock sb)) return net.minecraft.world.InteractionResult.PASS;
            if (sb.station == Station.BELLOWS) return net.minecraft.world.InteractionResult.PASS;
            if (level.getBlockEntity(hit.getBlockPos()) instanceof net.axther.aftrhrs.feature.smithing.station.StationBE be) {
                return be.use(player, hand, hit);
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        Soulbound.init();
        SmithingCommands.register();
    }

    private static Item toolItem(SmithingDefs.ToolType t, Item.Properties p) {
        if (t.has("bow")) return new SmithedBowItem(t, p);
        if (t.has("crossbow")) return new SmithedCrossbowItem(t, p);
        if (t.has("fishing")) return new SmithedRodItem(t, p);
        if (t.kind().equals("ammo")) return new SmithedArrowItem(t, p);
        return new SmithedToolItem(t, p);
    }

    private static BlockBehaviour.Properties blockProps(Station s) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of().noOcclusion();
        return switch (s) {
            case FORGE -> p.mapColor(MapColor.STONE).strength(3.5f).requiresCorrectToolForDrops().sound(SoundType.DEEPSLATE_BRICKS)
                    .lightLevel(st -> st.getValue(StationBlock.COAL) == StationBlock.Coal.LIT ? 13 : 0);
            case BELLOWS, CARVING_BENCH, ASSEMBLY_BENCH, TROUGH -> p.mapColor(MapColor.WOOD).strength(2f).sound(SoundType.WOOD);
            case WHEEL -> p.mapColor(MapColor.WOOD).strength(2f).sound(SoundType.STONE);
            case CRUCIBLE -> p.mapColor(MapColor.TERRACOTTA_ORANGE).strength(2.5f).sound(SoundType.DECORATED_POT);
            case ANVIL, SPOUT -> p.mapColor(MapColor.METAL).strength(5f).requiresCorrectToolForDrops().sound(SoundType.ANVIL);
            case CASTING_BENCH -> p.mapColor(MapColor.STONE).strength(2.5f).requiresCorrectToolForDrops().sound(SoundType.STONE);
            case RUNE_TABLE -> p.mapColor(MapColor.COLOR_BLACK).strength(4f).requiresCorrectToolForDrops().sound(SoundType.POLISHED_DEEPSLATE)
                    .lightLevel(st -> st.getValue(StationBlock.ON) ? 7 : 0);
            case FIRE_BRICKS, GREAT_DRAIN -> BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).strength(3f)
                    .requiresCorrectToolForDrops().sound(SoundType.NETHER_BRICKS);
        };
    }

    // ------------------------------------------------------------------ lookups

    public static BlockEntityType<BlockEntity> beType(Station s) {
        BlockEntityType<BlockEntity> t = BE_TYPES.get(s);
        if (t == null) throw new IllegalStateException("no block entity for " + s);
        return t;
    }

    public static StationBlock block(Station s) {
        return BLOCKS.get(s);
    }

    /** Another of the mod's items by path ({@code arcana_permafrost_shard}), or AIR. */
    public static Item item(String path) {
        return BuiltInRegistries.ITEM.getOptional(id(path)).orElse(Items.AIR);
    }

    public static int edge(ItemStack s) {
        return s.getOrDefault(EDGE, 100);
    }

    public static void setEdge(ItemStack s, int edge) {
        s.set(EDGE, Math.max(0, Math.min(100, edge)));
    }

    /** A broken tool was mended: it works again. */
    public static void repaired(ItemStack s, @Nullable Level level) {
        s.remove(BROKEN);
        ToolStats.apply(s, level == null ? null : level.registryAccess());
    }

    // ------------------------------------------------------------------ making stacks

    /** A part of {@code material} with {@code stars}, or EMPTY when the material can't make that part. */
    public static ItemStack part(String partId, String material, int stars) {
        PartItem item = PARTS.get(partId);
        SmithingDefs.Material m = SmithingDefs.material(material);
        if (item == null || m == null || !m.fits(item.part.role())) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(item);
        s.set(MATERIAL, material);
        s.set(PART, new PartInfo(Math.max(1, Math.min(5, stars)), m.metal() ? "water" : "", "", false));
        return s;
    }

    /**
     * An assembled tool of {@code toolId}: {@code materials} and {@code stars} give one value per part in the type's
     * order (the last value repeats). EMPTY when a material can't make its part.
     */
    public static ItemStack tool(String toolId, List<String> materials, List<Integer> stars) {
        return tool(toolId, materials, stars, null);
    }

    public static ItemStack tool(String toolId, List<String> materials, List<Integer> stars, net.minecraft.core.HolderLookup.@Nullable Provider registries) {
        SmithingDefs.ToolType type = SmithingDefs.tool(toolId);
        Item item = TOOLS.get(toolId);
        if (type == null || item == null || materials.isEmpty()) return ItemStack.EMPTY;
        ToolBuild build = ToolBuild.of(type, materials, stars);
        if (!valid(build)) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(item, type.kind().equals("ammo") ? type.makes() : 1);
        s.set(BUILD, build);
        if (Mods.edged(type)) s.set(EDGE, 100);
        ToolStats.apply(s, registries);
        return s;
    }

    /** Every part of the build exists and its material fits the part's role. */
    public static boolean valid(ToolBuild build) {
        SmithingDefs.ToolType type = SmithingDefs.tool(build.tool());
        if (type == null || build.parts().size() != type.parts().size()) return false;
        for (int i = 0; i < build.parts().size(); i++) {
            ToolBuild.PartEntry e = build.parts().get(i);
            SmithingDefs.Part p = SmithingDefs.part(e.part());
            SmithingDefs.Material m = SmithingDefs.material(e.material());
            if (p == null || m == null || !p.id().equals(type.parts().get(i).part()) || !m.fits(p.role())) return false;
        }
        return true;
    }

    /** Material sets the creative tab and {@code /smith give} show. */
    static final String[][] SETS = {{"flint", "leather", "wood"}, {"iron", "leather", "wood"}, {"steel", "deer_hide", "bone"},
            {"rose_gold", "silk", "bamboo"}, {"netherite", "tufthorn_hide", "netherite"}, {"starforged", "silk", "steel"}};

    /** The materials of a tool type for one set: each part from the set member that fits its role. */
    public static List<String> materialsFor(SmithingDefs.ToolType t, String[] set) {
        List<String> mats = new ArrayList<>();
        for (SmithingDefs.ToolPart tp : t.parts()) mats.add(pick(SmithingDefs.part(tp.part()).role(), set));
        return mats;
    }

    /** The creative tab: stations and hand tools, each tool in a few material sets, then every part in every material. */
    static List<ItemStack> creativeItems(net.minecraft.core.HolderLookup.@Nullable Provider registries) {
        List<ItemStack> out = new ArrayList<>();
        for (Station s : Station.values()) out.add(new ItemStack(BLOCKS.get(s)));
        for (Item i : new Item[]{TONGS, KNIFE, CHISEL, STIRRING_ROD, LADLE_ITEM, MOLDING_BOX, FISH_OIL, SCRIBES_CRYSTAL, ALMANAC, FIRE_CLAY, FIRE_BRICK, WHETSTONE}) {
            out.add(new ItemStack(i));
        }
        for (String metal : ALLOY_METALS) {
            out.add(new ItemStack(INGOTS.get(metal)));
            out.add(new ItemStack(NUGGETS.get(metal)));
        }
        out.add(MoldItem.of(MoldItem.Kind.CLAY, "ingot"));
        out.add(MoldItem.of(MoldItem.Kind.CLAY, "nugget"));
        // sets can give the same tool twice (a material that can't make a part falls back): the tab takes each once
        java.util.Set<ToolBuild> seen = new java.util.HashSet<>();
        for (SmithingDefs.ToolType t : SmithingDefs.tools().values()) {
            for (String[] set : SETS) {
                ItemStack s = tool(t.id(), materialsFor(t, set), List.of(3), registries);
                if (!s.isEmpty() && seen.add(s.get(BUILD))) out.add(s.copyWithCount(1));
            }
        }
        for (SmithingDefs.Part p : SmithingDefs.parts().values()) {
            for (SmithingDefs.Material m : SmithingDefs.materials().values()) {
                ItemStack s = part(p.id(), m.id(), 3);
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /** A material from {@code set} for a part of {@code role} (head metal, wrap or carved wood...), falling back sensibly. */
    private static String pick(String role, String[] set) {
        String[] order = switch (role) {
            case "head", "plating" -> new String[]{set[0]};
            case "binding" -> new String[]{set[1], set[0]};
            case "handle", "grip" -> new String[]{set[2], set[1]};
            case "limb" -> new String[]{set[0], set[2]};
            default -> set;
        };
        for (String id : order) {
            SmithingDefs.Material m = SmithingDefs.material(id);
            if (m != null && m.fits(role)) return id;
        }
        return switch (role) {
            case "string" -> "silk";
            case "fletching" -> "down_feather";
            case "limb" -> "wood";
            case "plating" -> "iron";
            case "binding" -> "leather";
            default -> "wood";
        };
    }
}
