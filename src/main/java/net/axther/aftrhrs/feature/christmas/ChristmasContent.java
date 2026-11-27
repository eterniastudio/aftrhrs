package net.axther.aftrhrs.feature.christmas;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * The Christmas set's ids and block entities (content in {@code mod/content/items/arcana_christmas.yml}, made by
 * {@code mod/tools/xmasart/gen.py}): the decoratable fir tree, the gift boxes and mistletoe. {@link #declare()} runs before
 * {@code ArcanaContent.init()}, {@link #register()} right after it.
 */
public final class ChristmasContent {
    public static final String FIR_TREE = "arcana_fir_tree";
    public static final String MISTLETOE = "arcana_mistletoe";
    public static final String SNOW_GLOBE = "arcana_snow_globe";
    public static final String ADVENT = "arcana_advent_wreath";
    public static final String TREE_LIGHTS = "arcana_tree_lights";
    public static final String LIGHTS_LIT = "arcana_fir_tree_lights_lit";
    public static final String LIGHTS_UNLIT = "arcana_fir_tree_lights_unlit";
    public static final Set<String> TOPPERS = Set.of("arcana_tree_star", "arcana_tree_angel");
    public static final Set<String> PRESENT_COLORS = Set.of("red", "green", "blue", "gold", "white");

    public static BlockEntityType<TreeBlockEntity> TREE;
    public static BlockEntityType<PresentBlockEntity> PRESENT;
    public static BlockEntityType<MistletoeBlockEntity> MISTLETOE_BE;

    private ChristmasContent() {
    }

    public static boolean isFirTree(@Nullable String id) {
        return FIR_TREE.equals(id);
    }

    /** {@code arcana_<colour>_present} (not the decorative pile). */
    public static boolean isPresent(@Nullable String id) {
        if (id == null || !id.startsWith("arcana_") || !id.endsWith("_present")) return false;
        return PRESENT_COLORS.contains(id.substring("arcana_".length(), id.length() - "_present".length()));
    }

    public static boolean isMistletoe(@Nullable String id) {
        return MISTLETOE.equals(id);
    }

    /** Things that hang on the fir tree: baubles, candy canes, gingerbread men. */
    public static boolean isOrnament(@Nullable String id) {
        if (id == null) return false;
        return (id.startsWith("arcana_") && id.endsWith("_bauble")) || id.equals("arcana_candy_cane") || id.equals("arcana_gingerbread_man");
    }

    public static boolean isTopper(@Nullable String id) {
        return id != null && TOPPERS.contains(id);
    }

    public static void declare() {
        ArcanaContent.blockEntity(ChristmasContent::isFirTree, TreeBlockEntity::new, false, false);
        ArcanaContent.blockEntity(ChristmasContent::isPresent, PresentBlockEntity::new, false, false);
        ArcanaContent.blockEntity(ChristmasContent::isMistletoe, MistletoeBlockEntity::new, true, false);
    }

    public static void register() {
        TREE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("christmas_tree"),
                FabricBlockEntityTypeBuilder.create(TreeBlockEntity::new, ArcanaContent.blocks(ChristmasContent::isFirTree)).build());
        PRESENT = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("present"),
                FabricBlockEntityTypeBuilder.create(PresentBlockEntity::new, ArcanaContent.blocks(ChristmasContent::isPresent)).build());
        MISTLETOE_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("mistletoe"),
                FabricBlockEntityTypeBuilder.create(MistletoeBlockEntity::new, ArcanaContent.blocks(ChristmasContent::isMistletoe)).build());
    }
}
