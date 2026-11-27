"""Lurefin (untameable deep-sea anglerfish).

Phase 2: in water it only ever shows its idle_swim mood (the idle director keeps flourishes for calm moods), so the
life is in that loop: a long lure-bob loop (the lure sways and dips, the bulb swings and pulses, the jaw hangs slack
and now and then snaps, the fins scull). The swim gets a sinuous body / tail wave with the lure trailing; the wild
state machine adds a stalk (lure held out front, slow glide), a threat (jaw gape, fins flared, lure flash), a lure
dangle and a jaw snap."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, pulse, wave  # noqa: E402
from _p2kit import keep, copy_from, env, frozen, pos, rot, scl  # noqa: E402


def lure(c, L, amp=1.0, cycles=2):
    rot(c, 'lure', lambda t: (amp * (6 * wave(t, L, cycles) - 4), 0, amp * 4 * wave(t, L, 1, 0.3)))
    rot(c, 'lure_tip', lambda t: (amp * 10 * wave(t, L, cycles, -0.12), 0, 0))
    rot(c, 'lure_bulb', lambda t: (amp * 16 * wave(t, L, cycles, -0.25), 0, amp * 12 * wave(t, L, 1, 0.1)))
    scl(c, 'lure_bulb', lambda t: (0.15 * pulse(t % (L / cycles), 0.0, L / cycles * 0.4),) * 3)


def clips(m):
    out = []
    idle_swim, swim = frozen(m, 'idle_swim'), frozen(m, 'swim')

    # ---- idle_swim (replaces the generated one): lure bobbing, slack jaw, a snap now and then, sculling fins
    L = 8.0
    c = Clip(m, 'idle_swim', L, 'loop')
    copy_from(c, idle_swim, loop=True, skip={'lure', 'lure_tip', 'lure_bulb', 'jaw'})
    pos(c, 'body', lambda t: (0, 0.8 * wave(t, L, 2), 0))
    rot(c, 'body', lambda t: (2 * wave(t, L, 2, 0.25), 6 * wave(t, L, 1), 0))
    lure(c, L, 1.3, 4)
    rot(c, 'jaw', lambda t: (-6 - 3 * wave(t, L, 4) + keys(t, [(0, 0), (5.2, 0), (5.45, -26), (5.6, 4), (5.8, 0), (8, 0)]), 0, 0))
    rot(c, 'fin_l', lambda t: (0, 18 * wave(t, 1.0, 1), 0))
    rot(c, 'fin_r', lambda t: (0, -18 * wave(t, 1.0, 1), 0))
    c.sound(5.5, 'attack', 0.35, 1.3)
    out.append(keep(m, c))

    # ---- swim: sinuous body / tail wave, lure trailing back
    Ls = float(swim['animation_length'])
    c = Clip(m, 'swim', Ls, 'loop')
    copy_from(c, swim)
    rot(c, 'body', lambda t: (0, 8 * wave(t, Ls, 1), 0))
    rot(c, 'tail', lambda t: (0, 22 * wave(t, Ls, 1, -0.15), 0))
    rot(c, 'tail_fin', lambda t: (0, 28 * wave(t, Ls, 1, -0.3), 0))
    rot(c, 'lure', lambda t: (14, 0, 0))
    rot(c, 'lure_tip', lambda t: (10 + 6 * wave(t, Ls, 1, -0.2), 0, 0))
    rot(c, 'lure_bulb', lambda t: (10 * wave(t, Ls, 1, -0.35), 0, 0))
    out.append(keep(m, c))

    # ---- stalk: a slow glide with the lure dangled right in front of the jaw
    L = 2.0
    c = Clip(m, 'stalk', L, 'loop')
    copy_from(c, swim, speed=Ls / L)
    rot(c, 'lure', lambda t: (-14 + 3 * wave(t, L, 1), 0, 0))
    rot(c, 'lure_tip', lambda t: (-10, 0, 0))
    rot(c, 'lure_bulb', lambda t: (12 * wave(t, L, 2), 0, 8 * wave(t, L, 1)))
    scl(c, 'lure_bulb', lambda t: (0.2 + 0.1 * wave(t, L, 2),) * 3)
    rot(c, 'jaw', lambda t: (-10, 0, 0))
    out.append(keep(m, c))

    # ---- threat (overlay): jaw gapes, fins flare, the bulb flashes, a rush of bubbles
    c = Clip(m, 'threat', 1.2)
    e = lambda t: keys(t, [(0, 0), (0.2, 1), (0.8, 1), (1.2, 0)])
    rot(c, 'jaw', lambda t: (-36 * e(t), 0, 0))
    rot(c, 'fin_l', lambda t: (0, 30 * e(t), 0))
    rot(c, 'fin_r', lambda t: (0, -30 * e(t), 0))
    rot(c, 'h_head', lambda t: (8 * e(t), 0, 0))
    scl(c, 'lure_bulb', lambda t: (0.5 * pulse(t, 0.1, 0.6),) * 3)
    c.sound(0.15, 'minecraft:entity.guardian.ambient', 0.6, 1.6)
    out.append(keep(m, c))

    # ---- lure dangle (flourish): the lure swings out and dances, then a jaw snap
    c = Clip(m, 'lure_dangle', 3.0)
    copy_from(c, idle_swim, loop=True)
    rot(c, 'lure', lambda t: (-18 * env(t, 0, 0.4, 2.2, 2.6), 0, 10 * wave(t, 0.8, 1) * env(t, 0.3, 0.5, 2.0, 2.3)))
    rot(c, 'lure_bulb', lambda t: (22 * wave(t, 0.5, 1) * env(t, 0.3, 0.5, 2.0, 2.3), 0, 0))
    scl(c, 'lure_bulb', lambda t: (0.3 * env(t, 0.3, 0.5, 2.0, 2.3),) * 3)
    rot(c, 'jaw', lambda t: (keys(t, [(0, 0), (2.2, -10), (2.4, -32), (2.55, 4), (2.8, 0), (3.0, 0)]), 0, 0))
    c.sound(2.45, 'attack', 0.5, 1.2)
    out.append(keep(m, c))
    return out


TAGS = {'stalk': {'p': 'swimming', 'gait': 'swim', 'move': True, 'loop': 'loop'},
        'threat': {'layer': 'overlay', 'move': True}, 'lure_dangle': {'p': 'swimming'}}
STATES = {'stalk': 'stalk', 'threat': 'threat'}
IDLE = {'standing': [{'clip': '@mood', 'w': 6, 'min': 8, 'max': 20}, {'clip': 'lure_dangle', 'w': 2, 'cd': 14},
                     {'clip': 'ability', 'w': 1, 'cd': 20}]}
PROCEDURAL = {'bank': 1.4, 'bank_max': 18}
