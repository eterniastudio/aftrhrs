"""Small helpers shared by the Creatures 2 End clip modules (chorusling, shulkling, voidpuff). Not a model:
gen_creatures.py only loads clips/<model>.py, these import this one. Clips are authored from scratch on the new
rigs (the bbmodels carry no clips), in Blockbench convention (x+ pitches the front up, y+ turns toward -x, z+ raises
+x), baked at 20 fps by animkit."""
import math

from animkit import Clip, step, smooth, wave, pulse, keys, overshoot, decay  # noqa: F401
from _smalla_kit import env, osc, bump, jolt, Out  # noqa: F401

CLOSED = (1.0, 0.06, 1.0)


def const(v):
    return lambda t: v


def hold_pose(c, pose):
    """Every (bone, channel) of pose {(bone, ch): xyz} held still (the base of a posture clip)."""
    for (bone, ch), v in pose.items():
        c.tracks[(bone, ch)] = const(v)
    return c


def add(c, bone, ch, fn):
    """Adds fn(t) to the bone's track (scale: fn gives the delta from 1)."""
    if ch == 'scale':
        return c._add(bone, 'scale', (lambda f: lambda t: tuple(1.0 + x for x in f(t)))(fn), True)
    return c._add(bone, ch, fn, True)


def R(c, bone, fn):
    return add(c, bone, 'rotation', fn)


def P(c, bone, fn):
    return add(c, bone, 'position', fn)


def S(c, bone, fn):
    return add(c, bone, 'scale', fn)


def eyes_closed(c, bone='eyes', spans=None):
    """Eyes squashed shut (sleep-type clips only): the whole clip, or during spans [(t0, t1)] with short eases."""
    if spans is None:
        c.tracks[(bone, 'scale')] = const(CLOSED)
        return c

    def f(t):
        k = 0.0
        for t0, t1 in spans:
            a = 1.0 if t0 <= 0 else smooth((t - t0) / 0.12)
            b = 1.0 if t1 >= c.L else smooth((t1 - t) / 0.12)
            k = max(k, min(a, b))
        return (1.0, 1.0 - 0.94 * k, 1.0)
    c.tracks[(bone, 'scale')] = f
    return c


def loop_ok(L, period):
    n = L / period
    if abs(n - round(n)) > 1e-6:
        raise ValueError('loop %.3f is not a multiple of %.3f' % (L, period))
    return int(round(n))
