"""Cairnling (phase 2): a literal stack of stones, stoic, not an animal. Its life is geological: the stones settle and
re-stack, a pebble tumbles off its back and pops back into place, it grinds its top stone round, its crystal hums.
Sitting = sinking into a dormant rock (legs drawn in, arms tucked, head stone sunk). The generic animal flourishes
its mood pools name are re-authored as stone versions (roll_over = a stack wobble, spin = a top-stone grind,
play_bow = a slow stoic bow, pant = settling breaths, sneeze = a pebble puff)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import decay, posture_set  # noqa: E402
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, overlay_from, start, step, Clip  # noqa: E402

TUFF = 'minecraft:block.tuff.step'
GRAVEL = 'minecraft:block.gravel.step'
CALCITE = 'minecraft:block.calcite.step'
GRIND = 'minecraft:block.grindstone.use'
CHIME = 'minecraft:block.amethyst_block.chime'
PLACE = 'minecraft:block.tuff.place'


def thunk(t, t0, amt=1.0):
    """A heavy settle at t0: a quick squash and a tiny rebound."""
    return amt * (bump(t, t0 - 0.02, t0 + 0.14) - 0.35 * bump(t, t0 + 0.14, t0 + 0.32))


def clips(m):
    out = Out(m)

    # --- the dormant sit: it sinks into a plain rock --------------------------------------------------------------
    sit = Clip(m, 'sit', 2.0, 'hold')
    for (bone, ch), v in {
        ('body', 'position'): (0, -3.2, 0), ('body', 'rotation'): (3, 0, 2),
        ('left_leg', 'scale'): (0.85, 0.2, 0.85), ('right_leg', 'scale'): (0.85, 0.2, 0.85),
        ('left_arm', 'rotation'): (8, -20, 12), ('left_arm', 'position'): (-1.0, -3.0, 0),
        ('left_arm', 'scale'): (1.05, 0.62, 1.1),
        ('right_arm', 'rotation'): (8, 20, -12), ('right_arm', 'position'): (1.0, -3.0, 0),
        ('right_arm', 'scale'): (1.05, 0.62, 1.1),
        ('h_head', 'position'): (0, -1.4, 0.2), ('h_head', 'rotation'): (-8, -12, 4),
        ('crystal_sprout', 'rotation'): (0, 0, -6),
        ('back_pebbles', 'position'): (0, -0.6, 0),
    }.items():
        sit.tracks[(bone, ch)] = (lambda v: lambda t: v)(v)
    m.put(sit)
    out.append(sit)
    # sinking into it / rising out of it: heavy, stone by stone (posture_set blends, plus a settle thunk)
    trans = posture_set(m, lengths={'sit_down': 1.3, 'stand_up': 1.2})
    for c in trans:
        if c.name == 'sit_down':
            S(c, 'body', lambda t: (0.06 * thunk(t, 1.05), -0.08 * thunk(t, 1.05), 0.06 * thunk(t, 1.05)))
            R(c, 'h_head', lambda t: (0, 0, 5 * osc(t, 3) * env(t, 0.2, 1.0, 0.15)))
            c.sound(0.4, GRAVEL, 0.6, 0.6)
            c.sound(1.05, PLACE, 0.7, 0.7)
        elif c.name == 'stand_up':
            R(c, 'body', lambda t: (0, 0, 4 * osc(t, 4) * env(t, 0.1, 0.9, 0.15)))
            c.sound(0.1, GRAVEL, 0.6, 0.7)
            c.sound(0.75, TUFF, 0.6, 0.9)
        out.append(c)
        m.put(c)

    # --- standing ----------------------------------------------------------------------------------------------
    # re-stack: the head stone lifts off, slides askew, its arms push it straight, it drops back with a thunk
    c = start(m, 'restack', 3.2)
    lift = lambda t: step(t, 0.2, 0.5) * (1 - step(t, 2.2, 2.4))
    askew = lambda t: step(t, 0.5, 0.9) * (1 - step(t, 1.5, 2.1))
    P(c, 'h_head', lambda t: (1.6 * askew(t), 1.4 * lift(t) - 0.4 * thunk(t, 2.42), 0))
    R(c, 'h_head', lambda t: (0, 14 * askew(t), -12 * askew(t) + 4 * osc(t, 5) * env(t, 0.9, 1.5, 0.1)))
    R(c, 'left_arm', lambda t: (150 * env(t, 1.2, 2.3, 0.35), 0, -10 * env(t, 1.2, 2.3, 0.35)))
    R(c, 'right_arm', lambda t: (150 * env(t, 1.3, 2.3, 0.35), 0, 10 * env(t, 1.3, 2.3, 0.35)))
    S(c, 'body', lambda t: (0.05 * thunk(t, 2.42), -0.07 * thunk(t, 2.42), 0.05 * thunk(t, 2.42)))
    R(c, 'crystal_sprout', lambda t: (0, 0, 10 * osc(t, 2.5) * env(t, 0.4, 2.8, 0.3)))
    c.sound(0.3, CALCITE, 0.6, 0.8)
    c.sound(1.6, GRIND, 0.15, 1.3)
    c.sound(2.42, PLACE, 0.8, 0.8)
    out.append(c)

    # a pebble tumbles off its back, it turns to look, the pebble hops back up into place
    c = start(m, 'pebble_drop', 3.6)
    fall = lambda t: step(t, 0.3, 0.75)
    back = lambda t: step(t, 2.35, 2.85)
    k = lambda t: fall(t) * (1 - back(t))
    P(c, 'back_pebbles', lambda t: (0, -4.6 * k(t) + 3.5 * bump(t, 2.35, 2.85) - 0.3 * thunk(t, 2.86), 3.2 * k(t)))
    R(c, 'back_pebbles', lambda t: (-120 * k(t) + 20 * bump(t, 0.75, 0.95), 0, 25 * k(t)))
    R(c, 'h_head', lambda t: (-10 * env(t, 0.9, 2.4, 0.3), 95 * env(t, 0.8, 2.3, 0.4), 0))
    R(c, 'body', lambda t: (0, 15 * env(t, 0.9, 2.3, 0.4), 0))
    R(c, 'right_arm', lambda t: (25 * env(t, 1.4, 2.3, 0.3), 0, 0))
    S(c, 'body', lambda t: (0.04 * thunk(t, 2.86), -0.05 * thunk(t, 2.86), 0.04 * thunk(t, 2.86)))
    c.sound(0.35, GRAVEL, 0.5, 1.3)
    c.sound(0.75, CALCITE, 0.6, 1.4)
    c.sound(2.35, CHIME, 0.5, 1.2)
    c.sound(2.86, CALCITE, 0.6, 1.1)
    out.append(c)

    # grind: the top stone grinds round a full turn, the crystal wobbling on top
    c = start(m, 'grind_top', 3.2)
    R(c, 'h_head', lambda t: (0, 360 * step(t, 0.4, 2.7), 2 * osc(t, 9) * env(t, 0.5, 2.6, 0.2)))
    P(c, 'h_head', lambda t: (0, 0.25 * env(t, 0.4, 2.7, 0.2), 0))
    R(c, 'crystal_sprout', lambda t: (0, -40 * env(t, 0.5, 2.8, 0.5), 8 * osc(t, 3) * env(t, 0.5, 2.8, 0.3)))
    R(c, 'left_arm', lambda t: (0, 0, -10 * env(t, 0.4, 2.7)))
    R(c, 'right_arm', lambda t: (0, 0, 10 * env(t, 0.4, 2.7)))
    c.sound(0.45, GRIND, 0.22, 0.6)
    c.sound(1.6, GRIND, 0.22, 0.65)
    c.sound(2.7, PLACE, 0.4, 1.0)
    out.append(c)

    # crystal hum: the crystal sprout swells and rings, the rune seam glints, the stack leans back to listen
    c = start(m, 'crystal_hum', 2.8)
    h = lambda t: env(t, 0.3, 2.4, 0.4)
    S(c, 'crystal_sprout', lambda t: ((0.28 + 0.06 * osc(t, 4)) * h(t),) * 3)
    S(c, 'rune_seam', lambda t: (0.25 * h(t), 0.4 * h(t), 0.2 * h(t)))
    R(c, 'h_head', lambda t: (10 * h(t), 0, 0))
    R(c, 'body', lambda t: (4 * h(t), 0, 0))
    R(c, 'left_arm', lambda t: (0, 0, -18 * h(t)))
    R(c, 'right_arm', lambda t: (0, 0, 18 * h(t)))
    c.sound(0.4, CHIME, 0.7, 0.9)
    c.sound(1.2, CHIME, 0.5, 1.2)
    out.append(c)

    # stillness (loop variant): a stone being a stone. Every few seconds the stack settles a hair, grit trickles
    c = start(m, 'stillness', 6.0, 'loop')
    P(c, 'body', lambda t: (0, -0.35 * step(t, 1.0, 1.15) * (1 - step(t, 5.0, 5.8)), 0))
    R(c, 'body', lambda t: (0, 0, 2.5 * step(t, 3.0, 3.12) * (1 - step(t, 5.0, 5.8))))
    R(c, 'h_head', lambda t: (0, 12 * env(t, 1.5, 4.8, 0.8), -1.5 * step(t, 3.05, 3.15) * (1 - step(t, 5.0, 5.8))))
    c.sound(1.05, GRAVEL, 0.25, 0.6)
    c.sound(3.05, CALCITE, 0.25, 0.8)
    out.append(c)

    # --- the mood pools' animal flourishes, as stone -------------------------------------------------------------
    c = start(m, 'roll_over', 2.2)            # happy: the whole stack wobbles and clacks back true
    w = lambda t: decay(t, 0.2, 1.6, 2.2)
    R(c, 'body', lambda t: (0, 0, 9 * w(t)))
    R(c, 'h_head', lambda t: (0, 0, -9 * decay(t, 0.3, 1.6, 2.0)))
    R(c, 'back_pebbles', lambda t: (0, 0, 12 * decay(t, 0.35, 1.6, 2.0)))
    R(c, 'left_arm', lambda t: (0, 0, -20 * w(t)))
    R(c, 'right_arm', lambda t: (0, 0, -20 * w(t)))
    for t0 in (0.5, 0.82, 1.15):
        c.sound(t0, CALCITE, 0.45, 1.2)
    out.append(c)

    c = start(m, 'spin', 2.0)                 # happy: a quick half-turn grind of the head stone and back
    R(c, 'h_head', lambda t: (0, 180 * step(t, 0.2, 0.9) - 180 * step(t, 1.1, 1.8), 0))
    c.sound(0.25, GRIND, 0.18, 1.0)
    c.sound(1.15, GRIND, 0.18, 1.1)
    out.append(c)

    c = start(m, 'play_bow', 2.4)             # happy: a slow, stoic bow of the whole stack
    b = lambda t: env(t, 0.3, 1.9, 0.5)
    R(c, 'body', lambda t: (-18 * b(t), 0, 0))
    R(c, 'h_head', lambda t: (-12 * b(t), 0, 0))
    R(c, 'left_arm', lambda t: (-15 * b(t), 0, 0))
    R(c, 'right_arm', lambda t: (-15 * b(t), 0, 0))
    c.sound(0.4, GRAVEL, 0.35, 0.7)
    out.append(c)

    c = start(m, 'pant', 3.0)                 # hot: slow settling breaths, stones rising and sinking
    S(c, 'body', lambda t: (0.03 * env(t, 0.1, 2.9, 0.3) * osc(t, 1.0),) * 3)
    P(c, 'h_head', lambda t: (0, 0.4 * env(t, 0.1, 2.9, 0.3) * (osc(t, 1.0, -0.1) + 1) / 2, 0))
    out.append(c)

    c = start(m, 'sneeze', 1.6)               # idle: a puff of grit, the pebbles jump
    R(c, 'h_head', lambda t: (8 * bump(t, 0.2, 0.55) - 10 * jolt(t, 0.55, 1.1), 0, 0))
    P(c, 'back_pebbles', lambda t: (0, 1.5 * bump(t, 0.6, 0.9), 0))
    S(c, 'body', lambda t: (0.04 * bump(t, 0.55, 0.8), -0.05 * bump(t, 0.55, 0.8), 0.04 * bump(t, 0.55, 0.8)))
    c.sound(0.6, GRAVEL, 0.5, 1.4)
    c.sound(0.9, CALCITE, 0.4, 1.5)
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    # petting: it rumbles like tumbling pebbles; the head stone buzzes, the crystal leans into the hand
    c = start(m, 'pet', 2.0)
    e = lambda t: env(t, 0.1, 1.85, 0.3)
    R(c, 'h_head', lambda t: (6 * e(t), 0, 3 * osc(t, 9) * e(t)))
    P(c, 'h_head', lambda t: (0, 0.4 * e(t), 0))
    R(c, 'crystal_sprout', lambda t: (12 * e(t), 0, 10 * osc(t, 1.0) * e(t)))
    P(c, 'back_pebbles', lambda t: (0, 0.25 * osc(t, 11) * e(t), 0))
    S(c, 'body', lambda t: (0.015 * osc(t, 9) * e(t),) * 3)
    out.append(c)

    c = overlay_from(m, 'pet_sit', 1.8, 'sit', 'end', ('h_head', 'crystal_sprout', 'back_pebbles'))
    e2 = lambda t: env(t, 0.1, 1.65, 0.3)
    R(c, 'h_head', lambda t: (5 * e2(t), 0, 3 * osc(t, 9) * e2(t)))
    R(c, 'crystal_sprout', lambda t: (12 * e2(t), 0, 10 * osc(t, 1.0) * e2(t)))
    P(c, 'back_pebbles', lambda t: (0, 0.25 * osc(t, 11) * e2(t), 0))
    out.append(c)

    # bond level-up: the stack bursts apart (head stone and pebbles fly up, the head spins), slams back together
    c = start(m, 'level_up', 2.8)
    up = lambda t: bump(t, 0.3, 1.5)
    P(c, 'h_head', lambda t: (0, 6 * up(t) - 0.5 * thunk(t, 1.52), 0))
    R(c, 'h_head', lambda t: (0, 360 * step(t, 0.4, 1.4), 0))
    P(c, 'back_pebbles', lambda t: (0, 4 * bump(t, 0.4, 1.45), 2.5 * bump(t, 0.4, 1.45)))
    R(c, 'back_pebbles', lambda t: (-180 * step(t, 0.45, 1.4), 0, 0))
    P(c, 'body', lambda t: (0, 1.5 * bump(t, 0.25, 0.6) - 0.6 * thunk(t, 1.55), 0))
    R(c, 'left_arm', lambda t: (160 * env(t, 0.3, 1.4, 0.25), 0, -15 * env(t, 0.3, 1.4)))
    R(c, 'right_arm', lambda t: (160 * env(t, 0.3, 1.4, 0.25), 0, 15 * env(t, 0.3, 1.4)))
    S(c, 'crystal_sprout', lambda t: (0.45 * jolt(t, 1.5, 2.7),) * 3)
    S(c, 'rune_seam', lambda t: (0.3 * jolt(t, 1.5, 2.7), 0.5 * jolt(t, 1.5, 2.7), 0.3 * jolt(t, 1.5, 2.7)))
    c.sound(0.3, CHIME, 0.6, 1.0)
    c.sound(1.52, PLACE, 0.9, 0.8)
    c.sound(1.6, CHIME, 0.8, 1.5)
    out.append(c)

    # --- dormant (sitting) variants ------------------------------------------------------------------------------
    c = start(m, 'sit_settle', 6.0, 'loop', src='sit', at=0)
    P(c, 'body', lambda t: (0, -0.3 * step(t, 2.0, 2.12) * (1 - step(t, 5.2, 5.9)), 0))
    R(c, 'h_head', lambda t: (0, 0, 2 * step(t, 2.02, 2.14) * (1 - step(t, 5.2, 5.9))))
    P(c, 'back_pebbles', lambda t: (0, -0.3 * step(t, 4.0, 4.1) * (1 - step(t, 5.3, 5.9)), 0))
    c.sound(2.0, GRAVEL, 0.25, 0.55)
    out.append(c)

    c = start(m, 'sit_crystal', 4.0, 'loop', src='sit', at=0)
    S(c, 'crystal_sprout', lambda t: (0.12 + 0.1 * osc(t, 0.5, -0.25),) * 3)
    R(c, 'crystal_sprout', lambda t: (0, 30 * osc(t, 0.25), 0))
    S(c, 'rune_seam', lambda t: (0.1, 0.2 + 0.15 * osc(t, 0.5, -0.25), 0.1))
    out.append(c)

    c = start(m, 'sit_peek', 6.0, 'loop', src='sit', at=0)
    pk = lambda t: env(t, 0.6, 4.6, 0.6)
    P(c, 'h_head', lambda t: (0, 1.3 * pk(t), 0))
    R(c, 'h_head', lambda t: (8 * pk(t), 12 + 40 * (env(t, 1.2, 2.6, 0.4) - env(t, 2.9, 4.2, 0.4)), 0))
    c.sound(0.7, GRAVEL, 0.25, 0.8)
    c.sound(4.7, PLACE, 0.3, 0.9)
    out.append(c)

    # --- sleeping variants (eyes shut) -----------------------------------------------------------------------------
    c = start(m, 'sleep_dormant', 8.0, 'loop', src='sleep')
    P(c, 'body', lambda t: (0, -0.5, 0))
    P(c, 'h_head', lambda t: (0, -0.4, 0))
    S(c, 'crystal_sprout', lambda t: (0.06 * osc(t, 0.25),) * 3)
    R(c, 'head_moss', lambda t: (0, 0, 6 * osc(t, 0.25)))
    out.append(c)

    c = start(m, 'sleep_grind', 8.0, 'loop', src='sleep')
    R(c, 'h_head', lambda t: (0, 10 * osc(t, 0.125), 0.8 * osc(t, 7) * env(t, 1.0, 3.0, 0.3)))
    P(c, 'back_pebbles', lambda t: (0, -0.25 * bump(t, 5.0, 6.5), 0))
    out.append(c)
    return out


TAGS = {
    'roll_over': {'p': 'standing'},
    'pet_sit': {'p': 'sitting'},
    'sit_settle': {'p': 'sitting'}, 'sit_crystal': {'p': 'sitting'}, 'sit_peek': {'p': 'sitting'},
    'sleep_dormant': {'p': 'lying'}, 'sleep_grind': {'p': 'lying'},
}

IDLE = {
    'standing': [
        {'clip': 'stillness', 'w': 4, 'min': 6, 'max': 18, 'cd': 12},
        {'clip': 'restack', 'w': 3, 'cd': 30},
        {'clip': 'pebble_drop', 'w': 3, 'cd': 35},
        {'clip': 'grind_top', 'w': 2, 'cd': 30, 'boost': {'night': 1.5}},
        {'clip': 'crystal_hum', 'w': 2, 'cd': 40, 'boost': {'owner_near': 1.5}},
    ],
    'sitting': [
        {'clip': 'pet_sit', 'w': 0, 'replace': True},
        {'clip': 'groom', 'w': 0, 'replace': True},
        {'clip': 'sit_settle', 'w': 4, 'min': 6, 'max': 18, 'cd': 12},
        {'clip': 'sit_crystal', 'w': 2, 'min': 8, 'max': 16, 'cd': 25, 'boost': {'night': 2}},
        {'clip': 'sit_peek', 'w': 2, 'min': 6, 'max': 6, 'cd': 30, 'boost': {'owner_near': 2}},
    ],
    'lying': [
        {'clip': 'sleep_dormant', 'w': 2, 'min': 24, 'max': 64},
        {'clip': 'sleep_grind', 'w': 1, 'min': 8, 'max': 16, 'cd': 40},
    ],
}

# a rock does not breathe much and has no ears or tail; it looks round slowly and not far
PROCEDURAL = {'breath': 0.004, 'look_yaw': 35, 'look_pitch': 15}
