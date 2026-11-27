package net.axther.aftrhrs.feature.pets;

import net.axther.aftrhrs.core.Adv;
import net.axther.aftrhrs.core.Dialogs;
import net.axther.aftrhrs.core.GuideGlyphs;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Legacy;
import net.axther.aftrhrs.core.Names;
import net.axther.aftrhrs.core.Pdc;
import net.axther.aftrhrs.core.SafeYaml;
import net.axther.aftrhrs.core.Tasks;
import net.axther.aftrhrs.core.Text;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.ArcanaMob;
import net.axther.aftrhrs.entity.Species;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.AABB;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.IntList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * The creature codex: one entry for every creature the mod registers ({@link ArcanaEntities}, built from the species
 * data, so it can't drift), with the taming guide's text ({@code guide.yml}: tame item, odds, heal, job, bond) for
 * tameables and {@code codex.yml}'s for the rest (what it is, where it lives, what it does, danger, boss fights). Young
 * creatures ({@code young_of}) and {@code form-of} entities (Wukong's Hair Clone) are forms on their parent's page.
 *
 * <p>Per player every entry is undiscovered, seen (been within {@code codex.seen-radius} of one) or, for tameables,
 * tamed. Kept in {@code aftrhrs/data/pets-codex.yml} (same format as the Paper server's file, plus {@code forms}).
 * Tamed-count milestones from {@code codex.milestones} are given once each ("all" = every tameable creature).
 *
 * <p>Front doors: {@code /codex} and its aliases, the hub and the taming guide item ({@code feature/codex}, through
 * {@link Hooks.Codex}), {@code /pets codex}, {@code /bestiary} (Wildlife tab) and a pet's "View in Codex" button.
 * Modded players get the mod's codex screen ({@link PetsCompanion}), everyone else these dialogs.
 */
final class Codex {
    /** A drop: item id, amount range and chance. */
    record Drop(String item, int min, int max, double chance) {
    }

    /**
     * Another entity shown on an entry's page (a young creature, a boss's summoned copy). {@code plainName} and
     * {@code plainNote} are the words of {@code name} / {@code note} (MiniMessage parsed once per reload, for the mod).
     */
    record Form(String id, String entity, String name, String note, double health, double damage, String plainName, String plainNote) {
    }

    /** A codex tab. */
    record Category(String id, String name, String blurb, String item, String icon, String guideGroup, List<String> kinds) {
    }

    /** One codex entry. Fields are filled once in {@link #reload} and never changed. */
    static final class Entry {
        String id, entity, name, color, category, kind, danger, dangerKey;
        boolean tameable, swarm;
        String job = "", habitat = "", tame = "", odds = "", heal = "", bond = "", about = "", behavior = "";
        String summonItem, summonWhere;
        List<String> phases = List.of(), realms = List.of();
        double health, damage, armor;
        int exp;
        List<Drop> drops = List.of();
        List<Form> forms = List.of();
        /** Guide glyphs (dialogs only; the mod draws the creature itself), or null. */
        String icon, preview;
        /** For search: everything the entry says, lower case. */
        String haystack = "";
        /**
         * The plain words of the MiniMessage fields above (the mod's codex screen), parsed once at the end of each
         * {@link #buildEntries}: {@code pSummonWhere} is null when {@code summonWhere} is, {@code colorHex} is the
         * upper-case colour or null when it isn't a hex colour.
         */
        String pName = "", pKind = "", pDanger = "", pHabitat = "", pAbout = "", pBehavior = "", pTame = "", pOdds = "", pJob = "",
                pBond = "", pHeal = "", pSummonWhere;
        List<String> pPhases = List.of();
        String colorHex;

        String id() {
            return id;
        }

        String name() {
            return name;
        }

        boolean boss() {
            return "boss".equals(dangerKey);
        }
    }

    record Milestone(String key, int count, String message, List<String> commands, boolean firework) {
    }

    static final class Book {
        final Set<String> seen = new LinkedHashSet<>();
        final Set<String> tamed = new LinkedHashSet<>();
        final Set<String> milestones = new LinkedHashSet<>();
        /** Variants found by taming or breeding: "&lt;species&gt;:&lt;variant key&gt;" and "&lt;species&gt;:shiny". */
        final Set<String> variants = new LinkedHashSet<>();
        /** Forms seen (MythicMobs ids of young creatures, clones ...). */
        final Set<String> forms = new LinkedHashSet<>();
    }

    /** Pdc key on the harmless milestone firework (its blast hurts nobody). */
    static final String FIREWORK = "feather:codex_firework";
    private static final List<String> REALMS = List.of("overworld", "ocean", "nether", "end");
    private static final String HEADER = "# Feather creature codex (seen / tamed species per player). Edit only while the server is stopped.\n";

    private final PetsModule m;
    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** Form MythicMobs id -> its entry's id. */
    private final Map<String, String> formOf = new HashMap<>();
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, String> realmNames = new LinkedHashMap<>();
    /** The tabs and realms as the mod's codex message lists them (plain words), rebuilt with the entries. */
    private JsonArray categoriesJson = new JsonArray(), realmsJson = new JsonArray();
    private List<String> howTo = List.of(), petCare = List.of();
    private final List<Milestone> milestones = new ArrayList<>();
    private final List<Milestone> variantMilestones = new ArrayList<>();
    private final Map<UUID, Book> books = new ConcurrentHashMap<>();
    /** What each player last opened ("main", "category:x", "entry:y" ...), for tests and /pets admin. */
    final Map<UUID, String> lastView = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private boolean unreadable;
    private double radius = 12;
    private boolean enabled = true;
    private boolean seenNotice = true;
    private int tameableTotal;
    /** What the entries were last built from ({@link #inputsKey}); null until the first build or after a forced reload. */
    private String builtFrom;

    Codex(PetsModule module) {
        this.m = module;
        this.file = module.feather().dataFile("pets-codex.yml");
    }

    // ------------------------------------------------------------------ config

    void reload(Yaml cfg, Map<String, PetSpecies> species) {
        reload(cfg, species, false);
    }

    /**
     * Settings and milestones from pets.yml; the entries are rebuilt only when what they are built from changed
     * (guide.yml, codex.yml, altars.yml, the pets.yml species, the registered creatures) or {@code force} (/codex reload).
     */
    void reload(Yaml cfg, Map<String, PetSpecies> species, boolean force) {
        if (force) builtFrom = null;
        enabled = cfg.getBoolean("codex.enabled", true);
        radius = Math.max(2, Math.min(32, cfg.getDouble("codex.seen-radius", 12)));
        seenNotice = cfg.getBoolean("codex.seen-notice", true);
        milestones.clear();
        Yaml ms = PetsModule.section(cfg, "codex.milestones");
        if (ms != null) {
            for (String k : ms.getKeys(false)) {
                Yaml c = ms.getConfigurationSection(k);
                int count;
                if (k.equalsIgnoreCase("all")) count = -1;
                else {
                    try {
                        count = Integer.parseInt(k.trim());
                    } catch (NumberFormatException e) {
                        m.feather().getLogger().warn("pets.yml codex.milestones.{}: use a number or 'all'.", k);
                        continue;
                    }
                }
                milestones.add(new Milestone(k.toLowerCase(Locale.ROOT), count, c == null ? null : c.getString("message"),
                        c == null ? List.of() : c.getStringList("commands"), c == null || c.getBoolean("firework", true)));
            }
        }
        variantMilestones.clear();
        Yaml vm = PetsModule.section(cfg, "codex.variant-milestones");
        if (vm != null) {
            for (String k : vm.getKeys(false)) {
                Yaml c = vm.getConfigurationSection(k);
                int count;
                try {
                    count = Integer.parseInt(k.trim());
                } catch (NumberFormatException e) {
                    m.feather().getLogger().warn("pets.yml codex.variant-milestones.{}: use a number.", k);
                    continue;
                }
                if (count <= 0) continue;
                variantMilestones.add(new Milestone("variants-" + count, count, c == null ? null : c.getString("message"),
                        c == null ? List.of() : c.getStringList("commands"), c == null || c.getBoolean("firework", true)));
            }
        }
        buildEntries(species);
    }

    /** Reads a resource config, or an empty one if it can't be read (the codex still lists every creature). */
    private Yaml resource(String name) {
        try {
            return m.feather().loadResourceConfig(name);
        } catch (RuntimeException e) {
            m.feather().getLogger().warn("Codex: could not read {}: {}", name, e.toString());
            return new Yaml();
        }
    }

    private static Yaml sec(Yaml y, String path) {
        return PetsModule.section(y, path);
    }

    /**
     * The inputs of {@link #buildEntries} in one string: size and CRC-32 of the three text files on disk (their bundled
     * defaults never change while the game runs), the pets.yml species and the registered creatures.
     */
    private String inputsKey(Map<String, PetSpecies> petSpecies) {
        StringBuilder sb = new StringBuilder();
        Path dir = m.feather().getDataFolder();
        for (String name : List.of("guide.yml", "codex.yml", "altars.yml")) {
            sb.append(name).append(':');
            try {
                byte[] b = Files.readAllBytes(dir.resolve(name));
                CRC32 crc = new CRC32();
                crc.update(b);
                sb.append(b.length).append('/').append(Long.toHexString(crc.getValue()));
            } catch (IOException e) {
                sb.append('-');
            }
            sb.append(';');
        }
        sb.append(ArcanaEntities.all().size()).append(';');   // registered once at startup
        sb.append(petSpecies.size()).append('/').append(petSpecies.hashCode());   // records: content hashes
        return sb.toString();
    }

    /** Every registered creature becomes an entry (or a form of one); the text files only add words. */
    private void buildEntries(Map<String, PetSpecies> petSpecies) {
        String key = inputsKey(petSpecies);
        if (key.equals(builtFrom)) return;   // a /feather reload that changed none of it (not even parsed)
        builtFrom = null;
        Yaml guide = resource("guide.yml"), codex = resource("codex.yml"), altars = resource("altars.yml");
        Yaml guideCreatures = sec(guide, "creatures"), texts = sec(codex, "creatures"), bosses = sec(altars, "bosses");
        howTo = guide.getStringList("menu.how-to");
        petCare = guide.getStringList("menu.pet-care");

        // guide groups: creature -> group, and the guide's order
        Map<String, String> groupOf = new HashMap<>();
        Map<String, Integer> guideOrder = new HashMap<>();
        for (Map<?, ?> g : guide.getMapList("menu.groups")) {
            if (!(g.get("creatures") instanceof List<?> l)) continue;
            for (Object o : l) {
                String id = String.valueOf(o);
                groupOf.putIfAbsent(id, String.valueOf(g.get("id")));
                guideOrder.putIfAbsent(id, guideOrder.size());
            }
        }
        categories.clear();
        Yaml cats = sec(codex, "categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                Yaml c = cats.getConfigurationSection(id);
                if (c == null) continue;
                categories.put(id, new Category(id, c.getString("name", id), c.getString("blurb", ""), c.getString("item", ""),
                        c.getString("icon", ""), c.getString("guide-group", ""), c.getStringList("kinds")));
            }
        }
        if (categories.isEmpty()) {   // no codex.yml at all: one tab per kind of creature
            categories.put("companions", new Category("companions", "Companions", "", "", "", "companions", List.of("pet")));
            categories.put("mounts", new Category("mounts", "Mounts", "", "", "", "mounts", List.of("mount")));
            categories.put("wild-ones", new Category("wild-ones", "Wild Ones", "", "", "", "wild", List.of("hostile")));
            categories.put("wildlife", new Category("wildlife", "Wildlife", "", "", "", "", List.of("wildlife", "livestock", "particles")));
            categories.put("monsters", new Category("monsters", "Monsters", "", "", "", "", List.of("undead", "untameable", "police")));
            categories.put("bosses", new Category("bosses", "Bosses", "", "", "", "", List.of("boss")));
        }
        realmNames.clear();
        for (String r : REALMS) realmNames.put(r, codex.getString("realms." + r, r));
        Map<String, String> dangerLabels = new HashMap<>();
        for (String k : List.of("harmless", "neutral", "hostile", "boss", "tameable")) dangerLabels.put(k, codex.getString("labels.danger." + k, k));

        // forms: young creatures (species data) and codex.yml form-of
        formOf.clear();
        Map<EntityType<?>, Species> all = ArcanaEntities.all();
        for (Species s : all.values()) {
            String parent = texts == null ? null : texts.getString(s.mythicId + ".form-of");
            if (parent == null) parent = s.youngOf();
            if (parent != null && ArcanaEntities.byMythicId(parent) != null) formOf.put(s.mythicId, parent);
        }

        List<Entry> built = new ArrayList<>();
        Map<String, List<Form>> forms = new HashMap<>();
        for (Species s : all.values()) {
            String parent = formOf.get(s.mythicId);
            if (parent != null) {
                Yaml t = texts == null ? null : texts.getConfigurationSection(s.mythicId);
                String note = t != null && t.getString("about") != null ? t.getString("about") : Species.str(s.json, "note", "");
                String n = note == null ? "" : note;
                forms.computeIfAbsent(parent, k -> new ArrayList<>()).add(new Form(s.mythicId, s.entityId().toString(),
                        s.name, n, s.health(), s.damage(), plainOf(s.name), plainOf(n)));
                continue;
            }
            built.add(entry(s, petSpecies, guideCreatures == null ? null : guideCreatures.getConfigurationSection(s.mythicId),
                    texts == null ? null : texts.getConfigurationSection(s.mythicId), bosses == null ? null : bosses.getConfigurationSection(s.mythicId),
                    groupOf.get(s.mythicId), dangerLabels));
        }
        for (Entry e : built) e.forms = List.copyOf(forms.getOrDefault(e.id, List.of()));
        // tab order, then the guide's order, then the species data's
        List<String> catOrder = new ArrayList<>(categories.keySet());
        List<Entry> sorted = new ArrayList<>(built);
        sorted.sort((a, b) -> {
            int c = Integer.compare(catOrder.indexOf(a.category), catOrder.indexOf(b.category));
            if (c != 0) return c;
            return Integer.compare(guideOrder.getOrDefault(a.id, 10_000 + built.indexOf(a)), guideOrder.getOrDefault(b.id, 10_000 + built.indexOf(b)));
        });
        entries.clear();
        tameableTotal = 0;
        Map<String, Integer> perTab = new LinkedHashMap<>();
        for (Entry e : sorted) {
            plainText(e);
            entries.put(e.id, e);
            if (e.tameable) tameableTotal++;
            perTab.merge(e.category, 1, Integer::sum);
        }
        JsonArray cj = new JsonArray();
        for (Category c : categories.values()) {
            JsonObject co = new JsonObject();
            co.addProperty("id", c.id());
            co.addProperty("name", plainOf(c.name()));
            if (!c.blurb().isEmpty()) co.addProperty("blurb", plainOf(c.blurb()));
            if (!c.item().isEmpty()) co.addProperty("item", c.item());
            cj.add(co);
        }
        categoriesJson = cj;
        JsonArray rj = new JsonArray();
        for (Map.Entry<String, String> r : realmNames.entrySet()) {
            JsonObject ro = new JsonObject();
            ro.addProperty("id", r.getKey());
            ro.addProperty("name", plainOf(r.getValue()));
            rj.add(ro);
        }
        realmsJson = rj;
        m.feather().getLogger().info("Codex: {} creatures ({} tameable) and {} forms from {} entity types; tabs {}", entries.size(), tameableTotal,
                formOf.size(), all.size(), perTab);
        builtFrom = key.contains(":-;") ? inputsKey(petSpecies) : key;   // a missing file was just written with its default
    }

    /** Guide text may carry MiniMessage; the mod gets the words (null reads as nothing). */
    private String plainOf(String mini) {
        return mini == null ? "" : Adv.plain(m.text().parse(mini, TagResolver.empty()));
    }

    /** Fills the entry's plain-text copies (the mod's codex screen; {@code PetsCompanion.codexSnapshot}). */
    private void plainText(Entry e) {
        e.pName = plainOf(e.name);
        e.pKind = plainOf(e.kind);
        e.pDanger = plainOf(e.danger);
        e.pHabitat = plainOf(e.habitat);
        e.pAbout = plainOf(e.about);
        e.pBehavior = plainOf(e.behavior);
        e.pTame = plainOf(e.tame);
        e.pOdds = plainOf(e.odds);
        e.pJob = plainOf(e.job);
        e.pBond = plainOf(e.bond);
        e.pHeal = plainOf(e.heal);
        e.pSummonWhere = e.summonWhere == null ? null : plainOf(e.summonWhere);
        e.pPhases = e.phases.stream().map(this::plainOf).toList();
        e.colorHex = e.color != null && TextColor.fromHexString(e.color) != null ? e.color.toUpperCase(Locale.ROOT) : null;
    }

    private Entry entry(Species s, Map<String, PetSpecies> petSpecies, Yaml g, Yaml t, Yaml boss, String group, Map<String, String> dangerLabels) {
        Entry e = new Entry();
        e.id = s.mythicId;
        e.entity = s.entityId().toString();
        e.tameable = petSpecies.containsKey(s.mythicId) && !s.untameable();
        e.swarm = s.kind == Species.Kind.PARTICLES || s.model == null;
        e.name = g != null ? g.getString("name", s.name) : s.name;
        String color = g != null ? g.getString(Legacy.key(g, "color", "colour")) : null;
        if (color == null && t != null) color = t.getString("color");
        if (color == null) color = legacyColor(Species.str(s.json, "color", "f"));
        e.color = color;
        // category: codex.yml, then the guide group, then the kind
        String kindToken = switch (s.kind) {
            case HOSTILE -> e.tameable ? "hostile" : "untameable";
            default -> s.kind.name().toLowerCase(Locale.ROOT);
        };
        String cat = t == null ? null : t.getString("category");
        if (cat == null || !categories.containsKey(cat)) {
            cat = null;
            if (group != null) for (Category c : categories.values()) if (group.equals(c.guideGroup())) {
                cat = c.id();
                break;
            }
            if (cat == null) for (Category c : categories.values()) if (c.kinds().contains(kindToken)) {
                cat = c.id();
                break;
            }
            if (cat == null) cat = categories.keySet().iterator().next();
        }
        e.category = cat;
        e.kind = g != null && !g.getString("kind", "").isEmpty() ? g.getString("kind", "") : singular(categories.get(cat).name());
        if (g != null) {
            e.job = g.getString("ability", "");
            e.habitat = g.getString("habitat", "");
            e.tame = g.getString("tame", "");
            e.odds = g.getString("odds", "");
            e.heal = g.getString("heal", "");
            e.bond = g.getString("bond", "");
            e.icon = blank(g.getString("icon"));
            e.preview = blank(g.getString("preview"));
        }
        if (t != null) {
            if (e.habitat.isEmpty()) e.habitat = t.getString("habitat", "");
            e.about = t.getString("about", "");
            e.behavior = t.getString("behavior", "");
            e.phases = t.getStringList("phases");
            if (e.icon == null) e.icon = blank(t.getString("icon"));
            if (e.preview == null) e.preview = blank(t.getString("preview"));
        }
        // a glyph guide.yml names but the mod doesn't ship counts as none (the page shows the spawn egg instead)
        if (e.icon != null && GuideGlyphs.of(e.icon).equals(Component.empty())) e.icon = null;
        if (e.preview != null && GuideGlyphs.of(e.preview).equals(Component.empty())) e.preview = null;
        if (e.about.isEmpty() && !e.tameable) e.about = Species.str(s.json, "note", "");
        if (boss != null) {
            e.summonItem = blank(boss.getString("item"));
            e.summonWhere = blank(boss.getString("where.hint"));
        }
        if (e.habitat.isEmpty()) e.habitat = e.summonWhere != null ? e.summonWhere : habitatOf(s);
        // danger
        String danger = t == null ? null : t.getString("danger");
        if (danger == null) {
            if (s.kind == Species.Kind.BOSS) danger = "boss";
            else if (e.tameable && (s.hostile() || s.kind == Species.Kind.HOSTILE)) danger = "tameable";
            else if (e.tameable) danger = "harmless";
            else if (s.kind == Species.Kind.UNDEAD || s.kind == Species.Kind.POLICE || s.hostile()) danger = "hostile";
            else danger = "harmless";
        }
        e.dangerKey = danger;
        e.danger = dangerLabels.getOrDefault(danger, danger);
        e.health = s.health();
        e.damage = s.damage();
        e.armor = Species.dbl(s.json, "armor", 0);
        e.drops = drops(s);
        e.exp = 0;
        if (s.json.has("drops")) for (JsonElement d : s.json.getAsJsonArray("drops")) {
            if (d.isJsonObject() && d.getAsJsonObject().has("exp")) e.exp += d.getAsJsonObject().get("exp").getAsInt();
        }
        List<String> realms = t == null ? List.of() : t.getStringList("realms");
        e.realms = realms.isEmpty() ? realmsOf(s, boss) : List.copyOf(realms);
        e.haystack = String.join(" ", e.name, e.kind, e.job, e.habitat, e.tame, e.about, e.behavior, e.danger,
                categories.get(cat).name(), String.join(" ", e.realms)).toLowerCase(Locale.ROOT);
        return e;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** "Monsters" -> "Monster", "Wildlife" stays. */
    private static String singular(String name) {
        if (name.endsWith("ies")) return name.substring(0, name.length() - 3) + "y";
        if (name.endsWith("ses")) return name.substring(0, name.length() - 2);
        if (name.endsWith("s") && !name.endsWith("ss")) return name.substring(0, name.length() - 1);
        return name;
    }

    /** A species' legacy color code ("6") as a hex color. */
    private static String legacyColor(String code) {
        // the 16 Minecraft colors, codes 0-9 a-f (white, 'f', is most species: the codex's own text color instead)
        String[] hex = {"#000000", "#0000AA", "#00AA00", "#00AAAA", "#AA0000", "#AA00AA", "#FFAA00", "#AAAAAA", "#555555", "#5555FF",
                "#55FF55", "#55FFFF", "#FF5555", "#FF55FF", "#FFFF55", "#F1EDFF"};
        int i = code == null || code.isEmpty() ? -1 : Character.digit(code.charAt(0), 16);
        return i < 0 || i == 15 ? null : hex[i];
    }

    /** Where it spawns, from its spawn rules: a few biome names. */
    private static String habitatOf(Species s) {
        Set<String> names = new LinkedHashSet<>();
        for (Species.Spawn sp : s.spawns) for (String b : sp.biomes()) names.add(pretty(b));
        if (names.isEmpty()) return "";
        List<String> list = new ArrayList<>(names);
        String head = String.join(", ", list.subList(0, Math.min(3, list.size())));
        return list.size() > 3 ? head + " and more" : head;
    }

    private static String pretty(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        p = p.substring(p.lastIndexOf('/') + 1).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String w : p.split(" ")) {
            if (w.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /** Overworld / ocean / nether / end, from the spawn rules (undead: the biomes they haunt; bosses: their altar). */
    private static List<String> realmsOf(Species s, Yaml boss) {
        Set<String> out = new LinkedHashSet<>();
        boolean aquatic = s.aquatic() != null;
        for (Species.Spawn sp : s.spawns) {
            String dim = sp.dimension();
            if (dim.endsWith("the_nether")) out.add("nether");
            else if (dim.endsWith("the_end")) out.add("end");
            else out.add(aquatic || sp.water() ? "ocean" : "overworld");
        }
        JsonObject replace = s.section("replace");
        if (replace != null && replace.has("biomes")) {
            for (JsonElement b : replace.getAsJsonArray("biomes")) {
                String id = b.getAsString();
                out.add(id.contains("soul_sand") || id.contains("nether") || id.contains("crimson") || id.contains("warped") || id.contains("basalt_delta")
                        ? "nether" : "overworld");
            }
        }
        if (boss != null) {
            for (String env : boss.getStringList("where.environments")) {
                switch (env.toUpperCase(Locale.ROOT)) {
                    case "NETHER" -> out.add("nether");
                    case "THE_END", "END" -> out.add("end");
                    default -> out.add("overworld");
                }
            }
        }
        if (out.isEmpty()) out.add(aquatic ? "ocean" : "overworld");
        List<String> sorted = new ArrayList<>();
        for (String r : REALMS) if (out.contains(r)) sorted.add(r);
        return sorted;
    }

    /** Its drops (items only; XP is {@link Entry#exp}), custom items by their aftrhrs id. */
    private static List<Drop> drops(Species s) {
        List<Drop> out = new ArrayList<>();
        if (!s.json.has("drops")) return out;
        for (JsonElement el : s.json.getAsJsonArray("drops")) {
            if (!el.isJsonObject()) continue;
            JsonObject d = el.getAsJsonObject();
            String raw = d.has("item") ? d.get("item").getAsString() : d.has("custom") ? d.get("custom").getAsString() : null;
            if (raw == null) continue;
            Item item = item(raw);
            if (item == null) continue;
            int max = d.has("max") ? d.get("max").getAsInt() : 1;
            if (max <= 0) continue;   // "0-0" drops are placeholders the pack never gives
            out.add(new Drop(BuiltInRegistries.ITEM.getKey(item).toString(), d.has("min") ? d.get("min").getAsInt() : 1, max,
                    d.has("chance") ? d.get("chance").getAsDouble() : 1));
        }
        return out;
    }

    /** A vanilla or Arcana item by id ({@code minecraft:bone}, {@code aftrhrs:x}, or a bare id of either), or null. */
    static Item item(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<String> tries = raw.contains(":") ? List.of(raw) : List.of("minecraft:" + raw, "aftrhrs:" + raw);
        for (String id : tries) {
            Identifier key = Identifier.tryParse(id);
            if (key == null) continue;
            Item item = BuiltInRegistries.ITEM.getValue(key);
            if (item != null && item != Items.AIR) return item;
        }
        return null;
    }

    boolean enabled() {
        return enabled;
    }

    /** Every entry. */
    int total() {
        return entries.size();
    }

    /** The entries that can be tamed (the tamed count and its milestones). */
    int tameableTotal() {
        return tameableTotal;
    }

    Collection<Entry> entries() {
        return entries.values();
    }

    Entry entry(String id) {
        if (id == null) return null;
        Entry e = entries.get(id);
        if (e != null) return e;
        String parent = formOf.get(id);
        return parent == null ? null : entries.get(parent);
    }

    /** Whether this MythicMobs id has a codex page (its own or as a form). */
    boolean has(String id) {
        return entry(id) != null;
    }

    /** The entry a MythicMobs id is shown on (itself, or its parent for a form), or null. */
    String entryOf(String id) {
        Entry e = entry(id);
        return e == null ? null : e.id;
    }

    Map<String, Category> categories() {
        return categories;
    }

    Map<String, String> realmNames() {
        return realmNames;
    }

    /** The {@code categories} array of the mod's codex message (shared: copy before handing it out). */
    JsonArray categoriesJson() {
        return categoriesJson;
    }

    /** The {@code realms} array of the mod's codex message (shared: copy before handing it out). */
    JsonArray realmsJson() {
        return realmsJson;
    }

    List<String> howTo() {
        return howTo;
    }

    List<String> petCare() {
        return petCare;
    }

    /** /codex tab completion: tabs, realms, then every creature's short id ("emberkit"). */
    List<String> completions() {
        List<String> out = new ArrayList<>(categories.keySet());
        out.addAll(realmNames.keySet());
        for (Entry e : entries.values()) out.add(e.entity.substring(e.entity.indexOf(':') + 1));
        return out;
    }

    /** Resolves a category id, a category name or a realm id loosely ("Dogs & Cats", "dogs"); null if none. */
    String category(String want) {
        if (want == null || want.isBlank()) return null;
        String w = want.toLowerCase(Locale.ROOT).trim();
        if (categories.containsKey(w)) return w;
        for (Category c : categories.values()) {
            if (c.name().equalsIgnoreCase(w) || c.id().startsWith(w) || c.name().toLowerCase(Locale.ROOT).startsWith(w)) return c.id();
        }
        return null;
    }

    /** A MythicMobs id or a creature's name (case-insensitive), as the entry it is shown on; null if none. */
    String species(String want) {
        if (want == null || want.isBlank()) return null;
        String direct = entryOf(want);
        if (direct != null) return direct;
        String w = want.toLowerCase(Locale.ROOT).trim();
        for (Entry e : entries.values()) {
            if (e.name.equalsIgnoreCase(w) || e.id.equalsIgnoreCase(w) || e.entity.equalsIgnoreCase(w) || e.entity.endsWith(":" + w)) return e.id;
        }
        // a form by its name or entity id (wukong_clone, "Tufthorn (young)")
        for (Entry e : entries.values()) {
            for (Form f : e.forms) if (f.name().equalsIgnoreCase(w) || f.id().equalsIgnoreCase(w) || f.entity().endsWith(":" + w)) return e.id;
        }
        return null;
    }

    // ------------------------------------------------------------------ progress

    Book book(UUID player) {
        return books.computeIfAbsent(player, k -> new Book());
    }

    int seenCount(Book b) {
        int n = 0;
        for (String id : entries.keySet()) if (b.seen.contains(id) || b.tamed.contains(id)) n++;
        return n;
    }

    int tamedCount(Book b) {
        int n = 0;
        for (Entry e : entries.values()) if (e.tameable && b.tamed.contains(e.id)) n++;
        return n;
    }

    /** Entries this player hasn't found yet (codex quests). */
    int unseen(UUID player) {
        Book b = books.get(player);
        return b == null ? total() : Math.max(0, total() - seenCount(b));
    }

    boolean seen(Book b, String id) {
        return b.seen.contains(id) || b.tamed.contains(id);
    }

    /** Records a sighting (a form also marks its parent seen); true if the entry is new. */
    boolean see(ServerPlayer player, String type) {
        if (!enabled) return false;
        Entry e = entry(type);
        if (e == null) return false;
        Book b = book(player.getUUID());
        if (!type.equals(e.id) && b.forms.add(type)) dirty = true;
        if (!b.seen.add(e.id)) return false;
        dirty = true;
        Hooks.Quests q = m.feather().service(Hooks.Quests.class);
        if (q != null) q.progress(player, "codex", e.id, 1);
        if (seenNotice) {
            m.actionBar(player, "codex.seen-new", Text.unparsed("name", e.name), Text.unparsed("seen", seenCount(b)),
                    Text.unparsed("total", total()));
            m.sound(player, "codex-seen");
        }
        return true;
    }

    /** Records a tame (also counts as seen). {@code quiet}: no chat line (back-filling pets owned before the codex). */
    /**
     * Marks the species seen and tamed. True when it is new in this player's codex and not {@code quiet}: the caller
     * then says so (the pets module's "tamed-first-kind" line, or {@link #announceTamed}); this sends no line of its own
     * apart from milestones.
     */
    boolean tamed(UUID owner, String species, boolean quiet) {
        Entry e = entries.get(species);
        if (!enabled || e == null || !e.tameable) return false;
        Book b = book(owner);
        boolean newSeen = b.seen.add(species);
        boolean newTamed = b.tamed.add(species);
        if (newSeen || newTamed) dirty = true;
        ServerPlayer p = m.feather().player(owner);
        if (p != null) {
            Hooks.Quests q = m.feather().service(Hooks.Quests.class);
            if (newTamed && newSeen && !quiet && q != null) q.progress(p, "codex", species, 1);
            if (!quiet && !unreadable) checkMilestones(p, b);
        }
        return newTamed && !quiet;
    }

    /** {@code <tamed>} and {@code <tameable>}: the player's codex count, for the tame lines. */
    TagResolver tamedTags(UUID owner) {
        Book b = books.get(owner);
        return TagResolver.resolver(Text.unparsed("tamed", b == null ? 0 : tamedCount(b)), Text.unparsed("tameable", tameableTotal()));
    }

    /** The codex's own "tamed for the first time" line, where no tame line says it (a pet grew up, a bred baby). */
    void announceTamed(UUID owner, String species) {
        Entry e = entries.get(species);
        ServerPlayer p = m.feather().player(owner);
        if (e == null || p == null) return;
        Book b = book(owner);
        m.send(p, "codex.tamed-new", Text.unparsed("name", e.name), Text.unparsed("tamed", tamedCount(b)),
                Text.unparsed("total", tameableTotal()), Text.unparsed("tameable", tameableTotal()));
    }

    /** Records the pet's color variant and shiny look as found; tells the owner and gives variant milestones. */
    void found(UUID owner, Pet pet) {
        if (!enabled || !entries.containsKey(pet.species)) return;
        Book b = book(owner);
        Variants.Variant v = m.variants().get(pet.species, pet.variant);
        List<String> fresh = new ArrayList<>();
        if (v != null && b.variants.add(pet.species + ":" + v.key())) fresh.add(v.name());
        if (pet.shiny && b.variants.add(pet.species + ":shiny")) fresh.add(m.raw("variants.shiny-word"));
        if (fresh.isEmpty()) return;
        dirty = true;
        ServerPlayer p = m.feather().player(owner);
        if (p == null) return;
        Entry e = entries.get(pet.species);
        for (String name : fresh) {
            m.send(p, "codex.variant-new", Text.unparsed("name", e.name), Text.unparsed("variant", name),
                    Text.unparsed("found", variantsFound(b, pet.species)), Text.unparsed("of", variantsTotal(pet.species)));
        }
        if (!unreadable) checkVariantMilestones(p, b);
    }

    int variantsFound(Book b, String species) {
        int n = 0;
        for (Variants.Variant v : m.variants().of(species)) if (b.variants.contains(species + ":" + v.key())) n++;
        return n;
    }

    int variantsTotal(String species) {
        return m.variants().of(species).size();
    }

    int variantCount(Book b) {
        int n = 0;
        for (String s : b.variants) {
            int i = s.indexOf(':');
            if (i < 0) continue;
            String species = s.substring(0, i), key = s.substring(i + 1);
            if (key.equals("shiny") ? entries.containsKey(species) : m.variants().get(species, key) != null) n++;
        }
        return n;
    }

    private void checkVariantMilestones(ServerPlayer p, Book b) {
        int n = variantCount(b);
        for (Milestone ms : variantMilestones) {
            if (n < ms.count() || b.milestones.contains(ms.key())) continue;
            b.milestones.add(ms.key());
            dirty = true;
            TagResolver tags = TagResolver.resolver(Text.unparsed("count", ms.count()), Text.unparsed("found", n),
                    Text.unparsed("player", Names.name(p)));
            String msg = ms.message() != null ? ms.message() : m.raw("codex.variant-milestone");
            if (!msg.isEmpty()) Adv.send(p, m.text().parse(msg, tags));
            runCommands(p, ms.commands());
            if (ms.firework()) firework(p);
            m.sound(p, "codex-milestone");
        }
    }

    private void checkMilestones(ServerPlayer p, Book b) {
        int n = tamedCount(b);
        for (Milestone ms : milestones) {
            int need = ms.count() < 0 ? tameableTotal() : ms.count();
            if (need <= 0 || n < need || b.milestones.contains(ms.key())) continue;
            b.milestones.add(ms.key());
            dirty = true;
            TagResolver tags = TagResolver.resolver(Text.unparsed("count", need), Text.unparsed("tamed", n),
                    Text.unparsed("total", tameableTotal()), Text.unparsed("tameable", tameableTotal()), Text.unparsed("player", Names.name(p)));
            String msg = ms.message() != null ? ms.message() : m.raw(ms.count() < 0 ? "codex.milestone-all" : "codex.milestone");
            if (!msg.isEmpty()) Adv.send(p, m.text().parse(msg, tags));
            runCommands(p, ms.commands());
            if (ms.firework()) firework(p);
            m.sound(p, "codex-milestone");
        }
    }

    private void runCommands(ServerPlayer p, List<String> commands) {
        MinecraftServer server = net.axther.aftrhrs.core.Feather.server();
        for (String cmd : commands) {
            String c = cmd.replace("<player>", Names.name(p)).replace("<uuid>", p.getUUID().toString());
            if (c.startsWith("/")) c = c.substring(1);
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
            } catch (RuntimeException e) {
                m.feather().getLogger().warn("Codex milestone command failed: {} ({})", c, e.getMessage());
            }
        }
    }

    /** A harmless firework burst over the player (its blast is cancelled by the damage listener). */
    private void firework(ServerPlayer p) {
        try {
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL,
                    IntList.of(0xC3A6FF, 0x8FD3FF), IntList.of(0xFFFFFF), false, true))));
            ServerLevel level = (ServerLevel) p.level();
            FireworkRocketEntity fw = new FireworkRocketEntity(level, p.getX(), p.getY() + 1, p.getZ(), rocket);
            Pdc.putInt(fw, FIREWORK, 1);
            level.addFreshEntity(fw);
        } catch (Throwable ignored) {
        }
    }

    /** "Next reward at 10 tamed (3 to go)", or null when every milestone is done. */
    Component nextMilestoneLine(Book b, int tamed) {
        Milestone best = null;
        int bestNeed = Integer.MAX_VALUE;
        for (Milestone ms : milestones) {
            int need = ms.count() < 0 ? tameableTotal() : ms.count();
            if (b.milestones.contains(ms.key()) || need <= tamed) continue;
            if (need < bestNeed) {
                best = ms;
                bestNeed = need;
            }
        }
        if (best == null) return null;
        return m.msg("codex.next-milestone", Text.unparsed("count", bestNeed), Text.unparsed("left", Math.max(0, bestNeed - tamed)));
    }

    /** Pets a player already owns go in as tamed (players who had pets before the codex existed). */
    void backfill(ServerPlayer player) {
        for (Pet p : m.store().of(player.getUUID())) {
            tamed(player.getUUID(), p.species, true);
            if (!enabled || !entries.containsKey(p.species)) continue;
            Book b = book(player.getUUID());
            Variants.Variant v = m.variants().get(p.species, p.variant);
            if (v != null && b.variants.add(p.species + ":" + v.key())) dirty = true;
            if (p.shiny && b.variants.add(p.species + ":shiny")) dirty = true;
        }
    }

    void joined(ServerPlayer p) {
        UUID id = p.getUUID();
        Tasks.later(40, () -> {
            ServerPlayer now = m.feather().player(id);
            if (now != null) backfill(now);
        });
    }

    /** Every codex.scan-seconds: Arcana creatures within the seen radius of each player mark their entry (and form) seen. */
    void scan() {
        if (!enabled || entries.isEmpty()) return;
        double r2 = radius * radius;
        int everything = entries.size() + formOf.size();
        for (ServerPlayer p : m.feather().onlinePlayers()) {
            if (p.isSpectator() || p.isDeadOrDying()) continue;
            Book b = books.get(p.getUUID());
            if (b != null && b.seen.size() + b.forms.size() >= everything && seenCount(b) >= entries.size() && b.forms.containsAll(formOf.keySet())) continue;
            AABB box = p.getBoundingBox().inflate(radius);
            for (Mob e : p.level().getEntitiesOfClass(Mob.class, box, x -> x instanceof ArcanaMob)) {
                String type = ((ArcanaMob) e).species().mythicId;
                Entry en = type == null ? null : entry(type);
                if (en == null) continue;
                if (b != null && b.seen.contains(en.id) && (type.equals(en.id) || b.forms.contains(type))) continue;
                if (e.distanceToSqr(p) > r2) continue;
                see(p, type);
                b = books.get(p.getUUID());
            }
        }
    }

    // ------------------------------------------------------------------ the dialogs (players without the mod)

    /**
     * Opens the codex: the mod's screen when the player has it, else the dialogs. {@code category}: a tab or realm id,
     * {@code species}: an entry (or form) id; either may be null. {@code back}: where the main page's bottom button goes.
     */
    void open(ServerPlayer viewer, String category, String species, Consumer<ServerPlayer> back) {
        String entry = species(species);
        String cat = category == null ? null : realmNames.containsKey(category) ? category : category(category);
        lastView.put(viewer.getUUID(), entry != null ? "entry:" + entry : cat != null ? "category:" + cat : "main");
        m.feather().advance(viewer, "event", "guide");
        if (m.companion() != null && m.companion().openCodex(viewer, cat, entry)) return;
        Consumer<ServerPlayer> home = pl -> openMain(pl, back);
        if (entry != null) {
            Entry e = entries.get(entry);
            List<String> list = list(e.category, null);
            openEntry(viewer, list, list.indexOf(entry), e.category, back != null ? back : home);
        } else if (cat != null) openList(viewer, cat, null, home);
        else openMain(viewer, back);
    }

    /** The ids of a category or realm (null: everything), filtered by a search ("" or null: no search). */
    List<String> list(String category, String query) {
        List<String> out = new ArrayList<>();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        for (Entry e : entries.values()) {
            if (category != null && !category.equals(e.category) && !e.realms.contains(category)) continue;
            if (!q.isEmpty() && !e.haystack.contains(q)) continue;
            out.add(e.id);
        }
        return out;
    }

    private TagResolver counts(Book b) {
        int tamed = tamedCount(b);
        return TagResolver.resolver(Text.unparsed("tamed", tamed), Text.unparsed("seen", seenCount(b)), Text.unparsed("total", total()),
                Text.unparsed("tameable", tameableTotal()));
    }

    private void openMain(ServerPlayer viewer, Consumer<ServerPlayer> back) {
        Book b = book(viewer.getUUID());
        TagResolver counts = counts(b);
        Dialogs.Menu menu = m.dialogs().menu(m.msg("codex.title", counts));
        menu.text(m.msg("codex.body", counts), 300);
        if (m.variants().enabled()) {
            int allVariants = 0, found = 0, shinies = 0;
            for (String id : entries.keySet()) {
                allVariants += variantsTotal(id);
                found += variantsFound(b, id);
                if (b.variants.contains(id + ":shiny")) shinies++;
            }
            if (allVariants > 0) {
                menu.text(m.msg("codex.variants-summary", Text.unparsed("found", found), Text.unparsed("total", allVariants),
                        Text.unparsed("shinies", shinies), Text.unparsed("symbol", m.variants().symbol())), 300);
            }
        }
        Component next = nextMilestoneLine(b, tamedCount(b));
        if (next != null) menu.text(next, 300);
        menu.input(Dialogs.Input.text("q", m.msg("codex.search-label")).maxLength(32).width(200));
        Consumer<ServerPlayer> home = pl -> openMain(pl, back);
        menu.button(m.msg("codex.search"), m.msg("codex.search-tooltip"), 150, (pl, r) -> {
            String q = r.getText("q");
            if (q == null || q.isBlank()) openMain(pl, back);
            else openList(pl, null, q.trim(), home);
        });
        for (Category c : categories.values()) {
            int[] n = tally(b, c.id());
            TagResolver t = TagResolver.resolver(Text.unparsed("name", c.name()), Text.unparsed("seen", n[0]), Text.unparsed("count", n[1]),
                    Text.unparsed("blurb", c.blurb()), Text.component("icon", glyph(c.icon(), false)));
            menu.button(m.msg("codex.category-button", t), m.msg("codex.category-tooltip", t), 150, pl -> openList(pl, c.id(), null, home));
        }
        for (Map.Entry<String, String> r : realmNames.entrySet()) {
            int[] n = tally(b, r.getKey());
            if (n[1] == 0) continue;
            TagResolver t = TagResolver.resolver(Text.unparsed("name", r.getValue()), Text.unparsed("seen", n[0]), Text.unparsed("count", n[1]));
            String id = r.getKey();
            menu.button(m.msg("codex.realm-button", t), m.msg("codex.realm-tooltip", t), 150, pl -> openList(pl, id, null, home));
        }
        if (!howTo.isEmpty()) menu.button(m.msg("codex.how-to"), m.msg("codex.how-to-tooltip"), 150, pl -> openText(pl, "codex.how-to", howTo, home));
        if (!petCare.isEmpty()) menu.button(m.msg("codex.pet-care"), m.msg("codex.pet-care-tooltip"), 150, pl -> openText(pl, "codex.pet-care", petCare, home));
        Hooks.Bestiary bestiary = m.feather().service(Hooks.Bestiary.class);
        if (bestiary != null) {
            menu.button(m.msg("codex.bestiary-tab"), m.msg("codex.bestiary-tab-tooltip"), 150, pl -> bestiary.openBestiary(pl, home));
        }
        menu.columns(2);
        if (back != null) menu.exit(m.text().msg("menu.back"), back);
        else menu.exit(m.text().msg("menu.close"), (Consumer<ServerPlayer>) null);
        menu.open(viewer);
        m.text().sound(viewer, "book");
    }

    /** [seen, total] of a category or realm. */
    private int[] tally(Book b, String category) {
        int seen = 0, n = 0;
        for (Entry e : entries.values()) {
            if (!category.equals(e.category) && !e.realms.contains(category)) continue;
            n++;
            if (seen(b, e.id)) seen++;
        }
        return new int[]{seen, n};
    }

    private void openText(ServerPlayer viewer, String titleKey, List<String> lines, Consumer<ServerPlayer> back) {
        Dialogs.Menu menu = m.dialogs().menu(m.msg(titleKey));
        for (String line : lines) menu.text(m.text().parse(line.isEmpty() ? " " : line), 300);
        menu.exit(m.text().msg("menu.back"), back);
        menu.open(viewer);
    }

    /** A category, realm or search result as a grid of buttons. */
    private void openList(ServerPlayer viewer, String category, String query, Consumer<ServerPlayer> back) {
        Book b = book(viewer.getUUID());
        List<String> ids = list(category, query);
        Category c = category == null ? null : categories.get(category);
        int[] n = category == null ? new int[]{0, ids.size()} : tally(b, category);
        String name = c != null ? c.name() : category != null ? realmNames.getOrDefault(category, category) : query;
        TagResolver t = TagResolver.resolver(Text.unparsed("name", name), Text.unparsed("seen", n[0]), Text.unparsed("count", n[1]),
                Text.unparsed("query", query == null ? "" : query), Text.component("icon", c == null ? Component.empty() : glyph(c.icon(), false)));
        Dialogs.Menu menu = m.dialogs().menu(m.msg(query != null ? "codex.search-title" : "codex.category-title", t));
        if (c != null && !c.blurb().isEmpty()) menu.text(m.text().parse("<muted>" + c.blurb()), 300);
        if (ids.isEmpty()) menu.text(m.msg("codex.search-none", t), 300);
        lastView.put(viewer.getUUID(), query != null ? "search:" + query : "category:" + category);
        Consumer<ServerPlayer> self = pl -> openList(pl, category, query, back);
        Hooks.Bestiary bestiary = m.feather().service(Hooks.Bestiary.class);
        if (bestiary != null && "wildlife".equals(category)) {
            menu.button(m.msg("codex.bestiary-tab"), m.msg("codex.bestiary-tab-tooltip"), 100, pl -> bestiary.openBestiary(pl, self));
        }
        for (int i = 0; i < ids.size(); i++) {
            Entry e = entries.get(ids.get(i));
            boolean isTamed = b.tamed.contains(e.id), isSeen = seen(b, e.id);
            TagResolver tags = tags(e);
            Component label, tip;
            if (isTamed) {
                label = icon(e, false).append(m.msg("codex.entry-tamed", tags));
                tip = m.msg("codex.tooltip-tamed", tags);
                int vt = variantsTotal(e.id);
                if (m.variants().enabled() && vt > 0) {
                    tip = tip.append(Component.newline()).append(m.msg("codex.tooltip-variants", Text.unparsed("found", variantsFound(b, e.id)),
                            Text.unparsed("total", vt), Text.component("shiny", b.variants.contains(e.id + ":shiny")
                                    ? m.msg("codex.shiny-found", Text.unparsed("symbol", m.variants().symbol())) : Component.empty())));
                }
            } else if (isSeen) {
                label = icon(e, false).append(m.msg("codex.entry-seen", tags));
                tip = m.msg(e.tameable ? "codex.tooltip-seen" : "codex.tooltip-wild-seen", tags);
            } else {
                label = icon(e, true).append(m.msg("codex.entry-unknown", tags));
                tip = m.msg("codex.tooltip-unknown", tags);
            }
            int idx = i;
            menu.button(label, tip, 100, pl -> openEntry(pl, ids, idx, category, self));
        }
        menu.columns(3);
        menu.exit(m.text().msg("menu.back"), back);
        menu.open(viewer);
        m.text().sound(viewer, "menu-open");
    }

    /** One creature's page; Previous / Next step through {@code ids}. */
    private void openEntry(ServerPlayer viewer, List<String> ids, int index, String category, Consumer<ServerPlayer> back) {
        if (ids.isEmpty()) {
            back.accept(viewer);
            return;
        }
        int n = ids.size(), i = ((index % n) + n) % n;
        Entry e = entries.get(ids.get(i));
        if (e == null) {
            back.accept(viewer);
            return;
        }
        lastView.put(viewer.getUUID(), "entry:" + e.id);
        Book b = book(viewer.getUUID());
        boolean isTamed = b.tamed.contains(e.id), isSeen = seen(b, e.id);
        TagResolver tags = tags(e);
        Dialogs.Menu menu = m.dialogs().menu(icon(e, !isSeen).append(m.msg(isSeen ? "codex.page-title" : "codex.page-title-unknown", tags)));
        if (isSeen) {
            Component preview = e.preview == null ? Component.empty() : GuideGlyphs.of(e.preview);
            if (!preview.equals(Component.empty())) {
                // nine blank lines make room; the glyph's ascent draws it up into them
                menu.text(Component.text("\n".repeat(9)).append(preview), 300);
            } else {
                Item egg = ArcanaEntities.spawnEgg(e.entity.substring(e.entity.indexOf(':') + 1));
                if (egg != null) menu.item(new ItemStack(egg), m.text().parse("<muted>" + (e.about.isEmpty() ? e.job : e.about)), 260);
            }
        }
        String state = isTamed ? "tamed" : isSeen ? "seen" : "unknown";
        String page = e.tameable ? "codex.page-" + state : isSeen ? "codex.page-wild" : "codex.page-wild-unknown";
        menu.text(m.msg(page, tags), 300);
        if (isSeen) {
            if (e.tameable) {
                Component vb = variantBlock(b, e.id, isTamed);
                if (!vb.equals(Component.empty())) menu.text(vb, 300);
            } else {
                menu.text(stats(e), 300);
                Component drops = dropLine(e);
                if (drops != null) menu.text(drops, 300);
            }
            if (e.summonItem != null) {
                Item it = item(e.summonItem);
                Component name = it == null ? Component.text(e.summonItem) : Adv.adventure(new ItemStack(it).getHoverName());
                menu.text(m.msg("codex.page-summon", Text.component("item", name), Text.unparsed("where", e.summonWhere == null ? "?" : e.summonWhere)), 300);
            }
            if (!e.phases.isEmpty()) {
                menu.text(m.msg("codex.page-phases-title"), 300);
                for (String line : e.phases) menu.text(m.msg("codex.page-phase", Text.component("line", m.text().parse(line))), 300);
            }
            if (!e.forms.isEmpty()) {
                List<Component> parts = new ArrayList<>();
                for (Form f : e.forms) parts.add(b.forms.contains(f.id()) ? Component.text(f.name()) : m.msg("codex.variant-unknown"));
                menu.text(m.msg("codex.page-forms", Text.component("list", Component.join(JoinConfiguration.separator(m.msg("codex.variant-separator")), parts))), 300);
            }
            Hooks.Bestiary bestiary = m.feather().service(Hooks.Bestiary.class);
            Hooks.Bestiary.BestiaryMob line = bestiary == null ? null : bestiary.bestiaryMob(viewer.getUUID(), e.id);
            if (line != null) {
                menu.text(m.msg("codex.page-bestiary", Text.unparsed("harvested", line.harvested()), Text.unparsed("slain", line.slain()),
                        Text.unparsed("page", line.page())), 300);
            }
        }
        if (n > 1) {
            Entry prev = entries.get(ids.get((i - 1 + n) % n)), next = entries.get(ids.get((i + 1) % n));
            menu.button(m.msg("codex.previous"), m.text().parse("<muted>" + (seen(b, prev.id) ? prev.name : "???")), 100,
                    pl -> openEntry(pl, ids, i - 1, category, back));
            menu.button(m.msg("codex.next"), m.text().parse("<muted>" + (seen(b, next.id) ? next.name : "???")), 100,
                    pl -> openEntry(pl, ids, i + 1, category, back));
            menu.columns(2);
        }
        menu.exit(m.text().msg("menu.back"), back);
        menu.open(viewer);
        m.text().sound(viewer, "page");
    }

    private Component stats(Entry e) {
        String hp = fmt(e.health), atk = e.damage > 0 ? fmt(e.damage) : "-", armor = e.armor > 0 ? fmt(e.armor) : "-";
        return m.msg("codex.page-stats", Text.unparsed("health", hp), Text.unparsed("attack", atk), Text.unparsed("armor", armor),
                Text.unparsed("xp", e.exp));
    }

    private Component dropLine(Entry e) {
        if (e.drops.isEmpty()) return null;
        List<Component> parts = new ArrayList<>();
        for (Drop d : e.drops) {
            Item it = item(d.item());
            if (it == null) continue;
            Component name = Adv.adventure(new ItemStack(it).getHoverName());
            String amount = d.min() == d.max() ? String.valueOf(d.max()) : Math.max(0, d.min()) + "-" + d.max();
            String chance = d.chance() >= 1 ? "" : " " + Math.round(d.chance() * 100) + "%";
            parts.add(Component.text(amount + " ").append(name).append(Component.text(chance)));
        }
        if (parts.isEmpty()) return null;
        return m.msg("codex.page-drops", Text.component("list", Component.join(JoinConfiguration.separator(m.msg("codex.variant-separator")), parts)));
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    /** The species page's variant block: "Variants 1/3", each found / ???, and the shiny mark. */
    private Component variantBlock(Book b, String species, boolean isTamed) {
        List<Variants.Variant> list = m.variants().of(species);
        if (!m.variants().enabled() || list.isEmpty() && !isTamed) return Component.empty();
        List<Component> parts = new ArrayList<>();
        for (Variants.Variant v : list) {
            boolean has = b.variants.contains(species + ":" + v.key());
            parts.add(has ? m.msg("codex.variant-found", Text.component("variant", Component.text(v.name(), TextColor.color(v.tint()))))
                    : m.msg("codex.variant-unknown"));
        }
        boolean shiny = b.variants.contains(species + ":shiny");
        Component line = Component.join(JoinConfiguration.separator(m.msg("codex.variant-separator")), parts);
        return m.msg("codex.page-variants", Text.unparsed("found", variantsFound(b, species)), Text.unparsed("total", list.size()),
                Text.component("list", parts.isEmpty() ? m.msg("codex.variant-none") : line),
                Text.component("shiny", m.msg(shiny ? "codex.shiny-found" : "codex.shiny-unknown", Text.unparsed("symbol", m.variants().symbol()))));
    }

    private TagResolver tags(Entry e) {
        String color = e.color != null && TextColor.fromHexString(e.color) != null ? e.color : "#F1EDFF";
        TextColor c = TextColor.fromHexString(color);
        return TagResolver.resolver(Text.unparsed("name", e.name), Text.unparsed("kind", e.kind),
                Text.component("job", text(e.job)), Text.component("habitat", text(e.habitat)),
                Text.component("tame", text(e.tame)), Text.component("heal", text(e.heal)), Text.component("odds", text(e.odds)),
                Text.component("bond", text(e.bond)), Text.component("about", text(e.about)), Text.component("behavior", text(e.behavior)),
                Text.unparsed("danger", e.danger),
                Placeholder.styling("color", c), Placeholder.styling("colour", c));   // older message files say <colour>
    }

    /** Guide / codex text (MiniMessage with palette tags), or "?" when empty. */
    private Component text(String mini) {
        return mini == null || mini.isEmpty() ? Component.text("?") : m.text().parse(mini);
    }

    /** The creature's guide icon (black for a silhouette), then a space; empty without the glyph. */
    private Component icon(Entry e, boolean silhouette) {
        return glyph(e.icon, silhouette);
    }

    private static Component glyph(String name, boolean silhouette) {
        if (name == null || name.isEmpty()) return Component.empty();
        Component g = PlushItems.glyph(name, silhouette ? TextColor.color(0x1A1A1A) : null);
        return g == null ? Component.empty() : g.append(Component.text(" "));
    }

    // ------------------------------------------------------------------ disk

    void load() {
        books.clear();
        unreadable = false;
        Yaml yml = SafeYaml.read(file, m.feather().getLogger(), "codex progress");
        if (yml == null) {
            unreadable = true;
            return;
        }
        Yaml sec = yml.getConfigurationSection("players");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            UUID id = PetStore.uuid(k);
            Yaml c = sec.getConfigurationSection(k);
            if (id == null || c == null) continue;
            Book b = new Book();
            b.seen.addAll(c.getStringList("seen"));
            b.tamed.addAll(c.getStringList("tamed"));
            b.milestones.addAll(c.getStringList("milestones"));
            b.variants.addAll(c.getStringList("variants"));
            b.forms.addAll(c.getStringList("forms"));
            books.put(id, b);
        }
    }

    /** Plain maps copied on the server thread (nothing shared with the live books); null when no book has anything. */
    private Object snapshot() {
        Map<String, Object> players = new LinkedHashMap<>();
        for (Map.Entry<UUID, Book> en : books.entrySet()) {
            Book b = en.getValue();
            if (b.seen.isEmpty() && b.tamed.isEmpty() && b.milestones.isEmpty() && b.variants.isEmpty() && b.forms.isEmpty()) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("seen", new ArrayList<>(b.seen));
            p.put("tamed", new ArrayList<>(b.tamed));
            if (!b.milestones.isEmpty()) p.put("milestones", new ArrayList<>(b.milestones));
            if (!b.variants.isEmpty()) p.put("variants", new ArrayList<>(b.variants));
            if (!b.forms.isEmpty()) p.put("forms", new ArrayList<>(b.forms));
            players.put(en.getKey().toString(), p);
        }
        if (players.isEmpty()) return null;   // the same "{}" file as before
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("players", players);
        return root;
    }

    /**
     * Writes if anything changed: the books are copied here (server thread), dumped and written on the shared data
     * thread. {@code wait}: until it is on disk (shutdown).
     */
    void save(boolean wait) {
        if (!dirty || unreadable) return;
        dirty = false;
        Object snap = snapshot();
        var log = m.feather().getLogger();
        if (wait) {
            if (!SafeYaml.writeSync(file, HEADER, snap, log)) dirty = true;
            return;
        }
        // on the data thread: a lost write just makes the next timer tick try again
        SafeYaml.writeAsync(file, HEADER, snap, log).thenAccept(ok -> {
            if (!ok) dirty = true;
        });
    }

    void shutdown() {
        save(true);   // queued behind any pending async codex save on the shared data thread, so it lands last
    }

    void reset(UUID player) {
        if (books.remove(player) != null) dirty = true;
    }
}
