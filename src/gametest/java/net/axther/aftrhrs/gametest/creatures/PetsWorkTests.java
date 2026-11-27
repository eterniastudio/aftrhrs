package net.axther.aftrhrs.gametest.creatures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.axther.aftrhrs.core.Feather;
import net.axther.aftrhrs.core.Yaml;
import net.axther.aftrhrs.entity.ArcanaCreature;
import net.axther.aftrhrs.entity.ArcanaEntities;
import net.axther.aftrhrs.entity.Species;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Work Here jobs, breeding, the menus / stable / codex screens, and pets.yml across a restart. */
public class PetsWorkTests {
    static ArcanaCreature tamed(GameTestHelper h, String id, ServerPlayer owner, Vec3 at) {
        return PetsTests.tamed(h, ArcanaEntities.species(ArcanaEntities.type(id)), owner, at);
    }

    /** Mossbun "Work Here": the crops around it grow when work time passes. */
    @GameTest(maxTicks = 100, padding = 16)
    public void workHereGrowsCrops(GameTestHelper h) {
        CT.arena(h, 12, 12);
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.FARMLAND);
                h.setBlock(new BlockPos(x, 1, z), Blocks.WHEAT.defaultBlockState());
            }
        }
        h.setBlock(new BlockPos(5, 0, 5), Blocks.WATER);
        h.setBlock(new BlockPos(5, 1, 5), Blocks.AIR);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        ArcanaCreature c = tamed(h, "mossbun", owner, new Vec3(5, 1, 6));
        Object rec = PetsTests.record(c);
        Object work = CT.call(PetsTests.module(), "work");
        String err = (String) CT.call(work, "order", owner, rec);
        h.assertTrue(err == null, "Work Here refused: " + err);
        h.assertTrue(c.staying(), "a working pet stays");
        CT.call(work, "fakeElapsed", rec, 120);
        h.runAfterDelay(60, () -> {
            int grown = 0;
            for (int x = 2; x <= 8; x++) for (int z = 2; z <= 8; z++) {
                var st = h.getBlockState(new BlockPos(x, 1, z));
                if (st.getBlock() instanceof CropBlock crop && crop.getAge(st) > 0) grown++;
            }
            h.assertTrue(grown > 0, "no crop grew after 2 hours of work");
            // following again ends the job
            c.setStaying(false, owner);
            h.assertFalse((boolean) CT.call(rec, "working"), "still working after following");
            c.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** Burrowpip "Work Here" (owner away): finds go into the nearby chest. */
    @GameTest(maxTicks = 100, padding = 16)
    public void workHereFillsChest(GameTestHelper h) {
        CT.arena(h, 12, 12);
        for (int x = 0; x <= 12; x++) for (int z = 0; z <= 12; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
        h.setBlock(new BlockPos(7, 1, 5), Blocks.CHEST);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(5, 1, 3));
        ArcanaCreature c = tamed(h, "burrowpip", owner, new Vec3(5, 1, 5));
        Object rec = PetsTests.record(c);
        Object work = CT.call(PetsTests.module(), "work");
        String err = (String) CT.call(work, "order", owner, rec);
        h.assertTrue(err == null, "Work Here refused: " + err);
        CT.call(work, "fakeElapsed", rec, 600);
        h.runAfterDelay(60, () -> {
            ChestBlockEntity chest = h.getBlockEntity(new BlockPos(7, 1, 5), ChestBlockEntity.class);
            int n = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) n += chest.getItem(i).getCount();
            h.assertTrue(n > 0, "the chest is empty after 10 hours of digging (waiting " + CT.get(rec, "workWaiting") + ", bank " + CT.get(rec, "workBank") + ", working " + CT.call(rec, "working") + ", spot " + CT.get(rec, "work") + " state " + where(h, rec) + ")");
            c.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    static String where(GameTestHelper h, Object rec) {
        Object spot = CT.get(rec, "work");
        if (spot == null) return "-";
        var l = (net.axther.aftrhrs.core.Loc) CT.call(spot, "location");
        var pos = l.blockPos();
        return l.level().getBlockState(pos) + " / below " + l.level().getBlockState(pos.below()) + " dirt-tag " + l.level().getBlockState(pos.below()).is(net.minecraft.tags.BlockTags.DIRT);
    }

    /** Two staying bond-3 corgis fed their treat (the tame item, Cooked Beef) make a bred baby; the treats are used. */
    @GameTest(maxTicks = 200, padding = 16)
    public void breedingMakesABaby(GameTestHelper h) {
        CT.arena(h, 10, 10);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        ArcanaCreature a = tamed(h, "corgi", owner, new Vec3(4, 1, 4));
        ArcanaCreature b = tamed(h, "corgi", owner, new Vec3(5, 1, 4));
        Object m = PetsTests.module();
        for (ArcanaCreature c : List.of(a, b)) {
            CT.call(m, "setBond", PetsTests.record(c), 3);
            c.setStaying(true, owner);
        }
        Species s = a.species();
        int before = PetsTests.petsOf(owner.getUUID()).size();
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(s.tameItem(), 4));
        PetsTests.withCap(1000, () -> {
            PetsTests.use(owner, a);
            PetsTests.use(owner, b);
        });
        h.assertValueEqual(owner.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 2, "treats after feeding both");
        int cap = (int) CT.get(m, "defaultCap");
        CT.set(m, "defaultCap", 1000);
        h.runAfterDelay(120, () -> {
            CT.set(m, "defaultCap", cap);
            List<Object> pets = PetsTests.petsOf(owner.getUUID());
            h.assertValueEqual(pets.size(), before + 1, "pets after breeding");
            Object baby = null;
            for (Object p : pets) if ((boolean) CT.get(p, "bred")) baby = p;
            h.assertTrue(baby != null, "no bred record");
            var e = PetsTests.entityOf(baby);
            h.assertTrue(e != null, "the baby is not in the world");
            // a fed staying pet below the treat's bond... still eats its food (the treat is not its food any more)
            a.setHealth(a.getMaxHealth() / 2);
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(s.healItem(), 2));
            PetsTests.use(owner, a);
            h.assertTrue(a.getHealth() > a.getMaxHealth() / 2, "a staying pet did not eat its food");
            for (var x : List.of(a, b)) x.discard();
            if (e != null) e.discard();
            CT.disconnect(owner);
            h.succeed();
        });
    }

    /** The /pets menu, a pet's page, the stable and the codex open for a player with pets (dialogs / chest menus). */
    @GameTest(maxTicks = 40)
    public void screensOpen(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        ArcanaCreature c = tamed(h, "luna", owner, new Vec3(3, 1, 3));
        Object m = PetsTests.module();
        Object rec = PetsTests.record(c);
        CT.call(m, "setBond", rec, 5);
        List<String> problems = new ArrayList<>();
        Runnable[] opens = {
                () -> CT.call(CT.call(m, "menu"), "open", owner, 0),
                () -> CT.call(CT.call(m, "menu"), "openActions", owner, rec),
                () -> CT.call(CT.call(m, "stable"), "open", owner, false),
                () -> CT.call(CT.call(m, "codex"), "open", owner, null, null, null),
                () -> h.getLevel().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "pets"),
                () -> h.getLevel().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "pets codex"),
                () -> h.getLevel().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "pets summon"),
                () -> h.getLevel().getServer().getCommands().performPrefixedCommand(h.getLevel().getServer().createCommandSourceStack(),
                        "pets admin list " + owner.getGameProfile().name()),
        };
        int i = 0;
        for (Runnable r : opens) {
            try {
                r.run();
            } catch (Throwable t) {
                problems.add("screen " + i + ": " + CT.err(t));
            }
            i++;
        }
        c.discard();
        CT.disconnect(owner);
        CT.report(h, "screens", problems);
        h.succeed();
    }

    /** pets.yml written and read back (a restart) keeps every record's state, name, bond, stay, variant and owner. */
    @GameTest(maxTicks = 40)
    public void storeSurvivesRestart(GameTestHelper h) {
        CT.arena(h, 8, 8);
        ServerPlayer owner = CT.player(h, GameType.SURVIVAL, new Vec3(1, 1, 1));
        ArcanaCreature c = tamed(h, "maine_coon", owner, new Vec3(3, 1, 3));
        Object m = PetsTests.module();
        Object rec = PetsTests.record(c);
        CT.call(m, "rename", rec, "Mr Whiskers");
        CT.call(m, "setBond", rec, 2);
        c.setStaying(true, owner);
        Object store = PetsTests.store();
        UUID id = PetsTests.petId(rec);
        Path journal = Feather.get().dataFile("pets.journal");
        Path yml = Feather.get().dataFile("pets.yml");
        // a durability point is one journal line, and a fresh load replays it
        CT.call(store, "journal", pets(rec));
        h.assertTrue(read(journal).contains(id.toString()), "the journal has no line for the pet");
        checkRestart(h, rec, id);
        // a full save holds it: the journal is emptied and pets.yml says how far it got
        CT.call(store, "saveNow");
        h.assertValueEqual(read(journal), "", "the journal after a full save");
        h.assertTrue(read(yml).contains("journal-seq: "), "pets.yml has no journal-seq");
        checkRestart(h, rec, id);
        c.discard();
        CT.disconnect(owner);
        h.succeed();
    }

    private static void checkRestart(GameTestHelper h, Object rec, UUID id) {
        Object fresh = newStore(Feather.get().dataFile("pets.yml"), Feather.get().dataFile("pets.journal"));
        CT.call(fresh, "load");
        Object back = CT.call(fresh, "get", id);
        h.assertTrue(back != null, "record lost across a restart");
        for (String f : List.of("name", "level", "xp", "stay", "species", "owner", "entity", "state")) {
            h.assertValueEqual(String.valueOf(CT.get(back, f)), String.valueOf(CT.get(rec, f)), f + " after a restart");
        }
        CT.call(fresh, "shutdown");   // not dirty after a replay: writes nothing
    }

    // ------------------------------------------------------------------ pets.yml + journal on scratch files

    static Object newStore(Path yml, Path journal) {
        try {
            var ctor = CT.cls("net.axther.aftrhrs.feature.pets.PetStore").getDeclaredConstructor(Feather.class, Path.class, Path.class);
            ctor.setAccessible(true);
            return ctor.newInstance(Feather.get(), yml, journal);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static Object newPet(UUID owner, String species, long created) {
        try {
            var ctor = CT.cls("net.axther.aftrhrs.feature.pets.Pet").getDeclaredConstructor(UUID.class, UUID.class, String.class);
            ctor.setAccessible(true);
            Object p = ctor.newInstance(UUID.randomUUID(), owner, species);
            CT.set(p, "created", created);
            return p;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static Object spot(UUID worldId, String key, String name, double x, double y, double z) {
        try {
            var ctor = CT.cls("net.axther.aftrhrs.feature.pets.Pet$Spot").getDeclaredConstructor(UUID.class, String.class, String.class,
                    double.class, double.class, double.class);
            ctor.setAccessible(true);
            return ctor.newInstance(worldId, key, name, x, y, z);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** A Pet[] for the varargs journal(Pet...). */
    static Object pets(Object... ps) {
        Object arr = java.lang.reflect.Array.newInstance(CT.cls("net.axther.aftrhrs.feature.pets.Pet"), ps.length);
        for (int i = 0; i < ps.length; i++) java.lang.reflect.Array.set(arr, i, ps[i]);
        return arr;
    }

    static String read(Path f) {
        try {
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void append(Path f, String text) {
        try {
            Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Path scratch() {
        try {
            return Files.createTempDirectory("aftrhrs-pets-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The journal replays in order on load: put, release tombstones, a torn last line skipped (and the next append
     * starts a new line), lines a full save already holds (seq at most journal-seq) ignored.
     */
    @GameTest
    public void journalReplaysAfterACrash(GameTestHelper h) {
        Path dir = scratch();
        Path yml = dir.resolve("pets.yml"), jr = dir.resolve("pets.journal");
        UUID owner = UUID.randomUUID();
        Object s1 = newStore(yml, jr);
        CT.call(s1, "load");
        Object a = newPet(owner, "ArcanaBlackCat", 1000L);
        Object b = newPet(owner, "ArcanaBlackCat", 2000L);
        UUID ida = PetsTests.petId(a), idb = PetsTests.petId(b);
        CT.call(s1, "add", a);
        CT.call(s1, "saveNow");
        h.assertTrue(read(yml).contains(ida.toString()), "pets.yml lacks the first pet");
        // the "crash": changes only in the journal from here on
        CT.set(a, "name", "Line one\nline two");
        CT.call(s1, "journal", pets(a));
        CT.call(s1, "add", b);
        CT.call(s1, "journal", pets(b));
        h.assertValueEqual(read(jr).lines().count(), 2L, "journal lines after two puts");
        CT.call(s1, "release", a, true);
        CT.call(s1, "journalRelease", a, true);
        append(jr, "{seq: 4, op: put, id: " + idb + ", pet: {owner: ");   // torn by the crash
        Object s2 = newStore(yml, jr);
        CT.call(s2, "load");
        h.assertTrue(CT.call(s2, "get", ida) == null, "a released pet came back from the journal");
        h.assertTrue((boolean) CT.call(s2, "isReleased", ida), "the release tombstone was not replayed");
        Object b2 = CT.call(s2, "get", idb);
        h.assertTrue(b2 != null, "a journalled pet is missing after a replay");
        h.assertValueEqual(((java.util.concurrent.atomic.AtomicLong) CT.get(s2, "seq")).get(), 3L, "seq after a replay (the torn line has none)");
        // the next append after a torn line starts on a new line
        CT.set(b2, "name", "Renamed");
        CT.call(s2, "journal", pets(b2));
        Object s3 = newStore(yml, jr);
        CT.call(s3, "load");
        h.assertValueEqual(String.valueOf(CT.get(CT.call(s3, "get", idb), "name")), "Renamed", "name after an append past a torn line");
        // a full save covers the journal; a stale line (seq <= journal-seq) is then ignored
        CT.call(s3, "saveNow");
        h.assertValueEqual(read(jr), "", "the journal after a full save");
        h.assertTrue(read(yml).contains("journal-seq: 4"), "pets.yml journal-seq after the save: " + read(yml).lines().limit(3).toList());
        append(jr, "{seq: 3, op: drop, id: " + idb + "}\n");
        Object s4 = newStore(yml, jr);
        CT.call(s4, "load");
        h.assertTrue(CT.call(s4, "get", idb) != null, "a journal line older than pets.yml was replayed");
        h.assertTrue(CT.call(s4, "get", ida) == null, "the released pet is back after the full save");
        for (Object s : List.of(s1, s2, s3, s4)) CT.call(s, "shutdown");
        h.succeed();
    }

    /**
     * A crash mid-character (a non-ASCII name) loses only its torn line, not the journal. A journal older than a
     * pets.yml without journal-seq (a version without the journal saved it, after a downgrade) is set aside, not
     * replayed over the newer records; one newer than it (a crash before the first journal-aware save) still replays.
     */
    @GameTest
    public void journalSurvivesTornCharactersAndStaleFiles(GameTestHelper h) {
        Path dir = scratch();
        Path yml = dir.resolve("pets.yml"), jr = dir.resolve("pets.journal");
        UUID owner = UUID.randomUUID();
        Object s1 = newStore(yml, jr);
        CT.call(s1, "load");
        Object a = newPet(owner, "ArcanaBlackCat", 1000L);
        UUID ida = PetsTests.petId(a);
        CT.call(s1, "add", a);
        CT.call(s1, "saveNow");
        String cat = "Miña 猫";
        CT.set(a, "name", cat);
        CT.call(s1, "journal", pets(a));
        byte[] torn = ("{seq: 2, op: put, id: " + ida + ", pet: {owner: " + owner + ", species: ArcanaBlackCat, name: \"猫")
                .getBytes(StandardCharsets.UTF_8);
        appendBytes(jr, java.util.Arrays.copyOf(torn, torn.length - 1));   // cut inside the last character's three bytes
        Object s2 = newStore(yml, jr);
        CT.call(s2, "load");
        Object a2 = CT.call(s2, "get", ida);
        h.assertValueEqual(String.valueOf(CT.get(a2, "name")), cat, "name replayed from before a line torn mid-character");
        h.assertValueEqual(((java.util.concurrent.atomic.AtomicLong) CT.get(s2, "seq")).get(), 1L, "seq after a line torn mid-character");
        CT.set(a2, "name", "After");
        CT.call(s2, "journal", pets(a2));
        Object s3 = newStore(yml, jr);
        CT.call(s3, "load");
        h.assertValueEqual(String.valueOf(CT.get(CT.call(s3, "get", ida), "name")), "After", "name after an append past a torn character");

        // a downgrade: a version without the journal saves pets.yml (no journal-seq) after the journal was written
        CT.call(s3, "saveNow");
        String newer = read(yml).replaceAll("(?m)^journal-seq: .*\\R", "");
        h.assertTrue(!newer.contains("journal-seq") && newer.contains("After"), "pets.yml as an old version writes it");
        String staleLine = "{seq: 1, op: put, id: " + ida + ", pet: {owner: " + owner + ", species: ArcanaBlackCat, name: Stale}}\n";
        writeFile(jr, staleLine);
        writeFile(yml, newer);
        setModified(yml, modified(jr) + 5000);
        Object s4 = newStore(yml, jr);
        CT.call(s4, "load");
        h.assertValueEqual(String.valueOf(CT.get(CT.call(s4, "get", ida), "name")), "After", "name after a stale journal");
        h.assertTrue(!Files.exists(jr), "the stale journal was left in place");
        try (var files = Files.list(dir)) {
            h.assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("pets.journal.stale-")), "the stale journal was not kept aside");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // a crash before the first journal-aware save: the journal is newer than pets.yml and replays
        writeFile(jr, staleLine);
        setModified(yml, modified(jr) - 5000);
        Object s5 = newStore(yml, jr);
        CT.call(s5, "load");
        h.assertValueEqual(String.valueOf(CT.get(CT.call(s5, "get", ida), "name")), "Stale", "name from a journal newer than pets.yml");
        for (Object s : List.of(s1, s2, s3, s4, s5)) CT.call(s, "shutdown");
        h.succeed();
    }

    static void appendBytes(Path f, byte[] bytes) {
        try {
            Files.write(f, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void writeFile(Path f, String text) {
        try {
            Files.writeString(f, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static long modified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void setModified(Path f, long millis) {
        try {
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(millis));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The background save writes pets.yml byte for byte as the old Yaml.set() save did, plus the journal-seq line. */
    @GameTest
    public void petsYmlKeepsItsFormat(GameTestHelper h) {
        Path dir = scratch();
        Path yml = dir.resolve("pets.yml"), jr = dir.resolve("pets.journal");
        Object st = newStore(yml, jr);
        CT.call(st, "load");
        UUID o1 = UUID.fromString("00000000-0000-0000-0000-00000000000b"), o2 = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        Object full = newPet(o1, "ArcanaBlackCat", 5000L);
        CT.set(full, "name", "Sir \"Fluff\": the 2nd");
        CT.set(full, "namePending", true);
        CT.set(full, "entity", UUID.randomUUID());
        CT.set(full, "xp", 120);
        CT.set(full, "level", 1);
        CT.set(full, "stay", true);
        CT.set(full, "health", 13.3333);
        CT.set(full, "maxHealth", 20.0);
        CT.set(full, "color", "#ff9ecb");
        CT.set(full, "freeReviveAt", 77L);
        CT.set(full, "variant", "ginger");
        CT.set(full, "shiny", true);
        CT.set(full, "breedReadyAt", 99L);
        CT.set(full, "bred", true);
        CT.set(full, "work", spot(null, "minecraft:overworld", "world", 1.234, 64, -5.555));
        CT.set(full, "workClock", 12345L);
        CT.set(full, "workBank", 600L);
        CT.set(full, "spot", spot(UUID.randomUUID(), "minecraft:the_nether", "world_nether", 0.5, 70.126, 3));
        @SuppressWarnings("unchecked") List<UUID> rep = (List<UUID>) CT.get(full, "replaced");
        rep.add(UUID.randomUUID());
        rep.add(UUID.randomUUID());
        Object fallen = newPet(o2, "ArcanaMossbun", 3000L);
        CT.set(fallen, "state", CT.get(fallen, "state").getClass().getEnumConstants()[1]);   // FALLEN
        CT.set(fallen, "diedAt", 4444L);
        CT.set(fallen, "deathUnseen", true);
        Object stabled = newPet(o2, "ArcanaMossbun", 1000L);
        CT.set(stabled, "state", CT.get(stabled, "state").getClass().getEnumConstants()[2]);   // STABLED
        CT.set(stabled, "stabledAt", 555L);
        CT.set(stabled, "spot", spot(null, null, "world", 10, 20, 30));
        for (Object p : List.of(full, fallen, stabled)) CT.call(st, "add", p);
        Object gone = newPet(o1, "ArcanaBlackCat", 9000L);
        CT.call(st, "add", gone);
        CT.call(st, "release", gone, true);
        CT.call(st, "saveNow");
        String now = read(yml);
        String old = oldFormat(st, List.of(full, fallen, stabled));
        String header = "# Feather pets. Edit only while the server is stopped.\n";
        h.assertTrue(now.startsWith(header + "journal-seq: "), "pets.yml starts with " + now.lines().limit(2).toList());
        String withoutSeq = now.replaceFirst("(?m)^journal-seq: \\d+\n", "");
        if (!withoutSeq.equals(old)) {
            net.axther.aftrhrs.AftrHrs.LOG.error("[bt-creatures] pets.yml format changed.\n--- old\n{}\n--- new\n{}", old, withoutSeq);
            throw h.assertionException(net.minecraft.network.chat.Component.literal("pets.yml differs from the old format (see the log)"));
        }
        CT.call(st, "shutdown");
        h.succeed();
    }

    /** What the old PetStore.save wrote for these records (dotted Yaml.set calls), for the format test. */
    private static String oldFormat(Object store, List<Object> pets) {
        Yaml yml = new Yaml();
        List<String> rel = new ArrayList<>();
        @SuppressWarnings("unchecked") java.util.Map<UUID, Long> released = (java.util.Map<UUID, Long>) CT.get(store, "released");
        for (var en : released.entrySet()) rel.add(en.getKey() + " " + en.getValue());
        yml.set("released", rel);
        List<Object> sorted = new ArrayList<>(pets);
        sorted.sort(java.util.Comparator.comparing((Object p) -> CT.get(p, "owner").toString()).thenComparingLong(p -> (long) CT.get(p, "created")));
        for (Object p : sorted) {
            String b = "pets." + CT.get(p, "id") + ".";
            yml.set(b + "owner", CT.get(p, "owner").toString());
            yml.set(b + "species", CT.get(p, "species"));
            yml.set(b + "name", CT.get(p, "name"));
            if ((boolean) CT.get(p, "namePending")) yml.set(b + "name-pending", true);
            Object ent = CT.get(p, "entity");
            yml.set(b + "entity", ent == null ? null : ent.toString());
            yml.set(b + "state", CT.get(p, "state").toString().toLowerCase(java.util.Locale.ROOT));
            yml.set(b + "bond-xp", CT.get(p, "xp"));
            yml.set(b + "bond-level", CT.get(p, "level"));
            yml.set(b + "stay", CT.get(p, "stay"));
            yml.set(b + "health", round((double) CT.get(p, "health")));
            yml.set(b + "max-health", round((double) CT.get(p, "maxHealth")));
            yml.set(b + "created", CT.get(p, "created"));
            if ((long) CT.get(p, "diedAt") > 0) yml.set(b + "died", CT.get(p, "diedAt"));
            if ((boolean) CT.get(p, "deathUnseen")) yml.set(b + "death-unseen", true);
            if (CT.get(p, "color") != null) yml.set(b + "color", CT.get(p, "color"));
            if ((long) CT.get(p, "freeReviveAt") > 0) yml.set(b + "free-revive", CT.get(p, "freeReviveAt"));
            if ((long) CT.get(p, "stabledAt") > 0) yml.set(b + "stabled", CT.get(p, "stabledAt"));
            @SuppressWarnings("unchecked") List<UUID> rep = (List<UUID>) CT.get(p, "replaced");
            if (!rep.isEmpty()) yml.set(b + "replaced", rep.stream().map(UUID::toString).toList());
            if (CT.get(p, "variant") != null) yml.set(b + "variant", CT.get(p, "variant"));
            if ((boolean) CT.get(p, "shiny")) yml.set(b + "shiny", true);
            if ((long) CT.get(p, "breedReadyAt") > 0) yml.set(b + "breed-ready", CT.get(p, "breedReadyAt"));
            if ((boolean) CT.get(p, "bred")) yml.set(b + "bred", true);
            Object work = CT.get(p, "work");
            if (work != null) {
                putSpot(yml, b + "work.", work);
                yml.set(b + "work.clock", CT.get(p, "workClock"));
                if ((long) CT.get(p, "workBank") > 0) yml.set(b + "work.bank", CT.get(p, "workBank"));
            }
            Object spot = CT.get(p, "spot");
            if (spot != null) putSpot(yml, b + "location.", spot);
        }
        return "# Feather pets. Edit only while the server is stopped.\n" + yml.saveToString();
    }

    private static void putSpot(Yaml yml, String b, Object s) {
        yml.set(b + "world", CT.call(s, "worldName"));
        yml.set(b + "world-key", CT.call(s, "worldKey"));
        Object wid = CT.call(s, "worldId");
        yml.set(b + "world-id", wid == null ? null : wid.toString());
        yml.set(b + "x", round((double) CT.call(s, "x")));
        yml.set(b + "y", round((double) CT.call(s, "y")));
        yml.set(b + "z", round((double) CT.call(s, "z")));
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
