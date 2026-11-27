"""Driftglow (deep-sea jellyfish with a glowing heart): bell pulses, tentacle drift, lantern flashes. In water it
jets up with strong pulses and turns lazily; on land it bobs and drifts its tentacles; sitting it spreads out like
a jelly on the sand. Phase 2 (posture transitions from its own poses, as before)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403
from animkit import posture_set  # noqa: E402

# each tentacle's "outward" direction (its own sit pose spreads them this way): (x sign, z sign)
TENT = {'tent_fl': (1, 1), 'tent_fr': (1, -1), 'tent_bl': (-1, 1), 'tent_br': (-1, -1)}
GLOW = 'minecraft:glow'


def flare(c, fn, tip=1.2, phase=None):
    """Tentacles flare outward by fn(t) degrees (tips follow by `tip`); phase(name) -> time offset for a ripple."""
    for b, (sx, sz) in TENT.items():
        off = phase(b) if phase else 0.0
        c.rot(b, (lambda sx, sz, off: lambda t: (sx * fn(t - off), 0, sz * fn(t - off)))(sx, sz, off), add=True)
        c.rot(b + '_tip', (lambda sx, sz, off: lambda t: (sx * tip * fn(t - off - 0.08), 0, sz * tip * fn(t - off - 0.08)))(sx, sz, off), add=True)


def bell(c, fn):
    """Bell contraction: fn > 0 squeezes it narrow and tall (a pulse), < 0 relaxes it wide."""
    c.scl('h_head', lambda t: (1 - 0.14 * fn(t), 1 + 0.12 * fn(t), 1 - 0.14 * fn(t)), add=True)


def heart(c, fn):
    c.scl('core', lambda t: tuple([1 + fn(t)] * 3), add=True)


def clips(m):
    out = posture_set(m)
    for c in out:
        m.put(c)

    # --- in water: three strong jet pulses that lift it up, tentacles snapping in and trailing out
    L = 2.8
    c = Clip(m, 'swim_pulse_rise', L, 'once')
    loop_base(c, m, 'idle_swim')
    beats = [0.3, 1.0, 1.7]
    squeeze = lambda t: sum(pulse(t, b, b + 0.3) - 0.5 * pulse(t, b + 0.3, b + 0.7) for b in beats)
    bell(c, squeeze)
    flare(c, lambda t: -14 * sum(pulse(t, b, b + 0.3) for b in beats) + 12 * sum(pulse(t, b + 0.3, b + 0.75) for b in beats))
    c.pos('body', lambda t: (0, keys(t, [(0, 0), (0.3, 0), (0.6, 1.2), (1.0, 1.2), (1.3, 2.4), (1.7, 2.4), (2.0, 3.4), (2.8, 0)]), 0), add=True)
    heart(c, lambda t: 0.15 * sum(pulse(t, b, b + 0.4) for b in beats))
    for b in beats:
        c.sound(b, 'minecraft:entity.glow_squid.squirt', 0.2, 1.7)
    c.particle(1.0, 'minecraft:bubble', 3, 0.3, 0.02, 0.2)
    out.append(keep(m, c))

    # --- in water: a lazy drifting turn, tentacles trailing behind the rotation
    L = 3.6
    c = Clip(m, 'swim_drift_turn', L, 'once')
    loop_base(c, m, 'idle_swim')
    c.rot('body', lambda t: (0, 360 * step(t, 0.3, 3.2), 0), add=True)
    c.rot('body', lambda t: (6 * pulse(t, 0.3, 3.2), 0, 0), add=True)
    flare(c, lambda t: 10 * pulse(t, 0.4, 3.3) + 5 * math.sin(2 * math.pi * 3 * t / L) * pulse(t, 0.4, 3.3),
          phase=lambda b: {'tent_fl': 0.0, 'tent_fr': 0.15, 'tent_br': 0.3, 'tent_bl': 0.45}[b])
    heart(c, lambda t: 0.1 * pulse(t, 0.5, 3.0))
    out.append(keep(m, c))

    # --- lantern flash (land and water): the heart flares bright, the bell blooms, a ring of glow
    L = 2.0
    c = one_shot(m, 'glow_flash', L)
    heart(c, lambda t: 0.6 * pulse(t, 0.2, 0.8) + 0.25 * pulse(t, 0.8, 1.6))
    bell(c, lambda t: -0.6 * pulse(t, 0.15, 0.9))
    flare(c, lambda t: 22 * pulse(t, 0.15, 1.2))
    c.pos('body', lambda t: (0, 0.8 * pulse(t, 0.15, 1.2), 0), add=True)
    c.sound(0.25, 'minecraft:block.amethyst_block.chime', 0.6, 1.6)
    c.sound(0.3, 'minecraft:entity.glow_squid.ambient', 0.3, 1.8)
    c.particle(0.35, GLOW, 8, 0.6, 0.03, 0.6)
    out.append(keep(m, c))

    # --- bell pulse (standing loop): steady jellyfish pulses, tentacles rippling one after another
    L = 3.0
    c = Clip(m, 'idle_bell_pulse', L, 'loop')
    loop_base(c, m)
    sq = lambda t: pulse(t % 1.5, 0.0, 0.45) - 0.4 * pulse(t % 1.5, 0.45, 1.1)
    bell(c, sq)
    c.pos('body', lambda t: (0, 0.6 * pulse(t % 1.5, 0.2, 1.2), 0), add=True)
    flare(c, lambda t: -8 * pulse(t % 1.5, 0.0, 0.45) + 10 * pulse(t % 1.5, 0.45, 1.2),
          phase=lambda b: {'tent_fl': 0.0, 'tent_fr': 0.08, 'tent_br': 0.16, 'tent_bl': 0.24}[b])
    heart(c, lambda t: 0.08 * pulse(t % 1.5, 0.0, 0.6))
    out.append(keep(m, c))

    # --- tentacle drift (standing loop): as if in a slow current, each tentacle wanders on its own
    L = 6.0
    c = Clip(m, 'idle_tentacle_drift', L, 'loop')
    loop_base(c, m)
    for i, (b, (sx, sz)) in enumerate(TENT.items()):
        c.rot(b, (lambda i, sx, sz: lambda t: (sx * 12 * wave(t, L, 2, i * 0.23), 0, sz * 12 * wave(t, L, 1, i * 0.31)))(i, sx, sz), add=True)
        c.rot(b + '_tip', (lambda i, sx, sz: lambda t: (sx * 18 * wave(t, L, 2, i * 0.23 - 0.08), 0, sz * 16 * wave(t, L, 1, i * 0.31 - 0.08)))(i, sx, sz), add=True)
    c.rot('h_head', lambda t: (5 * wave(t, L, 1), 0, 5 * wave(t, L, 1, 0.25)), add=True)
    c.rot('body', lambda t: (0, 20 * wave(t, L, 1), 0), add=True)
    out.append(keep(m, c))

    # --- curious reach: the bell tilts towards something and one front tentacle reaches out to touch it
    L = 2.8
    c = one_shot(m, 'curious_reach', L)
    reach = lambda t: keys(t, [(0, 0), (0.5, 1), (2.1, 1), (2.8, 0)])
    c.rot('h_head', lambda t: (-12 * reach(t), 0, 6 * reach(t)), add=True)
    c.rot('body', lambda t: (-8 * reach(t), 0, 0), add=True)
    c.rot('tent_fl', lambda t: (55 * reach(t), 0, 10 * reach(t)), add=True)
    c.rot('tent_fl_tip', lambda t: (25 * reach(t) + 15 * pulse(t, 1.0, 1.4) - 15 * pulse(t, 1.5, 1.9), 0, 0), add=True)
    heart(c, lambda t: 0.25 * pulse(t, 1.0, 1.8))
    c.sound(1.2, 'minecraft:block.amethyst_block.hit', 0.3, 1.9)
    out.append(keep(m, c))

    # --- sitting loops (spread out flat like a jelly on the sand)
    L = 5.0
    c = sit_loop(m, 'sit_tip_curl', L)
    for i, b in enumerate(TENT):
        sx, sz = TENT[b]
        c.rot(b + '_tip', (lambda i, sx, sz: lambda t: (-sx * 25 * pulse((t + i * 1.25) % L, 0.0, 1.2), 0, -sz * 25 * pulse((t + i * 1.25) % L, 0.0, 1.2)))(i, sx, sz), add=True)
    bell(c, lambda t: 0.3 * wave(t, L, 2))
    out.append(keep(m, c))

    L = 6.0
    c = sit_loop(m, 'sit_glow_breathe', L)
    heart(c, lambda t: 0.15 * (0.5 + 0.5 * wave(t, L, 2)))
    bell(c, lambda t: -0.4 * (0.5 + 0.5 * wave(t, L, 2)))
    out.append(keep(m, c))

    # --- lying loops
    L = 7.0
    c = sleep_loop(m, 'sleep_glow_dim', L)
    heart(c, lambda t: 0.12 * wave(t, L, 2))
    bell(c, lambda t: -0.2 * wave(t, L, 2))
    out.append(keep(m, c))

    L = 7.0
    c = sleep_loop(m, 'sleep_tentacle_twitch', L)
    c.rot('tent_br_tip', lambda t: (-20 * pulse(t, 2.0, 2.4), 0, -20 * pulse(t, 2.0, 2.4)), add=True)
    c.rot('tent_fl_tip', lambda t: (20 * pulse(t, 4.5, 4.9), 0, 20 * pulse(t, 4.5, 4.9)), add=True)
    out.append(keep(m, c))

    # --- reactions
    L = 2.0   # petting: the bell squishes and wobbles, tentacles curl up towards the hand, the heart glows
    c = one_shot(m, 'pet', L)
    pat = lambda t: pulse(t, 0.15, 0.6)
    bell(c, lambda t: -1.2 * pat(t) + 0.5 * decay(t, 0.6, 3, 3))
    flare(c, lambda t: -25 * pulse(t, 0.4, 1.8))
    heart(c, lambda t: 0.35 * pulse(t, 0.3, 1.8))
    c.pos('body', lambda t: (0, -0.5 * pat(t) + 0.6 * pulse(t, 0.6, 1.6), 0), add=True)
    c.particle(0.7, GLOW, 3, 0.3, 0.02, 0.8)
    out.append(keep(m, c))

    L = 1.6
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'core')})
    bell(c, lambda t: -1.0 * pulse(t, 0.1, 0.55) + 0.4 * decay(t, 0.55, 3, 3))
    heart(c, lambda t: 0.3 * pulse(t, 0.2, 1.5))
    out.append(keep(m, c))

    L = 3.0   # bond level-up: jets straight up with rapid pulses, spins, and bursts into light at the top
    c = one_shot(m, 'level_up', L)
    beats = [0.2, 0.5, 0.8, 1.1]
    bell(c, lambda t: sum(pulse(t, b, b + 0.22) for b in beats) - 0.8 * pulse(t, 1.4, 2.2))
    flare(c, lambda t: -12 * sum(pulse(t, b, b + 0.22) for b in beats) + 30 * pulse(t, 1.3, 2.6))
    c.pos('root', lambda t: (0, keys(t, [(0, 0), (0.2, 0), (1.3, 8), (2.2, 7), (3.0, 0)]), 0), add=True)
    c.rot('body', lambda t: (0, 360 * step(t, 0.3, 1.4), 0), add=True)
    heart(c, lambda t: 0.9 * pulse(t, 1.2, 2.4))
    c.sound(1.35, 'minecraft:block.amethyst_block.resonate', 0.6, 1.5)
    c.sound(1.4, 'minecraft:block.conduit.activate', 0.25, 1.8)
    c.particle(1.4, GLOW, 16, 0.8, 0.05, 0.6)
    c.particle(1.6, 'minecraft:end_rod', 6, 0.6, 0.03, 0.8)
    out.append(keep(m, c))
    return out


TAGS = {
    'swim_pulse_rise': {'p': 'swimming', 'layer': 'base', 'move': True}, 'swim_drift_turn': {'p': 'swimming', 'layer': 'base', 'move': True},
    'glow_flash': {'layer': 'base', 'move': False, 'p': 'standing'}, 'curious_reach': {'layer': 'base', 'move': False},
    'idle_bell_pulse': {'p': 'standing'}, 'idle_tentacle_drift': {'p': 'standing'},
    'sit_tip_curl': {'p': 'sitting'}, 'sit_glow_breathe': {'p': 'sitting'},
    'sleep_glow_dim': {'p': 'lying'}, 'sleep_tentacle_twitch': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False, 'p': 'standing'},
}

IDLE = {
    'standing': [
        # on land
        idle_entry('idle_bell_pulse', 3, mn=6, mx=12, cd=12, when='!in_water'),
        idle_entry('idle_tentacle_drift', 2, mn=6, mx=12, cd=15, when='!in_water'),
        idle_entry('curious_reach', 2, cd=25, when='!in_water', boost={'player_near': 1.5}),
        idle_entry('glow_flash', 1, cd=30, when='!in_water', boost={'night': 2}),
        # in water
        idle_entry('swim_pulse_rise', 3, cd=12, when='mood:idle_swim'),
        idle_entry('swim_drift_turn', 2, cd=18, when='mood:idle_swim'),
        idle_entry('glow_flash', 2, cd=20, when='mood:idle_swim'),
    ],
    'sitting': [
        idle_entry('sit_tip_curl', 3, mn=5, mx=10, cd=10),
        idle_entry('sit_glow_breathe', 2, mn=6, mx=12, cd=15, boost={'night': 2}),
    ],
    'lying': [
        idle_entry('sleep_glow_dim', 2, mn=20, mx=50),
        idle_entry('sleep_tentacle_twitch', 1, mn=7, mx=14, cd=30),
    ],
}

PROCEDURAL = {'breath': 0.02}
