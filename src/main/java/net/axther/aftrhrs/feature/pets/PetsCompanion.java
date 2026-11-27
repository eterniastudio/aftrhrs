package net.axther.aftrhrs.feature.pets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Perms;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.net.Companion;
import net.axther.aftrhrs.net.Wire;
import net.kyori.adventure.text.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pets for the mod's pet screens ({@code pets} feature, docs/companion-protocol.md; the client side is
 * {@code client/pets}). Players get their pets, the stable and the codex as data; each carries its creature's
 * {@code entity} id and the client draws the mod's own GeckoLib model (no models are sent). Every request goes through
 * the same calls as the /pets chest and its dialogs (same checks, messages and sounds). An owner also gets a fresh
 * snapshot (at most once a second) when a pet comes out, goes away, falls or its health moves by 1 HP.
 *
 * <p>The old {@code pet_model} request (Blockbench rigs streamed from MythicMobs packs) is gone; a client that still
 * sends it is ignored.
 */
final class PetsCompanion {
    private static volatile PetsCompanion active;

    private final PetsModule m;
    /** Species id (MythicMobs id) → the creature's entity id ({@code aftrhrs:black_cat}). */
    private volatile Map<String, String> entities = Map.of();
    private final Set<UUID> dirty = new HashSet<>();
    private final Map<UUID, String> sent = new HashMap<>();
    private final Map<UUID, Map<UUID, Double>> sentHealth = new HashMap<>();
    private Tasks.Task flusher;

    PetsCompanion(PetsModule m) {
        this.m = m;
    }

    void start() {
        Companion.on("pets_open", "pets", (p, msg) -> run(b -> b.openRequest(p, msg)));
        Companion.on("pet_action", "pets", (p, msg) -> run(b -> b.action(p, msg)));
        Companion.on("pets_bring_all", "pets", (p, msg) -> run(b -> b.bringAll(p)));
        // Health-only updates for clients that know them (docs/companion-protocol.md, "pets_hp").
        Companion.addFeature("pets_hp", "pets");
        m.feather().once("pets.companion.quit", () -> Companion.onQuit(p -> run(b -> b.forget(p.getUUID()))));
        flusher = Tasks.timer(20, 20, this::flush);
        scan();
        active = this;
    }

    private static void run(java.util.function.Consumer<PetsCompanion> action) {
        PetsCompanion b = active;
        if (b != null) action.accept(b);
    }

    void stop() {
        if (active == this) active = null;
        if (flusher != null) flusher.cancel();
        flusher = null;
    }

    private void forget(UUID owner) {
        dirty.remove(owner);
        sent.remove(owner);
        sentHealth.remove(owner);
    }

    private boolean modded(ServerPlayer p) {
        return Companion.has(p, "pets") && Perms.has(p, "feather.pets");
    }

    /** The codex screen: the mod, and permission for the codex's front door (feather.guide, everyone by default). */
    private boolean moddedCodex(ServerPlayer p) {
        return Companion.has(p, "pets") && Perms.has(p, "feather.guide");
    }

    /** A vanilla screen is about to open: sends the mod's instead. True when the player got it. */
    boolean open(ServerPlayer p, String screen) {
        if (screen.equals("codex")) return openCodex(p, null, null);
        if (!modded(p)) return false;
        sendPets(p, screen.equals("stable") ? "stable" : "pets");
        return true;
    }

    /** The codex is about to open as dialogs: sends the mod's codex screen instead (on that tab / creature). */
    boolean openCodex(ServerPlayer p, String category, String species) {
        if (!moddedCodex(p) || !m.codex().enabled()) return false;
        sendCodex(p, category, species);
        return true;
    }

    // ------------------------------------------------------------------ pushes

    void changed(UUID owner) {
        dirty.add(owner);
    }

    /** Every second: owners whose pets changed, and owners whose loaded pets' health moved by 1 HP or more. */
    private void flush() {
        for (UUID owner : dirty) push(owner);
        dirty.clear();
        for (UUID owner : new ArrayList<>(sentHealth.keySet())) pushHealth(owner);
    }

    private void push(UUID owner) {
        ServerPlayer p = m.feather().player(owner);
        if (p != null) sendPets(p, null, true);
    }

