package net.axther.aftrhrs.gametest.soak;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-tick timing for the soak (registered only with {@code -Daftrhrs.soak=true}). A tick is measured from an early
 * START_SERVER_TICK phase to a late END_SERVER_TICK phase, so it includes every mod listener, the bots' connection
 * ticks, their brains, stats and saves.
 *
 * <p>JFR events (free without a recording; filter them with thresholds): {@code aftrhrs.soak.Tick} every tick (chunk
 * loads, generated, unloads, saved, tags), {@code aftrhrs.soak.LevelTick} for level ticks of 5 ms or more,
 * {@code aftrhrs.soak.Save} for every saveEverything, {@code aftrhrs.soak.Gap} for 20 ms or more between one tick's end
 * and the next one's start (the idle wait, plus server.execute tasks and chunk main-thread work run while waiting).
 *
 * <p>Chunk counts run from one tick's end to the next one's end, so a tick's loads, generated and unloads include the
 * gap before it: most async loads are promoted to FULL by main-thread chunk tasks run in that gap, and counting only
 * inside the tick would lose them. The Gap event also carries the gap's own counts.
 *
 * <p>With {@code -Daftrhrs.soak.tickLog=<ms>} every tick at or over that many ms, and every save tick, is appended to
 * {@code <server>/soak/ticks.csv}.
 */
final class SoakTicks {
    private SoakTicks() {
    }

    static final Identifier EARLY = Identifier.fromNamespaceAndPath("aftrhrs", "soak_early");
    static final Identifier LATE = Identifier.fromNamespaceAndPath("aftrhrs", "soak_late");
    /** ticks.csv threshold in ms (0: no CSV). */
    static final long LOG_MS = Long.getLong("aftrhrs.soak.tickLog", 0L);
    private static final int MAX_TAGS = 240;

    @Name("aftrhrs.soak.Tick")
    @Label("Soak server tick")
    @Category({"Aftr Hrs", "Soak"})
    @StackTrace(false)
    static final class TickEvent extends Event {
        @Label("Tick")
        long tick;
        @Label("Chunk loads")
        int loads;
        @Label("Generated")
        int generated;
        @Label("Unloads")
        int unloads;
        @Label("Saved")
        boolean saved;
        @Label("Tags")
        String tags;
    }

    @Name("aftrhrs.soak.LevelTick")
    @Label("Soak level tick")
    @Category({"Aftr Hrs", "Soak"})
    @StackTrace(false)
    static final class LevelTickEvent extends Event {
        @Label("Dimension")
        String dim;
        @Label("Tick")
        long tick;
    }

    @Name("aftrhrs.soak.Save")
    @Label("Soak saveEverything")
    @Category({"Aftr Hrs", "Soak"})
    @StackTrace(false)
    static final class SaveEvent extends Event {
        @Label("Flush")
        boolean flush;
        @Label("Force")
        boolean force;
        @Label("Tick")
        long tick;
    }

    @Name("aftrhrs.soak.Gap")
    @Label("Soak between ticks")
    @Category({"Aftr Hrs", "Soak"})
    @StackTrace(false)
    static final class GapEvent extends Event {
        @Label("Tick")
        long tick;
        @Label("Chunk loads")
        int loads;
        @Label("Generated")
        int generated;
        @Label("Unloads")
        int unloads;
        @Label("Tags of the tick before")
        String tags;
    }

    private static long tick;
    /** System.nanoTime() at the start of the current tick (early phase). */
    static long tickStart;
    private static long tickEnd;
    private static double lastGapMs;
    private static int loads, generated, unloads;
    private static boolean saved;
    private static final StringBuilder tags = new StringBuilder();
    private static final Map<String, Integer> loadsByDim = new TreeMap<>();
    private static final Map<ServerLevel, LevelTickEvent> levelEvents = new IdentityHashMap<>();
    private static final Map<ServerLevel, Long> levelStart = new IdentityHashMap<>();
    private static TickEvent current;
    private static GapEvent gap;
    private static SaveEvent save;
    private static Writer csv;

