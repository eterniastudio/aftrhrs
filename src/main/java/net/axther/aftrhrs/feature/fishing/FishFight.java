package net.axther.aftrhrs.feature.fishing;

import java.util.Arrays;

/**
 * The reel-in fight for big fish: one deterministic simulation that the client plays (Angler's Journal mod screen,
 * {@code FightScreen}) and the server replays from the client's inputs, so the server alone decides the outcome.
 *
 * <p>Per tick (20 a second) the player either reels or lets the line run. Reeling raises the line <b>tension</b> (more
 * while the fish is making a run) and wins line (<b>progress</b>, less against a run); letting go lowers the tension
 * and the fish takes line back. Tension at {@link Params#lineStrength} snaps the line, progress at 0 means the fish got
 * away, progress at 1 lands it, and the clock running out ({@link Params#maxTicks}) lets it go. The fish rests and
 * makes runs on a schedule drawn from the fight's seed (xorshift, so both sides draw the same runs); its
 * {@link Params#strength} comes from the species' rarity and the weight, the rod helps with {@link Params#reelPower}
 * (Luck of the Sea, the rod's {@code fight} bonus) and {@link Params#lineStrength} (Unbreaking).
 *
 * <p>The client reports {@code ticks} (how long it played) and {@code toggles} (the ticks where it switched between
 * reeling and not, starting not reeling). {@link #judge} rejects what can't have happened (more ticks than the server
 * clock allows, toggles out of order or out of range, a fight that went on after it ended) and otherwise replays it.
 */
public final class FishFight {
    public enum Outcome { RUNNING, LANDED, SNAPPED, ESCAPED, TIMEOUT, INVALID }

    /** Everything the simulation needs; the server makes it and sends a copy to the client. */
    public record Params(long seed, float strength, float reelPower, float lineStrength, int maxTicks) {
    }

    public static final float START_TENSION = 0.2f, START_PROGRESS = 0.3f;

    /** One fight in progress. */
    public static final class Sim {
        public final Params params;
        public float tension = START_TENSION, progress = START_PROGRESS;
        public int tick;
        public int runTicks, restTicks, runAge;
        public Outcome outcome = Outcome.RUNNING;
        private long rng;

        public Sim(Params params) {
            this.params = params;
            this.rng = params.seed() == 0 ? 0x9E3779B97F4A7C15L : params.seed();
            this.restTicks = 12 + next(20);
        }

        private int next(int bound) {
            rng ^= rng << 13;
            rng ^= rng >>> 7;
            rng ^= rng << 17;
            return (int) Math.floorMod(rng, (long) bound);
        }

        public boolean running() {
            return runTicks > 0;
        }

        /** How hard the fish pulls this tick, 0..1: a third of its strength at rest, ramping to all of it in a run. */
        public float pull() {
            if (runTicks <= 0) return params.strength() * 0.35f;
            return params.strength() * (0.35f + 0.65f * Math.min(1f, runAge / 6f));
        }

        /** One tick. Returns the outcome after it (RUNNING while the fight goes on). */
        public Outcome step(boolean reeling) {
            if (outcome != Outcome.RUNNING) return outcome;
            float pull = pull();
            if (reeling) {
                tension += 0.026f + 0.045f * pull;
                progress += 0.0105f * params.reelPower() * (1f - 0.55f * pull);
            } else {
                tension -= 0.035f;
                progress -= 0.0065f * pull;
            }
            if (tension < 0) tension = 0;
            tick++;
            // the fish's schedule: rest, then a run, then rest again
            if (runTicks > 0) {
                runAge++;
                if (--runTicks == 0) restTicks = 15 + next(30);
            } else if (--restTicks <= 0) {
                runTicks = 8 + next(18);
                runAge = 0;
            }
            if (tension >= params.lineStrength()) outcome = Outcome.SNAPPED;
            else if (progress >= 1f) outcome = Outcome.LANDED;
            else if (progress <= 0f) outcome = Outcome.ESCAPED;
            else if (tick >= params.maxTicks()) outcome = Outcome.TIMEOUT;
            return outcome;
        }
    }

    private FishFight() {
    }

    /**
     * How hard a fish fights, 0.25..0.95: a base by rarity plus a little per tenfold weight (a 100 lb fish pulls harder
     * than a 1 lb one), a bit more for a trophy.
     */
    public static float strength(int rank, int grams, boolean trophy) {
        float[] base = {0.30f, 0.40f, 0.55f, 0.70f};
        float b = base[Math.max(0, Math.min(3, rank))];
        double w = Math.log10(Math.max(1, grams) / 1000.0);
        float s = (float) (b + 0.06 * w + (trophy ? 0.05 : 0));
        return Math.max(0.25f, Math.min(0.95f, s));
    }

    /** The outcome of replaying these inputs for {@code ticks} ticks (RUNNING when it hadn't ended by then). */
    public static Outcome replay(Params p, int ticks, int[] toggles) {
        Sim sim = new Sim(p);
        boolean reeling = false;
        int ti = 0;
        for (int t = 0; t < ticks; t++) {
            while (ti < toggles.length && toggles[ti] == t) {
                reeling = !reeling;
                ti++;
            }
            if (sim.step(reeling) != Outcome.RUNNING) {
                // the fight ended here: a report that runs on past its end didn't come from the real game
                return t == ticks - 1 ? sim.outcome : Outcome.INVALID;
            }
        }
        return sim.outcome;
    }

    /**
     * The server's judgement of a client's report. {@code elapsedMs}: server time since the fight started;
     * {@code slackMs}: latency allowance. INVALID for an impossible report (faster than real time, bad toggles, ticks
     * past the end); otherwise the replayed outcome, where a fight that hadn't ended (the player gave up) counts as
     * ESCAPED.
     */
    public static Outcome judge(Params p, int ticks, int[] toggles, long elapsedMs, long slackMs) {
        if (ticks < 1 || ticks > p.maxTicks() || toggles == null || toggles.length > ticks) return Outcome.INVALID;
        if ((long) ticks * 50L > elapsedMs + slackMs) return Outcome.INVALID;
        int prev = -1;
        for (int t : toggles) {
            if (t <= prev || t < 0 || t >= ticks) return Outcome.INVALID;
            prev = t;
        }
        Outcome o = replay(p, ticks, toggles);
        return o == Outcome.RUNNING ? Outcome.ESCAPED : o;
    }

    /** A reasonable player (reels below 70% tension, lets go above): for tuning and the game tests. */
    public static int[] autopilot(Params p, float high, float low) {
        Sim sim = new Sim(p);
        int[] out = new int[16];
        int n = 0;
        boolean reeling = false;
        while (sim.outcome == Outcome.RUNNING) {
            boolean want = reeling ? sim.tension < high : sim.tension < low;
            if (want != reeling) {
                if (n == out.length) out = Arrays.copyOf(out, n * 2);
                out[n++] = sim.tick;
                reeling = want;
            }
            sim.step(reeling);
        }
        return Arrays.copyOf(out, n);
    }

    /** How many ticks {@link #autopilot}'s fight takes (its end tick). */
    public static int length(Params p, int[] toggles) {
        Sim sim = new Sim(p);
        boolean reeling = false;
        int ti = 0;
        while (sim.outcome == Outcome.RUNNING) {
            while (ti < toggles.length && toggles[ti] == sim.tick) {
                reeling = !reeling;
                ti++;
            }
            sim.step(reeling);
        }
        return sim.tick;
    }
}