    /**
     * Health moved by 1 HP or more on some loaded pets since the last snapshot: a {@code pets_hp} with just those pets
     * (clients with the feature), else a full push. A different set of pets than the snapshot showed is a full push too.
     */
    private void pushHealth(UUID owner) {
        ServerPlayer p = m.feather().player(owner);
        Map<UUID, Double> was = sentHealth.get(owner);
        if (p == null || p.hasDisconnected() || !modded(p) || was == null) return;
        List<Pet> pets = m.store().of(owner);
        pets.removeIf(Pet::stabled);
        pets.addAll(m.stable().parked(owner));
        if (pets.size() != was.size()) {
            sendPets(p, null, true);
            return;
        }
        for (Pet pet : pets) {
            if (!was.containsKey(pet.id)) {
                sendPets(p, null, true);
                return;
            }
        }
        List<Pet> moved = new ArrayList<>();
        List<LivingEntity> live = new ArrayList<>();
        // Only pets that are out can have moved: stored and fallen ones cost no entity lookup.
        for (Pet pet : pets) {
            if (!pet.alive() || pet.entity == null || !(m.entity(pet) instanceof LivingEntity le)) continue;
            if (Math.abs(le.getHealth() - was.get(pet.id)) >= 1) {
                moved.add(pet);
                live.add(le);
            }
        }
        if (moved.isEmpty()) return;
        if (!Companion.has(p, "pets_hp")) {
            sendPets(p, null, true);
            return;
        }
        JsonObject msg = Companion.message("pets_hp");
        JsonArray list = new JsonArray();
        for (int i = 0; i < moved.size(); i++) {
            Pet pet = moved.get(i);
            LivingEntity le = live.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", pet.id.toString());
            o.addProperty("health", round(le.getHealth()));
            o.addProperty("maxHealth", round(le.getMaxHealth()));
            o.addProperty("where", Adv.plain(m.menu().whereText(p, pet, le)));
            list.add(o);
            was.put(pet.id, round(le.getHealth()));
        }
        msg.add("pets", list);
        // The last full push no longer matches what the client shows: the next one goes out whatever it says.
        sent.remove(owner);
        Companion.send(p, msg);
    }

    // ------------------------------------------------------------------ creature ids

    /** Which creature each species is (startup and /feather reload). */
    void scan() {
        Map<String, String> known = new HashMap<>();
        Set<String> species = new LinkedHashSet<>(m.speciesIds());
        for (Codex.Entry e : m.codex().entries()) {
            species.add(e.id);
            for (Codex.Form f : e.forms) species.add(f.id());
        }
        for (String id : species) {
            EntityType<?> t = ArcanaEntities.byMythicId(id);
            if (t != null) known.put(id, BuiltInRegistries.ENTITY_TYPE.getKey(t).toString());
        }
        entities = Map.copyOf(known);
    }

    // ------------------------------------------------------------------ requests

    private void openRequest(ServerPlayer p, JsonObject msg) {
        String screen = Wire.str(msg, "screen", "");
        if (screen.equals("codex")) {
            // the codex has its own permission (feather.guide); the rest of this needs feather.pets
            if (moddedCodex(p) && m.codex().enabled()) {
                String species = m.codex().entryOf(Wire.str(msg, "species", ""));
                String category = Wire.str(msg, "category", "");
                m.codex().lastView.put(p.getUUID(), species != null ? "entry:" + species : category.isEmpty() ? "main" : "category:" + category);
                sendCodex(p, category.isEmpty() ? null : category, species);
            } else if (modded(p) && !m.refuse(p)) sendPets(p, "pets");
            return;
        }
        if (!modded(p) || m.refuse(p)) return;
        switch (screen) {
            case "pets", "stable" -> sendPets(p, screen);
            default -> sendPets(p, null);
        }
    }

    private void bringAll(ServerPlayer p) {
        if (!modded(p) || m.refuse(p)) return;
        if (m.bringAll(p) == 0) m.send(p, "summon.none");
        sendPets(p, null);
    }

