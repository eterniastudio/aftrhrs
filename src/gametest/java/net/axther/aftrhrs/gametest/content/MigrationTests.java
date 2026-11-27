package net.axther.aftrhrs.gametest.content;

import net.axther.aftrhrs.content.ArcanaContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Paper items (Oraxen ids in custom_data.PublicBukkitValues) become native items as they load: plain, inside a shulker
 * box, with a real rename kept and Oraxen's blank name / markers dropped (so they stack with crafted ones).
 * (World furniture / storage conversion is exercised on the dev server with the Paper sample world; see BUGS_content.md.)
 */
public class MigrationTests {
    static ItemStack load(ServerLevel level, String snbt) throws Exception {
        CompoundTag t = TagParser.parseCompoundFully(snbt);
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return ItemStack.CODEC.parse(ops, t).getOrThrow();
    }

    @GameTest
    public void oraxenItemsBecomeNative(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("migration_items");
        String chair = "{id:\"minecraft:paper\",count:3,components:{\"minecraft:custom_name\":\"\",\"minecraft:item_name\":\"Oak Chair\","
                + "\"minecraft:item_model\":\"oraxen:arcana_oak_chair\",\"minecraft:custom_data\":{PublicBukkitValues:{\"oraxen:id\":\"arcana_oak_chair\",\"oraxen:furniture\":1b}}}}";
        ItemStack s = load(level, chair);
        f.check(s.is(ArcanaContent.item("arcana_oak_chair")) && s.getCount() == 3, "Oraxen chair loaded as " + s);
        f.check(ItemStack.isSameItemSameComponents(s, new ItemStack(ArcanaContent.item("arcana_oak_chair"))), "converted chair doesn't stack with a new one: " + s.getComponentsPatch());
        // a real rename stays, Feather keys move to the top of custom_data
        String renamed = "{id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_name\":\"Grandpa's chair\",\"minecraft:custom_data\":{PublicBukkitValues:{\"oraxen:id\":\"arcana_oak_chair\",\"feather:crafter\":\"Kev\"}}}}";
        ItemStack r = load(level, renamed);
        f.check(r.is(ArcanaContent.item("arcana_oak_chair")), "renamed chair loaded as " + r);
        f.check(r.has(DataComponents.CUSTOM_NAME) && r.getHoverName().getString().equals("Grandpa's chair"), "rename lost: " + r.getHoverName().getString());
        var cd = r.get(DataComponents.CUSTOM_DATA);
        f.check(cd != null && cd.copyTag().getStringOr("feather:crafter", "").equals("Kev") && !cd.copyTag().contains("PublicBukkitValues"), "Feather key not moved: " + cd);
        // inside a shulker box
        String box = "{id:\"minecraft:shulker_box\",count:1,components:{\"minecraft:container\":[{slot:0,item:" + chair + "}]}}";
        ItemStack b = load(level, box);
        ItemContainerContents cc = b.get(DataComponents.CONTAINER);
        ItemStack inside = cc == null ? ItemStack.EMPTY : cc.copyOne();
        f.check(inside.is(ArcanaContent.item("arcana_oak_chair")), "chair inside a shulker box loaded as " + inside);
        // unknown Oraxen ids keep their item (nothing is lost)
        ItemStack u = load(level, "{id:\"minecraft:paper\",count:2,components:{\"minecraft:custom_data\":{PublicBukkitValues:{\"oraxen:id\":\"no_such_thing_123\"}}}}");
        f.check(u.is(Items.PAPER) && u.getCount() == 2, "unknown Oraxen id became " + u);
        f.finish(h);
    }

