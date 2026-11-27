package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Legacy;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bond perks: what Feather itself gives a pet (and its owner) as the bond grows, on top of the abilities' bond
 * scaling. Everything is read from the pet record at the moment it matters (an event, the 1 s timer), so a perk is
 * live from spawn/load/revive on and gone the moment the pet is released. The name color (Affection) is now worn by
 * the creature's own name tag; the Devoted title shows in the menus.
 */
final class Perks {
    enum Perk {
        AFFECTION("affection", 1), SURE_FOOTED("sure-footed", 2), COMFORT("comfort", 3), SECOND_WIND("second-wind", 4),
        DEVOTED("devoted", 5);

        final String key;
        final int defaultLevel;

        Perk(String key, int defaultLevel) {
            this.key = key;
            this.defaultLevel = defaultLevel;
        }
    }

    /** A turned-on perk as the mod's pets snapshot lists it: plain name and description, and the unlock level. */
    record Row(String key, String name, String desc, int at) {
    }

    private final PetsModule m;
    private final Map<Perk, Integer> levels = new EnumMap<>(Perk.class);
    /** {@link #rows()}, built on first use after each reload (so the messages and palette are loaded by then). */
    private List<Row> rows;
    /** Color name -> hex, in the order the picker shows them. */
    private final Map<String, String> colors = new LinkedHashMap<>();
    private final Map<UUID, Vec3> lastSpot = new HashMap<>();
    private final Map<UUID, Integer> stuckStrikes = new HashMap<>();
    private long seconds;

    private int pettingXp;
    private long pettingCooldownMs;
    private boolean pettingSneak;
    private double stuckDistance;
    private double comfortRadius, comfortHeal, comfortBelow;
    private int comfortSeconds;
    private long comfortCooldownMs;
    private long freeReviveMs;
    private String title;
    private SimpleParticleType aura;
    private boolean broadcast;

