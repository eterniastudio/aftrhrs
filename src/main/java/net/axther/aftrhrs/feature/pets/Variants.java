package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rare looks, rolled when a creature is tamed or born: a per-species color variant (pets.yml
 * {@code variants.species}) and, separately, a shiny roll. Both live in the pet record, so they survive death, revive,
 * the stable, renames, restarts and chunk reloads. On the creature the variant is its tint (multiplied over the
 * texture on clients) and the shiny flag; set directly on the entity, no polling. A shiny pet sparkles now and then
 * and wears a ✦ before its name.
 */
final class Variants {
    /** One color variant of a species. {@code tint} is 0xRRGGBB, multiplied over the texture. */
    record Variant(String key, String name, int tint, double weight) {
    }

    /** The outcome of a roll. {@code variant} is null for the normal look. */
    record Roll(String variant, boolean shiny) {
    }

    private final PetsModule m;
    private final Map<String, List<Variant>> bySpecies = new LinkedHashMap<>();
    /** Admin/test hook: the next roll for this player gives exactly this. */
    private final Map<UUID, Roll> forced = new ConcurrentHashMap<>();
    private boolean enabled = true;
    private double colorChance = 0.05, shinyChance = 1.0 / 150;
    private String symbol = "✦";
    private SimpleParticleType sparkle;
    private int sparkleEvery = 3, sparkleCount = 2;
    private long seconds;

    Variants(PetsModule module) {
        this.m = module;
    }

    void reload(Yaml cfg) {
        enabled = cfg.getBoolean("variants.enabled", true);
        colorChance = clamp(cfg.getDouble(net.axther.aftrhrs.core.Legacy.key(cfg, "variants.color-chance", "variants.colour-chance"), 0.05));
        shinyChance = clamp(cfg.getDouble("variants.shiny-chance", 1.0 / 150));
        symbol = cfg.getString("variants.shiny.symbol", "✦");
        if (symbol == null || symbol.isBlank()) symbol = "✦";
        sparkleEvery = Math.max(1, cfg.getInt("variants.shiny.every-seconds", 3));
        sparkleCount = Math.max(1, cfg.getInt("variants.shiny.count", 2));
        String p = cfg.getString("variants.shiny.particle", "FIREWORK");
        sparkle = PetUtil.particle(p);
        if (sparkle == null && p != null && !p.isBlank() && !p.equalsIgnoreCase("none")) {
            m.feather().getLogger().warn("pets.yml variants.shiny.particle: unknown or not a simple particle: {}", p);
        }
        bySpecies.clear();
        Yaml sec = PetsModule.section(cfg, "variants.species");
        if (sec == null) return;
        for (String species : sec.getKeys(false)) {
            Yaml s = sec.getConfigurationSection(species);
            if (s == null) continue;
            List<Variant> list = new ArrayList<>();
            for (String key : s.getKeys(false)) {
                Yaml v = s.getConfigurationSection(key);
                String hex = v == null ? s.getString(key) : v.getString("tint");
                TextColor c = hex == null ? null : TextColor.fromHexString(hex.trim());
                if (c == null) {
                    m.feather().getLogger().warn("pets.yml variants.species.{}.{}: tint must be '#rrggbb'.", species, key);
                    continue;
                }
                String name = v == null ? pretty(key) : v.getString("name", pretty(key));
                double weight = v == null ? 1 : Math.max(0, v.getDouble("weight", 1));
                list.add(new Variant(key.toLowerCase(Locale.ROOT), name, c.value(), weight));
            }
            if (!list.isEmpty()) bySpecies.put(species, List.copyOf(list));
        }
    }

    private static double clamp(double d) {
        return Double.isFinite(d) ? Math.max(0, Math.min(1, d)) : 0;
    }

