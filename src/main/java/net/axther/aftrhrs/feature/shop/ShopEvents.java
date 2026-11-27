package net.axther.aftrhrs.feature.shop;

import net.axther.aftrhrs.core.event.FeatherEvents;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.migrate.Legacy;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The shop's Fabric listeners (registered once; they do nothing while the module is off):
 * <ul>
 *   <li>right-clicks on the counters and registered market stalls (any part of the furniture block, any held item);
 *       registered stalls can't be broken (staff remove them with /stall remove first);</li>
 *   <li>the Stall Keeper item (on a block, in the air, on an entity) and clicks on keeper villagers;</li>
 *   <li>keeper villagers as they load (also old ones from the Paper world, through a {@link Legacy} converter), and
 *       lightning never leaves a witch where a keeper stood;</li>
 *   <li>join: away-sales notes, stray screen copies.</li>
 * </ul>
 */
final class ShopEvents {
    private static final Map<UUID, Long> handled = new HashMap<>();

    private ShopEvents() {
    }

    static void register(Feather feather) {
        feather.once("shop.events", () -> {
            UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
                ShopModule m = ShopModule.active;
                if (m == null || level.isClientSide() || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
                ItemStack held = p.getItemInHand(hand);
                if (m.keepers.isToken(held) || (hand == InteractionHand.OFF_HAND && m.keepers.isToken(p.getMainHandItem()))) {
                    return m.keepers.useToken(p, hand, hit);
                }
                return furnitureClick(m, p, (ServerLevel) level, hand, hit.getBlockPos());
            });
            UseItemCallback.EVENT.register((player, level, hand) -> {
                ShopModule m = ShopModule.active;
                if (m == null || level.isClientSide() || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
                if (!m.keepers.isToken(p.getItemInHand(hand))) return InteractionResult.PASS;
                return m.keepers.useToken(p, hand, null);
            });
            UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
                ShopModule m = ShopModule.active;
                if (m == null || level.isClientSide() || !(player instanceof ServerPlayer p)) return InteractionResult.PASS;
                if (Keepers.isKeeper(entity)) return m.keepers.interact(p, entity, hand);
                if (m.keepers.isToken(p.getItemInHand(hand))) return m.keepers.tokenOnEntity(p, hand);
                return InteractionResult.PASS;
            });
            PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
                ShopModule m = ShopModule.active;
                if (m == null || level.isClientSide() || !(player instanceof ServerPlayer p)) return true;
                return !protectedStall(m, p, (ServerLevel) level, pos, state);
            });
            ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
                ShopModule m = ShopModule.active;
                if (m == null || !(entity instanceof Villager) || !Keepers.isKeeper(entity)) return;
                // Next tick, never inside the entity manager's load loop (a discard there breaks its iteration).
                Tasks.later(1L, () -> {
                    if (ShopModule.active == m) m.keepers.check(entity);
                });
            });
            // No witch from lightning, no zombie villager, nothing (Paper Keepers.onTransform).
            FeatherEvents.MOB_CONVERT.register((mob, into) -> !Keepers.isKeeper(mob));
            ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, params) -> {
                // Belt and braces: a conversion some other mod forced through still leaves no stray copy.
                if (Keepers.isKeeper(previous) && converted != null && !Keepers.isKeeper(converted)) converted.discard();
            });
            // Splash/lingering potions, arrows, beacons: keepers take no effects (Paper Keepers.onPotion).
            FeatherEvents.EFFECT_ADD.register((target, effect, source) -> !Keepers.isKeeper(target));
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                ShopModule m = ShopModule.active;
                if (m != null) m.joined(handler.player);
            });
            // Paper-era keeper villagers ("feather:stall_keeper" in their BukkitValues) stay keepers: claim them so no
            // other converter clears them, and link them to their stall once they are in the world.
            Legacy.converter((level, entity, bukkitValues) -> {
                if (!(entity instanceof Villager) || !bukkitValues.contains(Keepers.KEEPER_KEY)) return false;
                Tasks.sync(() -> {
                    ShopModule m = ShopModule.active;
                    if (m != null) m.keepers.check(entity);
                });
                return true;
            });
        });
    }

    private static boolean justHandled(ServerPlayer p) {
        Long t = handled.get(p.getUUID());
        long now = Tasks.now();
        return t != null && now - t >= 0 && now - t <= 1;
    }

    /** A right-click on shop furniture: counters open their screens, stalls theirs. Public services, whatever claims say. */
    private static InteractionResult furnitureClick(ShopModule m, ServerPlayer p, ServerLevel level, InteractionHand hand, BlockPos pos) {
        if (!level.isLoaded(pos)) return InteractionResult.PASS; // never load a chunk for a click
        BlockState st = level.getBlockState(pos);
        String id = ShopItems.blockId(st);
        if (!ShopModule.isShopFurniture(id)) return InteractionResult.PASS;
        ShopItems.Spot spot = new ShopItems.Spot(level, ShopItems.origin(st, pos), id);
        if (hand != InteractionHand.MAIN_HAND || justHandled(p)) return InteractionResult.FAIL;
        handled.put(p.getUUID(), Tasks.now());
        if (handled.size() > 1024) handled.clear();
        return m.furnitureClick(p, spot) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** Registered stalls stay put: breaking any part of one is refused. */
    private static boolean protectedStall(ShopModule m, ServerPlayer p, ServerLevel level, BlockPos pos, BlockState state) {
        if (m.store.stalls.isEmpty()) return false;
        String id = ShopItems.blockId(state);
        if (id == null) return false;
        ShopStore.Stall stall = m.stalls.byBlock(new ShopItems.Spot(level, ShopItems.origin(state, pos), id));
        if (stall == null) return false;
        m.error(p, Perms.has(p, ShopModule.STALL_ADMIN) ? "shop.stall.cant-break-staff" : "shop.stall.cant-break", Text.unparsed("name", stall.name));
        return true;
    }
}
