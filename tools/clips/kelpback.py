"""Kelpback (sea cow, Creatures 2 ocean): every clip authored here (the source bbmodel has none).

Character: slow, heavy, unbothered. It hangs in the water with the paddle tail sculling, swims with slow up-and-down
strokes of the paddle (manatees never sweep sideways), grazes seagrass nose-down with a chewing lip, rests on the floor,
rises for a breath at the surface, rolls lazily and rubs its face with a flipper. The kelp garden on its back always
sways a beat behind the body. Eyes close only in sleep and death."""
from animkit import Clip, wave, pulse, step, keys, decay, transition

KELP = (('kelp_a', 0.0), ('kelp_b', 0.33), ('kelp_c', 0.6))


def kelp(c, L, amp=6, lean=0.0, cycles=1):
    """the garden sways a beat behind (lean: fronds bent back by the current while swimming)"""
    for b, ph in KELP:
        c.rot(b, (lambda ph: lambda t: (lean + 0.4 * amp * wave(t, L, cycles, ph + 0.25), 0, amp * wave(t, L, cycles, ph)))(ph))


def hold(c, pose):
    for (b, ch), v in pose.items():
        c.tracks[(b, ch)] = (lambda v: lambda t: v)(v)


LIE = {('body', 'position'): (0, -0.6, 0), ('body', 'rotation'): (-3, 0, 0), ('h_head', 'rotation'): (-8, 0, 0),
       ('tail', 'rotation'): (4, 0, 0), ('tail_fluke', 'rotation'): (-4, 0, 0),
       ('flipper_l', 'rotation'): (0, 18, 20), ('flipper_r', 'rotation'): (0, -18, -20)}


