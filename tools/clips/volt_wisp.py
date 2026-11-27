"""Volt Wisp (tameable hostile, flyer: a giggly ball of static).

Phase 2, two modes. Wild: it crackles (rings snap round in jerky steps, the core flickers and swells, tendrils
twitch, the eye narrows), stalks with its rings tilted forward and tendrils streaming, and flares up (core swell,
rings snapping flat) when it picks a target, plus discharge sparks. Tamed: a lazy orbit (slow ring spin, tendrils
swaying), giggle bounces, a ring flip and a static nuzzle; sits with dimmed rings and sleeps dimmer still."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, pulse, step, wave  # noqa: E402
from _p2kit import keep, copy_from, env, frozen, pos, rot, scl  # noqa: E402
import _p2hostile  # noqa: E402

TENDRILS = ('tendril_1', 'tendril_2', 'tendril_3')


def jerky(t, period, deg):
    """A spin that advances in snaps: deg per period, eased within the first 30 % of each period."""
    n = int(t // period)
    u = (t - n * period) / period
    return (n + step(u, 0.0, 0.3)) * deg


def clips(m):
    out = []
    hover, sleep = frozen(m, 'hover'), frozen(m, 'sleep')

    # ---- wild crackle: jerky ring snaps, flickering core, twitchy tendrils, narrowed eye
    L = 4.0
    c = Clip(m, 'idle_wild', L, 'loop')
    copy_from(c, hover, loop=True, skip={'ring_a', 'ring_b', 'ring_c'} | set(TENDRILS))
    rot(c, 'ring_a', lambda t: (0, jerky(t, 0.5, 90), 0))
    rot(c, 'ring_b', lambda t: (0, -jerky(t + 0.17, 0.5, 90), 0))
    rot(c, 'ring_c', lambda t: (jerky(t + 0.33, 1.0, 90), 0, 0))
    flick = lambda t: 0.6 * wave(t, 0.1, 1) * (pulse(t % 1.0, 0.0, 0.3)) + 0.4 * wave(t, 0.2, 1, 0.3) * pulse(t % 2.0, 1.1, 1.5)
    scl(c, 'core', lambda t: (0.08 * flick(t) + 0.06 * wave(t, L, 2),) * 3)
    pos(c, 'core', lambda t: (0.3 * wave(t, 0.4, 1) * pulse(t % 2.0, 1.1, 1.5), 0.8 * wave(t, L, 2), 0))
    for i, b in enumerate(TENDRILS):
        rot(c, b, lambda t, i=i: (16 * wave(t, 0.5, 1, i * 0.33) + 10 * flick(t), 0, 12 * wave(t, 1.0, 1, i * 0.2)))
    scl(c, 'eye', lambda t: (0.1, -0.3, 0))
    for k in range(4):
        c.sound(0.05 + k * 1.0, 'minecraft:block.copper_bulb.turn_on', 0.2, 1.6 + 0.1 * (k % 2))
    out.append(keep(m, c))

    # ---- stalk: rings tilted forward, tendrils streaming back, steady approach
    L = 1.2
    c = Clip(m, 'stalk', L, 'loop')
    copy_from(c, hover, loop=True, speed=float(hover['animation_length']) / 2.4, skip=set(TENDRILS))
    rot(c, 'core', lambda t: (-18, 0, 0))
    for i, b in enumerate(TENDRILS):
        rot(c, b, lambda t, i=i: (-50 + 8 * wave(t, L, 1, i * 0.3), 0, 0))
    scl(c, 'eye', lambda t: (0.1, -0.35, 0))
    pos(c, 'core', lambda t: (0, 0.5 * wave(t, L, 1), 0))
    out.append(keep(m, c))

    # ---- threat (overlay): the core swells, rings snap flat and blaze, tendrils flare out, crack
    c = Clip(m, 'threat', 1.3)
    e = lambda t: keys(t, [(0, 0), (0.15, -0.2), (0.35, 1), (0.95, 1), (1.3, 0)])
    scl(c, 'core', lambda t: (1 + 0.4 * e(t),) * 3)
    for b in ('ring_a', 'ring_b', 'ring_c'):
        scl(c, b, lambda t: (1 + 0.25 * e(t),) * 3)
    for i, b in enumerate(TENDRILS):
        rot(c, b, lambda t, i=i: (60 * e(t) + 8 * wave(t, 0.1, 1) * env(t, 0.35, 0.4, 0.9, 0.95), 0, (i - 1) * 30 * e(t)))
    rot(c, 'ring_a', lambda t: (0, 180 * step(t, 0.2, 0.5), 0))
    c.sound(0.3, 'ambient_angry', 1.0, 1.0)
    c.sound(0.35, 'minecraft:entity.lightning_bolt.impact', 0.25, 2.0)
    out.append(keep(m, c))

    # ---- discharge sparks (overlay, wild + tamed): three crackling twitches
    c = Clip(m, 'spark', 1.4)
    sp = lambda t: sum(pulse(t, 0.1 + i * 0.4, 0.3 + i * 0.4) for i in range(3))
    scl(c, 'core', lambda t: (1 + 0.2 * sp(t),) * 3)
    for i, b in enumerate(TENDRILS):
        rot(c, b, lambda t, i=i: (30 * sp(t) * (1 if i % 2 else -1), 0, 15 * sp(t)))
    for i in range(3):
        c.sound(0.12 + i * 0.4, 'minecraft:block.copper_bulb.turn_off', 0.3, 1.8)
    out.append(keep(m, c))

    # ---- tamed lazy orbit (loop variant): slow smooth ring spin, tendrils swaying, eye relaxed
    L = 6.0
    c = Clip(m, 'idle_lazy', L, 'loop')
    copy_from(c, hover, loop=True, skip={'ring_a', 'ring_b', 'ring_c'} | set(TENDRILS))
    rot(c, 'ring_a', lambda t: (0, 360 * t / L, 0))
    rot(c, 'ring_b', lambda t: (0, -360 * t / L, 0))
    rot(c, 'ring_c', lambda t: (0, 0, 360 * t / L))
    for i, b in enumerate(TENDRILS):
        rot(c, b, lambda t, i=i: (12 * wave(t, L, 2, i * 0.3), 0, 8 * wave(t, L, 1, i * 0.2)))
    pos(c, 'core', lambda t: (0.6 * wave(t, L, 1), 1.0 * wave(t, L, 2), 0))
    out.append(keep(m, c))

    # ---- tamed giggle (one-shot): bouncy hops with squash, rings wobbling, a chiptune giggle
    c = Clip(m, 'giggle', 1.8)
    copy_from(c, hover, loop=True)
    hop = lambda t: sum(pulse(t, 0.1 + i * 0.4, 0.45 + i * 0.4) for i in range(4))
    pos(c, 'core', lambda t: (0, 2.2 * hop(t), 0))
    scl(c, 'core', lambda t: (0.08 * (1 - hop(t)) * env(t, 0.1, 0.2, 1.5, 1.7), -0.08 * (1 - hop(t)) * env(t, 0.1, 0.2, 1.5, 1.7), 0))
    for b in ('ring_b', 'ring_c'):
        rot(c, b, lambda t: (10 * wave(t, 0.4, 1) * env(t, 0.1, 0.2, 1.5, 1.7), 0, 0))
    for i in range(4):
        c.sound(0.2 + i * 0.4, 'minecraft:block.note_block.bit', 0.35, 1.4 + 0.15 * i)
    out.append(keep(m, c))

    # ---- tamed ring flip (one-shot): the vertical rings flip over each other
    c = Clip(m, 'ring_flip', 1.6)
    copy_from(c, hover, loop=True)
    rot(c, 'ring_b', lambda t: (0, 0, 360 * step(t, 0.2, 1.1)), )
    rot(c, 'ring_c', lambda t: (360 * step(t, 0.35, 1.25), 0, 0))
    pos(c, 'core', lambda t: (0, 1.2 * pulse(t, 0.1, 1.4), 0))
    c.sound(0.3, 'minecraft:block.beacon.ambient', 0.4, 2.0)
    out.append(keep(m, c))

    # ---- dim sleep (lying variant): core sinks and shrinks, rings barely turning
    L = 6.0
    c = Clip(m, 'sleep_dim', L, 'loop')
    copy_from(c, sleep, loop=True, speed=3.0 / L)
    scl(c, 'core', lambda t: (-0.12 + 0.04 * wave(t, L, 1),) * 3)
    for b in ('ring_a', 'ring_b', 'ring_c'):
        scl(c, b, lambda t: (-0.1,) * 3)
    out.append(keep(m, c))
    import _p2kit
    _p2kit.reactions(m, out, pet='giggle', pet_sit='spark', bond_level='ring_flip')
    return out


TAGS = dict(_p2hostile.TAGS, stalk={'p': 'airborne', 'gait': 'fly', 'move': True, 'loop': 'loop'}, spark={'layer': 'overlay', 'move': True}, idle_lazy={'p': 'standing'},
            giggle={'p': 'standing'}, ring_flip={'p': 'standing'}, sleep_dim={'p': 'lying'})
IDLE, STATES = _p2hostile.tables(
    wild=[('spark', 3, 8), ('threat', 1, 30, 'player_near:12'), ('look_around', 1, 16)],
    tame=[('idle_lazy', 4, (6, 16), ''), ('giggle', 2, 18, ''), ('ring_flip', 2, 20, ''), ('spark', 1, 20),
          ('happy', 1, 30, 'happy')],
    sitting=[{'clip': '@sit', 'w': 6, 'min': 10, 'max': 24}, {'clip': 'spark', 'w': 1, 'cd': 24}],
    lying=[{'clip': '@sleep', 'w': 3, 'min': 30, 'max': 90}, {'clip': 'sleep_dim', 'w': 3, 'min': 30, 'max': 90}])
PROCEDURAL = {'bank': 1.5, 'bank_max': 20}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
