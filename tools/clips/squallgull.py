"""Squallgull: a cheeky shore gull. Perched (its Perch skill sets the `idle` clip), it preens, raises both wings high
in a big stretch, ruffles, shuffles its feet on the spot, tilts its head at you and throws its head back for the gull's
long call. On the wing it calls out (head-only overlay over the flight)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, decay, env, keys, once, pulse, wave  # noqa: E402
from animkit import Clip  # noqa: E402

PROCEDURAL = {'tail_sway': 4}
WINGS = (('right_wing', 'right_wingtip', 1), ('left_wing', 'left_wingtip', -1))


def preen(m, L=2.6):
    c = once(m, 'preen', L)
    e = lambda t: env(t, 0.05, L - 0.1, 0.4, 0.45)
    nib = lambda t: 7 * wave(t, 0.28) * env(t, 0.5, L - 0.5, 0.1, 0.1)
    c.rot('h_head', lambda t: (-24 * e(t) + nib(t), 115 * e(t), 14 * e(t)), add=True)
    c.rot('right_wing', lambda t: (0, 0, 14 * e(t)), add=True)
    c.rot('body', lambda t: (0, 8 * e(t), -4 * e(t)), add=True)
    c.rot('tail', lambda t: (-6 * e(t), 0, 0), add=True)
    c.sound(0.9, 'minecraft:entity.parrot.fly', 0.12, 1.5)
    c.sound(1.7, 'minecraft:entity.parrot.fly', 0.1, 1.6)
    return c


def wing_stretch(m, L=2.4):
    """Both wings raised high over the back, tips spread, a quiver, then folded away neatly."""
    c = once(m, 'wing_stretch', L)
    up = lambda t: keys(t, [(0, 0), (0.45, 1), (1.5, 1), (2.0, 0), (L, 0)])
    quiver = lambda t: 4 * wave(t, 0.12) * env(t, 0.6, 1.4, 0.1, 0.1)
    for w, tip, sg in WINGS:
        # like the flight pose (wings swept out sideways with y +-88), then lifted high over the back
        c.rot(w, (lambda sg: lambda t: (0, sg * 88 * up(t), sg * (55 * up(t) + quiver(t))))(sg), add=True)
        c.rot(tip, (lambda sg: lambda t: (-12 * up(t), 0, 0))(sg), add=True)
    c.rot('body', lambda t: (6 * up(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (-12 * up(t), 0, 0), add=True)
    c.rot('tail', lambda t: (-10 * up(t), 0, 0), add=True)
    c.sound(0.3, 'minecraft:entity.parrot.fly', 0.3, 0.9)
    c.sound(1.85, 'minecraft:entity.parrot.fly', 0.25, 1.1)
    return c


def ruffle(m, L=1.3):
    c = once(m, 'ruffle', L)
    puff = lambda t: env(t, 0.0, L, 0.15, 0.5)
    c.scl('body', lambda t: (1 + 0.12 * puff(t), 1 + 0.1 * puff(t), 1 + 0.06 * puff(t)), add=True)
    c.rot('body', lambda t: (0, 0, 7 * decay(t, 0.15, 5.0, 4.0)), add=True)
    c.rot('h_head', lambda t: (0, 0, -16 * decay(t, 0.2, 5.0, 4.0)), add=True)
    for w, tip, sg in WINGS:
        c.rot(w, (lambda sg: lambda t: (0, 0, sg * 16 * puff(t)))(sg), add=True)
    c.rot('tail', lambda t: (0, 18 * decay(t, 0.25, 5, 4), 0), add=True)
    c.sound(0.15, 'minecraft:entity.parrot.fly', 0.3, 1.3)
    return c


def perch_shuffle(m, L=1.8):
    """Steps on the spot, turning a little: left, right, left, with a body wobble each step."""
    c = once(m, 'perch_shuffle', L)
    steps = ((0.1, 'left_leg'), (0.55, 'right_leg'), (1.0, 'left_leg'))
    for t0, leg in steps:
        c.rot(leg, (lambda t0: lambda t: (28 * pulse(t, t0, t0 + 0.35), 0, 0))(t0), add=True)
        c.pos(leg, (lambda t0: lambda t: (0, 1.0 * pulse(t, t0, t0 + 0.35), 0))(t0), add=True)
        c.sound(t0 + 0.35, 'step', 0.5, 1.2)
    c.rot('body', lambda t: (0, keys(t, [(0, 0), (0.45, 12), (0.9, 6), (1.35, 18), (L, 0)]), 5 * (pulse(t, 0.1, 0.45) - pulse(t, 0.55, 0.9) + pulse(t, 1.0, 1.35))), add=True)
    c.rot('h_head', lambda t: (0, -10 * pulse(t, 0.2, 1.4), 0), add=True)
    return c


def head_tilt(m, L=1.8):
    c = once(m, 'head_tilt', L)
    c.rot('h_head', lambda t: (6 * pulse(t, 0.1, 1.6), 0, keys(t, [(0, 0), (0.2, 28), (0.8, 28), (1.0, -20), (1.5, -20), (L, 0)])), add=True)
    return c


def long_call(m, L=2.8):
    """The gull's long call: head jerks down, then is thrown up and back with the bill wide, three cries."""
    c = once(m, 'long_call', L)
    pitch = lambda t: keys(t, [(0, 0), (0.3, -40), (0.55, -36), (0.8, 52), (2.2, 48), (L, 0)])
    c.rot('h_head', lambda t: (pitch(t), 0, 0), add=True)
    c.rot('jaw', lambda t: (-28 * (pulse(t, 0.8, 1.2) + pulse(t, 1.25, 1.65) + 0.8 * pulse(t, 1.7, 2.15)), 0, 0), add=True)
    c.rot('body', lambda t: (-8 * pulse(t, 0.1, 0.7) + 10 * env(t, 0.7, 2.4, 0.2, 0.4), 0, 0), add=True)
    for w, tip, sg in WINGS:
        c.rot(w, (lambda sg: lambda t: (0, 0, sg * 20 * env(t, 0.7, 2.3, 0.15, 0.3)))(sg), add=True)
    c.rot('tail', lambda t: (8 * env(t, 0.7, 2.3), 0, 0), add=True)
    c.sound(0.85, 'ambient', 0.7, 1.0)
    c.sound(1.3, 'ambient', 0.6, 1.08)
    c.sound(1.75, 'ambient', 0.5, 1.15)
    return c


