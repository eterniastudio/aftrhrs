"""Olive: 83 hand-made clips (Purr Therapy belly-up flops, lying belly-up on the bath mat, the wall-eyed derp stare,
tub loafs, mlems, head tilts, kneading, wriggle-rolls, tail chases, zoomies that end in a flop, the belly trap ...)
plus the shared cat body language (clips/_cats.py) played as a relaxed, bouncy young tom: a touch quicker and bigger
than the tabby, with a soft belly pouch that wobbles a little.

Source: Documents/Blockbench/models/arcana/work/olive/olive.py + olive_anims.py (the baked clips are turn-free except
the shared spin and tail_chase's two full turns, which are unspun here). New here: sitting loops with his tub loaf,
three sleeping loops, a head-rub `pet_sit` while staying, a bond level-up with two happy hops."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

TAGS = dict(C.TAGS, love_blink={'layer': 'base', 'move': False, 'p': 'sitting'},
            idle_bathmat={'p': 'belly'},
            idle_sleepy={'p': 'lying'}, idle_cold={'p': 'lying'}, idle_scared={'p': 'lying'})
PROCEDURAL = {'tail_sway': 7, 'ear_twitch': 1.0, 'breath': 0.02}


def style(m):
    return C.CatStyle(m, s=1.0, k=0.96, a=1.05, tail=1.0, chonk=0.4)


def clips(m):
    fixed = [C.unspin(m, n) for n in ('spin', 'tail_chase') if m.has(n)]
    for c in fixed:
        m.put(c)
    st = style(m)
    out = C.common(st, sit_loaf_pose=m.pose('tub_loaf', 2.0))
    extra = [C.happy(st), C.eat(st), C.tame(st), C.level_up(st, hops=2, twist=25)]
    for c in extra:
        m.put(c)
    return fixed + out + extra


def IDLE(m):
    return C.idle_tables(style(m), lazy=1.3, playful=0.9)
