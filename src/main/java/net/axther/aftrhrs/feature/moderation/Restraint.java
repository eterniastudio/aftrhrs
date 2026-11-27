package net.axther.aftrhrs.feature.moderation;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.event.FeatherEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

/**
 * Holds players in place (Bukkit's pile of cancelled events, shared by the auth gate and /freeze): they can look
 * around but not walk (falling is fine, unless flying), build, break, interact, use or move items, open containers,
 * swap hands, take portals or pearls, fight or be hurt. Commands and chat are each module's own business.
 */
public final class Restraint {
    private Restraint() {
    }

    /** Registers the listeners (call once per JVM per restraint, e.g. inside {@code feather.once}). */
    public static void register(Predicate<ServerPlayer> held) {
        Predicate<Player> h = p -> p instanceof ServerPlayer sp && held.test(sp);
        UseBlockCallback.EVENT.register((p, level, hand, hit) -> h.test(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((p, level, hand) -> h.test(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((p, level, hand, entity, hit) -> h.test(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((p, level, hand, entity, hit) -> h.test(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((p, level, hand, pos, dir) -> h.test(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, p, pos, state, be) -> !h.test(p));
        FeatherEvents.BLOCK_PLACE.register((p, level, pos, state, stack) -> !held.test(p));
        FeatherEvents.ITEM_DROP.register((p, stack) -> !held.test(p));
        FeatherEvents.ITEM_PICKUP.register((p, item) -> !held.test(p));
        FeatherEvents.SWAP_HANDS.register(p -> !held.test(p));
        FeatherEvents.CONTAINER_OPEN.register((p, menu) -> !held.test(p));
        FeatherEvents.CONTAINER_CLICK.register((p, menu) -> !held.test(p));
        FeatherEvents.PLAYER_TELEPORT.register((p, from, to, cause) -> switch (cause) {
            case ENDER_PEARL, CHORUS_FRUIT, NETHER_PORTAL, END_PORTAL, END_GATEWAY, SPECTATE -> !held.test(p);
            default -> true;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            // They can't be hurt (they can't defend themselves) ... /kill and the void still work.
            if (entity instanceof ServerPlayer p && held.test(p) && !source.is(DamageTypes.GENERIC_KILL) && !source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
                return false;
            }
            // ... and can't hurt anyone (arrows they shot included).
            ServerPlayer attacker = FeatherEvents.attacker(source);
            return attacker == null || !held.test(attacker);
        });
        FeatherEvents.PLAYER_MOVE.register((p, from, to) -> !held.test(p) || onlyFalling(p, from, to));
    }

    /** Looking around and falling are fine (the server would kick a player hanging mid-air for flying). */
    private static boolean onlyFalling(ServerPlayer p, Loc from, Loc to) {
        return from.x() == to.x() && from.z() == to.z() && to.y() <= from.y() && !p.getAbilities().flying;
    }
}
