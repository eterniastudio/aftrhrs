package net.axther.aftrhrs.feature.tools;

import net.axther.aftrhrs.content.ArcanaContent;
import net.axther.aftrhrs.core.ClaimAction;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Hammers / excavators (3x3 on the mined face) and lumber axes (fell connected natural trees).
 *
 * <p>Extra blocks are broken with {@code ServerPlayerGameMode.destroyBlock}, the vanilla survival break path: it fires
 * Fabric's break events (claims cancel it), drops loot with the held tool (fortune / silk touch), damages the tool by 1
 * through vanilla durability and drops nothing in creative. The extra breaks run one tick after the original break so
 * the center block is always mined with the intact tool.
 */
final class AreaTools {
    private final ToolsModule plugin;

    private boolean areaEnabled;
    private final Set<String> areaTools = new HashSet<>();
    private double hardnessFactor, hardnessSlack;
    private final Set<Block> areaDeny = new HashSet<>();

    private boolean fellEnabled;
    private final Set<String> axeTools = new HashSet<>();
    private int maxLogs, maxRadius, minLeaves, logsPerTick;
    private final Set<Block> naturalFoliage = new HashSet<>();

    /** Players with a felling job in progress (one at a time per player). */
    private final Set<UUID> felling = new HashSet<>();

    AreaTools(ToolsModule plugin) {
        this.plugin = plugin;
    }

    void load(Yaml c) {
        areaEnabled = c.getBoolean("area-mining.enabled", true);
        areaTools.clear();
        areaTools.addAll(ToolsModule.lowerList(c.getStringList("area-mining.tools")));
        hardnessFactor = c.getDouble("area-mining.max-hardness-factor", 1.5);
        hardnessSlack = c.getDouble("area-mining.max-hardness-slack", 0.5);
        areaDeny.clear();
        for (String s : c.getStringList("area-mining.never-break")) {
            Block b = block(s);
            if (b != null) areaDeny.add(b);
            else plugin.log().warn("area-mining.never-break: unknown material {}", s);
        }
        fellEnabled = c.getBoolean("tree-felling.enabled", true);
        axeTools.clear();
        axeTools.addAll(ToolsModule.lowerList(c.getStringList("tree-felling.tools")));
        maxLogs = Math.max(1, c.getInt("tree-felling.max-logs", 256));
        maxRadius = Math.max(1, c.getInt("tree-felling.max-horizontal-radius", 10));
        minLeaves = Math.max(1, c.getInt("tree-felling.min-natural-leaves", 3));
        logsPerTick = Math.max(1, c.getInt("tree-felling.logs-per-tick", 32));
        naturalFoliage.clear();
        for (String s : c.getStringList("tree-felling.extra-natural-foliage")) {
            Block b = block(s);
            if (b != null) naturalFoliage.add(b);
            else plugin.log().warn("tree-felling.extra-natural-foliage: unknown material {}", s);
        }
    }

