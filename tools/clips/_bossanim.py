"""Helpers for the boss and police clip modules (p2-bosses). Not a model module: no model is named _bossanim, so
gen_creatures never loads it on its own; the boss modules import it.

Pose-to-pose authoring on top of animkit, built from the models' own clips (their vocabulary of poses):
  P(m, clip, t)          a pose {(bone, ch): xyz} sampled with the clip's real interpolation (catmull-rom aware)
  mix(a, b, w)           a + (b - a) * w per key (missing keys count as rest)
  add(a, delta) / off(**{bone: (x, y, z)}) / scale_delta(...)  pose arithmetic
  seq(c, [(t, pose), ...], ease)   tracks through the poses (eased stops at each key; first = last for loops)
  retime(m, name, src, [(new_t, src_t), ...])  the same clip on a new time line (holds, slower wind-ups)
  ccopy(c, src)          animkit Clip.copy, but catmull-rom aware (copies stay as smooth as the original)
  shake(c, bone, t0, t1, amp, freq)  an additive tremble inside a window (straining, impact shudder)
Values are Blockbench convention (x+ pitches the front up; y+ turns towards -x; z+ raises +x).
"""
import math

from animkit import Clip, _vec, smooth

REST = {'rotation': (0.0, 0.0, 0.0), 'position': (0.0, 0.0, 0.0), 'scale': (1.0, 1.0, 1.0)}


# ------------------------------------------------------------------------------------------------ sampling
def _pts(ch):
    if not isinstance(ch, dict) or any(k in ch for k in ('vector', 'pre', 'post')):
        v = _vec(ch, 'post')
        return [(0.0, v, v, False)]
    out = []
    for k, e in ch.items():
        try:
            t = float(k)
        except ValueError:
            continue
        cat = isinstance(e, dict) and e.get('lerp_mode') == 'catmullrom'
        out.append((t, _vec(e, 'pre'), _vec(e, 'post'), cat))
    out.sort(key=lambda p: p[0])
    return out


def _cr(p0, p1, p2, p3, u):
    return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (-p0 + 3 * p1 - 3 * p2 + p3) * u ** 3)


def csample(ch, t, default=(0.0, 0.0, 0.0)):
    """Channel value at t with GeckoLib's interpolation: linear, or catmull-rom where a key of the segment says so."""
    if ch is None:
        return default
    pts = _pts(ch)
    if not pts:
        return default
    if t <= pts[0][0]:
        return pts[0][1]
    for i in range(len(pts) - 1):
        t1, _, v1, c1 = pts[i]
        t2, v2, _, c2 = pts[i + 1]
        if t <= t2:
            u = (t - t1) / (t2 - t1) if t2 > t1 else 1.0
            if c1 or c2:
                p0 = pts[i - 1][2] if i > 0 else v1
                p3 = pts[i + 2][1] if i + 2 < len(pts) else v2
                return tuple(_cr(p0[j], v1[j], v2[j], p3[j], u) for j in range(3))
            return tuple(v1[j] + (v2[j] - v1[j]) * u for j in range(3))
    return pts[-1][2]


def P(m, clip, t):
    """Pose of `clip` at time t ('end' = last frame) with the real interpolation."""
    c = m.clips[clip]
    if t == 'end':
        t = float(c.get('animation_length', 0))
    out = {}
    for bone, chs in (c.get('bones') or {}).items():
        for ch, data in chs.items():
            out[(bone, ch)] = csample(data, t, REST[ch])
    return out


# ------------------------------------------------------------------------------------------------ pose arithmetic
def mix(a, b, w, bones=None):
    out = {}
    for k in set(a) | set(b):
        if bones is not None and k[0] not in bones:
            out[k] = a.get(k, REST[k[1]])
            continue
        va, vb = a.get(k, REST[k[1]]), b.get(k, REST[k[1]])
        out[k] = tuple(va[i] + (vb[i] - va[i]) * w for i in range(3))
    return out


def add(a, delta, w=1.0):
    """a + w * delta (delta: offsets from rest, e.g. off(...) or diff(b, rest_pose))."""
    out = dict(a)
    for k, d in delta.items():
        base = out.get(k, REST[k[1]])
        if k[1] == 'scale':
            out[k] = tuple(base[i] * (1 + (d[i] - 1) * w) for i in range(3))
        else:
            out[k] = tuple(base[i] + d[i] * w for i in range(3))
    return out


def diff(b, a):
    """b - a as a delta (scale as a ratio)."""
    out = {}
    for k in set(a) | set(b):
        va, vb = a.get(k, REST[k[1]]), b.get(k, REST[k[1]])
        if k[1] == 'scale':
            out[k] = tuple((vb[i] / va[i]) if va[i] else 1.0 for i in range(3))
        else:
            out[k] = tuple(vb[i] - va[i] for i in range(3))
    return out


def off(rot=None, pos=None, scl=None):
    """A delta from dicts {bone: (x, y, z)} per channel."""
    out = {}
    for ch, d in (('rotation', rot), ('position', pos), ('scale', scl)):
        for bone, v in (d or {}).items():
            out[(bone, ch)] = tuple(float(x) for x in v)
    return out


def only(pose, bones):
    return {k: v for k, v in pose.items() if k[0] in bones}


def without(pose, bones):
    return {k: v for k, v in pose.items() if k[0] not in bones}


