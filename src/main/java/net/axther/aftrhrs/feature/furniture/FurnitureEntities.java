package net.axther.aftrhrs.feature.furniture;

import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.feature.furniture.curios.CurioBlockEntity;
import net.axther.aftrhrs.feature.furniture.gadgets.GadgetBlockEntity;
import net.axther.aftrhrs.feature.furniture.power.PowerBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The block entities curios, gadgets, power and chess put on their furniture (where Paper drew display entities over
 * Oraxen furniture or kept state in the furniture entity's PDC). Which pieces get one comes from the bundled
 * curios.yml / gadgets.yml / power.yml ids (blocks are built at startup, before any server config is read).
 *
 * <p>{@link #declare()} runs before {@code ArcanaContent.init()}, {@link #register()} right after it.
 */
public final class FurnitureEntities {
    public static BlockEntityType<CurioBlockEntity> CURIO;
    public static BlockEntityType<GadgetBlockEntity> GADGET;
    public static BlockEntityType<PowerBlockEntity> POWER;
    public static BlockEntityType<net.axther.aftrhrs.feature.chess.ChessBlockEntity> CHESS;

    private static Predicate<String> curioIds = id -> false, gadgetIds = id -> false, powerIds = id -> false;

    private FurnitureEntities() {
    }

    public static void declare() {
        Yaml curios = Feather.bundled("curios.yml");
        List<Pattern> c = new ArrayList<>();
        for (String k : List.of("pedestal", "hat-stand", "statue", "flower-box", "doormat")) {
            c.addAll(ItemMatcher.globs(curios.getStringList(k + ".ids")));
        }
        List<String> suffixes = new ArrayList<>();
        for (String k : List.of("bottom", "middle", "top")) suffixes.add(curios.getString("pedestal.stack." + k, "_" + k));
        curioIds = id -> {
            if (ItemMatcher.anyMatch(c, id)) return true;
            for (String s : suffixes) if (id.endsWith(s) && ItemMatcher.anyMatch(c, id.substring(0, id.length() - s.length()))) return true;
            return false;
        };
        Yaml gadgets = Feather.bundled("gadgets.yml");
        List<Pattern> g = new ArrayList<>();
        for (String k : List.of("spikes", "goblets", "jars", "cages")) g.addAll(ItemMatcher.globs(gadgets.getStringList(k + ".ids")));
        gadgetIds = id -> ItemMatcher.anyMatch(g, id);
        Yaml power = Feather.bundled("power.yml");
        String fan = power.getString("fan.item", "feather_encased_fan").toLowerCase(Locale.ROOT);
        String gen = power.getString("generator.item", "feather_coal_generator").toLowerCase(Locale.ROOT);
        powerIds = id -> id.equals(fan) || id.equals(gen);

        ArcanaContent.blockEntity(curioIds, (pos, state) -> new CurioBlockEntity(pos, state), true, true);
        ArcanaContent.blockEntity(gadgetIds, (pos, state) -> new GadgetBlockEntity(pos, state), true, false);
        ArcanaContent.blockEntity(powerIds, (pos, state) -> new PowerBlockEntity(pos, state), true, true);
        ArcanaContent.blockEntity(net.axther.aftrhrs.feature.chess.ChessModule::isTable, net.axther.aftrhrs.feature.chess.ChessBlockEntity::new, true, true);
    }

    public static void register() {
        CURIO = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("curio"),
                FabricBlockEntityTypeBuilder.create(CurioBlockEntity::new, ArcanaContent.blocks(curioIds)).build());
        GADGET = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("gadget"),
                FabricBlockEntityTypeBuilder.create(GadgetBlockEntity::new, ArcanaContent.blocks(gadgetIds)).build());
        POWER = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("power"),
                FabricBlockEntityTypeBuilder.create(PowerBlockEntity::new, ArcanaContent.blocks(powerIds)).build());
        CHESS = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AftrHrs.id("chess_table"),
                FabricBlockEntityTypeBuilder.create(net.axther.aftrhrs.feature.chess.ChessBlockEntity::new,
                        ArcanaContent.blocks(net.axther.aftrhrs.feature.chess.ChessModule::isTable)).build());
        FurnitureLegacy.init();
        AftrHrs.LOG.info("Furniture block entities: {} curio, {} gadget, {} power pieces", ArcanaContent.blocks(curioIds).length,
                ArcanaContent.blocks(gadgetIds).length, ArcanaContent.blocks(powerIds).length);
    }

    public static boolean isCurio(String id) {
        return id != null && curioIds.test(id);
    }

    public static boolean isGadget(String id) {
        return id != null && gadgetIds.test(id);
    }

    public static boolean isPower(String id) {
        return id != null && powerIds.test(id);
    }
}
