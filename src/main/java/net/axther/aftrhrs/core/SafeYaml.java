package net.axther.aftrhrs.core;

import net.axther.aftrhrs.AftrHrs;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Crash-safe YAML data files (Feather's {@code SafeYaml} and {@code SafeFiles} in one): written to {@code <name>.tmp}
 * and moved over the real file (atomic where the file system can), with the previous good file kept as
 * {@code <name>.bak}. A file that can't be parsed is never overwritten: it is copied to
 * {@code <name>.broken-<timestamp>}, the {@code .bak} is tried instead, and when that fails too the caller gets
 * {@code null} and must not save until the file is fixed.
 */
public final class SafeYaml {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /**
     * Files this session wrote or read successfully: their size and modified time at that point (known-good YAML, no
     * need to parse again).
     */
    private static final Map<Path, long[]> WRITTEN = new ConcurrentHashMap<>();

    private SafeYaml() {
    }

    /**
     * Reads a data file. Missing: an empty configuration. Unparsable: logged, a {@code .broken-<timestamp>} copy is
     * kept, and the {@code .bak} is used if it parses; otherwise {@code null} (the caller marks the data unreadable).
     *
     * @param what what the file holds, for the log ("altar cooldowns and fights")
     */
    public static Yaml read(Path file, Logger log, String what) {
        return read(file, log, what, true);
    }

    /**
     * {@link #read(Path, Logger, String)}; with {@code alert} false a broken file is only logged, not shown to staff
     * ({@link DataAlerts}), for readers that pause nothing when it fails (another class owns and writes the file).
     */
    public static Yaml read(Path file, Logger log, String what, boolean alert) {
        if (!Files.exists(file)) {
            DataAlerts.fixed(file);
            return new Yaml();
        }
        long[] before = stamp(file);
        try {
            Yaml yml = Yaml.loadStrict(file);
            // the stamp from before the read: any change during or after it mismatches, so the next write parses again
            remember(file, before);
            DataAlerts.fixed(file);
            return yml;
        } catch (IOException | RuntimeException e) {
            WRITTEN.remove(file.toAbsolutePath());   // the file is bad: never trust an old stamp for it
            Path broken = keepBroken(file, log);
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            if (Files.exists(bak)) {
                try {
                    Yaml yml = Yaml.loadStrict(bak);
                    log.error("Could not read {}: {}. Loaded the backup {} instead{}.", file, e.toString(), bak.getFileName(),
                            broken != null ? "; the unreadable file was kept as " + broken.getFileName() : "");
                    DataAlerts.fixed(file);   // saving goes on (over the broken file, which was kept)
                    return yml;
                } catch (IOException | RuntimeException ignored) {
                    // fall through: nothing readable
                }
            }
            log.error("Could not read {}: {}. It is left untouched{}; {} are not saved until the file is fixed and Feather is reloaded.",
                    file, e.toString(), broken != null ? " (copy: " + broken.getFileName() + ")" : "", what);
            if (alert) DataAlerts.broken(file, what);
            return null;
        }
    }

    /** {@link #read} with Feather's logger and a generic description (Feather's {@code SafeFiles.read}). */
    public static Yaml read(Path file) {
        return read(file, Feather.get().getLogger(), "changes");
    }

    /** Whether the file is missing or parses. */
    public static boolean parses(Path file) {
        if (!Files.exists(file)) return true;
        try {
            Yaml.loadStrict(file);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Path keepBroken(Path file, Logger log) {
        Path copy = file.resolveSibling(file.getFileName() + ".broken-" + LocalDateTime.now().format(STAMP));
        try {
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            return copy;
        } catch (IOException e) {
            log.warn("Could not copy {} to {}: {}", file, copy.getFileName(), e.toString());
            return null;
        }
    }

    /** Writes the text through {@code <name>.tmp}, keeping the file it replaces as {@code <name>.bak}. Logs failures. */
    public static boolean write(Path file, String data, Logger log) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                // Only a good file becomes the backup (never a broken one over a good .bak). A file still exactly as we
                // last wrote or read it is good without parsing it again (some stores save on the main thread per change,
                // and the first save after a load would otherwise parse the whole file a second time).
                if (ourOwn(file) || parses(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                else keepBroken(file, log);
            }
            Yaml.SafeFiles.writeAtomic(file, data);
            remember(file);
            return true;
        } catch (IOException e) {
            log.error("Could not save {}: {}", file, e.toString());
            return false;
        }
    }

    private static boolean ourOwn(Path file) throws IOException {
        long[] w = WRITTEN.get(file.toAbsolutePath());
        return w != null && w[0] == Files.size(file) && w[1] == Files.getLastModifiedTime(file).toMillis();
    }

    /** {size, mtime-ms} of a regular file, or null. */
    private static long[] stamp(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            return new long[]{Files.size(file), Files.getLastModifiedTime(file).toMillis()};
        } catch (IOException e) {
            return null;
        }
    }

    private static void remember(Path file, long[] st) {
        Path key = file.toAbsolutePath();
        if (st == null) WRITTEN.remove(key);
        else WRITTEN.put(key, st);
    }

    private static void remember(Path file) {
        remember(file, stamp(file));
    }

    /** Saves a Yaml with {@link #write}. */
    public static boolean write(Path file, Yaml yml, Logger log) {
        return write(file, yml.saveToString(), log);
    }

    /** {@link #write} with Feather's logger. */
    public static boolean write(Path file, String data) {
        return write(file, data, Feather.get().getLogger());
    }

    // ------------------------------------------------------------------ the data thread

    /**
     * One daemon thread that turns {@link Yaml#snapshot snapshots} into text and writes them, in the order they were
     * handed over: an async save queued before a sync one is written first, so it can never overwrite it. It lives for
     * the JVM (singleplayer opens several worlds); {@link #drain} at server stop waits for what is queued.
     */
    private static final String DATA_THREAD = "AftrHrs-Data";
    private static final ExecutorService DATA_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, DATA_THREAD);
        t.setDaemon(true);
        return t;
    });

    /**
     * Dumps the snapshot (taken on the caller's thread with {@link Yaml#snapshot}) after {@code header} and writes it
     * with {@link #write}, on the data thread. The future says whether the write worked (never fails exceptionally).
     */
    public static CompletableFuture<Boolean> writeAsync(Path file, String header, Object snapshot, Logger log) {
        return queue(file, header, snapshot, log, new AtomicBoolean()).done();
    }

    /** A queued write: {@code started} is claimed by whoever goes first, the job (it writes) or a timed-out wait (it doesn't). */
    private record Queued(CompletableFuture<Boolean> done, AtomicBoolean started) {
    }

    private static Queued queue(Path file, String header, Object snapshot, Logger log, AtomicBoolean started) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Runnable job = () -> {
            if (!started.compareAndSet(false, true)) {   // a writeSync gave up on it: nothing may reach the file now
                done.complete(false);
                return;
            }
            boolean ok;
            try {
                ok = write(file, (header == null ? "" : header) + Yaml.dump(snapshot), log);
            } catch (RuntimeException e) {
                log.error("Could not save {}: {}", file, e.toString());
                ok = false;
            }
            done.complete(ok);
        };
        if (Thread.currentThread().getName().equals(DATA_THREAD)) {
            job.run();
            return new Queued(done, started);
        }
        try {
            DATA_IO.execute(job);
        } catch (RejectedExecutionException e) {
            job.run();
        }
        return new Queued(done, started);
    }

    /**
     * {@link #writeAsync} and waits for it: for saves that must be on disk before the caller goes on (an item leaves,
     * coins are paid). Queued behind the async saves already waiting, so writes to a file stay in order. When the job
     * has not started within 10 s it is cancelled and this returns false; once started it is waited for. So false
     * always means nothing of this save reached the file, and callers may roll back.
     */
    public static boolean writeSync(Path file, String header, Object snapshot, Logger log) {
        Queued q = queue(file, header, snapshot, log, new AtomicBoolean());
        try {
            return q.done().get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (q.started().compareAndSet(false, true)) {
                log.error("Saving {} did not start within 10 s; it was cancelled.", file);
                return false;
            }
            log.warn("Saving {} is taking longer than 10 s; waiting for it.", file);
            return awaitStarted(q.done(), file, log);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (q.started().compareAndSet(false, true)) {
                log.error("Interrupted while saving {}; the save was cancelled.", file);
                return false;
            }
            return awaitStarted(q.done(), file, log);
        } catch (ExecutionException e) {
            log.error("Could not save {}: {}", file, e.getCause() == null ? e.toString() : e.getCause().toString());
            return false;
        }
    }

    /** The real answer of a write that is already running (its future never fails exceptionally). */
    private static boolean awaitStarted(CompletableFuture<Boolean> done, Path file, Logger log) {
        try {
            return done.join();
        } catch (RuntimeException e) {
            log.error("Could not save {}: {}", file, e.toString());
            return false;
        }
    }

    /** Waits (at most {@code seconds}) until every save queued so far is written; server stop, after the modules saved. */
    public static void drain(long seconds) {
        if (Thread.currentThread().getName().equals(DATA_THREAD)) return;
        try {
            DATA_IO.submit(() -> {
            }).get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            AftrHrs.LOG.warn("Data saves still running after {} s at shutdown", seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RejectedExecutionException e) {
            AftrHrs.LOG.warn("Could not wait for the data saves: {}", e.toString());
        }
    }
}