    private void action(ServerPlayer pl, JsonObject msg) {
        if (!modded(pl) || m.refuse(pl)) return;
        Pet p = m.store().get(PetStore.uuid(Wire.str(msg, "pet", "")));
        if (p == null || !p.owner.equals(pl.getUUID())) {
            sendPets(pl, null);
            return;
        }
        String a = Wire.str(msg, "a", "");
        if (!allowed(p, a)) {
            sendPets(pl, null);
            return;
        }
        UUID id = pl.getUUID();
        switch (a) {
            case "summon" -> {
                // the chest's right-click / "Call To Me": the snapshot follows once it arrived (or didn't)
                m.bring(p, pl, true, o -> {
                    ServerPlayer now = m.feather().player(id);
                    if (now == null) return;
                    m.reportSummon(now, p, o);
                    sendPets(now, null);
                });
                return;
            }
            case "stay", "follow" -> {
                if (!m.setStay(pl, p, a.equals("stay"))) m.send(pl, "stay.not-loaded", m.petTags(p));
            }
            case "work" -> {
                String err = m.work().order(pl, p);
                if (err != null) {
                    m.send(pl, err, m.petTags(p));
                    m.sound(pl, "error");
                } else {
                    m.send(pl, "work.started", m.petTags(p), m.work().tags(p), Text.component("job", m.work().describe(p)));
                    m.sound(pl, "work");
                }
            }
            case "stable" -> {
                String err = m.stable().park(pl, p);
                if (err != null) {
                    m.send(pl, err, m.petTags(p), Text.unparsed("capacity", m.stable().capacityLabel(pl)));
                    m.sound(pl, "error");
                } else {
                    m.send(pl, "stable.in", m.petTags(p), Text.unparsed("count", m.stable().parked(pl.getUUID()).size()),
                            Text.unparsed("capacity", m.stable().capacityLabel(pl)));
                }
            }
            case "bring_out" -> {
                String err = m.stable().bringOut(pl, p);
                if (err != null) {
                    m.send(pl, err, m.petTags(p), Text.unparsed("cap", m.capLabel(pl)));
                    m.sound(pl, "error");
                } else {
                    m.send(pl, "stable.out", m.petTags(p));
                }
            }
            case "revive" -> m.revive(pl, p, null);
            case "release" -> {
                if (!Wire.bool(msg, "confirm", false)) {
                    sendPets(pl, null);
                    return;
                }
                String name = m.displayName(p);
                String err = m.release(pl, p);
                if (err != null) {
                    m.send(pl, err, m.petTags(p));
                    m.sound(pl, "error");
                } else {
                    m.send(pl, "release.done", Text.unparsed("pet", name));
                    m.text().sound(pl, "deleted");
                }
            }
            case "rename" -> {
                String name = Wire.str(msg, "name", "");
                if (name.length() > 256) name = name.substring(0, 256);
                String err = m.rename(p, name);
                if (err != null) {
                    m.send(pl, err, Text.unparsed("max", m.nameMax()));
                    m.sound(pl, "error");
                } else {
                    m.send(pl, "rename.done", m.petTags(p));
                }
            }
            case "color", "colour" -> {   // "colour": mod builds from before the spelling change
                String want = Wire.str(msg, "color", Wire.str(msg, "colour", ""));
                String hex = null;
                for (String c : m.perks().colors().values()) if (c.equalsIgnoreCase(want)) hex = c;
                if (hex == null && !want.isEmpty() && !want.equalsIgnoreCase("none")) {
                    sendPets(pl, null);
                    return;
                }
                p.color = hex;
                m.store().markDirty();
                m.refresh(p);
                m.send(pl, "color.done", m.petTags(p));
            }
            default -> {
                return;
            }
        }
        sendPets(pl, null);
    }

    /** The buttons the pet's dialog offers in its state. */
    private boolean allowed(Pet p, String a) {
        if (p.stabled()) return a.equals("bring_out") || a.equals("rename") || a.equals("release");
        return switch (a) {
            case "summon", "stay", "follow", "stable" -> p.alive();
            case "work" -> p.alive() && m.work().enabled() && !p.working();
            case "revive" -> p.fallen();
            case "color", "colour" -> canColor(p);
            case "rename", "release" -> true;
            default -> false;
        };
    }

