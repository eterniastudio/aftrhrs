"""Clover: 88 hand-made clips (lucky digs with a four-leaf clover in his teeth, the gentleman's bow, prim sits with his
tail round his toes, side-eyes, bib grooming, paperwork sits and page swats, butt wiggles, bug claps, meerkat stands,
creeper crab hops ...) plus the shared cat body language (clips/_cats.py) played as a lively young tuxedo: quick,
light on his feet, sits up very straight.

Source: Documents/Blockbench/models/arcana/work/clover/clover.py + clover_anims.py (the baked clips are already
turn-free, so nothing needs unspinning except the shared spin and paper_sit's single turn). New here: sitting loops with
his prim sit, three sleeping loops, a head-rub `pet_sit` while staying, a bond level-up that is two quick hops."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

TAGS = dict(C.TAGS, love_blink={'layer': 'base', 'move': False, 'p': 'sitting'},
            idle_paperwork={'p': 'sitting'}, idle_meadow={'p': 'lying'},
            idle_sleepy={'p': 'lying'}, idle_cold={'p': 'lying'}, idle_scared={'p': 'lying'})
PROCEDURAL = {'tail_sway': 8, 'ear_twitch': 1.3, 'breath': 0.015}


def style(m):
    return C.CatStyle(m, s=1.0, k=0.95, a=1.05, tail=1.15)


def clips(m):
    fixed = [C.unspin(m, n) for n in ('spin', 'paper_sit') if m.has(n)]
    for c in fixed:
        m.put(c)
    st = style(m)
    out = C.common(st, sit_loaf_pose=m.pose('idle_meadow', 0))
    extra = [C.happy(st), C.eat(st), C.tame(st), C.level_up(st, hops=2, twist=30)]
    for c in extra:
        m.put(c)
    return fixed + out + extra


def IDLE(m):
    return C.idle_tables(style(m), playful=0.8)
