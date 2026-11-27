"""Cinder Imp (tameable hostile: a mischievous fire imp).

Phase 2, two modes. Wild: a hunched, menacing idle (claws flexing, tail lashing, the tail flame flaring, head
tracking), a crouched creeping stalk toward a far target, a threat display (arms flung wide, claws splayed, flame
flare, cackle) the moment it picks a target, a cackle-and-crouch, and a telegraphed leap (it coils for 0.35 s before it
springs; PetSkills delays the jump to match). Tamed: warming its hands, juggling a flame, tail flicks, claw licking;
sits and sleeps curled around its tail flame."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import Clip, keys, pulse, wave  # noqa: E402
from _p2kit import keep, copy_from, env, frozen, pair, pos, rot, scl  # noqa: E402
import _p2hostile  # noqa: E402

CLAWS_R = ('right_claw_0_r', 'right_claw_1_r', 'right_claw_2_r')
CLAWS_L = ('left_claw_0_r', 'left_claw_1_r', 'left_claw_2_r')


def claws(c, fn):
    for b in CLAWS_R + CLAWS_L:
        rot(c, b, lambda t: (fn(t), 0, 0))


def flame(c, fn):
    scl(c, 'glow_flame', lambda t: (fn(t), fn(t) * 1.3, fn(t)))


def clips(m):
    out = []
    idle, walk, sleep = frozen(m, 'idle'), frozen(m, 'walk'), frozen(m, 'sleep')

    # ---- wild idle: hunched, claws flexing, tail lashing, flame flaring, head tracking
    L = 4.0
    c = Clip(m, 'idle_wild', L, 'loop')
    copy_from(c, idle, loop=True, speed=3.0 / L * 1.0)
    rot(c, 'body', lambda t: (-16 + 2 * wave(t, L, 2), 0, 3 * wave(t, L, 1)))
    pos(c, 'body', lambda t: (0, -0.6 + 0.3 * wave(t, L, 4), -0.5))
    rot(c, 'h_head', lambda t: (14, keys(t, [(0, 0), (0.8, 0), (1.1, 26), (2.0, 26), (2.3, -20), (3.4, -20), (3.7, 0), (4, 0)]),
                                6 * wave(t, L, 1, 0.3)))
    pair(c, 'right_arm', 'left_arm', lambda t: (28 + 4 * wave(t, L, 2, 0.2), 0, 12))
    claws(c, lambda t: 22 * pulse(t % 1.0, 0.1, 0.6))
    rot(c, 'tail', lambda t: (8, 26 * wave(t, L, 3), 0))
    rot(c, 'tail_tip', lambda t: (0, 30 * wave(t, L, 3, -0.12), 0))
    flame(c, lambda t: 0.25 * pulse(t % 2.0, 0.2, 0.7))
    out.append(keep(m, c))

    # ---- stalk: crouched creep (walk legs, slowed), arms forward, head low and level, tail lashing
    sp = 0.45 / 0.95
    c = Clip(m, 'stalk', 0.95, 'loop')
    copy_from(c, walk, speed=sp)
    rot(c, 'body', lambda t: (-22, 0, 2 * wave(t, 0.95, 1)))
    pos(c, 'body', lambda t: (0, -0.8, -0.6))
    rot(c, 'h_head', lambda t: (18, 0, 0))
    pair(c, 'right_arm', 'left_arm', lambda t: (34, 0, 10))
    claws(c, lambda t: 16)
    rot(c, 'tail', lambda t: (10, 22 * wave(t, 0.95, 1), 0))
    rot(c, 'tail_tip', lambda t: (0, 26 * wave(t, 0.95, 1, -0.15), 0))
    out.append(keep(m, c))

    # ---- threat (overlay): arms flung wide, claws splayed, head jut, flame flare, cackle
    c = Clip(m, 'threat', 1.4)
    e = lambda t: keys(t, [(0, 0), (0.15, -0.15), (0.35, 1), (1.0, 1), (1.4, 0)])
    pair(c, 'right_arm', 'left_arm', lambda t: (70 * e(t), 0, 44 * e(t)))
    claws(c, lambda t: -26 * e(t))
    rot(c, 'h_head', lambda t: (-10 * e(t), 0, 10 * wave(t, 0.3, 1) * env(t, 0.4, 0.5, 0.9, 1.0)))
    rot(c, 'tail', lambda t: (34 * e(t), 0, 0))
    flame(c, lambda t: 0.6 * e(t))
    for b in ('right_ear_r', 'left_ear_r'):
        rot(c, b, lambda t: (-20 * e(t), 0, 0))
    c.sound(0.3, 'ambient_angry', 1.0, 1.0)
    c.sound(0.35, 'minecraft:item.firecharge.use', 0.5, 1.3)
    out.append(keep(m, c))

    # ---- telegraphed leap (additive accent): coil 0.35 s, spring, tuck, land
    c = Clip(m, 'leap', 1.1)
    k = lambda pts: (lambda t: keys(t, pts))
    rot(c, 'body', lambda t: (keys(t, [(0, 0), (0.3, -24), (0.42, 14), (0.75, -6), (1.1, 0)]), 0, 0))
    pos(c, 'body', lambda t: (0, keys(t, [(0, 0), (0.3, -2.2), (0.42, 1.2), (1.1, 0)]), 0))
    pair(c, 'right_arm', 'left_arm', lambda t: (keys(t, [(0, 0), (0.3, -40), (0.45, 70), (0.8, 40), (1.1, 0)]), 0,
                                                keys(t, [(0, 0), (0.3, 10), (0.6, 25), (1.1, 0)])))
    pair(c, 'right_leg', 'left_leg', lambda t: (keys(t, [(0, 0), (0.3, 40), (0.45, -30), (0.75, 30), (1.1, 0)]), 0, 0))
    pair(c, 'right_shin', 'left_shin', lambda t: (keys(t, [(0, 0), (0.3, -55), (0.45, 0), (0.75, -40), (1.1, 0)]), 0, 0))
    rot(c, 'tail', lambda t: (keys(t, [(0, 0), (0.3, 20), (0.5, -20), (1.1, 0)]), 0, 0))
    claws(c, k([(0, 0), (0.3, -20), (0.5, -30), (1.1, 0)]))
    flame(c, lambda t: 0.5 * pulse(t, 0.2, 0.9))
    c.sound(0.1, 'minecraft:entity.blaze.burn', 0.5, 1.6)
    c.sound(0.36, 'minecraft:entity.goat.long_jump', 0.6, 1.5)
    out.append(keep(m, c))

    # ---- tamed: warming its hands (standing loop variant)
    L = 5.0
    c = Clip(m, 'warm_hands', L, 'loop')
    copy_from(c, idle, loop=True, speed=3.0 / L)
    rub = lambda t: wave(t, 0.5, 1)
    pair(c, 'right_arm', 'left_arm', lambda t: (58, 0, -14 + 6 * rub(t)))
    claws(c, lambda t: 20)
    rot(c, 'h_head', lambda t: (-10, 0, 8 * wave(t, L, 1)))
    rot(c, 'tail', lambda t: (0, 12 * wave(t, L, 1), 0))
    flame(c, lambda t: 0.1 * wave(t, 1.0, 1))
    c.sound(1.0, 'minecraft:block.fire.ambient', 0.4, 1.4)
    out.append(keep(m, c))

    # ---- tamed: juggling a flame hand to hand (one-shot)
    c = Clip(m, 'flame_juggle', 3.2)
    copy_from(c, idle, loop=True)
    toss_r = lambda t: sum(pulse(t, 0.3 + i * 0.8, 0.7 + i * 0.8) for i in range(3))
    toss_l = lambda t: sum(pulse(t, 0.7 + i * 0.8, 1.1 + i * 0.8) for i in range(3))
    rot(c, 'right_arm', lambda t: (50 * env(t, 0, 0.3, 2.8, 3.2) + 50 * toss_r(t), 0, -10))
    rot(c, 'left_arm', lambda t: (50 * env(t, 0, 0.3, 2.8, 3.2) + 50 * toss_l(t), 0, 10))
    rot(c, 'h_head', lambda t: (-18 * (toss_r(t) + toss_l(t)) * env(t, 0.2, 0.4, 2.7, 3.0) - 10 * env(t, 0, 0.3, 2.8, 3.2),
                                12 * wave(t, 1.6, 1) * env(t, 0.2, 0.4, 2.7, 3.0), 0))
    pos(c, 'body', lambda t: (0, 0.5 * (toss_r(t) + toss_l(t)), 0))
    rot(c, 'tail', lambda t: (0, 16 * wave(t, 0.8, 1), 0))
    flame(c, lambda t: 0.3 * (toss_r(t) + toss_l(t)))
    for i in range(3):
        c.sound(0.45 + i * 0.8, 'minecraft:item.firecharge.use', 0.25, 1.8)
    c.sound(2.9, 'happy', 0.7, 1.1)
    out.append(keep(m, c))

    # ---- tail flick (overlay, any posture): a quick whip and a flame puff
    c = Clip(m, 'tail_flick', 1.2)
    rot(c, 'tail', lambda t: (keys(t, [(0, 0), (0.15, 20), (0.4, -6), (1.2, 0)]), 30 * wave(t, 0.5, 1) * env(t, 0, 0.1, 0.8, 1.1), 0))
    rot(c, 'tail_tip', lambda t: (0, 34 * wave(t, 0.5, 1, -0.12) * env(t, 0.05, 0.15, 0.85, 1.15), 0))
    flame(c, lambda t: 0.45 * pulse(t, 0.1, 0.7))
    c.sound(0.15, 'minecraft:block.fire.ambient', 0.4, 1.6)
    out.append(keep(m, c))

    # ---- claw licking (overlay: standing and sitting)
    c = Clip(m, 'claw_lick', 2.6)
    e2 = lambda t: env(t, 0, 0.4, 2.1, 2.6)
    lick = lambda t: wave(t, 0.3, 1) * env(t, 0.5, 0.6, 1.9, 2.1)
    rot(c, 'right_arm', lambda t: (88 * e2(t), 0, -28 * e2(t)))
    for b in CLAWS_R:
        rot(c, b, lambda t: (24 * e2(t), 0, 0))
    rot(c, 'h_head', lambda t: (-16 * e2(t) + 6 * lick(t), -14 * e2(t), 0))
    out.append(keep(m, c))

    # ---- curled sleep around its tail flame (lying variant)
    L = 4.0
    c = Clip(m, 'sleep_curl', L, 'loop')
    copy_from(c, sleep, loop=True, speed=3.0 / L)
    rot(c, 'tail', lambda t: (0, 80, 0))
    rot(c, 'tail_tip', lambda t: (0, 60, 0))
    rot(c, 'h_head', lambda t: (-14, -20, 0))
    pos(c, 'body', lambda t: (0, 0.2 * wave(t, L, 1), 0))
    flame(c, lambda t: -0.25 + 0.08 * wave(t, L, 1))
    out.append(keep(m, c))
    import _p2kit
    _p2kit.reactions(m, out, pet='tail_flick', pet_sit='tail_flick', bond_level='flame_juggle')
    return out


TAGS = dict(_p2hostile.TAGS, warm_hands={'p': 'standing'}, flame_juggle={'p': 'standing'},
            tail_flick={'layer': 'overlay', 'move': True}, claw_lick={'layer': 'overlay', 'move': False},
            sleep_curl={'p': 'lying'}, leap={'layer': 'additive', 'move': True, 'p': 'airborne'})
IDLE, STATES = _p2hostile.tables(
    wild=[('tail_flick', 2, 10), ('threat', 1, 30, 'player_near:12'), ('look_around', 1, 16)],
    tame=[('warm_hands', 3, (6, 14), ''), ('flame_juggle', 2, 24), ('tail_flick', 2, 10), ('claw_lick', 2, 20),
          ('hop', 1, 30, 'happy'), ('spin', 1, 30, 'happy')],
    sitting=[{'clip': '@sit', 'w': 6, 'min': 10, 'max': 24}, {'clip': 'tail_flick', 'w': 3, 'cd': 10},
             {'clip': 'claw_lick', 'w': 2, 'cd': 20}],
    lying=[{'clip': '@sleep', 'w': 3, 'min': 30, 'max': 90}, {'clip': 'sleep_curl', 'w': 3, 'min': 30, 'max': 90}])
PROCEDURAL = {'tail_sway': 6}

TAGS['pet_sit'] = {'layer': 'overlay', 'move': False}   # the sitting petting reaction layers over the held sit