    static void register() {
        Identifier def = net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE;
        ServerTickEvents.START_SERVER_TICK.addPhaseOrdering(EARLY, def);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(def, LATE);
        ServerTickEvents.START_LEVEL_TICK.addPhaseOrdering(EARLY, def);
        ServerTickEvents.END_LEVEL_TICK.addPhaseOrdering(def, LATE);
        ServerTickEvents.START_SERVER_TICK.register(EARLY, s -> start());
        ServerTickEvents.END_SERVER_TICK.register(LATE, s -> end());
        ServerTickEvents.START_LEVEL_TICK.register(EARLY, l -> {
            LevelTickEvent e = new LevelTickEvent();
            e.begin();
            levelEvents.put(l, e);
            levelStart.put(l, System.nanoTime());
        });
        ServerTickEvents.END_LEVEL_TICK.register(LATE, l -> {
            LevelTickEvent e = levelEvents.remove(l);
            Long t0 = levelStart.remove(l);
            if (e == null || t0 == null) return;
            e.end();
            if (System.nanoTime() - t0 >= 5_000_000L) {
                e.dim = l.dimension().identifier().getPath();
                e.tick = tick;
                e.commit();
            }
        });
        ServerLifecycleEvents.BEFORE_SAVE.register((s, flush, force) -> {
            save = new SaveEvent();
            save.flush = flush;
            save.force = force;
            save.begin();
            saved = true;
        });
        ServerLifecycleEvents.AFTER_SAVE.register((s, flush, force) -> {
            if (save == null) return;
            save.end();
            save.tick = tick;
            save.commit();
            save = null;
        });
        ServerChunkEvents.CHUNK_LOAD.register((l, c, fresh) -> {
            loads++;
            if (fresh) generated++;
            loadsByDim.merge(l.dimension().identifier().getPath(), 1, Integer::sum);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((l, c) -> unloads++);
    }

    /** Opens ticks.csv (with -Daftrhrs.soak.tickLog) once the soak folder exists. */
    static void open(Path dir) {
        if (LOG_MS <= 0) return;
        try {
            Path f = dir.resolve("ticks.csv");
            boolean fresh = !Files.exists(f);
            csv = new BufferedWriter(Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            if (fresh) csv.write("time,tick,ms,gap_ms,loads,generated,unloads,saved,dims,tags\n");
            csv.flush();
            SoakDriver.log("tick log: ticks of " + LOG_MS + " ms or more and every save tick go to " + f);
        } catch (IOException e) {
            SoakDriver.problem("ticks.csv", e);
            csv = null;
        }
    }

    /** Server stopped: closes the CSV and forgets the levels. */
    static void close() {
        if (csv != null) {
            try {
                csv.close();
            } catch (IOException ignored) {
            }
            csv = null;
        }
        levelEvents.clear();
        levelStart.clear();
        current = null;
        gap = null;
        save = null;
    }

    /** Names a heavy action in the current tick ("save", "stats", "goto:Soak3:the_nether" ...). Server thread only. */
    static void tag(String t) {
        if (tags.length() >= MAX_TAGS) return;
        if (!tags.isEmpty()) tags.append(' ');
        tags.append(t);
    }

    /** The current tick's tags, space-separated. */
    static String tags() {
        return tags.toString();
    }

    /** Called at every tick start. */
    static void clearTags() {
        tags.setLength(0);
    }

    private static void start() {
        long now = System.nanoTime();
        if (gap != null) {
            gap.end();
            lastGapMs = (now - tickEnd) / 1e6;
            if (lastGapMs >= 20) {
                gap.tick = tick;
                gap.loads = loads;
                gap.generated = generated;
                gap.unloads = unloads;
                gap.commit();
            }
            gap = null;
        } else {
            resetChunkCounts(); // first tick: drop the world-load chunks
        }
        tick++;
        saved = false;
        clearTags();
        current = new TickEvent();
        current.begin();
        tickStart = System.nanoTime();
    }

    private static void end() {
        if (current == null) return;
        double ms = (System.nanoTime() - tickStart) / 1e6;
        String t = tags.toString();
        current.end();
        current.tick = tick;
        current.loads = loads;
        current.generated = generated;
        current.unloads = unloads;
        current.saved = saved;
        current.tags = t;
        current.commit();
        current = null;
        if (csv != null && (ms >= LOG_MS || saved)) {
            try {
                csv.write(String.format(Locale.ROOT, "%s,%d,%.1f,%.1f,%d,%d,%d,%b,\"%s\",\"%s\"%n", LocalTime.now().withNano(0), tick, ms,
                        lastGapMs, loads, generated, unloads, saved, loadsByDim, t.replace('"', '\'')));
                csv.flush();
            } catch (IOException e) {
                SoakDriver.problem("ticks.csv write", e);
                close();
            }
        }
        // Reset here, not at the start: loads finished in the coming gap count toward the next tick.
        resetChunkCounts();
        gap = new GapEvent();
        gap.tags = t;
        gap.begin();
        tickEnd = System.nanoTime();
    }

    private static void resetChunkCounts() {
        loads = generated = unloads = 0;
        loadsByDim.clear();
    }
}
