"""Luna: 83 hand-made clips (biscuits, zoomies, chatter + pounce, loafs, sploots, belly traps ...) audited after the
rotation-sign fix, plus the shared cat body language (clips/_cats.py) played big and dramatic.

Fixes: knead_start (circling before the biscuits), zoomies and tail_chase finish whole turns of the root; they are
re-baked so the turn ends where it started (no full reverse spin on the blend into knead / idle). New: sitting wash
/ tail wrap / lookout / loaf loops (her loaf pose), three sleeping loops, mood tail flicks and quivers, ears pinned
flat when hurt, a head-rub `pet_sit` while staying, a dramatic triple-pronk bond level-up."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

TAGS = dict(C.TAGS)
PROCEDURAL = {'tail_sway': 8, 'ear_twitch': 1.4}


def style(m):
    return C.CatStyle(m, s=1.0, k=1.0, a=1.15, tail=1.2)


def clips(m):
    fixed = [C.unspin(m, n) for n in ('knead_start', 'zoomies', 'tail_chase') if m.has(n)]
    for c in fixed:
        m.put(c)
    st = style(m)
    out = C.common(st, sit_loaf_pose=m.pose('loaf', 2.5))
    extra = [C.happy(st), C.eat(st), C.tame(st), C.level_up(st, hops=3, twist=45)]
    for c in extra:
        m.put(c)
    return fixed + out + extra


def IDLE(m):
    return C.idle_tables(style(m), extra_standing=[
        {'clip': 'tail_flick', 'w': 1.5, 'cd': 10, 'when': 'owner_far'},      # dramatic: sulks when you leave
    ], playful=0.6)
