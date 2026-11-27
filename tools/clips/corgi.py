"""Corgi: all butt. The fluffy rump wiggles when it is excited (stubby tail a blur), it play-bows at everything with
bouncy little hops, and it sploots: flat on its belly, short back legs stretched straight out behind like a frog.
Shared dog life from `_dogs.py`."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _dogs as K  # noqa: E402
from _dogs import Clip, env, keys, osc, pulse, wave  # noqa: E402


def butt_wiggle(d, name='butt_wiggle'):
    """The corgi butt wiggle: front paws planted and head steady on its person, the whole rear swinging side to side
    (hips swing, the back legs paddle in place), the stub tail a blur; it builds up and settles."""
    L = d.T(2.4)
    c = Clip(d.m, name, L)
    set_ = d.at(d.rest, d.pant(0.9), d.ears_d(perk=0.8), body_p=(0, -0.3, 0), h_head=(8, 0, 0))
    d.seq(c, [(0, d.rest), (0.1 * L, set_), (0.9 * L, set_), (L, d.rest)])
    hz = 3.2
    e = lambda t: env(t, 0.08 * L, 0.92 * L, 0.18 * L)
    # the body yaws about its middle: rear swings, the head counter-turns so it stays on target
    c.rot('body', lambda t: (0, 13 * osc(t, hz) * e(t), 6 * osc(t, hz, 0, 0.25) * e(t)), add=True)
    c.rot('h_head', lambda t: (0, -13 * osc(t, hz) * e(t), -5 * osc(t, hz, 0, 0.25) * e(t)), add=True)
    c.rot('left_front_leg', lambda t: (0, 13 * osc(t, hz) * e(t), 0), add=True)
    c.rot('right_front_leg', lambda t: (0, 13 * osc(t, hz) * e(t), 0), add=True)
    c.rot('left_back_leg', lambda t: (10 * max(0.0, osc(t, hz)) * e(t), 0, -8 * osc(t, hz) * e(t)), add=True)
    c.rot('right_back_leg', lambda t: (10 * max(0.0, -osc(t, hz)) * e(t), 0, -8 * osc(t, hz) * e(t)), add=True)
    c.pos('body', lambda t: (0.5 * osc(t, hz) * e(t), 0.3 * abs(osc(t, hz)) * e(t), 0), add=True)
    d.add_wag(c, 9.0, 34, 0.06 * L, 0.94 * L)
    d.snd(c, 0.2 * L, 'voice.pant', 0.6, 1.0)
    d.snd(c, 0.7 * L, 'voice.ambient', 0.45, 1.2)
    return c


def sploot_pose(d):
    """Flat on its belly, front legs forward, back legs straight out behind, chin up."""
    drop = -(d.m.pivot['body'][1] - 2.0) * 0.82
    return d.at(d.rest, body_p=(0, drop, 0.4), left_front_leg=(78, 0, -4), right_front_leg=(78, 0, 4),
                left_back_leg=(-84, 0, -12), right_back_leg=(-84, 0, 12), h_head=(6, 0, 0),
                left_back_leg_p=(0, 0.2, 0.6), right_back_leg_p=(0, 0.2, 0.6))


def sploot(d, name='sploot'):
    """The sploot (lying loop): flat, frog legs behind, slow breaths, head slowly turning to watch, the odd ear flick
    and a lazy tail stub wag."""
    L = 6.0
    c = Clip(d.m, name, L, 'loop')
    p = sploot_pose(d)
    d.seq(c, [(0, p), (L, p)])
    c.scl('body', lambda t: (1 + 0.015 * wave(t, L, 2), 1 + 0.04 * wave(t, L, 2), 1), add=True)
    c.rot('h_head', lambda t: (2 * wave(t, L, 2, 0.1), keys(t, [(0, 0), (0.2 * L, 22), (0.45 * L, 22), (0.65 * L, -14), (0.85 * L, -14), (L, 0)]), 0), add=True)
    c.rot('left_ear', lambda t: (0, 0, 14 * pulse(t, 0.5 * L, 0.54 * L)), add=True)
    d.add_wag(c, 2 / L, 8)
    return c


def sploot_flop(d, name='sploot_flop'):
    """Standing to sploot: front legs slide out, the rump drops with the back legs kicking straight out behind, a
    contented flat rest with a sigh, then it pushes back up like a little seal."""
    L = d.T(4.6)
    c = Clip(d.m, name, L)
    p = sploot_pose(d)
    half = d.at(d.rest, body=(-10, 0, 0), body_p=(0, -1.0, 0), left_front_leg=(40, 0, 0), right_front_leg=(40, 0, 0))
    push = d.at(d.rest, body=(12, 0, 0), body_p=(0, -1.2, 0), left_back_leg=(-30, 0, 0), right_back_leg=(-30, 0, 0))
    d.seq(c, [(0, d.rest), (0.1 * L, half), (0.2 * L, p), (0.76 * L, p), (0.86 * L, push), (L, d.rest)])
    c.scl('body', lambda t: (1, 1 + 0.06 * pulse(t, 0.3 * L, 0.48 * L), 1), add=True)
    c.rot('h_head', lambda t: (-8 * pulse(t, 0.44 * L, 0.6 * L), 16 * pulse(t, 0.5 * L, 0.74 * L), 0), add=True)
    d.add_wag(c, 4.0, 20, 0.22 * L, 0.74 * L)
    d.snd(c, 0.2 * L, 'minecraft:entity.wolf.step', 0.5, 1.3)
    d.snd(c, 0.46 * L, 'ambient_sleepy', 0.4, 1.0)
    return c


def sit_lean(d, name='sit_lean'):
    """The lopsided corgi sit: slumps onto one hip, one back leg sticking out, head tilted, tongue out."""
    L = d.T(3.0)
    c = Clip(d.m, name, L, 'loop')
    slump = d.at(d.sit, d.pant(0.8), body=(0, 0, -10), body_p=(-0.4, -0.3, 0), h_head=(2, 0, 10),
                 right_back_leg=(-20, 0, 26))
    d.seq(c, [(0, d.sit), (0.16 * L, slump), (0.84 * L, slump), (L, d.sit)])
    d.add_wag(c, 4 / L, 16)
    return c


def clips(m):
    d = K.Dog(m, voice='entity.wolf_cute', vp=1.32, wag='y', ears='up', wag_amp=1.2)
    out = K.shared(d, play_bow={'bounces': 3, 'depth': 18}, idle_wag={'body': 0.6})
    for fn in (butt_wiggle, sploot, sploot_flop, sit_lean):
        c = fn(d)
        m.put(c)
        out.append(c)
    return out


TAGS = dict(K.TAGS, **{'butt_wiggle': {'p': 'standing'}, 'sploot': {'p': 'lying'}, 'sploot_flop': {'p': 'standing'},
                       'sit_lean': {'p': 'sitting'}})


def IDLE(m):
    t = K.idle_tables(m, extra_standing=[
        {'clip': 'butt_wiggle', 'w': 1.8, 'cd': 22, 'when': 'player_near:8', 'boost': {'owner_near:6': 2, 'happy': 2}},
        {'clip': 'sploot_flop', 'w': 1.0, 'cd': 50, 'boost': {'hot': 3}},
    ], extra_sitting=[
        {'clip': 'sit_lean', 'w': 1.2, 'min': m.length('sit_lean'), 'max': m.length('sit_lean') * 2, 'cd': 25},
    ], extra_lying=[
        {'clip': 'sploot', 'w': 1.5, 'min': 12, 'max': 30, 'cd': 60, 'when': '!night'},
    ])
    for e in t['standing']:
        if e['clip'] == 'play_bow':
            e['w'] *= 2                         # corgis play-bow at everything
            e['when'] = 'player_near:8,!night'
    return t


PROCEDURAL = {'tail_sway': 10, 'ear_twitch': 1.4, 'breath': 0.014}

# short legs: it breaks into its scampering gallop well before the long-legged dogs do
GAIT = {'run_on': 0.17, 'run_off': 0.13}
