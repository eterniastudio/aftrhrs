package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.content.BlockSets;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.IglooPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Map;
import java.util.TreeMap;

/** The snow, ice and calcite block sets: slipperiness, and the igloo rebuilt from snow bricks. */
public class BlockSetTests {
    static final Identifier IGLOO_TOP = Identifier.withDefaultNamespace("igloo/top");

    static Map<String, Integer> census(ServerLevel level, BlockPos from, BlockPos to) {
        Map<String, Integer> out = new TreeMap<>();
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir()) out.merge(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(), 1, Integer::sum);
        }
        return out;
    }

    /** Polished ice is as slippery as packed ice (blue: as blue ice), bricks/tiles/chiseled have normal grip. */
    @GameTest
    public void iceFriction(GameTestHelper h) {
        CT.Fails f = new CT.Fails("block_sets_friction");
        Map<String, Float> want = Map.of(
                "arcana_polished_ice", Blocks.PACKED_ICE.getFriction(), "arcana_polished_ice_stairs", Blocks.PACKED_ICE.getFriction(),
                "arcana_polished_ice_slab", Blocks.PACKED_ICE.getFriction(), "arcana_polished_blue_ice", Blocks.BLUE_ICE.getFriction(),
                "arcana_ice_bricks", Blocks.STONE.getFriction(), "arcana_ice_tiles", Blocks.STONE.getFriction(),
                "arcana_chiseled_blue_ice", Blocks.STONE.getFriction(), "arcana_snow_bricks", Blocks.STONE.getFriction());
        want.forEach((id, fr) -> {
            Block b = ArcanaContent.block(id);
            f.check(b != null, id + ": not registered");
            if (b != null) f.check(b.getFriction() == fr, id + ": friction " + b.getFriction() + " expected " + fr);
        });
        f.finish(h);
    }

    /** The igloo template (data/minecraft/structure/igloo/top.nbt) is the mod's: snow bricks, no vanilla snow. */
    @GameTest(maxTicks = 100, padding = 16)
    public void iglooTemplateUsesSnowBricks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("igloo_template");
        StructureTemplate t = level.getStructureTemplateManager().get(IGLOO_TOP).orElse(null);
        if (t == null) {
            h.fail("no igloo/top template");
            return;
        }
        f.check(t.getSize().getX() == 7 && t.getSize().getY() == 5 && t.getSize().getZ() == 8, "size " + t.getSize());
        for (Rotation rot : new Rotation[]{Rotation.NONE, Rotation.CLOCKWISE_90}) {
            BlockPos origin = h.absolutePos(new BlockPos(1, 1, 1));
            CT.clear(level, origin.offset(4, 2, 4), 7);
            StructurePlaceSettings ps = new StructurePlaceSettings().setRotation(rot).setMirror(Mirror.NONE)
                    .setRotationPivot(new BlockPos(3, 5, 5)).addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
            t.placeInWorld(level, origin, origin, ps, level.getRandom(), 2);
            BoundingBox bb = t.getBoundingBox(ps, origin);
            Map<String, Integer> c = census(level, new BlockPos(bb.minX(), bb.minY(), bb.minZ()), new BlockPos(bb.maxX(), bb.maxY(), bb.maxZ()));
            String r = rot + ": ";
            f.check(!c.containsKey("minecraft:snow_block"), r + "vanilla snow blocks left " + c);
            f.check(c.getOrDefault("aftrhrs:arcana_snow_bricks", 0) == 68, r + "snow bricks " + c);
            f.check(c.getOrDefault("aftrhrs:arcana_polished_snow", 0) == 24, r + "polished snow " + c);
            f.check(c.getOrDefault("aftrhrs:arcana_chiseled_snow", 0) == 2, r + "chiseled snow " + c);
            f.check(c.getOrDefault("aftrhrs:arcana_snow_brick_stairs", 0) == 25, r + "stairs " + c);
            f.check(c.getOrDefault("aftrhrs:arcana_snow_brick_slab", 0) == 4, r + "slabs " + c);
            // the furnishings and the hatch are still there
            for (String keep : new String[]{"minecraft:ice", "minecraft:oak_trapdoor", "minecraft:red_bed", "minecraft:furnace",
                    "minecraft:crafting_table", "minecraft:redstone_torch", "minecraft:white_carpet"}) {
                f.check(c.containsKey(keep), r + "missing " + keep);
            }
            BlockPos hatch = origin.offset(StructureTemplate.calculateRelativePosition(ps, new BlockPos(3, 0, 5)));
            f.check(level.getBlockState(hatch).is(Blocks.OAK_TRAPDOOR), r + "trapdoor not at the hatch: " + CT.id(level.getBlockState(hatch)));
            CT.clear(level, origin.offset(4, 2, 4), 7);
        }
        f.finish(h);
    }

    /**
     * The real igloo piece (IglooPieces): without a basement the hatch is filled, with polished snow (IglooPieceMixin)
     * instead of a snow block; with a ladder below (the basement shaft) the trapdoor stays.
     */
    @GameTest(maxTicks = 100, padding = 16)
    public void iglooPieceHatch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("igloo_piece");
        f.check(BlockSets.iglooFloorFill(Blocks.SNOW_BLOCK.defaultBlockState()).is(ArcanaContent.block("arcana_polished_snow")),
                "fill is not polished snow");
        f.check(BlockSets.iglooFloorFill(Blocks.STONE.defaultBlockState()).is(Blocks.STONE), "other states changed");
        for (boolean basement : new boolean[]{false, true}) {
            BlockPos origin = h.absolutePos(new BlockPos(1, 2, 1));
            CT.clear(level, origin.offset(4, 2, 4), 7);
            for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, -1, -1), origin.offset(7, -1, 8))) {
                level.setBlock(p.immutable(), Blocks.STONE.defaultBlockState(), 2);
            }
            BlockPos hatch = origin.offset(3, 0, 5);
            if (basement) level.setBlock(hatch.below(), Blocks.LADDER.defaultBlockState(), 2);
            // IglooPiece moves the template onto the WORLD_SURFACE_WG height at the entrance: aim it at origin
            int height = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX() + 3, origin.getZ());
            IglooPieces.IglooPiece piece = new IglooPieces.IglooPiece(level.getStructureTemplateManager(), IGLOO_TOP,
                    origin.atY(origin.getY() - height + IglooPieces.GENERATION_HEIGHT + 1), Rotation.NONE, 0);
            BoundingBox all = new BoundingBox(origin.getX() - 16, level.getMinY(), origin.getZ() - 16,
                    origin.getX() + 24, level.getMaxY(), origin.getZ() + 24);
            piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(), all,
                    ChunkPos.containing(origin), origin);
            BlockState at = level.getBlockState(hatch);
            String r = basement ? "with basement: " : "no basement: ";
            if (basement) f.check(at.is(Blocks.OAK_TRAPDOOR), r + "hatch is " + CT.id(at));
            else f.check(at.is(ArcanaContent.block("arcana_polished_snow")), r + "hatch is " + CT.id(at));
            f.check(level.getBlockState(origin.offset(3, 3, 0)).is(ArcanaContent.block("arcana_chiseled_snow")),
                    r + "keystone is " + CT.id(level.getBlockState(origin.offset(3, 3, 0))) + " (placed at the wrong height?)");
            Map<String, Integer> c = census(level, origin, origin.offset(6, 4, 7));
            f.check(!c.containsKey("minecraft:snow_block"), r + "vanilla snow blocks " + c);
            CT.clear(level, origin.offset(4, 2, 4), 7);
            for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, -1, -1), origin.offset(7, -1, 8))) {
                level.setBlock(p.immutable(), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        f.finish(h);
    }
}
