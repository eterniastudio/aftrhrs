"""Mossbun (phase 2): a chibi lop bunny with a glowing blossom sprout. Bunny life: nose wiggles (the whole little head
quivers), lop-ear flops, binkies (the twisting joy leap), face grooming on its haunches, a warning thump, the blossom
twirling. Sitting = its loaf; lying = asleep, or the bunny flop onto its side."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import decay  # noqa: E402
from _smalla_kit import Out, P, R, S, bump, env, jolt, osc, overlay_from, start, step  # noqa: E402

HOP = 'minecraft:entity.rabbit.jump'
MOSS = 'minecraft:block.moss.step'
LEAF = 'minecraft:block.azalea_leaves.step'
SPORE = 'minecraft:happy_villager'
FRONT = ('left_front_leg', 'right_front_leg')
HIND = ('left_hind_leg', 'right_hind_leg')


def nose(c, e, amp=3.0):
    """The bunny nose wiggle: a fast little quiver of the head (it has no nose bone)."""
    R(c, 'h_head', lambda t: (amp * osc(t, 7.0) * e(t), 0, 0))
    P(c, 'h_head', lambda t: (0, 0.12 * osc(t, 14.0) * e(t), 0))


def clips(m):
    out = Out(m)

    # --- standing ----------------------------------------------------------------------------------------------
    c = start(m, 'nose_wiggle', 1.6, src='idle', at=0, bones=['h_head', 'h_left_ear', 'h_right_ear'])
    nose(c, lambda t: env(t, 0.1, 0.7, 0.08) + env(t, 0.9, 1.45, 0.08))
    R(c, 'h_left_ear', lambda t: (0, 0, -8 * bump(t, 0.5, 0.8)))
    out.append(c)

    # ear flop: one lop ear flicks up and flops down with a bounce, then the other
    c = start(m, 'ear_flop', 2.2, src='idle', at=0, bones=['h_head', 'h_left_ear', 'h_right_ear'])
    R(c, 'h_left_ear', lambda t: (0, 0, -55 * jolt(t, 0.15, 0.55) + 10 * decay(t, 0.55, 3.0, 4.0)))
    R(c, 'h_right_ear', lambda t: (0, 0, 55 * jolt(t, 0.95, 1.35) - 10 * decay(t, 1.35, 3.0, 4.0)))
    R(c, 'h_head', lambda t: (0, 0, 8 * bump(t, 0.1, 0.6) - 8 * bump(t, 0.9, 1.4)))
    out.append(c)

    # binky: crouch, leap with a mid-air twist and a hind kick, land soft and give a little head shake
    c = start(m, 'binky', 1.8)
    air = lambda t: bump(t, 0.3, 0.95)
    crouch = lambda t: bump(t, 0.05, 0.35) + 0.6 * bump(t, 0.9, 1.15)
    P(c, 'root', lambda t: (0, 7 * air(t), 0))
    R(c, 'root', lambda t: (0, 35 * osc(t, 1.55, 0) * env(t, 0.32, 0.95, 0.15), 0))
    R(c, 'body', lambda t: (10 * bump(t, 0.3, 0.6) - 8 * bump(t, 0.55, 0.95), 0, 25 * bump(t, 0.4, 0.9)))
    S(c, 'body', lambda t: (0.08 * crouch(t), -0.1 * crouch(t) + 0.06 * air(t), 0.08 * crouch(t)))
    for leg in HIND:
        R(c, leg, lambda t: (-55 * bump(t, 0.45, 0.85), 0, 0))
    for leg in FRONT:
        R(c, leg, lambda t: (35 * bump(t, 0.35, 0.8), 0, 0))
    R(c, 'h_left_ear', lambda t: (0, 0, -30 * air(t) + 8 * decay(t, 0.95, 3.0, 4.0)))
    R(c, 'h_right_ear', lambda t: (0, 0, 30 * air(t) - 8 * decay(t, 0.95, 3.0, 4.0)))
    R(c, 'h_head', lambda t: (0, 0, 14 * osc(t, 4.0) * env(t, 1.15, 1.7, 0.1)))
    R(c, 'h_blossom', lambda t: (0, 180 * step(t, 0.3, 1.0), 0))
    c.sound(0.3, HOP, 0.4, 1.4)
    c.sound(0.95, MOSS, 0.6, 1.3)
    out.append(c)

    # face groom: rises onto its haunches, front paws rub its face, pulls an ear down to wash it
    c = start(m, 'face_groom', 3.4)
    up = lambda t: env(t, 0.2, 3.1, 0.35)
    R(c, 'body', lambda t: (22 * up(t), 0, 0))
    P(c, 'body', lambda t: (0, 0.5 * up(t), 0.5 * up(t)))
    for leg, s in (('left_front_leg', 1), ('right_front_leg', -1)):
        R(c, leg, lambda t, s=s: (-70 * up(t) + 18 * osc(t, 3.0, 0.25 * s) * env(t, 0.6, 2.0, 0.15), 0, s * 10 * up(t)))
    R(c, 'h_head', lambda t: (-18 * up(t) + 5 * osc(t, 3.0) * env(t, 0.6, 2.0, 0.15), 0, 14 * env(t, 2.0, 2.9, 0.2)))
    R(c, 'h_left_ear', lambda t: (-30 * env(t, 2.0, 2.9, 0.2), 0, 20 * env(t, 2.0, 2.9, 0.2)))
    for leg in HIND:
        R(c, leg, lambda t: (-20 * up(t), 0, 0))
    out.append(c)

    # thump: hears something, sits up alert, one big hind-foot thump, ears up
    c = start(m, 'thump', 2.0)
    al = lambda t: env(t, 0.1, 1.8, 0.25)
    R(c, 'body', lambda t: (8 * al(t) - 6 * bump(t, 0.95, 1.1), 0, 0))
    R(c, 'h_head', lambda t: (10 * al(t), 25 * env(t, 0.2, 0.9, 0.2), 0))
    R(c, 'h_left_ear', lambda t: (-20 * al(t), 0, -25 * al(t)))
    R(c, 'h_right_ear', lambda t: (-20 * al(t), 0, 25 * al(t)))
    for leg in HIND:
        R(c, leg, lambda t: (-30 * bump(t, 0.75, 0.97) + 8 * bump(t, 0.97, 1.15), 0, 0))
    c.sound(0.98, 'minecraft:block.rooted_dirt.step', 0.8, 0.7)
    out.append(c)

    # blossom twirl: the sprout leans and the glowing blossom spins round, shedding a sparkle (overlay)
    c = start(m, 'blossom_twirl', 2.4, src='idle', at=0, bones=['h_sprout', 'h_blossom'])
    R(c, 'h_sprout', lambda t: (0, 0, 14 * osc(t, 1.0) * env(t, 0.1, 2.2)))
    R(c, 'h_blossom', lambda t: (0, 360 * step(t, 0.3, 2.0), 0))
    S(c, 'h_blossom', lambda t: (0.2 * bump(t, 0.6, 1.8),) * 3)
    c.particle(1.0, SPORE, 2, 0.2, 0.0, 1.2)
    out.append(c)

    # snuffling the ground (loop variant): head down, nose busy, a hop forward-and-back every so often
    c = start(m, 'graze_snuffle', 6.0, 'loop')
    dn = lambda t: 1.0
    R(c, 'h_head', lambda t: (-22 + 8 * bump(t, 2.8, 3.6), 14 * osc(t, 1 / 3.0), 0))
    nose(c, lambda t: (env(t, 0.2, 1.4) + env(t, 3.8, 5.2)), 3.5)
    R(c, 'body', lambda t: (-6, 0, 0))
    S(c, 'body', lambda t: (0.04 * bump(t, 2.7, 3.0), -0.05 * bump(t, 2.7, 3.0), 0.04 * bump(t, 2.7, 3.0)))
    R(c, 'h_left_ear', lambda t: (10, 0, 0))
    R(c, 'h_right_ear', lambda t: (10, 0, 0))
    c.sound(1.0, MOSS, 0.3, 1.5)
    c.sound(4.5, MOSS, 0.3, 1.6)
    out.append(c)

    # --- reactions -----------------------------------------------------------------------------------------------
    c = start(m, 'pet', 2.0)
    e = lambda t: env(t, 0.1, 1.85, 0.3)
    S(c, 'body', lambda t: (0.08 * e(t), -0.08 * e(t), 0.08 * e(t)))
    P(c, 'body', lambda t: (0, -0.4 * e(t), 0))
    R(c, 'h_head', lambda t: (-8 * e(t), 0, 6 * osc(t, 0.8) * e(t)))
    R(c, 'h_left_ear', lambda t: (30 * e(t), 0, 10 * e(t)))
    R(c, 'h_right_ear', lambda t: (30 * e(t), 0, -10 * e(t)))
    S(c, 'h_blossom', lambda t: (0.25 * e(t),) * 3)
    c.particle(0.7, SPORE, 3, 0.25, 0.0, 1.1)
    out.append(c)

    c = overlay_from(m, 'pet_sit', 1.8, 'sit', 'end', ('h_head', 'h_left_ear', 'h_right_ear', 'h_blossom'))
    e2 = lambda t: env(t, 0.1, 1.65, 0.3)
    R(c, 'h_head', lambda t: (-8 * e2(t), 0, 6 * osc(t, 0.8) * e2(t)))
    R(c, 'h_left_ear', lambda t: (30 * e2(t), 0, 10 * e2(t)))
    R(c, 'h_right_ear', lambda t: (30 * e2(t), 0, -10 * e2(t)))
    S(c, 'h_blossom', lambda t: (0.25 * e2(t),) * 3)
    out.append(c)

    # bond level-up: two binkies in a row and the blossom bursts into full bloom
    c = start(m, 'level_up', 2.8)
    air = lambda t: bump(t, 0.2, 0.8) + 0.85 * bump(t, 1.0, 1.6)
    P(c, 'root', lambda t: (0, 7 * air(t), 0))
    R(c, 'root', lambda t: (0, 40 * osc(t, 1.7) * env(t, 0.2, 1.6, 0.15), 0))
    R(c, 'body', lambda t: (0, 0, 22 * (bump(t, 0.3, 0.75) - bump(t, 1.1, 1.55))))
    for leg in HIND:
        R(c, leg, lambda t: (-50 * (bump(t, 0.35, 0.75) + bump(t, 1.15, 1.55)), 0, 0))
    S(c, 'h_blossom', lambda t: (0.5 * jolt(t, 1.6, 2.75),) * 3)
    R(c, 'h_blossom', lambda t: (0, 360 * step(t, 1.6, 2.6), 0))
    R(c, 'h_left_ear', lambda t: (0, 0, -30 * air(t)))
    R(c, 'h_right_ear', lambda t: (0, 0, 30 * air(t)))
    c.particle(1.7, SPORE, 10, 0.5, 0.05, 1.0)
    c.sound(0.2, HOP, 0.5, 1.4)
    c.sound(1.0, HOP, 0.5, 1.55)
    c.sound(1.7, 'minecraft:block.azalea.place', 0.8, 1.3)
    out.append(c)

    # --- sitting (loaf) variants -------------------------------------------------------------------------------
    c = start(m, 'sit_nose', 4.0, 'loop', src='sit', at='end')
    nose(c, lambda t: env(t, 0.3, 1.3, 0.1) + env(t, 2.4, 3.4, 0.1))
    R(c, 'h_left_ear', lambda t: (0, 0, -10 * bump(t, 1.6, 2.0)))
    out.append(c)

    c = start(m, 'sit_groom', 4.0, 'loop', src='sit', at='end')
    g = lambda t: env(t, 0.4, 3.6, 0.4)
    for leg, s in (('left_front_leg', 1), ('right_front_leg', -1)):
        R(c, leg, lambda t, s=s: (-55 * g(t) + 15 * osc(t, 2.5, 0.25 * s) * g(t), 0, s * 8 * g(t)))
    R(c, 'h_head', lambda t: (-14 * g(t) + 4 * osc(t, 2.5) * g(t), 0, 0))
    R(c, 'body', lambda t: (10 * g(t), 0, 0))
    out.append(c)

    c = start(m, 'sit_melt', 6.0, 'loop', src='sit', at='end')
    ml = lambda t: env(t, 0.5, 5.5, 0.8)
    S(c, 'body', lambda t: (0.06 * ml(t), -0.06 * ml(t), 0.06 * ml(t)))
    P(c, 'h_head', lambda t: (0, -0.5 * ml(t), 0))
    R(c, 'h_left_ear', lambda t: (10 * ml(t), 0, 8 * ml(t)))
    R(c, 'h_right_ear', lambda t: (10 * ml(t), 0, -8 * ml(t)))
    out.append(c)

    # --- lying variants ------------------------------------------------------------------------------------------
    c = start(m, 'sleep_flop', 6.0, 'loop', src='sleep')    # the bunny flop: tipped onto its side, hind legs out
    R(c, 'body', lambda t: (0, 0, 62))
    P(c, 'body', lambda t: (1.2, -0.6, 0))
    for leg in HIND:
        R(c, leg, lambda t: (-35, 0, 0))
    S(c, 'body', lambda t: (0.02 * osc(t, 0.5),) * 3)
    out.append(c)

    c = start(m, 'sleep_twitch', 6.0, 'loop', src='sleep')
    nose(c, lambda t: env(t, 1.0, 1.8, 0.1) + env(t, 4.0, 4.6, 0.1), 2.0)
    R(c, 'h_right_ear', lambda t: (0, 0, 12 * bump(t, 2.6, 2.9)))
    for leg in HIND:
        R(c, leg, lambda t: (-12 * osc(t, 8) * env(t, 3.2, 3.7, 0.1), 0, 0))
    out.append(c)
    return out


TAGS = {
    'pet_sit': {'p': 'sitting'},
    'sit_nose': {'p': 'sitting'}, 'sit_groom': {'p': 'sitting'}, 'sit_melt': {'p': 'sitting'},
    'sleep_flop': {'p': 'lying'}, 'sleep_twitch': {'p': 'lying'},
}

IDLE = {
    'standing': [
        {'clip': 'nose_wiggle', 'w': 4, 'cd': 8},
        {'clip': 'ear_flop', 'w': 3, 'cd': 15},
        {'clip': 'binky', 'w': 2, 'cd': 25, 'boost': {'happy': 3, 'owner_near': 1.5, 'night': 0.5}},
        {'clip': 'face_groom', 'w': 2, 'cd': 30, 'boost': {'rain': 2}},
        {'clip': 'thump', 'w': 1, 'cd': 40, 'boost': {'monster_near': 6}},
        {'clip': 'blossom_twirl', 'w': 2, 'cd': 25, 'boost': {'day': 1.5}},
        {'clip': 'graze_snuffle', 'w': 3, 'min': 6, 'max': 18, 'cd': 15},
    ],
    'sitting': [
        {'clip': 'pet_sit', 'w': 0, 'replace': True},
        {'clip': 'sit_nose', 'w': 3, 'min': 4, 'max': 12, 'cd': 12},
        {'clip': 'sit_groom', 'w': 2, 'min': 4, 'max': 8, 'cd': 25},
        {'clip': 'sit_melt', 'w': 2, 'min': 6, 'max': 18, 'cd': 25, 'boost': {'night': 2}},
    ],
    'lying': [
        {'clip': 'sleep_flop', 'w': 2, 'min': 18, 'max': 48},
        {'clip': 'sleep_twitch', 'w': 2, 'min': 6, 'max': 12, 'cd': 30},
    ],
}

PROCEDURAL = {'ear_twitch': 1.4, 'tail_sway': 3, 'breath': 0.016}
