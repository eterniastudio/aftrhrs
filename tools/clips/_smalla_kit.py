"""Small authoring helpers shared by the phase-2 "small A" clip modules (emberkit, snowpelt_owlet, cairnling,
glimmermoth, runeback_tortoise, skyfin_ray, mossbun, burrowpip, honeycub). Not a model: gen_creatures.py only loads
clips/<model>.py, these modules import this one.

Every clip starts from a base (a looping copy of a clip, or a frozen pose) and adds offsets with `add`, so variants
start and end exactly on the pose they come from (envelopes are 0 at both ends)."""
import math

from animkit import Clip, sample, smooth, step


def env(t, t0, t1, ramp=0.25):
    """1 between t0 and t1 with smooth ramps of `ramp` s on both sides (0 outside): an offset that fades in and out."""
    if t <= t0 or t >= t1:
        return 0.0
    r = min(ramp, (t1 - t0) / 2)
    return smooth((t - t0) / r) * smooth((t1 - t) / r)


def osc(t, freq, phase=0.0):
    """sin at freq Hz (pair with env so loops stay seamless)."""
    return math.sin(2 * math.pi * (freq * t + phase))


def bump(t, t0, t1):
    """0 -> 1 -> 0 between t0 and t1 (sin^2), for single motions (a hop, a nod)."""
    if t <= t0 or t >= t1:
        return 0.0
    return math.sin(math.pi * (t - t0) / (t1 - t0)) ** 2


def jolt(t, t0, t1):
    """Fast attack, slow release (0 -> 1 quickly after t0, back to 0 by t1): flinches, flares."""
    if t <= t0 or t >= t1:
        return 0.0
    u = (t - t0) / (t1 - t0)
    a = 0.18
    return smooth(u / a) if u < a else 1 - smooth((u - a) / (1 - a))


def loop_copy(c, clip, bones=None, speed=1.0):
    """c's tracks start from `clip`, wrapped at its length (a looping base for any clip length)."""
    src = c.m.clips[clip]
    L = float(src.get('animation_length') or 1.0)
    for bone, chs in (src.get('bones') or {}).items():
        if bones is not None and bone not in bones:
            continue
        for ch, data in chs.items():
            rest = (1, 1, 1) if ch == 'scale' else (0, 0, 0)
            c.tracks[(bone, ch)] = (lambda d, rest: lambda t: sample(d, (t * speed) % L, rest))(data, rest)
    return c


def freeze(c, clip, at=0.0, bones=None):
    """c's tracks start from `clip`'s pose at `at` ('end' = last frame), held still."""
    pose = c.m.pose(clip, at)
    for (bone, ch), v in pose.items():
        if bones is not None and bone not in bones:
            continue
        c.tracks[(bone, ch)] = (lambda v: lambda t: v)(v)
    return c


def start(m, name, L, loop='once', src='idle', at=None, bones=None):
    """A new clip on a base: `src` looping (at=None) or frozen at `at`."""
    c = Clip(m, name, L, loop)
    if src:
        if at is None:
            n = L / m.length(src)
            if loop == 'loop' and abs(n - round(n)) > 0.01:
                raise ValueError('%s: loop length %.2f is not a multiple of %s (%.2f): it would not be seamless'
                                 % (name, L, src, m.length(src)))
            loop_copy(c, src, bones)
        else:
            freeze(c, src, at, bones)
    return c


def add(c, bone, ch, fn):
    """Adds fn(t) -> (x, y, z) to the bone's track (scale: added to 1, i.e. fn gives the delta)."""
    if bone is None:
        return c
    if ch == 'scale':
        g = (lambda f: lambda t: tuple(1.0 + x for x in f(t)))(fn)
        return c._add(bone, 'scale', g, True)
    return c._add(bone, ch, fn, True)


def R(c, bone, fn):
    return add(c, bone, 'rotation', fn)


def P(c, bone, fn):
    return add(c, bone, 'position', fn)


def S(c, bone, fn):
    return add(c, bone, 'scale', fn)


def overlay_from(m, name, L, pose_clip, at, bones):
    """An overlay clip (head / ears / tail only) that sits on `pose_clip`'s pose for `bones`: add offsets on top."""
    c = Clip(m, name, L, 'once')
    pose = m.pose(pose_clip, at)
    for b in bones:
        for ch in ('rotation', 'position'):
            v = pose.get((b, ch), (0.0, 0.0, 0.0))
            c.tracks[(b, ch)] = (lambda v: lambda t: v)(v)
    return c


class Out(list):
    """The clip list a module returns. Each clip is baked the moment it is appended and frozen: animkit.merge bakes
    after clips() returns, and lambdas reading locals that later clips reassign (e, up, air ...) would otherwise all
    see the last clip's values."""

    def __init__(self, m, clips=()):
        super().__init__()
        self.m = m
        for c in clips:
            self.append(c)

    def append(self, c):
        self.m.put(c)
        data = self.m.clips[c.name]
        c.bake = lambda: data
        super().append(c)


__all__ = ['Out','env', 'osc', 'bump', 'jolt', 'loop_copy', 'freeze', 'start', 'add', 'R', 'P', 'S', 'overlay_from', 'step',
           'smooth', 'Clip']