    /**
     * The legacy hook sits on the public decode constructor, not on copy(): a converted stack copies as the native
     * item, with no Paper data coming back, and copies of plain stacks stay plain.
     */
    @GameTest
    public void convertedItemsCopyAsNative(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        CT.Fails f = new CT.Fails("migration_copy");
        String chair = "{id:\"minecraft:paper\",count:2,components:{\"minecraft:custom_data\":{PublicBukkitValues:{\"oraxen:id\":\"arcana_oak_chair\",\"feather:crafter\":\"Kev\"}}}}";
        ItemStack s = load(level, chair);
        ItemStack c = s.copy();
        f.check(c.is(ArcanaContent.item("arcana_oak_chair")) && c.getCount() == 2, "copy of a converted chair is " + c);
        var cd = c.get(DataComponents.CUSTOM_DATA);
        f.check(cd == null || !cd.copyTag().contains("PublicBukkitValues"), "copy has Paper data again: " + cd);
        f.check(cd != null && cd.copyTag().getStringOr("feather:crafter", "").equals("Kev"), "copy lost the moved Feather key: " + cd);
        ItemStack split = c.split(1);
        f.check(split.is(ArcanaContent.item("arcana_oak_chair")) && ItemStack.isSameItemSameComponents(split, c), "split of a converted chair is " + split);
        ItemStack plain = new ItemStack(Items.PAPER, 3).copy();
        f.check(plain.is(Items.PAPER) && plain.getCount() == 3 && plain.getComponentsPatch().isEmpty(), "plain paper copied as " + plain);
        f.finish(h);
    }

    // ------------------------------------------------------------------ Yaml.dump fast path

    /** Strings the fuzz is built from: YAML indicators, resolver look-alikes, unicode, plus pieces SnakeYAML writes its own way. */
    private static final String FUZZ_ALPHA = "ab: #-?'\"!&*[]{},|>%@`~0123456789.eE+_xyNnoOfFtTyY\u00a7\u00e9<=";
    private static final String[] FUZZ_EXTRA = {"\uD83D\uDE00", "\u00A0", "\u3042", " ", "..", "--", "\t", "\n", "\u0085", "\uD800",
            "\uDC00", "\u2028", "\uFEFF", "\uFFFE", "\u0007", "\u007F", "---", "...", "0x", "1_0", ":", "#", "\u00a7a", "~", "<<"};
    private static final String[] EDGE = {"yes", "no", "off", "on", "Y", "n", "~", "null", "Null", "", "12:30", "2026-11-11", "2026-11-11 10:00:00",
            "0x1F", "1_000", ".5", "08", "0_", "-08", "0", "00", "+1", "1e5", "1.0E10", ".inf", ".NaN", "<<", "=", ":x", "x:", "a: b", "a #b", "a#b",
            "- x", "-x", "? x", "?x", "trail ", " lead", "\uD83D\uDE00 emoji", "\u00a76gold \u00a7lbold", "it's", "\"q\"", "@at", "`tick", "%p", "a,b",
            "[x]", "{x}", "&a", "*a", "!t", "|", ">", "---x", "...x", "a: ", "#", "a:b", "true", "False", "TRUE", "1:20:30", "-.inf", "+.inf", "1,000"};

