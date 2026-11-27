package net.axther.aftrhrs.gametest.creatures;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.GuideGlyphs;
import net.axther.aftrhrs.core.Hooks;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.core.cmd.Sender;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * The creature codex (the codex and the taming guide in one): every registered creature has a complete entry, every old
 * way in opens it, /pets no longer holds it, and seeing / taming creatures still fills it in.
 */
public class CodexTests {
    static Object codex() {
        return CT.call(PetsTests.module(), "codex");
    }

    @SuppressWarnings("unchecked")
    static Collection<Object> entries() {
        return (Collection<Object>) CT.call(codex(), "entries");
    }

    static String str(Object o, String field) {
        Object v = CT.get(o, field);
        return v == null ? null : String.valueOf(v);
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Every Arcana entity type has a codex page (its own, or as a form of its parent: young, Hair Clone) with a name, its
     * text, an icon (guide glyph or spawn egg) and a preview (its model, or the swarm for the particle-only Duskflies);
     * bosses have their altar offering, where to summon them and a fight summary. Nothing is listed by hand, so a new
     * creature without text fails here.
     */
    @GameTest
    public void everyEntityHasACodexEntry(GameTestHelper h) {
        List<String> problems = new ArrayList<>();
        Object codex = codex();
        @SuppressWarnings("unchecked")
        Map<String, Object> cats = (Map<String, Object>) CT.call(codex, "categories");
        @SuppressWarnings("unchecked")
        Map<String, String> realms = (Map<String, String>) CT.call(codex, "realmNames");
        Set<String> pages = new HashSet<>();
        int forms = 0;
        for (Species s : ArcanaEntities.all().values()) {
            Object e = CT.call(codex, "entry", s.mythicId);
            if (e == null) {
                problems.add(s.id + " (" + s.mythicId + ") has no codex entry");
                continue;
            }
            String id = str(e, "id");
            if (!id.equals(s.mythicId)) forms++;
            pages.add(id);
        }
        h.assertValueEqual(entries().size() + forms, ArcanaEntities.all().size(), "codex entries + forms vs registered types");
        for (Object e : entries()) {
            String id = str(e, "id");
            String where = id + ": ";
            Species s = ArcanaEntities.species(ArcanaEntities.byMythicId(id));
            if (blank(str(e, "name"))) problems.add(where + "no name");
            if (!cats.containsKey(str(e, "category"))) problems.add(where + "unknown tab " + str(e, "category"));
            @SuppressWarnings("unchecked")
            List<String> rs = (List<String>) CT.get(e, "realms");
            if (rs.isEmpty() || !realms.keySet().containsAll(rs)) problems.add(where + "realms " + rs);
            if (blank(str(e, "danger"))) problems.add(where + "no danger line");
            if (blank(str(e, "habitat"))) problems.add(where + "no habitat");
            boolean tameable = (boolean) CT.get(e, "tameable");
            boolean boss = (boolean) CT.call(e, "boss");
            if (tameable) {
                for (String f : List.of("job", "tame", "odds", "heal", "bond")) if (blank(str(e, f))) problems.add(where + "tameable without " + f);
            } else {
                if (blank(str(e, "about"))) problems.add(where + "no 'about' text (codex.yml)");
                @SuppressWarnings("unchecked")
                List<String> phases = (List<String>) CT.get(e, "phases");
                if (blank(str(e, "behavior")) && phases.isEmpty()) problems.add(where + "no behavior / fight text (codex.yml)");
            }
            if (boss) {
                @SuppressWarnings("unchecked")
                List<String> phases = (List<String>) CT.get(e, "phases");
                if (phases.isEmpty()) problems.add(where + "boss without phases");
                String item = str(e, "summonItem");
                Item it = item == null ? null : BuiltInRegistries.ITEM.getValue(Identifier.parse(item.contains(":") ? item : "aftrhrs:" + item));
                if (it == null || it == Items.AIR) problems.add(where + "boss without a summon item (altars.yml): " + item);
                if (blank(str(e, "summonWhere"))) problems.add(where + "boss without a summon place (altars.yml)");
            }
            // icon: a guide glyph that exists (one guide.yml names but the mod lacks is dropped), else the spawn egg
            String icon = str(e, "icon");
            boolean glyph = icon != null && !GuideGlyphs.of(icon).equals(net.kyori.adventure.text.Component.empty());
            if (icon != null && !glyph) problems.add(where + "kept glyph " + icon + ", which the mod doesn't have");
            if (!glyph && ArcanaEntities.spawnEgg(s.id) == null) problems.add(where + "no icon (no glyph, no spawn egg)");
            // preview: the GeckoLib model the screen draws, or the firefly swarm
            boolean swarm = (boolean) CT.get(e, "swarm");
            if (!swarm && (s.model == null || CodexTests.class.getResource("/assets/aftrhrs/geckolib/models/entity/" + s.model + ".geo.json") == null)) {
                problems.add(where + "no model to preview (" + s.model + ")");
            }
            if (swarm && s.kind != Species.Kind.PARTICLES) problems.add(where + "drawn as a swarm but has no particles kind");
            @SuppressWarnings("unchecked")
            List<Object> fs = (List<Object>) CT.get(e, "forms");
            for (Object f : fs) {
                if (blank((String) CT.call(f, "name"))) problems.add(where + "form without a name");
                if (ArcanaEntities.byMythicId((String) CT.call(f, "id")) == null) problems.add(where + "form of no registered type");
            }
        }
        // the other way round: text for creatures that don't exist is drift too
        Yaml codexYml = Feather.bundled("codex.yml"), guide = Feather.bundled("guide.yml");
        for (Yaml y : List.of(codexYml, guide)) {
            Yaml cs = y.getConfigurationSection("creatures");
            if (cs == null) continue;
            for (String k : cs.getKeys(false)) if (ArcanaEntities.byMythicId(k) == null) problems.add("text for unknown creature " + k);
        }
        CT.report(h, "codex coverage", problems);
        h.succeed();
    }

    /** The mod's codex snapshot lists every entry with its tab, realms and creature, and names what the player has seen. */
    @GameTest(maxTicks = 40)
    public void snapshotListsEverything(GameTestHelper h) {
        CT.arena(h, 6, 6);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        Object codex = codex();
        CT.call(codex, "see", p, "ArcanaWukongClone");
        JsonObject msg = (JsonObject) CT.call(CT.call(PetsTests.module(), "companion"), "codexSnapshot", p, "bosses", null);
        List<String> problems = new ArrayList<>();
        int n = 0;
        for (JsonElement el : msg.getAsJsonArray("entries")) {
            JsonObject o = el.getAsJsonObject();
            n++;
            for (String k : List.of("species", "state", "category", "entity", "realms")) if (!o.has(k)) problems.add(o + " lacks " + k);
            if (!o.get("state").getAsString().equals("unknown") && !o.has("name")) problems.add(o.get("species") + " seen but unnamed");
        }
        h.assertValueEqual(n, entries().size(), "entries in the snapshot");
        h.assertValueEqual(msg.get("category").getAsString(), "bosses", "the tab it opens on");
        h.assertTrue(msg.getAsJsonArray("categories").size() >= 7, "tabs in the snapshot");
        JsonObject wukong = null;
        for (JsonElement el : msg.getAsJsonArray("entries")) if (el.getAsJsonObject().get("species").getAsString().equals("ArcanaWukong")) wukong = el.getAsJsonObject();
        h.assertTrue(wukong != null && wukong.get("state").getAsString().equals("seen"), "seeing a Hair Clone marks Sun Wukong seen");
        h.assertTrue(wukong.has("summon") && wukong.has("phases"), "Wukong's page has its altar and fight");
        h.assertTrue(wukong.getAsJsonArray("forms").get(0).getAsJsonObject().get("seen").getAsBoolean(), "the clone is a seen form");
        CT.call(codex, "reset", p.getUUID());
        CT.disconnect(p);
        CT.report(h, "snapshot", problems);
        h.succeed();
    }

    /**
     * The codex text the mod gets is parsed once per reload (Codex.plainText): a fully seen book's snapshot is the same,
     * byte for byte, before and after the codex is reloaded (the cached tab and realm lists are rebuilt, not kept), and
     * the cached words match a fresh parse of the raw text.
     */
    @GameTest(maxTicks = 40)
    @SuppressWarnings("unchecked")
    public void snapshotTextSurvivesAReload(GameTestHelper h) {
        CT.arena(h, 6, 6);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        Object codex = codex();
        Object companion = CT.call(PetsTests.module(), "companion");
        List<String> problems = new ArrayList<>();
        try {
            Object book = CT.call(codex, "book", p.getUUID());
            Set<String> seen = (Set<String>) CT.get(book, "seen"), tamed = (Set<String>) CT.get(book, "tamed"),
                    forms = (Set<String>) CT.get(book, "forms");
            for (Object e : entries()) {
                String id = str(e, "id");
                seen.add(id);
                tamed.add(id);
                for (Object f : (List<Object>) CT.get(e, "forms")) forms.add((String) CT.call(f, "id"));
            }
            String before = CT.call(companion, "codexSnapshot", p, null, null).toString();
            Object cats = CT.call(codex, "categoriesJson");
            Feather.get().service(Hooks.Codex.class).reloadCodex();
            h.assertTrue(CT.call(codex, "categoriesJson") != cats, "the cached tab list wasn't rebuilt by the reload");
            String after = CT.call(companion, "codexSnapshot", p, null, null).toString();
            if (!before.equals(after)) {
                int i = 0;
                while (i < Math.min(before.length(), after.length()) && before.charAt(i) == after.charAt(i)) i++;
                problems.add("the snapshot changed after a reload at " + i + ": ..." + before.substring(Math.max(0, i - 60), Math.min(before.length(), i + 60))
                        + " vs ..." + after.substring(Math.max(0, i - 60), Math.min(after.length(), i + 60)));
            }
            // The cache says what a fresh parse says.
            Object text = CT.call(PetsTests.module(), "text");
            for (Object e : entries()) {
                for (String[] k : new String[][]{{"name", "pName"}, {"kind", "pKind"}, {"danger", "pDanger"}, {"habitat", "pHabitat"},
                        {"about", "pAbout"}, {"tame", "pTame"}, {"job", "pJob"}}) {
                    String raw = str(e, k[0]);
                    String want = raw == null ? "" : net.axther.aftrhrs.core.Adv.plain((net.kyori.adventure.text.Component) CT.call(text, "parse", raw,
                            new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty()}));
                    if (!want.equals(str(e, k[1]))) problems.add(str(e, "id") + "." + k[1] + " is '" + str(e, k[1]) + "', a parse gives '" + want + "'");
                }
            }
        } finally {
            CT.call(codex, "reset", p.getUUID());
            CT.disconnect(p);
        }
        CT.report(h, "codex reload", problems);
        h.succeed();
    }

