#!/usr/bin/env python3
"""Compare two soak snapshots (run-soak/soak/snap-<a>, snap-<b>; written by /soak snap, gametest/soak/SoakPersist).

Usage: python tools/soak_diff.py run-soak/soak/snap-before run-soak/soak/snap-after [--yaml]
Reports data files that vanished / changed (with a YAML-aware diff of the changed keys when --yaml), every Arcana
entity that vanished or whose saved state changed (owner, bond, name, variant...), platform blocks / block entities
that changed, and player files that changed. Exit code 1 when anything was lost.
"""
import difflib
import re
import sys
from pathlib import Path


def lines(p):
    f = Path(p)
    return f.read_text(encoding="utf-8").splitlines() if f.exists() else []


def kv(path, key_fn):
    out = {}
    for line in lines(path):
        if line.strip():
            k, v = key_fn(line)
            out[k] = v
    return out


def flat_yaml(p):
    try:
        import yaml
    except ImportError:
        return None
    try:
        d = yaml.safe_load(Path(p).read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        return {"<unparsable>": str(e)}
    out = {}

    def walk(prefix, v):
        if isinstance(v, dict):
            for k, x in v.items():
                walk(f"{prefix}.{k}" if prefix else str(k), x)
        else:
            out[prefix] = v

    walk("", d)
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    a, b = Path(sys.argv[1]), Path(sys.argv[2])
    show_yaml = "--yaml" in sys.argv
    lost = 0
    # data files
    fa = kv(a / "files.txt", lambda l: (l.split(" ")[0], l.split(" ", 1)[1]))
    fb = kv(b / "files.txt", lambda l: (l.split(" ")[0], l.split(" ", 1)[1]))
    gone = sorted(set(fa) - set(fb))
    new = sorted(set(fb) - set(fa))
    changed = sorted(k for k in set(fa) & set(fb) if fa[k].split(" ")[1] != fb[k].split(" ")[1])
    print(f"== data files: {len(fa)} -> {len(fb)}; vanished {len(gone)}, new {len(new)}, changed {len(changed)}")
    for k in gone:
        print("  VANISHED", k)
        lost += 1
    for k in new:
        print("  new", k)
    for k in changed:
        print("  changed", k, fa[k], "->", fb[k])
        if show_yaml and k.endswith(".yml"):
            ya, yb = flat_yaml(a / "data" / k), flat_yaml(b / "data" / k)
            if ya is not None and yb is not None:
                for key in sorted(set(ya) | set(yb)):
                    if ya.get(key) != yb.get(key):
                        print(f"      {key}: {str(ya.get(key))[:100]!r} -> {str(yb.get(key))[:100]!r}")
    # entities
    ea = kv(a / "entities.txt", lambda l: (l.split(" ")[0], l.split(" ", 1)[1]))
    eb = kv(b / "entities.txt", lambda l: (l.split(" ")[0], l.split(" ", 1)[1]))
    egone = sorted(set(ea) - set(eb))
    print(f"== Arcana entities: {len(ea)} -> {len(eb)}; vanished {len(egone)}, new {len(set(eb) - set(ea))}")
    summary = re.compile(r"^(\S+) (\S+) (.*?) ?pos=(\S+) nbt=(.*)$")
    for k in egone:
        m = summary.match(ea[k])
        tame = "tame=true" in ea[k]
        print("  VANISHED" if tame else "  vanished", k, ea[k][:160])
        if tame:
            lost += 1
    nchanged = 0
    for k in sorted(set(ea) & set(eb)):
        ma, mb = summary.match(ea[k]), summary.match(eb[k])
        if not ma or not mb:
            continue
        if ma.group(3) != mb.group(3):
            print("  STATE", k, ma.group(1), ":", ma.group(3), "->", mb.group(3))
            lost += 1
        if ma.group(5) != mb.group(5):
            nchanged += 1
            if nchanged <= 15:
                da, db = ma.group(5), mb.group(5)
                sm = difflib.SequenceMatcher(None, da, db)
                diffs = [f"{da[i1:i2][:60]!r}->{db[j1:j2][:60]!r}" for tag, i1, i2, j1, j2 in sm.get_opcodes() if tag != "equal"]
                print("  nbt", k, ma.group(1), "; ".join(diffs[:4]))
    print(f"  ({nchanged} entities with other NBT changes)")
    # blocks
    # "x, y, z state..." (BlockPos.toShortString has spaces)
    bkey = lambda l: (" ".join(l.split(" ")[:3]), l.split(" ", 3)[3])
    ba = kv(a / "blocks.txt", bkey)
    bb = kv(b / "blocks.txt", bkey)
    bgone = sorted(set(ba) - set(bb))
    bch = sorted(k for k in set(ba) & set(bb) if ba[k] != bb[k])
    print(f"== platform blocks: {len(ba)} -> {len(bb)}; vanished {len(bgone)}, changed {len(bch)}")
    for k in bgone[:30]:
        print("  VANISHED", k, ba[k][:140])
        lost += 1
    for k in bch[:30]:
        print("  CHANGED", k, ba[k][:200], "\n        ->", bb[k][:200])
        lost += 1
    # players
    pa = kv(a / "players.txt", lambda l: (l.split(" ")[0], l.split(" ", 1)[1]))
    pb = kv(b / "players.txt", lambda l: (l.split(" ")[0], l.split(" ", 1)[1]))
    print(f"== player files: {len(pa)} -> {len(pb)}")
    for k in sorted(set(pa) - set(pb)):
        print("  VANISHED", k)
        lost += 1
    for k in sorted(set(pa) & set(pb)):
        if pa[k] != pb[k]:
            sm = difflib.SequenceMatcher(None, pa[k], pb[k], autojunk=False)
            diffs = [f"{pa[k][max(0, i1 - 30):i2][:90]!r}->{pb[k][max(0, j1 - 30):j2][:90]!r}" for tag, i1, i2, j1, j2 in sm.get_opcodes() if tag != "equal"]
            print("  changed", k, "; ".join(diffs[:5]))
        if "UNREADABLE" in pb[k]:
            lost += 1
    print("LOST/CORRUPT:", lost)
    sys.exit(1 if lost else 0)


if __name__ == "__main__":
    main()