def clips(m):
    out = []

    def put(c):
        m.put(c)
        out.append(c)
        return c

    L = 5.0
    c = Clip(m, 'idle', L, 'loop')
    c.pos('body', lambda t: (0, 0.35 * wave(t, L), 0))
    c.rot('body', lambda t: (1.5 * wave(t, L, 1, 0.2), 0, 1.0 * wave(t, L, 1, 0.5)))
    c.rot('h_head', lambda t: (2 * wave(t, L, 1, 0.1), 5 * wave(t, L, 1, 0.35), 0))
    c.rot('tail', lambda t: (5 * wave(t, L, 2), 0, 0))
    c.rot('tail_fluke', lambda t: (8 * wave(t, L, 2, -0.12), 0, 0))
    c.rot('flipper_l', lambda t: (8 * wave(t, L, 2, 0.25), 0, 5 * wave(t, L, 2)))
    c.rot('flipper_r', lambda t: (8 * wave(t, L, 2, 0.75), 0, -5 * wave(t, L, 2, 0.5)))
    c.rot('jaw', lambda t: (-3 * pulse(t, 2.0, 2.8), 0, 0))
    kelp(c, L)
    put(c)

    L = 2.4
    c = Clip(m, 'swim', L, 'loop')
    c.rot('body', lambda t: (2.5 * wave(t, L, 1, 0.5), 0, 0))
    c.pos('body', lambda t: (0, 0.4 * wave(t, L, 1, 0.6), 0))
    c.rot('tail', lambda t: (10 * wave(t, L), 0, 0))
    c.rot('tail_fluke', lambda t: (16 * wave(t, L, 1, -0.15), 0, 0))
    c.rot('h_head', lambda t: (-2 * wave(t, L, 1, 0.5), 0, 0))
    c.rot('flipper_l', lambda t: (0, 24, 18 + 4 * wave(t, L)))
    c.rot('flipper_r', lambda t: (0, -24, -18 - 4 * wave(t, L)))
    kelp(c, L, 5, lean=-14)
    put(c)

    L = 2.0
    c = Clip(m, 'graze', L, 'loop')
    c.rot('body', lambda t: (-9, 0, 0))
    c.pos('body', lambda t: (0, -0.5, 0))
    c.rot('h_head', lambda t: (-16 + 3 * wave(t, L, 2), 4 * wave(t, L), 0))
    c.rot('jaw', lambda t: (-8 * abs(wave(t, L, 2)), 0, 0))
    c.rot('tail', lambda t: (6 + 3 * wave(t, L), 0, 0))
    c.rot('tail_fluke', lambda t: (5 * wave(t, L, 1, -0.2), 0, 0))
    c.rot('flipper_l', lambda t: (-10, 0, 10))
    c.rot('flipper_r', lambda t: (-10, 0, -10))
    kelp(c, L, 5)
    put(c)

    L = 2.6
    c = Clip(m, 'graze_tear', L)
    c.copy('graze')
    c.rot('h_head', lambda t: (-16 + 10 * pulse(t, 0.8, 1.4) - 4 * pulse(t, 0.3, 0.8), 14 * pulse(t, 0.6, 1.6), 0))
    put(c)

    L = 5.0
    c = Clip(m, 'lie', L, 'loop')
    hold(c, LIE)
    c.scl('body', lambda t: (1 + 0.012 * wave(t, L), 1 + 0.02 * wave(t, L), 1))
    c.rot('tail_fluke', lambda t: (-4 + 3 * wave(t, L), 0, 0))
    kelp(c, L, 4)
    put(c)

    L = 6.0
    c = Clip(m, 'sleep', L, 'loop')
    hold(c, LIE)
    c.rot('h_head', lambda t: (-12, 0, 0))
    c.scl('h_eyes', lambda t: (1, 0.06, 1))
    c.scl('body', lambda t: (1 + 0.015 * wave(t, L), 1 + 0.025 * wave(t, L), 1))
    kelp(c, L, 3)
    put(c)

    L = 3.0
    c = Clip(m, 'lie_look', L)
    hold(c, LIE)
    c.rot('h_head', lambda t: (-8 + 10 * pulse(t, 0, L), keys(t, [(0, 0), (0.8, 24), (1.8, 24), (2.6, 0), (3.0, 0)]), 0))
    kelp(c, L, 4)
    put(c)

    put(transition(m, 'lie_down', 'idle', 0, 'lie', 0, 1.6, 0.2))
    put(transition(m, 'stand_up', 'lie', 0, 'idle', 0, 1.4, 0.1))

    L = 1.6
    c = Clip(m, 'harvest', L)
    c.copy('idle')
    c.rot('body', lambda t: (0, 0, 8 * wave(t, 0.8) * pulse(t, 0, L)))
    c.rot('h_head', lambda t: (6 * pulse(t, 0, L), 26 * pulse(t, 0.1, 1.5), 0))
    c.rot('tail_fluke', lambda t: (18 * pulse(t, 0.4, 1.2), 0, 0))
    put(c)

    c = Clip(m, 'hurt', 0.5)
    c.rot('h_head', lambda t: (10 * pulse(t, 0, 0.5), 0, 0))
    c.rot('tail', lambda t: (-14 * pulse(t, 0, 0.5), 0, 0))
    c.rot('flipper_l', lambda t: (0, 0, 20 * pulse(t, 0, 0.4)))
    c.rot('flipper_r', lambda t: (0, 0, -20 * pulse(t, 0, 0.4)))
    put(c)

    L = 2.2
    c = Clip(m, 'death', L, 'hold')
    c.rot('body', lambda t: (0, 0, 150 * step(t, 0.2, 1.9)))
    c.pos('body', lambda t: (0, 3.5 * step(t, 0.2, 1.9), 0))
    c.rot('flipper_l', lambda t: (0, 0, 30 * step(t, 0.6, 1.8)))
    c.rot('flipper_r', lambda t: (0, 0, -20 * step(t, 0.6, 1.8)))
    c.rot('tail', lambda t: (6 * step(t, 0.4, 1.6), 0, 0))
    c.scl('h_eyes', lambda t: (1, 1 - 0.94 * step(t, 0.4, 1.0), 1))
    kelp(c, L, 8)
    put(c)

    L = 3.0
    c = Clip(m, 'look_around', L)
    c.copy('idle')
    c.rot('h_head', lambda t: (3 * pulse(t, 0, L), keys(t, [(0, 0), (0.7, 26), (1.4, 26), (2.2, -24), (2.7, -24), (3.0, 0)]), 0))
    put(c)

    # rises for a breath: nose up, a lift, the nostrils open (lip), settles back
    L = 3.2
    c = Clip(m, 'surface_breath', L)
    up = lambda t: step(t, 0, 1.0) * (1 - step(t, 2.2, 3.2))
    c.rot('body', lambda t: (14 * up(t), 0, 0))
    c.pos('body', lambda t: (0, 2.5 * up(t), 0))
    c.rot('h_head', lambda t: (16 * up(t), 0, 0))
    c.rot('jaw', lambda t: (-10 * pulse(t, 1.0, 1.8), 0, 0))
    c.rot('tail', lambda t: (8 * up(t) + 4 * wave(t, L, 2), 0, 0))
    c.rot('tail_fluke', lambda t: (10 * wave(t, L, 2, -0.15), 0, 0))
    c.particle(1.2, 'minecraft:bubble', 10, 0.4, 0.08, 0.9)
    kelp(c, L, 7)
    put(c)

    # a lazy barrel roll
    L = 3.4
    c = Clip(m, 'roll', L)
    c.rot('body', lambda t: (0, 0, 360 * step(t, 0.2, 3.2)))
    c.pos('body', lambda t: (0, 5.0 * pulse(t, 0.2, 3.2), 0))
    c.rot('flipper_l', lambda t: (0, 0, 24 * pulse(t, 0.2, 3.2)))
    c.rot('flipper_r', lambda t: (0, 0, -24 * pulse(t, 0.2, 3.2)))
    kelp(c, L, 10, cycles=2)
    put(c)

    L = 2.6
    c = Clip(m, 'flipper_scratch', L)
    c.copy('idle')
    c.rot('flipper_l', lambda t: (-30 * pulse(t, 0.2, 2.4), 40 * pulse(t, 0.2, 2.4), 36 * pulse(t, 0.2, 2.4) + 8 * wave(t, 0.5) * pulse(t, 0.6, 2.0)))
    c.rot('h_head', lambda t: (-4 * pulse(t, 0.2, 2.4), 14 * pulse(t, 0.2, 2.4), -10 * pulse(t, 0.2, 2.4)))
    put(c)

    L = 1.6
    c = Clip(m, 'look_up', L)
    c.copy('idle')
    c.rot('h_head', lambda t: (16 * pulse(t, 0, L), 0, 0))
    c.rot('jaw', lambda t: (-6 * pulse(t, 0.5, 1.1), 0, 0))
    put(c)

    L = 2.0
    c = Clip(m, 'chew', L)
    c.copy('idle')
    c.rot('jaw', lambda t: (-7 * (pulse(t, 0.2, 0.6) + pulse(t, 0.7, 1.1) + pulse(t, 1.2, 1.6)), 0, 0))
    put(c)
    return out