# ------------------------------------------------------------------------------------------------ building clips
def seq(c, frames, ease='smooth', keys_=None):
    """Tracks through [(t, pose)] for every key any pose has (rest where a pose lacks it). ease: smooth (stops at each
    key), linear, out (fast start), in (fast end); per segment ease can be given as a third tuple item."""
    allk = set()
    for f in frames:
        allk |= set(f[1])
    if keys_ is not None:
        allk &= set(keys_)
    times = [f[0] for f in frames]
    eases = [f[2] if len(f) > 2 else ease for f in frames]

    def make(k):
        vals = [f[1].get(k, REST[k[1]]) for f in frames]

        def fn(t):
            if t <= times[0]:
                return vals[0]
            for i in range(len(times) - 1):
                if t <= times[i + 1]:
                    span = times[i + 1] - times[i]
                    u = (t - times[i]) / span if span > 0 else 1.0
                    e = eases[i + 1]
                    if e == 'smooth':
                        u = smooth(u)
                    elif e == 'out':
                        u = 1 - (1 - u) ** 2
                    elif e == 'in':
                        u = u * u
                    elif e == 'snap':          # very fast in (a strike)
                        u = u ** 3
                    elif e == 'back':          # out with a small overshoot
                        s = 1.70158
                        u -= 1
                        u = u * u * ((s + 1) * u + s) + 1
                    return tuple(vals[i][j] + (vals[i + 1][j] - vals[i][j]) * u for j in range(3))
            return vals[-1]
        return fn
    for k in sorted(allk):
        c.tracks[k] = make(k)
    return c


def ccopy(c, src, t0=0.0, speed=1.0, bones=None, period=None):
    """Clip.copy with catmull-rom sampling; `period` wraps the source time (copying a loop into a longer loop)."""
    s = c.m.clips[src]
    for bone, chs in (s.get('bones') or {}).items():
        if bones is not None and bone not in bones:
            continue
        for ch, data in chs.items():
            def fn(t, data=data, ch=ch):
                ts = t0 + t * speed
                if period:
                    ts %= period
                return csample(data, ts, REST[ch])
            c.tracks[(bone, ch)] = fn
    return c


def retime(m, name, src, tmap, loop='once'):
    """`src` on a new time line: tmap [(new_t, src_t), ...] piecewise linear (equal src_t in a row = a hold)."""
    L = tmap[-1][0]
    c = Clip(m, name, L, loop)

    def remap(t):
        if t <= tmap[0][0]:
            return tmap[0][1]
        for (a, sa), (b, sb) in zip(tmap, tmap[1:]):
            if t <= b:
                u = (t - a) / (b - a) if b > a else 1.0
                return sa + (sb - sa) * u
        return tmap[-1][1]
    s = m.clips[src]
    for bone, chs in (s.get('bones') or {}).items():
        for ch, data in chs.items():
            c.tracks[(bone, ch)] = (lambda data, ch: lambda t: csample(data, remap(t), REST[ch]))(data, ch)
    return c


def add_const(c, delta):
    """Adds a constant delta pose (B.off(...)) on top of the clip's tracks (rotation / position add, scale multiplies)."""
    for (bone, ch), d in delta.items():
        if not c.m.has_bone(bone):
            continue
        if ch == 'scale':
            prev = c.tracks.get((bone, ch))
            c.tracks[(bone, ch)] = (lambda prev, d: lambda t: tuple((prev(t)[i] if prev else 1.0) * d[i] for i in range(3)))(prev, d)
        else:
            c._add(bone, ch, (lambda d: lambda t: d)(d), True)
    return c


def shake(c, bone, t0, t1, amp, freq=12.0, axes=(1, 0, 1)):
    """An additive tremble on `bone` between t0 and t1 (sin^2 envelope)."""
    if bone is None:
        return c

    def fn(t):
        if t <= t0 or t >= t1:
            return (0.0, 0.0, 0.0)
        env = math.sin(math.pi * (t - t0) / (t1 - t0)) ** 2
        a = amp * env
        return (axes[0] * a * math.sin(2 * math.pi * freq * t), axes[1] * a * math.sin(2 * math.pi * freq * t + 1.3),
                axes[2] * a * math.cos(2 * math.pi * freq * t * 0.87))
    return c.rot(bone, fn, add=True)


def wobble(c, bone, ch, fn):
    """Adds fn(t) -> xyz on top of an existing track (or as the track)."""
    if bone is None or not c.m.has_bone(bone):
        return c
    return c._add(bone, ch, fn, True)


def sounds(c, items):
    """items: [(t, event, volume, pitch)] -> Clip.sound keyframes."""
    for it in items:
        t, ev = it[0], it[1]
        vol = it[2] if len(it) > 2 else 1.0
        pitch = it[3] if len(it) > 3 else 1.0
        c.sound(t, ev, vol, pitch)
    return c


def with_sounds(m, src, items, name=None):
    """A copy of a generated clip (same name by default: it replaces it) with sound keyframes added."""
    s = m.clips[src]
    loop = {True: 'loop', 'hold_on_last_frame': 'hold'}.get(s.get('loop'), 'once')
    c = Clip(m, name or src, float(s.get('animation_length', 1)), loop)
    ccopy(c, src)
    return sounds(c, items)


def keep(m, c):
    """Bakes the clip into the model now and freezes it: animkit.merge bakes the returned clips again after clips()
    returns, when lambdas that read names reassigned later in the module would see the last values."""
    data = c.bake()
    m.clips[c.name] = data
    c.bake = lambda: data
    return c
