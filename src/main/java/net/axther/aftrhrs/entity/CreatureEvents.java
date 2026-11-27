package net.axther.aftrhrs.entity;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Hooks the pets module (and anything else) listens to. They replace the MythicMobs contract of the Paper server
 * ({@code featherpetslot}, {@code featherpet{a=register}}, MythicMobDeathEvent, ModelEngine's model interact event).
 * All fire on the server thread.
 */
public final class CreatureEvents {
    private CreatureEvents() {
    }

    /**
     * Before a tame roll uses up the tame item: return false to refuse (the pet cap). The player keeps the item. A
     * listener that returns false is responsible for telling the player why (the creature shows nothing itself).
     * Replaces the {@code featherpetslot} condition.
     */
    public static final Event<CanTame> CAN_TAME = EventFactory.createArrayBacked(CanTame.class, listeners -> (creature, player) -> {
        for (CanTame l : listeners) if (!l.canTame(creature, player)) return false;
        return true;
    });

    /**
     * A creature was just tamed (owner set, full health). Replaces {@code featherpet{a=register}}. A listener returns
     * true when it told the player about the tame (the pets module's "joined your pets" line); every listener runs, and
     * the creature prints its own short line only when none did.
     */
    public static final Event<Tamed> TAMED = EventFactory.createArrayBacked(Tamed.class, listeners -> (creature, player) -> {
        boolean told = false;
        for (Tamed l : listeners) told |= l.onTamed(creature, player);   // no short-circuit: all listeners run
        return told;
    });

    /** Any Arcana creature died (tamed or wild). */
    public static final Event<Died> DIED = EventFactory.createArrayBacked(Died.class, listeners -> (mob, source) -> {
        for (Died l : listeners) l.onDied(mob, source);
    });

    /** A creature's ability fired (bond XP source). {@code ability} is the skill id, e.g. {@code ArcanaEmberkit_Hearth}. */
    public static final Event<AbilityFired> ABILITY = EventFactory.createArrayBacked(AbilityFired.class, listeners -> (creature, ability) -> {
        for (AbilityFired l : listeners) l.onAbility(creature, ability);
    });

    /** The owner fed (healed) a creature with its heal item. */
    public static final Event<Fed> FED = EventFactory.createArrayBacked(Fed.class, listeners -> (creature, player, item) -> {
        for (Fed l : listeners) l.onFed(creature, player, item);
    });

    /**
     * A player right-clicked a creature, before the creature's own handling (tame, stay, feed, ride, pet). Return
     * anything but PASS to take over the click (the /pets menu, plush revive, work orders, breeding ...).
     */
    public static final Event<Interact> INTERACT = EventFactory.createArrayBacked(Interact.class, listeners -> (mob, player, hand) -> {
        for (Interact l : listeners) {
            InteractionResult r = l.onInteract(mob, player, hand);
            if (r != InteractionResult.PASS) return r;
        }
        return InteractionResult.PASS;
    });

    /** A tamed creature's stay/follow state changed (owner Shift + right-click, whistle, API). */
    public static final Event<StayChanged> STAY = EventFactory.createArrayBacked(StayChanged.class, listeners -> (creature, stay) -> {
        for (StayChanged l : listeners) l.onStay(creature, stay);
    });

    /**
     * A player harvested something off a live wild creature by its own handling (an undead variant's {@code snip}),
     * not through the wildlife module's harvests. {@code what} names the harvest. The bestiary counts it.
     */
    public static final Event<Harvested> HARVESTED = EventFactory.createArrayBacked(Harvested.class, listeners -> (mob, player, what) -> {
        for (Harvested l : listeners) l.onHarvested(mob, player, what);
    });

    /**
     * A tamed creature grew up into another type (a raised penguin chick becoming an adult, {@link PenguinCreature}):
     * {@code adult} already carries the owner, bond and look and is about to be added to the world; {@code young} is
     * removed right after. The pets module moves the pet's record over to the adult.
     */
    public static final Event<GrewUp> GREW_UP = EventFactory.createArrayBacked(GrewUp.class, listeners -> (young, adult) -> {
        for (GrewUp l : listeners) l.onGrewUp(young, adult);
    });

    @FunctionalInterface
    public interface GrewUp {
        void onGrewUp(ArcanaCreature young, ArcanaCreature adult);
    }

    @FunctionalInterface
    public interface Harvested {
        void onHarvested(net.minecraft.world.entity.Mob mob, ServerPlayer player, String what);
    }

    @FunctionalInterface
    public interface CanTame {
        boolean canTame(ArcanaCreature creature, ServerPlayer player);
    }

    @FunctionalInterface
    public interface Tamed {
        /** @return true if this listener told the player about the tame */
        boolean onTamed(ArcanaCreature creature, ServerPlayer player);
    }

    @FunctionalInterface
    public interface Died {
        void onDied(ArcanaMob mob, @Nullable DamageSource source);
    }

    @FunctionalInterface
    public interface AbilityFired {
        void onAbility(ArcanaCreature creature, String ability);
    }

    @FunctionalInterface
    public interface Fed {
        void onFed(ArcanaCreature creature, ServerPlayer player, ItemStack item);
    }

    @FunctionalInterface
    public interface Interact {
        InteractionResult onInteract(ArcanaMob mob, ServerPlayer player, InteractionHand hand);
    }

    @FunctionalInterface
    public interface StayChanged {
        void onStay(ArcanaCreature creature, boolean stay);
    }
}
