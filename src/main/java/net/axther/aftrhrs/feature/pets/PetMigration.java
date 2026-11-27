package net.axther.aftrhrs.feature.pets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.migrate.Legacy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * Carries the Paper server's pets over. A MythicMobs creature from the old world already loads as its native type
 * (same UUID, position, health; {@code migrate/EntityTypeMixin}); this converter turns the MythicMobs/ModelEngine
 * leftovers on it into creature state:
 * <ul>
 *   <li>clears {@code NoAI}, {@code Silent}, invisibility and the base mob's held item (ModelEngine hid the vanilla
 *       base entity, MythicMobs froze it while the model's skills drove it);</li>
 *   <li>{@code mythicmobs:owner} → tamed and owned; {@code mythicmobs:variables}: {@code stay} → sitting,
 *       {@code arcana_bond} → bond, saved ability variables ({@code gift_armed} ...) → {@link ArcanaCreature#setVar},
 *       {@code petname} / {@code arcana_namecolor} → kept for the pets module;</li>
 *   <li>the {@code mythicmobs:*} / {@code modelengine:*} tags are dropped; {@code feather:pet} (the record id) stays.</li>
 * </ul>
 * The pets module then adopts the creature one tick later: a record (by {@code feather:pet} or entity id) wins and puts
 * its own name, bond, color, variant and shiny on it; a tamed creature without a record becomes a new record with the
 * name, bond and color the old creature carried.
 */
final class PetMigration {
    /** What a migrated creature carried that only a new record can keep. */
    record Info(String name, int bond, String color, boolean staying) {
    }

    /** MythicMobs variables that are state of the old skills, not ability data. */
    private static final Set<String> TRANSIENT = Set.of("stay", "arcana_bond", "nameshown", "napping", "far", "busy");

    private PetMigration() {
    }

    static void register(Feather feather) {
        feather.once("pets.legacy", () -> Legacy.converter(PetMigration::convert));
    }

    private static boolean convert(ServerLevel level, Entity entity, CompoundTag bukkit) {
        if (!(entity instanceof ArcanaCreature c)) return false;
        if (bukkit.getString("mythicmobs:type").isEmpty() && bukkit.getString(PetsModule.PET_KEY).isEmpty()) return false;
        // what ModelEngine / MythicMobs left on the base entity
        c.setNoAi(false);
        c.setSilent(false);
        c.setInvisible(false);
        c.removeEffect(MobEffects.INVISIBILITY);
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        c.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        c.setDeltaMovement(Vec3.ZERO);

        JsonObject vars = variables(bukkit.getString("mythicmobs:variables").orElse(null));
        UUID owner = PetStore.uuid(bukkit.getString("mythicmobs:owner").orElse(null));
        String name = str(vars, "petname");
        String color = str(vars, "arcana_namecolor");
        int bond = num(vars, "arcana_bond", 0);
        boolean stay = num(vars, "stay", 0) == 1;
        boolean recorded = bukkit.getString(PetsModule.PET_KEY).isPresent();

        // the MythicMobs display name ("Black Cat" in purple) is not the pet's name; the record / petname decides
        if (c.hasCustomName() && (c.getCustomName().getString().equals(c.species().name) || owner != null || recorded)) {
            c.setCustomName(null);
            c.setCustomNameVisible(false);
        }
        if (owner != null) {
            c.setTame(true, true);
            c.setOwnerReference(EntityReference.of(owner));
            c.setPersistenceRequired();
            c.setBond(bond);
            if (stay != c.staying()) c.setStaying(stay, null);
            if (name != null && !name.isBlank()) {
                c.setCustomName(net.minecraft.network.chat.Component.literal(name));
                c.setCustomNameVisible(true);
            }
            if (vars != null) {
                for (var en : vars.entrySet()) {
                    if (TRANSIENT.contains(en.getKey()) || !en.getValue().isJsonObject()) continue;
                    JsonObject v = en.getValue().getAsJsonObject();
                    if (!"Integer".equalsIgnoreCase(str(v, "type")) || num(v, "expirationTime", 0) != 0) continue;
                    c.setVar(en.getKey(), num(v, "value", 0));
                }
            }
        }
        // the Paper tags are done; feather:pet (the record id) stays for the pets module
        CompoundTag data = Pdc.of(c).copy();
        for (String k : Set.copyOf(data.keySet())) {
            if (k.startsWith("mythicmobs:") || k.startsWith("modelengine:")) data.remove(k);
        }
        Pdc.changed(c, data);

        PetsModule m = PetsModule.active();
        if (m != null && owner != null) m.stashLegacy(c.getUUID(), new Info(name, bond, color, stay));
        Feather.get().getLogger().info("Migrated {} {} ({}){}.", c.species().mythicId, c.getUUID(),
                owner == null ? "wild" : "owned by " + owner, recorded ? ", pet record " + bukkit.getString(PetsModule.PET_KEY).orElse("") : "");
        return true;
    }

    /** {@code {"entries": {name: {"value": ..., "type": "Integer", "expirationTime": 0}}}} → the entries object. */
    private static JsonObject variables(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonElement entries = root.getAsJsonObject().get("entries");
            return entries != null && entries.isJsonObject() ? entries.getAsJsonObject() : root.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A variable's value (from the entries object) or a plain field (of one entry). */
    private static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        if (e != null && e.isJsonObject()) e = e.getAsJsonObject().get("value");
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int num(JsonObject o, String key, int def) {
        String s = str(o, key);
        if (s == null) return def;
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