    private boolean canColor(Pet p) {
        return m.perks().has(p, Perks.Perk.AFFECTION) && !m.perks().colors().isEmpty();
    }

    // ------------------------------------------------------------------ the pets snapshot

    private void sendPets(ServerPlayer viewer, String open) {
        sendPets(viewer, open, false);
    }

    private void sendPets(ServerPlayer viewer, String open, boolean push) {
        if (viewer.hasDisconnected() || !modded(viewer)) return;
        UUID id = viewer.getUUID();
        Map<UUID, Double> health = new HashMap<>();
        JsonObject msg = petsSnapshot(viewer, health, false);
        if (push) {
            // A push that changes nothing isn't sent. Only pushes compare, so only they pay for the string, and the
            // same string goes out (a push never carries "open").
            String json = msg.toString();
            if (json.equals(sent.get(id))) return;
            sent.put(id, json);
            sentHealth.put(id, health);
            Companion.sendJson(viewer, "pets", json);
            return;
        }
        // This snapshot already shows every change marked so far: the next flush has nothing left to push.
        sent.remove(id);
        dirty.remove(id);
        sentHealth.put(id, health);
        if (open != null) msg.addProperty("open", open);
        Companion.send(viewer, msg);
    }

    /**
     * The {@code pets} message for this viewer, without {@code open}; each pet's rounded health goes into
     * {@code health}. {@code reference}: every pet built on its own, the way it was before the per-snapshot cache (the
     * game tests check both give the same JSON).
     */
    JsonObject petsSnapshot(ServerPlayer viewer, Map<UUID, Double> health, boolean reference) {
        UUID id = viewer.getUUID();
        JsonObject msg = Companion.message("pets");
        JsonObject cap = new JsonObject();
        int petCap = m.cap(viewer), stableCap = m.stable().capacity(viewer);
        List<Pet> parked = m.stable().parked(id);
        cap.addProperty("pets", petCap == Integer.MAX_VALUE ? -1 : petCap);
        cap.addProperty("active", m.countActive(id));
        cap.addProperty("stable", stableCap == Integer.MAX_VALUE ? -1 : stableCap);
        cap.addProperty("stabled", parked.size());
        msg.add("cap", cap);
        msg.addProperty("codex", m.codex().enabled());
        JsonArray colors = new JsonArray();
        for (String hex : m.perks().colors().values()) colors.add(hex.toUpperCase(Locale.ROOT));
        List<Pet> pets = m.store().of(id);
        pets.removeIf(Pet::stabled);
        pets.addAll(parked);
        JsonArray list = new JsonArray();
        Snap snap = reference ? null : new Snap(viewer, colors);
        for (Pet p : pets) {
            JsonObject o = reference ? petReference(viewer, p, colors) : pet(snap, p);
            health.put(p.id, o.get("health").getAsDouble());
            list.add(o);
        }
        msg.add("pets", list);
        return msg;
    }

    /**
     * What the pets of one snapshot share, worked out once for it and then dropped: the perk rows per bond level, the
     * plushie per species, world names, the "where" lines, the viewer's plushies (one inventory scan) and a few
     * settings. Entities, health, breeding and revive notes stay per pet and live.
     */
    private final class Snap {
        final ServerPlayer viewer;
        final JsonArray colors;
        final int maxLevel;
        final boolean workOn;
        final Map<Integer, JsonArray> perksByLevel = new HashMap<>();
        final Map<String, Optional<JsonObject>> plush = new HashMap<>();
        final Map<Level, String> worldOfLevel = new IdentityHashMap<>();
        final Map<List<Object>, String> worldOfSpot = new HashMap<>();
        final Map<String, String> whereLine = new HashMap<>();
        final Map<Integer, String> nearLine = new HashMap<>();
        Set<String> plushIds;

        Snap(ServerPlayer viewer, JsonArray colors) {
            this.viewer = viewer;
            this.colors = colors;
            this.maxLevel = m.maxLevel();
            this.workOn = m.work().enabled();
        }