TAGS = {
    'graze': {'p': 'standing'}, 'graze_tear': {'p': 'standing'},
    'lie': {'p': 'lying'}, 'sleep': {'p': 'lying'}, 'lie_look': {'p': 'lying'},
    'surface_breath': {'move': False}, 'roll': {'move': False},
}
IDLE = {
    'standing': [
        {'clip': '@mood', 'w': 5, 'min': 6, 'max': 14},
        {'clip': 'look_around', 'w': 2, 'cd': 14},
        {'clip': 'flipper_scratch', 'w': 2, 'cd': 18},
        {'clip': 'roll', 'w': 1, 'cd': 40},
        {'clip': 'chew', 'w': 2, 'cd': 10},
    ],
    'graze': [
        {'clip': '@mood', 'w': 4, 'min': 3, 'max': 6},
        {'clip': 'graze_tear', 'w': 3, 'cd': 6},
        {'clip': 'chew', 'w': 1, 'cd': 8},
    ],
    'lying': [
        {'clip': '@mood', 'w': 3, 'min': 10, 'max': 25},
        {'clip': 'lie_look', 'w': 2, 'cd': 12},
        {'clip': 'sleep', 'w': 0.6, 'min': 20, 'max': 60, 'boost': {'night': 10}},
    ],
    'notice': [
        {'clip': 'look_up', 'w': 3},
        {'clip': 'chew', 'w': 1},
    ],
}
STATES = {'sleep': 'sleep'}
PROCEDURAL = {'tail_sway': 0, 'breath': 0.008}
