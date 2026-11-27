"""Shared helpers for the phase-2 small-creature clip modules (puddlepup ... driftglow). Not a model module itself
(gen_creatures only loads clips/<model>.py); each module does `from _life import *`.

Everything is in Blockbench convention (see animkit): rot x+ pitches the front of a bone up, y+ turns towards -x,
z+ raises the +x side; positions in px.
"""
import math

from animkit import Clip, decay, keys, overshoot, pulse, sample, step, wave

__all__ = ['Clip', 'decay', 'keys', 'overshoot', 'pulse', 'step', 'wave', 'env', 'const', 'base', 'loop_base',
           'tail_wag', 'hop', 'shake_body', 'squash', 'sit_loop', 'sleep_loop', 'one_shot', 'ramp', 'pose_of',
           'idle_entry', 'math', 'keep']


def keep(m, c):
    """Bakes the clip NOW and freezes it. The tracks are closures over the module's local names (e, L, up ...), which
    later clips in the same module reassign; merge() bakes after clips() returns, so without this every clip would
    be baked with the last clip's envelopes and lengths."""
    data = m.put(c) and m.clips[c.name]
    c.bake = lambda: data
    return c


def env(t, t0, t1, up=0.25, down=None):
    """0 -> 1 (eased over `up` s from t0) ... 1 -> 0 (over `down` s ending t1)."""
    down = up if down is None else down
    return step(t, t0, t0 + up) * (1 - step(t, t1 - down, t1))


def ramp(t, L, edge=0.3):
    """Envelope for a one-shot of length L: eases in over `edge` s and out over the last `edge` s."""
    return env(t, 0, L, edge, edge)


def const(v):
    return lambda t: v


def pose_of(m, clip, t):
    return m.pose(clip, t)


def base(c, pose):
    """Every bone of `pose` ({(bone, ch): xyz}) held constant (add tracks on top with add=True)."""
    for (bone, ch), v in pose.items():
        c.tracks[(bone, ch)] = const(v)
    return c


def loop_base(c, m, clip='idle'):
    """Starts a clip from another loop, wrapped at a speed that fits a whole number of its cycles into c.L (seamless)."""
    Lb = m.length(clip)
    n = max(1, int(round(c.L / Lb)))
    speed = n * Lb / c.L
    src = m.clips[clip]
    for bone, chs in (src.get('bones') or {}).items():
        for ch, data in chs.items():
            rest = (1, 1, 1) if ch == 'scale' else (0, 0, 0)
            c.tracks[(bone, ch)] = (lambda d, r: lambda t: sample(d, (t * speed) % Lb if t < c.L else 0.0, r))(data, rest)
    return c


def one_shot(m, name, L, start='idle', t0=0.0):
    """A one-shot that starts and ends on `start`'s pose at t0 (add motions that are 0 at both ends)."""
    c = Clip(m, name, L, 'once')
    return base(c, m.pose(start, t0))


def sit_loop(m, name, L, sit='sit'):
    c = Clip(m, name, L, 'loop')
    return base(c, m.pose(sit, 'end'))


def sleep_loop(m, name, L, sleep='sleep'):
    """A lying loop on the sleep clip (eyes as the sleep clip has them; name must be sleep-type: sleep_*)."""
    c = Clip(m, name, L, 'loop')
    return loop_base(c, m, sleep)


def tail_wag(c, bones, amp, freq_cycles, L=None, env_fn=None, axis=1, lag=0.06, grow=1.3):
    """A wag along a tail chain: each segment lags the previous one and swings a bit wider."""
    L = L or c.L
    for i, b in enumerate(bones):
        a = amp * (grow ** i)
        sh = lag * i

        def fn(t, a=a, sh=sh):
            e = env_fn(t) if env_fn else 1.0
            v = a * e * math.sin(2 * math.pi * freq_cycles * (t - sh) / L)
            out = [0.0, 0.0, 0.0]
            out[axis] = v
            return tuple(out)
        c.rot(b, fn, add=True)
    return c


def hop(c, bone, t0, t1, height, squash_bone=None):
    """A hop: anticipation dip, a parabolic lift between t0 and t1, landing squash."""
    def fn(t):
        dip = -0.6 * pulse(t, t0 - 0.12, t0 + 0.02) - 0.5 * pulse(t, t1 - 0.02, t1 + 0.14)
        if t0 < t < t1:
            u = (t - t0) / (t1 - t0)
            return (0, height * 4 * u * (1 - u) + dip, 0)
        return (0, dip, 0)
    c.pos(bone, fn, add=True)
    if squash_bone:
        c.scl(squash_bone, lambda t: (1 + 0.12 * pulse(t, t0 - 0.12, t0 + 0.02) + 0.14 * pulse(t, t1 - 0.02, t1 + 0.16),
                                      1 - 0.14 * pulse(t, t0 - 0.12, t0 + 0.02) + 0.1 * pulse(t, t0 + 0.02, t0 + 0.2)
                                      - 0.16 * pulse(t, t1 - 0.02, t1 + 0.16),
                                      1 + 0.12 * pulse(t, t0 - 0.12, t0 + 0.02) + 0.14 * pulse(t, t1 - 0.02, t1 + 0.16)),
              add=True)
    return c


def shake_body(c, bone, t0, t1, amp, cycles, axis=2):
    """A wet-dog shake: fast roll oscillation with an envelope (peaks in the middle)."""
    def fn(t):
        if t <= t0 or t >= t1:
            return (0, 0, 0)
        u = (t - t0) / (t1 - t0)
        v = amp * math.sin(math.pi * u) ** 0.7 * math.sin(2 * math.pi * cycles * u)
        out = [0.0, 0.0, 0.0]
        out[axis] = v
        return tuple(out)
    c.rot(bone, fn, add=True)
    return c


def squash(c, bone, t0, t1, amt):
    """A squash (wider, shorter) pulse between t0 and t1."""
    c.scl(bone, lambda t: (1 + amt * pulse(t, t0, t1), 1 - amt * pulse(t, t0, t1), 1 + amt * pulse(t, t0, t1)), add=True)
    return c


def idle_entry(clip, w=1, cd=None, mn=None, mx=None, when=None, boost=None):
    e = {'clip': clip, 'w': w}
    if cd is not None:
        e['cd'] = cd
    if mn is not None:
        e['min'], e['max'] = mn, mx
    if when:
        e['when'] = when
    if boost:
        e['boost'] = boost
    return e
