package net.axther.aftrhrs.gametest.soak;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Soak / persistence driver (dev only; inert unless {@code -Daftrhrs.soak=true}, see the {@code runSoak} task).
 * Console command {@code /soak}: {@code gen <dim> <radius>} pre-generates, {@code bots <n>} logs in headless players,
 * {@code run <minutes>} runs the chaos loop (bots wander and act, fast days, weather, autosaves, stats), {@code stats},
 * {@code gc}, {@code probe} (collection sizes in the mod's static/module state), {@code fixture}, {@code snap <name>},
 * {@code quitall}, {@code summonall}. Output: {@code <server>/soak/} (stats.csv, problems.log, probe.log, snapshots).
 */
public class SoakDriver implements ModInitializer {
    static final boolean ON = Boolean.getBoolean("aftrhrs.soak");
    /** -Daftrhrs.soak.gc=true: a forced System.gc() before every 5-minute stats pass (off: it stalls that tick). */
    static final boolean FORCE_GC = Boolean.getBoolean("aftrhrs.soak.gc");
    private static MinecraftServer server;
    static Path out;
    static final List<SoakBot> bots = new ArrayList<>();
    static final Map<SoakBot, SoakBrain> brains = new HashMap<>();
    static final AtomicInteger problems = new AtomicInteger();

    // chaos loop
    private static long runUntil;
    private static int runTicks;
    private static final long[] window = new long[600];
    private static int windowN;
    private static long worstTick;
    private static long driverNanos;
    private static long statsAt;
    private static long gcAt;
    private static long weatherAt;
    private static long saveAt;
    private static long probeAt;
    private static final java.util.Random rnd = new java.util.Random(7);

    // pre-generation
    private record Gen(ServerLevel level, Deque<ChunkPos> todo, int total, AtomicInteger done, AtomicInteger inflight, long started) {
    }

    private static final Deque<Gen> gens = new ArrayDeque<>();

    static MinecraftServer server() {
        return server;
    }

    @Override
    public void onInitialize() {
        if (!ON) return;
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            out = s.getServerDirectory().resolve("soak");
            try {
                Files.createDirectories(out);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            SoakTicks.open(out);
            log("soak driver ready (pid " + ProcessHandle.current().pid() + ")");
            String plan = System.getProperty("aftrhrs.soak.plan", "");
            for (String step : plan.split(";")) if (!step.isBlank()) run(s.createCommandSourceStack(), step.trim());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            log("server stopping; " + problems.get() + " problems so far");
            runUntil = 0;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            SoakTicks.close();
            bots.clear();
            brains.clear();
            gens.clear();
            server = null;
        });
        // The tick starts in SoakTicks' early START phase and is sampled in the late END phase, after every listener
        // (this driver's bots, brains, stats and saves included).
        SoakTicks.register();
        ServerTickEvents.END_SERVER_TICK.register(SoakDriver::tick);
        ServerTickEvents.END_SERVER_TICK.register(SoakTicks.LATE, SoakDriver::sample);
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> d.register(Commands.literal("soak")
                .requires(src -> src.getEntity() == null)
                .then(Commands.argument("args", StringArgumentType.greedyString()).executes(c -> {
                    run(c.getSource(), StringArgumentType.getString(c, "args"));
                    return 1;
                }))));
    }

    static void run(CommandSourceStack src, String line) {
        String[] a = line.split(" ");
        try {
            switch (a[0]) {
                case "gen" -> gen(level(a[1]), Integer.parseInt(a[2]));
                case "bots" -> bots(Integer.parseInt(a[1]));
                case "run" -> {
                    runUntil = System.currentTimeMillis() + (long) (Double.parseDouble(a[1]) * 60_000);
                    runTicks = 0;
                    long now = System.currentTimeMillis();
                    statsAt = gcAt = weatherAt = probeAt = now;
                    saveAt = now + 15_000; // save-all never shares a tick with stats
                    log("chaos loop for " + a[1] + " min");
                }
                case "halt" -> runUntil = 0;
                case "stats" -> stats(true);
                case "gc" -> {
                    SoakTicks.tag("gc");
                    System.gc(); // asked for by hand: force it
                    stats(true);
                }
                case "probe" -> SoakProbe.probe("manual");
                case "quitall" -> bots.forEach(SoakBot::quit);
                case "summonall" -> SoakPersist.summonAll(server, a.length > 1 ? Integer.parseInt(a[1]) : 1);
                case "fixture" -> SoakPersist.fixture(server);
                case "names" -> SoakPersist.names(server);
                case "snap" -> SoakPersist.snapshot(server, a[1]);
                case "save" -> {
                    SoakTicks.tag("save");
                    server.saveEverything(false, true, true);
                }
                case "loadall" -> loadAll();
                default -> log("unknown /soak " + line);
            }
        } catch (Exception e) {
            problem("/soak " + line, e);
        }
    }

    static ServerLevel level(String dim) {
        return switch (dim) {
            case "nether" -> server.getLevel(Level.NETHER);
            case "end" -> server.getLevel(Level.END);
            default -> server.overworld();
        };
    }

    // ------------------------------------------------------------------ pre-generation

    private static void gen(ServerLevel level, int radiusBlocks) {
        int r = radiusBlocks >> 4;
        Deque<ChunkPos> todo = new ArrayDeque<>();
        // spiral out from 0,0 so the spawn area is ready first
        for (int ring = 0; ring <= r; ring++) {
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == ring) todo.add(new ChunkPos(x, z));
                }
            }
        }
        gens.add(new Gen(level, todo, todo.size(), new AtomicInteger(), new AtomicInteger(), System.currentTimeMillis()));
        log("gen " + level.dimension().identifier() + ": " + todo.size() + " chunks");
    }

    private static void tickGen() {
        Gen g = gens.peek();
        if (g == null) return;
        while (g.inflight.get() < 48 && !g.todo.isEmpty()) {
            ChunkPos pos = g.todo.poll();
            g.inflight.incrementAndGet();
            CompletableFuture<?> f = g.level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED, pos, 0);
            f.whenComplete((v, t) -> server.execute(() -> {
                // keep it loaded a few seconds: entities load after the chunk (and converters run on entity load)
                net.axther.aftrhrs.core.Tasks.later(100, () -> g.level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 0));
                g.inflight.decrementAndGet();
                int d = g.done.incrementAndGet();
                if (t != null) problem("gen " + pos, t);
                if (d % 1000 == 0 || d == g.total) {
                    long s = Math.max(1, (System.currentTimeMillis() - g.started) / 1000);
                    log("gen " + g.level.dimension().identifier() + " " + d + "/" + g.total + " (" + d / s + " chunks/s)");
                }
            }));
        }
        if (g.todo.isEmpty() && g.inflight.get() == 0) gens.poll();
    }

    /** Every chunk that exists on disk (region or entities file), in every dimension, loaded for a while: migration runs. */
    private static void loadAll() throws IOException {
        Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        int queued = 0;
        for (ServerLevel l : server.getAllLevels()) {
            var id = l.dimension().identifier();
            Path dim = root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
            java.util.Set<Long> found = new java.util.LinkedHashSet<>();
            for (String sub : new String[]{"region", "entities"}) {
                Path dir = dim.resolve(sub);
                if (!Files.isDirectory(dir)) continue;
                try (var files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String n = f.getFileName().toString();
                        String[] p = n.split("\\.");
                        if (p.length != 4 || !p[3].equals("mca") || Files.size(f) < 8192) continue;
                        int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
                        byte[] head = new byte[4096];
                        try (var in = Files.newInputStream(f)) {
                            if (in.readNBytes(head, 0, 4096) < 4096) continue;
                        }
                        for (int i = 0; i < 1024; i++) {
                            int off = ((head[i * 4] & 0xff) << 16) | ((head[i * 4 + 1] & 0xff) << 8) | (head[i * 4 + 2] & 0xff);
                            if (off != 0) found.add(ChunkPos.pack(rx * 32 + (i & 31), rz * 32 + (i >> 5)));
                        }
                    }
                }
            }
            Deque<ChunkPos> todo = new ArrayDeque<>();
            for (long k : found) todo.add(ChunkPos.unpack(k));
            if (todo.isEmpty()) continue;
            gens.add(new Gen(l, todo, todo.size(), new AtomicInteger(), new AtomicInteger(), System.currentTimeMillis()));
            log("loadall " + id + ": " + todo.size() + " chunks on disk");
            queued++;
        }
        log("loadall queued " + queued + " dimensions");
    }

    static boolean generating() {
        return !gens.isEmpty();
    }

    // ------------------------------------------------------------------ bots

    private static void bots(int n) {
        for (int i = bots.size(); i < n; i++) {
            SoakBot b = new SoakBot("Soak" + (i + 1));
            bots.add(b);
            brains.put(b, new SoakBrain(b, i, rnd.nextLong()));
            b.login(server);
        }
        log(bots.size() + " bots");
    }

    // ------------------------------------------------------------------ tick

    /** Late END phase: the whole tick, from SoakTicks' early START phase. */
    private static void sample(MinecraftServer s) {
        if (runUntil <= 0) return;
        long dt = System.nanoTime() - SoakTicks.tickStart;
        window[windowN++ % window.length] = dt;
        worstTick = Math.max(worstTick, dt);
    }

    private static void tick(MinecraftServer s) {
        long t0 = System.nanoTime();
        try {
            tickGen();
            boolean chaos = runUntil > System.currentTimeMillis();
            for (SoakBot b : bots) {
                b.tick(s);
                SoakBrain brain = brains.get(b);
                if (brain != null) brain.tick(s, chaos);
                b.endTick();
            }
            if (chaos) chaos(s);
            else if (runUntil > 0) {
                runUntil = 0;
                stats(true);
                SoakProbe.probe("end of run");
                log("chaos loop done after " + runTicks + " ticks");
            }
        } catch (Exception e) {
            problem("driver tick", e);
        }
        driverNanos += System.nanoTime() - t0;
    }

    private static void chaos(MinecraftServer s) {
        runTicks++;
        // 20x day speed: a day every minute
        ServerLevel ow = s.overworld();
        ow.dimensionType().defaultClock().ifPresent(c -> s.clockManager().addTicks(c, 19));
        long ms = System.currentTimeMillis();
        if (ms >= weatherAt) {
            weatherAt = ms + 60_000 + rnd.nextInt(120_000);
            String[] w = {"clear", "rain", "thunder"};
            command(s, "weather " + w[rnd.nextInt(3)]);
        }
        if (ms >= saveAt) {
            saveAt = ms + 90_000;
            SoakTicks.tag("save");
            command(s, "save-all");
        }
        if (ms >= statsAt) {
            statsAt = ms + 30_000;
            boolean gc = ms >= gcAt;
            if (gc) gcAt = ms + 300_000;
            stats(gc);
        }
        if (ms >= probeAt) {
            probeAt = ms + 300_000;
            SoakTicks.tag("probe");
            SoakProbe.probe("t+" + runTicks);
        }
    }

    static void command(MinecraftServer s, String cmd) {
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd);
    }

    // ------------------------------------------------------------------ stats

    private static boolean header;

    /**
     * Old-generation heap in use right after the last collection that covered it (MemoryPoolMXBean collection usage of
     * the "Old Gen" pool; without one, the largest heap pool's), in bytes; -1 when the JVM reports none. Unlike the
     * current used heap it doesn't swing with young-gen garbage, so it shows a leak without forcing a GC.
     */
    static long heapAfterGc() {
        long best = -1;
        for (MemoryPoolMXBean b : ManagementFactory.getMemoryPoolMXBeans()) {
            if (b.getType() != MemoryType.HEAP) continue;
            MemoryUsage u = b.getCollectionUsage();
            if (u == null) continue;
            if (b.getName().contains("Old Gen")) return u.getUsed();
            best = Math.max(best, u.getUsed());
        }
        return best;
    }

    static void stats(boolean gc) {
        if (server == null) return;
        SoakTicks.tag("stats");
        int n = Math.min(windowN, window.length);
        long[] w = Arrays.copyOf(window, n);
        Arrays.sort(w);
        double avg = n == 0 ? 0 : Arrays.stream(w).average().orElse(0) / 1e6;
        double p95 = n == 0 ? 0 : w[(int) (n * 0.95)] / 1e6;
        double p99 = n == 0 ? 0 : w[Math.min(n - 1, (int) (n * 0.99))] / 1e6;
        double max = worstTick / 1e6;
        long vanillaMax = Arrays.stream(server.getTickTimesNanos()).max().orElse(0);
        worstTick = 0;
        windowN = 0;
        if (gc && FORCE_GC) {
            SoakTicks.tag("gc");
            System.gc();
        }
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long afterGc = heapAfterGc();
        StringBuilder dims = new StringBuilder();
        int arcana = 0, entities = 0, chunks = 0;
        Map<String, Integer> byType = new TreeMap<>();
        for (ServerLevel l : server.getAllLevels()) {
            int e = 0;
            for (Entity en : l.getAllEntities()) {
                e++;
                var key = BuiltInRegistries.ENTITY_TYPE.getKey(en.getType());
                if (key.getNamespace().equals(AftrHrs.ID)) {
                    arcana++;
                    byType.merge(key.getPath(), 1, Integer::sum);
                }
            }
            entities += e;
            chunks += l.getChunkSource().getLoadedChunksCount();
            dims.append(l.dimension().identifier().getPath()).append('=').append(l.getChunkSource().getLoadedChunksCount()).append('/').append(e).append(' ');
        }
        int online = (int) bots.stream().filter(SoakBot::connected).count();
        String line = String.format(java.util.Locale.ROOT, "%s,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%s,%d,%d,%d,%d,%d,%.2f",
                LocalTime.now().withNano(0), runTicks, avg, p95, p99, max, vanillaMax / 1e6, heap >> 20, afterGc < 0 ? -1 : afterGc >> 20,
                gc ? (FORCE_GC ? "gc" : "pass") : "", chunks, entities, arcana, online, problems.get(), driverNanos / 1e6 / Math.max(1, n));
        driverNanos = 0;
        try {
            Path f = out.resolve("stats.csv");
            if (!header && Files.exists(f) && !Files.readAllLines(f).stream().findFirst().orElse("").contains("heap_after_gc_mb")) {
                // a stats.csv from before heap_after_gc_mb: keep it aside instead of mixing column layouts
                Files.move(f, out.resolve("stats-old-" + System.currentTimeMillis() + ".csv"));
            }
            if (!header && !Files.exists(f)) {
                Files.writeString(f, "time,ticks,avg_ms,p95_ms,p99_ms,max_ms,vanilla_max100_ms,heap_mb,heap_after_gc_mb,gc,chunks,entities,arcana,bots_online,problems,driver_ms_per_tick\n");
            }
            header = true;
            Files.writeString(f, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (gc) Files.writeString(out.resolve("types.log"), LocalTime.now().withNano(0) + " " + dims + byType + " tickets " + tickets() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AftrHrs.LOG.warn("soak stats", e);
        }
        log("stats " + line + " | " + dims);
    }

    /** Chunk tickets by type per level (a mod leaking tickets keeps chunks loaded forever). */
    static String tickets() {
        StringBuilder sb = new StringBuilder();
        for (ServerLevel l : server.getAllLevels()) {
            Map<String, Integer> n = new TreeMap<>();
            try {
                var f = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("ticketStorage");
                f.setAccessible(true);
                Object ts = f.get(l.getChunkSource());
                var tf = net.minecraft.world.level.TicketStorage.class.getDeclaredField("tickets");
                tf.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<net.minecraft.server.level.Ticket>>) tf.get(ts);
                for (var list : map.values()) {
                    for (var t : list) {
                        var key = BuiltInRegistries.TICKET_TYPE.getKey(t.getType());
                        n.merge(key == null ? "?" : key.getPath(), 1, Integer::sum);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                n.put("error " + e, 1);
            }
            sb.append(l.dimension().identifier().getPath()).append(n).append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ logging

    static void log(String s) {
        AftrHrs.LOG.info("[soak] {}", s);
    }

    static void problem(String what) {
        problem(what, null);
    }

    static synchronized void problem(String what, Throwable t) {
        problems.incrementAndGet();
        AftrHrs.LOG.error("[soak] PROBLEM {}", what, t);
        if (out == null) return;
        StringWriter sw = new StringWriter();
        sw.append(LocalTime.now().withNano(0).toString()).append(' ').append(what).append('\n');
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        try {
            Files.writeString(out.resolve("problems.log"), sw.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    static void feedback(CommandSourceStack src, String s) {
        src.sendSystemMessage(Component.literal(s));
    }

    static ResourceKey<Level> randomDim(java.util.Random r) {
        int i = r.nextInt(10);
        return i < 6 ? Level.OVERWORLD : i < 8 ? Level.NETHER : Level.END;
    }
}
