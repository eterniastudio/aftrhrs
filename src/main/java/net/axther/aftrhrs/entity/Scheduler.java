package net.axther.aftrhrs.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-creature timeline: delayed steps (MythicMobs {@code delay N} inside a skill) and named cooldowns (a metaskill's
 * {@code Cooldown:}). Ticked by the creature on the server; nothing here is saved (as with MythicMobs, a restart drops
 * running sequences, and creatures restore their held poses on load).
 */
public final class Scheduler {
    private record Task(long at, Runnable run) {
    }

    private final List<Task> tasks = new ArrayList<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private long now;

    /** Runs {@code r} after {@code ticks} ticks (0 = on the next tick). */
    public void after(int ticks, Runnable r) {
        tasks.add(new Task(now + Math.max(0, ticks), r));
    }

    /** A sequence of steps with waits between them: {@code seq().run(a).wait(10).run(b).start()}. */
    public Seq seq() {
        return new Seq(this);
    }

    /** True (and starts the cooldown) if {@code key} is off cooldown. {@code ticks} &lt;= 0 never blocks. */
    public boolean ready(String key, int ticks) {
        Long until = cooldowns.get(key);
        if (until != null && until > now) return false;
        if (ticks > 0) cooldowns.put(key, now + ticks);
        return true;
    }

    public boolean onCooldown(String key) {
        Long until = cooldowns.get(key);
        return until != null && until > now;
    }

    public void clearCooldown(String key) {
        cooldowns.remove(key);
    }

    public long now() {
        return now;
    }

    public void tick() {
        now++;
        if (tasks.isEmpty()) return;
        List<Task> due = null;
        for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.at <= now) {
                if (due == null) due = new ArrayList<>();
                due.add(t);
                it.remove();
            }
        }
        if (due != null) for (Task t : due) t.run.run();
    }

    public void clear() {
        tasks.clear();
    }

    public static final class Seq {
        private final Scheduler s;
        private int at;
        private final List<Task> steps = new ArrayList<>();

        Seq(Scheduler s) {
            this.s = s;
        }

        public Seq run(Runnable r) {
            steps.add(new Task(at, r));
            return this;
        }

        public Seq wait(int ticks) {
            at += Math.max(0, ticks);
            return this;
        }

        public void start() {
            for (Task t : steps) {
                if (t.at == 0) t.run.run();
                else s.after((int) t.at, t.run);
            }
        }
    }
}
