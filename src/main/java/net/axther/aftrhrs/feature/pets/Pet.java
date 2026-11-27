package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Worlds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One pet a player owns. The record id is stable for the pet's whole life; the entity UUID changes when the pet is
 * revived or respawned next to its owner. Exactly one living entity belongs to a record ({@link #entity}).
 */
final class Pet {
    /** ALIVE: has (or should have) a living entity. FALLEN: died, waits for a revive. STABLED: parked, no entity. */
    enum State { ALIVE, FALLEN, STABLED }

    final UUID id;
    final UUID owner;
    /** The MythicMobs id the Paper server used (ArcanaBlackCat); {@link PetsModule#type} maps it to the native type. */
    String species;
    /** Name the owner gave it, or null (then the species name is shown). */
    String name;
    /** Renamed while it had no loaded entity: the next load puts the record's name on the creature. */
    boolean namePending;
    /** The living entity, or null while fallen / stabled. */
    UUID entity;
    State state = State.ALIVE;
    int xp;
    int level;
    /** Last known stay/follow state. */
    boolean stay;
    double health;
    double maxHealth;
    long created;
    long diedAt;
    /** The owner was offline when it fell; tell them on join. */
    boolean deathUnseen;
    /** Last known position (or where it fell). */
    Spot spot;
    /** Name color the owner picked (a bond perk), e.g. "#ff9ecb", or null. */
    String color;
    /** When the last free revive (a bond perk) was used, epoch ms; 0 = never. */
    long freeReviveAt;
    /** When it was put in the stable, epoch ms; 0 while not stabled. */
    long stabledAt;
    /** Entities this pet was respawned away from; if one of them loads again it is a leftover copy and is removed. */
    final List<UUID> replaced = new ArrayList<>();
    /** Color variant key from pets.yml variants.species.&lt;species&gt;, or null for the normal look. */
    String variant;
    /** The rare shiny roll (stacks with a color variant). */
    boolean shiny;
    /** Breeding cooldown: it can breed again from this time on (epoch ms); 0 = ready. */
    long breedReadyAt;
    /** Born from two of the owner's pets (not tamed in the wild). */
    boolean bred;
    /** Where it was told to "Work Here", or null when it isn't working. */
    Spot work;
    /** Work is credited up to this time (epoch ms). */
    long workClock;
    /** Work time earned but not done yet (ms). */
    long workBank;
    /** Why the work is waiting (a messages key under work.waiting), or null. Not saved. */
    String workWaiting;
    /** A catch-up after a chunk load is due: the next payout tells the owner. Not saved. */
    boolean workReport;
    /** Its load since the last unload was handled. Not saved. */
    boolean workLoaded;

    Pet(UUID id, UUID owner, String species) {
        this.id = id;
        this.owner = owner;
        this.species = species;
    }

    boolean working() {
        return work != null && state == State.ALIVE;
    }

    boolean alive() {
        return state == State.ALIVE;
    }

    boolean fallen() {
        return state == State.FALLEN;
    }

    boolean stabled() {
        return state == State.STABLED;
    }

    void addReplaced(UUID old) {
        if (old == null || replaced.contains(old)) return;
        replaced.add(old);
        if (replaced.size() > 16) replaced.remove(0);
    }

    /**
     * A position stored by world UUID (Paper only; kept from old data), key and name, so it still resolves when
     * worlds are renamed or namespaced.
     */
    record Spot(UUID worldId, String worldKey, String worldName, double x, double y, double z) {
        static Spot of(Entity e) {
            return of((ServerLevel) e.level(), e.getX(), e.getY(), e.getZ());
        }

        static Spot of(Loc l) {
            return of(l.level(), l.x(), l.y(), l.z());
        }

        static Spot of(ServerLevel level, double x, double y, double z) {
            return new Spot(null, Worlds.key(level), Worlds.name(level), x, y, z);
        }

        ServerLevel level() {
            return Worlds.resolve(worldId, worldKey, worldName);
        }

        Loc location() {
            ServerLevel l = level();
            return l == null ? null : new Loc(l, x, y, z);
        }

        boolean sameLevel(ServerLevel l) {
            return l != null && l == level();
        }
    }
}
