"""Rime Wraith (tameable hostile: a shy little snow spirit in a frosty hood).

Phase 2, two modes. Wild: an eerie drift (it floats in slow swells, the head tilts too far, clawed fingers curl and
uncurl, the tatters ripple), a gliding stalk with reaching claws, a threat (arms up and wide, jaw dropped in a frost
hiss) and frost breaths. Tamed: a calm bobbing float, shy peeks (hides its face in its claws, then peeks out), a happy
twirl and a soft frost breath; sits and sleeps curled into a floating ball."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, pulse, wave  # noqa: E402
from _p2kit import keep, copy_from, env, frozen, pair, pos, rot, scl  # noqa: E402
import _p2hostile  # noqa: E402

FINGERS = [s + '_finger_' + f + '_r' for s in ('right', 'left') for f in 'abc']


def fingers(c, fn):
    for b in FINGERS:
        rot(c, b, lambda t: (fn(t), 0, 0))


def tatters(c, L, amp, cycles=2):
    rot(c, 'tatters_front', lambda t: (amp * wave(t, L, cycles), 0, 0.5 * amp * wave(t, L, cycles + 1, 0.3)))
    rot(c, 'tatters_back', lambda t: (-amp * wave(t, L, cycles, 0.2), 0, 0.5 * amp * wave(t, L, cycles + 1, 0.5)))


def breath(c, t0, t1, amp=1.0, hiss=True):
    """Frost breath: head draws back, juts forward, jaw drops; core pulses."""
    e = lambda t: env(t, t0, t0 + 0.25, t1 - 0.3, t1)
    rot(c, 'h_head', lambda t: (keys(t, [(t0, 0), (t0 + 0.25, 10 * amp), (t0 + 0.5, -14 * amp), (t1, 0)]), 0, 0))
    rot(c, 'jaw', lambda t: (-34 * amp * env(t, t0 + 0.35, t0 + 0.5, t1 - 0.35, t1 - 0.1), 0, 0))
    scl(c, 'core', lambda t: (0.3 * amp * e(t),) * 3)
    if hiss:
        c.sound(t0 + 0.4, 'minecraft:block.powder_snow.break', 0.6 * amp, 0.8)
        c.sound(t0 + 0.45, 'minecraft:entity.breeze.whirl', 0.35 * amp, 1.4)


def clips(m):
    out = []
    idle, walk, sleep = frozen(m, 'idle'), frozen(m, 'walk'), frozen(m, 'sleep')

    # ---- wild drift: slow swells, too-far head tilts, curling claws, rippling tatters
    L = 6.0
    c = Clip(m, 'idle_wild', L, 'loop')
    copy_from(c, idle, loop=True, speed=0.5)
    pos(c, 'body', lambda t: (0.6 * wave(t, L, 1, 0.25), 1.4 * wave(t, L, 2), 0))
    rot(c, 'body', lambda t: (-6, 0, 4 * wave(t, L, 1)))
    rot(c, 'h_head', lambda t: (6, keys(t, [(0, 0), (1.5, 0), (2.2, 18), (3.6, 18), (4.2, 0), (6, 0)]),
                                keys(t, [(0, 0), (1.6, 0), (2.4, 34), (3.5, 34), (4.3, -8), (5.2, 0), (6, 0)])))
    pair(c, 'right_arm', 'left_arm', lambda t: (24 + 6 * wave(t, L, 2, 0.1), 0, 14 + 4 * wave(t, L, 1)))
    pair(c, 'right_forearm', 'left_forearm', lambda t: (26 + 8 * wave(t, L, 2, 0.2), 0, 0))
    fingers(c, lambda t: 26 * pulse(t % 3.0, 0.5, 2.0))
    rot(c, 'jaw', lambda t: (-8 - 4 * wave(t, L, 3), 0, 0))
    tatters(c, L, 10)
    scl(c, 'core', lambda t: (0.12 * wave(t, L, 3),) * 3)
    out.append(keep(m, c))

    # ---- stalk: a leaning glide, claws reaching, tatters streaming back
    L = 1.6
    c = Clip(m, 'stalk', L, 'loop')
    copy_from(c, walk, speed=float(walk['animation_length']) / L)
    rot(c, 'body', lambda t: (-16, 0, 3 * wave(t, L, 1)))
    pos(c, 'body', lambda t: (0, 1.0 + 0.6 * wave(t, L, 2), 0))
    rot(c, 'h_head', lambda t: (12, 0, 6 * wave(t, L, 1, 0.2)))
    pair(c, 'right_arm', 'left_arm', lambda t: (62 + 8 * wave(t, L, 1), 0, 8))
    pair(c, 'right_forearm', 'left_forearm', lambda t: (20, 0, 0))
    fingers(c, lambda t: 18 + 10 * wave(t, L, 2))
    rot(c, 'tatters_front', lambda t: (22 + 6 * wave(t, L, 2), 0, 0))
    rot(c, 'tatters_back', lambda t: (26 + 8 * wave(t, L, 2, 0.3), 0, 0))
    rot(c, 'jaw', lambda t: (-12, 0, 0))
    out.append(keep(m, c))

    # ---- threat (overlay): arms up and wide, claws splayed, jaw dropped in a frost hiss
    c = Clip(m, 'threat', 1.6)
    e = lambda t: keys(t, [(0, 0), (0.2, -0.1), (0.45, 1), (1.15, 1), (1.6, 0)])
    pair(c, 'right_arm', 'left_arm', lambda t: (30 * e(t), 0, 70 * e(t)))
    pair(c, 'right_forearm', 'left_forearm', lambda t: (40 * e(t), 0, 0))
    fingers(c, lambda t: -20 * e(t))
    rot(c, 'h_head', lambda t: (-12 * e(t), 0, 6 * wave(t, 0.25, 1) * env(t, 0.5, 0.6, 1.0, 1.15)))
    rot(c, 'jaw', lambda t: (-40 * e(t), 0, 0))
    scl(c, 'core', lambda t: (0.5 * e(t),) * 3)
    c.sound(0.4, 'ambient_angry', 1.0, 1.0)
    c.sound(0.45, 'minecraft:block.powder_snow.break', 0.7, 0.7)
    out.append(keep(m, c))

    # ---- frost breath (overlay; wild hiss / tamed puff)
    c = Clip(m, 'frost_breath', 2.0)
    breath(c, 0.0, 2.0)
    out.append(keep(m, c))
    c = Clip(m, 'frost_puff', 1.6)
    breath(c, 0.0, 1.6, amp=0.5, hiss=False)
    c.sound(0.4, 'minecraft:block.powder_snow.step', 0.5, 1.5)
    out.append(keep(m, c))

    # ---- tamed: calm bobbing float (standing loop variant)
    L = 5.0
    c = Clip(m, 'drift_calm', L, 'loop')
    copy_from(c, idle, loop=True, speed=3.0 / L)
    pos(c, 'body', lambda t: (0, 1.0 + 0.8 * wave(t, L, 1), 0))
    rot(c, 'body', lambda t: (2 * wave(t, L, 1, 0.25), 0, 0))
    rot(c, 'h_head', lambda t: (4 * wave(t, L, 1, 0.3), 8 * wave(t, L, 1, 0.1), 8 * wave(t, L, 1)))
    pair(c, 'right_arm', 'left_arm', lambda t: (10 + 3 * wave(t, L, 1, 0.2), 0, 6))
    pair(c, 'right_forearm', 'left_forearm', lambda t: (30, 0, 0))
    tatters(c, L, 6, 1)
    out.append(keep(m, c))

    # ---- tamed: shy peek (one-shot): hides its face in its claws, peeks out between them, lowers them
    c = Clip(m, 'shy_peek', 3.6)
    copy_from(c, idle, loop=True)
    hide = lambda t: keys(t, [(0, 0), (0.5, 1), (1.4, 1), (1.7, 0.6), (2.4, 0.6), (2.6, 1), (3.0, 1), (3.6, 0)])
    pair(c, 'right_arm', 'left_arm', lambda t: (78 * hide(t), 0, -18 * hide(t)))
    pair(c, 'right_forearm', 'left_forearm', lambda t: (56 * hide(t), 0, 0))
    rot(c, 'right_arm', lambda t: (0, 0, -14 * env(t, 1.5, 1.7, 2.3, 2.5)))
    rot(c, 'h_head', lambda t: (-10 * hide(t), 0, 14 * env(t, 1.5, 1.7, 2.3, 2.5)))
    pos(c, 'body', lambda t: (0, -0.8 * hide(t), 0))
    c.sound(1.8, 'ambient_happy', 0.6, 1.3)
    out.append(keep(m, c))

    # ---- tamed: happy twirl (one-shot): a full spin with the tatters flaring
    c = Clip(m, 'twirl', 1.8)
    copy_from(c, idle, loop=True)
    spin = lambda t: keys(t, [(0, 0), (0.2, -10), (1.3, 360), (1.8, 360)])
    rot(c, 'body', lambda t: (0, spin(t), 0))
    pos(c, 'body', lambda t: (0, 2 * pulse(t, 0.1, 1.5), 0))
    pair(c, 'right_arm', 'left_arm', lambda t: (0, 0, 50 * pulse(t, 0.2, 1.5)))
    rot(c, 'tatters_front', lambda t: (-30 * pulse(t, 0.3, 1.5), 0, 0))
    rot(c, 'tatters_back', lambda t: (30 * pulse(t, 0.3, 1.5), 0, 0))
    c.sound(0.3, 'minecraft:entity.breeze.idle_air', 0.5, 1.5)
    c.sound(1.3, 'ambient_happy', 0.6, 1.2)
    out.append(keep(m, c))

    # ---- curled floating-ball sleep (lying variant)
    L = 5.0
    c = Clip(m, 'sleep_ball', L, 'loop')
    copy_from(c, sleep, loop=True, speed=3.0 / L)
    rot(c, 'body', lambda t: (-14, 0, 0))
    rot(c, 'h_head', lambda t: (-20, 0, 8))
    rot(c, 'tatters_front', lambda t: (-30, 0, 0))
    rot(c, 'tatters_back', lambda t: (30, 0, 0))
    pos(c, 'body', lambda t: (0, 0.6 * wave(t, L, 1), 0))
    out.append(keep(m, c))
    import _p2kit
    _p2kit.reactions(m, out, pet='shy_peek', pet_sit='frost_puff', bond_level='twirl')
    return out


TAGS = dict(_p2hostile.TAGS, frost_breath={'layer': 'overlay', 'move': True}, frost_puff={'layer': 'overlay', 'move': True},
            drift_calm={'p': 'standing'}, shy_peek={'p': 'standing'}, twirl={'p': 'standing'}, sleep_ball={'p': 'lying'})
IDLE, STATES = _p2hostile.tables(
    wild=[('frost_breath', 2, 14), ('threat', 1, 30, 'player_near:12'), ('wail', 0.5, 40, 'player_near:8'),
          ('look_around', 1, 16)],
    tame=[('drift_calm', 3, (6, 14), ''), ('shy_peek', 2, 22, ''), ('twirl', 1, 28, 'happy'), ('frost_puff', 2, 16),
          ('curious', 1, 24)],
    sitting=[{'clip': '@sit', 'w': 6, 'min': 10, 'max': 24}, {'clip': 'frost_puff', 'w': 2, 'cd': 18},
             {'clip': 'look_around', 'w': 1, 'cd': 20}],
    lying=[{'clip': '@sleep', 'w': 3, 'min': 30, 'max': 90}, {'clip': 'sleep_ball', 'w': 3, 'min': 30, 'max': 90}])
PROCEDURAL = {'breath': 0.016}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