    /** A Bukkit material name ({@code BUDDING_AMETHYST}) or block id as a block, or null. */
    static Block block(String material) {
        String k = material.trim().toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(k.contains(":") ? k : "minecraft:" + k);
        if (id == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    Set<String> configuredIds() {
        Set<String> s = new HashSet<>(areaTools);
        s.addAll(axeTools);
        return s;
    }

    /** After a player broke a block (Fabric AFTER: the break went through). */
    void onBroken(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        if (plugin.isBusy(player) || player.isShiftKeyDown()) return;
        ItemStack tool = player.getMainHandItem();
        String id = ToolsModule.idOf(tool);
        if (id != null && areaEnabled && areaTools.contains(id)) startAreaMining(player, level, pos, state, tool, id);
        else if (fellEnabled && id != null && axeTools.contains(id)) {
            startFelling(player, level, pos, state, held -> id.equals(ToolsModule.idOf(held)));
        } else if (fellEnabled && tool.is(net.minecraft.tags.ItemTags.AXES)
                && net.axther.aftrhrs.feature.enchants.Enchants.level(tool, net.axther.aftrhrs.feature.enchants.Enchants.TIMBERFALL) > 0
                && !net.axther.aftrhrs.feature.smithing.Effects.busy(player)) {
            startFelling(player, level, pos, state, held -> held == tool); // Timberfall (enchantment): the same axe
        }
    }

    void forget(UUID player) {
        felling.remove(player);
    }

    // ------------------------------------------------------------------ area mining

    private void startAreaMining(ServerPlayer player, ServerLevel level, BlockPos center, BlockState was, ItemStack tool, String id) {
        if (!canAreaMine(level, center, was, tool)) return;
        Direction face = minedFace(player, center);
        float centerHardness = was.getDestroySpeed(level, center);
        double maxHardness = centerHardness * hardnessFactor + hardnessSlack;
        List<BlockPos> targets = new ArrayList<>(8);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                targets.add(switch (face.getAxis()) {
                    case Y -> center.offset(a, 0, b);
                    case Z -> center.offset(a, b, 0);
                    case X -> center.offset(0, a, b);
                });
            }
        }
        UUID uuid = player.getUUID();
        Tasks.later(1, () -> {
            ServerPlayer p = plugin.feather.player(uuid);
            if (p == null || p.isDeadOrDying() || p.level() != level) return;
            for (BlockPos pos : targets) {
                if (!id.equals(ToolsModule.idOf(p.getMainHandItem()))) return; // tool broke or swapped
                if (!level.isLoaded(pos)) continue;
                BlockState s = level.getBlockState(pos);
                if (!canAreaMine(level, pos, s, p.getMainHandItem())) continue;
                if (s.getDestroySpeed(level, pos) > maxHardness) continue;
                breakAsPlayer(p, level, pos);
            }
        });
    }

    /** Harvestable, mineable with this tool, no block entity, not Arcana content, not listed. */
    private boolean canAreaMine(ServerLevel level, BlockPos pos, BlockState s, ItemStack tool) {
        if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return false;
        if (areaDeny.contains(s.getBlock())) return false;
        if (s.getDestroySpeed(level, pos) < 0) return false;
        if (s.hasBlockEntity()) return false;
        if (ArcanaContent.id(s) != null) return false;
        if (tool.getDestroySpeed(s) <= 1.0f) return false;
        return tool.isCorrectToolForDrops(s);
    }