    /**
     * Every old way in opens the one codex: /codex, the taming guide's names (/tamingguide, /taming, /arcanaguide),
     * /pets codex, /bestiary (on Wildlife), the hub's button and the Arcana Taming Guide item; /codex &lt;tab|creature&gt;
     * lands on that tab or page.
     */
    @GameTest(maxTicks = 60)
    public void oldEntryPointsOpenTheCodex(GameTestHelper h) {
        CT.arena(h, 6, 6);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        @SuppressWarnings("unchecked")
        Map<UUID, String> last = (Map<UUID, String>) CT.get(codex(), "lastView");
        List<String> problems = new ArrayList<>();
        String[][] cases = {
                {"codex", "main"}, {"tamingguide", "main"}, {"taming", "main"}, {"arcanaguide", "main"}, {"creatures", "main"},
                {"pets codex", "main"}, {"bestiary", "category:wildlife"}, {"codex bosses", "category:bosses"},
                {"codex emberkit", "entry:ArcanaEmberkit"}, {"codex Sun Wukong", "entry:ArcanaWukong"}, {"codex nether", "category:nether"},
                {"tamingguide wukong_clone", "entry:ArcanaWukong"},
        };
        for (String[] c : cases) {
            last.remove(p.getUUID());
            try {
                h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), c[0]);
            } catch (Throwable t) {
                problems.add("/" + c[0] + ": " + CT.err(t));
            }
            if (!c[1].equals(last.get(p.getUUID()))) problems.add("/" + c[0] + " opened " + last.get(p.getUUID()) + ", wanted " + c[1]);
        }
        // the hub: a Codex button, no separate Taming Guide button
        @SuppressWarnings("unchecked")
        Map<String, Object> hub = (Map<String, Object>) CT.get(Feather.get().hub(), "entries");
        if (hub.containsKey("guide")) problems.add("the hub still has a separate taming guide button");
        Object button = hub.get("codex");
        if (button == null) problems.add("the hub has no Codex button");
        else {
            last.remove(p.getUUID());
            @SuppressWarnings("unchecked")
            Consumer<ServerPlayer> action = (Consumer<ServerPlayer>) CT.call(button, "action");
            action.accept(p);
            if (!"main".equals(last.get(p.getUUID()))) problems.add("the hub's Codex button opened " + last.get(p.getUUID()));
        }
        // the taming guide item runs /tamingguide
        Item guide = BuiltInRegistries.ITEM.getValue(Identifier.parse("aftrhrs:arcana_taming_guide"));
        if (guide == Items.AIR) problems.add("no Arcana Taming Guide item");
        else {
            last.remove(p.getUUID());
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(guide));
            guide.use(h.getLevel(), p, InteractionHand.MAIN_HAND);
            if (!"main".equals(last.get(p.getUUID()))) problems.add("the Taming Guide item opened " + last.get(p.getUUID()));
        }
        // the hook other modules use
        Hooks.Codex hook = Feather.get().service(Hooks.Codex.class);
        if (hook == null) problems.add("no Hooks.Codex provider");
        else {
            last.remove(p.getUUID());
            hook.openCodex(p, "mounts", null, null);
            if (!"category:mounts".equals(last.get(p.getUUID()))) problems.add("Hooks.Codex opened " + last.get(p.getUUID()));
            if (!hook.completions().contains("bosses") || !hook.completions().contains("emberkit")) problems.add("/codex tab completion " + hook.completions());
        }
        CT.disconnect(p);
        CT.report(h, "entry points", problems);
        h.succeed();
    }

    /** /pets is pet management only: no codex button in its chest, no "codex" in its tab completion. */
    @GameTest(maxTicks = 40)
    public void petsMenuHasNoCodex(GameTestHelper h) {
        CT.arena(h, 6, 6);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        @SuppressWarnings("unchecked")
        Map<UUID, String> last = (Map<UUID, String>) CT.get(codex(), "lastView");
        last.remove(p.getUUID());
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "pets");
        List<String> problems = new ArrayList<>();
        if (last.containsKey(p.getUUID())) problems.add("/pets opened the codex");
        var menu = p.containerMenu;
        if (menu == p.inventoryMenu) problems.add("/pets opened no chest");
        for (int i = 0; i < menu.slots.size() - 36; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.is(Items.KNOWLEDGE_BOOK) || s.getHoverName().getString().toLowerCase().contains("codex")) problems.add("slot " + i + " is a codex button: " + s.getHoverName().getString());
        }
        p.closeContainer();
        @SuppressWarnings("unchecked")
        List<String> subs = (List<String>) CT.call(PetsTests.module(), "onTabComplete", Sender.of(p), "pets", new String[]{""});
        if (subs.contains("codex")) problems.add("/pets still offers codex: " + subs);
        CT.disconnect(p);
        CT.report(h, "pets menu", problems);
        h.succeed();
    }

    /**
     * Discovery: a wild creature, a young one (its parent's page and the form), a boss's clone and a tamed pet all go
     * in the codex; only tameables count as tamed, against the tameable total.
     */
    @GameTest(maxTicks = 60, padding = 12)
    public void discoveryRecordsWildAndTamed(GameTestHelper h) {
        CT.arena(h, 10, 10);
        ServerPlayer p = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        Object codex = codex();
        CT.call(codex, "reset", p.getUUID());
        List<Mob> spawned = new ArrayList<>();
        // (no real Hair Clone here: the Wukong fight test counts every clone within 96 blocks; see() stands in for it)
        for (String[] s : new String[][]{{"tufthorn", "3"}, {"bristleboar_young", "5"}, {"lurefin", "7"}}) {
            Mob m = CT.spawn(h, ArcanaEntities.species(ArcanaEntities.type(s[0])), new Vec3(Integer.parseInt(s[1]), 1, 4));
            m.setNoAi(true);
            spawned.add(m);
        }
        var pet = PetsTests.tamed(h, ArcanaEntities.species(ArcanaEntities.type("mossbun")), p, new Vec3(2, 1, 2));
        CT.call(codex, "scan");
        CT.call(codex, "see", p, "ArcanaWukongClone");
        Object book = CT.call(codex, "book", p.getUUID());
        @SuppressWarnings("unchecked")
        Set<String> seen = (Set<String>) CT.get(book, "seen"), tamed = (Set<String>) CT.get(book, "tamed"), forms = (Set<String>) CT.get(book, "forms");
        List<String> problems = new ArrayList<>();
        for (String id : List.of("ArcanaTufthorn", "ArcanaBristleboar", "ArcanaWukong", "ArcanaLurefin", "ArcanaMossbun")) {
            if (!seen.contains(id)) problems.add(id + " not seen (seen " + seen + ")");
        }
        if (!forms.containsAll(List.of("ArcanaBristleboarYoung", "ArcanaWukongClone"))) problems.add("forms " + forms);
        if (!tamed.contains("ArcanaMossbun")) problems.add("the tamed Mossbun is not tamed in the codex");
        // wild creatures are never "tamed", even if something says so
        CT.call(codex, "tamed", p.getUUID(), "ArcanaTufthorn", true);
        if (tamed.contains("ArcanaTufthorn")) problems.add("a Tufthorn counted as tamed");
        int tameable = (int) CT.call(codex, "tameableTotal"), total = (int) CT.call(codex, "total");
        if (tameable <= 0 || tameable >= total) problems.add("tameable " + tameable + " of " + total);
        if ((int) CT.call(codex, "tamedCount", book) != 1) problems.add("tamed count " + CT.call(codex, "tamedCount", book));
        int unseen = (int) CT.call(codex, "unseen", p.getUUID());
        if (unseen != total - (int) CT.call(codex, "seenCount", book)) problems.add("unseen " + unseen);
        for (Mob m : spawned) m.discard();
        pet.discard();
        CT.call(codex, "reset", p.getUUID());
        CT.disconnect(p);
        CT.report(h, "discovery", problems);
        h.succeed();
    }

    /** pets-codex.yml from the plain-map snapshot (dumped off the server thread) is what the old Yaml.set() save wrote. */
    @GameTest
    @SuppressWarnings("unchecked")
    public void codexSaveKeepsItsFormat(GameTestHelper h) {
        Object codex = codex();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), empty = UUID.randomUUID();
        try {
            Object ba = CT.call(codex, "book", a);
            ((Set<String>) CT.get(ba, "seen")).addAll(List.of("ArcanaTufthorn", "ArcanaMossbun"));
            ((Set<String>) CT.get(ba, "tamed")).add("ArcanaMossbun");
            ((Set<String>) CT.get(ba, "milestones")).add("5");
            ((Set<String>) CT.get(ba, "variants")).add("ArcanaMossbun:shiny");
            ((Set<String>) CT.get(ba, "forms")).add("ArcanaBristleboarYoung");
            Object bb = CT.call(codex, "book", b);
            ((Set<String>) CT.get(bb, "seen")).add("ArcanaLurefin");
            CT.call(codex, "book", empty);
            Yaml old = new Yaml();
            for (Map.Entry<UUID, Object> en : ((Map<UUID, Object>) CT.get(codex, "books")).entrySet()) {
                Object bk = en.getValue();
                Set<String> seen = (Set<String>) CT.get(bk, "seen"), tamed = (Set<String>) CT.get(bk, "tamed"), ms = (Set<String>) CT.get(bk, "milestones"),
                        vs = (Set<String>) CT.get(bk, "variants"), fs = (Set<String>) CT.get(bk, "forms");
                if (seen.isEmpty() && tamed.isEmpty() && ms.isEmpty() && vs.isEmpty() && fs.isEmpty()) continue;
                String base = "players." + en.getKey() + ".";
                old.set(base + "seen", new ArrayList<>(seen));
                old.set(base + "tamed", new ArrayList<>(tamed));
                if (!ms.isEmpty()) old.set(base + "milestones", new ArrayList<>(ms));
                if (!vs.isEmpty()) old.set(base + "variants", new ArrayList<>(vs));
                if (!fs.isEmpty()) old.set(base + "forms", new ArrayList<>(fs));
            }
            String now = Yaml.dump(CT.call(codex, "snapshot"));
            h.assertValueEqual(now, old.saveToString(), "pets-codex.yml text");
            h.assertTrue(now.contains(a.toString()) && !now.contains(empty.toString()), "the snapshot keeps the right players");
        } finally {
            for (UUID u : List.of(a, b, empty)) CT.call(codex, "reset", u);
        }
        h.succeed();
    }

    /**
     * A reload that changed none of the codex's inputs keeps the built entries (no re-parse of every text); a change to
     * codex.yml on disk, or /codex reload, rebuilds them.
     */
    @GameTest
    public void entriesRebuildOnlyWhenInputsChange(GameTestHelper h) {
        Object m = PetsTests.module(), codex = codex();
        Object cfg = CT.get(m, "cfg"), species = CT.get(m, "species");
        java.nio.file.Path file = Feather.get().getDataFolder().resolve("codex.yml");
        String id = "ArcanaCorgi";
        Object first = CT.call(codex, "entry", id);
        h.assertTrue(first != null, "no codex entry for " + id);
        long skipped = Long.MAX_VALUE, forced = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long a = System.nanoTime();
            CT.call(codex, "reload", cfg, species);
            skipped = Math.min(skipped, System.nanoTime() - a);
        }
        h.assertTrue(CT.call(codex, "entry", id) == first, "an unchanged reload rebuilt the entries");
        for (int i = 0; i < 3; i++) {
            long a = System.nanoTime();
            CT.call(codex, "reload", cfg, species, true);
            forced = Math.min(forced, System.nanoTime() - a);
        }
        Object second = CT.call(codex, "entry", id);
        h.assertTrue(second != first, "a forced reload (/codex reload) kept the old entries");
        byte[] before;
        try {
            before = java.nio.file.Files.readAllBytes(file);
            java.nio.file.Files.write(file, (new String(before, java.nio.charset.StandardCharsets.UTF_8) + "\n# codex test\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        try {
            CT.call(codex, "reload", cfg, species);
            h.assertTrue(CT.call(codex, "entry", id) != second, "a changed codex.yml did not rebuild the entries");
        } finally {
            try {
                java.nio.file.Files.write(file, before);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            CT.call(codex, "reload", cfg, species);
        }
        net.axther.aftrhrs.AftrHrs.LOG.info("[bt-creatures] codex reload: unchanged {} ms, rebuilt {} ms", skipped / 1e6, forced / 1e6);
        h.succeed();
    }
}