        /** The same array for every pet of a level (never changed after it is made). */
        JsonArray perks(Pet p) {
            return perksByLevel.computeIfAbsent(p.level, k -> PetsCompanion.this.perks(p));
        }

        JsonObject plush(PetSpecies s) {
            if (s == null) return null;
            return plush.computeIfAbsent(s.plush() == null ? "" : s.plush(), k -> Optional.ofNullable(Wire.item(PlushItems.build(s.plush())))).orElse(null);
        }

        String world(Level level) {
            return worldOfLevel.computeIfAbsent(level, m::worldName);
        }

        String world(Pet.Spot spot) {
            if (spot == null) return m.worldName((Pet.Spot) null);
            return worldOfSpot.computeIfAbsent(java.util.Arrays.asList(spot.worldId(), spot.worldKey(), spot.worldName()), k -> m.worldName(spot));
        }

        /** {@code PetMenu.whereText} as plain text, each distinct line made once. */
        String where(Pet p, Entity e) {
            if (e != null && e.level() == viewer.level()) {
                int d = (int) Math.round(e.distanceTo(viewer));
                return nearLine.computeIfAbsent(d, k -> Adv.plain(m.msg("gui.where-near", Text.unparsed("distance", k))));
            }
            String world = e != null ? world(e.level()) : world(p.spot);
            String key = e != null ? "gui.where-world" : "gui.where-far";
            return whereLine.computeIfAbsent(key + '\n' + world, k -> Adv.plain(m.msg(key, Text.unparsed("world", world))));
        }

