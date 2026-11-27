"""Dachshund: the long noodle badger hound. Its excitement travels the whole length of it (head one way, body the
other, tail the first way again: a long-body wiggle), and it digs: nose down, front paws scrabbling like mad, dirt
flying, then a sneeze. Shared dog life from `_dogs.py`."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _dogs as K  # noqa: E402
from _dogs import Clip, env, osc, pulse  # noqa: E402


def long_wiggle(d, name='long_wiggle'):
    """A wave down the noodle: head, body and tail swing in alternating directions (an S that travels from nose to
    tail), the front and back legs stepping in place to keep up, ears flapping."""
    L = d.T(2.2)
    c = Clip(d.m, name, L)
    base = d.at(d.rest, d.pant(0.8), d.ears_d(perk=0.4))
    d.seq(c, [(0, d.rest), (0.1 * L, base), (0.9 * L, base), (L, d.rest)])
    hz = 2.6
    e = lambda t: env(t, 0.06 * L, 0.94 * L, 0.18 * L)
    c.rot('h_head', lambda t: (3 * osc(t, 2 * hz) * e(t), 22 * osc(t, hz) * e(t), 8 * osc(t, hz) * e(t)), add=True)
    c.rot('body', lambda t: (0, -11 * osc(t, hz, 0, 0.12) * e(t), -6 * osc(t, hz, 0, 0.12) * e(t)), add=True)
    c.pos('body', lambda t: (0.6 * osc(t, hz, 0, 0.12) * e(t), 0.25 * abs(osc(t, hz)) * e(t), 0), add=True)
    for leg, ph in (('left_front_leg', 0.0), ('right_front_leg', 0.5), ('left_back_leg', 0.62), ('right_back_leg', 0.12)):
        c.rot(leg, (lambda ph: lambda t: (14 * max(0.0, osc(t, hz, 0, ph)) * e(t), 0, 6 * osc(t, hz, 0, 0.12) * e(t)))(ph), add=True)
    for side, sg in (('left', -1), ('right', 1)):
        c.rot(side + '_ear', (lambda sg: lambda t: (0, 0, sg * 14 * osc(t, hz, 0, -0.2) * e(t)))(sg), add=True)
    d.add_wag(c, hz, 34, 0.04 * L, 0.96 * L)
    d.snd(c, 0.25 * L, 'voice.pant', 0.6, 1.0)
    return c


def dig(d, name='dig'):
    """Digging: plants the back legs, nose to the dirt, front paws scrabbling in a fast alternating blur (dirt
    sounds on every scoop), a look into the hole, a second burst, then it lifts its head and sneezes."""
    L = d.T(4.2)
    c = Clip(d.m, name, L)
    pitch = -14
    legs, dy = d.planted(pitch, back='vertical', front='reach')
    dn = d.at(d.rest, legs, body=(pitch, 0, 0), body_p=(0, dy, 0), h_head=(-30 - pitch, 0, 0), **d.ears_d(perk=0.6))
    look = d.at(dn, h_head=(-14, 0, 12))
    sneeze = d.at(d.rest, h_head=(16, 0, 0), jaw=(-10, 0, 0), **d.ears_d(out=0.6))
    d.seq(c, [(0, d.rest), (0.1 * L, dn), (0.42 * L, dn), (0.5 * L, look), (0.56 * L, dn), (0.8 * L, dn), (0.86 * L, sneeze),
              (0.9 * L, d.at(d.rest, h_head=(-14, 0, 0))), (L, d.rest)])
    hz = 6.0
    bursts = ((0.12, 0.42), (0.56, 0.8))
    burst = lambda t: sum(env(t, a * L, b * L, 0.05) for a, b in bursts)
    for leg, ph in (('left_front_leg', 0.0), ('right_front_leg', 0.5)):
        c.rot(leg, (lambda ph: lambda t: (34 * osc(t, hz, 0, ph) * burst(t), 0, 0))(ph), add=True)
        c.pos(leg, (lambda ph: lambda t: (0, 0.5 * max(0.0, osc(t, hz, 0, ph + 0.25)) * burst(t), 0))(ph), add=True)
    c.rot('h_head', lambda t: (3 * osc(t, 2 * hz) * burst(t), 0, 0), add=True)
    c.rot('body', lambda t: (0, 0, 2.5 * osc(t, hz) * burst(t)), add=True)
    d.add_wag(c, 4.0, 24, 0.1 * L, 0.84 * L, lift=6)
    for a, b in bursts:
        n = int((b - a) * L * hz / 2)
        for k in range(n):
            d.snd(c, a * L + (2 * k + 0.5) / hz, 'minecraft:block.rooted_dirt.hit' if k % 2 else 'minecraft:block.gravel.hit',
                  0.35, 1.2 + 0.08 * (k % 3))
    d.snd(c, 0.86 * L, 'minecraft:entity.panda.sneeze', 0.45, 1.6)
    return c


def sit_beg(d, name='sit_beg'):
    """Sitting up on its haunches like a little meerkat (the dachshund beg), front paws tucked, swaying for balance."""
    L = d.T(3.0)
    c = Clip(d.m, name, L, 'loop')
    up = d.at(d.sit, body=(34, 0, 0), body_p=(0, 1.0, 0.6), h_head=(-30, 0, 0), left_front_leg=(40, 0, 4), right_front_leg=(40, 0, -4),
              **d.ears_d(perk=0.6))
    d.seq(c, [(0, d.sit), (0.2 * L, up), (0.8 * L, up), (L, d.sit)])
    c.rot('body', lambda t: (3 * osc(t, 2 / L) * env(t, 0.2 * L, 0.8 * L, 0.1 * L), 0, 4 * osc(t, 1 / L) * env(t, 0.2 * L, 0.8 * L, 0.1 * L)), add=True)
    c.rot('left_front_leg', lambda t: (10 * pulse(t, 0.3 * L, 0.45 * L), 0, 0), add=True)
    c.rot('right_front_leg', lambda t: (10 * pulse(t, 0.5 * L, 0.65 * L), 0, 0), add=True)
    d.add_wag(c, 6 / L, 18)
    return c


def clips(m):
    d = K.Dog(m, voice='entity.wolf_puglin', vp=1.28, wag='y', ears='drop')
    out = K.shared(d, play_bow={'bounces': 2, 'depth': 16}, idle_wag={'body': 0.5})
    for fn in (long_wiggle, dig, sit_beg):
        c = fn(d)
        m.put(c)
        out.append(c)
    return out


TAGS = dict(K.TAGS, **{'long_wiggle': {'p': 'standing'}, 'dig': {'p': 'standing'}, 'sit_beg': {'p': 'sitting'}})


def IDLE(m):
    return K.idle_tables(m, extra_standing=[
        {'clip': 'long_wiggle', 'w': 1.6, 'cd': 22, 'when': 'player_near:8', 'boost': {'owner_near:6': 2, 'happy': 2}},
        {'clip': 'dig', 'w': 1.3, 'cd': 40, 'when': '!in_water', 'boost': {'alone': 1.5}},
    ], extra_sitting=[
        {'clip': 'sit_beg', 'w': 1.0, 'min': m.length('sit_beg'), 'max': m.length('sit_beg') * 2, 'cd': 30, 'when': 'owner_near:6'},
    ])


PROCEDURAL = {'tail_sway': 8, 'ear_twitch': 0.8, 'breath': 0.013}

# short legs: it breaks into its scampering gallop well before the long-legged dogs do
GAIT = {'run_on': 0.17, 'run_off': 0.13}