    /**
     * Yaml.dump writes the plain snapshot trees itself (FastDump) instead of through SnakeYAML; its text must be
     * SnakeYAML's byte for byte. A seeded fuzz of 100k documents, the fixed edge cases, every data / config file
     * the test server has and (when present) the soak world's data; for anything FastDump hands back, dump() is
     * still SnakeYAML's text.
     */
    @GameTest
    public void fastDumpMatchesSnakeYaml(GameTestHelper h) throws Exception {
        CT.Fails f = new CT.Fails("yaml_fast_dump");
        long t0 = System.nanoTime();
        java.util.Random rnd = new java.util.Random(20261125L);
        int same = 0, bailed = 0, n = 100_000;
        for (int t = 0; t < n; t++) {
            String s = t < EDGE.length ? EDGE[t] : fuzzString(rnd, rnd.nextInt(60) == 0 ? 130 + rnd.nextInt(1500) : rnd.nextInt(8));
            String k = fuzzString(rnd, rnd.nextInt(6));
            java.util.Map<String, Object> doc = fuzzDoc(rnd, s, k);
            String snake;
            try {
                snake = net.axther.aftrhrs.core.Yaml.snakeDump(doc);
            } catch (RuntimeException e) {
                continue;
            }
            String fast = net.axther.aftrhrs.core.Yaml.fastDump(doc);
            if (fast == null) {
                bailed++;
                if (!net.axther.aftrhrs.core.Yaml.dump(doc).equals(snake)) f.add("dump() after a bail differs for [" + esc(s) + "]");
                continue;
            }
            if (!fast.equals(snake)) {
                f.add("fast dump differs for [" + esc(s) + "] / key [" + esc(k) + "]:\n" + snake + "---\n" + fast);
                continue;
            }
            same++;
            // the text reads back as the same string (a cheap round trip on every case)
            Object back = ((java.util.List<?>) net.axther.aftrhrs.core.Yaml.fromString(fast).raw().get("v")).get(0);
            if (!s.equals(back)) f.add("[" + esc(s) + "] read back as [" + back + "]");
        }
        long fuzzMs = (System.nanoTime() - t0) / 1_000_000;
        f.check(same > n / 2, "only " + same + " of " + n + " fuzz documents took the fast path");
        net.axther.aftrhrs.AftrHrs.LOG.info("[bt-content] yaml_fast_dump fuzz: {} documents, {} byte-identical, {} handed to SnakeYAML, {} ms", n, same, bailed, fuzzMs);

        // the forms SnakeYAML writes its own way go to it, whole document
        String[] bail = {"line\nbreak", "tab\there", "bell\u0007", "nel\u0085", "bom\uFEFF", "half\uD800"};
        for (String s : bail) {
            java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
            doc.put("ok", "fine");
            doc.put("v", s);
            f.check(net.axther.aftrhrs.core.Yaml.fastDump(doc) == null, "fast path took [" + esc(s) + "]");
            // (SnakeYAML refuses a lone surrogate outright: dump() must fail the same way, not write something)
            f.check(textOrError(() -> net.axther.aftrhrs.core.Yaml.dump(doc)).equals(textOrError(() -> net.axther.aftrhrs.core.Yaml.snakeDump(doc))),
                    "dump() of [" + esc(s) + "] isn't SnakeYAML's");
        }
        java.util.Map<String, Object> longKey = new java.util.LinkedHashMap<>();
        longKey.put("k".repeat(130), 1);
        java.util.Map<String, Object> emptyKey = new java.util.LinkedHashMap<>();
        emptyKey.put("", 1);
        java.util.Map<String, Object> uuid = new java.util.LinkedHashMap<>();
        uuid.put("id", java.util.UUID.fromString("7f1c3a52-0c1a-4f5e-9d3c-111111111111"));
        java.util.Map<String, Object> floatNan = new java.util.LinkedHashMap<>();
        floatNan.put("f", Float.NaN);
        for (java.util.Map<String, Object> doc : java.util.List.of(longKey, emptyKey, uuid, floatNan)) {
            f.check(net.axther.aftrhrs.core.Yaml.fastDump(doc) == null, "fast path took " + doc.keySet());
            f.check(net.axther.aftrhrs.core.Yaml.dump(doc).equals(net.axther.aftrhrs.core.Yaml.snakeDump(doc)), "dump() of " + doc.keySet() + " isn't SnakeYAML's");
        }
        // SnakeYAML counts the key's "!!str" tag (5) toward its simple-key limit of 128: from 123 characters a key is
        // written "? key", plain or quoted, at the root or nested
        for (int len : new int[]{121, 122, 123, 127, 128}) {
            for (String unit : new String[]{"k", "a: "}) {
                String key = unit.repeat(len).substring(0, len);
                java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
                root.put(key, 1);
                java.util.Map<String, Object> nested = new java.util.LinkedHashMap<>();
                nested.put("x", new java.util.LinkedHashMap<>(java.util.Map.of(key, java.util.List.of("v"))));
                for (java.util.Map<String, Object> doc : java.util.List.of(root, nested)) {
                    String what = "key of " + len + " [" + unit.trim() + "] " + (doc == root ? "at the root" : "nested");
                    same(f, what, doc);
                    boolean fast = net.axther.aftrhrs.core.Yaml.fastDump(doc) != null;
                    f.check(fast == (len < 123), what + (fast ? ": fast path took it" : ": fast path refused it"));
                }
            }
        }
        // a collection that appears twice gets an anchor from SnakeYAML: not the fast path
        java.util.Map<String, Object> shared = new java.util.LinkedHashMap<>(), twice = new java.util.LinkedHashMap<>();
        twice.put("a", shared);
        twice.put("b", shared);
        f.check(net.axther.aftrhrs.core.Yaml.fastDump(twice) == null, "fast path took a shared map");
        // nesting: list of list of map, empty map / list in both positions, numbers of every kind
        java.util.Map<String, Object> nest = new java.util.LinkedHashMap<>();
        nest.put("ll", java.util.List.of(java.util.List.of(new java.util.LinkedHashMap<>(java.util.Map.of("a", 1))), java.util.List.of(new java.util.ArrayList<>(), new java.util.LinkedHashMap<>())));
        nest.put("m", new java.util.LinkedHashMap<>());
        nest.put("l", new java.util.ArrayList<>());
        nest.put("deep", java.util.List.of(new java.util.LinkedHashMap<>(java.util.Map.of("x", java.util.List.of("p", "q")))));
        nest.put("nums", java.util.List.of(1, -1L, Long.MIN_VALUE, (short) 7, (byte) -2, 0.1, -0.0, 1e300, 4.9e-324, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.25f, 3.4028235e38f, true, false));
        nest.put("nil", null);
        same(f, "nesting", nest);

        // real files: everything in the test server's data folder (configs and stores), then the soak world's data
        java.util.List<java.nio.file.Path> files = new java.util.ArrayList<>();
        java.nio.file.Path root = net.axther.aftrhrs.core.Feather.get().getDataFolder();
        java.nio.file.Path soak = java.nio.file.Path.of("C:/Users/kevin/Desktop/aftrhrs-wt/bt-soak/mod/run-soak/aftrhrs");
        for (java.nio.file.Path dir : java.util.List.of(root, soak)) {
            if (!java.nio.file.Files.isDirectory(dir)) continue;
            try (var walk = java.nio.file.Files.walk(dir, 4)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".yml") && java.nio.file.Files.isRegularFile(p)).forEach(files::add);
            }
        }
        int checked = 0;
        for (java.nio.file.Path p : files) {
            net.axther.aftrhrs.core.Yaml y;
            try {
                y = net.axther.aftrhrs.core.Yaml.loadStrict(p);
            } catch (Exception e) {
                continue;   // not ours to judge here
            }
            Object snap = y.snapshot();
            if (snap == null) continue;
            same(f, p.toString(), snap);
            checked++;
        }
        f.check(checked >= 5, "only " + checked + " data files to compare");
        net.axther.aftrhrs.AftrHrs.LOG.info("[bt-content] yaml_fast_dump: {} data/config files compared, {} ms in all", checked, (System.nanoTime() - t0) / 1_000_000);
        f.finish(h);
    }

    // ------------------------------------------------------------------ SafeYaml read stamps

    /**
     * A file read successfully is trusted by the next write without a second parse, but only while it is unchanged:
     * the good file becomes the .bak; one changed into broken YAML after the read is kept as .broken-* and the .bak stays.
     */
    @GameTest
    public void safeYamlTrustsOnlyUnchangedReads(GameTestHelper h) throws Exception {
        CT.Fails f = new CT.Fails("safeyaml_read_stamp");
        org.slf4j.Logger log = net.axther.aftrhrs.AftrHrs.LOG;
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("safeyaml");
        // (1) read a good file, then write: the old content is the backup, nothing is called broken
        java.nio.file.Path good = dir.resolve("good.yml");
        String old = "a: 1\nlist:\n- x\n";
        java.nio.file.Files.writeString(good, old);
        net.axther.aftrhrs.core.Yaml y = net.axther.aftrhrs.core.SafeYaml.read(good, log, "test data");
        f.check(y != null && y.getInt("a") == 1, "good file didn't read: " + y);
        f.check(net.axther.aftrhrs.core.SafeYaml.write(good, "a: 2\n", log), "write failed");
        java.nio.file.Path bak = dir.resolve("good.yml.bak");
        f.check(java.nio.file.Files.exists(bak) && java.nio.file.Files.readString(bak).equals(old), "backup isn't the old content: "
                + (java.nio.file.Files.exists(bak) ? java.nio.file.Files.readString(bak) : "missing"));
        f.check(brokenCopies(dir, "good.yml") == 0, "a good file was kept as broken");
        f.check(java.nio.file.Files.readString(good).equals("a: 2\n"), "new content not written");
        // (2) read a good file, then it turns into broken YAML (a byte appended, a new mtime): the write must notice
        java.nio.file.Path later = dir.resolve("later.yml");
        java.nio.file.Files.writeString(later, "b: 1\n");
        java.nio.file.Files.writeString(dir.resolve("later.yml.bak"), "b: 0\n");
        f.check(net.axther.aftrhrs.core.SafeYaml.read(later, log, "test data") != null, "later.yml didn't read");
        java.nio.file.Files.writeString(later, "b: [\n", java.nio.file.StandardOpenOption.APPEND);
        java.nio.file.Files.setLastModifiedTime(later, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        f.check(net.axther.aftrhrs.core.SafeYaml.write(later, "b: 2\n", log), "write over the changed file failed");
        f.check(brokenCopies(dir, "later.yml") == 1, "the file broken after the read wasn't kept (" + brokenCopies(dir, "later.yml") + " copies)");
        f.check(java.nio.file.Files.readString(dir.resolve("later.yml.bak")).equals("b: 0\n"), "a broken file replaced the backup");
        // (3) same size, new mtime, broken content: the stamp alone must catch it
        java.nio.file.Path same = dir.resolve("same.yml");
        java.nio.file.Files.writeString(same, "c: 12\n");
        java.nio.file.Files.writeString(dir.resolve("same.yml.bak"), "c: 0\n");
        f.check(net.axther.aftrhrs.core.SafeYaml.read(same, log, "test data") != null, "same.yml didn't read");
        java.nio.file.Files.writeString(same, "c: [2\n");
        java.nio.file.Files.setLastModifiedTime(same, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 7000));
        f.check(net.axther.aftrhrs.core.SafeYaml.write(same, "c: 3\n", log), "write over the same-size file failed");
        f.check(brokenCopies(dir, "same.yml") == 1, "same-size broken file wasn't kept");
        f.check(java.nio.file.Files.readString(dir.resolve("same.yml.bak")).equals("c: 0\n"), "same-size broken file replaced the backup");
        f.finish(h);
    }

    // ------------------------------------------------------------------ data alerts

    /** An unreadable data file is listed for staff (one line when they join, none for others) until a good read. */
    @GameTest
    public void unreadableDataFilesAlertStaff(GameTestHelper h) throws Exception {
        CT.Fails f = new CT.Fails("data_alerts");
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("alerts");
        java.nio.file.Path file = dir.resolve("alerted-store.yml");
        java.nio.file.Files.writeString(file, "a: [\n");
        net.axther.aftrhrs.core.Yaml y = net.axther.aftrhrs.core.SafeYaml.read(file, net.axther.aftrhrs.AftrHrs.LOG, "test entries");
        f.check(y == null, "a broken file read as " + y);
        f.check(net.axther.aftrhrs.core.DataAlerts.isBroken(file), "broken file not registered");
        f.check(net.axther.aftrhrs.core.DataAlerts.lines().stream().anyMatch(c -> c.getString().contains("alerted-store.yml") && c.getString().contains("test entries")),
                "no alert line names the file: " + net.axther.aftrhrs.core.DataAlerts.lines().stream().map(c -> c.getString()).toList());
        CT.Bot admin = CT.join(h, "adm", true);
        CT.Bot user = CT.join(h, "usr", false);
        try {
            admin.drain();
            user.drain();
            // (JOIN fires inside placeNewPlayer, before CT.join makes the bot an operator: call the join step again)
            net.axther.aftrhrs.core.DataAlerts.joined(admin.p);
            net.axther.aftrhrs.core.DataAlerts.joined(user.p);
            f.check(chatLines(admin).stream().filter(s -> s.contains("alerted-store.yml")).count() == 1, "staff didn't get exactly one line for the file");
            f.check(chatLines(user).stream().noneMatch(s -> s.contains("alerted-store.yml")), "a non-staff player got the alert");
        } finally {
            admin.leave();
            user.leave();
        }
        java.nio.file.Files.writeString(file, "a: 1\n");
        f.check(net.axther.aftrhrs.core.SafeYaml.read(file, net.axther.aftrhrs.AftrHrs.LOG, "test entries") != null, "the fixed file didn't read");
        f.check(!net.axther.aftrhrs.core.DataAlerts.isBroken(file), "a good read didn't clear the alert");
        // a reader that pauses nothing (alert off) only logs: staff aren't told
        java.nio.file.Path quiet = dir.resolve("quiet-store.yml");
        java.nio.file.Files.writeString(quiet, "a: [\n");
        f.check(net.axther.aftrhrs.core.SafeYaml.read(quiet, net.axther.aftrhrs.AftrHrs.LOG, "nothing", false) == null, "a broken file read quietly");
        f.check(!net.axther.aftrhrs.core.DataAlerts.isBroken(quiet), "a read with the alert off registered the file");
        f.finish(h);
    }

    private static java.util.List<String> chatLines(CT.Bot bot) {
        java.util.List<String> out = new java.util.ArrayList<>();
        bot.channel.runPendingTasks();
        bot.channel.flushOutbound();
        Object o;
        while ((o = bot.channel.readOutbound()) != null) {
            if (o instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket s && !s.overlay()) out.add(s.content().getString());
        }
        return out;
    }

    // ------------------------------------------------------------------ analytics index

    /**
     * Analytics keeps players.idx, a cache of every player file: a file with the size and mtime the index saw comes
     * from it, a changed one is read, a deleted one is gone, a corrupt or missing index means a full read with the
     * same values, and the index is (re)written on the IO thread.
     */
    @GameTest
    public void analyticsIndexFollowsTheFiles(GameTestHelper h) throws Exception {
        CT.Fails f = new CT.Fails("analytics_index");
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("stats");
        java.nio.file.Path players = dir.resolve("players");
        java.nio.file.Files.createDirectories(players);
        java.nio.file.Path idx = dir.resolve("players.idx");
        int n = Integer.getInteger("aftrhrs.test.statsPlayers", 200);
        java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            java.util.UUID id = java.util.UUID.nameUUIDFromBytes(("stats-player-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ids.add(id);
            java.nio.file.Files.writeString(players.resolve(id + ".yml"), statsFile("P" + i, i, 3));
        }
        java.nio.file.Files.writeString(players.resolve("not-a-uuid.yml"), "name: x\n");
        var m = net.axther.aftrhrs.feature.analytics.AnalyticsModule.detached(net.axther.aftrhrs.core.Feather.get(), dir);
        try {
            long t = System.nanoTime();
            int[] r = m.loadForTest();
            long fullMs = (System.nanoTime() - t) / 1_000_000;
            f.check(r[0] == 0 && r[1] == n, "first load (no index): " + java.util.Arrays.toString(r));
            checkStats(f, m, ids, -1, -1, "full read");
            // (d) no index -> written once the IO thread drains
            m.drainForTest();
            f.check(java.nio.file.Files.exists(idx), "players.idx not written after a load without one");
            t = System.nanoTime();
            r = m.loadForTest();
            long idxMs = (System.nanoTime() - t) / 1_000_000;
            f.check(r[0] == n && r[1] == 0, "second load: " + java.util.Arrays.toString(r));
            checkStats(f, m, ids, -1, -1, "from index");
            net.axther.aftrhrs.AftrHrs.LOG.info("[bt-content] analytics_index: {} players, full read {} ms, from index {} ms", n, fullMs, idxMs);
            // (a) one file rewritten with another value -> that one is read again
            java.nio.file.Path five = players.resolve(ids.get(5) + ".yml");
            java.nio.file.Files.writeString(five, statsFile("P5", 999, 3));
            java.nio.file.Files.setLastModifiedTime(five, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 3000));
            r = m.loadForTest();
            f.check(r[0] == n - 1 && r[1] == 1, "load after one rewrite: " + java.util.Arrays.toString(r));
            f.check(m.get(ids.get(5), "blocks-mined") == 999, "rewritten file not re-read: " + m.get(ids.get(5), "blocks-mined"));
            m.drainForTest();
            // (b) one file deleted -> gone
            java.nio.file.Files.delete(players.resolve(ids.get(7) + ".yml"));
            r = m.loadForTest();
            f.check(r[0] == n - 1 && r[1] == 0, "load after one delete: " + java.util.Arrays.toString(r));
            f.check(m.get(ids.get(7), "blocks-mined") == 0 && m.get(ids.get(7), "boss.x_y") == 0, "deleted player still has stats");
            f.check(m.get(ids.get(5), "blocks-mined") == 999, "rewritten value not in the new index");
            // a counted + flushed player: its file and the index both follow
            m.increment(ids.get(9), "blocks-mined", 5);
            m.flushForTest();
            m.drainForTest();
            r = m.loadForTest();
            f.check(r[0] == n - 1 && r[1] == 0, "load after a flush: " + java.util.Arrays.toString(r));
            f.check(m.get(ids.get(9), "blocks-mined") == 14, "flushed value: " + m.get(ids.get(9), "blocks-mined"));
            // (c) a corrupt index -> a full read with the same values
            java.nio.file.Files.write(idx, new byte[]{'A', 'H', 'S', 'I', 0, 0, 0, 1, 0x7f, 0x7f});
            r = m.loadForTest();
            f.check(r[0] == 0 && r[1] == n - 1, "load with a corrupt index: " + java.util.Arrays.toString(r));
            checkStats(f, m, ids, 5, 7, "after a corrupt index");
            f.check(m.get(ids.get(9), "blocks-mined") == 14, "flushed value after a full read: " + m.get(ids.get(9), "blocks-mined"));
            m.drainForTest();
            r = m.loadForTest();
            f.check(r[0] == n - 1 && r[1] == 0, "the index was not rebuilt after a corrupt one: " + java.util.Arrays.toString(r));
        } finally {
            m.closeForTest();
        }
        f.finish(h);
    }

    private static String statsFile(String name, long mined, long boss) {
        return "name: " + name + "\nstats:\n  blocks-mined: " + mined + "\n  boss:\n    x_y: " + boss + "\n  playtime: 60\n";
    }

    /** Every player's stats as written by {@link #statsFile} (skip: the rewritten / deleted ones, or -1). */
    private static void checkStats(CT.Fails f, net.axther.aftrhrs.feature.analytics.AnalyticsModule m, java.util.List<java.util.UUID> ids, int rewritten, int deleted, String when) {
        for (int i = 0; i < ids.size(); i++) {
            if (i == rewritten || i == deleted || (rewritten >= 0 && i == 9)) continue;
            java.util.UUID id = ids.get(i);
            if (m.get(id, "blocks-mined") != i || m.get(id, "boss.x_y") != 3 || m.get(id, "playtime") != 60 || !("P" + i).equals(m.nameOf(id))) {
                f.add(when + ": player " + i + " has " + m.get(id, "blocks-mined") + "/" + m.get(id, "boss.x_y") + "/" + m.get(id, "playtime") + " " + m.nameOf(id));
                return;
            }
        }
    }

    private static long brokenCopies(java.nio.file.Path dir, String name) throws java.io.IOException {
        try (var s = java.nio.file.Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(name + ".broken-")).count();
        }
    }

    /** FastDump of a snapshot equals SnakeYAML's (when it takes it) and reads back as the same tree. */
    private static void same(CT.Fails f, String what, Object snap) {
        String snake = net.axther.aftrhrs.core.Yaml.snakeDump(snap);
        String fast = net.axther.aftrhrs.core.Yaml.fastDump(snap);
        f.check(net.axther.aftrhrs.core.Yaml.dump(snap).equals(snake), what + ": dump() isn't SnakeYAML's text");
        if (fast == null) return;
        f.check(fast.equals(snake), what + ": fast dump differs from SnakeYAML's");
        f.check(net.axther.aftrhrs.core.Yaml.snakeDump(net.axther.aftrhrs.core.Yaml.fromString(fast).snapshot()).equals(snake), what + ": fast dump doesn't read back the same");
    }

    private static String textOrError(java.util.function.Supplier<String> dump) {
        try {
            return dump.get();
        } catch (RuntimeException e) {
            return "error: " + e.getClass().getName();
        }
    }

    private static String fuzzString(java.util.Random rnd, int len) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (rnd.nextInt(6) == 0) s.append(FUZZ_EXTRA[rnd.nextInt(FUZZ_EXTRA.length)]);
            else s.append(FUZZ_ALPHA.charAt(rnd.nextInt(FUZZ_ALPHA.length())));
        }
        return s.toString();
    }

    /** A document with the string as a list item, a map key and a nested value, around lists, maps and numbers. */
    private static java.util.Map<String, Object> fuzzDoc(java.util.Random rnd, String s, String k) {
        java.util.Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("k" + s, new java.util.ArrayList<>(java.util.List.of(new java.util.ArrayList<>(java.util.List.of(s)), new java.util.LinkedHashMap<>(), 1.5, -0.0, Long.MAX_VALUE)));
        inner.put(k.isEmpty() ? "e" : k, rnd.nextDouble() * Math.pow(10, rnd.nextInt(40) - 20));
        java.util.List<Object> l = new java.util.ArrayList<>();
        l.add(s);
        l.add(inner);
        l.add(new java.util.ArrayList<>());
        l.add(rnd.nextFloat() * 1e6f);
        l.add(rnd.nextInt());
        l.add(rnd.nextBoolean());
        l.add(null);
        java.util.Map<String, Object> lm = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> leaf = new java.util.LinkedHashMap<>();
        leaf.put("a", s);
        lm.put(s.isEmpty() ? "x" : s, new java.util.ArrayList<>(java.util.List.of(new java.util.ArrayList<>(java.util.List.of(leaf)))));
        l.add(lm);
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("v", l);
        if (!s.isEmpty() && !"v".equals(s)) doc.put(s, rnd.nextInt(3) == 0 ? new java.util.LinkedHashMap<>() : s);
        return doc;
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7E) b.append(String.format("\\u%04X", (int) c));
            else b.append(c);
        }
        return b.length() > 80 ? b.substring(0, 80) + "..." : b.toString();
    }
}