        /** {@code PetsModule.hasPlushie}: the viewer's plushies are read once per snapshot. */
        boolean hasPlushie(Pet p) {
            PetSpecies s = m.species(p.species);
            if (s == null || s.plush() == null) return false;
            if (plushIds == null) {
                plushIds = new HashSet<>();
                var inv = viewer.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    String id = PlushItems.id(inv.getItem(i));
                    if (id != null) plushIds.add(id.toLowerCase(Locale.ROOT));
                }
            }
            return plushIds.contains(s.plush().toLowerCase(Locale.ROOT));
        }
    }

    private JsonObject pet(Snap c, Pet p) {
        ServerPlayer viewer = c.viewer;
        PetSpecies s = m.species(p.species);
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id.toString());
        o.addProperty("species", p.species);
        o.addProperty("speciesName", m.speciesName(p));
        o.addProperty("kind", s == null ? "pet" : s.kind().name().toLowerCase(Locale.ROOT));
        o.addProperty("name", m.displayName(p));
        o.addProperty("custom", p.name != null && !p.name.isBlank());
        String entity = entities.get(p.species);
        if (entity != null) o.addProperty("entity", entity);
        JsonObject plush = c.plush(s);
        if (plush != null) o.add("plush", plush);
        Variants.Variant v = m.variants().get(p.species, p.variant);
        if (v != null) {
            JsonObject variant = new JsonObject();
            variant.addProperty("key", v.key());
            variant.addProperty("name", v.name());
            variant.addProperty("tint", hex(v.tint()));
            o.add("variant", variant);
        }
        o.addProperty("shiny", p.shiny);
        o.addProperty("level", p.level);
        o.addProperty("maxLevel", c.maxLevel);
        o.addProperty("xp", p.xp);
        o.addProperty("xpLevel", m.levelXp(p.level));
        o.addProperty("xpNext", m.nextLevelXp(p));
        o.add("perks", c.perks(p));
        Entity e = m.entity(p);
        double hp = p.health, max = p.maxHealth;
        if (e instanceof LivingEntity le) {
            hp = le.getHealth();
            max = le.getMaxHealth();
        }
        o.addProperty("health", round(hp));
        o.addProperty("maxHealth", round(max));
        o.addProperty("state", p.state.name().toLowerCase(Locale.ROOT));
        o.addProperty("working", p.working());
        o.addProperty("staying", p.stay);
        o.addProperty("loaded", e != null);
        o.addProperty("out", e != null);
        o.addProperty("canWork", e != null && c.workOn && !p.working());
        if (p.alive()) o.addProperty("where", c.where(p, e));
        else if (p.fallen()) o.addProperty("where", c.world(p.spot));
        if (p.color != null && m.perks().has(p, Perks.Perk.AFFECTION)) o.addProperty("color", p.color.toUpperCase(Locale.ROOT));
        boolean canColor = canColor(p) && !p.stabled();
        o.addProperty("canColor", canColor);
        if (canColor) o.add("colors", c.colors);
        if (p.alive()) {
            Component breed = m.breedingLine(p);
            if (breed != null) o.addProperty("breeding", Adv.plain(breed));
        }
        if (s != null && s.heal() != null) o.addProperty("heal", BuiltInRegistries.ITEM.getKey(s.heal()).toString());
        if (p.fallen()) {
            Pet.Spot at = p.spot;
            o.addProperty("fallenAt", at == null ? "?" : (int) Math.floor(at.x()) + ", " + (int) Math.floor(at.y()) + ", " + (int) Math.floor(at.z()));
            o.addProperty("reviveNote", Adv.plain(m.msg(m.perks().freeReviveReady(p) ? "dialog.revive-tooltip-free"
                    : c.hasPlushie(p) ? "dialog.revive-tooltip" : "dialog.revive-tooltip-missing", m.petTags(p))));
        }
        return o;
    }

    /** One pet built on its own (the code before the per-snapshot cache), for {@link #petsSnapshot} with reference. */
    private JsonObject petReference(ServerPlayer viewer, Pet p, JsonArray colors) {
        PetSpecies s = m.species(p.species);
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id.toString());
        o.addProperty("species", p.species);
        o.addProperty("speciesName", m.speciesName(p));
        o.addProperty("kind", s == null ? "pet" : s.kind().name().toLowerCase(Locale.ROOT));
        o.addProperty("name", m.displayName(p));
        o.addProperty("custom", p.name != null && !p.name.isBlank());
        String entity = entities.get(p.species);
        if (entity != null) o.addProperty("entity", entity);
        JsonObject plush = s == null ? null : Wire.item(PlushItems.build(s.plush()));
        if (plush != null) o.add("plush", plush);
        Variants.Variant v = m.variants().get(p.species, p.variant);
        if (v != null) {
            JsonObject variant = new JsonObject();
            variant.addProperty("key", v.key());
            variant.addProperty("name", v.name());
            variant.addProperty("tint", hex(v.tint()));
            o.add("variant", variant);
        }
        o.addProperty("shiny", p.shiny);
        o.addProperty("level", p.level);
        o.addProperty("maxLevel", m.maxLevel());
        o.addProperty("xp", p.xp);
        o.addProperty("xpLevel", m.levelXp(p.level));
        o.addProperty("xpNext", m.nextLevelXp(p));
        o.add("perks", perks(p));
        Entity e = m.entity(p);
        double hp = p.health, max = p.maxHealth;
        if (e instanceof LivingEntity le) {
            hp = le.getHealth();
            max = le.getMaxHealth();
        }
        o.addProperty("health", round(hp));
        o.addProperty("maxHealth", round(max));
        o.addProperty("state", p.state.name().toLowerCase(Locale.ROOT));
        o.addProperty("working", p.working());
        o.addProperty("staying", p.stay);
        o.addProperty("loaded", e != null);
        o.addProperty("out", e != null);
        o.addProperty("canWork", e != null && m.work().enabled() && !p.working());
        if (p.alive()) o.addProperty("where", Adv.plain(m.menu().whereText(viewer, p, e)));
        else if (p.fallen()) o.addProperty("where", m.worldName(p.spot));
        if (p.color != null && m.perks().has(p, Perks.Perk.AFFECTION)) o.addProperty("color", p.color.toUpperCase(Locale.ROOT));
        boolean canColor = canColor(p) && !p.stabled();
        o.addProperty("canColor", canColor);
        if (canColor) o.add("colors", colors);
        if (p.alive()) {
            Component breed = m.breedingLine(p);
            if (breed != null) o.addProperty("breeding", Adv.plain(breed));
        }
        if (s != null && s.heal() != null) o.addProperty("heal", BuiltInRegistries.ITEM.getKey(s.heal()).toString());
        if (p.fallen()) {
            Pet.Spot at = p.spot;
            o.addProperty("fallenAt", at == null ? "?" : (int) Math.floor(at.x()) + ", " + (int) Math.floor(at.y()) + ", " + (int) Math.floor(at.z()));
            o.addProperty("reviveNote", Adv.plain(m.msg(m.perks().freeReviveReady(p) ? "dialog.revive-tooltip-free"
                    : m.hasPlushie(viewer, p) ? "dialog.revive-tooltip" : "dialog.revive-tooltip-missing", m.petTags(p))));
        }
        return o;
    }

    private JsonArray perks(Pet p) {
        JsonArray arr = new JsonArray();
        // The words are parsed once per reload (Perks.rows), not per pet per snapshot.
        for (Perks.Row r : m.perks().rows()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.key());
            o.addProperty("name", r.name());
            o.addProperty("desc", r.desc());
            o.addProperty("unlocked", p.level >= r.at());
            o.addProperty("at", r.at());
            arr.add(o);
        }
        return arr;
    }

    // ------------------------------------------------------------------ the codex snapshot

    /**
     * The whole codex for one player (docs/companion-protocol.md, "codex"): tabs, realms, counts and every entry. An
     * undiscovered entry carries only its tab, realms, creature (drawn as a silhouette) and habitat hint.
     */
    private void sendCodex(ServerPlayer viewer, String category, String species) {
        Companion.send(viewer, codexSnapshot(viewer, category, species));
    }

    /** The {@code codex} message for this player (also read by the game tests). */
    JsonObject codexSnapshot(ServerPlayer viewer, String category, String species) {
        Codex codex = m.codex();
        Codex.Book b = codex.book(viewer.getUUID());
        boolean variantsOn = m.variants().enabled();
        Hooks.Bestiary bestiary = m.feather().service(Hooks.Bestiary.class);
        JsonArray entries = new JsonArray();
        for (Codex.Entry e : codex.entries()) {
            boolean isTamed = b.tamed.contains(e.id), isSeen = codex.seen(b, e.id);
            JsonObject o = new JsonObject();
            o.addProperty("species", e.id);
            o.addProperty("state", isTamed ? "tamed" : isSeen ? "seen" : "unknown");
            o.addProperty("category", e.category);
            JsonArray realms = new JsonArray();
            for (String r : e.realms) realms.add(r);
            o.add("realms", realms);
            o.addProperty("tameable", e.tameable);
            o.addProperty("danger", e.dangerKey);
            o.addProperty("entity", e.entity);
            if (e.swarm) o.addProperty("preview", "swarm");
            // The words were parsed once per reload (Codex.plainText); the checks stay on the raw fields.
            if (!e.habitat.isEmpty()) o.addProperty("habitat", e.pHabitat);
            if (isSeen) {
                o.addProperty("name", e.pName);
                o.addProperty("kind", e.pKind);
                o.addProperty("dangerText", e.pDanger);
                if (e.colorHex != null) o.addProperty("color", e.colorHex);
                putIf(o, "about", e.about, e.pAbout);
                putIf(o, "behavior", e.behavior, e.pBehavior);
                putIf(o, "tame", e.tame, e.pTame);
                putIf(o, "odds", e.odds, e.pOdds);
                putIf(o, "ability", e.job, e.pJob);
                putIf(o, "bond", e.bond, e.pBond);
                if (isTamed) putIf(o, "heal", e.heal, e.pHeal);
                JsonObject stats = new JsonObject();
                stats.addProperty("health", e.health);
                if (e.damage > 0) stats.addProperty("attack", e.damage);
                if (e.armor > 0) stats.addProperty("armor", e.armor);
                if (e.exp > 0) stats.addProperty("xp", e.exp);
                o.add("stats", stats);
                JsonArray drops = new JsonArray();
                for (Codex.Drop d : e.drops) {
                    JsonObject dj = new JsonObject();
                    dj.addProperty("item", d.item());
                    dj.addProperty("min", d.min());
                    dj.addProperty("max", d.max());
                    dj.addProperty("chance", d.chance());
                    drops.add(dj);
                }
                if (!drops.isEmpty()) o.add("drops", drops);
                if (e.summonItem != null) {
                    JsonObject summon = new JsonObject();
                    Item it = Codex.item(e.summonItem);
                    summon.addProperty("item", it == null ? e.summonItem : BuiltInRegistries.ITEM.getKey(it).toString());
                    if (e.summonWhere != null) summon.addProperty("where", e.pSummonWhere);
                    o.add("summon", summon);
                }
                if (!e.phases.isEmpty()) {
                    JsonArray phases = new JsonArray();
                    for (String line : e.pPhases) phases.add(line);
                    o.add("phases", phases);
                }
                JsonArray variants = new JsonArray();
                if (variantsOn) {
                    for (Variants.Variant v : m.variants().of(e.id)) {
                        JsonObject vo = new JsonObject();
                        boolean has = b.variants.contains(e.id + ":" + v.key());
                        if (has) {
                            vo.addProperty("key", v.key());
                            vo.addProperty("name", v.name());
                            vo.addProperty("tint", hex(v.tint()));
                        }
                        vo.addProperty("seen", has);
                        variants.add(vo);
                    }
                }
                o.add("variants", variants);
                o.addProperty("shinySeen", b.variants.contains(e.id + ":shiny"));
                Hooks.Bestiary.BestiaryMob line = bestiary == null ? null : bestiary.bestiaryMob(viewer.getUUID(), e.id);
                if (line != null) {
                    JsonObject bj = new JsonObject();
                    bj.addProperty("harvested", line.harvested());
                    bj.addProperty("slain", line.slain());
                    bj.addProperty("page", line.page());
                    o.add("bestiary", bj);
                }
            }
            // forms: always listed (so the screen can count them), named once seen
            JsonArray forms = new JsonArray();
            for (Codex.Form f : e.forms) {
                JsonObject fo = new JsonObject();
                boolean seenForm = b.forms.contains(f.id());
                fo.addProperty("species", f.id());
                fo.addProperty("entity", f.entity());
                fo.addProperty("seen", seenForm);
                if (seenForm && isSeen) {
                    fo.addProperty("name", f.plainName());
                    if (!f.note().isEmpty()) fo.addProperty("note", f.plainNote());
                    fo.addProperty("health", f.health());
                    if (f.damage() > 0) fo.addProperty("attack", f.damage());
                }
                forms.add(fo);
            }
            if (!forms.isEmpty()) o.add("forms", forms);
            entries.add(o);
        }
        JsonObject msg = Companion.message("codex");
        msg.addProperty("open", "codex");
        if (category != null) msg.addProperty("category", category);
        if (species != null) msg.addProperty("species", species);
        JsonObject counts = new JsonObject();
        int tamed = codex.tamedCount(b);
        counts.addProperty("seen", codex.seenCount(b));
        counts.addProperty("tamed", tamed);
        counts.addProperty("total", codex.total());
        counts.addProperty("tameable", codex.tameableTotal());
        msg.add("counts", counts);
        // Built once per reload; copied so the message (and tests reading it) never shares the codex's arrays.
        msg.add("categories", codex.categoriesJson().deepCopy());
        msg.add("realms", codex.realmsJson().deepCopy());
        if (bestiary != null) {
            JsonArray pages = new JsonArray();
            for (Hooks.Bestiary.BestiaryPage pg : bestiary.bestiaryPages(viewer.getUUID())) {
                JsonObject po = new JsonObject();
                po.addProperty("id", pg.id());
                po.addProperty("name", pg.name());
                po.addProperty("done", pg.done());
                po.addProperty("total", pg.total());
                po.addProperty("reward", pg.reward());
                po.addProperty("paid", pg.paid());
                pages.add(po);
            }
            msg.add("bestiary", pages);
        }
        Component next = codex.nextMilestoneLine(b, tamed);
        if (next != null) msg.addProperty("next", Adv.plain(next));
        msg.add("entries", entries);
        return msg;
    }

    /** Adds the cached words {@code plain} when the raw text is there. */
    private static void putIf(JsonObject o, String key, String raw, String plain) {
        if (raw != null && !raw.isEmpty()) o.addProperty(key, plain);
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static double round(double d) {
        return Math.round(d * 10) / 10.0;
    }
}
