package net.axther.aftrhrs.core;

import net.axther.aftrhrs.AftrHrs;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The Bukkit scheduler, on the server tick: {@link #later}, {@link #timer}, {@link #async} and {@link #sync}.
 * Everything not marked async runs on the server thread at the end of a tick.
 */
public final class Tasks {
    private static final List<Task> TASKS = new ArrayList<>();
    private static final List<Task> ADDED = new ArrayList<>();
    private static final ExecutorService ASYNC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "AftrHrs-Async");
        t.setDaemon(true);
        return t;
    });
    private static long tick;
    /**
     * False from server stop until the next server starts: tasks scheduled then (quit handlers run after the modules
     * stopped) are dropped instead of running in the next singleplayer world against the old server's objects.
     */
    private static volatile boolean accepting = true;

    private Tasks() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Tasks::tick);
        // registered before Feather's SERVER_STARTING listener, so modules can schedule in enable()
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(s -> accepting = true);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> cancelAll());
    }

    /** The server tick counter since start. */
    public static long now() {
        return tick;
    }

    public static Task later(long delayTicks, Runnable r) {
        return add(new Task(r, Math.max(1, delayTicks), -1));
    }

    public static Task timer(long delayTicks, long periodTicks, Runnable r) {
        return add(new Task(r, Math.max(1, delayTicks), Math.max(1, periodTicks)));
    }

    /**
     * Runs on the server thread after whatever is running now (Bukkit's {@code runTask}). From another thread it is
     * queued on the server; on the server thread it waits for the end of the tick, never inline: {@code server.execute}
     * would run it at once, inside the caller (a click being handled, a chunk future completed by the chunk system
     * in the middle of its own update - /rtp crashed the server that way).
     */
    public static void sync(Runnable r) {
        MinecraftServer s = Feather.serverOrNull();
        if (s == null) return;
        if (s.isSameThread()) later(1, r);
        else s.execute(r);
    }

    public static CompletableFuture<Void> async(Runnable r) {
        return CompletableFuture.runAsync(r, ASYNC);
    }

    public static <T> CompletableFuture<T> async(Supplier<T> s) {
        return CompletableFuture.supplyAsync(s, ASYNC);
    }

    public static ExecutorService asyncExecutor() {
        return ASYNC;
    }

    private static synchronized Task add(Task t) {
        if (!accepting) {
            t.cancelled = true;
            return t;
        }
        ADDED.add(t);
        return t;
    }

    /** Cancels every task (server stopping); until the next server starts, new tasks are dropped. */
    public static synchronized void cancelAll() {
        accepting = false;
        for (Task t : TASKS) t.cancelled = true;
        for (Task t : ADDED) t.cancelled = true;
        TASKS.clear();
        ADDED.clear();
    }

    private static void tick(MinecraftServer server) {
        tick++;
        synchronized (Tasks.class) {
            TASKS.addAll(ADDED);
            ADDED.clear();
        }
        // one pass (removeIf): removing one-shot tasks one by one through the iterator shifted the list each time,
        // quadratic when chunk loads queue hundreds of them at once
        List<Task> due = new ArrayList<>();
        TASKS.removeIf(t -> {
            if (t.cancelled) return true;
            if (--t.wait > 0) return false;
            due.add(t);
            if (t.period > 0) {
                t.wait = t.period;
                return false;
            }
            return true;
        });
        for (Task t : due) {
            if (t.cancelled) continue;
            try {
                t.run.run();
            } catch (Throwable e) {
                AftrHrs.LOG.error("Task failed", e);
            }
            if (t.period <= 0) t.cancelled = true;
        }
    }

    public static final class Task {
        private final Runnable run;
        private long wait;
        private final long period;
        private volatile boolean cancelled;

        private Task(Runnable run, long wait, long period) {
            this.run = run;
            this.wait = wait;
            this.period = period;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }
}
