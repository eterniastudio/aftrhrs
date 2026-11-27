"""Golden Retriever: the goof. Its tail wags the whole dog (hips swing with every wag), it proudly prances over with
"something" in its mouth and presents it, and it flops over sideways for no reason at all, legs paddling. Shared
dog life from `_dogs.py`."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _dogs as K  # noqa: E402
from _dogs import Clip, env, osc, pulse, step  # noqa: E402


def present(d, name='present'):
    """Bringing you something: head held high and proud, mouth closed on its "prize", a bouncy high-stepping prance
    on the spot, then it drops it at your feet (jaw opens, head dips), backs into a play bow and barks: throw it!"""
    L = d.T(3.6)
    c = Clip(d.m, name, L)
    proud = d.at(d.rest, d.ears_d(perk=0.5), h_head=(18, 0, 0), jaw=(2, 0, 0), body=(4, 0, 0))
    drop = d.at(d.rest, d.ears_d(perk=0.8), h_head=(-30, 0, 0), jaw=(-16, 0, 0), body=(-4, 0, 0),
                left_front_leg=(6, 0, 0), right_front_leg=(6, 0, 0))
    bow = K.bow_pose(d, 22)
    d.seq(c, [(0, d.rest), (0.1 * L, proud), (0.5 * L, proud), (0.58 * L, drop), (0.66 * L, drop), (0.76 * L, bow),
              (0.9 * L, bow), (L, d.rest)])
    # the prance: high steps, diagonal pairs, the body bobbing (four steps)
    p0, p1 = 0.1 * L, 0.5 * L
    hz = 4 / (p1 - p0)
    pr = lambda t: env(t, p0, p1, 0.05 * L)
    for leg, ph in (('left_front_leg', 0.0), ('right_back_leg', 0.0), ('right_front_leg', 0.5), ('left_back_leg', 0.5)):
        amp = 34 if 'front' in leg else -18
        c.rot(leg, (lambda amp, ph: lambda t: (amp * max(0.0, osc(t, hz / 2, p0, ph)) * pr(t), 0, 0))(amp, ph), add=True)
        lo = d.lower.get(leg)
        if lo:
            c.rot(lo, (lambda ph, s: lambda t: (s * 40 * max(0.0, osc(t, hz / 2, p0, ph)) * pr(t), 0, 0))(ph, 1 if 'back' in leg else -1), add=True)
    c.pos('body', lambda t: (0, 0.7 * d.s * abs(osc(t, hz / 2, p0)) * pr(t), 0), add=True)
    c.rot('h_head', lambda t: (3 * osc(t, hz, p0) * pr(t), 6 * osc(t, hz / 4, p0) * pr(t), 0), add=True)
    c.rot('body', lambda t: (0, 6 * osc(t, hz / 2, p0, 0.25) * pr(t), 0), add=True)
    d.add_wag(c, 4.0, 30, 0.04 * L, 0.96 * L, lift=10)
    for k in range(4):
        d.snd(c, p0 + (k + 0.5) / hz, 'step', 0.5, 1.0)
    d.snd(c, 0.58 * L, 'minecraft:entity.item.pickup', 0.35, 0.7)
    d.snd(c, 0.8 * L, 'voice.ambient', 0.8, 1.1)
    return c


def goofy_flop(d, name='goofy_flop'):
    """Flops over onto its side out of pure happiness: tongue out, legs paddling the air, a wriggle on its back, then
    rolls back up and shakes its ears."""
    L = d.T(3.8)
    c = Clip(d.m, name, L)
    down = d.at(d.sleep, d.pant(1.0))
    side = d.at(down, body=(0, 0, 80), body_p=(0, -0.5 * d.s, 0), h_head=(10, 0, -40),
                left_front_leg=(-60, 0, 30), right_front_leg=(-50, 0, -20), left_back_leg=(-60, 0, 30), right_back_leg=(-40, 0, -20),
                **d.ears_d(out=1.0))
    side2 = d.at(side, body=(0, 0, 20), h_head=(0, 0, -15))
    d.seq(c, [(0, d.rest), (0.16 * L, down), (0.26 * L, side), (0.5 * L, side2), (0.62 * L, side), (0.74 * L, down),
              (0.86 * L, d.rest), (L, d.rest)])
    w = lambda t: env(t, 0.26 * L, 0.66 * L, 0.06 * L)
    for leg, ph in (('left_front_leg', 0.0), ('right_front_leg', 0.5), ('left_back_leg', 0.25), ('right_back_leg', 0.75)):
        c.rot(leg, (lambda ph: lambda t: (24 * osc(t, 3.2, 0, ph) * w(t), 0, 0))(ph), add=True)
    c.rot('body', lambda t: (0, 0, 10 * osc(t, 1.6, 0.26 * L) * w(t)), add=True)
    c.rot('h_head', lambda t: (0, 0, 12 * osc(t, 1.6, 0.26 * L, 0.1) * w(t) + 10 * osc(t, 5) * env(t, 0.86 * L, L, 0.04)), add=True)
    d.add_wag(c, 4.5, 26, 0.2 * L, 0.7 * L)
    d.snd(c, 0.2 * L, 'minecraft:entity.wolf.step', 0.6, 0.7)
    d.snd(c, 0.4 * L, 'voice.pant', 0.7, 1.0)
    d.snd(c, 0.88 * L, 'minecraft:entity.wolf.shake', 0.4, 1.3)
    return c


def sit_offer_paw(d, name='sit_offer_paw'):
    """Sitting: offers a paw, then the other one, head tilting hopefully, tail sweeping the floor (it knows tricks)."""
    L = d.T(3.2)
    c = Clip(d.m, name, L, 'loop')
    base = d.at(d.sit, d.pant(0.5), d.ears_d(perk=0.4))
    lo = lambda leg, a: {d.lower[leg]: (a, 0, 0)} if leg in d.lower else {}
    pawL = d.at(base, lo('left_front_leg', -50), left_front_leg=(70, 0, 6), h_head=(6, 0, 12), body=(0, 0, -4))
    pawR = d.at(base, lo('right_front_leg', -50), right_front_leg=(70, 0, -6), h_head=(6, 0, -12), body=(0, 0, 4))
    d.seq(c, [(0, base), (0.12 * L, pawL), (0.36 * L, pawL), (0.48 * L, base), (0.56 * L, pawR), (0.8 * L, pawR),
              (0.92 * L, base), (L, base)])
    d.add_wag(c, 8 / L, 22)
    return c


def clips(m):
    d = K.Dog(m, voice='entity.wolf', vp=0.95, wag='y', ears='drop', wag_amp=1.25)
    out = K.shared(d, idle_wag={'body': 1.0, 'L': 2.0}, pet={'wiggle': 2.0})
    for fn in (present, goofy_flop, sit_offer_paw):
        c = fn(d)
        m.put(c)
        out.append(c)
    return out


TAGS = dict(K.TAGS, **{'present': {'p': 'standing'}, 'goofy_flop': {'p': 'standing'}, 'sit_offer_paw': {'p': 'sitting'}})


def IDLE(m):
    return K.idle_tables(m, extra_standing=[
        {'clip': 'present', 'w': 1.4, 'cd': 40, 'when': 'owner_near:8', 'boost': {'happy': 2}},
        {'clip': 'goofy_flop', 'w': 0.7, 'cd': 60, 'when': '!rain', 'boost': {'happy': 2.5, 'owner_near:6': 1.5}},
    ], extra_sitting=[
        {'clip': 'sit_offer_paw', 'w': 1.2, 'min': m.length('sit_offer_paw'), 'max': m.length('sit_offer_paw') * 2, 'cd': 30,
         'when': 'owner_near:6'},
    ])


PROCEDURAL = {'tail_sway': 9, 'ear_twitch': 0.8, 'breath': 0.014}
