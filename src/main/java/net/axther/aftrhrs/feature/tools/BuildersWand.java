package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builder's wand: right-click a block face to extend the contiguous same-block area (up to size x size on the plane
 * of that face) one block outwards, spending matching plain blocks from the inventory (free in creative).
 */
final class BuildersWand {
    private static final Set<String> COUNTED = Set.of("age", "bites", "pickles", "candles", "eggs", "charges", "level",
            "flower_amount", "segment_amount", "part");

    private final ToolsModule plugin;
    private boolean enabled;
    private final Set<String> wands = new HashSet<>();
    private int half;
    private String sound;
    private String msgNoBlocks;
    private final Set<Block> deny = new HashSet<>();

    BuildersWand(ToolsModule plugin) {
        this.plugin = plugin;
    }

    void load(Yaml c) {
        enabled = c.getBoolean("builders-wand.enabled", true);
        wands.clear();
        wands.addAll(ToolsModule.lowerList(c.getStringList("builders-wand.items")));
        int size = Math.max(1, Math.min(9, c.getInt("builders-wand.size", 5)));
        half = (size - 1) / 2;
        sound = c.getString("builders-wand.sound", "block.amethyst_block.place");
        msgNoBlocks = c.getString("builders-wand.message-no-blocks", "<bad>You have no <block> left.");
        deny.clear();
        for (String s : c.getStringList("builders-wand.never-copy")) {
            Block b = AreaTools.block(s);
            if (b != null) deny.add(b);
            else plugin.log().warn("builders-wand.never-copy: unknown material {}", s);
        }
    }

    Set<String> configuredIds() {
        return wands;
    }