    private static String pretty(String key) {
        String k = key.replace('_', ' ').replace('-', ' ');
        return k.isEmpty() ? key : Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    boolean enabled() {
        return enabled;
    }

    String symbol() {
        return symbol;
    }

    List<Variant> of(String species) {
        return bySpecies.getOrDefault(species, List.of());
    }

    Variant get(String species, String key) {
        if (key == null) return null;
        for (Variant v : of(species)) if (v.key().equalsIgnoreCase(key)) return v;
        return null;
    }

    // ------------------------------------------------------------------ rolling

    void force(UUID player, String variant, boolean shiny) {
        forced.put(player, new Roll(variant, shiny));
    }

    /** A fresh roll for a tame (no parents) or a birth (two parents). */
    Roll roll(UUID owner, String species, Pet a, Pet b) {
        Roll f = forced.remove(owner);
        if (f != null) {
            Variant v = get(species, f.variant());
            return new Roll(v != null ? v.key() : null, f.shiny());
        }
        if (!enabled) return new Roll(null, false);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String variant = null;
        List<String> inherited = new ArrayList<>();
        for (Pet parent : new Pet[]{a, b}) {
            if (parent != null && parent.variant != null && get(species, parent.variant) != null) inherited.add(parent.variant);
        }
        if (!inherited.isEmpty() && r.nextDouble() < m.breeding().inheritChance()) {
            variant = inherited.get(r.nextInt(inherited.size()));
        } else if (r.nextDouble() < colorChance) {
            variant = pick(of(species), r);
        }
        double shinyOdds = shinyChance;
        if ((a != null && a.shiny) || (b != null && b.shiny)) shinyOdds = Math.min(1, shinyOdds * m.breeding().shinyMultiplier());
        return new Roll(variant, r.nextDouble() < shinyOdds);
    }

    private static String pick(List<Variant> list, ThreadLocalRandom r) {
        double total = 0;
        for (Variant v : list) total += v.weight();
        if (total <= 0) return null;
        double x = r.nextDouble() * total;
        for (Variant v : list) {
            x -= v.weight();
            if (x < 0) return v.key();
        }
        return list.get(list.size() - 1).key();
    }

    // ------------------------------------------------------------------ on the creature

    /** Tint, variant key and shiny flag on the loaded creature (the shiny ✦ is part of the name, see Perks.name). */
    void apply(Pet p, ArcanaCreature c) {
        if (c == null) return;
        Variant v = get(p.species, p.variant);
        int tint = v == null ? 0xFFFFFF : v.tint();
        if (c.tint() != tint) c.setTint(tint);
        if (c.shiny() != p.shiny) c.setShiny(p.shiny);
        String key = v == null ? null : v.key();
        if (!java.util.Objects.equals(c.variant(), key)) c.setVariant(key);
    }

    /** Takes the shiny symbol off a name read back from a creature (so it never ends up in the record). */
    String strip(String name) {
        if (name == null) return null;
        String prefix = symbol + " ";
        while (name.startsWith(prefix)) name = name.substring(prefix.length());
        return name;
    }

    /** A released creature goes back to its normal look. */
    void clear(ArcanaCreature c) {
        if (c == null) return;
        c.setTint(0xFFFFFF);
        c.setShiny(false);
        c.setVariant(null);
    }

    /** Every second: shiny sparkles (every variants.shiny.every-seconds). */
    void tick() {
        seconds++;
        if (sparkle == null || seconds % sparkleEvery != 0) return;
        for (Pet p : m.store().loaded()) {
            if (!p.alive() || !p.shiny) continue;
            ArcanaCreature c = m.creature(p);
            if (c == null) continue;
            PetUtil.particles(c, sparkle, Math.max(0.4, c.getBbHeight() * 0.7), sparkleCount, 0.3, 0.3, 0.01);
        }
    }

    // ------------------------------------------------------------------ text

    /** "Autumn" in its tint color, then "✦ Shiny"; empty for a normal pet. */
    Component label(Pet p) {
        Variant v = get(p.species, p.variant);
        Component out = Component.empty();
        if (v != null) out = out.append(Component.text(v.name(), TextColor.color(v.tint())));
        if (p.shiny) {
            if (v != null) out = out.append(Component.text(" "));
            out = out.append(m.msg("variants.shiny-label", Text.unparsed("symbol", symbol)));
        }
        return out;
    }

    boolean special(Pet p) {
        return p.shiny || get(p.species, p.variant) != null;
    }

    TagResolver tags(Pet p) {
        Variant v = get(p.species, p.variant);
        return TagResolver.resolver(Text.component("variant", v == null ? Component.empty()
                        : Component.text(v.name(), TextColor.color(v.tint()))),
                Text.component("look", label(p)), Text.unparsed("symbol", symbol));
    }

    /** Chat lines after a tame or a birth: one for a color variant, one (plus a sound) for a shiny. */
    void announce(ServerPlayer owner, Pet p) {
        if (owner == null) return;
        TagResolver tags = TagResolver.resolver(m.petTags(p), tags(p));
        if (get(p.species, p.variant) != null) {
            m.send(owner, "variants.found-color", tags);
            if (!p.shiny) m.sound(owner, "variant");
        }
        if (p.shiny) {
            m.send(owner, "variants.found-shiny", tags);
            m.sound(owner, "shiny");
            ArcanaCreature c = m.creature(p);
            if (c != null) PetUtil.particles(c, ParticleTypes.FIREWORK, 0.8, 30, 0.4, 0.5, 0.08);
        }
    }

    List<String> allKeys() {
        List<String> out = new ArrayList<>();
        for (List<Variant> l : bySpecies.values()) for (Variant v : l) if (!out.contains(v.key())) out.add(v.key());
        return out;
    }
}