def call_fly(m, L=1.2):
    """Head and bill only (overlay over the flight): a cry on the wing."""
    c = Clip(m, 'call_fly', L, 'once')
    c.rot('h_head', lambda t: (18 * env(t, 0.0, L, 0.2, 0.4), 0, 0))
    c.rot('jaw', lambda t: (-26 * (pulse(t, 0.2, 0.55) + pulse(t, 0.6, 0.95)), 0, 0))
    c.sound(0.2, 'ambient', 0.7, 1.05)
    return c


def clips(m):
    return [preen(m), wing_stretch(m), ruffle(m), perch_shuffle(m), head_tilt(m), long_call(m), call_fly(m), hurt(m, extra=('tail',))]


# a perched gull's activity clip is `idle` (Perch skill); on the wing it is `fly`
IDLE = {
    'idle': [
        {'clip': '@mood', 'w': 3, 'min': 3, 'max': 8},
        {'clip': 'preen', 'w': 3, 'cd': 8},
        {'clip': 'wing_stretch', 'w': 1.5, 'cd': 20},
        {'clip': 'ruffle', 'w': 2, 'cd': 10},
        {'clip': 'perch_shuffle', 'w': 2, 'cd': 6},
        {'clip': 'head_tilt', 'w': 2, 'cd': 6},
        {'clip': 'long_call', 'w': 1.2, 'cd': 15},
    ],
    'fly': [{'clip': 'call_fly', 'w': 1, 'cd': 12}],
    'notice': [
        {'clip': 'head_tilt', 'w': 2, 'when': 'var:perched'},
        {'clip': 'call_fly', 'w': 1},
    ],
}
