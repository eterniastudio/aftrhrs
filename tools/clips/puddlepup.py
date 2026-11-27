"""Puddlepup (axolotl water puppy with a bubble on its head): splashy shakes, puddle stomps, bubble play.
Phase 2: signature idles, sit / sleep variants, an in-water roll, pet / level-up reactions, clip-synced splashes."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _life import *  # noqa: E402,F401,F403

LEGS_F = ('left_front_leg', 'right_front_leg')
LEGS_B = ('left_back_leg', 'right_back_leg')
GILLS = ('left_gill', 'right_gill')


def gills(c, amp, cycles, env_fn=lambda t: 1.0, base=0.0):
    """Gill fronds flutter (left z+, right mirrored)."""
    c.rot('left_gill', lambda t: (0, 0, (base + amp * wave(t, c.L, cycles)) * env_fn(t)), add=True)
    c.rot('right_gill', lambda t: (0, 0, -(base + amp * wave(t, c.L, cycles)) * env_fn(t)), add=True)


def clips(m):
    out = []

    # --- puddle stomp: head down at the "puddle", front paws stomp left-right-left-right, each one a splash
    L = 2.4
    c = one_shot(m, 'puddle_stomp', L)
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (-16 * e(t), 0, 6 * wave(t, L, 2) * e(t)), add=True)
    c.rot('body', lambda t: (-5 * e(t), 0, 0), add=True)
    beats = [0.45, 0.85, 1.25, 1.65]
    for i, tb in enumerate(beats):
        leg = LEGS_F[i % 2]
        c.rot(leg, (lambda tb: lambda t: (38 * pulse(t, tb - 0.3, tb) , 0, 0))(tb), add=True)
        c.pos(leg, (lambda tb: lambda t: (0, 1.4 * pulse(t, tb - 0.3, tb), 0))(tb), add=True)
        c.pos('body', (lambda tb: lambda t: (0, -0.35 * pulse(t, tb - 0.02, tb + 0.14), 0))(tb), add=True)
        c.rot('body', (lambda tb, s: lambda t: (0, 0, s * 4 * pulse(t, tb - 0.3, tb + 0.1)))(tb, 1 if i % 2 else -1), add=True)
        c.sound(tb, 'splash', 0.35, 1.3 + 0.08 * i)
    tail_wag(c, ['tail', 'tail_fin'], 14, 6, env_fn=e)
    c.pos('bubble', lambda t: (0, 0.6 * sum(pulse(t, tb, tb + 0.25) for tb in beats), 0), add=True)
    gills(c, 10, 8, e, 6)
    out.append(keep(m, c))

    # --- shake: the big wet-dog shake (head leads, body follows, tail whips last), drops flying
    L = 1.5
    c = one_shot(m, 'shake', L)
    c.rot('h_head', lambda t: (-6 * pulse(t, 0.1, 1.3), 0, 0), add=True)
    shake_body(c, 'h_head', 0.15, 1.15, 26, 5)
    shake_body(c, 'body', 0.22, 1.25, 20, 5)
    shake_body(c, 'tail', 0.3, 1.35, 28, 5, axis=1)
    shake_body(c, 'tail_fin', 0.36, 1.42, 30, 5, axis=1)
    for g, s in (('left_gill', 1), ('right_gill', -1)):
        c.rot(g, (lambda s: lambda t: (0, 0, s * (14 * pulse(t, 0.1, 1.3) + 30 * math.sin(2 * math.pi * 6 * t) * pulse(t, 0.2, 1.2))))(s), add=True)
    c.pos('bubble', lambda t: (0, 0.8 * pulse(t, 0.2, 1.2), 0), add=True)
    c.scl('bubble', lambda t: (1 + 0.15 * math.sin(2 * math.pi * 7 * t) * pulse(t, 0.2, 1.3),
                               1 - 0.15 * math.sin(2 * math.pi * 7 * t) * pulse(t, 0.2, 1.3), 1), add=True)
    for leg in LEGS_F + LEGS_B:
        c.pos(leg, lambda t: (0, 0.3 * pulse(t, 0.15, 1.25), 0), add=True)
    c.pos('body', lambda t: (0, -0.4 * pulse(t, 0.0, 0.2) + 0.3 * pulse(t, 1.25, 1.5), 0), add=True)
    c.sound(0.25, 'minecraft:entity.wolf.shake', 0.5, 1.5)
    c.sound(0.7, 'splash', 0.3, 1.6)
    out.append(keep(m, c))

    # --- bubble bat: the head bubble floats up, it watches, rears up on its hind legs and boops it back
    L = 2.6
    c = one_shot(m, 'bubble_bat', L)
    c.pos('bubble', lambda t: (0, keys(t, [(0, 0), (0.2, 0), (1.0, 4.5), (1.45, 5.0), (1.6, 2.2), (2.0, 0.6), (2.6, 0)]),
                               keys(t, [(0, 0), (1.0, 0), (1.45, -0.6), (1.7, 0.2), (2.6, 0)])), add=True)
    c.scl('bubble', lambda t: tuple([1 + 0.12 * pulse(t, 1.45, 1.75)] * 3), add=True)
    c.rot('h_head', lambda t: (keys(t, [(0, 0), (0.3, 0), (1.0, 26), (1.45, 30), (1.8, 10), (2.3, -4), (2.6, 0)]),
                               0, keys(t, [(0, 0), (0.6, 10), (1.1, -6), (1.5, 0), (2.6, 0)])), add=True)
    rear = lambda t: keys(t, [(0, 0), (1.05, 0), (1.35, 1), (1.6, 1), (1.95, 0), (2.6, 0)])
    c.rot('body', lambda t: (22 * rear(t), 0, 0), add=True)
    c.pos('body', lambda t: (0, 1.2 * rear(t), 0.6 * rear(t)), add=True)
    c.rot('h_head', lambda t: (-14 * rear(t), 0, 0), add=True)
    c.rot('left_back_leg', lambda t: (-22 * rear(t), 0, 0), add=True)
    c.rot('right_back_leg', lambda t: (-22 * rear(t), 0, 0), add=True)
    c.rot('right_front_leg', lambda t: (keys(t, [(0, 0), (1.1, 0), (1.35, 70), (1.48, 95), (1.7, 40), (2.0, 0)]), 0, 8 * rear(t)), add=True)
    c.rot('left_front_leg', lambda t: (30 * rear(t), 0, -6 * rear(t)), add=True)
    tail_wag(c, ['tail', 'tail_fin'], 10, 3, env_fn=lambda t: ramp(t, L, 0.4))
    gills(c, 8, 6, lambda t: pulse(t, 0.8, 2.2), 14)
    c.sound(1.45, 'minecraft:block.bubble_column.bubble_pop', 0.6, 1.5)
    c.sound(2.05, 'minecraft:block.bubble_column.bubble_pop', 0.4, 1.8)
    out.append(keep(m, c))

    # --- splash pounce: play-bow wiggle, a pounce forward-and-up, splashy landing
    L = 2.2
    c = one_shot(m, 'puddle_pounce', L)
    bow = lambda t: keys(t, [(0, 0), (0.3, 1), (0.85, 1), (1.0, 0), (2.2, 0)])
    c.rot('body', lambda t: (-14 * bow(t) + 10 * pulse(t, 1.0, 1.35) - 6 * pulse(t, 1.35, 1.6), 0, 0), add=True)
    c.pos('body', lambda t: (0, -1.0 * bow(t), 0), add=True)
    for leg in LEGS_F:
        c.rot(leg, lambda t: (-40 * bow(t) + 45 * pulse(t, 0.95, 1.35), 0, 0), add=True)
    for leg in LEGS_B:
        c.rot(leg, lambda t: (-30 * pulse(t, 0.95, 1.3), 0, 0), add=True)
    c.rot('h_head', lambda t: (8 * bow(t) - 12 * pulse(t, 1.35, 1.7), 0, 0), add=True)
    c.rot('tail', lambda t: (10 * bow(t), 28 * wave(t, 0.3) * bow(t), 0), add=True)
    hop(c, 'root', 1.0, 1.38, 4.5, 'body')
    c.pos('root', lambda t: (0, 0, -3 * step(t, 1.0, 1.38) * (1 - step(t, 1.5, 2.2))), add=True)
    shake_body(c, 'body', 1.45, 2.05, 8, 3)
    c.sound(1.38, 'splash', 0.6, 1.2)
    c.sound(1.5, 'minecraft:block.pointed_dripstone.drip_water', 0.6, 1.3)
    out.append(keep(m, c))

    # --- standing loop: splashy fidget (weight shifts, toe taps, gill fans, tail swishes)
    L = 6.0
    c = Clip(m, 'idle_splashy', L, 'loop')
    loop_base(c, m)
    c.rot('body', lambda t: (0, 0, 4 * wave(t, L, 2)), add=True)
    c.rot('h_head', lambda t: (5 * wave(t, L, 1, 0.25), 14 * wave(t, L, 1), -6 * wave(t, L, 2)), add=True)
    for i, leg in enumerate(LEGS_F):
        c.rot(leg, (lambda i: lambda t: (20 * pulse((t + i * 1.5) % L, 2.0, 2.45) + 20 * pulse((t + i * 1.5) % L, 2.5, 2.95), 0, 0))(i), add=True)
    tail_wag(c, ['tail', 'tail_fin'], 12, 4)
    gills(c, 12, 6)
    c.sound(2.25, 'minecraft:block.pointed_dripstone.drip_water', 0.25, 1.4)
    c.sound(3.75, 'minecraft:block.pointed_dripstone.drip_water', 0.25, 1.2)
    out.append(keep(m, c))

    # --- gill flutter (head-only overlay, also used sitting): gills fan wide, head cocks
    L = 1.6
    c = Clip(m, 'gill_flutter', L, 'once')
    e = lambda t: ramp(t, L, 0.35)
    c.rot('h_head', lambda t: (4 * e(t), 0, 14 * e(t)))
    c.rot('left_gill', lambda t: (0, -8 * e(t), (22 + 14 * wave(t, L, 6)) * e(t)))
    c.rot('right_gill', lambda t: (0, 8 * e(t), -(22 + 14 * wave(t, L, 6)) * e(t)))
    c.pos('bubble', lambda t: (0, 0.5 * e(t), 0))
    out.append(keep(m, c))

    # --- sitting loops
    L = 5.0
    c = sit_loop(m, 'sit_gill_fan', L)
    gills(c, 14, 5, base=8)
    c.rot('h_head', lambda t: (4 * wave(t, L, 1), 16 * wave(t, L, 1, 0.25), 8 * wave(t, L, 2)), add=True)
    c.rot('tail', lambda t: (0, 16 * wave(t, L, 2), 0), add=True)
    c.rot('tail_fin', lambda t: (0, 20 * wave(t, L, 2, -0.1), 0), add=True)
    c.pos('bubble', lambda t: (0, 0.4 * wave(t, L, 2), 0), add=True)
    out.append(keep(m, c))

    L = 4.0
    c = sit_loop(m, 'sit_paw_splash', L)
    for i, leg in enumerate(LEGS_F):
        c.rot(leg, (lambda i: lambda t: (34 * pulse((t + 2.0 * i) % L, 0.6, 1.1), 0, 0))(i), add=True)
        c.pos(leg, (lambda i: lambda t: (0, 0.8 * pulse((t + 2.0 * i) % L, 0.6, 1.1), 0))(i), add=True)
    c.rot('h_head', lambda t: (-10 * (pulse(t, 0.4, 1.4) + pulse(t, 2.4, 3.4)), 0, 7 * wave(t, L, 1)), add=True)
    c.rot('tail', lambda t: (0, 10 * wave(t, L, 2), 0), add=True)
    c.sound(1.05, 'minecraft:block.pointed_dripstone.drip_water', 0.3, 1.5)
    c.sound(3.05, 'minecraft:block.pointed_dripstone.drip_water', 0.3, 1.3)
    out.append(keep(m, c))

    # --- lying loops (sleep-type: eyes as the sleep clip)
    L = 6.0
    c = sleep_loop(m, 'sleep_bubbles', L)
    c.scl('bubble', lambda t: tuple([1 + 0.25 * (0.5 + 0.5 * wave(t, L, 2, -0.25))] * 3), add=True)
    c.pos('bubble', lambda t: (0, 0.6 * (0.5 + 0.5 * wave(t, L, 2, -0.25)), 0), add=True)
    c.scl('body', lambda t: (1 + 0.025 * wave(t, L, 2), 1 + 0.03 * wave(t, L, 2), 1), add=True)
    gills(c, 6, 2)
    c.sound(1.5, 'minecraft:block.bubble_column.bubble_pop', 0.18, 1.1)
    c.sound(4.5, 'minecraft:block.bubble_column.bubble_pop', 0.18, 0.95)
    out.append(keep(m, c))

    L = 6.0
    c = sleep_loop(m, 'sleep_dream_paddle', L)
    paddle = lambda t: pulse(t, 1.0, 4.2)
    for i, leg in enumerate(LEGS_F + LEGS_B):
        c.rot(leg, (lambda i: lambda t: (18 * math.sin(2 * math.pi * 3 * t / 3.2 + i * math.pi / 2) * paddle(t), 0, 0))(i), add=True)
    c.rot('tail', lambda t: (0, 14 * wave(t, L, 6) * paddle(t), 0), add=True)
    c.rot('h_head', lambda t: (3 * paddle(t), 0, 0), add=True)
    gills(c, 10, 6, paddle)
    out.append(keep(m, c))

    # --- in water: a happy barrel roll (on the swim idle)
    L = 1.8
    c = Clip(m, 'swim_roll', L, 'once')
    loop_base(c, m, 'idle_swim')
    c.rot('root', lambda t: (0, 0, 360 * step(t, 0.25, 1.4)), add=True)
    c.pos('root', lambda t: (0, 1.5 * pulse(t, 0.2, 1.5), 0), add=True)
    tail_wag(c, ['tail', 'tail_fin'], 20, 3, env_fn=lambda t: ramp(t, L, 0.3))
    c.sound(0.4, 'minecraft:block.bubble_column.bubble_pop', 0.5, 1.3)
    out.append(keep(m, c))

    L = 3.0
    c = Clip(m, 'swim_bubble_blow', L, 'once')
    loop_base(c, m, 'idle_swim')
    c.pos('bubble', lambda t: (0, 3.5 * pulse(t, 0.6, 2.4), -1.0 * pulse(t, 0.6, 2.4)), add=True)
    c.scl('bubble', lambda t: tuple([1 + 0.5 * pulse(t, 0.6, 2.2)] * 3), add=True)
    c.rot('h_head', lambda t: (16 * pulse(t, 0.3, 2.6), 0, 0), add=True)
    gills(c, 10, 6, lambda t: ramp(t, L, 0.4), 10)
    c.sound(2.2, 'minecraft:block.bubble_column.bubble_pop', 0.6, 1.2)
    out.append(keep(m, c))

    # --- reactions
    L = 1.8   # petting: leans up into the hand, gills flare, tail goes wild, a little wiggle
    c = one_shot(m, 'pet', L)
    e = lambda t: ramp(t, L, 0.3)
    c.rot('h_head', lambda t: (22 * e(t), 10 * wave(t, L, 2) * e(t), 14 * e(t)), add=True)
    c.rot('body', lambda t: (6 * e(t), 0, 5 * wave(t, L, 3) * e(t)), add=True)
    c.pos('body', lambda t: (0, 0.5 * e(t), 0), add=True)
    gills(c, 14, 6, e, 18)
    tail_wag(c, ['tail', 'tail_fin'], 24, 7, env_fn=e)
    c.rot('left_back_leg', lambda t: (-8 * e(t), 0, 0), add=True)
    c.rot('right_back_leg', lambda t: (-8 * e(t), 0, 0), add=True)
    out.append(keep(m, c))

    L = 1.6   # pet while sitting: overlay on the sit pose (head, gills, tail only)
    c = Clip(m, 'pet_sit', L, 'once')
    sit = m.pose('sit', 'end')
    e = lambda t: ramp(t, L, 0.3)
    hs = sit[('h_head', 'rotation')]
    c.rot('h_head', lambda t: (hs[0] + 20 * e(t), hs[1] + 8 * wave(t, L, 2) * e(t), hs[2] + 10 * e(t)))
    for g, s in (('left_gill', 1), ('right_gill', -1)):
        b = sit.get((g, 'rotation'), (0, 0, 0))
        c.rot(g, (lambda b, s: lambda t: (b[0], b[1], b[2] + s * (18 + 12 * wave(t, L, 5)) * e(t)))(b, s))
    tb = sit[('tail', 'rotation')]
    c.rot('tail', lambda t: (tb[0], tb[1] + 22 * wave(t, L, 6) * e(t), tb[2]))
    out.append(keep(m, c))

    L = 2.6   # bond level-up: a splash leap with a full spin, a big shake on landing
    c = one_shot(m, 'level_up', L)
    hop(c, 'root', 0.35, 1.05, 7, 'body')
    c.rot('root', lambda t: (0, 360 * step(t, 0.35, 1.05), 0), add=True)
    c.rot('h_head', lambda t: (20 * pulse(t, 0.2, 1.1), 0, 0), add=True)
    for leg in LEGS_F:
        c.rot(leg, lambda t: (-50 * pulse(t, 0.35, 1.05), 0, 0), add=True)
    for leg in LEGS_B:
        c.rot(leg, lambda t: (40 * pulse(t, 0.35, 1.05), 0, 0), add=True)
    gills(c, 10, 8, lambda t: pulse(t, 0.2, 1.2), 26)
    shake_body(c, 'body', 1.25, 2.2, 18, 4)
    shake_body(c, 'h_head', 1.2, 2.15, 14, 4)
    tail_wag(c, ['tail', 'tail_fin'], 26, 9, env_fn=lambda t: ramp(t, L, 0.3))
    c.sound(0.35, 'splash', 0.5, 1.5)
    c.sound(1.05, 'splash', 0.8, 1.1)
    c.sound(1.4, 'minecraft:entity.wolf.shake', 0.4, 1.6)
    out.append(keep(m, c))
    return out


TAGS = {
    'puddle_stomp': {'layer': 'base', 'move': False}, 'bubble_bat': {'layer': 'base', 'move': False},
    'puddle_pounce': {'layer': 'base', 'move': False}, 'shake': {'layer': 'base', 'move': False, 'p': 'standing'},
    'idle_splashy': {'p': 'standing'}, 'gill_flutter': {'layer': 'overlay', 'move': True},
    'sit_gill_fan': {'p': 'sitting'}, 'sit_paw_splash': {'p': 'sitting'},
    'sleep_bubbles': {'p': 'lying'}, 'sleep_dream_paddle': {'p': 'lying'},
    'swim_roll': {'p': 'swimming', 'layer': 'base', 'move': True}, 'swim_bubble_blow': {'p': 'swimming', 'layer': 'base', 'move': True},
    'pet': {'layer': 'base', 'move': False}, 'pet_sit': {'layer': 'overlay', 'move': False},
    'level_up': {'layer': 'base', 'move': False},
}

IDLE = {
    'standing': [
        idle_entry('puddle_stomp', 2, cd=25, boost={'rain': 3, 'owner_near': 1.5}),
        idle_entry('bubble_bat', 2, cd=30),
        idle_entry('puddle_pounce', 1, cd=35, boost={'owner_near': 2}),
        idle_entry('shake', 1, cd=40, boost={'rain': 4}),
        idle_entry('idle_splashy', 2, mn=6, mx=12, cd=20),
        idle_entry('gill_flutter', 1, cd=15),
        idle_entry('swim_roll', 2, cd=14, when='mood:idle_swim'),
        idle_entry('swim_bubble_blow', 2, cd=16, when='mood:idle_swim'),
    ],
    'sitting': [
        idle_entry('sit_gill_fan', 3, mn=6, mx=14, cd=12),
        idle_entry('sit_paw_splash', 2, mn=4, mx=8, cd=20),
    ],
    'lying': [
        idle_entry('sleep_bubbles', 2, mn=20, mx=50),
        idle_entry('sleep_dream_paddle', 1, mn=6, mx=12, cd=40),
    ],
}

PROCEDURAL = {'tail': ['tail', 'tail_fin'], 'tail_sway': 7, 'ears': ['left_gill', 'right_gill'], 'ear_twitch': 6,
              'breath': 0.015}
GAIT = {}
