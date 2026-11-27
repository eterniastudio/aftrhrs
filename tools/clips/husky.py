"""Husky: the talker. Woo-woo conversations with its person, a full sitting howl at night (the night chorus), and
zoomies: tight laps around its own spot that skid into a play bow. The shared dog kit (`_dogs.py`) gives it the
common dog life: head tilt, sniffing, ear scratch, shake-off, stretch, play bow, petting and bond reactions, sitting
and sleeping variants."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _dogs as K  # noqa: E402
from _dogs import Clip, env, keys, osc, pulse, step  # noqa: E402

VOICE = ('entity.wolf', 1.0)


def dog(m):
    return K.Dog(m, voice=VOICE[0], vp=VOICE[1], wag='y', ears='up')


def talk(d, name='talk'):
    """"Woo-woo-woo": a husky answering back. Head bobs up on each syllable, jaw shaped like an O, ears flicking,
    a front paw stamp on the last one."""
    L = d.T(2.6)
    c = Clip(d.m, name, L)
    d.seq(c, [(0, d.rest), (0.12 * L, d.at(d.rest, h_head=(8, 0, 0), **d.ears_d(perk=0.6))), (0.88 * L, d.at(d.rest, h_head=(8, 0, 0), **d.ears_d(perk=0.6))),
              (L, d.rest)])
    syl = [(0.16, 0.3, 1.0), (0.34, 0.46, 0.8), (0.5, 0.66, 1.2), (0.72, 0.84, 0.9)]
    for k, (a, b, amt) in enumerate(syl):
        c.rot('h_head', (lambda a, b, amt, k: lambda t: (16 * amt * pulse(t, a * L, b * L), 0, (-1) ** k * 7 * pulse(t, a * L, b * L)))(a, b, amt, k), add=True)
        c.rot('jaw', (lambda a, b, amt: lambda t: (-20 * amt * pulse(t, a * L, b * L), 0, 0))(a, b, amt), add=True)
        c.rot('left_ear' if k % 2 else 'right_ear', (lambda a, b: lambda t: (10 * pulse(t, a * L, b * L), 0, 0))(a, b), add=True)
        d.snd(c, a * L + 0.02, 'voice.whine' if k % 2 else 'voice.ambient', 0.55 + 0.1 * amt, 0.95 + 0.12 * amt)
    c.rot('right_front_leg', lambda t: (22 * pulse(t, 0.7 * L, 0.84 * L), 0, 0), add=True)
    c.pos('body', lambda t: (0, -0.4 * pulse(t, 0.8 * L, 0.9 * L), 0), add=True)
    d.add_wag(c, 3.0, 14, 0.1 * L, 0.95 * L)
    return c


def sit_howl(d, name='sit_howl'):
    """Sitting, muzzle straight up at the sky: a long rising "awooo" (jaw opens in an O, throat stretched, ears laid
    back), a breath, a second shorter howl; the body rises with each one."""
    L = d.T(5.0)
    c = Clip(d.m, name, L, 'loop')
    up = d.at(d.sit, d.ears_d(flat=0.9), h_head=(64, 0, 0), body=(6, 0, 0), body_p=(0, 0.6 * d.s, 0))
    o1 = d.at(up, jaw=(-22, 0, 0))
    breath = d.at(d.sit, h_head=(28, 0, 0), **d.ears_d(flat=0.4))
    o2 = d.at(up, jaw=(-18, 0, 0), h_head=(-6, 0, 0))
    d.seq(c, [(0, d.sit), (0.12 * L, up), (0.18 * L, o1), (0.48 * L, o1), (0.56 * L, breath), (0.64 * L, up),
              (0.68 * L, o2), (0.84 * L, o2), (0.94 * L, d.sit), (L, d.sit)])
    c.rot('jaw', lambda t: (-3 * osc(t, 5) * (env(t, 0.2 * L, 0.48 * L, 0.1) + env(t, 0.68 * L, 0.84 * L, 0.1)), 0, 0), add=True)
    c.rot('h_head', lambda t: (4 * step(t, 0.18 * L, 0.46 * L) * env(t, 0.1 * L, 0.5 * L, 0.05), 0, 0), add=True)
    # the howl events are two rising whines: the main sound and its layer (12 / 10 ticks later), both synced here
    d.snd(c, 0.18 * L, 'ability_howl', 0.9, 1.0)
    d.snd(c, 0.18 * L + 0.6, 'ability_howl.layer', 0.9, 1.0)
    d.snd(c, 0.68 * L, 'ability_howlback', 0.7, 1.05)
    d.snd(c, 0.68 * L + 0.5, 'ability_howlback.layer', 0.7, 1.05)
    return c


def zoom_laps(d, name='zoom_laps', laps=2, R=None):
    """Zoomies on the spot: two tight flat-out laps around its own spot (run cycle on the legs, body leaning into the
    curve, ears pinned), a skid stop into a play bow and a panting grin."""
    L = d.T(3.4)
    c = Clip(d.m, name, L)
    R = R if R is not None else 9 * d.s
    t0, t1 = 0.06 * L, 0.6 * L
    run_len = d.m.length('run')
    # the run legs (and head / tail bob) while lapping, blended in and out of the rest pose
    run = Clip(d.m, '_run', L)
    d.loop_from(run, 'run', speed=0.85)
    bow = K.bow_pose(d, 22)
    d.seq(c, [(0, d.rest), (t0, d.rest), (t1, d.rest), (t1 + 0.08 * L, bow), (0.86 * L, bow), (L, d.rest)])
    for key, fn in run.tracks.items():
        if key[0] in ('root', 'body') and key[1] == 'position':
            continue
        base = c.tracks.get(key)
        rest = K.REST[key[1]]

        def mix(t, fn=fn, base=base, rest=rest):
            w = env(t, t0 - 0.04 * L, t1 + 0.02 * L, 0.06 * L)
            b = base(t) if base else rest
            return tuple(bb + (r - rr) * w for bb, r, rr in zip(b, fn(t), rest))
        c.tracks[key] = mix
    # the laps: a figure eight around its own spot (left circle, then right), facing along the path; the yaw goes
    # 0 -> 360 -> 0 so the clip ends facing where it started (no unwinding spin in the blend out)
    u = lambda t: keys(t, [(t0, 0.0), (t1, 1.0)], 'linear')

    def path(t):
        x = u(t) * 2
        if x <= 1:
            a = 2 * math.pi * x
            return (-R * (1 - math.cos(a)), -R * math.sin(a), math.degrees(a), 1)
        a = 2 * math.pi * (x - 1)
        return (R * (1 - math.cos(a)), -R * math.sin(a), 360 - math.degrees(a), -1)
    on = lambda t: step(t, t0 - 0.02 * L, t0 + 0.08 * L) * (1 - step(t, t1 - 0.06 * L, t1 + 0.04 * L))
    side = lambda t: path(t)[3] * on(t) * (1 - pulse(t, 0.5 * (t0 + t1) - 0.04 * L, 0.5 * (t0 + t1) + 0.04 * L))
    c.pos('root', lambda t: (path(t)[0], 0, path(t)[1]), add=True)
    c.rot('root', lambda t: (0, path(t)[2], 0), add=True)
    c.rot('body', lambda t: (0, 0, 14 * side(t)), add=True)
    c.rot('h_head', lambda t: (0, 16 * side(t), 0), add=True)
    c.rot('left_ear', lambda t: d.ear('left', flat=1.0 * on(t)), add=True)
    c.rot('right_ear', lambda t: d.ear('right', flat=1.0 * on(t)), add=True)
    # skid
    c.pos('body', lambda t: (0, -0.8 * d.s * pulse(t, t1 - 0.02 * L, t1 + 0.1 * L), 0), add=True)
    d.add_wag(c, 6.0, 30, t1, L - 0.04 * L)
    n = int(laps * 2 * (t1 - t0) / run_len / 2)
    for k in range(max(2, n)):
        d.snd(c, t0 + (t1 - t0) * (k + 0.3) / max(2, n), 'step', 0.6, 1.1)
    d.snd(c, t1 + 0.12 * L, 'voice.ambient', 0.7, 1.15)
    d.snd(c, 0.92 * L, 'voice.pant', 0.6, 1.0)
    return c


def clips(m):
    d = dog(m)
    out = K.shared(d)
    for fn in (talk, sit_howl, zoom_laps):
        c = fn(d)
        m.put(c)
        out.append(c)
    return out


TAGS = dict(K.TAGS, **{'sit_howl': {'p': 'sitting'}, 'zoom_laps': {'p': 'standing'}, 'talk': {'p': 'standing'}})


def IDLE(m):
    t = K.idle_tables(m, extra_standing=[
        {'clip': 'talk', 'w': 1.6, 'cd': 25, 'when': 'player_near:8', 'boost': {'owner_near:6': 2, 'happy': 1.5}},
        {'clip': 'zoom_laps', 'w': 0.5, 'cd': 90, 'when': '!night', 'boost': {'happy': 3, 'cold': 2}},
    ], extra_sitting=[
        {'clip': 'sit_howl', 'w': 2, 'min': m.length('sit_howl'), 'max': m.length('sit_howl'), 'cd': 75, 'when': 'night'},
    ])
    return t


PROCEDURAL = {'tail_sway': 6, 'ear_twitch': 1.2, 'breath': 0.014}
