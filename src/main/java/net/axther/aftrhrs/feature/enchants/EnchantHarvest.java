package net.axther.aftrhrs.feature.enchants;

import net.axther.aftrhrs.feature.smithing.Effects;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The block and drop enchantments: Molten Touch, Bountiful and Pocketing (drops), Prospector (vein mining), Replanting
 * (harvest and replant) and Treasure Hunter (finds while digging). Vein mining and harvesting reuse smithing's code
 * ({@link Effects}), so they follow the same claim checks and never chain into each other.
 */
public final class EnchantHarvest {
    static final ResourceKey<LootTable> FINDS = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath("aftrhrs", "enchant/treasure_hunter"));
    static final TagKey<Block> DIGGABLE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("aftrhrs", "treasure_hunter"));
    /** Treasure Hunter: one find per player per 3 seconds at most. */
    private static final int FIND_COOLDOWN_MS = 3000;

    private final EnchantsModule m;
    private final Map<UUID, Long> lastFind = new HashMap<>();

    EnchantHarvest(EnchantsModule m) {
        this.m = m;
    }

    static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            EnchantsModule m = EnchantsModule.active;
            if (m == null || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return true;
            return m.harvest.beforeBreak(sp, sl, pos);
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            EnchantsModule m = EnchantsModule.active;
            if (m != null && player instanceof ServerPlayer sp && level instanceof ServerLevel sl) m.harvest.afterBreak(sp, sl, pos, state);
        });
        LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            EnchantsModule m = EnchantsModule.active;
            if (m != null && !drops.isEmpty()) m.harvest.modifyDrops(table, context, drops);
        });
    }

    // ------------------------------------------------------------------ Replanting

    private boolean beforeBreak(ServerPlayer p, ServerLevel level, BlockPos pos) {
        ItemStack tool = p.getMainHandItem();
        if (p.isShiftKeyDown() || Effects.busy(p) || !tool.is(ItemTags.HOES)) return true;
        int replant = Enchants.level(tool, Enchants.REPLANTING);
        if (replant <= 0 || !Effects.ripeCrop(level, pos)) return true;
        Effects.harvestCrops(p, level, pos, replant >= 2 ? 1 : 0);
        return false;
    }

    // ------------------------------------------------------------------ Prospector, Treasure Hunter

    private void afterBreak(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state) {
        ItemStack tool = p.getMainHandItem();
        if (tool.isEmpty() || p.isCreative()) return;
        int prospector = Enchants.level(tool, Enchants.PROSPECTOR);
        if (prospector > 0 && !p.isShiftKeyDown() && !Effects.busy(p) && Effects.ore(state) && tool.isCorrectToolForDrops(state)) {
            Effects.veinMine(p, level, pos, state, Enchants.PROSPECTOR_PER_LEVEL * prospector);
        }
        int hunter = Enchants.level(tool, Enchants.TREASURE_HUNTER);
        if (hunter > 0 && state.is(DIGGABLE) && level.getRandom().nextFloat() < Enchants.TREASURE_PER_LEVEL * hunter) unearth(p, level, pos, state, tool);
    }

    private void unearth(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        long now = System.currentTimeMillis();
        Long last = lastFind.get(p.getUUID());
        if (last != null && now - last < FIND_COOLDOWN_MS) return;
        lastFind.put(p.getUUID(), now);
        if (lastFind.size() > 512) lastFind.clear();
        LootTable table = level.getServer().reloadableRegistries().getLootTable(FINDS);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withParameter(LootContextParams.TOOL, tool)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, p)
                .withLuck(p.getLuck())
                .create(LootContextParamSets.BLOCK);
        for (ItemStack find : table.getRandomItems(params)) {
            if (find.isEmpty()) continue;
            p.sendOverlayMessage(Component.translatable("aftrhrs.enchants.unearthed", find.getHoverName()));
            if (Enchants.level(tool, Enchants.POCKETING) > 0 && p.getInventory().add(find)) continue;
            if (!find.isEmpty()) Block.popResource(level, pos, find);
        }
        level.playSound(null, pos, SoundEvents.BRUSH_GRAVEL_COMPLETED, SoundSource.PLAYERS, 1f, 1.1f);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
                8, 0.3, 0.2, 0.3, 0);
    }

    // ------------------------------------------------------------------ drops

    private void modifyDrops(Holder<LootTable> table, LootContext ctx, List<ItemStack> drops) {
        if (table.is(FINDS)) return; // Treasure Hunter's own finds stay as found
        BlockState state = ctx.getOptional(LootContextParams.BLOCK_STATE);
        if (state != null) {
            ItemInstance ti = ctx.getOptional(LootContextParams.TOOL);
            if (!(ti instanceof ItemStack tool) || tool.isEmpty()) return;
            int bountiful = Enchants.level(tool, Enchants.BOUNTIFUL);
            if (bountiful > 0 && ripe(state) && ctx.getRandom().nextFloat() < Enchants.BOUNTIFUL_PER_LEVEL * bountiful) bonusCrop(state, drops);
            if (Enchants.level(tool, Enchants.MOLTEN_TOUCH) > 0) {
                List<ItemStack> smelted = Effects.smeltAll(List.copyOf(drops), ctx.getLevel());
                drops.clear();
                drops.addAll(smelted);
            }
            return;
        }
        // Pocketing on a kill: the mob's loot goes straight into the killer's pack
        Entity killer = ctx.getOptional(LootContextParams.ATTACKING_ENTITY);
        Entity dead = ctx.getOptional(LootContextParams.THIS_ENTITY);
        if (!(killer instanceof ServerPlayer p) || !(dead instanceof LivingEntity) || dead == p
                || ctx.getOptional(LootContextParams.DAMAGE_SOURCE) == null) return;
        if (Enchants.held(p, Enchants.POCKETING) <= 0) return;
        for (ItemStack d : drops) p.getInventory().add(d);
        drops.removeIf(ItemStack::isEmpty);
    }

    private static boolean ripe(BlockState s) {
        if (s.getBlock() instanceof CropBlock crop) return crop.isMaxAge(s);
        return s.is(Blocks.NETHER_WART) && s.getValue(NetherWartBlock.AGE) >= 3;
    }

    /** One more of the crop's harvest: the drop that isn't its seed (wheat, beetroot), else the seed (carrot, potato). */
    private static void bonusCrop(BlockState state, List<ItemStack> drops) {
        ItemStack seed = new ItemStack(state.getBlock().asItem());
        ItemStack pick = null;
        for (ItemStack d : drops) {
            if (d.isEmpty()) continue;
            if (!ItemStack.isSameItem(d, seed)) {
                pick = d;
                break;
            }
            if (pick == null) pick = d;
        }
        if (pick != null) drops.add(pick.copyWithCount(1));
    }

    /** Pocketing for a broken block ({@code BlockPocketingMixin}): true when the drops went into the pack. */
    public static boolean pocket(BlockState state, ServerLevel level, BlockPos pos, BlockEntity be, Entity breaker, ItemStack tool) {
        if (EnchantsModule.active == null || !(breaker instanceof ServerPlayer) || Enchants.level(tool, Enchants.POCKETING) <= 0) return false;
        return Effects.pocket(state, level, pos, be, breaker, tool);
    }
}
