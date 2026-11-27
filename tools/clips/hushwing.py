"""Hushwing (silent moth-bat of the deep dark with sculk-glow feelers): ear swivels, wing wraps, owl head turns,
bat wing stretches, sonar pings. Phase 2. Its posture transitions started / ended with the wing tips flat while its
idle flexes them (35 deg pops): re-authored from its own idle / sit / sleep poses; loop_de_loop re-baked so it does
not unwind on the blend out."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403
from animkit import posture_set  # noqa: E402

EARS = ('h_left_ear', 'h_right_ear')
WINGS = ('left_wing', 'right_wing', 'left_wing_tip', 'right_wing_tip')
SOUL = 'minecraft:sculk_soul'


def wrap(c, m, fn):
    """Wings wrap round the body (its own sit pose) by fn(t) 0..1."""
    idle0, sit = m.pose('idle', 0), m.pose('sit', 'end')
    for b in WINGS:
        a = idle0.get((b, 'rotation'), (0, 0, 0))
        z = sit.get((b, 'rotation'), (0, 0, 0))
        d = tuple(y - x for x, y in zip(a, z))
        c.rot(b, (lambda d: lambda t: tuple(v * fn(t) for v in d))(d), add=True)


def feelers(c, fn):
    c.scl('glow_feelers', lambda t: tuple([1 + fn(t)] * 3), add=True)


def clips(m):
    out = posture_set(m, lengths={'sit_down': 1.0, 'stand_up': 0.9, 'lie_down': 1.4, 'wake_up': 1.6})
    for c in out:
        m.put(c)

    c = Clip(m, 'loop_de_loop', m.length('loop_de_loop'), 'once')
    c.copy('loop_de_loop')
    c.sound(0.3, 'minecraft:entity.phantom.flap', 0.15, 1.8)
    out.append(keep(m, c))

    # --- ear swivel (overlay: ears / head only, also sitting): each ear tracks a different faint sound
    L = 2.6
    c = Clip(m, 'ear_swivel', L, 'once')
    c.rot('h_left_ear', lambda t: (0, keys(t, [(0, 0), (0.3, 40), (1.0, 40), (1.3, -20), (1.9, -20), (2.3, 0), (2.6, 0)]), 0))
    c.rot('h_right_ear', lambda t: (0, keys(t, [(0, 0), (0.6, 0), (0.8, -38), (1.6, -38), (1.9, 25), (2.3, 0), (2.6, 0)]), 0))
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (1.0, 4), (1.6, -6), (2.6, 0)]), keys(t, [(0, 0), (0.4, 18), (1.2, 18), (1.7, -14), (2.2, -14), (2.6, 0)]), 0))
    c.rot('h_antennae', lambda t: (8 * pulse(t, 0.3, 0.6) + 8 * pulse(t, 1.4, 1.7), 0, 0))
    out.append(keep(m, c))

    # --- wing wrap (standing loop): wraps itself in its wings like a cloak, only the face and feelers peek out
    L = 5.0
    c = Clip(m, 'idle_wing_wrap', L, 'loop')
    loop_base(c, m)
    for b in WINGS:   # wrapped wings hold still (the idle flaps them)
        c.tracks[(b, 'rotation')] = const(m.pose('idle', 0).get((b, 'rotation'), (0, 0, 0)))
    wrap(c, m, lambda t: 0.85 + 0.08 * wave(t, L, 2))
    c.rot('h_head', lambda t: (-6, 12 * wave(t, L, 1), 0), add=True)
    for i, ear in enumerate(EARS):
        c.rot(ear, (lambda i: lambda t: (0, 25 * pulse(t, 1.0 + 2.0 * i, 1.8 + 2.0 * i) * (-1) ** i, 0))(i), add=True)
    feelers(c, lambda t: 0.15 * (0.5 + 0.5 * wave(t, L, 2)))
    out.append(keep(m, c))

    # --- owl head turn: slowly turns its head almost all the way round to look behind, then snaps back
    L = 3.0
    c = one_shot(m, 'owl_turn', L)
    c.rot('h_head', lambda t: (0, keys(t, [(0, 0), (0.3, 0), (1.4, 150), (2.1, 150), (2.35, -15), (2.6, 4), (3.0, 0)]),
                               keys(t, [(0, 0), (1.4, 10), (2.1, 10), (2.4, 0), (3.0, 0)])), add=True)
    for i, ear in enumerate(EARS):
        c.rot(ear, lambda t: (0, -20 * pulse(t, 1.2, 2.2), 0), add=True)
    c.rot('h_antennae', lambda t: (10 * pulse(t, 2.1, 2.5), 0, 0), add=True)
    out.append(keep(m, c))

    # --- wing stretch: a bat stretch, one wing fanned wide and flexed, then the other
    L = 3.2
    c = one_shot(m, 'wing_stretch', L)
    lw = lambda t: pulse(t, 0.2, 1.5)
    rw = lambda t: pulse(t, 1.5, 2.9)
    c.rot('left_wing', lambda t: (0, 0, 24 * lw(t) - 10 * rw(t)), add=True)
    c.rot('left_wing_tip', lambda t: (0, -15 * lw(t), 35 * lw(t)), add=True)
    c.rot('right_wing', lambda t: (0, 0, -24 * rw(t) + 10 * lw(t)), add=True)
    c.rot('right_wing_tip', lambda t: (0, 15 * rw(t), -35 * rw(t)), add=True)
    c.rot('body', lambda t: (0, 0, -8 * lw(t) + 8 * rw(t)), add=True)
    c.rot('h_head', lambda t: (8 * (lw(t) + rw(t)), 30 * lw(t) - 30 * rw(t), 0), add=True)
    c.sound(0.5, 'minecraft:entity.phantom.flap', 0.1, 1.9)
    c.sound(1.8, 'minecraft:entity.phantom.flap', 0.1, 1.8)
    out.append(keep(m, c))

    # --- sonar ping: the feelers flare, a sculk click, the ears snap forward to catch the echo
    L = 1.8
    c = one_shot(m, 'sonar_ping', L)
    feelers(c, lambda t: 0.6 * pulse(t, 0.2, 0.7) + 0.3 * pulse(t, 0.9, 1.3))
    c.rot('h_antennae', lambda t: (-16 * pulse(t, 0.15, 0.6), 0, 0), add=True)
    for i, ear in enumerate(EARS):
        c.rot(ear, (lambda s: lambda t: (-14 * pulse(t, 0.6, 1.6), s * 15 * pulse(t, 0.6, 1.6), 0))((-1) ** i), add=True)
    c.rot('h_head', lambda t: (6 * pulse(t, 0.1, 0.4) - 4 * pulse(t, 0.6, 1.6), 0, 0), add=True)
    c.sound(0.25, 'minecraft:block.sculk_sensor.clicking', 0.4, 1.5)
    c.sound(0.95, 'minecraft:block.sculk_sensor.clicking_stop', 0.3, 1.7)
    c.particle(0.3, SOUL, 2, 0.2, 0.02, 1.1)
    out.append(keep(m, c))

    # --- sitting loops (its sit is a hovering cocoon of wings)
    L = 5.0
    c = sit_loop(m, 'sit_peek', L)
    peek = lambda t: pulse(t, 1.0, 3.6)
    sit, idle0 = m.pose('sit', 'end'), m.pose('idle', 0)
    for b in ('left_wing', 'right_wing'):
        d = tuple(x - y for x, y in zip(idle0[(b, 'rotation')], sit[(b, 'rotation')]))
        c.rot(b, (lambda d: lambda t: tuple(v * 0.35 * peek(t) for v in d))(d), add=True)
    c.rot('h_head', lambda t: (6 * peek(t), 22 * wave(t, L, 1) * peek(t), 0), add=True)
    for i, ear in enumerate(EARS):
        c.rot(ear, (lambda i: lambda t: (0, 20 * pulse(t, 1.4 + i * 0.9, 2.4 + i * 0.9) * (-1) ** i, 0))(i), add=True)
    out.append(keep(m, c))

    L = 6.0
    c = sit_loop(m, 'sit_cocoon_sway', L)
    c.rot('body', lambda t: (0, 0, 4 * wave(t, L, 1)), add=True)
    c.pos('root', lambda t: (0, 0.6 * wave(t, L, 2), 0), add=True)
    c.rot('h_head', lambda t: (0, 0, -4 * wave(t, L, 1, -0.05)), add=True)
    feelers(c, lambda t: 0.12 * (0.5 + 0.5 * wave(t, L, 2)))
    out.append(keep(m, c))

    # --- lying loops
    L = 7.0
    c = sleep_loop(m, 'sleep_cocoon', L)
    feelers(c, lambda t: 0.2 * (0.5 + 0.5 * wave(t, L, 2)))
    c.pos('root', lambda t: (0, 0.4 * wave(t, L, 1), 0), add=True)
    out.append(keep(m, c))

    L = 7.0
    c = sleep_loop(m, 'sleep_ear_flick', L)
    c.rot('h_left_ear', lambda t: (0, 20 * pulse(t, 1.5, 1.9), 0), add=True)
    c.rot('h_right_ear', lambda t: (0, -20 * pulse(t, 4.2, 4.6), 0), add=True)
    c.rot('h_antennae', lambda t: (6 * pulse(t, 3.0, 3.4), 0, 0), add=True)
    out.append(keep(m, c))

    # --- reactions
    L = 2.0   # petting: ears fold back contentedly, a shivery wing flutter, feelers glow
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    for i, ear in enumerate(EARS):
        c.rot(ear, (lambda s: lambda t: (-28 * e(t), 0, s * 12 * e(t)))((-1) ** (i + 1)), add=True)
    c.rot('h_head', lambda t: (10 * e(t), 0, 16 * e(t)), add=True)
    for b, s in (('left_wing_tip', 1), ('right_wing_tip', -1)):
        c.rot(b, (lambda s: lambda t: (0, 0, s * 18 * math.sin(2 * math.pi * 8 * t / 1.0) * pulse(t, 0.4, 1.6)))(s), add=True)
    feelers(c, lambda t: 0.35 * e(t))
    c.particle(0.6, SOUL, 2, 0.3, 0.01, 1.0)
    out.append(keep(m, c))

    L = 1.6
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head',) + EARS})
    e = lambda t: ramp(t, L, 0.3)
    for i, ear in enumerate(EARS):
        c.rot(ear, (lambda s: lambda t: (-25 * e(t), 0, s * 10 * e(t)))((-1) ** (i + 1)), add=True)
    c.rot('h_head', lambda t: (8 * e(t), 0, 14 * e(t)), add=True)
    out.append(keep(m, c))

    L = 3.0   # bond level-up: a silent loop-de-loop, wings flared, a ring of sculk souls and a soft hoot
    c = one_shot(m, 'level_up', L)
    up = lambda t: keys(t, [(0, 0), (0.3, 0), (0.9, 1), (1.9, 1), (2.6, 0), (3.0, 0)])
    c.pos('root', lambda t: (0, 8 * up(t), 0), add=True)
    c.rot('root', lambda t: (360 * step(t, 0.8, 1.9), 0, 0), add=True)
    for b, s in (('left_wing', 1), ('right_wing', -1)):
        c.rot(b, (lambda s: lambda t: (0, 0, s * 28 * math.sin(2 * math.pi * 6 * t / 2.0) * pulse(t, 0.2, 2.8)))(s), add=True)
    feelers(c, lambda t: 0.7 * pulse(t, 0.6, 2.8))
    c.rot('h_head', lambda t: (18 * pulse(t, 1.9, 2.8), 0, 0), add=True)
    c.sound(0.9, 'minecraft:block.note_block.flute', 0.4, 0.62)
    c.sound(1.25, 'minecraft:block.note_block.flute', 0.4, 0.83)
    c.sound(1.9, 'minecraft:block.sculk_catalyst.bloom', 0.4, 1.4)
    c.particle(1.9, SOUL, 10, 0.8, 0.03, 0.8)
    out.append(keep(m, c))
    return out


TAGS = {
    'loop_de_loop': {'layer': 'base', 'move': False, 'p': 'standing'}, 'ear_swivel': {'layer': 'overlay', 'move': True},
    'owl_turn': {'layer': 'base', 'move': False}, 'wing_stretch': {'layer': 'base', 'move': False},
    'sonar_ping': {'layer': 'base', 'move': False}, 'idle_wing_wrap': {'p': 'standing'},
    'sit_peek': {'p': 'sitting'}, 'sit_cocoon_sway': {'p': 'sitting'},
    'sleep_cocoon': {'p': 'lying'}, 'sleep_ear_flick': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False, 'p': 'standing'},
}

IDLE = {
    'standing': [
        idle_entry('ear_swivel', 3, cd=12),
        idle_entry('idle_wing_wrap', 3, mn=6, mx=14, cd=15, boost={'night': 1.5, 'cold': 2}),
        idle_entry('owl_turn', 2, cd=25, boost={'monster_near': 3}),
        idle_entry('wing_stretch', 2, cd=30, boost={'dawn': 2}),
        idle_entry('sonar_ping', 2, cd=20),
    ],
    'sitting': [
        idle_entry('sit_peek', 3, mn=5, mx=10, cd=12),
        idle_entry('sit_cocoon_sway', 2, mn=6, mx=12, cd=15),
    ],
    'lying': [
        idle_entry('sleep_cocoon', 2, mn=20, mx=50),
        idle_entry('sleep_ear_flick', 1, mn=7, mx=14, cd=30),
    ],
}

PROCEDURAL = {'tail_sway': 3, 'ear_twitch': 5}
