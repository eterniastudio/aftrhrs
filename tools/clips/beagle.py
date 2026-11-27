"""Beagle: a nose with a dog attached. It works scent trails with its nose glued to the ground (long ears dragging,
white-tipped flag tail straight up), and when it finds something it throws its head back and bays. Shared dog
life from `_dogs.py`."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _dogs as K  # noqa: E402
from _dogs import Clip, env, keys, osc, pulse  # noqa: E402


def trail_sniff(d, name='trail_sniff'):
    """On a trail: nose to the ground, quartering left and right along the scent with quick snuffles, little
    shuffling steps forward, ears dragging, the flag tail up and ticking; at the end the head snaps up: got it."""
    L = d.T(4.6)
    c = Clip(d.m, name, L)
    pitch = -9
    legs, dy = d.planted(pitch, back='vertical', front='reach_back')
    down = d.at(d.rest, legs, body=(pitch, 0, 0), body_p=(0, dy, 0), h_head=(-44 - pitch, 0, 0),
                left_ear=d.ear('left', perk=1.0), right_ear=d.ear('right', perk=1.0))
    flag = {(b, 'rotation'): (-10 * (1 + i), 0, 0) for i, b in enumerate(d.tails)}
    down = d.at(down, flag)
    alert = d.at(d.rest, flag, h_head=(14, 0, 0), **d.ears_d(perk=0.8))
    d.seq(c, [(0, d.rest), (0.1 * L, down), (0.82 * L, down), (0.88 * L, alert), (0.96 * L, alert), (L, d.rest)])
    sweep = lambda t: keys(t, [(0.1 * L, 0), (0.25 * L, 26), (0.42 * L, -22), (0.58 * L, 20), (0.74 * L, -14), (0.82 * L, 0)])
    c.rot('h_head', lambda t: (0, sweep(t), 0.3 * sweep(t)), add=True)
    c.rot('body', lambda t: (0, 0.25 * sweep(t), 0), add=True)
    for k in range(6):
        a = 0.13 + k * 0.115
        c.rot('h_head', (lambda a: lambda t: (3.5 * osc(t, 8.0) * env(t, a * L, (a + 0.07) * L, 0.05), 0, 0))(a), add=True)
        d.snd(c, a * L + 0.02, 'minecraft:entity.fox.sniff', 0.45, 1.25 + 0.05 * (k % 2))
    # shuffling steps (diagonal pairs) while it works the line
    for leg, ph in (('left_front_leg', 0.0), ('right_back_leg', 0.0), ('right_front_leg', 0.5), ('left_back_leg', 0.5)):
        amp = 16 if 'front' in leg else 10
        c.rot(leg, (lambda amp, ph: lambda t: (amp * max(0.0, osc(t, 1.4, 0.1 * L, ph)) * env(t, 0.14 * L, 0.8 * L, 0.06 * L), 0, 0))(amp, ph), add=True)
    for side, sg in (('left', -1), ('right', 1)):
        c.rot(side + '_ear', (lambda sg: lambda t: (0, 0, sg * 0.25 * sweep(t)))(sg), add=True)
    d.add_wag(c, 2.2, 10, 0.1 * L, 0.9 * L)
    d.snd(c, 0.86 * L, 'voice.ambient', 0.5, 1.15)
    return c


def bay(d, name='bay', base=None, loop='once'):
    """The beagle bay: braces, throws its head back and lets out a long "a-roo-roo-rooo" (jaw pulsing, ears swinging
    back, tail rigid as a flag), then a final short woof."""
    base = d.rest if base is None else base
    L = d.T(3.0)
    c = Clip(d.m, name, L, loop)
    flag = {(b, 'rotation'): (-14 * (1 + i), 0, 0) for i, b in enumerate(d.tails)}
    brace = d.at(base, flag, body_p=(0, -0.4, 0), h_head=(-6, 0, 0))
    up = d.at(base, flag, d.ears_d(flat=1.0, out=0.3), h_head=(48, 0, 0), jaw=(-20, 0, 0), body=(5, 0, 0))
    d.seq(c, [(0, base), (0.1 * L, brace), (0.2 * L, up), (0.72 * L, up), (0.8 * L, d.at(brace, jaw=(-14, 0, 0))),
              (0.86 * L, brace), (L, base)])
    for k, (a, b) in enumerate(((0.22, 0.36), (0.36, 0.5), (0.5, 0.7))):
        c.rot('jaw', (lambda a, b: lambda t: (8 * pulse(t, a * L, b * L), 0, 0))(a, b), add=True)
        c.rot('h_head', (lambda a, b, k: lambda t: (-5 * pulse(t, a * L, b * L) + 3 * k * pulse(t, a * L, b * L), 0, 0))(a, b, k), add=True)
    c.rot('left_ear', lambda t: (0, 0, -10 * osc(t, 3.0) * env(t, 0.2 * L, 0.72 * L, 0.1)), add=True)
    c.rot('right_ear', lambda t: (0, 0, 10 * osc(t, 3.0) * env(t, 0.2 * L, 0.72 * L, 0.1)), add=True)
    d.snd(c, 0.2 * L, 'minecraft:entity.wolf_sad.whine', 0.8, 1.0)
    d.snd(c, 0.42 * L, 'minecraft:entity.wolf_sad.whine', 0.7, 1.1)
    d.snd(c, 0.82 * L, 'voice.ambient', 0.7, 1.0)
    return c


def sit_bay(d):
    return bay(d, 'sit_bay', d.sit, 'loop')


def clips(m):
    d = K.Dog(m, voice='entity.wolf', vp=1.12, wag='z', ears='drop', wag_amp=0.9)
    out = K.shared(d, sniff={'low': 1.3, 'sweep': 20})
    for fn in (trail_sniff, bay, sit_bay):
        c = fn(d)
        m.put(c)
        out.append(c)
    return out


TAGS = dict(K.TAGS, **{'trail_sniff': {'p': 'standing'}, 'bay': {'p': 'standing'}, 'sit_bay': {'p': 'sitting'}})


def IDLE(m):
    t = K.idle_tables(m, extra_standing=[
        {'clip': 'trail_sniff', 'w': 2.5, 'cd': 22, 'boost': {'alone': 1.5, 'day': 1.3}},
        {'clip': 'bay', 'w': 0.7, 'cd': 60, 'boost': {'monster_near': 4, 'night': 1.5}},
    ], extra_sitting=[
        {'clip': 'sit_bay', 'w': 0.8, 'min': m.length('sit_bay'), 'max': m.length('sit_bay'), 'cd': 70,
         'boost': {'night': 2, 'monster_near': 4}},
    ])
    for e in t['standing']:
        if e['clip'] == 'sniff':
            e['w'] *= 1.6                      # the nose is always working
    return t


PROCEDURAL = {'tail_sway': 7, 'ear_twitch': 0.7, 'breath': 0.013}