    Perks(PetsModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        rows = null;
        levels.clear();
        for (Perk p : Perk.values()) levels.put(p, cfg.getInt("perks." + p.key + ".level", p.defaultLevel));
        pettingXp = Math.max(0, cfg.getInt("perks.petting.xp", 3));
        pettingCooldownMs = (long) (Math.max(1, cfg.getDouble("perks.petting.cooldown-seconds", 300)) * 1000);
        pettingSneak = cfg.getBoolean("perks.petting.sneak", false);
        stuckDistance = Math.max(8, cfg.getDouble("perks.sure-footed.stuck-distance", 20));
        comfortRadius = Math.max(1, cfg.getDouble("perks.comfort.radius", 12));
        comfortHeal = Math.max(0, cfg.getDouble("perks.comfort.pet-heal", 1.0));
        comfortBelow = cfg.getDouble("perks.comfort.owner-below", 0.3);
        comfortSeconds = Math.max(1, cfg.getInt("perks.comfort.owner-regen-seconds", 5));
        comfortCooldownMs = (long) (Math.max(1, cfg.getDouble("perks.comfort.owner-cooldown-seconds", 300)) * 1000);
        freeReviveMs = (long) (Math.max(0.1, cfg.getDouble("perks.second-wind.hours", 24)) * 3_600_000L);
        title = cfg.getString("perks.devoted.title", "Devoted");
        broadcast = cfg.getBoolean("perks.devoted.broadcast", false);
        String a = cfg.getString("perks.devoted.aura", "END_ROD");
        aura = PetUtil.particle(a);
        if (aura == null && a != null && !a.isBlank() && !a.equalsIgnoreCase("none")) {
            m.feather().getLogger().warn("pets.yml perks.devoted.aura: unknown or not a simple particle: {}", a);
        }
        colors.clear();
        Yaml cs = PetsModule.section(cfg, Legacy.key(cfg, "perks.affection.colors", "perks.affection.colours"));
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                String hex = cs.getString(k, "");
                if (TextColor.fromHexString(hex) != null) colors.put(k, hex.toLowerCase(Locale.ROOT));
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /** The bond level a perk unlocks at, or 0 when it is turned off. */
    int level(Perk perk) {
        int l = levels.getOrDefault(perk, 0);
        return l < 1 || l > 5 ? 0 : l;
    }

    boolean has(Pet p, Perk perk) {
        int l = level(perk);
        return l > 0 && p.level >= l;
    }

    Map<String, String> colors() {
        return colors;
    }

    String title() {
        return title;
    }

    boolean pettingSneak() {
        return pettingSneak;
    }

    /** The name color the pet wears (Affection unlocked and picked), or null. */
    TextColor color(Pet p) {
        return has(p, Perk.AFFECTION) && p.color != null ? TextColor.fromHexString(p.color) : null;
    }

    /** The pet's name as a component: its picked color while Affection is unlocked, a shiny's ✦ before it. */
    Component name(Pet p) {
        Component c = Component.text(m.displayName(p));
        TextColor color = color(p);
        if (color != null) c = c.color(color);
        if (p.shiny && m.variants() != null) c = Component.text(m.variants().symbol() + " ", TextColor.color(0xFFE38A)).append(c);
        return c;
    }

    boolean freeReviveReady(Pet p) {
        return has(p, Perk.SECOND_WIND) && System.currentTimeMillis() - p.freeReviveAt >= freeReviveMs;
    }

    long freeReviveIn(Pet p) {
        if (!has(p, Perk.SECOND_WIND)) return -1;
        return Math.max(0, freeReviveMs - (System.currentTimeMillis() - p.freeReviveAt));
    }

    void usedFreeRevive(Pet p) {
        p.freeReviveAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ events

    /** Sure-footed: no fall damage. */
    boolean noFallDamage(Pet p) {
        return p != null && p.alive() && has(p, Perk.SURE_FOOTED);
    }

    /** The owner pets their pet (right-click with an empty hand): bond XP once per cooldown, hearts with Affection. */
    void pet(ServerPlayer owner, Pet p, ArcanaCreature e) {
        if (m.onCooldown("petfx:" + p.id, 600)) return;   // both hands fire for one click
        boolean xp = pettingXp > 0 && !m.onCooldown("petxp:" + p.id, pettingCooldownMs);
        if (has(p, Perk.AFFECTION) && e != null) PetUtil.particles(e, ParticleTypes.HEART, Math.max(0.6, e.getBbHeight() * 0.9), 3, 0.3, 0.2, 0);
        m.actionBar(owner, xp ? "perks.petted-xp" : "perks.petted", m.petTags(p), Text.unparsed("amount", pettingXp));
        // no owner-side sound: the creature plays its own petting sound (CreatureSfx "pet"), a purr only for cats
        if (xp) m.addXp(p, pettingXp);
    }

    /** A pet reached a new bond level: tell the owner what it unlocked. */
    void onLevelUp(Pet p, int from, int to, ServerPlayer owner) {
        if (owner == null) return;
        for (Perk perk : Perk.values()) {
            int l = level(perk);
            if (l <= from || l > to) continue;
            m.send(owner, "perks.unlocked", m.petTags(p), Text.component("perk", m.msg("perks." + perk.key + ".name")),
                    Text.component("description", m.msg("perks." + perk.key + ".description")));
        }
        int devoted = level(Perk.DEVOTED);
        if (devoted > 0 && from < devoted && to >= devoted) {
            m.send(owner, "perks.devoted.celebration", m.petTags(p), Text.unparsed("title", title));
            if (broadcast) {
                Component line = m.msg("perks.devoted.broadcast", m.petTags(p), Text.unparsed("title", title),
                        Text.unparsed("player", Names.name(owner)));
                for (ServerPlayer other : m.feather().onlinePlayers()) if (other != owner) Adv.send(other, line);
            }
            ArcanaCreature e = m.creature(p);
            if (e != null) PetUtil.particles(e, ParticleTypes.HEART, 0.8, 12, 0.6, 0.5, 0);
            m.sound(owner, "devoted");
        }
    }

    // ------------------------------------------------------------------ the 1 s timer

    void tick() {
        seconds++;
        boolean slow = seconds % 5 == 0;
        for (Pet p : m.store().loaded()) {
            if (!p.alive() || p.level < 1) continue;
            boolean showAura = aura != null && has(p, Perk.DEVOTED);
            boolean sure = slow && has(p, Perk.SURE_FOOTED) && !p.stay;
            boolean comfort = slow && has(p, Perk.COMFORT);
            if (!showAura && !sure && !comfort) continue;
            ArcanaCreature e = m.creature(p);
            if (e == null) continue;
            if (showAura) PetUtil.particles(e, aura, Math.max(0.4, e.getBbHeight() * 0.6), 2, 0.35, 0.35, 0.01);
            if (!sure && !comfort) continue;
            ServerPlayer owner = m.feather().player(p.owner);
            if (owner == null || owner.isDeadOrDying() || owner.isSpectator() || owner.level() != e.level()) {
                lastSpot.remove(p.id);
                continue;
            }
            double dist = owner.distanceTo(e);
            if (comfort && dist <= comfortRadius) comfort(p, e, owner);
            if (sure) unstick(p, e, owner, dist);
        }
        if (slow && lastSpot.size() > 4096) {
            lastSpot.clear();
            stuckStrikes.clear();
        }
    }

    /** Comfort: the pet heals slowly near its owner; an owner below the threshold gets a short Regeneration I. */
    private void comfort(Pet p, ArcanaCreature e, ServerPlayer owner) {
        if (comfortHeal > 0 && e.isAlive() && e.getHealth() < e.getMaxHealth()) e.setHealth((float) Math.min(e.getMaxHealth(), e.getHealth() + comfortHeal));
        GameType gm = owner.gameMode.getGameModeForPlayer();
        if (gm != GameType.SURVIVAL && gm != GameType.ADVENTURE) return;
        double max = owner.getMaxHealth();
        if (max <= 0 || owner.getHealth() / max >= comfortBelow) return;
        if (m.onCooldown("comfort:" + owner.getUUID(), comfortCooldownMs)) return;
        owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, comfortSeconds * 20, 0, true, true, true));
        PetUtil.particles(owner, ParticleTypes.HEART, 1.8, 3, 0.3, 0.2, 0);
        m.actionBar(owner, "perks.comfort.owner", m.petTags(p));
    }

