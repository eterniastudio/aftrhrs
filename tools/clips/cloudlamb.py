"""Cloudlamb (floating cloud-wool lamb with a rainbow star charm): fluffy bounces, wool puffs, floating hops, lazy
drifts, grazing. Phase 2."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403

LEGS_F = ('left_front_leg', 'right_front_leg')
LEGS_B = ('left_back_leg', 'right_back_leg')
LEGS = LEGS_F + LEGS_B


def ears(c, fn):
    """fn(t) -> degrees the ears flap out (left z-, right z+)."""
    c.rot('left_ear', lambda t: (0, 0, -fn(t)), add=True)
    c.rot('right_ear', lambda t: (0, 0, fn(t)), add=True)


def tuck(c, fn):
    """Legs tuck up under the fluff (fn 0..1)."""
    for leg in LEGS_F:
        c.rot(leg, lambda t: (-35 * fn(t), 0, 0), add=True)
    for leg in LEGS_B:
        c.rot(leg, lambda t: (35 * fn(t), 0, 0), add=True)


def puff(c, fn, amt=0.22):
    c.scl('body', lambda t: (1 + amt * fn(t), 1 + amt * 0.9 * fn(t), 1 + amt * fn(t)), add=True)


def clips(m):
    out = []

    # --- cloud bounce (standing loop): two soft bounces on the air, squash at the bottom, legs tuck on the way up
    L = 3.2
    c = Clip(m, 'idle_bounce', L, 'loop')
    loop_base(c, m)
    up = lambda t: math.sin(math.pi * ((t % (L / 2)) / (L / 2))) ** 2
    c.pos('root', lambda t: (0, 4.0 * up(t), 0), add=True)
    sq = lambda t: pulse(t % (L / 2), -0.01, 0.22) + pulse(t % (L / 2), L / 2 - 0.22, L / 2 + 0.01)
    c.scl('body', lambda t: (1 + 0.12 * sq(t), 1 - 0.12 * sq(t), 1 + 0.12 * sq(t)), add=True)
    tuck(c, lambda t: 0.6 * up(t))
    ears(c, lambda t: 18 * up(t))
    c.rot('h_head', lambda t: (8 * up(t), 0, 0), add=True)
    c.rot('charm', lambda t: (-20 * up(t), 0, 0), add=True)
    c.rot('tail', lambda t: (0, 20 * wave(t, L, 4), 0), add=True)
    c.sound(0.05, 'minecraft:block.wool.step', 0.35, 1.4)
    c.sound(1.65, 'minecraft:block.wool.step', 0.35, 1.5)
    out.append(keep(m, c))

    # --- wool puff: it takes a breath and the wool billows huge, then sighs back down with a wobble
    L = 2.2
    c = one_shot(m, 'wool_puff', L)
    inflate = lambda t: keys(t, [(0, 0), (0.15, -0.15), (0.75, 1), (1.2, 1), (1.6, -0.1), (1.9, 0.04), (2.2, 0)])
    puff(c, inflate, 0.28)
    c.pos('root', lambda t: (0, 1.5 * inflate(t), 0), add=True)
    ears(c, lambda t: 30 * inflate(t))
    c.rot('h_head', lambda t: (14 * inflate(t), 0, 0), add=True)
    tuck(c, lambda t: 0.5 * max(0.0, inflate(t)))
    c.rot('tail', lambda t: (-30 * max(0.0, inflate(t)), 0, 0), add=True)
    c.sound(0.25, 'minecraft:entity.breeze.inhale', 0.25, 1.7)
    c.sound(1.25, 'minecraft:block.wool.place', 0.6, 1.2)
    out.append(keep(m, c))

    # --- float hop: crouch, spring up high and drift down slowly like a dandelion, legs paddling, soft landing
    L = 3.2
    c = one_shot(m, 'float_hop', L)
    rise = lambda t: keys(t, [(0, 0), (0.25, -0.12), (0.7, 1.0), (2.4, 0.15), (2.7, -0.08), (3.2, 0)], 'smooth')
    c.pos('root', lambda t: (0, 9 * rise(t), 0), add=True)
    puff(c, lambda t: 0.6 * pulse(t, 0.7, 2.4) - 0.5 * pulse(t, 0.05, 0.35) - 0.5 * pulse(t, 2.45, 2.9), 0.2)
    for i, leg in enumerate(LEGS):
        c.rot(leg, (lambda i: lambda t: (24 * math.sin(2 * math.pi * 3 * t / 1.6 + i * 1.6) * pulse(t, 0.7, 2.5), 0, 0))(i), add=True)
    ears(c, lambda t: 25 * pulse(t, 0.3, 2.4) + 10 * math.sin(2 * math.pi * 4 * t / 1.6) * pulse(t, 0.7, 2.4))
    c.rot('h_head', lambda t: (16 * pulse(t, 0.3, 1.2) - 10 * pulse(t, 1.4, 2.6), 0, 0), add=True)
    c.rot('body', lambda t: (0, 0, 6 * math.sin(2 * math.pi * t / 1.6) * pulse(t, 0.7, 2.5)), add=True)
    c.rot('charm', lambda t: (-30 * pulse(t, 0.4, 1.2) + 25 * pulse(t, 1.2, 2.6), 0, 0), add=True)
    c.sound(0.35, 'minecraft:entity.breeze.jump', 0.25, 1.6)
    c.sound(2.55, 'minecraft:block.wool.fall', 0.5, 1.3)
    out.append(keep(m, c))

    # --- drift spin: lets the breeze turn it all the way round, legs dangling, dreamy
    L = 3.6
    c = one_shot(m, 'drift_spin', L)
    c.rot('root', lambda t: (0, 360 * step(t, 0.4, 3.2), 0), add=True)
    c.pos('root', lambda t: (0, 2.5 * pulse(t, 0.2, 3.4), 0), add=True)
    tuck(c, lambda t: -0.4 * pulse(t, 0.3, 3.3))
    c.rot('h_head', lambda t: (10 * pulse(t, 0.3, 3.3), 0, -8 * pulse(t, 0.3, 3.3)), add=True)
    ears(c, lambda t: 12 * pulse(t, 0.3, 3.3) + 6 * wave(t, L, 4) * pulse(t, 0.3, 3.3))
    c.rot('body', lambda t: (0, 0, 7 * pulse(t, 0.4, 3.2)), add=True)
    c.sound(0.5, 'minecraft:entity.breeze.idle_air', 0.2, 1.4)
    out.append(keep(m, c))

    # --- graze: drifts nose-down to nibble the grass, chews, looks up chewing
    L = 3.4
    c = one_shot(m, 'graze', L)
    down = lambda t: keys(t, [(0, 0), (0.5, 1), (2.2, 1), (2.7, 0), (3.4, 0)])
    c.rot('h_head', lambda t: (-38 * down(t) + 4 * math.sin(2 * math.pi * 5 * t / 1.0) * (pulse(t, 0.6, 2.2) + pulse(t, 2.5, 3.3)), 0, 0), add=True)
    c.rot('body', lambda t: (-8 * down(t), 0, 0), add=True)
    c.pos('root', lambda t: (0, -1.5 * down(t), 0), add=True)
    tuck(c, lambda t: -0.3 * down(t))
    ears(c, lambda t: 10 * down(t))
    c.rot('tail', lambda t: (0, 16 * wave(t, L, 5) * ramp(t, L, 0.4), 0), add=True)
    c.sound(0.9, 'minecraft:block.grass.break', 0.35, 1.5)
    c.sound(1.6, 'minecraft:entity.generic.eat', 0.3, 1.6)
    out.append(keep(m, c))

    # --- ear flop (head-only overlay, also sitting)
    L = 1.4
    c = Clip(m, 'ear_flop', L, 'once')
    c.rot('left_ear', lambda t: (0, 0, -30 * pulse(t, 0.05, 0.5) + 20 * pulse(t, 0.5, 0.95) - 12 * pulse(t, 0.9, 1.35)))
    c.rot('right_ear', lambda t: (0, 0, 30 * pulse(t, 0.2, 0.65) - 20 * pulse(t, 0.65, 1.1) + 10 * pulse(t, 1.0, 1.4)))
    c.rot('h_head', lambda t: (0, 0, 12 * pulse(t, 0.0, 0.7) - 12 * pulse(t, 0.6, 1.4)))
    out.append(keep(m, c))

    # --- sitting loops (the sit is a loaf on the cloud)
    L = 5.0
    c = sit_loop(m, 'sit_fluff', L)
    breathe = lambda t: 0.5 - 0.5 * math.cos(2 * math.pi * 2 * t / L)
    puff(c, breathe, 0.07)
    ears(c, lambda t: 8 * wave(t, L, 2))
    c.rot('h_head', lambda t: (5 * wave(t, L, 1), 14 * wave(t, L, 1, 0.25), 0), add=True)
    c.rot('tail', lambda t: (0, 14 * wave(t, L, 3), 0), add=True)
    out.append(keep(m, c))

    L = 6.0
    c = sit_loop(m, 'sit_breeze_rock', L)
    c.rot('body', lambda t: (3 * wave(t, L, 2, 0.25), 0, 6 * wave(t, L, 1)), add=True)
    c.pos('body', lambda t: (0, 0.6 * (0.5 + 0.5 * wave(t, L, 2)), 0), add=True)
    c.rot('h_head', lambda t: (0, 0, -6 * wave(t, L, 1, -0.05)), add=True)
    ears(c, lambda t: 6 * wave(t, L, 1, -0.1))
    c.rot('charm', lambda t: (8 * wave(t, L, 2), 0, -10 * wave(t, L, 1, -0.1)), add=True)
    out.append(keep(m, c))

    # --- lying loops: a sleeping cloud rises and sinks with its breath
    L = 6.0
    c = sleep_loop(m, 'sleep_float', L)
    c.pos('root', lambda t: (0, 1.4 * (0.5 - 0.5 * math.cos(2 * math.pi * t / L)), 0), add=True)
    puff(c, lambda t: 0.5 - 0.5 * math.cos(2 * math.pi * 2 * t / L), 0.05)
    c.rot('body', lambda t: (0, 0, 3 * wave(t, L, 1)), add=True)
    out.append(keep(m, c))

    L = 6.0
    c = sleep_loop(m, 'sleep_ear_twitch', L)
    c.rot('left_ear', lambda t: (0, 0, -16 * pulse(t, 1.0, 1.3) - 12 * pulse(t, 1.35, 1.6)), add=True)
    c.rot('right_ear', lambda t: (0, 0, 14 * pulse(t, 3.6, 3.9)), add=True)
    c.rot('tail', lambda t: (0, 18 * pulse(t, 4.4, 4.9), 0), add=True)
    c.rot('h_head', lambda t: (0, 4 * pulse(t, 2.0, 4.0), 0), add=True)
    out.append(keep(m, c))

    # --- reactions
    L = 2.0   # petting: melts into the hand, the wool fluffs, a happy little bob
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.35)
    puff(c, e, 0.12)
    c.rot('h_head', lambda t: (14 * e(t), 0, 18 * e(t)), add=True)
    c.rot('body', lambda t: (0, 0, 6 * e(t)), add=True)
    c.pos('root', lambda t: (0, 1.2 * e(t) + 0.6 * wave(t, L, 3) * e(t), 0), add=True)
    ears(c, lambda t: 22 * e(t))
    c.rot('tail', lambda t: (0, 24 * wave(t, L, 6) * e(t), 0), add=True)
    out.append(keep(m, c))

    L = 1.6
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    base(c, {k: v for k, v in sit.items() if k[0] in ('h_head', 'left_ear', 'right_ear', 'tail')})
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (12 * e(t), 0, 16 * e(t)), add=True)
    ears(c, lambda t: 20 * e(t))
    c.rot('tail', lambda t: (0, 22 * wave(t, L, 5) * e(t), 0), add=True)
    out.append(keep(m, c))

    L = 3.0   # bond level-up: a rocket of a float-hop, a full twirl at the top, three wool puffs on the way down
    c = one_shot(m, 'level_up', L)
    rise = lambda t: keys(t, [(0, 0), (0.25, -0.1), (0.7, 1.0), (1.4, 1.0), (2.5, 0.0), (3.0, 0)])
    c.pos('root', lambda t: (0, 12 * rise(t), 0), add=True)
    c.rot('root', lambda t: (0, 360 * step(t, 0.6, 1.4), 0), add=True)
    puff(c, lambda t: pulse(t, 1.3, 1.65) + pulse(t, 1.7, 2.05) + pulse(t, 2.1, 2.5), 0.2)
    tuck(c, lambda t: pulse(t, 0.3, 1.5))
    ears(c, lambda t: 30 * pulse(t, 0.3, 2.6))
    c.rot('charm', lambda t: (0, 720 * step(t, 0.5, 2.2), 0), add=True)
    c.rot('h_head', lambda t: (18 * pulse(t, 0.3, 1.5), 0, 0), add=True)
    for tb in (1.45, 1.85, 2.25):
        c.sound(tb, 'minecraft:block.wool.place', 0.6, 1.1 + 0.15 * (tb - 1.45))
    c.sound(0.35, 'minecraft:entity.breeze.jump', 0.3, 1.5)
    out.append(keep(m, c))
    return out


TAGS = {
    'wool_puff': {'layer': 'base', 'move': False}, 'float_hop': {'layer': 'base', 'move': False},
    'drift_spin': {'layer': 'base', 'move': False}, 'graze': {'layer': 'base', 'move': False},
    'idle_bounce': {'p': 'standing'}, 'ear_flop': {'layer': 'overlay', 'move': True},
    'sit_fluff': {'p': 'sitting'}, 'sit_breeze_rock': {'p': 'sitting'},
    'sleep_float': {'p': 'lying'}, 'sleep_ear_twitch': {'p': 'lying'},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': True},
    'level_up': {'layer': 'base', 'move': False},
}

IDLE = {
    'standing': [
        idle_entry('idle_bounce', 3, mn=6, mx=13, cd=15, boost={'happy': 2}),
        idle_entry('wool_puff', 2, cd=25, boost={'cold': 3}),
        idle_entry('float_hop', 2, cd=30, boost={'owner_near': 1.5}),
        idle_entry('drift_spin', 1, cd=35),
        idle_entry('graze', 2, cd=30, when='day'),
        idle_entry('ear_flop', 1, cd=12),
    ],
    'sitting': [
        idle_entry('sit_fluff', 3, mn=6, mx=14, cd=10),
        idle_entry('sit_breeze_rock', 2, mn=6, mx=12, cd=18, boost={'rain': 2}),
    ],
    'lying': [
        idle_entry('sleep_float', 2, mn=20, mx=50),
        idle_entry('sleep_ear_twitch', 1, mn=6, mx=12, cd=30),
    ],
}

PROCEDURAL = {'tail_sway': 8, 'ear_twitch': 3, 'breath': 0.018}
