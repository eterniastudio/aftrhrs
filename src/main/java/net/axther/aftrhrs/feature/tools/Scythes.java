package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Yaml;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harvest scythes: right-clicking or breaking a ripe crop harvests and replants every ripe crop in the square around
 * it. Each crop goes through Fabric's break check (claims) first.
 */
final class Scythes {
    private final ToolsModule plugin;
    private boolean enabled;
    private final Set<String> tools = new HashSet<>();
    private int radius;
    private boolean replant;
    private String sound;
    /** crop block -> the item that replants it */
    private final Map<Block, Item> seeds = new HashMap<>();

    Scythes(ToolsModule plugin) {
        this.plugin = plugin;
    }

    void load(Yaml c) {
        enabled = c.getBoolean("harvest.enabled", true);
        tools.clear();
        tools.addAll(ToolsModule.lowerList(c.getStringList("harvest.tools")));
        radius = Math.max(0, Math.min(3, c.getInt("harvest.radius", 1)));
        replant = c.getBoolean("harvest.replant", true);
        sound = c.getString("harvest.sound", "block.crop.break");
        seeds.clear();
        seeds.put(Blocks.WHEAT, Items.WHEAT_SEEDS);
        seeds.put(Blocks.CARROTS, Items.CARROT);
        seeds.put(Blocks.POTATOES, Items.POTATO);
        seeds.put(Blocks.BEETROOTS, Items.BEETROOT_SEEDS);
        seeds.put(Blocks.NETHER_WART, Items.NETHER_WART);
        if (c.getBoolean("harvest.include-cocoa", true)) seeds.put(Blocks.COCOA, Items.COCOA_BEANS);
    }

    Set<String> configuredIds() {
        return tools;
    }

    private boolean isScythe(ItemStack item) {
        String id = ToolsModule.idOf(item);
        return id != null && tools.contains(id);
    }

    private static IntegerProperty age(BlockState s) {
        for (Property<?> p : s.getProperties()) {
            if (p instanceof IntegerProperty ip && p.getName().equals("age")) return ip;
        }
        return null;
    }

    private boolean isRipeVanilla(BlockState s) {
        if (!seeds.containsKey(s.getBlock())) return false;
        IntegerProperty a = age(s);
        return a != null && s.getValue(a) >= a.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** The farming module's Arcana crops ({@link Hooks.Crops}), or null while farming is off. */
    private Hooks.Crops crops() {
        return plugin.feather.service(Hooks.Crops.class);
    }

    private boolean isRipeCustom(ServerLevel level, BlockPos pos) {
        Hooks.Crops crops = crops();
        if (crops == null) return false;
        try {
            return crops.ripe(level, pos);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isRipeCrop(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return isRipeVanilla(s) || isRipeCustom(level, pos);
    }

    /** A player is about to break a block: a ripe crop with a scythe becomes an area harvest (the plain break is cancelled). */
    boolean beforeBreak(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!enabled || plugin.isBusy(player) || player.isShiftKeyDown() || !ToolsModule.mayEditWorld(player)) return true;
        if (!isScythe(player.getMainHandItem()) || !isRipeCrop(level, pos)) return true;
        harvestArea(player, level, pos);
        return false;
    }

    /** Right-click on a ripe crop with a scythe. True if used. */
    boolean useOn(ServerPlayer player, ServerLevel level, BlockPos pos, InteractionHand hand) {
        if (!enabled || hand != InteractionHand.MAIN_HAND || plugin.isBusy(player)) return false;
        if (!isScythe(player.getMainHandItem()) || !isRipeCrop(level, pos)) return false;
        if (!ToolsModule.mayEditWorld(player)) return false;
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        harvestArea(player, level, pos);
        return true;
    }

    private void harvestArea(ServerPlayer player, ServerLevel level, BlockPos center) {
        String toolId = ToolsModule.idOf(player.getMainHandItem());
        if (toolId == null || !ToolsModule.mayEditWorld(player)) return;
        boolean creative = ToolsModule.isCreative(player);
        int harvested = 0;
        List<BlockPos> crops = new ArrayList<>();
        crops.add(center);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos b = center.offset(dx, 0, dz);
                if (level.isLoaded(b) && isRipeCrop(level, b)) crops.add(b);
            }
        }
        for (BlockPos crop : crops) {
            ItemStack tool = player.getMainHandItem();
            if (!toolId.equals(ToolsModule.idOf(tool))) break; // the scythe broke
            if (!isRipeCrop(level, crop)) continue;
            if (ToolsModule.inSpawnProtection(player, level, crop)) continue;
            if (!plugin.claimAllows(player, level, crop, ClaimAction.BUILD)) continue;
            BlockState before = level.getBlockState(crop);
            if (!isRipeVanilla(before)) {
                boolean done;
                Hooks.Crops hook = crops();
                if (hook == null) continue;
                plugin.setBusy(player, true);
                try {
                    done = hook.harvest(player, level, crop, replant);
                } catch (RuntimeException e) {
                    done = false;
                } finally {
                    plugin.setBusy(player, false);
                }
                if (done) {
                    harvested++;
                    if (!creative) tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                }
                continue;
            }
            BlockEntity be = level.getBlockEntity(crop);
            boolean allowed;
            plugin.setBusy(player, true);
            try {
                allowed = PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, crop, before, be);
            } finally {
                plugin.setBusy(player, false);
            }
            if (!allowed) continue;
            if (!isRipeCrop(level, crop)) continue; // something else changed it

            List<ItemStack> drops = creative ? List.of() : new ArrayList<>(Block.getDrops(before, level, crop, be, player, tool));
            boolean replanted = false;
            if (replant) {
                Item seed = seeds.get(before.getBlock());
                if (creative) {
                    replanted = true;
                } else {
                    for (ItemStack drop : drops) {
                        if (drop.is(seed) && drop.getCount() > 0) {
                            drop.shrink(1);
                            replanted = true;
                            break;
                        }
                    }
                }
            }
            IntegerProperty a = age(before);
            if (replanted && a != null) level.setBlock(crop, before.setValue(a, 0), 2);
            else level.setBlock(crop, Blocks.AIR.defaultBlockState(), 3);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, before), crop.getX() + 0.5, crop.getY() + 0.4,
                    crop.getZ() + 0.5, 12, 0.25, 0.25, 0.25, 0);
            for (ItemStack drop : drops) if (!drop.isEmpty()) Block.popResource(level, crop, drop);
            harvested++;
            if (!creative) tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
        if (harvested > 0) {
            ToolsModule.playSound(level, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, sound, SoundSource.BLOCKS, 1f, 1f);
        }
    }
}
