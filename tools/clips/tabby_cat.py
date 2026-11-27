"""Tabby Cat: the everyday cat, and the reference for the shared cat body language (clips/_cats.py): weight shifts,
flank grooming, pouncing at nothing, the hind-leg stretch, claw sharpening, biscuits, sitting wash / tail wrap /
lookout / loaf loops, three sleeping loops, mood tail flicks and quivers, ears pinned flat when hurt, and distinct
pet / happy / eat / tame / bond level-up reactions."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402

TAGS = dict(C.TAGS)
PROCEDURAL = {'tail_sway': 7, 'ear_twitch': 1.3}


def style(m):
    return C.CatStyle(m, s=1.0, k=1.0, a=1.0)


def clips(m):
    st = style(m)
    out = C.common(st)
    extra = [C.pet(st), C.happy(st), C.eat(st), C.tame(st), C.level_up(st), C.biscuits(st), C.claw_scratch(st)]
    for c in extra:
        m.put(c)
    return out + extra


def IDLE(m):
    return C.idle_tables(style(m), extra_standing=[
        {'clip': 'biscuits', 'w': 0.8, 'cd': 60, 'boost': {'owner_near': 2}},
        {'clip': 'claw_scratch', 'w': 0.7, 'cd': 50, 'boost': {'dawn': 3}},
    ])
