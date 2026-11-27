"""Saint Bernard: big, slow and heavy. Everything takes its time (tempo 1.35), every move has weight: it flops down
with a huge sigh, sits with a deep slow breath, and its signature move is the drool shake, a head shake that sets
the jowls and ears flapping and sends slobber flying. Shared dog life from `_dogs.py`."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _dogs as K  # noqa: E402
from _dogs import Clip, decay, env, osc, pulse  # noqa: E402


def drool_shake(d, name='drool_shake'):
    """A slow-starting, then wild head shake: jowls (jaw) flapping open, ears flying out, the body following a beat
    late, slobber noises; it ends with a lick of the chops and a satisfied huff."""
    L = d.T(2.6)
    c = Clip(d.m, name, L)
    brace = d.at(d.rest, h_head=(-10, 0, 0), body_p=(0, -0.4, 0))
    d.seq(c, [(0, d.rest), (0.12 * L, brace), (0.7 * L, brace), (L, d.rest)])
    s0, s1 = 0.14 * L, 0.66 * L
    hz = 4.0
    e = lambda t: env(t, s0, s1, 0.18 * L) * (0.6 + 0.4 * (t - s0) / (s1 - s0) if s0 < t < s1 else 0)
    c.rot('h_head', lambda t: (0, 16 * osc(t, hz, s0) * e(t), 34 * osc(t, hz, s0) * e(t) + 10 * decay(t, s1, 1.6, 4)), add=True)
    c.rot('jaw', lambda t: (-12 * abs(osc(t, hz, s0, 0.2)) * e(t) - 8 * pulse(t, 0.78 * L, 0.9 * L), 0, 0), add=True)
    c.rot('body', lambda t: (0, 0, 7 * osc(t, hz, s0, -0.2) * e(t)), add=True)
    for side, sg in (('left', -1), ('right', 1)):
        c.rot(side + '_ear', (lambda sg: lambda t: (8 * osc(t, hz * 2, s0) * e(t), 0, sg * (40 * osc(t, hz, s0, -0.3) * e(t) + 14 * decay(t, s1, 2.2, 4))))(sg), add=True)
    for i, b in enumerate(d.tails):
        c.rot(b, (lambda i: lambda t: d.wag(14 * osc(t, hz, s0, -0.35 - 0.1 * i) * e(t)))(i), add=True)
    if d.m.has_bone('barrel'):
        c.rot('barrel', lambda t: (0, 0, 22 * osc(t, hz, s0, -0.4) * e(t) + 12 * decay(t, s1, 1.8, 3)), add=True)
    for k in range(5):
        d.snd(c, s0 + (k + 0.4) * (s1 - s0) / 5, 'minecraft:entity.slime.squish_small', 0.35, 0.8 + 0.06 * k)
    d.snd(c, 0.3 * L, 'minecraft:block.honey_block.slide', 0.3, 1.2)
    d.snd(c, 0.84 * L, 'minecraft:entity.horse.breathe', 0.45, 0.6)
    return c


def flop_sigh(d, name='flop_sigh'):
    """Too much effort to stand: lowers its front, then its rear, flops onto its belly with a big chuff, rests its chin
    and lets out an enormous sigh, then heaves itself back up."""
    L = d.T(5.2)
    c = Clip(d.m, name, L)
    front = d.at(d.rest, *[K.Dog.planted(d, -16, back='vertical', front='reach')[0]],
                 body=(-16, 0, 0), body_p=(0, K.Dog.planted(d, -16)[1], 0), h_head=(10, 0, 0))
    down = d.at(d.sleep, body=(0, 0, -d.sleep.get(('body', 'rotation'), (0, 0, 0))[2]), h_head=(0, -d.sleep.get(('h_head', 'rotation'), (0, 0, 0))[1], -d.sleep.get(('h_head', 'rotation'), (0, 0, 0))[2]))
    chin = d.at(down, h_head=(-12, 0, 0), jaw=(0, 0, 0), **d.ears_d(flat=0.3))
    puff = d.at(chin, jaw=(-8, 0, 0))
    d.seq(c, [(0, d.rest), (0.12 * L, front), (0.22 * L, down), (0.3 * L, chin), (0.5 * L, chin), (0.58 * L, puff),
              (0.66 * L, chin), (0.78 * L, chin), (0.86 * L, front), (L, d.rest)])
    c.pos('body', lambda t: (0, -0.8 * decay(t, 0.22 * L, 2.0, 5) ** 2, 0), add=True)
    c.scl('body', lambda t: (1, 1 + 0.06 * pulse(t, 0.38 * L, 0.56 * L) - 0.03 * pulse(t, 0.56 * L, 0.7 * L), 1), add=True)
    d.add_wag(c, 1.2, 8, 0.3 * L, 0.78 * L)
    d.snd(c, 0.22 * L, 'minecraft:entity.wolf.step', 0.9, 0.55)
    d.snd(c, 0.56 * L, 'minecraft:entity.horse.breathe', 0.7, 0.55)
    return c


def sit_heavy_breath(d, name='sit_heavy_breath'):
    """Sitting like a mountain: slow, deep breaths that lift the whole chest, jowls puffing on the out-breath, the
    barrel swaying, eyes on its person."""
    L = d.T(4.0)
    c = Clip(d.m, name, L, 'loop')
    base = d.at(d.sit, d.ears_d(flat=0.2))
    d.seq(c, [(0, base), (L, base)])
    c.scl('body', lambda t: (1 + 0.02 * osc(t, 2 / L), 1 + 0.045 * osc(t, 2 / L), 1 + 0.02 * osc(t, 2 / L)), add=True)
    c.rot('h_head', lambda t: (3 * osc(t, 2 / L, 0, 0.1), 8 * osc(t, 1 / L), 0), add=True)
    c.rot('jaw', lambda t: (-5 * max(0.0, osc(t, 2 / L, 0, 0.5)), 0, 0), add=True)
    if d.m.has_bone('barrel'):
        c.rot('barrel', lambda t: (0, 0, 4 * osc(t, 1 / L, 0, 0.3)), add=True)
    d.add_wag(c, 1 / L, 10)
    d.snd(c, 0.6 * L, 'minecraft:entity.horse.breathe', 0.25, 0.55)
    return c


def clips(m):
    d = K.Dog(m, voice='entity.wolf_big', vp=0.72, wag='y', ears='drop', tempo=1.35)
    out = K.shared(d, play_bow={'bounces': 1, 'depth': 18}, shake={'amp': 0.9}, bond_level={'height': 5.0},
                   idle_wag={'body': 0.4})
    for fn in (drool_shake, flop_sigh, sit_heavy_breath):
        c = fn(d)
        m.put(c)
        out.append(c)
    return out


TAGS = dict(K.TAGS, **{'drool_shake': {'p': 'standing'}, 'flop_sigh': {'p': 'standing'}, 'sit_heavy_breath': {'p': 'sitting'}})


def IDLE(m):
    t = K.idle_tables(m, extra_standing=[
        {'clip': 'drool_shake', 'w': 1.5, 'cd': 30, 'boost': {'hot': 2}},
        {'clip': 'flop_sigh', 'w': 1.0, 'cd': 60, 'boost': {'hot': 2, 'night': 1.5}},
    ], extra_sitting=[
        {'clip': 'sit_heavy_breath', 'w': 3, 'min': m.length('sit_heavy_breath'), 'max': m.length('sit_heavy_breath') * 3, 'cd': 12},
        # its notice is a head-only overlay that would snap the sitting head level: not while sitting
        {'clip': 'notice', 'w': 0, 'replace': True},
    ])
    # a heavy dog does fewer acrobatics
    for e in t['standing']:
        if e['clip'] in ('play_bow', 'roll_over'):
            e['w'] *= 0.5
    return t


PROCEDURAL = {'tail_sway': 5, 'ear_twitch': 0.6, 'breath': 0.018}