    /** Face of the block the player is looking at; falls back to the view direction. */
    static Direction minedFace(ServerPlayer player, BlockPos block) {
        double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1.0;
        // This runs after the break (the block is gone, a world ray passes through it): clip the view ray against the
        // cell the block was in.
        Vec3 eye = player.getEyePosition();
        BlockHitResult cell = net.minecraft.world.phys.shapes.Shapes.block().clip(eye, eye.add(player.getLookAngle().scale(range)), block);
        if (cell != null && cell.getType() == HitResult.Type.BLOCK) return cell.getDirection();
        HitResult hit = player.pick(range, 1f, false);
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK && bh.getBlockPos().equals(block)) {
            return bh.getDirection();
        }
        float pitch = player.getXRot();
        if (pitch > 50) return Direction.UP;
        if (pitch < -50) return Direction.DOWN;
        Vec3 dir = player.getLookAngle();
        if (Math.abs(dir.x) > Math.abs(dir.z)) return dir.x > 0 ? Direction.WEST : Direction.EAST;
        return dir.z > 0 ? Direction.NORTH : Direction.SOUTH;
    }

    // ------------------------------------------------------------------ tree felling

    private void startFelling(ServerPlayer player, ServerLevel level, BlockPos origin, BlockState was,
                              java.util.function.Predicate<ItemStack> still) {
        Block logType = was.getBlock();
        if (!was.is(BlockTags.LOGS)) return;
        UUID uuid = player.getUUID();
        if (felling.contains(uuid)) return;

        List<BlockPos> logs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin.asLong());
        int leaves = 0;
        Set<Long> leafSeen = new HashSet<>();
        while (!queue.isEmpty() && logs.size() < maxLogs + 1) {
            BlockPos b = queue.poll();
            if (!b.equals(origin)) logs.add(b);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = b.offset(dx, dy, dz);
                        if (Math.abs(n.getX() - origin.getX()) > maxRadius || Math.abs(n.getZ() - origin.getZ()) > maxRadius) continue;
                        if (n.getY() < origin.getY()) continue; // a tree grows up from the cut, never down into a build
                        if (!level.isInsideBuildHeight(n.getY()) || !level.isLoaded(n)) continue;
                        BlockState ns = level.getBlockState(n);
                        long k = n.asLong();
                        if (ns.is(logType)) {
                            if (seen.add(k)) queue.add(n);
                        } else if (leaves < minLeaves && isNaturalFoliage(ns) && leafSeen.add(k)) {
                            leaves++;
                        }
                    }
                }
            }
        }
        if (logs.isEmpty() || leaves < minLeaves) return; // a lone log or a log build: vanilla
        if (logs.size() > maxLogs) logs = logs.subList(0, maxLogs);
        // Logs touching building blocks are a build (maybe one grown into a tree): never fell those.
        if (touchesBuild(level, origin)) return;
        for (BlockPos b : logs) if (touchesBuild(level, b)) return;
        logs.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(b -> b.distSqr(origin)));

        final List<BlockPos> todo = new ArrayList<>(logs);
        felling.add(uuid);
        int[] index = {0};
        Tasks.Task[] task = new Tasks.Task[1];
        task[0] = Tasks.timer(1, 1, () -> {
            ServerPlayer p = plugin.feather.player(uuid);
            if (p == null || p.isDeadOrDying() || !felling.contains(uuid) || plugin != ToolsModule.active) {
                felling.remove(uuid);
                task[0].cancel();
                return;
            }
            int done = 0;
            while (index[0] < todo.size() && done < logsPerTick) {
                BlockPos pos = todo.get(index[0]++);
                if (!still.test(p.getMainHandItem()) || p.getMainHandItem().isEmpty()) { // broke or swapped
                    felling.remove(uuid);
                    task[0].cancel();
                    return;
                }
                if (p.level() != level || !level.isLoaded(pos)) continue;
                if (!level.getBlockState(pos).is(logType)) continue;
                breakAsPlayer(p, level, pos);
                done++;
            }
            if (index[0] >= todo.size()) {
                felling.remove(uuid);
                task[0].cancel();
            }
        });
    }

    private static Set<Block> build;

    /** Blocks only players place: a log next to one belongs to a build. */
    private static synchronized Set<Block> buildBlocks() {
        if (build != null) return build;
        Set<Block> out = new HashSet<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState s = b.defaultBlockState();
            String n = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (s.is(BlockTags.PLANKS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.FENCES)
                    || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.WALLS)
                    || s.is(BlockTags.WOOL) || s.is(BlockTags.BEDS) || s.is(BlockTags.ALL_SIGNS) || s.is(BlockTags.WOOL_CARPETS)
                    || n.contains("glass") || n.startsWith("stripped_") || n.endsWith("bricks") || n.endsWith("concrete")
                    || n.equals("cobblestone") || n.equals("barrier") || n.endsWith("chest") || n.equals("barrel")
                    || n.equals("crafting_table") || n.equals("bookshelf") || n.endsWith("lantern") || n.equals("torch")
                    || n.equals("wall_torch") || n.equals("ladder")) {
                out.add(b);
            }
        }
        return build = out;
    }

    private static boolean touchesBuild(ServerLevel level, BlockPos b) {
        Set<Block> bb = buildBlocks();
        for (Direction f : Direction.values()) {
            BlockPos n = b.relative(f);
            if (!level.isLoaded(n)) continue;
            if (bb.contains(level.getBlockState(n).getBlock())) return true;
        }
        return false;
    }

    private boolean isNaturalFoliage(BlockState s) {
        if (naturalFoliage.contains(s.getBlock())) return true;
        if (!s.is(BlockTags.LEAVES)) return false;
        return s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
    }

    // ------------------------------------------------------------------ shared

    private void breakAsPlayer(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (ToolsModule.inSpawnProtection(player, level, pos) || !ToolsModule.mayEditWorld(player)) return;
        if (!plugin.claimAllows(player, level, pos, ClaimAction.BUILD)) return; // never into someone else's claim
        plugin.setBusy(player, true);
        try {
            player.gameMode.destroyBlock(pos);
        } catch (RuntimeException t) {
            plugin.log().warn("Failed to break {} at {}: {}", level.getBlockState(pos).getBlock(), pos.toShortString(), t.toString());
        } finally {
            plugin.setBusy(player, false);
        }
    }
}
