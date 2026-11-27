"""Loomworm (+ young): a fat jungle silkworm. Slow and soft: it rears up to sniff the air, sways its front end,
inches its segments together and stretches out again, curls up defensively when someone comes close and sleeps curled
in a C. Young ones wiggle."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _herd import hurt, Quad, decay, env, keys, loop, once, pulse, transition, wave  # noqa: E402

TAGS = {'sleep': {'p': 'lying'}}
PROCEDURAL = {'tail': ['tail', 'tuft'], 'tail_sway': 4, 'breath': 0.02}


def rear_up(m, L=2.6):
    c = once(m, 'rear_up', L)
    e = lambda t: env(t, 0.0, L, 0.5, 0.6)
    c.rot('front', lambda t: (34 * e(t), 12 * wave(t, 1.3) * e(t), 0), add=True)
    c.rot('h_head', lambda t: (8 * e(t) + 4 * wave(t, 0.35) * e(t), 18 * wave(t, 1.3, 1, 0.12) * e(t), 0), add=True)
    c.rot('back', lambda t: (-4 * e(t), 0, 0), add=True)
    c.rot('tail', lambda t: (0, 8 * wave(t, 1.3, 1, 0.3) * e(t), 0), add=True)
    return c


def sway(m, L=5.2):
    c = loop(m, 'sway', L)
    c.rot('front', lambda t: (3 * wave(t, L, 2), 10 * wave(t, L, 1), 0), add=True)
    c.rot('h_head', lambda t: (2 * wave(t, L, 2, 0.2), 12 * wave(t, L, 1, 0.12), 3 * wave(t, L, 1)), add=True)
    c.rot('back', lambda t: (0, -6 * wave(t, L, 1, 0.1), 0), add=True)
    c.rot('tail', lambda t: (0, -8 * wave(t, L, 1, 0.2), 0), add=True)
    c.rot('tuft', lambda t: (0, -10 * wave(t, L, 1, 0.3), 0), add=True)
    return c


def stretch(m, L=2.2):
    """Inchworm: the rear bunches up into a hump, then the front reaches out long, then it all settles."""
    c = once(m, 'stretch', L)
    bunch = lambda t: env(t, 0.0, 1.2, 0.4, 0.4)
    reach = lambda t: env(t, 0.8, L, 0.4, 0.5)
    c.pos('back', lambda t: (0, 0, -1.0 * bunch(t)), add=True)
    c.rot('back', lambda t: (14 * bunch(t), 0, 0), add=True)
    c.pos('tail', lambda t: (0, 0, -0.8 * bunch(t)), add=True)
    c.rot('tail', lambda t: (-10 * bunch(t), 0, 0), add=True)
    c.scl('body', lambda t: (1, 1 + 0.12 * bunch(t), 1 - 0.08 * bunch(t) + 0.08 * reach(t)), add=True)
    c.pos('front', lambda t: (0, 0, -1.2 * reach(t)), add=True)
    c.rot('h_head', lambda t: (6 * reach(t), 0, 0), add=True)
    c.pos('h_head', lambda t: (0, 0, -0.6 * reach(t)), add=True)
    return c


def curl(m, L=3.0):
    """Notice: head tucked under, front curled in, the whole worm squeezed short; then it slowly uncurls."""
    c = once(m, 'curl', L)
    e = lambda t: keys(t, [(0, 0), (0.25, 1), (2.0, 1), (L, 0)])
    c.rot('front', lambda t: (-22 * e(t), 0, 0), add=True)
    c.rot('h_head', lambda t: (-42 * e(t), 0, 0), add=True)
    c.rot('tail', lambda t: (-12 * e(t), 0, 0), add=True)
    c.scl('body', lambda t: (1 + 0.06 * e(t), 1 + 0.06 * e(t), 1 - 0.12 * e(t)), add=True)
    c.rot('body', lambda t: (0, 0, 3 * decay(t, 0.25, 5, 5)), add=True)
    return c


def graze_munch(m, L=1.4):
    c = once(m, 'graze_munch', L, 'graze')
    c.rot('h_head', lambda t: (-6 * max(0, wave(t, 0.2)) * env(t, 0, L, 0.1, 0.2), 0, 0), add=True)
    c.rot('front', lambda t: (0, 6 * wave(t, L, 1), 0), add=True)
    c.sound(0.3, 'minecraft:block.azalea_leaves.step', 0.25, 1.4)
    c.sound(0.9, 'minecraft:block.azalea_leaves.step', 0.2, 1.5)
    return c


def sleep(m, L=6.0):
    c = loop(m, 'sleep', L, 'lie')
    for b, y in (('front', 26), ('h_head', 28), ('back', -22), ('tail', -26), ('tuft', -18)):
        c.rot(b, (lambda y: lambda t: (0, y, 0))(y), add=True)
    c.scl('body', lambda t: (1 + 0.02 * wave(t, L, 1), 1 + 0.035 * wave(t, L, 1), 1))
    c.scl('eyes', lambda t: (1, 0.12, 1))
    return c


def wiggle(m, L=1.4):
    """Young: a happy wave runs from head to tail."""
    c = once(m, 'wiggle', L)
    e = lambda t: env(t, 0, L, 0.2, 0.3)
    for i, b in enumerate(('h_head', 'front', 'back', 'tail', 'tuft')):
        c.rot(b, (lambda i: lambda t: (0, 16 * wave(t - 0.1 * i, 0.7) * e(t), 4 * wave(t - 0.1 * i, 0.35) * e(t)))(i), add=True)
    c.pos('body', lambda t: (0, 0.6 * pulse(t, 0.2, 0.6) + 0.5 * pulse(t, 0.8, 1.2), 0), add=True)
    return c


def clips(m):
    q = Quad(m)
    return [q.look_around(L=3.2), rear_up(m), sway(m), stretch(m), curl(m), graze_munch(m), sleep(m), wiggle(m),
            transition(m, 'lie_down', 'idle', 0, 'lie', 0, 1.0, 0.1), transition(m, 'stand_up', 'lie', 0, 'idle', 0, 0.9, 0.05), hurt(m, amt=0.6)]


IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 4, 'min': 5, 'max': 12},
        {'clip': 'look_around', 'w': 2, 'cd': 14},
        {'clip': 'rear_up', 'w': 2.5, 'cd': 12},
        {'clip': 'sway', 'w': 2, 'min': 5, 'max': 12},
        {'clip': 'stretch', 'w': 2, 'cd': 10},
        {'clip': 'wiggle', 'w': 3, 'cd': 6, 'when': 'young'},
    ],
    'graze': [
        {'clip': '@mood', 'w': 4, 'min': 2, 'max': 5},
        {'clip': 'graze_munch', 'w': 3, 'cd': 3},
    ],
    'notice': [{'clip': 'curl', 'w': 2}, {'clip': 'rear_up', 'w': 1}],
    'lying': [
        {'clip': '@mood', 'w': 3, 'min': 10, 'max': 30},
        {'clip': 'sleep', 'w': 1, 'min': 20, 'max': 60, 'boost': {'night': 6}},
    ],
}
