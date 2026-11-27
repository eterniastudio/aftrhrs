#!/usr/bin/env python3
"""Where the server thread spends time in Aftr Hrs code, from a JFR recording (soak runs).

Usage: python tools/jfr_mod.py rec.jfr [--under <frame prefix>] | [--ticks N]
With --under: for samples through that frame, what it calls (next frame down) and the leaf frames;
with --under X --callers: who calls it (three frames up).
With --ticks N: the N slowest soak ticks (aftrhrs.soak.Tick events from gametest/soak/SoakTicks, measured from an early
START to a late END phase) with chunk loads/unloads, save flag and tags, plus the tick-time percentiles; restrict
other tools (Parks-style scripts) to those events' time ranges to attribute spikes.
Prints: share of server-thread samples with any net.axther frame; top mod entry points (outermost mod frame) and top
mod leaf frames (innermost mod frame), each with the share of all server-thread samples.
"""
import collections
import os
import re
import subprocess
import sys


TICK_EVENT = "aftrhrs.soak.Tick"


def duration_ms(text):
    """A jfr print duration ("52.3 ms", "1.02 s", "850 us", "12 ns") in milliseconds."""
    m = re.match(r"\s*([\d.]+)\s*(ns|us|ms|s|min|h)", text)
    if not m:
        return 0.0
    v = float(m.group(1))
    return v * {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1e3, "min": 6e4, "h": 3.6e6}[m.group(2)]


def ticks(rec, jh, n):
    out = subprocess.run([os.path.join(jh, "bin", "jfr"), "print", "--events", TICK_EVENT, rec],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    rows = []
    for ev in out.split(TICK_EVENT + " {")[1:]:
        f = {}
        for line in ev.splitlines():
            m = re.match(r"^\s+(\w+) = (.*)$", line)
            if m:
                f[m.group(1)] = m.group(2).strip()
        rows.append((duration_ms(f.get("duration", "0 ms")), f))
    if not rows:
        print("no " + TICK_EVENT + " events (a soak run with -Daftrhrs.soak=true, recorded with JFR?)")
        return
    ms = sorted(r[0] for r in rows)
    pct = lambda q: ms[min(len(ms) - 1, int(len(ms) * q))]
    print(f"{len(ms)} ticks: mean {sum(ms) / len(ms):.2f} ms, p50 {pct(0.5):.1f}, p95 {pct(0.95):.1f}, p99 {pct(0.99):.1f}, "
          f"max {ms[-1]:.1f} ms; over 50 ms: {sum(1 for v in ms if v >= 50)}")
    print(f"\n{n} slowest:")
    for d, f in sorted(rows, key=lambda r: -r[0])[:n]:
        print(f"  {d:8.1f} ms  tick {f.get('tick', '?'):>7}  {f.get('startTime', '')}  loads {f.get('loads', '?')}/gen "
              f"{f.get('generated', '?')}/unl {f.get('unloads', '?')}  saved {f.get('saved', '?')}  {f.get('tags', '')}")


def main():
    rec = sys.argv[1]
    jh = os.environ.get("JAVA_HOME", "C:/Users/kevin/.jdks/temurin-25.0.3")
    if "--ticks" in sys.argv:
        ticks(rec, jh, int(sys.argv[sys.argv.index("--ticks") + 1]))
        return
    under = sys.argv[sys.argv.index("--under") + 1] if "--under" in sys.argv else None
    callee = collections.Counter()
    leaf = collections.Counter()
    hits = 0
    out = subprocess.run([os.path.join(jh, "bin", "jfr"), "print", "--events", "jdk.ExecutionSample", "--stack-depth", "96", rec],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    events = out.split("jdk.ExecutionSample {")
    total = 0
    mod = 0
    outer = collections.Counter()
    inner = collections.Counter()
    frame_re = re.compile(r"^\s+([\w$.<>]+)\(")
    for ev in events[1:]:
        if 'sampledThread = "Server thread"' not in ev:
            continue
        total += 1
        frames = [m.group(1) for line in ev.splitlines() if (m := frame_re.match(line))]
        if under:
            idx = [i for i, f in enumerate(frames) if f.startswith(under)]
            if idx:
                hits += 1
                i = idx[-1]
                if "--callers" in sys.argv:
                    j = idx[0]
                    callee[" < ".join(frames[j + 1:j + 4])] += 1
                else:
                    callee[frames[i - 1] if i > 0 else "(self)"] += 1
                leaf[frames[0]] += 1
        modf = [f for f in frames if f.startswith("net.axther.")]
        if not modf:
            continue
        mod += 1
        inner[modf[0]] += 1
        outer[modf[-1]] += 1
    if total == 0:
        print("no server-thread samples")
        return
    if under:
        print(f"{under}: {hits} samples ({100 * hits / max(1, total):.1f}% of server thread)")
        print("calls:")
        for f, n in callee.most_common(20):
            print(f"  {100 * n / max(1, total):5.2f}%  {f}")
        print("leaves:")
        for f, n in leaf.most_common(20):
            print(f"  {100 * n / max(1, total):5.2f}%  {f}")
        return
    print(f"server-thread samples: {total}; with Aftr Hrs frames: {mod} ({100 * mod / total:.1f}%)")
    print("\ntop entry points (outermost mod frame):")
    for f, n in outer.most_common(25):
        print(f"  {100 * n / total:5.1f}%  {f}")
    print("\ntop leaf mod frames (innermost mod frame):")
    for f, n in inner.most_common(25):
        print(f"  {100 * n / total:5.1f}%  {f}")


if __name__ == "__main__":
    main()
