package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Loc;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Breeding: two of your own pets of the same species, both at bond {@code breeding.min-bond} or more, both told to
 * stay close together. Feed each its tame item (the one from the taming guide) and after a short heart effect a baby
 * is born, tamed to you with a fresh bond. Each parent then rests for {@code breeding.cooldown-hours}. At the pet cap
 * the baby goes to the stable when it has room; with both full, breeding is refused before any item is used.
 */
final class Breeding {
    private final PetsModule m;
    private final Map<UUID, Long> fed = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    /** Fed pets whose treat came out of the owner's inventory: refunded if the birth fails. */
    private final Set<UUID> paid = ConcurrentHashMap.newKeySet();

    /** What a click with a treat did: nothing for breeding, used up here, or counted while the creature's own feeding eats it. */
    enum Fed { PASS, USED, PACK }

    private final Map<String, Item> items = new HashMap<>();
    private final Set<String> excluded = new HashSet<>();
    private final Set<PetSpecies.Kind> excludedKinds = new HashSet<>();

    private boolean enabled = true;
    private int minBond = 3;
    private double distance = 4;
    private long windowMs = 60_000, cooldownMs = 24 * 3_600_000L;
    private int delayTicks = 60;
    private double inheritChance = 0.5, shinyMultiplier = 2;

    Breeding(PetsModule module) {
        this.m = module;
    }

