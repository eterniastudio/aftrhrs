"""Black Cat: sleek and a little mysterious. The shared cat body language (clips/_cats.py) at a slower, smoother
tempo, plus its signatures: the Egyptian statue sit (tall, chin high, tail wrapped neat, a slow survey) and the
Halloween arch stretch it does at night."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cats as C  # noqa: E402
from animkit import keys, pulse, step  # noqa: E402

TAGS = dict(C.TAGS, sit_statue={'p': 'sitting'}, arch_stretch={'p': 'standing'})
PROCEDURAL = {'tail_sway': 6, 'ear_twitch': 1.1}


def style(m):
    return C.CatStyle(m, s=1.0, k=1.1, a=0.95)


def sit_statue(st):
    L = 9.0
    c = C._sit(st, 'sit_statue', L)
    up = lambda t: C.env(t, 0.0, 0.9, 8.1, 9.0)
    c.rot('body', lambda t: (8 * up(t), 0, 0), add=True)
    c.pos('root', lambda t: (0, 0.35 * st.s * up(t), 0), add=True)
    for leg in (C.LF, C.RF):
        c.rot(leg, lambda t: (-8 * up(t), 0, 0), add=True)
    for leg in (C.LB, C.RB):
        c.rot(leg, lambda t: (-6 * up(t), 0, 0), add=True)
    yaw = lambda t: keys(t, [(0, 0), (1.5, 0), (3.0, 24), (4.6, 24), (6.2, -18), (7.4, -18), (8.4, 0), (9.0, 0)])
    c.rot(C.H, lambda t: (2 * up(t), yaw(t), 0), add=True)
    c.rot(C.TAIL, lambda t: (0, 10 * up(t), 0), add=True)
    c.rot(C.MID, lambda t: (0, 10 * up(t), 0), add=True)
    c.rot(C.TIP, lambda t: (0, 10 * up(t) + 20 * pulse(t, 5.2, 5.7), 0), add=True)
    C._breath(c, st, L, 0.014)
    return c


def arch_stretch(st):
    s = st.s
    c = C.K(st, 'arch_stretch', 2.8)
    e = lambda t: keys(t, [(0, 0), (0.6, 1), (2.1, 1), (2.8, 0)])
    c.pos('body', lambda t: (0, 1.3 * s * e(t), 0))
    c.scl('body', lambda t: (1, 1 + 0.08 * e(t), 1))
    for leg in C.LEGS:
        c.pos(leg, lambda t: (0, -1.3 * s * e(t), 0))
    c.rot(C.LF, lambda t: (-6 * e(t), 0, 0))
    c.rot(C.RF, lambda t: (-6 * e(t), 0, 0))
    c.rot(C.LB, lambda t: (6 * e(t), 0, 0))
    c.rot(C.RB, lambda t: (6 * e(t), 0, 0))
    c.rot(C.H, lambda t: (-14 * e(t), 34 * e(t), 6 * e(t)))
    c.rot(C.JAW, lambda t: (-16 * pulse(t, 0.8, 1.7), 0, 0))
    c.rot(C.EAR_R, lambda t: (10 * e(t), 0, 0))
    c.rot(C.EAR_L, lambda t: (10 * e(t), 0, 0))
    c.rot(C.TAIL, lambda t: (-85 * e(t), 0, 0))
    c.rot(C.MID, lambda t: (35 * e(t), 0, 0))
    c.rot(C.TIP, lambda t: (45 * e(t), 0, 0))
    c.sound(0.9, 'ambient_sleepy', 0.4, 0.95)
    return c


def clips(m):
    st = style(m)
    out = C.common(st)
    extra = [C.pet(st), C.happy(st), C.eat(st), C.tame(st), C.level_up(st, twist=24), sit_statue(st), arch_stretch(st)]
    for c in extra:
        m.put(c)
    return out + extra


def IDLE(m):
    st = style(m)
    L = round(m.length('sit_statue'), 2)
    return C.idle_tables(st, extra_standing=[
        {'clip': 'arch_stretch', 'w': 1.0, 'cd': 60, 'when': 'night'},
    ], extra_sitting=[
        {'clip': 'sit_statue', 'w': 3, 'min': L, 'max': L, 'cd': 25, 'boost': {'night': 1.5}},
    ], playful=0.7)