    /** Right-click a block with the wand. True if the click was used. */
    boolean useOn(ServerPlayer player, ServerLevel level, BlockPos clicked, Direction face, InteractionHand hand) {
        if (!enabled || hand != InteractionHand.MAIN_HAND) return false;
        String id = ToolsModule.idOf(player.getMainHandItem());
        if (id == null || !wands.contains(id)) return false;
        if (plugin.isBusy(player) || !ToolsModule.mayEditWorld(player)) return false;
        BlockState src = level.getBlockState(clicked);
        if (!isCopyable(level, clicked, src)) return false; // let vanilla handle it (opens chests etc.)
        Block type = src.getBlock();
        Item item = type.asItem();
        List<BlockPos[]> targets = collect(level, clicked, face, type);
        if (targets.isEmpty()) return true;
        boolean creative = ToolsModule.isCreative(player);
        Inventory inv = player.getInventory();
        if (!creative && countPlain(inv, item) <= 0) {
            ToolsModule.actionBar(player, plugin.text(msgNoBlocks.replace("<block>",
                    BuiltInRegistries.BLOCK.getKey(type).getPath().replace('_', ' '))));
            return true;
        }
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        int placed = 0;
        plugin.setBusy(player, true);
        try {
            for (BlockPos[] pair : targets) {
                BlockPos dest = pair[1];
                if (!id.equals(ToolsModule.idOf(player.getMainHandItem()))) break; // wand broke
                BlockState data = level.getBlockState(pair[0]);
                int cost = cost(data);
                if (!creative && countPlain(inv, item) < cost) break;
                if (!isFree(level, dest) || occupied(level, dest) || !inWorld(level, dest)) continue;
                if (ToolsModule.inSpawnProtection(player, level, dest)) continue;
                if (!plugin.claimAllows(player, level, dest, ClaimAction.BUILD)) continue;
                if (data.hasProperty(BlockStateProperties.PERSISTENT)) data = data.setValue(BlockStateProperties.PERSISTENT, true);
                if (data.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    data = data.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(dest).is(Fluids.WATER) && level.getFluidState(dest).isSource());
                }
                ItemStack as = new ItemStack(item);
                if (!FeatherEvents.BLOCK_PLACE.invoker().allow(player, level, dest, data, as)) break; // protected: stop
                level.setBlock(dest, data, 3);
                FeatherEvents.BLOCK_PLACED.invoker().placed(player, level, dest, data, as);
                if (!creative) {
                    takePlain(inv, item, cost);
                    player.getMainHandItem().hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                }
                placed++;
            }
        } finally {
            plugin.setBusy(player, false);
        }
        if (placed > 0) {
            BlockPos at = clicked.relative(face);
            ToolsModule.playSound(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, sound, SoundSource.BLOCKS, 1f, 1f);
        }
        return true;
    }

    /** Source/destination pairs: contiguous blocks of {@code type} on the clicked plane whose face side is free. */
    private List<BlockPos[]> collect(ServerLevel level, BlockPos origin, Direction face, Block type) {
        List<BlockPos[]> out = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(origin);
        seen.add(origin);
        int[][] steps = switch (face.getAxis()) {
            case Y -> new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
            case Z -> new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
            case X -> new int[][]{{0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        };
        while (!queue.isEmpty()) {
            BlockPos b = queue.poll();
            if (!level.getBlockState(b).is(type)) continue;
            BlockPos dest = b.relative(face);
            if (!inWorld(level, dest) || !isFree(level, dest) || occupied(level, dest)) continue;
            out.add(new BlockPos[]{b, dest});
            for (int[] s : steps) {
                BlockPos n = b.offset(s[0], s[1], s[2]);
                if (Math.abs(n.getX() - origin.getX()) > half || Math.abs(n.getY() - origin.getY()) > half
                        || Math.abs(n.getZ() - origin.getZ()) > half) continue;
                if (!level.isLoaded(n)) continue;
                if (seen.add(n)) queue.add(n);
            }
        }
        return out;
    }

    /** Items a copy costs: one per slab half and one per lichen / vein / resin face. */
    private static int cost(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE) && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
        if (s.getBlock() instanceof MultifaceBlock && !s.is(net.minecraft.world.level.block.Blocks.VINE)) {
            int n = 0;
            for (Direction d : Direction.values()) {
                var p = MultifaceBlock.getFaceProperty(d);
                if (s.hasProperty(p) && s.getValue(p)) n++;
            }
            return Math.max(1, n);
        }
        return 1;
    }

    private static boolean inWorld(ServerLevel level, BlockPos b) {
        return level.isInsideBuildHeight(b.getY()) && level.getWorldBorder().isWithinBounds(b);
    }

    private static boolean isFree(ServerLevel level, BlockPos b) {
        return level.getBlockState(b).canBeReplaced();
    }

    /** Living things / vehicles / hanging entities in the space (vanilla refuses to place into them too). */
    private static boolean occupied(ServerLevel level, BlockPos b) {
        for (Entity e : level.getEntities((Entity) null, new AABB(b), e -> !(e instanceof ItemEntity) && e.isAlive())) {
            if (e instanceof LivingEntity || e instanceof VehicleEntity || e instanceof HangingEntity) {
                if (e instanceof ServerPlayer p && p.isSpectator()) continue;
                return true;
            }
        }
        return false;
    }

    /** Plain building blocks only: no block entities, no multi-part / multi-count / growing blocks (dupe-safe). */
    private boolean isCopyable(ServerLevel level, BlockPos pos, BlockState s) {
        Block b = s.getBlock();
        if (s.isAir() || b instanceof LiquidBlock || deny.contains(b)) return false;
        Item item = b.asItem();
        if (item == Items.AIR || !(item instanceof BlockItem bi) || bi.getBlock() != b) return false;
        if (!BuiltInRegistries.ITEM.getKey(item).equals(BuiltInRegistries.BLOCK.getKey(b))) return false; // seeds != crop
        if (s.getDestroySpeed(level, pos) < 0 || s.hasBlockEntity()) return false;
        if (ArcanaContent.id(s) != null) return false;
        for (Property<?> p : s.getProperties()) if (COUNTED.contains(p.getName())) return false;
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return false; // doors, tall plants
        if (s.hasProperty(BlockStateProperties.LAYERS) && s.getValue(BlockStateProperties.LAYERS) > 1) return false;
        return true;
    }

    private static boolean isPlain(ItemStack s, Item item) {
        return !s.isEmpty() && s.is(item);
    }

    private static int countPlain(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (isPlain(inv.getItem(i), item)) n += inv.getItem(i).getCount();
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        if (isPlain(off, item)) n += off.getCount();
        return n;
    }

    private static void takePlain(Inventory inv, Item item, int amount) {
        for (int i = 35; i >= 0 && amount > 0; i--) { // take from the back of the inventory first
            ItemStack s = inv.getItem(i);
            if (!isPlain(s, item)) continue;
            int take = Math.min(amount, s.getCount());
            s.shrink(take);
            amount -= take;
        }
        if (amount > 0) {
            ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
            if (isPlain(off, item)) off.shrink(Math.min(amount, off.getCount()));
        }
    }
}
