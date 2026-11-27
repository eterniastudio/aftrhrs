package net.axther.aftrhrs.feature.pets;

import net.minecraft.world.item.Item;

/** A tameable creature from pets.yml {@code species} (keyed by its MythicMobs id, as the records are). */
record PetSpecies(String id, String name, Kind kind, String plush, Item heal) {
    enum Kind { PET, MOUNT, HOSTILE }

    boolean mount() {
        return kind == Kind.MOUNT;
    }
}