    /**
     * Sure-footed: a following pet that is far from its owner and hasn't moved for two checks (10 s), or is beyond
     * follow.far-distance, is brought next to the owner. Only while the owner stands on the ground and isn't riding.
     */
    private void unstick(Pet p, ArcanaCreature e, ServerPlayer owner, double dist) {
        Vec3 now = e.position();
        Vec3 before = lastSpot.put(p.id, now);
        if (dist <= stuckDistance) {
            stuckStrikes.remove(p.id);
            return;
        }
        if (!owner.onGround() || owner.isPassenger() || owner.isFallFlying() || owner.getAbilities().flying) return;
        boolean still = before != null && before.distanceToSqr(now) < 1.0;
        int strikes = still ? stuckStrikes.merge(p.id, 1, Integer::sum) : 0;
        if (!still) stuckStrikes.remove(p.id);
        if (strikes >= 2 || dist > m.farDistance()) {
            stuckStrikes.remove(p.id);
            m.bring(p, owner, false, o -> {
                if (o == PetsModule.Outcome.MOVED || o == PetsModule.Outcome.RESPAWNED) lastSpot.remove(p.id);
            });
        }
    }

    // ------------------------------------------------------------------ menu text

    /** One line per enabled perk (unlocked or locked), then the next unlock. */
    Component describe(Pet p) {
        List<Component> lines = new ArrayList<>();
        Perk next = null;
        for (Perk perk : sorted()) {
            int l = level(perk);
            TagResolver tags = TagResolver.resolver(Text.unparsed("perk-level", l),
                    Text.component("perk", m.msg("perks." + perk.key + ".name")),
                    Text.component("description", m.msg("perks." + perk.key + ".description")));
            boolean on = p.level >= l;
            lines.add(m.msg(on ? "perks.line-unlocked" : "perks.line-locked", tags));
            if (!on && next == null) next = perk;
        }
        if (lines.isEmpty()) return Component.empty();
        if (next != null) {
            int l = level(next);
            lines.add(m.msg("perks.next", Text.component("perk", m.msg("perks." + next.key + ".name")),
                    Text.unparsed("perk-level", l), Text.unparsed("xp", Math.max(0, m.levelXp(l) - p.xp))));
        } else {
            lines.add(m.msg("perks.all-unlocked", m.petTags(p)));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** Short tooltip line: how many perks are unlocked and the next one. */
    Component summary(Pet p) {
        int on = 0;
        Perk next = null;
        for (Perk perk : sorted()) {
            if (p.level >= level(perk)) on++;
            else if (next == null) next = perk;
        }
        if (next == null) return m.text().item(m.raw("gui.pet-perks-all"), Text.unparsed("count", on));
        return m.text().item(m.raw("gui.pet-perks"), Text.unparsed("count", on),
                Text.component("perk", m.msg("perks." + next.key + ".name")), Text.unparsed("perk-level", level(next)));
    }

    /** {@link #sorted()} with each perk's plain text, parsed once per reload instead of once per pet per snapshot. */
    List<Row> rows() {
        List<Row> r = rows;
        if (r == null) {
            List<Row> out = new ArrayList<>();
            for (Perk perk : sorted()) {
                out.add(new Row(perk.key, Adv.plain(m.msg("perks." + perk.key + ".name")), Adv.plain(m.msg("perks." + perk.key + ".description")),
                        level(perk)));
            }
            rows = r = List.copyOf(out);
        }
        return r;
    }

    /** The perks that are turned on, by unlock level. */
    List<Perk> sorted() {
        List<Perk> out = new ArrayList<>();
        for (Perk p : Perk.values()) if (level(p) > 0) out.add(p);
        out.sort(Comparator.comparingInt(this::level));
        return out;
    }
}
