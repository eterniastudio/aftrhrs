package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The stable: pets parked out of the world. A stabled pet keeps its record (species, name, bond, color, health)
 * but has no entity, doesn't count toward the cap and can't die. Parking saves the record (state {@code stabled},
 * the old entity on the {@code replaced} list) to disk <em>before</em> the entity is removed, so after a crash the
 * entity that comes back with the chunk is recognized as a leftover and removed: never two of the same pet.
 */
final class Stable {
    private final PetsModule m;
    private int defaultCapacity;

    Stable(PetsModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        defaultCapacity = Math.max(0, cfg.getInt("stable.capacity", 9));
    }

    /** Stable size: feather.pets.stable.unlimited, the highest feather.pets.stable.&lt;n&gt;, else stable.capacity. */
    int capacity(ServerPlayer p) {
        return PetUtil.numeric(p, "feather.pets.stable.", defaultCapacity);
    }

    String capacityLabel(ServerPlayer p) {
        int c = capacity(p);
        return c == Integer.MAX_VALUE ? "∞" : Integer.toString(c);
    }

    List<Pet> parked(UUID owner) {
        List<Pet> out = new ArrayList<>();
        for (Pet p : m.store().ofUnsorted(owner)) if (p.stabled()) out.add(p);
        out.sort(Comparator.comparingLong(p -> p.stabledAt));
        return out;
    }

    // ------------------------------------------------------------------ in

    /** Parks the pet. Returns null when done, else the message key that explains why not. */
    String park(ServerPlayer owner, Pet p) {
        if (!p.owner.equals(owner.getUUID())) return "stable.not-yours";
        if (p.stabled()) return "stable.already";
        if (p.fallen()) return "stable.fallen";
        if (parked(p.owner).size() >= capacity(owner)) return "stable.full";
        if (m.busy(p)) return "stable.busy";
        if (m.breeding().pending(p)) return "stable.breeding";
        ArcanaCreature e = m.creature(p);
        if (e != null) {
            String ridden = m.riddenState(e, owner);
            if (ridden != null) return ridden;
        }
        if (tooFast(owner, p)) return "stable.too-fast";
        if (e != null) m.syncRecord(p, e);
        UUID old = p.entity;
        p.state = Pet.State.STABLED;
        p.stabledAt = System.currentTimeMillis();
        p.stay = false;
        m.work().stop(p);
        m.store().addReplaced(p, old);
        m.store().setEntity(p, null);
        m.changed(p.owner);
        if (p.health <= 0 && p.maxHealth > 0) p.health = p.maxHealth;
        m.store().journal(p);   // on disk first, then the entity goes
        if (e != null && e.isAlive()) {
            e.stopRiding();
            e.ejectPassengers();
            PetUtil.particles(e, ParticleTypes.CLOUD, 0.6, 14, 0.35, 0.35, 0.02);
            m.despawn(e);
        }
        m.sound(owner, "stable-in");
        return null;
    }

    // ------------------------------------------------------------------ out

    /** Brings a parked pet out next to the owner. Returns null when done, else the message key. */
    String bringOut(ServerPlayer owner, Pet p) {
        if (!p.owner.equals(owner.getUUID())) return "stable.not-yours";
        if (!p.stabled()) return "stable.not-stabled";
        if (!m.hasFreeSlot(owner, false)) return "stable.at-cap";
        if (ArcanaEntities.byMythicId(p.species) == null) return "stable.unavailable";
        if (tooFast(owner, p)) return "stable.too-fast";
        double fraction = p.maxHealth > 0 && p.health > 0 ? Math.min(1.0, p.health / p.maxHealth) : 1.0;
        Entity e = m.spawnPet(p, PetUtil.near(owner), fraction);
        if (e == null) {
            p.state = Pet.State.STABLED;   // spawnPet only changes the state after a successful spawn; be sure
            return "stable.failed";
        }
        p.stabledAt = 0;
        m.store().journal(p);
        PetUtil.particles(e, ParticleTypes.HAPPY_VILLAGER, 0.6, 10, 0.4, 0.4, 0);
        m.sound(owner, "stable-out");
        return null;
    }

    /** At most once per pet every 1.5 s and once per player every 0.4 s (each one is written to the pets journal). */
    private boolean tooFast(ServerPlayer owner, Pet p) {
        return m.onCooldown("stable:" + p.id, 1500) || m.onCooldown("stable-player:" + owner.getUUID(), 400);
    }

    // ------------------------------------------------------------------ the screen

    /** The stable dialog. {@code fromMenu}: its bottom button goes back to the /pets chest instead of closing. */
    void open(ServerPlayer viewer, boolean fromMenu) {
        if (m.refuse(viewer) || m.handOff(viewer, "stable")) return;
        List<Pet> pets = parked(viewer.getUUID());
        TagResolver counts = TagResolver.resolver(Text.unparsed("count", pets.size()), Text.unparsed("capacity", capacityLabel(viewer)),
                Text.unparsed("active", m.countActive(viewer.getUUID())), Text.unparsed("cap", m.capLabel(viewer)));
        Dialogs.Menu menu = m.dialogs().menu(m.msg("stable.title", counts));
        menu.text(m.msg(pets.isEmpty() ? "stable.body-empty" : "stable.body", counts));
        for (Pet p : pets) {
            UUID id = p.id;
            TagResolver tags = m.petTags(p);
            Component tip = m.msg("stable.entry-tooltip", tags, counts);
            if (m.variants().special(p)) tip = tip.append(Component.newline()).append(m.variants().label(p));
            menu.button(m.msg("stable.entry", tags), tip, 150, pl -> {
                Pet pet = m.store().get(id);
                if (pet == null || !pet.owner.equals(pl.getUUID())) {
                    open(pl, fromMenu);
                    return;
                }
                m.menu().openActions(pl, pet);   // bring out, rename or release
            });
        }
        menu.columns(2);
        if (fromMenu) menu.exit(m.text().msg("menu.back"), m::openMenu);
        else menu.exit(m.text().msg("menu.close"), (java.util.function.Consumer<ServerPlayer>) null);
        menu.open(viewer);
        m.text().sound(viewer, "menu-open");
    }
}