    void reload(Yaml cfg, Map<String, PetSpecies> species) {
        enabled = cfg.getBoolean("breeding.enabled", true);
        minBond = Math.max(0, Math.min(5, cfg.getInt("breeding.min-bond", 3)));
        distance = Math.max(1, Math.min(16, cfg.getDouble("breeding.distance", 4)));
        windowMs = (long) (Math.max(5, cfg.getDouble("breeding.feed-window-seconds", 60)) * 1000);
        delayTicks = (int) Math.max(0, Math.round(cfg.getDouble("breeding.delay-seconds", 3) * 20));
        cooldownMs = (long) (Math.max(0, cfg.getDouble("breeding.cooldown-hours", 24)) * 3_600_000L);
        inheritChance = Math.max(0, Math.min(1, cfg.getDouble("breeding.inherit-chance", 0.5)));
        shinyMultiplier = Math.max(1, cfg.getDouble("breeding.shiny-multiplier", 2));
        excluded.clear();
        for (String s : cfg.getStringList("breeding.exclude")) excluded.add(s.trim());
        excludedKinds.clear();
        for (String k : cfg.getStringList("breeding.exclude-kinds")) {
            try {
                excludedKinds.add(PetSpecies.Kind.valueOf(k.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                m.feather().getLogger().warn("pets.yml breeding.exclude-kinds: unknown kind {} (pet, mount or hostile).", k);
            }
        }
        // The treat: breeding.items.<species>, else the taming guide's tame item, else the pet's food.
        items.clear();
        Yaml guide;
        try {
            guide = m.feather().loadResourceConfig("guide.yml");
        } catch (RuntimeException e) {
            guide = new Yaml();
        }
        Yaml over = PetsModule.section(cfg, "breeding.items");
        for (PetSpecies s : species.values()) {
            Item item = null;
            if (over != null && over.isString(s.id())) item = PetUtil.item(over.getString(s.id(), ""));
            String tame = guide.getString("creatures." + s.id() + ".tame");
            if (item == null && tame != null) item = PetUtil.item(tame.trim());
            if (item == null) item = s.heal();
            if (item != null) items.put(s.id(), item);
        }
    }

    boolean enabled() {
        return enabled;
    }

    double inheritChance() {
        return inheritChance;
    }

    double shinyMultiplier() {
        return shinyMultiplier;
    }

    int minBond() {
        return minBond;
    }

    Item item(String species) {
        return items.get(species);
    }

    boolean allowed(String speciesId) {
        if (!enabled || excluded.contains(speciesId)) return false;
        PetSpecies s = m.species(speciesId);
        return s != null && !excludedKinds.contains(s.kind());
    }

    boolean pending(Pet p) {
        return pending.contains(p.id);
    }

    long restLeft(Pet p) {
        return Math.max(0, p.breedReadyAt - System.currentTimeMillis());
    }

    void resetCooldowns(UUID owner) {
        for (Pet p : m.store().of(owner)) p.breedReadyAt = 0;
        fed.keySet().removeIf(id -> {
            Pet p = m.store().get(id);
            return p == null || p.owner.equals(owner);
        });
        paid.removeIf(id -> {
            Pet p = m.store().get(id);
            return p == null || p.owner.equals(owner);
        });
        m.store().markDirty();
    }

    // ------------------------------------------------------------------ feeding

    /**
     * The owner right-clicked their pet holding {@code held} in {@code hand}. USED: a breeding treat, the click is used
     * up here; PACK: fed, but the treat is also its food (the creature's feeding takes the item, so the click goes on);
     * PASS: normal handling.
     */
    Fed feed(ServerPlayer owner, Pet p, ItemStack held, InteractionHand hand) {
        Item item = held.isEmpty() ? null : held.getItem();
        if (!enabled || item == null || item != items.get(p.species) || !p.alive() || !p.owner.equals(owner.getUUID())) return Fed.PASS;
        ArcanaCreature e = m.creature(p);
        if (e == null) return Fed.PASS;
        if (m.onCooldown("breedfeed:" + p.id, 700)) return Fed.USED;   // both hands for one click
        TagResolver tags = TagResolver.resolver(m.petTags(p), Text.unparsed("item", PetUtil.pretty(item)), Text.unparsed("bond", minBond));
        PetSpecies s = m.species(p.species);
        boolean isFood = s != null && s.heal() == item;
        if (!allowed(p.species)) {
            if (isFood) return Fed.PASS;
            if (!m.onCooldown("breedhint:" + owner.getUUID(), 8000)) m.send(owner, "breeding.excluded", tags);
            return Fed.USED;
        }
        if (!p.stay) {
            // the same item can also be its food (Cloudlamb: wheat): then this is plain feeding
            if (isFood) return Fed.PASS;
            if (!m.onCooldown("breedhint:" + owner.getUUID(), 8000)) m.send(owner, "breeding.need-stay", tags);
            return Fed.USED;
        }
        if (p.level < minBond) {
            m.send(owner, "breeding.need-bond", tags);
            m.sound(owner, "error");
            return Fed.USED;
        }
        long rest = restLeft(p);
        if (rest > 0) {
            m.send(owner, "breeding.resting", tags, Text.unparsed("time", duration(rest)));
            m.sound(owner, "error");
            return Fed.USED;
        }
        if (pending.contains(p.id)) return Fed.USED;
        if (!room(owner)) {
            m.send(owner, "breeding.no-room", tags, roomTags(owner));
            m.sound(owner, "error");
            return Fed.USED;
        }
        Long before = fed.get(p.id);
        boolean already = before != null && System.currentTimeMillis() - before < windowMs;
        if (!already) {
            if (!isFood) {
                if (owner.isCreative()) {
                    paid.remove(p.id);
                } else if (!paid.contains(p.id)) {
                    // a treat from a feed whose window ran out is still owed back: re-feeding doesn't take a second one
                    held.shrink(1);
                    paid.add(p.id);
                }
            } else {
                paid.remove(p.id);
            }
            fed.put(p.id, System.currentTimeMillis());
            PetUtil.particles(e, ParticleTypes.HEART, Math.max(0.6, e.getBbHeight() * 0.9), 4, 0.3, 0.25, 0);
            m.sound(owner, "breed-feed");
        }
        Pet partner = partner(p, e);
        Fed result = !already && isFood ? Fed.PACK : Fed.USED;
        if (partner == null) {
            m.actionBar(owner, "breeding.fed", tags);
            return result;
        }
        start(owner, p, partner);
        return result;
    }

    /** Another of the owner's pets of this species, fed, staying and close by. */
    private Pet partner(Pet p, Entity e) {
        long now = System.currentTimeMillis();
        Pet best = null;
        double bestD = Double.MAX_VALUE;
        for (Pet o : m.store().of(p.owner)) {
            if (o == p || !o.alive() || !o.species.equals(p.species) || !o.stay || pending.contains(o.id)) continue;
            Long at = fed.get(o.id);
            if (at == null || now - at >= windowMs || o.level < minBond || restLeft(o) > 0) continue;
            Entity oe = m.entity(o);
            if (oe == null || oe.level() != e.level()) continue;
            double d = oe.distanceToSqr(e);
            if (d <= distance * distance && d < bestD) {
                best = o;
                bestD = d;
            }
        }
        return best;
    }

    private void start(ServerPlayer owner, Pet a, Pet b) {
        pending.add(a.id);
        pending.add(b.id);
        fed.remove(a.id);
        fed.remove(b.id);
        int refund = (paid.remove(a.id) ? 1 : 0) + (paid.remove(b.id) ? 1 : 0);
        m.actionBar(owner, "breeding.starting", m.petTags(a), Text.unparsed("partner", m.displayName(b)));
        int steps = Math.max(1, delayTicks / 10);
        for (int i = 0; i < steps; i++) {
            Tasks.later(Math.max(1, i * 10L), () -> {
                for (Pet p : new Pet[]{a, b}) {
                    Entity e = m.entity(p);
                    if (e != null) PetUtil.particles(e, ParticleTypes.HEART, Math.max(0.6, e.getBbHeight() * 0.9), 2, 0.35, 0.25, 0);
                }
            });
        }
        UUID ownerId = owner.getUUID();
        Tasks.later(Math.max(1, delayTicks), () -> {
            pending.remove(a.id);
            pending.remove(b.id);
            birth(ownerId, a, b, refund);
        });
    }

    private void birth(UUID ownerId, Pet a, Pet b, int refund) {
        ServerPlayer owner = m.feather().player(ownerId);
        if (owner == null) {
            // logged off during the countdown: no baby, and the treats drop by the parents instead of vanishing
            Entity at = m.entity(a) != null ? m.entity(a) : m.entity(b);
            Item item = items.get(a.species);
            if (at != null && item != null && refund > 0 && at.level() instanceof ServerLevel sl) at.spawnAtLocation(sl, new ItemStack(item, refund));
            return;
        }
        Entity ea = m.entity(a), eb = m.entity(b);
        if (m.store().get(a.id) != a || m.store().get(b.id) != b || ea == null || eb == null || !a.stay || !b.stay
                || ea.level() != eb.level() || ea.distanceToSqr(eb) > distance * distance * 4) {
            m.send(owner, "breeding.failed", m.petTags(a));
            m.sound(owner, "error");
            refund(owner, a.species, refund);
            return;
        }
        boolean slot = m.hasFreeSlot(owner, false);
        boolean stableRoom = m.stable().parked(ownerId).size() < m.stable().capacity(owner);
        if (!slot && !stableRoom) {
            m.send(owner, "breeding.no-room", m.petTags(a), roomTags(owner));
            m.sound(owner, "error");
            refund(owner, a.species, refund);
            return;
        }
        Pet baby = new Pet(UUID.randomUUID(), ownerId, a.species);
        baby.created = System.currentTimeMillis();
        baby.bred = true;
        Variants.Roll roll = m.variants().roll(ownerId, a.species, a, b);
        baby.variant = roll.variant();
        baby.shiny = roll.shiny();
        ServerLevel level = (ServerLevel) ea.level();
        Loc at = new Loc(level, (ea.getX() + eb.getX()) / 2, (ea.getY() + eb.getY()) / 2, (ea.getZ() + eb.getZ()) / 2);
        BlockPos bp = at.blockPos();
        if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) at = Loc.of(ea);
        if (slot) {
            m.store().add(baby);
            Entity le = m.spawnPet(baby, at, 1.0);
            if (le == null) {
                m.store().remove(baby);
                m.send(owner, "breeding.spawn-failed", m.petTags(a));
                m.sound(owner, "error");
                refund(owner, a.species, refund);
                return;
            }
            PetUtil.particles(le, ParticleTypes.HEART, 0.6, 8, 0.4, 0.3, 0);
        } else {
            baby.state = Pet.State.STABLED;
            baby.stabledAt = System.currentTimeMillis();
            baby.spot = Pet.Spot.of(at);
            m.store().add(baby);
        }
        long until = System.currentTimeMillis() + cooldownMs;
        a.breedReadyAt = until;
        b.breedReadyAt = until;
        m.store().journal(baby, a, b);
        m.feather().stat(ownerId, "pets-bred", 1);
        if (m.codex().tamed(ownerId, baby.species, false)) m.codex().announceTamed(ownerId, baby.species);   // as before: the born line doesn't say it
        m.codex().found(ownerId, baby);
        TagResolver tags = TagResolver.resolver(m.petTags(baby), Text.unparsed("count", m.countActive(ownerId)),
                Text.unparsed("cap", m.capLabel(owner)), roomTags(owner));
        m.send(owner, slot ? "breeding.born" : "breeding.born-stable", tags);
        m.sound(owner, "born");
        m.variants().announce(owner, baby);
        m.changed(ownerId);
    }

    // ------------------------------------------------------------------ helpers

    boolean room(ServerPlayer owner) {
        return m.hasFreeSlot(owner, false) || m.stable().parked(owner.getUUID()).size() < m.stable().capacity(owner);
    }

    private TagResolver roomTags(ServerPlayer owner) {
        return TagResolver.resolver(Text.unparsed("count", m.countActive(owner.getUUID())), Text.unparsed("cap", m.capLabel(owner)),
                Text.unparsed("stabled", m.stable().parked(owner.getUUID()).size()),
                Text.unparsed("capacity", m.stable().capacityLabel(owner)));
    }

    /** Gives back the treats a failed birth used. */
    private void refund(ServerPlayer owner, String species, int n) {
        Item item = items.get(species);
        if (n <= 0 || item == null) return;
        PetUtil.give(owner, new ItemStack(item, n));
        m.send(owner, "breeding.refunded", Text.unparsed("item", PetUtil.pretty(item)), Text.unparsed("count", n));
    }

    /** "3h 20m", "12m", "40s". */
    static String duration(long ms) {
        long s = Math.max(1, ms / 1000);
        long h = s / 3600, mins = (s % 3600) / 60;
        if (h > 0) return mins > 0 ? h + "h " + mins + "m" : h + "h";
        if (mins > 0) return mins + "m";
        return s + "s";
    }
}
